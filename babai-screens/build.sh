#!/usr/bin/env bash
# Builds Babai Screens (dist/BabaiScreens.apk) without the Android SDK:
#   tools/get-tools.sh <tools dir>      (once)
#   ./build.sh <tools dir>
# Signed with $BS_KS (default keystore/, kept out of git). Keep that file safe: every update must be
# signed with the same key, or the TVs refuse it.
set -euo pipefail
TOOLS=$(realpath "$1"); HERE=$(cd "$(dirname "$0")" && pwd)
VERSION_CODE=7
VERSION_NAME=1.6
KS="${BS_KS:-$HERE/keystore/babai-screens.p12}"; KS_PASS="${BS_KS_PASS:-babai-screens}" # the signing key: never in git
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
CP_TOOLS="$TOOLS/lib/*"

echo "== tests"
mkdir -p "$WORK/t"
javac -nowarn -d "$WORK/t" "$HERE/src/com/babai/screens/Net.java" "$HERE/test/NetTest.java"
java -cp "$WORK/t" com.babai.screens.NetTest

echo "== compile"
mkdir -p "$WORK/gen/com/babai/screens" "$WORK/classes"
printf 'package com.babai.screens;\n\nfinal class Version {\n    static final int CODE = %s;\n    static final String NAME = "%s";\n    private Version() {}\n}\n' "$VERSION_CODE" "$VERSION_NAME" > "$WORK/gen/com/babai/screens/Version.java"
# the Dosa board (1.6): the Babai Dosa Board's drawing code, shared with the KDS 7.8 patch
BOARD="$HERE/../kds-78-board"
javac -nowarn --release 8 -d "$WORK/classes" -cp "$TOOLS/android-all.jar" $(find "$HERE/src" "$WORK/gen" -name '*.java') "$BOARD/src/com/babai/board/BoardModel.java" "$BOARD/src/com/babai/board/BoardView.java"
java -cp "$CP_TOOLS" com.android.dx.command.Main --dex --min-sdk-version=21 --output="$WORK/classes.dex" "$WORK/classes"

echo "== package"
mkdir -p "$WORK/proj"
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" "$HERE/AndroidManifest.xml" > "$WORK/proj/AndroidManifest.xml"
cp -r "$HERE/res" "$WORK/proj/res"
mkdir -p "$WORK/proj/assets" && cp -r "$BOARD/assets/babai_board" "$WORK/proj/assets/" # the board's fonts (OFL) and pictures
cat > "$WORK/proj/apktool.yml" <<YML
version: 2.10.0
apkFileName: BabaiScreens.apk
isFrameworkApk: false
usesFramework:
  ids:
  - 1
sdkInfo:
  minSdkVersion: 21
  targetSdkVersion: 29
packageInfo:
  forcedPackageId: 127
versionInfo:
  versionCode: $VERSION_CODE
  versionName: $VERSION_NAME
doNotCompress:
- png
- resources.arsc
YML
java -cp "$CP_TOOLS" brut.apktool.Main b -o "$WORK/res.apk" "$WORK/proj" >/dev/null
(cd "$WORK" && zip -q res.apk classes.dex)
# resources.arsc stored (not compressed), as Android 11+ wants
(cd "$WORK" && unzip -q -o res.apk resources.arsc && zip -q -d res.apk resources.arsc && zip -q -0 res.apk resources.arsc)

echo "== sign"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -storetype PKCS12 -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias babai \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Babai Screens, O=Babai Tiffins" >/dev/null 2>&1
  echo "new signing key: $KS (keep a safe copy outside git; updates need the same key)"
fi
mkdir -p "$WORK/signer"
javac -nowarn --release 8 -d "$WORK/signer" -cp "$CP_TOOLS" "$HERE/tools/Sign.java"
# apksig 2.3.0 (the newest on Maven Central) signs v1 only on Java 8-11: SIGN_JAVA, else Java 11 if installed
SIGN_JAVA="${SIGN_JAVA:-$(ls /usr/lib/jvm/java-11-openjdk-*/bin/java 2>/dev/null | head -1)}"; SIGN_JAVA="${SIGN_JAVA:-java}"
mkdir -p "$HERE/dist"
"$SIGN_JAVA" --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED -cp "$WORK/signer:$CP_TOOLS" Sign "$WORK/res.apk" "$HERE/dist/BabaiScreens.apk" "$KS" "$KS_PASS" babai
javac -nowarn --release 8 -d "$WORK/signer" -cp "$CP_TOOLS" "$HERE/tools/Verify.java"
"$SIGN_JAVA" --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED -cp "$WORK/signer:$CP_TOOLS" Verify "$HERE/dist/BabaiScreens.apk"
unzip -v "$HERE/dist/BabaiScreens.apk" resources.arsc | grep -q " Stored " || { echo "resources.arsc is compressed"; exit 1; }
echo "== done: dist/BabaiScreens.apk ($(du -k "$HERE/dist/BabaiScreens.apk" | cut -f1) KB, version $VERSION_NAME / $VERSION_CODE)"
