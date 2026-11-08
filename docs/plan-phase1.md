# Plan: Phase 1, task 1 — SQLCipher + sqlite-vec + FTS5 for Android arm64

**Status: Plan, no code yet.** Versions verified on 2026-11-08.

**Goal:** one `libsqlcipher.so` for `arm64-v8a`, aligned to 16 KB, with SQLCipher, FTS5 and sqlite-vec compiled in statically, inside the `:core:vault` module.

## 1. Sources and versions

| Component | Version | Exact pin | Notes |
| --- | --- | --- | --- |
| `sqlcipher/sqlcipher-android` | `v4.20.0` (latest stable) | commit `94bdf2589942b11a53b7d9a005d5c1e66920d4e8` | Java/JNI layer. `v5.0.0-beta.1` exists and is not used |
| `sqlcipher/sqlcipher` | `v4.20.0`, SQLite 3.54.1 | commit `355d4db92d3404e71faa8088d071a71d767807f4` | Submodule of the above; matches the release tag |
| `sqlcipher/libtomcrypt` | as pinned by the release | commit `476a9579ae94f32b9ea9e2747bfb04b302370259` | Upstream's default crypto provider. See decision D1 |
| `asg017/sqlite-vec` | `v0.1.9` (latest stable) | `sqlite-vec-0.1.9-amalgamation.zip`, SHA-256 `b87cdda12112657ba5ab8842f0088a4090982eaf41f22b2bd6d495b81765a8c9`; commit `e9f598abfa0c06b328d8fe5da9c3760cce74be10` | `v0.1.10-alpha.4` exists and is not used |
| Android NDK | r30 LTS, `30.0.16248370` | `ndkVersion` in `:core:vault` | Upstream pins r25c (`25.2.9519653`), below the project's r27+ rule. See section 6 |
| `androidx.sqlite:sqlite` | `2.7.0` | Gradle | Required by the upstream Java layer |
| AGP / Gradle / JDK | 9.5.1 / 9.6.0 / 21 | Same as `spikes/diag` | `ndkBuild` is used, so CMake is not needed |

**How the sources enter the repo:**

- `third_party/sqlcipher-android`: git submodule pinned to the commit above, with its two nested submodules.
- `third_party/sqlite-vec/`: vendored `sqlite-vec.c` and `sqlite-vec.h` (about 320 KB), plus a `SHA256SUMS` file recording the zip hash.
- The generated `sqlite3.c` and `sqlite3.h` are build outputs and are not committed. The root `.gitignore` already excludes `*.so`, `*.aar` and `*.apk`.

The prebuilt AAR from Maven cannot be used: it does not contain sqlite-vec, and a separate loadable extension would not be statically linked.

## 2. Build

### 2.1 Generate the amalgamation

Same commands the upstream `build.gradle` uses, wrapped in a Gradle task that runs before `preBuild` and writes to `build/generated/sqlcipher/`:

```sh
./configure --with-tempstore=yes --disable-tcl
make sqlite3.c
```

### 2.2 Native build

`:core:vault` has its own `Android.mk`. It lists the upstream JNI sources and the upstream libtomcrypt `Android.mk`, and adds two files. The upstream `Android.mk` is not edited, so the submodule stays clean.

```make
LOCAL_MODULE := libsqlcipher
LOCAL_SRC_FILES := <upstream JNI .cpp files> $(GEN)/sqlite3.c sqlite-vec.c vault_jni.c

LOCAL_CFLAGS := <upstream default flags, minus SQLITE_ENABLE_LOAD_EXTENSION> \
    -DSQLITE_CORE -DSQLITE_VEC_STATIC -DSQLITE_VEC_ENABLE_NEON

LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
```

- `Application.mk` keeps upstream's `APP_STL := c++_static`, `-Wl,--exclude-libs,ALL` and `APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true`, and adds `APP_ABI := arm64-v8a` and `APP_PLATFORM := android-36`.
- **FTS5 needs no extra work:** upstream already defines `SQLITE_ENABLE_FTS5`.
- The module must stay named `libsqlcipher`, and the Java package `net.zetetic.database` must stay as it is, because the JNI registers against those names.

