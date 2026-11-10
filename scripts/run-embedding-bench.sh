#!/bin/sh
# EmbeddingGemma benchmark on the phone: NPU, GPU and CPU, one process each.
# The device must be unlocked and stay untouched; the screen stays on by itself.
#
# Usage: scripts/run-embedding-bench.sh <serial> [minutes per backend, default 10] [backends, default "npu gpu cpu"]
set -u

SERIAL=$1
MINUTES=${2:-10}
BACKENDS=${3:-"npu gpu cpu"}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB="$SDK/platform-tools/adb -s $SERIAL"
OUT=$ROOT/build/embedding-bench
MODELS=$ROOT/tools/models
PACKAGE=com.sebrain.diag
mkdir -p "$OUT"

$ADB get-state > /dev/null 2>&1 || { echo "Device $SERIAL not found"; exit 1; }
$ADB shell dumpsys trust | grep -m1 -q 'deviceLocked=1' && { echo "The device is locked. Unlock it and start again."; exit 1; }

(cd "$ROOT/spikes/diag" && ./gradlew -q assembleDebug > "$OUT/build.log" 2>&1) || { echo "Build failed, see $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/spikes/diag/app/build/outputs/apk/debug/app-debug.apk" > /dev/null

# Models are pushed from the Mac; the app verifies their pinned SHA-256 and downloads nothing.
for f in embeddinggemma-2-text-270m.litertlm embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm; do
    [ -f "$MODELS/$f" ] || { echo "Missing $MODELS/$f"; exit 1; }
    if ! $ADB shell run-as $PACKAGE ls "files/models/$f" > /dev/null 2>&1; then
        $ADB push "$MODELS/$f" "/data/local/tmp/$f" > /dev/null
        $ADB shell "run-as $PACKAGE mkdir -p files/models; cat /data/local/tmp/$f | run-as $PACKAGE sh -c 'cat > files/models/$f'; rm /data/local/tmp/$f"
    fi
done

$ADB shell svc power stayon usb
for backend in $BACKENDS; do
    echo "== $backend"
    $ADB logcat -c
    $ADB shell am start -S -n $PACKAGE/.MainActivity --es mode embed --es backend "$backend" --ei minutes "$MINUTES" > /dev/null
    i=0
    limit=$(( (MINUTES + 4) * 20 ))
    until $ADB logcat -d -s SEBRAIN_DIAG:I | grep -q "EMBED_DONE $backend"; do
        sleep 3; i=$((i + 1))
        [ $i -gt $limit ] && { echo "timed out"; break; }
    done
    $ADB logcat -d -v time -s SEBRAIN_DIAG:V > "$OUT/$backend.log"
    $ADB logcat -d -v time | grep -E "E/litert|E litert|Failed to initialize Dispatch" | tail -5 > "$OUT/$backend-errors.log"
    $ADB shell am force-stop $PACKAGE
    grep -E "RESULT\|" "$OUT/$backend.log" | sed 's/.*RESULT|//'
    # Cool down before the next backend.
    [ "$MINUTES" -gt 0 ] && sleep 120
done
$ADB shell svc power stayon false
$ADB shell input keyevent KEYCODE_HOME
echo "== vector heads (same text; close values mean the backends agree)"
grep -h "vector head" "$OUT"/*.log | sed 's/.*SEBRAIN_DIAG[^:]*: //'
