#!/bin/sh
# Measures search on the phone's REAL vault with questions the phone's model writes
# (SyntheticRetrievalEval). Only numbers leave the phone: no title, question or note text.
# About 15 minutes for 50 questions. The device must start unlocked, and its power button must
# not be pressed during the run; the screen stays on by itself.
#
# Usage: scripts/run-synthetic-retrieval.sh <serial> [count, default 50] [seed, default 7] [minChars, default 200]
set -u

SERIAL=$1
COUNT=${2:-50}
SEED=${3:-7}
MIN_CHARS=${4:-200}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT=$ROOT/build/synthetic-retrieval
STAMP=$(date +%Y%m%d-%H%M%S)
LOG=$OUT/eval-$STAMP.log
RESULT=$OUT/instrument-$STAMP.log
mkdir -p "$OUT"
cd "$ROOT"

$ADB get-state > /dev/null 2>&1 || { echo "Device $SERIAL not found"; exit 1; }
$ADB shell dumpsys trust | grep '(current)' | grep -q 'deviceLocked=1' && { echo "The device is locked. Unlock it and start again."; exit 1; }

ANDROID_SERIAL=$SERIAL ./gradlew -q :app:installDebug :app:installDebugAndroidTest > "$OUT/install.log" 2>&1 \
    || { echo "Install failed, see $OUT/install.log"; exit 1; }

# The vault must not be open in the app while the test opens it.
$ADB shell am force-stop com.sebrain.app
$ADB shell svc power stayon usb
RUN_TIMEOUT=1800000
SCREEN_TIMEOUT=$($ADB shell settings get system screen_off_timeout | tr -d '\r[:space:]')
[ "$SCREEN_TIMEOUT" = "$RUN_TIMEOUT" ] && SCREEN_TIMEOUT=60000
trap '$ADB shell svc power stayon false; $ADB shell settings put system screen_off_timeout "$SCREEN_TIMEOUT"' EXIT
$ADB shell settings put system screen_off_timeout $RUN_TIMEOUT

$ADB logcat -c
# Streamed to a file: the device buffer rotates during the run and loses the early lines.
$ADB logcat -v time -s SEBRAIN_EVAL:I > "$LOG" &
LOGCAT_PID=$!
$ADB shell am instrument -w -e eval synthetic -e count "$COUNT" -e seed "$SEED" -e minChars "$MIN_CHARS" \
    -e class com.sebrain.app.SyntheticRetrievalEval com.sebrain.app.test/androidx.test.runner.AndroidJUnitRunner > "$RESULT" 2>&1
sleep 2
kill $LOGCAT_PID 2> /dev/null

grep -q 'VaultKeyException\$DeviceLocked\|VaultKeyException\$ScreenOff' "$RESULT" \
    && echo "== The device locked or its screen turned off during the run: unlock it, leave it untouched and run again."
if grep -q '^OK (1 test)' "$RESULT"; then STATUS=PASS; else STATUS=FAIL; fi
echo "== $STATUS ($(grep -m1 '^Time:' "$RESULT"))"
grep -E 'eval sizes|eval synthetic notes=|eval RESULT|eval synthetic invalid=' "$LOG" | sed 's/.*SEBRAIN_EVAL[^:]*: //'
echo "Log (numbers only): $LOG"
[ $STATUS = PASS ] || { echo "Instrumentation output: $RESULT"; exit 1; }
