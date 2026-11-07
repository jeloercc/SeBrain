# SeBrain — contexto para Claude Code

App Android nativa (Kotlin + C/JNI) que actúa como segundo cerebro 100 % local en un Google Pixel 10 (Tensor G5).
Ingiere datos de otras apps, los procesa con IA on-device y los guarda cifrados.
Documento de investigación completo (el porqué de cada decisión): https://claude.ai/artifact/VzkdPNuHAbtrMDV83R2HSJ

## Reglas duras (no negociables)

- La app NO declara `android.permission.INTERNET`. Si una dependencia lo fusiona, eliminarlo con `tools:node="remove"`.
- Cero SDKs de analítica, telemetría o crash reporting (Firebase, Crashlytics, etc.).
  - **Excepción pendiente de confirmar:** ML Kit GenAI (`com.google.mlkit:genai-prompt`, opcional tras un ajuste) arrastra `transport-backend-cct`, `transport-runtime` y librerías `firebase-*`. Resultado en `docs/benchmarks.md`.
- Ningún dato en claro sale del proceso. Nada se escribe en disco sin cifrar, salvo binarios de modelos.
- `allowBackup="false"`, `dataExtractionRules` que excluyan todo, `FLAG_SECURE` en pantallas con contenido de la bóveda.
- Los modelos se importan localmente (SAF / `adb push`) y se verifican con SHA-256 fijado en código. La app no descarga nada.
- Toda librería nativa alineada a páginas de 16 KB (`-Wl,-z,max-page-size=16384`, NDK r27+).

## Stack decidido

| Capa | Usar | NO usar (descartado) |
| --- | --- | --- |
| LLM background | LiteRT-LM (Kotlin), backend NPU → GPU → CPU | MLC LLM / TVM (no usa la TPU del Tensor G5) |
| Aceleración NPU | LiteRT CompiledModel + Google Tensor ML SDK (Beta) | Delegado NNAPI (deprecado en Android 15) |
| LLM interactivo | LiteRT-LM por defecto. Opcional tras un ajuste, fuera del núcleo: ML Kit GenAI Prompt API (Gemini Nano), solo con Activity en primer plano; la app no depende de la versión `nano-*` | Gemini Nano desde Worker o foreground service (`BACKGROUND_USE_BLOCKED`) |
| Embeddings | EmbeddingGemma 2 Text 270M, Matryoshka a 256 dims, L2 normalizado | — |
| Modelo de extracción | Gemma 3 1B int4 (subir a Gemma 4 E2B si la TPU lo sostiene) | — |
| Base de datos | `sqlcipher-android` (build propio) con clave raw `x'…'` | `android.database.sqlite`, Room sin SQLCipher |
| Vectores | `sqlite-vec` compilado estático dentro de SQLCipher (`sqlite3_auto_extension`) | `sqlite-vss` (abandonado) |
| Texto | FTS5, búsqueda híbrida con RRF (k = 60) | — |
| Trabajo diferido | WorkManager (cargando, batería no baja, idle para lotes grandes) | Servicios residentes con el LLM cargado |

## Arquitectura (tres procesos, una bóveda)

1. **Ingesta** (siempre activa, sin IA): Share intent, `NotificationListenerService` con allowlist por paquete, tile + OCR (opcional). Normaliza, deduplica (SHA-256 del contenido canónico) y cifra con ECIES usando la clave pública `K_inbox` en segmentos AES-GCM (`K_seg` efímera por segmento). Funciona con el teléfono bloqueado.
2. **Cognición** (`CognitionWorker`): descifra segmentos → chunking → extracción JSON con decodificación restringida → embeddings → inserta en `capture`, `chunk`, `chunk_fts`, `chunk_vec` en una transacción. Borra el segmento solo tras `COMMIT`. Carga el LLM por lote y lo libera al terminar.
3. **Consulta** (app abierta): embedding de la consulta → RRF FTS5 + vec0 → top 8 → LiteRT-LM con citas `[n]`. Con el ajuste de Gemini Nano activo, se usa Nano y se vuelve a LiteRT-LM si AICore devuelve `BUSY`.

### Claves (Android Keystore)

