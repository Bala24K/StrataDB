#!/usr/bin/env bash
# Compiles and runs StrataDB's tests in environments that have a JRE-shaped
# JDK install (jdk.compiler module present, but no `javac` binary on PATH).
#
# On a normal machine with a full JDK and Maven installed, you don't need this
# script at all — just run:
#   mvn test
#
# This script exists purely for sandboxes / minimal containers.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOOTSTRAP="$PROJECT_DIR/scripts/Build.java"

if command -v javac >/dev/null 2>&1; then
  echo "javac found — compiling normally."
  rm -rf "$PROJECT_DIR/out"
  mkdir -p "$PROJECT_DIR/out/classes" "$PROJECT_DIR/out/test-classes"
  javac -d "$PROJECT_DIR/out/classes" $(find "$PROJECT_DIR/src/main/java" -name "*.java")
  javac -cp "$PROJECT_DIR/out/classes" -d "$PROJECT_DIR/out/test-classes" $(find "$PROJECT_DIR/src/test/java" -name "*.java")
else
  echo "javac not found on PATH — using the JavaCompiler API bootstrap."
  rm -rf "$PROJECT_DIR/out"
  java "$BOOTSTRAP" "$PROJECT_DIR"
fi

echo
echo "=== Running tests ==="
java -cp "$PROJECT_DIR/out/classes:$PROJECT_DIR/out/test-classes" com.strata.testutil.TestRunner
