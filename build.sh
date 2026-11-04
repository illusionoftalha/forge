#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes build/test-classes
javac -encoding UTF-8 -d build/classes desktop/src/forge/*.java desktop/src/forge/analysis/*.java
javac -encoding UTF-8 -cp build/classes -d build/test-classes tests/IntegrationTest.java
python -m unittest discover -s tests -v
java -cp build/test-classes:build/classes forge.IntegrationTest
python scripts/package_classes.py
