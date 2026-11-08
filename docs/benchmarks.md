# Benchmarks — Fase 0

## Spike de diagnóstico (`spikes/diag/`), 2026-11-07

**Dispositivo:** Pixel 10 (`frankel`), Android 17, SDK 37 (`sdk_full` 37.1), parche 2026-10-05, build `CP3A.261005.005`, AICore 26.10.15.7. USB conectado, batería 74 %, Wi-Fi activo.

**Build:** AGP 9.5.1, Gradle 9.6.0, KGP 2.4.0, JDK 21, `genai-prompt:1.0.0-beta5`, `minSdk` 36, `targetSdk` 37, `compileSdk` 37.1. APK debug sin `android.permission.INTERNET`.

**Ejecuciones:** dos completas en primer plano (12:21:11 y 12:21:40), con el dispositivo desbloqueado y la app en primer plano durante toda la prueba. Valores en el formato `ejecución 1 / ejecución 2`.

| Prueba | Resultado | Valor | Nota |
| --- | --- | --- | --- |
| `ro.boot.verifiedbootstate` / `ro.boot.flash.locked` | PASS | `green` / `1` | Bootloader bloqueado |
| `FEATURE_STRONGBOX_KEYSTORE` | PASS | `true` | Por adb: `strongbox_keystore=300`, `hardware_keystore=500` |
| Keygen EC P-256 `PURPOSE_AGREE_KEY` en StrongBox | PASS | 121,2 / 123,0 ms | **StrongBox acepta `AGREE_KEY`**; el reintento en TEE no hizo falta y no se ejecutó |
| `KeyInfo.securityLevel` EC | PASS | `STRONGBOX` (2) | |
| ECDH lado Keystore (privada StrongBox + pública efímera) | PASS | 44,80 / 44,05 ms | Media de 20; rango 40,02–52,88 ms; secretos iguales en todas las iteraciones |
| ECDH lado software (privada efímera + pública Keystore) | PASS | 0,58 / 0,57 ms | Media de 20; provider `AndroidOpenSSL` |
| Keygen efímera en software | PASS | 0,23 / 0,22 ms | Media de 20 |
| ECDH completo (efímera + ambos lados) | PASS | 45,68 / 44,90 ms | Media de 20 |
| Keygen AES-256-GCM en StrongBox con `setUnlockedDeviceRequired(true)` | PASS | 63,6 / 25,8 ms | Una muestra por ejecución |
| `KeyInfo.securityLevel` AES | PASS | `STRONGBOX` (2), 256 bits | |
| AES-GCM wrap de 32 bytes | PASS | 33,07 / 33,92 ms | Media de 20; `Cipher.ENCRYPT_MODE` con `PURPOSE_ENCRYPT`, no `PURPOSE_WRAP_KEY`; incluye `init` y `doFinal` |
| AES-GCM unwrap de 32 bytes | PASS | 32,74 / 32,44 ms | Media de 20; `Cipher.DECRYPT_MODE`; coincide con el original en todas las iteraciones |
| `setUnlockedDeviceRequired` con el dispositivo bloqueado | PASS (rechazada) | `KeyStoreException: Device locked` | Ver la sección «`K_wrap` con el Pixel bloqueado» |
| Gemini Nano `checkStatus()` | INFO | `DOWNLOADABLE` / `AVAILABLE` | Tras `download()` pasó a disponible |
| Gemini Nano `download()` | PASS | 2 s | Duración sospechosa para un modelo; ver observación 2 |
| `getBaseModelName()` | PASS | `nano-v4-full` | Reproducido 3 veces; cumple el criterio actualizado (`nano-*`, genera texto, bootloader bloqueado, sin `INTERNET`). La documentación lista el Pixel 10 en `nano-v3` |
| Prompt, tiempo al primer token, primera llamada del proceso | PASS | 587 / 422 ms | Streaming, sin `warmup()`; no es un arranque en frío garantizado (observación 3) |
| Prompt, tiempo total, primera llamada del proceso | PASS | 946 / 740 ms | 3 chunks, 77 caracteres |
| Prompt, tiempo al primer token, segunda llamada | PASS | 358 / 360 ms | |
| Prompt, tiempo total, segunda llamada | PASS | 693 / 710 ms | 3 chunks, 77 caracteres |
| Worker en segundo plano, `generateContent` | PASS (bloqueado) | `BACKGROUND_USE_BLOCKED` (30) | Proceso con `importance=400`, `foreground=false`; ejecutado una vez |
| Nano con `INTERNET` eliminado del manifest | PASS | Todas las pruebas Nano funcionan | `tools:node="remove"`; verificado con `aapt2 dump permissions` |

**Prompt usado:** «Responde en una sola frase: ¿para qué sirve una llave?»

**Respuesta (idéntica en las 4 llamadas):** «Una llave sirve para abrir o cerrar cerraduras y acceder a objetos o lugares.»

**Error exacto del Worker:**

```text
com.google.mlkit.genai.common.GenAiException: [ErrorCode 30] Background usage is blocked. Please use the API when your app is in the foreground instead.
Caused by: AICore failed with error type 2-INFERENCE_ERROR and error code 30-BACKGROUND_USE_BLOCKED: Inference failed.
```

### Observaciones

1. **Modelo distinto del documentado.** El dispositivo reporta `nano-v4-full`. La documentación de ML Kit (consultada el 2026-11-07) lista el Pixel 10 en `nano-v3` y `nano-v4` solo en Pixel 11 y Galaxy Z Flip8/Fold8. No se encontró documentación oficial que explique este valor. El criterio de CLAUDE.md se cambió para que la app no dependa de la versión.
2. **La descarga no se pudo medir.** `DOWNLOADABLE` pasó a completada en 2 s, sin eventos de progreso registrados. Es probable que los pesos ya estuvieran en el dispositivo, pero no está confirmado: el tamaño del modelo sigue sin medirse. Una ejecución anterior de la app (12:16:59) pudo influir, y sus logs se perdieron.
3. **«Primera llamada» no significa frío.** El proceso de la app era nuevo, pero AICore pudo mantener el modelo cargado de llamadas previas. Para un arranque en frío real habría que medir tras reiniciar el dispositivo.
4. **Telemetría transitiva.** `genai-prompt` arrastra `com.google.android.datatransport:transport-backend-cct`, `transport-runtime`, `firebase-encoders`, `firebase-encoders-json` y `firebase-components`. De ahí viene `INTERNET`. Ver el análisis más abajo.
5. **Versión de Kotlin.** `genai-prompt:1.0.0-beta5` está compilado con Kotlin 2.4.0, y el Kotlin integrado en AGP 9.5.1 (2.2.0) no puede leerlo. Hay que fijar KGP 2.4.0 en el classpath de `buildscript`.
6. **Muestra limitada.** Son dos ejecuciones, en un dispositivo conectado por USB y sin control térmico. Las cifras sirven como orden de magnitud.

