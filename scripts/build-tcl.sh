#!/bin/sh
# Builds the pinned Tcl into tools/tcl. It is only used to generate sqlite3.c.
# Nothing is installed outside the project; tools/ is not committed.
set -eu

VERSION=8.6.18
SHA256=14f9af32b1767ff718477a8f974ad03c34341097e6b43f4ce54644ee974e268e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=$ROOT/tools
ARCHIVE=$TOOLS/dl/tcl$VERSION-src.tar.gz

mkdir -p "$TOOLS/dl" "$TOOLS/src"
if [ ! -f "$ARCHIVE" ]; then
    curl -fsSL -o "$ARCHIVE" \
        "https://sourceforge.net/projects/tcl/files/Tcl/$VERSION/tcl$VERSION-src.tar.gz/download"
fi
echo "$SHA256  $ARCHIVE" | shasum -a 256 -c -

tar xzf "$ARCHIVE" -C "$TOOLS/src"
cd "$TOOLS/src/tcl$VERSION/unix"
./configure --prefix="$TOOLS/tcl" --disable-shared
make -j8
make install
