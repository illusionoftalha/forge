#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
javac -encoding UTF-8 -d build/classes desktop/src/forge/*.java desktop/src/forge/analysis/*.java tests/IntegrationTest.java
python -m unittest discover -s tests -v
java -cp build/classes forge.IntegrationTest
jar --create --file build/forge.jar --main-class forge.Main -C build/classes forge