## `K_wrap` con el Pixel bloqueado, 2026-11-07

**Método:** clave AES-256-GCM en StrongBox con `setUnlockedDeviceRequired(true)`. Con el teléfono desbloqueado se cifra una DEK aleatoria de 32 bytes; el IV, el texto cifrado y el SHA-256 de la DEK se guardan en `noBackupFilesDir`. Después se lanza `LockProbeReceiver` por `am broadcast` en cada estado; `isDeviceLocked` se lee dentro de la app y se confirma con `dumpsys trust`.

| Prueba | Estado | Resultado | Excepción | Nota |
| --- | --- | --- | --- | --- |
| Descifrar, control antes (14:08) | Desbloqueado | PASS | — | El SHA-256 coincide |
| Cifrar, control antes (14:08) | Desbloqueado | PASS | — | |
| Descifrar, sonda 1 | Bloqueado | PASS (rechazada) | `InvalidKeyException` ← `KeyStoreException` | |
| Cifrar, sonda 1 | Bloqueado | PASS (rechazada) | `InvalidKeyException` ← `KeyStoreException` | |
| Descifrar, sonda 2 (18:49) | Bloqueado, pantalla en reposo | PASS (rechazada) | `InvalidKeyException` ← `KeyStoreException` | |
| Cifrar, sonda 2 (18:49) | Bloqueado, pantalla en reposo | PASS (rechazada) | `InvalidKeyException` ← `KeyStoreException` | |
| Usar una clave recién creada | Bloqueado | Rechazada | `InvalidKeyException` ← `KeyStoreException` | Se observó por accidente al intentar preparar la prueba |
| Descifrar tras volver a desbloquear (18:50) | Desbloqueado | PASS | — | El SHA-256 coincide; la clave sobrevive al bloqueo |
| Cifrar tras volver a desbloquear (18:50) | Desbloqueado | PASS | — | |

```text
java.security.InvalidKeyException: Keystore operation failed
  at android.security.keystore2.AndroidKeyStoreCipherSpiBase.engineInit
  at javax.crypto.Cipher.init
Caused by: android.security.KeyStoreException: Device locked (internal Keystore code: -72)
    0: system/security/keystore2/src/enforcements.rs:590: device is locked.
    1: Error::Km(r#DEVICE_LOCKED)) (public error code: 2 internal Keystore code: -72)
```

**Notas:**

- El rechazo llega en `Cipher.init`, no en `doFinal`.
- La excepción externa es `InvalidKeyException`, con `android.security.KeyStoreException` como causa (`getNumericErrorCode()=2`). No aparece `UserNotAuthenticatedException`. El código de la app debe recorrer la cadena de causas para reconocer el bloqueo.
- Tras desbloquear, la misma clave vuelve a descifrar el blob guardado: el bloqueo no la invalida.

## Telemetría de ML Kit sin `INTERNET`, 2026-11-07

**Conclusión:** sin `INTERNET`, no se observó tráfico de la app y el intento de subida falla en el DNS. Pero el SDK **sí intenta subir** a Firebase Logging y **guarda eventos sin cifrar** en disco. La excepción sigue pendiente de confirmar.

**Método:** APK debug sin R8 y sin `INTERNET`, datos de la app borrados con `pm clear`. Con `log.tag.TRuntime.*` activado de forma temporal, se capturó logcat, se inspeccionó la base de datos con `run-as` y se forzó el job con `cmd jobscheduler run -f`.

| Prueba | Resultado | Valor | Nota |
| --- | --- | --- | --- |
| App arrancada sin llamar a ML Kit (40 s) | Sin actividad | 0 eventos, sin base de datos, sin job | `MlKitInitProvider` y los servicios siguen en el manifest |
| Tras usar Nano (2 prompts) | Telemetría en cola | 12 eventos `FIREBASE_ML_SDK` | Se crea `databases/com.google.android.datatransport.events` |
| Job de subida programado | Sí | `JobInfoSchedulerService` | Requiere espera mínima de 1 día, `IDLE` y conectividad |
| Subida forzada (1 intento) | Bloqueada | `EPERM` | Ver el log debajo |
| Eventos tras el intento | Siguen en disco | 12, `num_attempts=0` | No se midió cuánto tiempo se retienen |
| Tráfico del UID en `dumpsys netstats detail` | Sin tráfico | 0 filas para el UID, de 747 filas | |
| Grupos del proceso | Sin `inet` | `9997 20230 50230` | Falta el grupo 3003 |
| Texto del prompt y la respuesta en los payloads | No encontrado | 0 coincidencias | Búsqueda de texto; el protobuf no se decodificó por completo |
| Texto del prompt y la respuesta en los datos de la app | No encontrado | 0 coincidencias | `grep` recursivo en el directorio privado |

```text
TransportRuntime.CctTransportBackend: Making request to: https://firebaselogging.googleapis.com/v0cc/log/batch?format=json_proto3
TransportRuntime.Executor: java.lang.SecurityException: Permission denied (missing INTERNET permission?)
  Caused by: android.system.ErrnoException: android_getaddrinfo failed: EPERM (Operation not permitted)
```

**Endpoints en el dex** (ofuscados en el código y decodificados aquí):

