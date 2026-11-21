#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$ROOT"

SDK=$(sed -n 's/^sdk.dir=//p' local.properties)
BUILD_TOOLS="$SDK/build-tools/36.0.0"
APK="app/build/outputs/apk/release/app-release.apk"
# The release certificate (public): a build signed with any other key fails.
RELEASE_CERT_SHA256="6e9367f802e17fcfa7c41dc86fbfabe1d018af07c4aa653bd0a858df0fa201bf"

[ -x "$BUILD_TOOLS/aapt2" ] || { echo "FAIL: aapt2 not found at $BUILD_TOOLS"; exit 1; }
# Always check a fresh build; a stale APK made this script pass on old code before.
./gradlew -q :app:assembleRelease
[ -f "$APK" ] || { echo "FAIL: missing $APK"; exit 1; }
"$BUILD_TOOLS/apksigner" verify "$APK" || { echo "FAIL: $APK is not validly signed"; exit 1; }
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | grep -q "SHA-256 digest: $RELEASE_CERT_SHA256" \
    || { echo "FAIL: $APK is not signed with the SeBrain release key"; exit 1; }

BADGING=$("$BUILD_TOOLS/aapt2" dump badging "$APK")
MANIFEST=$("$BUILD_TOOLS/aapt2" dump xmltree --file AndroidManifest.xml "$APK")

fail() { echo "FAIL: $1"; exit 1; }
if printf '%s\n' "$BADGING" | grep -q "android.permission.QUERY_ALL_PACKAGES"; then fail "QUERY_ALL_PACKAGES is declared"; fi
if printf '%s\n' "$MANIFEST" | grep -q "NotificationListenerService"; then fail "a notification listener is declared"; fi
printf '%s\n' "$BADGING" | grep -q "android.permission.INTERNET"
printf '%s\n' "$MANIFEST" | grep -q 'allowBackup.*=false'
printf '%s\n' "$MANIFEST" | grep -q 'usesCleartextTraffic.*=false'
printf '%s\n' "$MANIFEST" | grep -q 'dataExtractionRules'
grep -q 'BuildConfig.DEBUG' app/src/main/kotlin/com/sebrain/app/MainActivity.kt
grep -q 'FLAG_SECURE' app/src/main/kotlin/com/sebrain/app/MainActivity.kt

echo "RELEASE PRIVACY CHECKS: PASS"
