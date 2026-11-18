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
# android.jar on the compile classpath: ClearOnExit/BrowserDb/ErrorLog
# import android.* (the pure WipeCoordinator tests never call Android
# APIs at runtime; the jar is only for compile resolution).
ANDROID_JAR="$HOME/android-sdk/platforms/android-35/android.jar"
"$KOTLINC" -jvm-target 17 -classpath "$ANDROID_JAR" -d "$OUT" \
    "$SRC/com/nova/browser/adblock/AdBlockEngine.kt" \
    "$SRC/com/nova/browser/web/UrlUtils.kt" \
    "$SRC/com/nova/browser/web/CosmeticFilter.kt" \
    "$SRC/com/nova/browser/web/IntentValidator.kt" \
    "$SRC/com/nova/browser/data/TabGroup.kt" \
    "$SRC/com/nova/browser/extensions/Userscript.kt" \
    "$SRC/com/nova/browser/extensions/ExtensionRuntime.kt" \
    "$SRC/com/nova/browser/web/Suggestions.kt" \
    "$SRC/com/nova/browser/web/HttpsEnforcer.kt" \
    "$SRC/com/nova/browser/reader/ReaderExtractor.kt" \
    "$SRC/com/nova/browser/data/Downloader.kt" \
    "$SRC/com/nova/browser/devtools/JsonPretty.kt" \
    "$SRC/com/nova/browser/devtools/CurlBuilder.kt" \
    "$SRC/com/nova/browser/devtools/NetworkLog.kt" \
    "$SRC/com/nova/browser/devtools/ConsoleLog.kt" \
    "$SRC/com/nova/browser/devtools/ElementInspector.kt" \
    "$SRC/com/nova/browser/devtools/HttpClientEngine.kt" \
    "$SRC/com/nova/browser/web/PermissionQueue.kt" \
    "$SRC/com/nova/browser/data/ErrorLog.kt" \
    "$SRC/com/nova/browser/data/BrowserDb.kt" \
    "$SRC/com/nova/browser/data/ClearOnExit.kt" \
    "$SRC/com/nova/browser/devtools/DevToolsHub.kt" \
    "$ROOT/tests/AdBlockEngineTest.kt" \
    "$ROOT/tests/UrlUtilsTest.kt" \
    "$ROOT/tests/CosmeticFilterTest.kt" \
    "$ROOT/tests/TabGroupsTest.kt" \
    "$ROOT/tests/ExtensionsTest.kt" \
    "$ROOT/tests/ReaderSuggestionsTest.kt" \
    "$ROOT/tests/DevToolsTest.kt" \
    "$ROOT/tests/HttpsEnforcerTest.kt" \
    "$ROOT/tests/IntentValidatorTest.kt" \
    "$ROOT/tests/PermissionQueueTest.kt" \
    "$ROOT/tests/ClearOnExitTest.kt"

# kotlin-stdlib for the test run (the sources use only the stdlib).
STDLIB="$FILES_ROOT/tools/kotlinc/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || STDLIB="$FILES_ROOT/deps/kotlin-stdlib-2.0.21.jar"

FAIL=0
for main in \
    com.nova.browser.adblock.AdBlockEngineTestKt \
    com.nova.browser.web.UrlUtilsTestKt \
    com.nova.browser.web.CosmeticFilterTestKt \
    com.nova.browser.data.TabGroupsTestKt \
    com.nova.browser.extensions.ExtensionsTestKt \
    com.nova.browser.web.ReaderSuggestionsTestKt \
    com.nova.browser.devtools.DevToolsTestKt \
    com.nova.browser.web.HttpsEnforcerTestKt \
    com.nova.browser.web.IntentValidatorTestKt \
    com.nova.browser.web.PermissionQueueTestKt \
    com.nova.browser.data.ClearOnExitTestKt; do
    java -cp "$OUT:$STDLIB" "$main" || FAIL=1
done
if [ "$FAIL" -eq 0 ]; then
    echo "ALL JVM TESTS PASSED"
else
    echo "SOME JVM TESTS FAILED"
    exit 1
fi
