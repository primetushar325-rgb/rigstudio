#!/usr/bin/env bash
# Compile-checks :core and the non-Compose :app sources against a minimal Android API stub set
# (tools/android-stubs/src). The shipping build is Gradle + the real Android SDK; this script keeps
# the engine and app logic honest on any machine that has a JDK and kotlinc - including CI - by
# failing loudly the moment a signature drifts from the real framework APIs the stubs mirror.
#
#   JAVA_HOME=... KOTLIN_HOME=... bash tools/check_app.sh
#
# Both variables are optional here: known sandbox locations are used as fallbacks.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
ROOT=$(pwd)

: "${JAVA_HOME:=/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime}"
: "${KOTLIN_HOME:=/tmp/kc/package}"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "check_app: no java at $JAVA_HOME/bin/java - set JAVA_HOME" >&2
    exit 2
fi
if [ ! -f "$KOTLIN_HOME/lib/kotlin-compiler.jar" ]; then
    echo "check_app: no kotlinc at $KOTLIN_HOME - set KOTLIN_HOME to a Kotlin compiler distribution" >&2
    exit 2
fi

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
KC() { "$JAVA_HOME/bin/java" -Xmx1400m -cp "$KOTLIN_HOME/lib/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"; }

compile() {
    label=$1; out=$2; classpath=$3; shift 3
    echo "==> compiling $label"
    log="$OUT/$label.log"
    KC -nowarn -no-stdlib -cp "$classpath" -d "$out" "$@" >"$log" 2>&1
    if grep -q "error:" "$log"; then
        grep "error:" "$log" | head -40
        echo "check_app: $label FAILED to compile" >&2
        return 1
    fi
    return 0
}

mkdir -p "$OUT/stubs" "$OUT/core" "$OUT/app"

STUB_FILES=$(find "$ROOT/tools/android-stubs/src" -name '*.kt' | sort)
CORE_FILES=$(find "$ROOT/core/src/main/kotlin" -name '*.kt' | sort)
# Compose UI is intentionally excluded: it needs the real Compose compiler and cannot be checked
# without the Android SDK. Everything else - viewmodels, renderers, exporters, storage - is checked.
APP_FILES=$(find "$ROOT/app/src/main/kotlin" -name '*.kt' ! -path '*/ui/*' ! -name 'MainActivity.kt' | sort)

fail=0
compile "android-stubs" "$OUT/stubs" "$KOTLIN_HOME/lib/kotlin-stdlib.jar" $STUB_FILES || fail=1
compile "core" "$OUT/core" "$KOTLIN_HOME/lib/kotlin-stdlib.jar" $CORE_FILES || fail=1
compile "app ($(echo "$APP_FILES" | wc -l | tr -d ' ') sources)" "$OUT/app" \
    "$KOTLIN_HOME/lib/kotlin-stdlib.jar:$OUT/core:$OUT/stubs" $APP_FILES || fail=1

if [ "$fail" -ne 0 ]; then
    echo "==> FAILED"
    exit 1
fi
echo "==> OK: stubs, core and app all compile"