### 2.3 Statically linking sqlite-vec

- `-DSQLITE_CORE` makes `sqlite-vec.c` call the `sqlite3_*` symbols directly instead of through the loadable-extension API.
- `-DSQLITE_VEC_STATIC` removes the export decoration from `sqlite3_vec_init`.
- `-DSQLITE_VEC_ENABLE_NEON` enables NEON distance functions; NEON is baseline on arm64.
- `sqlite-vec.c` includes `sqlite3.h`, so the include path must contain the generated directory.

**Registration.** `SQLITE_EXTRA_INIT` cannot be used, because SQLCipher already sets it to `sqlcipher_extra_init`. A small `vault_jni.c` exposes one JNI function:

```c
#include "sqlite3.h"
#include "sqlite-vec.h"
#include <jni.h>

JNIEXPORT jint JNICALL
Java_com_sebrain_core_vault_VaultNative_registerExtensions(JNIEnv *env, jclass cls) {
  return sqlite3_auto_extension((void (*)(void))sqlite3_vec_init);
}
```

Kotlin calls `System.loadLibrary("sqlcipher")`, then `VaultNative.registerExtensions()` once, before the first connection, and treats a non-zero result as a fatal error. An explicit call is preferred over `__attribute__((constructor))` because it returns an error code and has a defined order.

### 2.4 Opening with a raw key

- Per CLAUDE.md, the DEK is never passed as a passphrase. The key is the ASCII byte array `x'<64 hex chars>'`, passed to the `byte[] password` overload of `SQLiteDatabase.openOrCreateDatabase`.
- SQLCipher's `crypto_impl.c` documents that form as raw key data, so PBKDF2 is skipped.
- The hex array and the DEK are zeroed after opening. A hex `String` must never be created, because it cannot be zeroed.

## 3. Module layout

The repo has no root Gradle project yet; `spikes/diag` stays separate.

```text
settings.gradle.kts            includes :core:vault
build.gradle.kts
third_party/
  sqlcipher-android/           git submodule, v4.20.0
  sqlite-vec/                  sqlite-vec.c, sqlite-vec.h, SHA256SUMS
core/vault/
  build.gradle.kts             android library, ndkBuild, ndkVersion, abiFilters arm64-v8a
  consumer-rules.pro           keeps the net.zetetic.database JNI classes
  src/main/jni/
    Android.mk
    Application.mk
    vault_jni.c
  src/main/kotlin/com/sebrain/core/vault/
    VaultNative.kt             loadLibrary + registerExtensions
  src/androidTest/kotlin/com/sebrain/core/vault/
    VaultAcceptanceTest.kt
```

The upstream Java sources are added with `sourceSets.main.java.srcDir("../../third_party/sqlcipher-android/sqlcipher/src/main/java")`. Task 1 adds only what the acceptance test needs: no schema and no `capture`, `chunk` or `chunk_vec` tables.

## 4. Acceptance test

An instrumented test on the Pixel 10 (`:core:vault:connectedDebugAndroidTest`) plus static checks on the built APK. All of them must pass.

| # | Check | Pass condition |
| --- | --- | --- |
| 1 | Open with raw key | A random 32-byte key in `x'…'` form creates the database; it reopens with the same key |
| 2 | Key is really raw | Opening with the wrong raw key fails with code 26. Open time is far below that of a control database opened with a passphrase (PBKDF2, 256,000 iterations) |
| 3 | File is encrypted | The first 16 bytes are not `SQLite format 3\0`, and a known plaintext marker is absent from the file |
| 4 | Versions | `PRAGMA cipher_version` starts with `4.20.0`; `sqlite_version()` is `3.54.1`; `PRAGMA cipher_provider` is recorded |
| 5 | `vec_version()` | Returns `v0.1.9`, on the first connection and on a second one, which proves the auto-extension applies to every connection |
| 6 | `vec0` insert and query | `CREATE VIRTUAL TABLE v USING vec0(embedding float[256])`; insert 1000 deterministic L2-normalized vectors; a `MATCH` query with `k = 8` returns the same ids and order as a brute-force computation in Kotlin |
| 7 | `vec0` delete and persistence | A deleted row stops being returned; the results of check 6 survive close and reopen |
| 8 | FTS5 | `ENABLE_FTS5` is in `PRAGMA compile_options`; a `MATCH` query with `bm25()` returns the expected order; with `tokenize='unicode61 remove_diacritics 2'`, `reunion` finds `reunión` |
| 9 | Extension loading is off | `SELECT load_extension('x')` fails |
| 10 | 16 KB alignment | `llvm-readelf -l libsqlcipher.so`: every `LOAD` segment has `Align 0x4000`. `zipalign -c -P 16 -v 4` passes on the APK |
| 11 | Manifest | `aapt2 dump permissions` shows no `android.permission.INTERNET` |