- `https://firebaselogging.googleapis.com/v0cc/log/batch?format=json_proto3`
- `https://firebaselogging-pa.googleapis.com/v1/firelog/legacy/batchlog`

**Lo que contienen los eventos, sin cifrar en disco:**

- **Metadatos:** `device`, `model`, `manufacturer`, `hardware`, `product`, `fingerprint`, `os-uild`, `sdk-version`, `country`, `locale`, `mcc_mnc`, `tz-offset`, `net-type`, `mobile-subtype`, `application_build`.
- **Payload (110–142 bytes):** paquete de la app, versión del SDK, locale y un UUID de instancia.
- **`shared_prefs/com.google.mlkit.internal.xml`:** `ml_sdk_instance_id`, un UUID persistente.

**Conflictos con CLAUDE.md:**

- «Cero SDKs de analítica, telemetría»: se incumple si ML Kit está en el APK, aunque no llegue a enviar nada.
- «Nada se escribe en disco sin cifrar»: se incumple solo con metadatos del dispositivo. No se encontró contenido del usuario.

**Sobre el ajuste:** un ajuste apagado evitó los eventos en la prueba de 40 s, pero las librerías, el init provider y los servicios siguen dentro del APK. Solo un flavor de build sin ML Kit los eliminaría.

**Límites:**

- Se analizó solo el proceso de la app. AICore y Google Play Services son procesos separados, con sus propios permisos, y no se analizaron.
- Es una ejecución con un solo intento de subida forzado y sin captura de paquetes.
- En la fase sin ML Kit no se comprobó si se crea `ml_sdk_instance_id`.
- No se probó eliminar los servicios de `datatransport` del manifest.

### Dependencias en el APK

APK debug sin R8. Son 83 entradas del `debugRuntimeClasspath`, con las versiones ya resueltas (incluye BOM y artefactos vacíos como `listenablefuture`).

**Relacionadas con telemetría, Google y ML Kit:**

```text
com.google.android.datatransport:transport-api:2.2.1
com.google.android.datatransport:transport-backend-cct:2.3.3
com.google.android.datatransport:transport-runtime:2.2.6
com.google.android.gms:play-services-base:18.5.0
com.google.android.gms:play-services-basement:18.9.0
com.google.android.gms:play-services-tasks:18.2.0
com.google.firebase:firebase-annotations:16.0.0
com.google.firebase:firebase-components:16.1.0
com.google.firebase:firebase-encoders-json:17.1.0
com.google.firebase:firebase-encoders:16.1.0
com.google.mlkit:common:18.11.0
com.google.mlkit:genai-common:1.0.0-beta5
com.google.mlkit:genai-prompt:1.0.0-beta5
com.google.mlkit:genai-schema:1.0.0-alpha2
```

**Métodos en el dex:** `com.google.android.gms` 11164, `com.google.android.datatransport` 1158, `com.google.mlkit` 1161, `com.google.firebase` 362.

**Componentes de terceros fusionados en el manifest:** `TransportBackendDiscovery`, `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver`, `MlKitComponentDiscoveryService`, `MlKitInitProvider`, más los de WorkManager y `androidx.startup`.

**Permisos resultantes:** `aicore.service.BIND_SERVICE`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` y el permiso propio `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Ninguno es `INTERNET`.

<details>
<summary>Lista completa (83)</summary>

```text
androidx.activity:activity:1.6.0
androidx.annotation:annotation-experimental:1.4.1
androidx.annotation:annotation-jvm:1.9.1
androidx.annotation:annotation:1.9.1
androidx.appcompat:appcompat-resources:1.6.1
androidx.appcompat:appcompat:1.6.1
androidx.arch.core:core-common:2.2.0
androidx.arch.core:core-runtime:2.2.0
androidx.collection:collection-jvm:1.4.2
androidx.collection:collection:1.4.2
androidx.concurrent:concurrent-futures-ktx:1.1.0
androidx.concurrent:concurrent-futures:1.1.0
androidx.core:core-ktx:1.12.0
androidx.core:core:1.12.0
androidx.cursoradapter:cursoradapter:1.0.0
androidx.customview:customview:1.0.0
androidx.drawerlayout:drawerlayout:1.0.0
androidx.emoji2:emoji2-views-helper:1.2.0
androidx.emoji2:emoji2:1.2.0
androidx.fragment:fragment:1.3.6
androidx.interpolator:interpolator:1.0.0
androidx.lifecycle:lifecycle-common:2.6.2
androidx.lifecycle:lifecycle-livedata-core:2.6.2
androidx.lifecycle:lifecycle-livedata:2.6.2
androidx.lifecycle:lifecycle-process:2.6.2
androidx.lifecycle:lifecycle-runtime:2.6.2
androidx.lifecycle:lifecycle-service:2.6.2
androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2
androidx.lifecycle:lifecycle-viewmodel:2.6.2
androidx.loader:loader:1.0.0
androidx.profileinstaller:profileinstaller:1.3.0
androidx.resourceinspection:resourceinspection-annotation:1.0.1
androidx.room:room-common-jvm:2.7.0
androidx.room:room-common:2.7.0
androidx.room:room-runtime-android:2.7.0
androidx.room:room-runtime:2.7.0
androidx.savedstate:savedstate:1.2.1
androidx.sqlite:sqlite-android:2.5.0
androidx.sqlite:sqlite-framework-android:2.5.0
androidx.sqlite:sqlite-framework:2.5.0
androidx.sqlite:sqlite:2.5.0
androidx.startup:startup-runtime:1.1.1
androidx.tracing:tracing-ktx:1.2.0
androidx.tracing:tracing:1.2.0
androidx.vectordrawable:vectordrawable-animated:1.1.0
androidx.vectordrawable:vectordrawable:1.1.0
androidx.versionedparcelable:versionedparcelable:1.1.1
androidx.viewpager:viewpager:1.0.0
androidx.work:work-runtime-ktx:2.12.0
androidx.work:work-runtime:2.12.0
com.google.android.datatransport:transport-api:2.2.1
com.google.android.datatransport:transport-backend-cct:2.3.3
com.google.android.datatransport:transport-runtime:2.2.6
com.google.android.gms:play-services-base:18.5.0
com.google.android.gms:play-services-basement:18.9.0
com.google.android.gms:play-services-tasks:18.2.0
com.google.code.findbugs:jsr305:3.0.2
com.google.errorprone:error_prone_annotations:2.18.0
com.google.firebase:firebase-annotations:16.0.0
com.google.firebase:firebase-components:16.1.0
com.google.firebase:firebase-encoders-json:17.1.0
com.google.firebase:firebase-encoders:16.1.0
com.google.guava:failureaccess:1.0.1
com.google.guava:guava:32.0.0-jre
com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava
com.google.j2objc:j2objc-annotations:2.8
com.google.mlkit:common:18.11.0
com.google.mlkit:genai-common:1.0.0-beta5
com.google.mlkit:genai-prompt:1.0.0-beta5
com.google.mlkit:genai-schema:1.0.0-alpha2
javax.inject:javax.inject:1
org.checkerframework:checker-qual:3.33.0
org.jetbrains:annotations:23.0.0
org.jetbrains.kotlin:kotlin-stdlib -> 2.4.0
org.jetbrains.kotlin:kotlin-stdlib:2.4.0
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.11.0
org.jspecify:jspecify:1.0.0
org.reactivestreams:reactive-streams:1.0.3
```

