#!/bin/sh
# Compiles the sources and packages them as cellstore.jar (needs a JDK 21).
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac --release 21 -d out $(find src -name '*.java')
jar --create --file cellstore.jar --main-class ds.cellstore.Main -C out .
echo "built cellstore.jar"