**Limit of check 10:** `getconf PAGE_SIZE` on this Pixel 10 returns 4096, so running on the device does not exercise a 16 KB page size. Check 10 is static. A runtime check would need the Pixel's 16 KB developer option or a 16 KB emulator image; it is not part of this task unless requested.

**Also measured, not a pass/fail check:** time to open, and insert and KNN query time over 1000 and 10,000 vectors, as input for D1.

## 5. Steps

1. Install NDK r30 with `sdkmanager` and check its host binaries (section 6).
2. Add the submodules and vendor sqlite-vec, verifying the SHA-256.
3. Generate the amalgamation on the Mac (section 6).
4. Create the root Gradle project and `:core:vault`; build `libsqlcipher.so`.
5. Run the static checks, then the acceptance test on the Pixel 10.
6. Record the results in `docs/benchmarks.md`.

## 6. macOS Intel versus Linux

**Nothing in this task is known to require Linux.** The only Linux requirement in the project is compiling models for the TPU, which is unrelated. Two items are unverified on this Mac and are checked first:

| Item | Status | Check | Fallback |
| --- | --- | --- | --- |
| NDK r30 on Intel | Unverified. The Mac package is `android-ndk-r30-darwin.dmg`; the NDK shipped universal binaries in past releases, but I could not confirm the x86_64 slice for r30 | After installing: `file $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang` | NDK r28c (`28.2.13676358`), which still meets the r27+ rule |
| Amalgamation generation | Unverified. Upstream uses `--disable-tcl`, and SQLite's README says core deliverables build without Tcl. This Mac has Tcl 8.5.9, and many SQLite makefile targets need 8.6 or later, so the build must not pick it up | Run step 3 before anything else | Generate `sqlite3.c` once in a Linux container and use the file as the input |

Everything else works on the Mac: `make`, Apple command line tools (installed), `ndk-build`, and `llvm-readelf` from the NDK. CMake is not installed and is not needed. Homebrew is not used.

## 7. Risks

- **The upstream JNI on r30.** Upstream builds with r25c. Moving to r30 may surface new compiler errors in the JNI code.
- **sqlite-vec is pre-1.0,** and its compile-time flags are documented as unstable. Pinning the version and running check 6 covers this. The open NEON issues (#322, #356) concern `int8` distances; the project uses `float`.
- **The build has no upstream test for this combination.** I found no upstream issue describing sqlite-vec statically linked into SQLCipher, so this is covered only by the acceptance test.

## 8. Crypto provider

**Decision: start with LibTomCrypt**, upstream's default, which adds no dependency. Upstream issue #96 reports slower page reads with it on low-end devices (about 4.5x on one query), and the runtime `cipher_provider` setting cannot change it, so it has to be measured on this device. PBKDF2 does not matter here because the key is raw.

**Later Pixel benchmark, LibTomCrypt versus static OpenSSL** (`-DSQLCIPHER_CRYPTO_OPENSSL`). Same device, same data, release build, several runs per provider:

| Operation | Workload |
| --- | --- |
| KNN query over `vec0` | 10,000 vectors, 256 dimensions, `k = 8` |
| FTS5 query | `MATCH` with `bm25()` over the same corpus |
| Bulk insert | Rows into `vec0` and FTS5 in one transaction |

Switch to OpenSSL only if the numbers justify it.