- `K_inbox`: par EC P-256, `PURPOSE_AGREE_KEY`, StrongBox (fallback TEE si StrongBox lo rechaza).
- `K_wrap`: AES-256-GCM, StrongBox, `setUnlockedDeviceRequired(true)`; envuelve la DEK de 32 bytes guardada en `noBackupFilesDir`.
- `K_secret` (opcional): AES-256-GCM con `AUTH_BIOMETRIC_STRONG` para campos marcados como secreto.
- Nunca pasar la DEK como passphrase (evita el PBKDF2). Poner a cero los buffers tras usarlos.

### AccessibilityService

Desactivado y fuera del alcance hasta la Fase 7. Android 17 lo revoca con Advanced Protection activo. No declarar `isAccessibilityTool`.

## Entorno verificado (2026-11-07)

- Dispositivo: Pixel 10 (frankel), Android 17, SDK 37 (sdk_full 37.1), parche 2026-10-05.
- Bootloader bloqueado (`verifiedbootstate=green`, `flash.locked=1`): ML Kit GenAI disponible. No desbloquear.
- `strongbox_keystore=300`, `hardware_keystore=500`. AICore 26.10.15.7.
- Mac de desarrollo: Intel x86_64, macOS 15.8.2, JDK 21 (Temurin 21.0.13 x64), Android command line tools instalados a mano, sin Homebrew (`ANDROID_HOME=~/Library/Android/sdk`; adb desde `$ANDROID_HOME/platform-tools`).
- Google Tensor SDK Beta (Pixel 10 y 11): acceso concedido. Aún sin usar; no se usa en el spike de diagnóstico.
- ML Kit Prompt API: `com.google.mlkit:genai-prompt:1.0.0-beta5`. `getBaseModelName()` observado el 2026-11-07: `nano-v4-full`.
- Tensor ML SDK: la compilación de modelos para la TPU exige estación Linux x86_64. En la Mac, usar modelos TPU ya compilados (`.litertlm` de LiteRT Community / model garden) o un contenedor/VM Linux. No intentar compilar para TPU en macOS.

## Módulos Gradle

`:app` (Compose) · `:ingest` · `:cognition` · `:core:crypto` · `:core:vault` (incluye el `.so` de SQLCipher + sqlite-vec) · `:core:ml`

`minSdk` y `targetSdk`: Android 16/17 (el único dispositivo es el Pixel 10). No añadir compatibilidad con versiones antiguas.

## Fase actual: 0 — Spikes de riesgo

Cada spike entrega código mínimo + números medidos en el Pixel 10. Nada de UI todavía.

- [ ] Build de `sqlcipher-android` con `sqlite-vec.c` (`-DSQLITE_CORE`) y FTS5, alineado a 16 KB. Criterio: `SELECT vec_version()` y FTS5 en `PRAGMA compile_options` en el dispositivo.
- [ ] Benchmark LiteRT-LM: EmbeddingGemma 2 (270M) y Gemma 3 1B en NPU, GPU y CPU. Medir latencia, tokens/s, RAM pico y temperatura tras 10 min.
- [x] ML Kit: `getBaseModelName()` devuelve un modelo `nano-*` y genera texto con bootloader bloqueado y sin `INTERNET`. Observado el 2026-11-07: `nano-v4-full`.
- [x] Keystore: ¿StrongBox acepta `PURPOSE_AGREE_KEY` en EC P-256? Sí (`KeyInfo.securityLevel=STRONGBOX`, ECDH ≈ 44 ms).

Salida de la fase: tabla de benchmarks en `docs/benchmarks.md` y decisión de backend por modelo.

## Decisiones abiertas (no asumir; preguntar)

- Modo de procesamiento del inbox: Estricto (solo desbloqueado) o Equilibrado (tras el primer desbloqueo).
- Confirmar la excepción de ML Kit: LiteRT-LM es el motor por defecto y ML Kit queda opcional tras un ajuste, pendiente de cerrar con el análisis de telemetría de `docs/benchmarks.md`.
- Distribución: solo sideload o Play privado.
- Allowlist inicial de apps para notificaciones.

## Forma de trabajar

- Rol: arquitecto de sistemas Android. Respuestas técnicas y directas, sin halagos ni relleno.
- Antes de usar una API de LiteRT-LM, Tensor SDK o ML Kit GenAI, verificar la firma en la documentación actual: cambian rápido y la memoria del modelo está desactualizada.
- Señalar explícitamente cualquier limitación del HAL/OS en lugar de rodearla en silencio.
- Cambios en la jerarquía de claves o en el esquema de la BD: proponer primero, no aplicar directamente.