</details>

## Gemma-4-E2B-it en Edge Gallery, 2026-11-07

Datos medidos por el usuario en Edge Gallery, en el Pixel 10. No proceden del spike.

| Prueba | Backend | Corrida | TTFT | Prefill | Decode | RAM | Resultado |
| --- | --- | --- | --- | --- | --- | --- | --- |
| JSON | TPU | 1 | 1206 ms | 142,6 tok/s | 19,2 tok/s | 1,03 GB | JSON válido; Monterrey omitido en «lugares» |
| JSON | TPU | 2 | 1396 ms | 114,6 tok/s | 19,4 tok/s | 659 MB | JSON válido; Monterrey omitido en «lugares» |
| RAG, 730 tokens | TPU | 1 | 2254 ms | 350,1 tok/s | 19,8 tok/s | 1,05 GB | Correcta |
| Frase corta | TPU | 1 | 492 ms | 66,8 tok/s | 17,6 tok/s | 793 MB | — |
| Frase corta | CPU (2,6 GB) | 1 | 1914 ms | 18,5 tok/s | 14,3 tok/s | 2,29 GB | — |

**Condiciones:** max tokens 4096 (no 1024); sin control de temperatura en la UI; teléfono sin calentamiento notable.

## `CognitionWorker`: ECDH frente al tiempo total por segmento, 2026-11-07

**Objetivo:** decidir sobre `docs/proposal-ecdh-window.md`. Criterio fijado por el usuario: si ECDH es < 5 % del tiempo total por segmento, se mantiene un ECDH por segmento.

**Código:** `spikes/diag/.../CognitionBenchWorker.kt`. Es un `CoroutineWorker` con `setForeground` (`dataSync`) y se lanza con `am start ... --es mode cognition --es batches <n>`.

**Montaje:**
- **Ingesta simulada** (fuera del cronómetro): N textos sintéticos de 150–450 caracteres, cifrados con ECIES contra `K_inbox`, con la jerarquía actual (efímera en software por segmento, HKDF-SHA256, AES-256-GCM). Cada segmento se escribe en `noBackupFilesDir`.
- **`K_inbox`:** EC P-256 `AGREE_KEY` en StrongBox (alias `diag_k_inbox_bench`).
- **Por segmento, cronometrado:**
  - lectura del fichero;
  - ECDH en StrongBox (`KeyAgreement("ECDH", "AndroidKeyStore")`);
  - HKDF;
  - AES-GCM al descifrar;
  - extracción con el LLM: conversación nueva, `ResponseFormat.json(schema)`, `maxOutputToken = 256`, thinking desactivado.
- **Total por segmento:** todo lo anterior. La carga del motor (una por lote) se mide aparte.

**Motor:**
- `litertlm-android:0.18.1` + `litert-npu-runtime-google-tensor:2.3.2`, `Backend.NPU(nativeLibraryDir)`.
- Modelo: `gemma-4-E2B-it_Google_Tensor_G5.litertlm`, copiado de Edge Gallery. El SHA-256 `62faebcfd101acb841c33249530430397e031eb17d4dd3d2a71193d135705f27` se verifica antes de cada lote.
- Los `.so` tienen `LOAD p_align = 16384`.

**Condiciones:**
- USB conectado, batería al 80 %.
- `thermalStatus = 1` (LIGHT) al inicio y al final de cada lote.
- Pantalla apagada o la app en segundo plano durante casi todo el lote; el proceso se mantuvo con `importance = 125` gracias al FGS.
- Edge Gallery tenía un FGS de descarga activo durante el lote de 100.

| Lote | Carga del motor | ECDH media (p50 / p95) | HKDF media | AES-GCM media | LLM media (p50 / p95) | Total/seg media (p50 / p95) | **ECDH % del total** | JSON válido |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10 (21:24) | 3457 ms | 91,8 ms (88,9 / 93,3) | 0,40 ms | 0,81 ms | 11 720 ms (10 463 / 16 827) | 11 817 ms (10 559 / 16 913) | **0,777 %** (0,754 % con la carga) | 10/10 |
| 100 (21:38–22:12) | 3146 ms | 100,2 ms (99,0 / 128,7) | 0,78 ms | 1,06 ms | 20 204 ms (18 654 / 32 439) | 20 311 ms (18 774 / 32 548) | **0,493 %** (0,492 % con la carga) | 95/100 |

**Segmento individual:** el ECDH va del 0,23 % al 1,45 % del total de su segmento (máximo con el segmento más corto, de 6,3 s).

**Resultado:** ECDH supone menos del 1 % del total en ambos lotes, lejos del umbral del 5 %. **Se mantiene un ECDH por segmento** y la propuesta queda rechazada. El coste por segmento lo domina el LLM: más del 99 % del tiempo.

