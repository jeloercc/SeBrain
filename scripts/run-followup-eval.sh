#!/bin/sh
# Runs FollowUpEvalTest on the phone in the debug app (com.sebrain.app.debug), never the release
# app, and prints its summary. Copies the chosen model from tools/models into the debug app first.
# The device must start unlocked; the screen stays on by itself until the run ends.
#
# Every model given is run twice, without and with FollowUp, under one screen-on period: a phone
# that locks between runs stops the rest.
#
# Usage: scripts/run-followup-eval.sh <serial> <model file in tools/models>...
set -u

SERIAL=$1
shift
APP=com.sebrain.app.debug
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT=$ROOT/build/followup-eval
STAMP=$(date +%Y%m%d-%H%M%S)
LOG=$OUT/eval-$STAMP.log
RESULT=$OUT/instrument-$STAMP.log
mkdir -p "$OUT"
cd "$ROOT"

for MODEL_FILE in "$@"; do
    [ -f "tools/models/$MODEL_FILE" ] || { echo "tools/models/$MODEL_FILE not found"; exit 1; }
done
$ADB get-state > /dev/null 2>&1 || { echo "Device $SERIAL not found"; exit 1; }
$ADB shell dumpsys trust | grep '(current)' | grep -q 'deviceLocked=1' && { echo "The device is locked. Unlock it and start again."; exit 1; }

$ADB shell svc power stayon usb
RUN_TIMEOUT=1800000
SCREEN_TIMEOUT=$($ADB shell settings get system screen_off_timeout | tr -d '\r[:space:]')
[ "$SCREEN_TIMEOUT" = "$RUN_TIMEOUT" ] && SCREEN_TIMEOUT=60000
trap '$ADB shell svc power stayon false; $ADB shell settings put system screen_off_timeout "$SCREEN_TIMEOUT"; $ADB shell rm -f /data/local/tmp/sebrain-model.litertlm' EXIT
$ADB shell settings put system screen_off_timeout $RUN_TIMEOUT

ANDROID_SERIAL=$SERIAL ./gradlew -q :app:installDebug :app:installDebugAndroidTest > "$OUT/install.log" 2>&1 \
    || { echo "Install failed, see $OUT/install.log"; exit 1; }

for MODEL_FILE in "$@"; do
for FOLLOWUP in off on; do
LOG=$OUT/eval-$STAMP-$FOLLOWUP-$MODEL_FILE.log
RESULT=$OUT/instrument-$STAMP-$FOLLOWUP-$MODEL_FILE.log
echo "######## $MODEL_FILE followup=$FOLLOWUP"
    # The model goes into the debug app only, once; the app checks its SHA-256 before loading it.
    if ! $ADB shell run-as $APP ls "files/models/$MODEL_FILE" > /dev/null 2>&1; then
        echo "== Copying $MODEL_FILE into $APP"
        $ADB push "tools/models/$MODEL_FILE" /data/local/tmp/sebrain-model.litertlm > /dev/null || { echo "Push failed"; exit 1; }
        $ADB shell "run-as $APP sh -c 'mkdir -p files/models && cp /data/local/tmp/sebrain-model.litertlm files/models/$MODEL_FILE'" \
            || { echo "Copy into $APP failed"; exit 1; }
        $ADB shell rm -f /data/local/tmp/sebrain-model.litertlm
    fi

    $ADB shell am force-stop $APP
    $ADB logcat -c
    $ADB logcat -v time -s SEBRAIN_EVAL:I > "$LOG" &
    LOGCAT_PID=$!

    $ADB shell am instrument -w -e model "$MODEL_FILE" -e followup "$FOLLOWUP" \
        -e class com.sebrain.app.FollowUpEvalTest $APP.test/androidx.test.runner.AndroidJUnitRunner > "$RESULT" 2>&1

    sleep 2
    kill $LOGCAT_PID 2> /dev/null
    sed 's/.*SEBRAIN_EVAL[^:]*: //' "$LOG"
    grep -q '^OK (1 test)' "$RESULT" || echo "== The run did not finish: $RESULT"
done
done
