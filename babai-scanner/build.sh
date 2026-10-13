#!/usr/bin/env bash
# Builds Babai Scanner (dist/BabaiScanner.apk) without the Android SDK:
#   ./build.sh <tools dir> <zxing core jar>
# tools: apktool.jar (its aapt2 packs the manifest + icon), jadx-1.5.1-all.jar (D8),
#        android-all.jar (compile only), uber-apk-signer.jar.  zxing: com.google.zxing:core 3.5.3.
set -euo pipefail
TOOLS=$(realpath "$1"); ZX=$(realpath "$2"); HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

echo "== tests"
mkdir -p "$WORK/t"
javac -nowarn -d "$WORK/t" -cp "$ZX" "$HERE/src/com/babai/scanner/Decoder.java" "$HERE/src/com/babai/scanner/Hid.java" "$HERE"/test/*.java
java -cp "$WORK/t:$ZX" DecoderTest | tail -1
java -cp "$WORK/t:$ZX" HidTest | tail -1

echo "== compile"
mkdir -p "$WORK/classes" "$WORK/dex"
javac -nowarn --release 8 -d "$WORK/classes" -cp "$TOOLS/android-all.jar:$ZX" $(find "$HERE/src" -name '*.java')
java -cp "$TOOLS/jadx-1.5.1-all.jar" com.android.tools.r8.D8 --release --min-api 24 \
    --lib "$TOOLS/android-all.jar" --output "$WORK/dex" $(find "$WORK/classes" -name '*.class') "$ZX"

echo "== package"
mkdir -p "$WORK/proj"
cp "$HERE/AndroidManifest.xml" "$WORK/proj/"
cp -r "$HERE/res" "$WORK/proj/res"
cat > "$WORK/proj/apktool.yml" <<YML
version: 2.10.0
apkFileName: BabaiScanner.apk
isFrameworkApk: false
usesFramework:
  ids:
  - 1
sdkInfo:
  minSdkVersion: 24
  targetSdkVersion: 33
packageInfo:
  forcedPackageId: 127
versionInfo:
  versionCode: 1
  versionName: 1.0
doNotCompress:
- png
YML
java -jar "$TOOLS/apktool.jar" b -o "$WORK/res.apk" "$WORK/proj" >/dev/null
cp "$WORK/dex/classes.dex" "$WORK/classes.dex"
(cd "$WORK" && zip -q res.apk classes.dex)
java -jar "$TOOLS/uber-apk-signer.jar" -a "$WORK/res.apk" -o "$WORK/signed" ${KS:+--ks "$KS" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS"} >/dev/null
mkdir -p "$HERE/dist"
cp "$WORK"/signed/*.apk "$HERE/dist/BabaiScanner.apk"
echo "== done: dist/BabaiScanner.apk"
