#!/usr/bin/env bash
# Fetches the build tools into <dir> (Maven Central only): apktool (with aapt2), dx, apksig and
# Robolectric's android-all (compile-only Android API).
set -euo pipefail
OUT=$(realpath -m "$1"); HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT/lib"
MVN=$(command -v mvn || echo /opt/apache-maven-3.9.11/bin/mvn)
(cd "$HERE" && "$MVN" -q dependency:copy-dependencies -DoutputDirectory="$OUT/lib")
[ -f "$OUT/android-all.jar" ] || curl -sSfo "$OUT/android-all.jar" https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
echo "tools in $OUT"
