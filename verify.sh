#!/usr/bin/env bash
set -eo pipefail
cd "$(dirname "$0")"

JAVAC=""
JAVA=""

# Try PATH first
if command -v javac &>/dev/null; then
  JAVAC="javac"
  JAVA="java"
fi

# Try Windows standard locations
if [ -z "$JAVAC" ]; then
  for dir in \
    "/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin" \
    "/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot/bin" \
    "/c/Program Files/Java/jdk-21/bin" \
    "/c/Program Files/Java/jdk-25/bin"
  do
    if [ -e "$dir/javac.exe" ]; then
      JAVAC="$dir/javac.exe"
      JAVA="$dir/java.exe"
      break
    fi
  done
fi

if [ -z "$JAVAC" ]; then
  echo "ERROR: javac not found. Run ./gradlew selfCheck instead." >&2
  exit 1
fi

echo "==> compiling"
rm -rf build/selfcheck && mkdir -p build/selfcheck
"$JAVAC" -d build/selfcheck $(find src/main/java -name '*.java' \
  ! -name 'MongoDocumentStore.java' \
  ! -name 'App.java')

echo
echo "==> running"
"$JAVA" -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck "$@"
