#!/usr/bin/env bash
# One command that proves the whole RigStudio V3 pipeline still holds together, offline:
#
#   bash tools/verify_all.sh            # engine tests + app compile check + template + sheet checks
#   bash tools/verify_all.sh --drift    # ...and fail if the committed template artefacts are stale
#
# Everything here runs with a JDK, kotlinc and plain Python 3 - no Android SDK, no network, no
# accounts. The Android build itself is Gradle's job; this is the safety net around it.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
ROOT=$(pwd)

: "${JAVA_HOME:=/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime}"
: "${KOTLIN_HOME:=/tmp/kc/package}"
export JAVA_HOME KOTLIN_HOME

DRIFT=0
for arg in "$@"; do
    [ "$arg" = "--drift" ] && DRIFT=1
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0
step() {
    name=$1; shift
    echo
    echo "=================================================================="
    echo "== $name"
    echo "=================================================================="
    if "$@"; then
        passed=$((passed + 1))
        echo "-- PASS: $name"
    else
        failed=$((failed + 1))
        echo "-- FAIL: $name"
    fi
}

step "core engine tests (161)" bash tools/run_core_tests.sh
step "core + app compile check (Android API stubs)" bash tools/check_app.sh
step "dump slot geometry + solved layout" bash tools/dump_slots.sh
step "render blank template PNG" python3 tools/render_template.py
step "template invariant: no guide ink inside any slot" \
    python3 tools/sheet_check.py docs/assets/blank-character-sheet.png --template

check_sheet() {
    variant=$1
    python3 tools/make_test_sheet.py --which "$variant" --out "$WORK/$variant.png" || return 1
    python3 tools/sheet_check.py "$WORK/$variant.png" --json > "$WORK/$variant.json" || true
    python3 tools/sheet_assert.py "$WORK/$variant.json" "$variant"
}

step "synthetic sheet: front only" check_sheet front
step "synthetic sheet: front + left profile" check_sheet front-side
step "synthetic sheet: every slot" check_sheet full

check_sample() {
    python3 tools/make_sample_character.py --out "$WORK/sample.png" || return 1
    python3 tools/sheet_check.py "$WORK/sample.png" --json > "$WORK/sample.json" || return 1
    python3 tools/sheet_assert.py "$WORK/sample.json" sample
}
step "sample character sheet (bundled test character)" check_sample

step "offline preview render: every view x clip renders non-empty frames" \
    bash tools/render_previews.sh docs/assets/sample-character-sheet.png "$WORK/previews" 4

if [ "$DRIFT" = "1" ]; then
    step "committed template artefacts are up to date" \
        git diff --exit-code -- tools/slots.json tools/layout.json \
            docs/assets/blank-character-sheet.png docs/assets/sample-character-sheet.png
fi

echo
echo "=================================================================="
echo "== verify_all: $passed passed, $failed failed"
echo "=================================================================="
[ "$failed" -eq 0 ]
