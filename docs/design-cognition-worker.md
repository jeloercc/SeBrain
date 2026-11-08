# Diseño: `CognitionWorker` por lotes troceados

**Estado: Propuesta — no implementada.** Nada de esto está en la app. El esquema de la BD que aparece aquí también es una propuesta (regla de CLAUDE.md). La jerarquía de claves no cambia: un ECDH con `K_inbox` por segmento.

**Base empírica:** `docs/benchmarks.md`, sección «`CognitionWorker`: ECDH frente al tiempo total por segmento». Pixel 10, Gemma-4-E2B en NPU, LLM entre 6 y 40 s por segmento.

## 1. Lotes de 25 segmentos sin FGS

**Por qué sin FGS:** Android no permite iniciar un FGS desde segundo plano. El segundo Worker de una cadena falló con `ForegroundServiceStartNotAllowedException`. `CognitionWorker` se ejecuta con el teléfono en reposo, así que no puede contar con un FGS.

**Restricción:** un Worker normal tiene una ventana de ejecución de unos 10 min. Al agotarse, el sistema lo detiene con `STOP_REASON_TIMEOUT`.

**Diseño:**

- Cada ejecución procesa como mucho **25 segmentos** y además tiene un **presupuesto de tiempo de 8 min**. Termina con lo primero que se agote.
  - Con la media del lote de 10 (11,8 s por segmento), 25 segmentos son unos 5 min.
  - Con la media del lote de 100 (20,3 s), 25 segmentos son unos 8,5 min.
  - Con el p95 (32,5 s), 25 segmentos son unos 13,5 min y no caben.
  - Por eso el presupuesto de tiempo es obligatorio y no basta con contar segmentos.
- Antes de empezar un segmento se comprueba:
  - `elapsed + p95_estimado < 8 min`, donde `p95_estimado` es el p95 móvil de los últimos segmentos (valor inicial 35 s).
  - Si no cabe, se guarda el checkpoint y se devuelve `Result.success()`.
- **Restricciones de WorkManager:** cargando, batería no baja y `requiresDeviceIdle` para lotes grandes, como dice CLAUDE.md.
- **Encadenado:** si al terminar quedan segmentos en el inbox, el Worker encola el siguiente con `enqueueUniqueWork("cognition", KEEP, …)`. `KEEP` evita que dos Workers se ejecuten a la vez.
  - Un Worker único no impide que un bucle bloqueante siga corriendo tras un `REPLACE`; eso ya ocurrió en el spike.
  - Por eso nunca se usa `REPLACE` y se comprueba `isStopped` (sección 2).
- **Motor:** se carga una vez por ejecución (3–5,7 s medidos) y se libera en `finally`. Si la carga cuesta 5 s y el lote dura 5–8 min, la carga supone alrededor del 1–2 % del lote.

## 2. Checkpoint por segmento e `isStopped`

**Unidad de trabajo:** un segmento. Su resultado se confirma en su propia transacción:

```text
por segmento:
  1. ECDH(K_inbox, E_seg) → HKDF → K_seg → descifrar        (≈ 0,1 s)
  2. chunking + extracción LLM (con 1 reintento, §4)         (≈ 6–40 s)
  3. embeddings de los chunks
  4. BEGIN; INSERT capture, chunk, chunk_fts, chunk_vec; COMMIT
  5. borrar el fichero del segmento (solo tras COMMIT)
  6. poner a cero K_seg, Z y el texto en claro
```

**El checkpoint es el propio inbox.** Un segmento cuyo `COMMIT` se completó ya no existe en el inbox, y uno que no llegó al `COMMIT` sigue ahí. Al retomar, el Worker toma los segmentos que quedan, en orden de captura. No hace falta un fichero de estado aparte, y cualquier corte deja la bóveda en un estado consistente.

**Idempotencia** si el proceso muere entre el paso 4 y el 5:

- Se propone añadir a `capture` una columna `segment_id` (UUID del segmento, guardado en su cabecera autenticada) con restricción `UNIQUE`. Requiere aprobación porque cambia el esquema.
- Al retomar, si `segment_id` ya existe se salta la extracción y se borra el fichero.

**`isStopped`:**

