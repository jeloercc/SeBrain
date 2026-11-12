#!/bin/sh
set -eu

SERIAL=${1:?Usage: $0 <serial>}
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb"
PACKAGE="com.sebrain.core.vault.test"
CLASS="com.sebrain.core.vault.SearchScaleBenchmarkTest"

cd "$ROOT"
ANDROID_SERIAL="$SERIAL" ./gradlew -q :core:vault:assembleDebug :core:vault:assembleDebugAndroidTest
ANDROID_SERIAL="$SERIAL" ./gradlew -q :core:vault:installDebug :core:vault:installDebugAndroidTest
"$ADB" -s "$SERIAL" shell am instrument -w -e class "$CLASS" "$PACKAGE/androidx.test.runner.AndroidJUnitRunner"
"$ADB" -s "$SERIAL" logcat -d -s SEBRAIN_SCALE:I
