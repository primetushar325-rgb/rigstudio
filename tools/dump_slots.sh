#!/usr/bin/env bash
# Compiles the core engine plus the slot dumper and writes tools/slots.json.
#
# Requires a JDK and a Kotlin compiler. Both are standard on a dev machine; the paths below are
# overridable so the script also works in sandboxes that install them elsewhere.
#
#   JAVA_HOME=/path/to/jdk KOTLIN_HOME=/path/to/kotlinc bash tools/dump_slots.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/tools/slots.json}"

: "${JAVA_HOME:?set JAVA_HOME to a JDK (needs java)}"
: "${KOTLIN_HOME:?set KOTLIN_HOME to a Kotlin compiler distribution (needs lib/kotlin-compiler.jar)}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

JAVA="$JAVA_HOME/bin/java"
KC="$JAVA -Xmx1200m -cp $KOTLIN_HOME/lib/kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

echo "==> compiling core + slot dumper"
$KC -nowarn -no-stdlib -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar" -d "$WORK/classes" \
  $(find "$ROOT/core/src/main/kotlin" -name '*.kt') "$ROOT/tools/kotlin/SlotDump.kt" 2>&1 \
  | grep -v "^warning:" || true

echo "==> dumping template to $OUT"
$JAVA -cp "$WORK/classes:$KOTLIN_HOME/lib/kotlin-stdlib.jar" com.rigstudio.tools.SlotDumpKt "$OUT"
