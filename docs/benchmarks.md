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
| `setUnlockedDeviceRequired` con el dispositivo bloqueado | No probado | — | Solo se midió desbloqueado; no se comprobó el rechazo estando bloqueado |
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