### Observaciones

1. **ECDH es más lento con el LLM activo.**
   - Midió 86–119 ms por operación en el lote de 10 (pico de 128 ms en el primer segmento) y una media de 100 ms (49,7–161,6 ms) en el de 100.
   - En el spike aislado eran 44 ms (40–53 ms).
   - **No se midió la causa.** Las hipótesis candidatas, sin verificar, son contención en StrongBox o en el bus, frecuencia de CPU y presión térmica.
   - Aun con 160 ms el ECDH sería < 1 % de un segmento de 20 s.
2. **El LLM se ralentiza dentro del lote de 100.** Media por bloques de 10 segmentos: 12,8 s → 22,7 → 27,1 → 29,0 → 23,9 → 16,8 → 17,8 → 18,1 → 18,2 → 16,7 s. `thermalStatus` siguió en 1 al principio y al final, pero no se muestreó durante el lote. La causa (térmica, competencia con el FGS de Edge Gallery u otra) no se midió.
3. **JSON inválido en 5 de 100.** Son los segmentos 29, 33, 43, 68 y 83. Todos dieron 264–265 caracteres y tardaron de 31 a 40 s, lo que apunta a que se agotaron los 256 tokens de salida sin cerrar el JSON. Tres de los cinco corresponden a la misma plantilla (cita médica). Las salidas no se registraron, así que la causa está sin confirmar.
4. **Calidad de la extracción**, vista en el segmento 0:
   - `titulo` salió como `": "`.
   - La fecha se inventó como `2024-05-15` porque el texto no da año.
   - Es una muestra; la calidad no se evaluó y queda para el benchmark del modelo de extracción.
5. **Limitación del SO: no se puede iniciar un FGS desde segundo plano.**
   - El segundo Worker de una cadena `10 → 100` empezó con la app en segundo plano.
   - `setForeground` falló con `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed`.
   - Un lote de 100 dura unos 34 min, más que la ventana de unos 10 min de un Worker normal.
   - Implicación para `CognitionWorker`: o los lotes caben en esa ventana (unos 25–50 segmentos con estos tiempos), o se trocean en varios Workers, o el FGS se inicia con la app en primer plano. Pendiente de diseño.
6. **`ExistingWorkPolicy.REPLACE` no detiene un bucle bloqueante.** Una corrida de 100 quedó contaminada cuando se lanzó una segunda en el mismo proceso: las dos se ejecutaron a la vez y esos datos se descartaron. El Worker real debe comprobar `isStopped` entre segmentos.
7. **Versión de LiteRT-LM.** Con `0.19.1`, `Backend.NPU` falla al cargar `libLiteRtDispatch_GoogleTensor.so` de `litert-npu-runtime-google-tensor:2.3.2`: `Could not load symbol LiteRtDispatchQueryInterface: undefined symbol`. 0.19.x espera la nueva ABI de dispatch y el runtime publicado exporta `LiteRtDispatchGetApi`. La versión `0.18.1` funciona.
8. **Muestra limitada.** Es un lote de cada tamaño, con textos sintéticos, en un solo dispositivo y sin control térmico.

### Repetición del lote de 100 (corrida 2), 2026-11-07 22:17–22:37

**Cambios respecto a la corrida 1:**
- `maxOutputToken` pasa de 256 a 512.
- `thermalStatus` y `getThermalHeadroom(10)` se muestrean en cada segmento.
- Las salidas inválidas se registran completas.
- Se comprueba `isStopped` entre segmentos.
- Edge Gallery cerrado con `force-stop` y sin descargas (ningún servicio activo).
- Mismo prompt, mismos textos y mismo modelo. Inicio con batería a 35,1 °C, 80 %, USB.

| Métrica | Corrida 1 (21:38) | Corrida 2 (22:17) |
| --- | --- | --- |
| Duración del lote | 33,9 min | 19,5 min |
| Carga del motor | 3146 ms | 4786 ms |
| ECDH media (p95) | 100,2 ms (128,7) | 91,0 ms (101,2) |
| LLM media (p50 / p95) | 20 204 ms (18 654 / 32 439) | 11 563 ms (10 262 / 16 048) |
| Total/seg media (p95) | 20 311 ms (32 548) | 11 658 ms (16 156) |
| **ECDH % del total** | 0,493 % | **0,781 %** |
| JSON válido | 95/100 | **95/100** |
| Título vacío entre los válidos | no medido | **19/95** |
| `thermalStatus` | 1 al inicio y al final | 0 en 48 segmentos, 1 en 52; nunca ≥ 2 |

**Total/seg medio por bloques de 10 segmentos (ms):**

| Bloque | 0–9 | 10–19 | 20–29 | 30–39 | 40–49 | 50–59 | 60–69 | 70–79 | 80–89 | 90–99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Corrida 1 | 12 789 | 22 705 | 27 134 | 28 968 | 23 925 | 16 844 | 17 761 | 18 062 | 18 241 | 16 681 |
| Corrida 2 | 12 130 | 10 704 | 11 803 | 12 628 | 12 151 | 9 407 | 12 241 | 11 658 | 12 272 | 11 586 |
| `headroom10s` medio, corrida 2 | 0,686 | 0,726 | 0,769 | 0,780 | 0,796 | 0,802 | 0,807 | 0,808 | 0,810 | 0,816 |

**Conclusiones:**

