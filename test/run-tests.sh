#!/usr/bin/env bash
# Runs the JVM unit tests for the HTTP/JSON core and the device-flow state machine.
# The Android component is not compiled here; the core has no android.* imports.
#
# Needs a JDK (JAVA_HOME) and these test-only jars, passed as environment variables:
#   JUNIT_JAR        junit-4.13.2.jar            (Maven Central junit:junit:4.13.2)
#   HAMCREST_JAR     hamcrest-core-1.3.jar       (Maven Central org.hamcrest:hamcrest-core:1.3)
#   JSON_TEST_JAR    json-20240303.jar           (Maven Central org.json:json:20240303)
# org.json on Android comes from the platform; this jar is only for the desktop JVM.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 8+ install}"
: "${JUNIT_JAR:?set JUNIT_JAR}"
: "${HAMCREST_JAR:?set HAMCREST_JAR}"
: "${JSON_TEST_JAR:?set JSON_TEST_JAR}"

SEP=":"
if command -v cygpath >/dev/null 2>&1; then SEP=";"; fi
cp_join() {
  local out="" p
  for p in "$@"; do
    if [ "$SEP" = ";" ]; then p="$(cygpath -w "$p")"; fi
    out="${out:+$out$SEP}$p"
  done
  printf '%s' "$out"
}

OUT="$ROOT/build/test-classes"
rm -rf "$OUT"
mkdir -p "$OUT"
DEPS=("$JSON_TEST_JAR" "$JUNIT_JAR" "$HAMCREST_JAR")

# Sources: only the platform-free core plus the tests. Live.java is a manual check, see README.
mapfile -t TEST_SOURCES < <(find "$ROOT/test" -name '*.java' ! -name 'LiveCheck.java')
"$JAVA_HOME/bin/javac" --release 8 -nowarn -encoding UTF-8 -d "$OUT" \
  -cp "$(cp_join "${DEPS[@]}")" \
  "$ROOT/src/ai/pollinations/ai2/PollinationsApi.java" \
  "$ROOT/src/ai/pollinations/ai2/DeviceFlow.java" \
  "${TEST_SOURCES[@]}"

"$JAVA_HOME/bin/java" -cp "$(cp_join "$OUT" "${DEPS[@]}")" org.junit.runner.JUnitCore \
  ai.pollinations.ai2.PollinationsApiTest \
  ai.pollinations.ai2.DeviceFlowTest
