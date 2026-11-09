#!/bin/sh
# Generates sqlite3.c and sqlite3.h from the pinned SQLCipher sources.
# The build runs out of tree, so the submodule stays clean.
#
# Usage: generate-amalgamation.sh <output-dir> <work-dir>
set -eu

OUT=$1
WORK=$2
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
SRC=$ROOT/third_party/sqlcipher-android/sqlcipher/src/main/jni/sqlcipher/src
TCLSH=$ROOT/tools/tcl/bin/tclsh8.6
# Amalgamation of SQLCipher 4.20.0 (SQLite 3.54.1). Update together with the submodule.
SQLITE3_C_SHA256=8cd2347d18886fee4651153fabeeeffb7cec008700987dab1c00261febc4220f

if [ ! -x "$TCLSH" ]; then
    echo "Missing $TCLSH. Run scripts/build-tcl.sh first." >&2
    exit 1
fi
if [ ! -f "$SRC/configure" ]; then
    echo "Missing SQLCipher sources. Run: git submodule update --init --recursive" >&2
    exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"
cd "$WORK"
"$SRC/configure" --with-tempstore=yes --disable-tcl --with-tclsh="$TCLSH"
make sqlite3.c
echo "$SQLITE3_C_SHA256  sqlite3.c" | shasum -a 256 -c -
cp sqlite3.c sqlite3.h "$OUT/"