1. **La ralentización de la corrida 1 no se reproduce.** Con Edge Gallery cerrado, el tiempo por segmento se mantiene estable en 9,4–12,6 s durante todo el lote. `thermalStatus` no pasó de 1, aunque el headroom subió de 0,69 a 0,82. La correlación apunta a la carga de Edge Gallery (FGS de descarga activo en la corrida 1) y no a la temperatura, pero **la causa no está demostrada**: no se midió la carga de CPU, de E/S ni de la NPU.
2. **ECDH sigue < 1 %.** Es el 0,781 % con un LLM más rápido; el máximo por segmento fue 1,45 %. La decisión de mantener un ECDH por segmento no cambia.
3. **ECDH con el LLM activo:** 83–127 ms (media 91 ms), frente a 44 ms en el spike aislado. Se reproduce sin Edge Gallery, así que no lo causaba Edge Gallery. La causa sigue sin medirse.
4. **El JSON inválido no se debe al límite de tokens.**
   - Fallan **los mismos 5 segmentos** que en la corrida 1 (29, 33, 43, 68, 83), con texto idéntico. La generación es determinista.
   - Las 5 salidas completas son `{ "titulo":": "` seguido de `\n\t` repetidos (corregido el 2026-11-08; el log mostraba los saltos de línea como espacios) hasta agotar el límite: 520–521 caracteres con 512 tokens, 264 con 256.
   - Es un bucle degenerado de espacios en blanco bajo decodificación restringida, no un JSON largo truncado.
   - Doblar los tokens solo duplica el tiempo perdido: 32 s por segmento fallido, frente a una mediana de 10 s.
5. **El título vacío es sistemático.** 19 de los 95 válidos tienen `titulo` igual a `": "`. Los 5 inválidos empiezan igual. El modelo emite `": "` como valor del primer campo, lo que apunta al prompt o al esquema y no al azar. No investigado.
6. **Implicación para el diseño:** un reintento con la misma entrada y más tokens no arregla nada. El §4 de `docs/design-cognition-worker.md` se ajustó en consecuencia.

## `CognitionWorker` en segundo plano real (sin FGS), 2026-11-07

**Montaje:**
- `--es mode cognition-bg`: Worker de 10 segmentos **sin `setForeground`**, con `requiresCharging` y un retraso de 120 s.
- Tras programarlo, la actividad se cierra con BACK y el proceso se mata con `kill -9`. No se usa `force-stop`, porque cancela el trabajo programado, ni `am kill`, que no lo mata mientras WorkManager lo mantiene con `oom adj` 0.
- La pantalla se apaga con `KEYCODE_SLEEP`.
- USB conectado y cargando. Mismo motor y modelo que en la corrida 2, `maxOutputToken` 512 y mismo prompt.

### Corrida A: pantalla apagada, sin idle forzado (22:48–22:59) — válida

| Métrica | Valor |
| --- | --- |
| Arranque del Worker | 22:54:22. Programado para las 22:50:06; JobScheduler lo aplazó unos 4 min |
| Estado del proceso | `importance=230`, `interactive=false`, `deviceIdle=false` al inicio, en los segmentos 0 y 9 y al final |
| Backend | **NPU.** `libLiteRtDispatch_GoogleTensor.so` cargado sin errores de dispatch |
| Carga del motor | 5073 ms |
| SHA-256 del modelo (3,9 GB) | 22 343 ms, frente a 4–10 s en primer plano |
| ECDH media (p95) | 101,0 ms (105,7) |
| LLM media (p50 / p95) | **29 183 ms** (29 414 / 32 350) |
| Total/seg media | 29 287 ms |
| ECDH % del total | 0,345 % |
| JSON válido / título vacío | 10/10 / 2 |
| `thermalStatus` | 0 en los 10 segmentos |
| Duración del lote | 298 s, dentro de la ventana de unos 10 min |

**Comparación con la misma prueba con la pantalla encendida.** Es una corrida descartada como prueba de segundo plano: alguien tocó la pantalla a las 22:45:21 (`WAKE_REASON_TAP`) y la actividad se restauró. Sirve de referencia con pantalla encendida y app en primer plano (`importance=100`). Las salidas son idénticas, carácter a carácter, porque la generación es determinista.

| Segmento | Caracteres | Pantalla encendida | Pantalla apagada | Factor |
| --- | --- | --- | --- | --- |
| 0 | 331 | 9 805 ms | 28 644 ms | ×2,92 |
| 1 | 119 | 5 909 ms | 24 709 ms | ×4,18 |
| 2 | 276 | 9 756 ms | 29 414 ms | ×3,01 |
| 3 | 158 | 6 550 ms | 25 595 ms | ×3,91 |
| 4 | 360 | 10 507 ms | 30 223 ms | ×2,88 |
| 5 | 466 | 12 840 ms | 33 277 ms | ×2,59 |
| 6 | 320 | 11 203 ms | 31 016 ms | ×2,77 |
| 7 | 174 | 7 326 ms | 26 849 ms | ×3,66 |
| 8 | 290 | 9 328 ms | 29 753 ms | ×3,19 |
| 9 | 391 | 11 244 ms | 32 350 ms | ×2,88 |

**Observaciones:**

1. **La NPU funciona desde un Worker en segundo plano real.** Funciona con la app cerrada, el proceso arrancado por JobScheduler, sin FGS y con la pantalla apagada. No hizo falta caer a GPU ni a CPU, y no hubo errores.
2. **El LLM es unas 3 veces más lento con la pantalla apagada.** Un ajuste lineal sobre los segmentos más corto (119 caracteres) y más largo (466) da lo siguiente:

   | Estado | Coste por carácter | Coste fijo por segmento |
   | --- | --- | --- |
   | Pantalla encendida | ≈ 20 ms | ≈ 3,5 s |
   | Pantalla apagada | ≈ 25 ms | ≈ 21,8 s |

   - Casi toda la penalización está en la **parte fija**: crear la conversación, el prefill o la compilación de la gramática de LLGuidance. La decodificación se encarece solo un 23 % por carácter.
   - El SHA-256 también fue unas 3 veces más lento, lo que apunta a que el proceso en segundo plano recibe menos CPU (cpuset o frecuencia).
   - **La causa no se midió.** No se registraron afinidad, frecuencia ni uso de la NPU.
3. **Implicación para el diseño (§1):**
   - Con unos 29 s por segmento, 25 segmentos son unos 12 min y no caben en 8 min. El presupuesto de tiempo limitará cada ejecución a unos 15 segmentos.
   - El p95 móvil inicial de 35 s sigue siendo razonable (p95 medido: 32,4 s).
   - No cambié el diseño. Si el coste fijo resulta ser la compilación de la gramática o el prefill del prompt de sistema, reutilizar una conversación precargada (`Conversation.clone()`) podría reducirlo. Está sin probar.
