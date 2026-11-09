#!/bin/sh
# Static acceptance checks 10, 11 and 13 of docs/plan-phase1.md. Run after building
# :core:vault:assembleDebug and :core:vault:assembleDebugAndroidTest.
set -eu

ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$ROOT"
SDK=$(sed -n 's/^sdk.dir=//p' local.properties)
LLVM=$SDK/ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin
BUILD_TOOLS=$SDK/build-tools/36.0.0
BUILD=core/vault/build
SO=$BUILD/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libsqlcipher.so
TEST_APK=$BUILD/outputs/apk/androidTest/debug/vault-debug-androidTest.apk
MANIFEST=$BUILD/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml
TEST_MANIFEST=$BUILD/intermediates/packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml
FAIL=0

echo "== check 10: LOAD alignment"
"$LLVM/llvm-readelf" -l "$SO" | grep LOAD
"$LLVM/llvm-readelf" -l "$SO" | grep LOAD | grep -qv '0x4000$' && { echo "FAIL: a LOAD segment is not 0x4000"; FAIL=1; }
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$TEST_APK" && echo "zipalign: OK" || { echo "FAIL: zipalign"; FAIL=1; }
ls -l "$SO" | awk '{print "so bytes:", $5}'

echo "== checks 11 and 13: permissions"
"$BUILD_TOOLS/aapt2" dump permissions "$TEST_APK"
for f in "$MANIFEST" "$TEST_MANIFEST"; do
    [ -f "$f" ] || { echo "FAIL: missing $f"; FAIL=1; continue; }
    if grep -q 'android.permission.INTERNET' "$f"; then echo "FAIL: INTERNET in $f"; FAIL=1; else echo "no INTERNET: $f"; fi
done
"$BUILD_TOOLS/aapt2" dump permissions "$TEST_APK" | grep -q 'android.permission.INTERNET' && { echo "FAIL: INTERNET in test APK"; FAIL=1; }

echo "== check 13: dependencies"
for c in debugRuntimeClasspath debugAndroidTestRuntimeClasspath; do
    ./gradlew -q :core:vault:dependencies --configuration "$c" > "$BUILD/deps-$c.txt"
    if grep -E -i 'firebase|crashlytics|analytics|datatransport|mlkit|play-services|telemetry|sentry|bugsnag' "$BUILD/deps-$c.txt"; then
        echo "FAIL: telemetry dependency in $c"; FAIL=1
    else
        echo "no telemetry dependency: $c ($(grep -c -- '--- ' "$BUILD/deps-$c.txt") entries)"
    fi
done

[ "$FAIL" -eq 0 ] && echo "STATIC CHECKS: PASS" || { echo "STATIC CHECKS: FAIL"; exit 1; }