- Se comprueba antes de cada segmento y entre los pasos 2 y 3. El paso 2 es el más largo.
- Si `isStopped` es verdadero:
  - Se cancela la generación en curso, si la hay, con `Conversation.cancelProcess()`.
  - No se hace `COMMIT` del segmento a medias. Se pierde como mucho el trabajo del LLM de un segmento.
  - Se registra `stopReason` (por ejemplo `STOP_REASON_TIMEOUT`, `CONSTRAINT_CHARGING` o `DEVICE_STATE`).
  - Se libera el motor y se sale.
- No hay checkpoint parcial dentro de un segmento. Guardar la salida del LLM a medias supondría escribir datos derivados en disco fuera de la bóveda.

## 3. Prompt con la fecha de captura

- La ingesta guarda `captured_at` (instante UTC más zona horaria del dispositivo) **dentro del texto en claro cifrado** del segmento, no en la cabecera, para no exponer metadatos en disco.
- El prompt de extracción la recibe como referencia:

```text
Fecha y hora de captura: 2026-11-07T21:24:00-06:00 (sábado).
Resuelve las fechas relativas ("mañana", "el jueves", "el 14") respecto a esa fecha.
Si el texto no da el año, usa el de la fecha de captura o el siguiente si la fecha ya pasó.
Si no se puede resolver, deja la fecha como texto literal en lugar de inventarla.
```

- **Motivo:** en el spike, el segmento 0 («el jueves 14») salió como `2024-05-15T10:30:00`, una fecha inventada.

## 4. Validación de esquema y reintento

**Un resultado es válido si se cumple todo esto:**

- JSON parseable.
- Están todas las claves `required`.
- Cada lista es una lista de strings.
- `titulo` no queda vacío después de quitar espacios, comillas y `:`. En el spike salió `": "`.

**Flujo:**

```text
intento 1 → válido → seguir
          → inválido → intento 2 (reintento único)
                      → válido → seguir
                      → inválido → marcar "extracción fallida"
```

**El reintento no puede repetir la misma configuración.** Con muestreo determinista, la segunda salida sería idéntica a la primera. El reintento usa:

- `maxOutputToken` al doble;
- `ThinkingConfig(false)`, igual que el primer intento;
- un mensaje adicional: «La respuesta anterior no era JSON válido o tenía el título vacío. Devuelve solo el objeto JSON».

Las salidas inválidas del spike de 256 tokens apuntan a que la salida se truncó. El benchmark con 512 tokens dirá si el doble basta.

**«Extracción fallida» no bloquea el lote:**

- El segmento se descifra, se trocea y sus chunks se insertan igualmente con embeddings y FTS5, pero sin campos extraídos. Así se puede encontrar por búsqueda aunque la extracción haya fallado.
- `capture.extraction_status` toma uno de estos valores: `ok`, `ok_retry` o `failed`. Es una propuesta de esquema.
- Se guardan `extraction_attempts` y el código de fallo (`json_invalid`, `title_empty`, `truncated`, `engine_error`), nunca la salida en claro fuera de la bóveda.
- El segmento se borra del inbox tras el `COMMIT`, como cualquier otro.
- Un ajuste futuro permitiría reintentar los `failed` con otro modelo. Fuera de alcance.
- Un error del motor (`LiteRtLmJniException`) no cuenta como fallo de extracción:
  - Se descarta el segmento en curso sin `COMMIT`.
  - Se reinicia el motor una vez.
  - Si vuelve a fallar, el Worker termina con `Result.retry()`.

## 5. Métricas que debe registrar el Worker real

Por ejecución, en memoria y sin escribir datos de contenido:

- Número de segmentos y la causa del fin: presupuesto, límite de 25, `isStopped` o inbox vacío.
- Media y p95 del LLM.
- Número de `ok`, `ok_retry` y `failed`.
- `thermalStatus` máximo.

Sirven para ajustar el presupuesto de 8 min y el límite de 25.

## Pendiente de aprobación

1. Columnas `capture.segment_id` (`UNIQUE`), `capture.extraction_status` y `capture.extraction_attempts` (cambio de esquema).
2. Que `captured_at` vaya dentro del texto en claro cifrado del segmento (formato de segmento v1).
3. Presupuesto de 8 min por ejecución y p95 móvil inicial de 35 s.
