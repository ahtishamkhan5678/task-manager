#!/usr/bin/env bash
# Compile everything into out/ and run the offline test suite (no API key needed).
set -e
cd "$(dirname "$0")"
rm -rf out
javac -d out $(find src -name "*.java")
java -cp out test.TestRunner
