#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Compiles and runs the :core engine tests with nothing but a JDK and the
# Kotlin compiler — no Gradle, no Android SDK, no network.
#
# This is the same test registry that `./gradlew :core:test` executes through
# the JUnit wrapper; it exists so the deterministic engine (template, sheet
# extraction, rig, animation, export validation, persistence) can be verified
# on machines where the Android toolchain is unavailable.
#
# Usage:
#   KOTLIN_HOME=/path/to/kotlin-compiler tools/run_core_tests.sh
#
# KOTLIN_HOME must contain lib/kotlin-compiler.jar and lib/kotlin-stdlib.jar
# (any Kotlin 2.x compiler distribution works).
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
OUT="${BUILD_DIR:-/tmp/rigstudio-core-tests}"

if [[ -z "${KOTLIN_HOME:-}" ]]; then
  echo "error: set KOTLIN_HOME to a Kotlin compiler distribution" >&2
  exit 2
fi
if [[ ! -f "$KOTLIN_HOME/lib/kotlin-compiler.jar" ]]; then
  echo "error: $KOTLIN_HOME/lib/kotlin-compiler.jar not found" >&2
  exit 2
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

kotlinc() {
  "$JAVA_BIN" -Xmx1400m -cp "$KOTLIN_HOME/lib/kotlin-compiler.jar" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"
}

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "==> compiling core sources"
MAIN_SOURCES=$(find "$CORE/src/main/kotlin" -name '*.kt' | sort)
kotlinc -no-stdlib -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar" \
  -d "$OUT/classes" $MAIN_SOURCES

echo "==> compiling core tests"
# The JUnit wrapper is skipped here: it needs junit.jar, which a Gradle build
# resolves but this offline runner does not.
TEST_SOURCES=$(find "$CORE/src/test/kotlin" -name '*.kt' ! -name 'CoreLibraryTest.kt' | sort)
kotlinc -no-stdlib -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar:$OUT/classes" \
  -d "$OUT/classes" $TEST_SOURCES

echo "==> running ${CORE##*/} test suites"
"$JAVA_BIN" -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar:$OUT/classes" \
  com.rigstudio.core.tests.CoreTestSuitesKt