4. **JobScheduler aplazó el arranque unos 4 min** con la pantalla apagada (programado a las 22:50:06, arrancó a las 22:54:22).

### Corrida B: idle forzado (`dumpsys deviceidle force-idle`) — incompleta

- **23:00:19:** `force-idle` activado: `mForceIdle=true`, `mState=IDLE`, Worker pendiente del retraso.
- **23:00:41:** el teléfono se levantó (`WAKE_REASON_LIFT`).
- **23:00:50:** la app se abrió en primer plano.
- **Poco después:** el teléfono se desconectó del USB. Desde las 23:01:02 no hay logs, y a las 23:16 `adb devices` no lo veía.
- **Sin resultado:** no hay ningún segmento procesado y no se sabe si el Worker llegó a ejecutarse con el modo idle forzado.
- **El modo idle forzado puede seguir activo en el teléfono:** `dumpsys deviceidle unforce` no se pudo ejecutar. Hay que ejecutarlo al reconectar y repetir la corrida B.

## Segundo plano: idle forzado, `cpuset`, conversación única y restricciones del esquema, 2026-11-08

Sesión de 15:30 a 16:18. Pixel conectado a corriente (`AC powered`), batería del 34 % al 56 %. Mismo modelo y motor (`litertlm-android:0.18.1`, NPU), `maxOutputToken` 512, prompt sin cambios. Desglose con `ExperimentalFlags.enableBenchmark` (hay que activarlo **antes** de crear el `Engine`; si no, `getBenchmarkInfo` lanza `Benchmark is not enabled`).

**Inicio de sesión:** `dumpsys deviceidle unforce` confirmado (`mForceIdle=false`, `mState=ACTIVE`). Volvió a confirmarse tras la corrida B y al final de la sesión.

### Corrida B: idle forzado

| Intento | Qué pasó | ¿Válido? |
| --- | --- | --- |
| 15:30:43 | 8,4 min en `IDLE` sin arrancar (`DEVICE_NOT_DOZING` sin cumplir). A las 15:39:06 se levantó el teléfono (`WAKE_REASON_LIFT`) y a las 15:39:13 se pulsó el botón de encendido; la actividad se restauró y reprogramó el Worker. Con `deviceIdle=true` y `importance=325`, el Worker arrancó, la NPU cargó en 6346 ms y generó el segmento 0. Luego cayó por un error mío (orden de `enableBenchmark`) | No |
| **15:43:11** | **15 min en `IDLE` sin arrancar.** La única restricción sin cumplir al final era `DEVICE_NOT_DOZING`; 84 de 84 muestras con el proceso muerto | **Sí** |

- **Con idle forzado, el Worker no se ejecuta.** Tras `unforce` (15:58:36) arrancó en 6 s (15:58:42).
- No se midió una ventana de mantenimiento real: con `force-idle` no hubo ninguna en esos 15 min.
- En el primer intento, un Worker ya programado arrancó en idle tras abrirse la app. Ese único segmento indica que la NPU no queda bloqueada por `deviceIdle=true`, pero no es una corrida limpia.

### Mismo lote en tres estados

10 segmentos, `importance` y `cpuset` medidos durante la corrida.

| Métrica | Pantalla encendida, top-app | Segundo plano, control | Segundo plano, conversación única |
| --- | --- | --- | --- |
| Hora | 16:03 | 15:58–16:03 | 16:07–16:09 |
| `importance` | 100 | 230 | 230 |
| `cpuset` (muestras) | `/top-app` (9 de 10) | `/background` (26 de 26) | `/background` (10 de 11) |
| CPU permitidas | 0–7 | **0–2** | **0–2** |
| Carga del motor | 3657 ms | 14 007 ms | 6106 ms |
| `createConversation` | **2,68 s** (2,63–2,77) | **14,9 s** (12,2–16,9) | 19,85 s, una sola vez |
| Prefill | 124–171 tokens; 141–789 tok/s | 124–171 tokens; 41–603 tok/s | 63–138 tokens; 115–442 tok/s |
| Decode | 17,0–18,1 tok/s | 14,9–16,6 tok/s | 14,2–15,6 tok/s |
| LLM media | 9,59 s | 23,35 s | 6,11 s |
| Total por segmento (p95) | 9,68 s (11,5) | **23,46 s** (26,0) | **6,21 s** (9,1) |
| Lote completo con carga | 100,7 s | 249,1 s | 88,5 s |
| ECDH media / % del total | 92,3 ms / 0,95 % | 101,6 ms / 0,43 % | 98,4 ms / 1,58 % |
| JSON válido | 10/10 | 10/10 | 10/10 |
| Título vacío | 2/10 | 2/10 | **10/10** |

**Máscara de CPU de cada grupo** (`/dev/cpuset/*/cpus`): `top-app` 0–7, `foreground` 0–6, `system-background` 0–4, `background` 0–2.

**Frecuencias por grupo de CPU**, muestreadas cada 10 s, MHz:

| Corrida | policy0 (CPU 0–1) | policy2 (2–4) | policy5 (5–6) | policy7 (7) |
| --- | --- | --- | --- | --- |
| Pantalla encendida: media / máx | 1051 / 1881 | 801 / 1785 | 736 / 1785 | **1732 / 3782** |
| Control en segundo plano, con proceso vivo: media / máx | 1124 / 2246 | 916 / 3052 | 848 / 3052 | 813 / 3782 |
| Conversación única: media / máx | 1156 / 2246 | 856 / 3052 | 510 / 3052 | 745 / 3168 |

El muestreo es grueso y las frecuencias son de todo el sistema, no del proceso, así que no se pueden atribuir al Worker.

**Conclusiones:**

