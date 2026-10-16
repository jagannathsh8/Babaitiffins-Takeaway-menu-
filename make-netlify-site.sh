#!/usr/bin/env bash
# Builds the folder to deploy on Netlify (babaidosa): customer page, indent planner, Babai Console,
# the old hub's last version, and the KDS and Babai Scanner apps.
#   ./make-netlify-site.sh <firebase db host> <out dir>
set -euo pipefail
DB="$1"; OUT=$(realpath -m "$2"); HERE=$(cd "$(dirname "$0")" && pwd)
rm -rf "$OUT"; mkdir -p "$OUT/hub" "$OUT/indent" "$OUT/kds" "$OUT/console" "$OUT/scanner"
sed "s/var BUILT_IN='__BABAI_DB__';/var BUILT_IN='$DB';/" "$HERE/dosa/index.html" > "$OUT/index.html"
cp "$HERE/indent/index.html" "$HERE/indent/plan.js" "$HERE/indent/xlsx.full.min.js" "$HERE/indent/xlsx.LICENSE.txt" "$OUT/indent/"
# Babai Console (installs as a service; updates itself from console/version.json)
cp "$HERE/babai-hub/dist/babai-console.exe" "$OUT/console/"
V=$(grep -o 'version     = "[0-9.]*"' "$HERE/babai-hub/main.go" | grep -o '[0-9.]*')
SUM=$(sha256sum "$OUT/console/babai-console.exe" | cut -d' ' -f1)
printf '{"v":"%s","url":"babai-console.exe","sha256":"%s"}\n' "$V" "$SUM" > "$OUT/console/version.json"
# The old Babai Hub keeps its last version (2.12): it must not turn into the Console by itself.
if [ -f "$HERE/babai-hub/dist/babai-hub.exe" ]; then
  cp "$HERE/babai-hub/dist/babai-hub.exe" "$OUT/hub/"
  HSUM=$(sha256sum "$OUT/hub/babai-hub.exe" | cut -d' ' -f1)
  printf '{"v":"2.12","url":"babai-hub.exe","sha256":"%s"}\n' "$HSUM" > "$OUT/hub/version.json"
fi
# KDS app (AppUpdate.BUILD): the screens update from here, or from the hub's copy of it
APK="$HERE/kds-dosa-live/dist/Petpooja_KDS_V11_DOSA_LIVE.apk"
if [ -f "$APK" ]; then
  B=$(grep -o 'static final int BUILD = [0-9]*' "$HERE/kds-dosa-live/src/com/pp/kds/dosa/AppUpdate.java" | grep -o '[0-9]*$')
  cp "$APK" "$OUT/kds/"
  KSUM=$(sha256sum "$OUT/kds/Petpooja_KDS_V11_DOSA_LIVE.apk" | cut -d' ' -f1)
  printf '{"b":%s,"url":"Petpooja_KDS_V11_DOSA_LIVE.apk","sha256":"%s"}\n' "$B" "$KSUM" > "$OUT/kds/version.json"
fi
# Babai Scanner app (versionCode in its build.sh)
SAPK="$HERE/babai-scanner/dist/BabaiScanner.apk"
if [ -f "$SAPK" ]; then
  SB=$(grep -o 'versionCode: [0-9]*' "$HERE/babai-scanner/build.sh" | grep -o '[0-9]*$')
  cp "$SAPK" "$OUT/scanner/"
  SSUM=$(sha256sum "$OUT/scanner/BabaiScanner.apk" | cut -d' ' -f1)
  printf '{"b":%s,"url":"BabaiScanner.apk","sha256":"%s"}\n' "$SB" "$SSUM" > "$OUT/scanner/version.json"
fi
printf 'User-agent: *\nDisallow: /indent/\nDisallow: /hub/\nDisallow: /kds/\nDisallow: /console/\nDisallow: /scanner/\n' > "$OUT/robots.txt"
echo "site in $OUT (console $V, KDS build ${B:-none}, scanner ${SB:-none})"
