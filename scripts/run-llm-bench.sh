#!/bin/sh
# Compares the candidate LLM files on the phone, one process per model and backend.
# The device must be unlocked and stay untouched; the screen stays on by itself.
#
# Usage: scripts/run-llm-bench.sh <serial> [runs, default "e4b-abliterated:gpu e4b-abliterated:cpu e2b-uncensored:gpu e2b-uncensored:cpu"]
set -u

SERIAL=$1
RUNS=${2:-"e4b-abliterated:gpu e4b-abliterated:cpu e2b-uncensored:gpu e2b-uncensored:cpu"}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT=$ROOT/build/llm-bench
MODELS=$ROOT/tools/models
PACKAGE=com.sebrain.diag
mkdir -p "$OUT"

$ADB get-state > /dev/null 2>&1 || { echo "Device $SERIAL not found"; exit 1; }
$ADB shell dumpsys trust | grep -m1 -q 'deviceLocked=1' && { echo "The device is locked. Unlock it and start again."; exit 1; }

(cd "$ROOT/spikes/diag" && ./gradlew -q assembleDebug > "$OUT/build.log" 2>&1) || { echo "Build failed, see $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/spikes/diag/app/build/outputs/apk/debug/app-debug.apk" > /dev/null

# Models are pushed from the Mac; the app verifies their pinned SHA-256 and downloads nothing.
for f in gemma-4-E4B-it-abliterated.litertlm gemma-4-E2B-it-Uncensored-MAX.litertlm; do
    [ -f "$MODELS/$f" ] || { echo "Missing $MODELS/$f, skipping it"; continue; }
    if ! $ADB shell run-as $PACKAGE ls "files/models/$f" > /dev/null 2>&1; then
        echo "pushing $f"
        $ADB push "$MODELS/$f" "/data/local/tmp/$f" > /dev/null
        $ADB shell "run-as $PACKAGE mkdir -p files/models; cat /data/local/tmp/$f | run-as $PACKAGE sh -c 'cat > files/models/$f'; rm /data/local/tmp/$f"
    fi
done

$ADB shell svc power stayon usb
for run in $RUNS; do
    model=${run%%:*}
    backend=${run##*:}
    echo "== $model on $backend"
    $ADB logcat -c
    # Streamed to a file: the device buffer rotates during long runs and loses the early lines.
    $ADB logcat -v time -s SEBRAIN_DIAG:V > "$OUT/$model-$backend.log" &
    LOGCAT_PID=$!
    $ADB shell am start -S -n $PACKAGE/.MainActivity --es mode llm --es model "$model" --es backend "$backend" > /dev/null
    i=0
    until grep -q "LLM_DONE $model $backend" "$OUT/$model-$backend.log"; do
        sleep 3; i=$((i + 1))
        [ $i -gt 200 ] && { echo "timed out"; break; }
    done
    kill $LOGCAT_PID 2> /dev/null
    $ADB logcat -d -v time | grep -E "E/litert|E litert|E/native|lowmemorykiller.*sebrain" | tail -8 > "$OUT/$model-$backend-errors.log"
    $ADB shell am force-stop $PACKAGE
    grep -E "RESULT\|" "$OUT/$model-$backend.log" | sed 's/.*RESULT|//'
    sleep 60
done
$ADB shell svc power stayon false
$ADB shell input keyevent KEYCODE_HOME