1. **El coste fijo de la corrida A es `createConversation`.** En segundo plano tarda 14,9 s de media, frente a 2,68 s con la pantalla encendida (×5,6). El decode apenas cambia (unos 15 frente a 17,6 tok/s).
2. **La causa medida más directa es el `cpuset`.** En `/background` el proceso solo dispone de las CPU 0–2, frente a 0–7 en `/top-app`. Encaja con que una operación de CPU como `createConversation` sea la más afectada, y con que el SHA-256 pase de 5 a 23–24 s. **No se probó la relación causal** cambiando solo el `cpuset`, y no se sabe qué hace `createConversation` en esos segundos. La compilación de la gramática de LLGuidance es una candidata no verificada.
3. **La conversación precargada no se puede medir como se propuso.** `Conversation.clone()` no existe en `litertlm-android:0.18.1` (sí en 0.19.1, que rompe la NPU), y tampoco hay API para fijar el prefijo del prompt. Lo único posible en 0.18.1 es **una sola `Conversation` para todo el lote**, y eso es lo medido:
   - Baja el total a 6,21 s por segmento (−74 %) y el lote de 249 s a 88,5 s.
   - **No es utilizable tal cual:** el historial se acumula entre segmentos. Las salidas cambian desde el segmento 1 (125 caracteres frente a 119) y el título sale vacío en 10 de 10, frente a 2 de 10.
   - Sirve como cota de lo que se ganaría evitando `createConversation`.
4. **Comparación con la corrida A:** el coste fijo estimado allí era de 21,8 s (ajuste lineal); hoy, medido directamente, `createConversation` son 14,9 s. El control total es 23,46 s por segmento frente a 29,29 s, sin desglose en la corrida A y en una sesión distinta.
5. **Tope en segundo plano medido.** Con 8 min por ejecución, la corrida A (29,3 s por segmento, 5,1 s de carga y 22,3 s de SHA-256) da unos 15 segmentos. El control de hoy (23,5 s, 14,0 s y 23,7 s) da unos 18.

**Contaminación física:** el teléfono se movió o se tocó durante la sesión pese a estar reservado.

| Hora | Evento | Efecto |
| --- | --- | --- |
| 15:39:06 y 15:39:13 | `WAKE_REASON_LIFT` y `power_button` | Invalidó el primer intento de B |
| 16:01:54 | `WAKE_REASON_LIFT` | Control: segmentos 6–9 con `interactive=true` al final; `createConversation` baja de unos 16,5 s a 12–14 s. Sin esos segmentos, los 6 primeros dan 15,9 s |
| 16:09:47 | `WAKE_REASON_TAP` | Conversación única: durante el segmento 9 |

**No explicado:** a las 15:43:00, justo tras reinstalar el APK y antes de la corrida, la app arrancó en modo `all` (Keystore y Nano) sin que yo la lanzara. `am start -S` la mató 3 s después. No afecta a los datos y no se encontró el origen en el buffer.

### Restricciones del esquema con LiteRT-LM 0.18.1 (sonda del §6)

`SchemaProbe.kt`, en primer plano con la pantalla encendida (16:10–16:18). Textos: los 5 segmentos que fallaban (29, 33, 43, 68, 83) y otros 3 de control por sonda. El veredicto automático de la sonda marca «NO RESPETA» cuando hay JSON inválido, aunque la restricción probada sí se cumpla, así que la columna «Veredicto» se revisó a mano sobre las salidas.

| Restricción | Sonda automática | Veredicto real | Evidencia |
| --- | --- | --- | --- |
| Control: esquema actual | 0/5 | Reproduce el fallo | Los 5 segmentos caen en el bucle de espacio en blanco |
| `x-guidance.whitespace_flexible=false` | 5/5 | **Se respeta** | JSON compacto sin whitespace fuera de strings, 5,7–6,9 s frente a 32 s; LiteRT-LM pasa `x-guidance` a LLGuidance |
| `minLength=40` en `titulo` | 2/3 | **Se respeta** | Los 3 títulos tienen ≥ 40 caracteres. El fallo del segmento 3 es el bucle de whitespace, ajeno a `minLength`. El modelo rellena con basura para llegar |
| `maxLength=10` | 3/3 | **Se respeta** | Títulos cortados: `": Cita con"`, `"Estado del"` |
| `pattern ^ZZ` | 3/3 | **Se respeta** | `"ZZoom reunión de diseño"`, `"ZZA pedido enviado"` |
| `maxItems=1` en `personas` | 3/3 | **Se respeta** | Una sola persona. En el segmento 0 mete dos nombres en un item: `"Laura Méndez, Diego"` |
| `additionalProperties=false` | 2/3 | **Se respeta** | Con la instrucción de añadir `lugar`, no aparece (segmentos 0 y 4); el control sin la restricción sí lo emite. El fallo del segmento 3 es el bucle |
| `ResponseFormat.regex` (JSON compacto) | 5/5 | **Funciona** | Alternativa disponible; no hace falta, porque `x-guidance` pasa |
| Esquema endurecido completo | 5/5 | Se respetan las reglas, **calidad peor** | Sin bucle, pero con títulos basura y campos perdidos (abajo) |

**Corrección:** el bucle es de `\n\t` repetidos. Antes lo describí como «tabuladores y espacios», porque el log sustituía los saltos de línea por espacios.

**Las restricciones se respetan, pero no resuelven el título.** Salidas del esquema endurecido:

```text
{"titulo":", personas:[{","personas":["Mamá","abuela Carmen","tía Rosa"],"fechas":[],"temas":[],"tareas":[]}
{"titulo":", \"personas\": [\"Dra. Patricia Ruiz\"], \"fechas\": [\"2024-05-17\"], \"temas\": [\"Cita\"", ...
{"titulo":">Resumen de llamada con cliente", ...
```

Con `whitespace_flexible=false` solo, el título tampoco se arregla: `": "`, `": Cita dentista"`, `": Resumen de llamada con cliente"`.

**Patrón observado, sin demostrar:** los títulos correctos aparecen cuando el modelo pone un salto de línea entre `:` y la comilla (`"titulo":⏎"Plan de escalada"`). Los títulos con `": "` aparecen cuando `"titulo":"` queda pegado. Es compatible con que la gramática fuerce `"titulo":"` y el modelo siga como si aún tuviera que escribir `": "`. Encaja con que el JSON compacto lo empeore. Sin probar: `x-guidance.whitespace_pattern` con un espacio opcional.
