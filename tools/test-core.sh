#!/bin/sh
# Compile the PENSY core and run its tests on the JVM.
# Needs kotlinc (KOTLINC=/path/to/kotlinc or in PATH) and a JDK.
set -e
cd "$(dirname "$0")/.."
KOTLINC=${KOTLINC:-kotlinc}
OUT=build/core-test
rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC" -nowarn core/src core/test -d "$OUT/tests.jar" -include-runtime
java -cp "$OUT/tests.jar" app.pensy.core.TestsKt
