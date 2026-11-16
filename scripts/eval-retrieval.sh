#!/bin/sh
# Measures retrieval on the phone's real vault against a golden set of questions.
#
#   scripts/eval-retrieval.sh golden.csv
#
# golden.csv has columns id,question,expected_title: a real question and the title of the
# note that answers it. The file is copied into the app's private folder, the measurement runs
# with the app's own model and vault, and only recall@1/5/8, MRR and per-question ranks are
# printed. The phone must be unlocked and SeBrain must not be open.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
ADB=$SDK/platform-tools/adb
[ -f "${1:-}" ] || { echo "Usage: $0 golden.csv"; exit 1; }
$ADB push "$1" /data/local/tmp/sebrain-golden.csv >/dev/null
$ADB shell "run-as com.sebrain.app sh -c 'mkdir -p files/eval && cp /data/local/tmp/sebrain-golden.csv files/eval/golden.csv'"
$ADB shell rm /data/local/tmp/sebrain-golden.csv
$ADB shell am force-stop com.sebrain.app
$ADB logcat -c
$ADB shell am instrument -w -e class com.sebrain.app.RetrievalEval com.sebrain.app.test/androidx.test.runner.AndroidJUnitRunner | grep -E "^OK|FAIL|Exception" || true
$ADB logcat -d -s SEBRAIN_EVAL:I | sed 's/.*SEBRAIN_EVAL: //' | grep "^eval"
$ADB shell "run-as com.sebrain.app rm -f files/eval/golden.csv"
