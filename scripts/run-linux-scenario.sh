#!/bin/sh
# Runs LinuxScenarioTest on the phone and prints its summary. It takes about 8 minutes, uses
# its own test vault, and needs a language model installed in the app.
# The device must start unlocked; the screen stays on by itself until the run ends.
#
# Usage: scripts/run-linux-scenario.sh <serial> [model, e.g. E4B]
set -u

SERIAL=$1
MODEL=${2:-}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT=$ROOT/build/linux-scenario
STAMP=$(date +%Y%m%d-%H%M%S)
LOG=$OUT/eval-$STAMP.log
RESULT=$OUT/instrument-$STAMP.log
mkdir -p "$OUT"
cd "$ROOT"

$ADB get-state > /dev/null 2>&1 || { echo "Device $SERIAL not found"; exit 1; }
$ADB shell dumpsys trust | grep -m1 -q 'deviceLocked=1' && { echo "The device is locked. Unlock it and start again."; exit 1; }

ANDROID_SERIAL=$SERIAL ./gradlew -q :app:installDebug :app:installDebugAndroidTest > "$OUT/install.log" 2>&1 \
    || { echo "Install failed, see $OUT/install.log"; exit 1; }

$ADB shell svc power stayon usb
$ADB logcat -c
# Streamed to a file: the device buffer rotates during the run and loses the early lines.
$ADB logcat -v time -s SEBRAIN_EVAL:I > "$LOG" &
LOGCAT_PID=$!

MODEL_ARG=""
[ -n "$MODEL" ] && MODEL_ARG="-e model $MODEL"
# shellcheck disable=SC2086 # MODEL_ARG is two words or none.
$ADB shell am instrument -w -e scenario linux $MODEL_ARG \
    -e class com.sebrain.app.LinuxScenarioTest com.sebrain.app.test/androidx.test.runner.AndroidJUnitRunner > "$RESULT" 2>&1

sleep 2
kill $LOGCAT_PID 2> /dev/null
$ADB shell svc power stayon false

if grep -q '^OK (1 test)' "$RESULT"; then STATUS=PASS; else STATUS=FAIL; fi
echo "== $STATUS ($(grep -m1 '^Time:' "$RESULT"))"
grep -E 'setup |write RESULT|links |retrieve RESULT|isolation |tool TAGS' "$LOG" | sed 's/.*SEBRAIN_EVAL[^:]*: //'
echo "Log: $LOG"
[ $STATUS = PASS ] || { echo "Instrumentation output: $RESULT"; exit 1; }
