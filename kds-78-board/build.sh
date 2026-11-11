#!/usr/bin/env bash
# Builds Petpooja KDS 7.8 + the Babai Dosa Board, as a patch on the ORIGINAL Petpooja APK:
#
#   ./build.sh <Petpooja_kds_7_8.apk> <out.apk> <tools dir>
#
# tools dir (as babai-screens/tools/get-tools.sh fetches it): android-all.jar and lib/ with
# apktool-cli 2.10.0, dalvik-dx 16.0.1, apksig 2.3.0. Signed with $BS_KS (default: the Babai
# Screens key, babai-screens/keystore, never in git).
#
# What changes in the APK (everything else is copied byte for byte):
#   classes4.dex  two calls added to Petpooja's code:
#                   MainActivity.onCreate (end)        Board.install(this)  the DOSA BOARD button
#                   DashboardViewModel.<init> (end)    Board.attach(this)   the live board, read only
#   classes6.dex  new: com.babai.board (the Dosa Board)
#   assets/babai_board/  the design's fonts (Montserrat, Figtree; OFL), mascot and logo
set -euo pipefail

IN=$(realpath "$1"); OUT=$(realpath -m "$2"); TOOLS=$(realpath "$3")
HERE=$(cd "$(dirname "$0")" && pwd)
CP_TOOLS="$TOOLS/lib/*"
KS="${BS_KS:-$HERE/../babai-screens/keystore/babai-screens.p12}"; KS_PASS="${BS_KS_PASS:-babai-screens}"
SIGN_JAVA="${SIGN_JAVA:-$(ls /usr/lib/jvm/java-11-openjdk-*/bin/java 2>/dev/null | head -1)}"; SIGN_JAVA="${SIGN_JAVA:-java}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== unit tests"
javac -d "$WORK/test" "$HERE/src/com/babai/board/BoardModel.java" "$HERE/test/BoardModelTest.java"
java -cp "$WORK/test" BoardModelTest | tail -1

echo "== compile the board"
mkdir -p "$WORK/stubs" "$WORK/classes" "$WORK/dex"
javac -nowarn --release 8 -d "$WORK/stubs" -cp "$TOOLS/android-all.jar" $(find "$HERE/stubs" -name '*.java')
javac -nowarn --release 8 -d "$WORK/classes" -cp "$TOOLS/android-all.jar:$WORK/stubs" $(find "$HERE/src" -name '*.java')
java -cp "$CP_TOOLS" com.android.dx.command.Main --dex --min-sdk-version=21 --output="$WORK/dex/classes.dex" "$WORK/classes"

echo "== patch Petpooja's classes4 (two calls)"
java -cp "$CP_TOOLS" brut.apktool.Main d -r -f -o "$WORK/dec" "$IN" >/dev/null
python3 - "$WORK/dec/smali_classes4/com/pp/kds" <<'PY'
import re, sys
base = sys.argv[1]
def hook(path, method_re, call):
    s = open(path).read()
    if call in s:
        return
    m = re.search(method_re + r"(.*?)(    return-void\n\.end method)", s, re.S)
    assert m and m.group(1).count("return-void") == 0, "unexpected shape: " + path
    s = s[:m.start(2)] + "    " + call + "\n\n" + s[m.start(2):]
    open(path, "w").write(s)
hook(base + "/MainActivity.smali", r"\.method protected onCreate\(Landroid/os/Bundle;\)V",
     "invoke-static {p0}, Lcom/babai/board/Board;->install(Landroid/app/Activity;)V")
hook(base + "/feature/dashboard/DashboardViewModel.smali", r"\.method public constructor <init>\(Landroid/content/Context;[^\n]*\)V",
     "invoke-static/range {p0 .. p0}, Lcom/babai/board/Board;->attach(Ljava/lang/Object;)V")
PY
grep -q "Board;->install" "$WORK/dec/smali_classes4/com/pp/kds/MainActivity.smali"
grep -q "Board;->attach" "$WORK/dec/smali_classes4/com/pp/kds/feature/dashboard/DashboardViewModel.smali"
for d in "$WORK"/dec/smali*; do [ "$(basename "$d")" = smali_classes4 ] || rm -rf "$d"; done
mv "$WORK/dec/smali_classes4" "$WORK/dec/smali"
java -cp "$CP_TOOLS" brut.apktool.Main b -o "$WORK/c4.apk" "$WORK/dec" >/dev/null
unzip -p "$WORK/c4.apk" classes.dex > "$WORK/classes4.dex"

echo "== assemble (the original APK, two dex files and the assets replaced / added)"
mkdir -p "$WORK/apk/assets"
cp "$IN" "$WORK/unsigned.apk"
zip -q -d "$WORK/unsigned.apk" 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.EC' 'META-INF/*.DSA' >/dev/null 2>&1 || true
cp "$WORK/classes4.dex" "$WORK/apk/classes4.dex"
cp "$WORK/dex/classes.dex" "$WORK/apk/classes6.dex"
cp -r "$HERE/assets/babai_board" "$WORK/apk/assets/"
(cd "$WORK/apk" && zip -q -r "$WORK/unsigned.apk" classes4.dex classes6.dex assets)

echo "== align, sign (v1 + v2) and check"
javac -nowarn --release 8 -d "$WORK/signer" -cp "$CP_TOOLS" "$HERE/../babai-screens/tools/Sign.java" "$HERE/../babai-screens/tools/Verify.java" "$HERE/tools/ZipAlign.java"
java -cp "$WORK/signer" ZipAlign "$WORK/unsigned.apk" "$WORK/aligned.apk"
mv "$WORK/aligned.apk" "$WORK/unsigned.apk"
SX="--add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED"
"$SIGN_JAVA" $SX -cp "$WORK/signer:$CP_TOOLS" Sign "$WORK/unsigned.apk" "$OUT" "$KS" "$KS_PASS" babai >/dev/null
"$SIGN_JAVA" $SX -cp "$WORK/signer:$CP_TOOLS" Verify "$OUT"
python3 - "$OUT" <<'PY'
import struct, sys, zipfile
# stored entries must be 4-byte aligned (resources.arsc: Android 11+ refuses the app otherwise)
f = open(sys.argv[1], "rb").read()
bad = []
for zi in zipfile.ZipFile(sys.argv[1]).infolist():
    if zi.compress_type != 0: continue
    o = zi.header_offset
    n, e = struct.unpack("<HH", f[o + 26:o + 30])
    data = o + 30 + n + e
    if data % (4096 if zi.filename.endswith(".so") else 4): bad.append(zi.filename)
print("alignment ok" if not bad else "NOT ALIGNED: " + ", ".join(bad[:5]))
sys.exit(1 if bad else 0)
PY
echo "== done: $OUT"
