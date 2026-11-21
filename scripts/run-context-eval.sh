#!/bin/sh
# Runs ContextWindowEvalTest on the phone in the debug app (com.sebrain.app.debug), never the
# release app: can the model take a larger context window and still find a buried fact?
# The model must already be in the debug app (scripts/run-followup-eval.sh copies it).
# The device must start unlocked; the screen stays on by itself until the run ends.
#
# Usage: scripts/run-context-eval.sh <serial> <part of a model file name> [windows, e.g. 4096,8192,16384]
set -u

SERIAL=$1
MODEL=$2
WINDOWS=${3:-4096,8192,16384}
APP=com.sebrain.app.debug
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT=$ROOT/build/context-eval
STAMP=$(date +%Y%m%d-%H%M%S)
LOG=$OUT/eval-$STAMP.log
RESULT=$OUT/instrument-$STAMP.log
mkdir -p "$OUT"
cd "$ROOT"

$ADB get-state > /dev/null 2>&1 || { echo "Device $SERIAL not found"; exit 1; }
$ADB shell dumpsys trust | grep '(current)' | grep -q 'deviceLocked=1' && { echo "The device is locked. Unlock it and start again."; exit 1; }

$ADB shell svc power stayon usb
RUN_TIMEOUT=1800000
SCREEN_TIMEOUT=$($ADB shell settings get system screen_off_timeout | tr -d '\r[:space:]')
[ "$SCREEN_TIMEOUT" = "$RUN_TIMEOUT" ] && SCREEN_TIMEOUT=60000
trap '$ADB shell svc power stayon false; $ADB shell settings put system screen_off_timeout "$SCREEN_TIMEOUT"' EXIT
$ADB shell settings put system screen_off_timeout $RUN_TIMEOUT

ANDROID_SERIAL=$SERIAL ./gradlew -q :app:installDebug :app:installDebugAndroidTest > "$OUT/install.log" 2>&1 \
    || { echo "Install failed, see $OUT/install.log"; exit 1; }

$ADB shell am force-stop $APP
$ADB logcat -c
$ADB logcat -v time -s SEBRAIN_EVAL:I > "$LOG" &
LOGCAT_PID=$!

$ADB shell am instrument -w -e model "$MODEL" -e windows "$WINDOWS" \
    -e class com.sebrain.app.ContextWindowEvalTest $APP.test/androidx.test.runner.AndroidJUnitRunner > "$RESULT" 2>&1

sleep 2
kill $LOGCAT_PID 2> /dev/null
sed 's/.*SEBRAIN_EVAL[^:]*: //' "$LOG"
grep -q '^OK (1 test)' "$RESULT" || { echo "== The run did not finish: $RESULT"; exit 1; }
