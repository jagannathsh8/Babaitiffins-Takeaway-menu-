#!/usr/bin/env bash
# Builds Petpooja KDS V11 + "Dosa Live Wait" from the stable V11 APK.
#
#   ./build.sh <input V11.apk> <output.apk> <tools dir>
#
# tools dir must contain: apktool.jar (2.10+), jadx-1.5.1-all.jar (provides D8),
# android-all.jar (Robolectric android-all 14, compile-only), uber-apk-signer.jar
#
# What changes in the APK:
#   1. classes4.dex  - MainActivity.onCreate gets ONE extra line: DosaLive.install(this);
#                      the old camera ScannerActivity is removed (replaced from src/)
#   2. classes7.dex  - new, contains com.pp.kds.dosa.* and the new com.pp.kds.scan.ScannerActivity
#   3. assets/ - mascot, food photos, prep_profile.json + prep_days.txt (make_prep_profile.py)
# All other dex files, resources and the manifest are copied byte-for-byte from the input,
# ScannerBridge (marks Food Ready) and the board logic are untouched.
set -euo pipefail

IN=$(realpath "$1"); OUT=$(realpath -m "$2"); TOOLS=$(realpath "$3")
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== unit tests"
javac -d "$WORK/test" "$HERE/src/com/pp/kds/dosa/DosaStats.java" "$HERE/src/com/pp/kds/dosa/PrepStats.java" \
    "$HERE/test/DosaStatsTest.java" "$HERE/test/PrepStatsTest.java"
java -cp "$WORK/test" DosaStatsTest | tail -1
java -cp "$WORK/test" PrepStatsTest | tail -1

echo "== compile add-on"
mkdir -p "$WORK/stubs" "$WORK/classes" "$WORK/dex"
javac -nowarn --release 8 -d "$WORK/stubs" -cp "$TOOLS/android-all.jar" $(find "$HERE/stubs" -name '*.java')
javac -nowarn --release 8 -d "$WORK/classes" -cp "$TOOLS/android-all.jar:$WORK/stubs" \
    $(find "$HERE/src" -name '*.java')
java -cp "$TOOLS/jadx-1.5.1-all.jar" com.android.tools.r8.D8 --release --min-api 21 \
    --lib "$TOOLS/android-all.jar" --lib "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" --classpath "$WORK/stubs" \
    --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')

echo "== patch MainActivity (classes4)"
java -jar "$TOOLS/apktool.jar" d -r -f -o "$WORK/dec" "$IN" >/dev/null
SMALI="$WORK/dec/smali_classes4/com/pp/kds/MainActivity.smali"
python3 - "$SMALI" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read()
hook = "    invoke-static {p0}, Lcom/pp/kds/dosa/DosaLive;->install(Landroid/app/Activity;)V\n\n"
if "DosaLive" not in s:
    m = re.search(r"(\.method protected onCreate\(Landroid/os/Bundle;\)V.*?)(    return-void\n\.end method)", s, re.S)
    assert m and m.group(1).count("return-void") == 0, "unexpected onCreate shape"
    s = s[:m.start(2)] + hook + s[m.start(2):]
    open(p, "w").write(s)
PY
grep -q "DosaLive;->install" "$SMALI"
# The camera scan screen is replaced by src/com/pp/kds/scan/ScannerActivity.java (same class name,
# so the manifest and the KDS camera button stay as they are). ScannerBridge is kept unchanged.
rm -f "$WORK"/dec/smali_classes4/com/pp/kds/scan/ScannerActivity*.smali
# Keep only the classes4 sources for the rebuild; every other dex is reused unchanged.
for d in "$WORK"/dec/smali*; do [ "$(basename "$d")" = smali_classes4 ] || rm -rf "$d"; done
mv "$WORK/dec/smali_classes4" "$WORK/dec/smali"
java -jar "$TOOLS/apktool.jar" b -o "$WORK/c4.apk" "$WORK/dec" >/dev/null
unzip -p "$WORK/c4.apk" classes.dex > "$WORK/classes4.dex"

echo "== assemble"
mkdir -p "$WORK/apk"
cp "$IN" "$WORK/unsigned.apk"
zip -q -d "$WORK/unsigned.apk" 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.EC' 'META-INF/*.DSA' || true
cp "$WORK/classes4.dex" "$WORK/apk/classes4.dex"
cp "$WORK/dex/classes.dex" "$WORK/apk/classes7.dex"
mkdir -p "$WORK/apk/assets" && cp "$HERE"/assets/* "$WORK/apk/assets/"
(cd "$WORK/apk" && zip -q "$WORK/unsigned.apk" classes4.dex classes7.dex assets/*)

echo "== align + sign"
java -jar "$TOOLS/uber-apk-signer.jar" -a "$WORK/unsigned.apk" -o "$WORK/signed" ${KS:+--ks "$KS" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS"} >/dev/null
cp "$WORK"/signed/*.apk "$OUT"
echo "== done: $OUT"
