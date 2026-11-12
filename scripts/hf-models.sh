#!/bin/sh
# Finds and fetches LiteRT-LM models from Hugging Face on the Mac. The phone app never
# downloads anything: a model reaches it as a file, and the app checks its SHA-256.
#
#   scripts/hf-models.sh search <words>          list .litertlm models, most downloaded first
#   scripts/hf-models.sh files <repo>            list a repo's files with size and SHA-256
#   scripts/hf-models.sh get <repo> <file>       download into tools/models and verify the hash
#   scripts/hf-models.sh push <file> [serial]    copy a downloaded file to the phone's Download folder
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MODELS=$ROOT/tools/models
API=https://huggingface.co/api/models
mkdir -p "$MODELS"

case "${1:-}" in
search)
    shift
    QUERY=$(printf '%s litertlm' "$*" | sed 's/ /%20/g')
    curl -fsS "$API?search=$QUERY&sort=downloads&direction=-1&limit=30" | python3 -I -c '
import sys, json
for m in json.load(sys.stdin):
    gated = "gated" if m.get("gated") else "open"
    print("%-70s downloads=%-8s likes=%-5s %s" % (m["id"], m.get("downloads"), m.get("likes"), gated))'
    ;;
files)
    curl -fsS "$API/$2/tree/main" | python3 -I -c '
import sys, json
for f in json.load(sys.stdin):
    if f["path"].endswith((".litertlm", ".tflite", ".task")):
        print("%-75s %7.2f GB  sha256=%s" % (f["path"], f["size"] / 1e9, (f.get("lfs") or {}).get("oid", "?")))'
    curl -fsS "$API/$2" | python3 -I -c '
import sys, json
d = json.load(sys.stdin)
print("license=%s gated=%s revision=%s" % ((d.get("cardData") or {}).get("license"), d.get("gated"), d.get("sha")))'
    ;;
get)
    REPO=$2
    FILE=$3
    EXPECTED=$(curl -fsS "$API/$REPO/tree/main" | python3 -I -c '
import sys, json
print(next(((f.get("lfs") or {}).get("oid", "") for f in json.load(sys.stdin) if f["path"] == sys.argv[1]), ""))' "$FILE")
    [ -n "$EXPECTED" ] || { echo "No such file in $REPO: $FILE" >&2; exit 1; }
    TARGET=$MODELS/$(basename "$FILE")
    [ -f "$TARGET" ] || curl -fL --progress-bar -o "$TARGET" "https://huggingface.co/$REPO/resolve/main/$FILE"
    ACTUAL=$(shasum -a 256 "$TARGET" | cut -d' ' -f1)
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        rm -f "$TARGET"
        echo "SHA-256 mismatch: the download was deleted" >&2
        exit 1
    fi
    echo "saved $TARGET"
    echo "sha256 $ACTUAL"
    ;;
push)
    FILE=$MODELS/$(basename "$2")
    [ -f "$FILE" ] || { echo "Not downloaded: $FILE" >&2; exit 1; }
    SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
    ADB=$SDK/platform-tools/adb
    [ -n "${3:-}" ] && ADB="$ADB -s $3"
    $ADB push "$FILE" "/sdcard/Download/$(basename "$FILE")"
    echo "On the phone: SeBrain > Import model > Downloads > $(basename "$FILE")"
    ;;
*)
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
