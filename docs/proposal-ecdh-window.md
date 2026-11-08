# Propuesta: clave efímera por ventana de tiempo + HKDF por segmento

**Estado: Rechazada (2026-11-07).** Se mantiene un ECDH por segmento. No hay nada implementado y CLAUDE.md no cambia.

**Motivo:** el criterio era adoptarla solo si ECDH supera el 5 % del tiempo total por segmento. En `CognitionWorker` medido en el Pixel 10 (detalle en `docs/benchmarks.md`), ECDH quedó muy por debajo:

| Lote | ECDH media | Total/seg media | ECDH % del total |
| --- | --- | --- | --- |
| 10 | 91,8 ms | 11 817 ms | 0,777 % |
| 100 | 100,2 ms | 20 311 ms | 0,493 % |

El máximo en un segmento individual fue 1,45 %. El LLM ocupa más del 99 % del tiempo. La propuesta ahorraría como mucho ese < 1 %, y a cambio empeoraría el secreto hacia adelante dentro de la ventana (riesgo 1).

**Reabrir solo si** cambia el reparto de costes, por ejemplo si muchos segmentos dejan de pasar por el LLM o el LLM baja a menos de 2 s por segmento.

El resto del documento se conserva como registro del análisis.

## Problema

Hoy cada segmento lleva su propia efímera, y descifrarlo exige un ECDH con `K_inbox` en StrongBox.

| Operación | Medido en Pixel 10 (2026-11-07) |
| --- | --- |
| ECDH con privada en StrongBox | ≈ 44 ms (rango 40–53 ms) |
| ECDH y keygen en software | < 1 ms |

Si se hace en serie, 1000 segmentos gastan unos 44 s de StrongBox (estimación a partir de la medida).

## Diseño

`K_inbox` y `K_wrap` no cambian. Solo cambia cómo se obtiene `K_seg`.

**Ingesta, al abrir la ventana `w`:**

1. Generar el par efímero en software `(e_w, E_w)`.
2. `Z_w = ECDH(e_w, K_inbox_pub)`.
3. `PRK_w = HKDF-Extract(salt = window_id, IKM = Z_w)`.
4. Poner a cero `e_w` y `Z_w`. Guardar `E_w` en la cabecera de cada segmento de la ventana.

**Ingesta, por cada segmento `i`:**

```text
K_seg_i = HKDF-Expand(PRK_w, info = "sebrain/seg/v1" || window_id || i, L = 32)
header  = version || window_id || E_w || i
ct      = AES-256-GCM(K_seg_i, nonce aleatorio de 96 bits, plaintext, aad = header)
```

**Cognición, por lote:**

1. Agrupar segmentos por `window_id`.
2. Hacer un ECDH en StrongBox por ventana para obtener `Z_w` y derivar `PRK_w`.
3. Derivar cada `K_seg_i` en software.
4. Poner a cero `PRK_w` al terminar la ventana.

**La ventana se cierra** al ocurrir lo primero de: tiempo máximo (propuesta inicial: 15 min), número máximo de segmentos, bloqueo de pantalla o muerte del proceso.

**Coste:** 1 ECDH de StrongBox por ventana en vez de 1 por segmento.

## Riesgos

| # | Riesgo | Gravedad | Mitigación | Riesgo residual |
| --- | --- | --- | --- | --- |
| 1 | **Secreto hacia adelante dentro de la ventana.** `PRK_w` vive en RAM de un proceso siempre activo, incluso con el teléfono bloqueado. Quien lea esa memoria descifra los segmentos ya escritos de la ventana; hoy solo obtendría el segmento en curso. | **Alta** | Ratchet: `PRK_(i+1) = HKDF(PRK_i, "ratchet")`, borrando el anterior tras cada segmento. | Los segmentos futuros de la ventana siguen expuestos. El cierre al bloquear limita la exposición, pero la ingesta con el teléfono bloqueado abre ventanas nuevas igualmente. |
| 2 | **Radio de impacto en cognición.** Filtrar un `Z_w` expone toda la ventana, no un segmento. | Media | Ventanas cortas y número máximo de segmentos. | Inherente al diseño. |
| 3 | **Reutilización de clave y nonce.** Repetir `i` tras un reinicio o un error reutilizaría `K_seg_i`. Con nonce aleatorio el fallo es menos grave, pero se pierde la garantía de clave única. | Alta si ocurre | El estado de ventana vive solo en RAM y nunca se persiste. Al reiniciar el proceso se abre una ventana nueva. | Bajo. |
| 4 | **Borrado de memoria no garantizado.** En la JVM, `fill(0)` no alcanza las copias que haga el GC o las que queden dentro de librerías. | Media | Mantener el estado en un único `ByteArray` o en nativo con `mlock` y borrado explícito. | Afecta también al diseño actual, pero aquí la clave vive minutos en vez de milisegundos. |
| 5 | **Mayor superficie de implementación.** HKDF, ratchet y gestión de ventanas son código criptográfico propio que hay que probar. | Media | Vectores de prueba RFC 5869, y pruebas de pérdida y desorden de segmentos. | Bajo. |
| 6 | **Ratchet y segmentos perdidos.** El receptor debe poder avanzar la cadena sin todos los segmentos. | Baja | Avanzar hasta `i` desde el último estado conocido. El coste es despreciable. | Bajo. |

El riesgo 1 es el principal: empeora una propiedad de seguridad real del diseño actual a cambio de ahorrar tiempo.

## Por qué no adoptarla aún

El coste por segmento de la cognición lo domina el LLM, no el ECDH. Con los datos de Edge Gallery (Gemma-4-E2B TPU, JSON):

| Paso | Tiempo aproximado |
| --- | --- |
| Primer token | 1206–1396 ms |
| Generar 100 tokens a 19 tok/s (salida de ejemplo) | ≈ 5,2 s |
| ECDH con StrongBox | ≈ 0,044 s |

El ECDH es menos del 1 % de un segmento que pase por el LLM, y esta propuesta ahorraría como mucho ese porcentaje. Esto es una estimación: aún no hay benchmark de `CognitionWorker` y el tamaño de salida es supuesto.

La propuesta solo compensa si hay muchos segmentos que no pasan por el LLM (por ejemplo, descartados por deduplicación antes de la extracción). Hoy eso no ocurre, porque la deduplicación se hace antes de cifrar, en la ingesta.

## Alternativas

| Opción | Coste de ECDH | Efecto en seguridad | Valoración |
| --- | --- | --- | --- |
| **A. Mantener el diseño actual** | 44 ms por segmento | Sin cambios | **Recomendada por ahora** |
| **B. Agrupar varias capturas en un segmento** | 44 ms por grupo | Una ventana de exposición por grupo; retiene datos en RAM hasta cerrarlo | Solo si A resulta insuficiente |
| **C. Esta propuesta con ratchet** | 44 ms por ventana | Riesgos 1 a 6 | Si la medición lo justifica |
| **D. Esta propuesta sin ratchet** | 44 ms por ventana | Sin secreto hacia adelante dentro de la ventana | No recomendada |

## Decisión

1. Se midió `CognitionWorker` con el umbral del 5 % como criterio: ECDH quedó en 0,49–0,78 %. **Rechazada.**
2. Las preguntas sobre la ventana de 15 min y el cierre al bloquear ya no aplican.
