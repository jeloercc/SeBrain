#!/bin/sh
# Runs one model evaluation test on the phone in the debug app (com.sebrain.app.debug), never the
# release app, once per chat model given, and prints its SEBRAIN_EVAL lines. Copies the embedding
# model and each chat model from tools/models into the debug app if missing. The device must
# start unlocked; the screen stays on until the run ends. Extra instrumentation arguments go in
# ARGS, such as ARGS="-e filler 300".
#
# Usage: [ARGS="-e key value"] scripts/run-model-eval.sh <test class> <serial> <chat model file in tools/models>...
set -u

CLASS=com.sebrain.app.$1
SERIAL=$2
shift 2
APP=com.sebrain.app.debug
ARGS=${ARGS:-}
EMBEDDING=embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT=$ROOT/build/model-eval
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
cd "$ROOT"

for MODEL_FILE in $EMBEDDING "$@"; do
    [ -f "tools/models/$MODEL_FILE" ] || { echo "tools/models/$MODEL_FILE not found"; exit 1; }
done
$ADB get-state > /dev/null 2>&1 || { echo "Device $SERIAL not found"; exit 1; }
$ADB shell dumpsys trust | grep '(current)' | grep -q 'deviceLocked=1' && { echo "The device is locked. Unlock it and start again."; exit 1; }

$ADB shell svc power stayon usb
RUN_TIMEOUT=1800000
SCREEN_TIMEOUT=$($ADB shell settings get system screen_off_timeout | tr -d '\r[:space:]')
[ "$SCREEN_TIMEOUT" = "$RUN_TIMEOUT" ] && SCREEN_TIMEOUT=60000
# KEEP_AWAKE=1 leaves the screen settings to a caller that chains several runs: restoring a
# one-minute timeout after a long run turns the screen off, and locks the phone, at once.
if [ -n "${KEEP_AWAKE:-}" ]; then
    trap '$ADB shell rm -f /data/local/tmp/sebrain-model.litertlm' EXIT
else
    trap '$ADB shell svc power stayon false; $ADB shell settings put system screen_off_timeout "$SCREEN_TIMEOUT"; $ADB shell rm -f /data/local/tmp/sebrain-model.litertlm' EXIT
fi
$ADB shell settings put system screen_off_timeout $RUN_TIMEOUT

ANDROID_SERIAL=$SERIAL ./gradlew -q :app:installDebug :app:installDebugAndroidTest > "$OUT/install.log" 2>&1 \
    || { echo "Install failed, see $OUT/install.log"; exit 1; }

# A model goes into the debug app only, once; the app checks its SHA-256 before loading it.
for MODEL_FILE in $EMBEDDING "$@"; do
    if ! $ADB shell run-as $APP ls "files/models/$MODEL_FILE" > /dev/null 2>&1; then
        echo "== Copying $MODEL_FILE into $APP"
        $ADB push "tools/models/$MODEL_FILE" /data/local/tmp/sebrain-model.litertlm > /dev/null || { echo "Push failed"; exit 1; }
        $ADB shell "run-as $APP sh -c 'mkdir -p files/models && cp /data/local/tmp/sebrain-model.litertlm files/models/$MODEL_FILE'" \
            || { echo "Copy into $APP failed"; exit 1; }
        $ADB shell rm -f /data/local/tmp/sebrain-model.litertlm
    fi
done

for MODEL_FILE in "$@"; do
    LOG=$OUT/eval-$STAMP-${CLASS##*.}-$MODEL_FILE.log
    RESULT=$OUT/instrument-$STAMP-$MODEL_FILE.log
    echo "######## $CLASS $MODEL_FILE $ARGS"
    $ADB shell am force-stop $APP
    $ADB logcat -c
    $ADB logcat -v time -s SEBRAIN_EVAL:I > "$LOG" &
    LOGCAT_PID=$!

    $ADB shell am instrument -w -e model "$MODEL_FILE" $ARGS \
        -e class $CLASS $APP.test/androidx.test.runner.AndroidJUnitRunner > "$RESULT" 2>&1

    sleep 2
    kill $LOGCAT_PID 2> /dev/null
    sed 's/.*SEBRAIN_EVAL[^:]*: //' "$LOG"
    grep -q '^OK (1 test)' "$RESULT" || echo "== The run did not finish: $RESULT"
done
