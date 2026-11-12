#!/bin/bash
# Run the JVM unit tests for the pure-Kotlin units (AdBlockEngine, UrlUtils,
# CosmeticFilter). No framework: each test file has a main() that prints its
# pass/fail counts and exits non-zero on any failure.
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FILES_ROOT="${NOVA_FILES_ROOT:-$ROOT/../nova-files}"
[ -d "$FILES_ROOT" ] || FILES_ROOT="$ROOT/../NOVA-Files"
[ -d "$FILES_ROOT" ] || FILES_ROOT="$HOME/workspace/nova-files"
export JAVA_HOME="${JAVA_HOME:-$FILES_ROOT/tools/jdk-17.0.21+9}"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="$FILES_ROOT/tools/kotlinc/bin/kotlinc"
[ -x "$KOTLINC" ] || { echo "kotlinc not found at $KOTLINC"; exit 1; }

OUT="$ROOT/build/test-classes"
rm -rf "$OUT"
mkdir -p "$OUT"

SRC="$ROOT/app/src/main/java"
"$KOTLINC" -jvm-target 17 -d "$OUT" \
    "$SRC/com/nova/browser/adblock/AdBlockEngine.kt" \
    "$SRC/com/nova/browser/web/UrlUtils.kt" \
    "$SRC/com/nova/browser/web/CosmeticFilter.kt" \
    "$ROOT/tests/AdBlockEngineTest.kt" \
    "$ROOT/tests/UrlUtilsTest.kt" \
    "$ROOT/tests/CosmeticFilterTest.kt"

# kotlin-stdlib for the test run (the sources use only the stdlib).
STDLIB="$FILES_ROOT/tools/kotlinc/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || STDLIB="$FILES_ROOT/deps/kotlin-stdlib-2.0.21.jar"

FAIL=0
for main in \
    com.nova.browser.adblock.AdBlockEngineTestKt \
    com.nova.browser.web.UrlUtilsTestKt \
    com.nova.browser.web.CosmeticFilterTestKt; do
    java -cp "$OUT:$STDLIB" "$main" || FAIL=1
done
if [ "$FAIL" -eq 0 ]; then
    echo "ALL JVM TESTS PASSED"
else
    echo "SOME JVM TESTS FAILED"
    exit 1
fi
