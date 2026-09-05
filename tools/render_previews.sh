#!/usr/bin/env bash
# Renders real animation frames from a character sheet using only the core engine (no Android
# SDK): extract -> rig -> forward kinematics -> draw lists -> a naive JVM blitter. Writes one
# filmstrip PNG per (view, clip) plus a contact sheet, and fails if any frame is empty.
#
#   bash tools/render_previews.sh [sheet.png] [outDir] [framesPerClip]
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
ROOT=$(pwd)

: "${JAVA_HOME:=/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime}"
: "${KOTLIN_HOME:=/tmp/kc/package}"
SHEET=${1:-docs/assets/sample-character-sheet.png}
OUT=${2:-/tmp/rigstudio-previews}
FRAMES=${3:-6}

if [ ! -f "$KOTLIN_HOME/lib/kotlin-compiler.jar" ]; then
    echo "render_previews: no kotlinc at $KOTLIN_HOME - set KOTLIN_HOME" >&2
    exit 2
fi
KC() { "$JAVA_HOME/bin/java" -Xmx1400m -cp "$KOTLIN_HOME/lib/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"; }

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/core" "$BUILD/tool" "$OUT"

KC -nowarn -no-stdlib -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar" -d "$BUILD/core" \
    $(find "$ROOT/core/src/main/kotlin" -name '*.kt' | sort) 2>&1 | grep "error:" | head -20
KC -nowarn -no-stdlib -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar:$BUILD/core" -d "$BUILD/tool" \
    "$ROOT/tools/kotlin/PreviewRender.kt" 2>&1 | grep "error:" | head -20

"$JAVA_HOME/bin/java" -Xmx1200m -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar:$BUILD/core:$BUILD/tool" \
    PreviewRenderKt "$SHEET" "$OUT" "$FRAMES"
