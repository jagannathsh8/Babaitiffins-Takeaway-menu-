#!/usr/bin/env bash
# Builds the folder to deploy on Netlify (babaidosa): customer page, indent planner, hub and KDS app updates.
#   ./make-netlify-site.sh <firebase db host> <out dir>
set -euo pipefail
DB="$1"; OUT=$(realpath -m "$2"); HERE=$(cd "$(dirname "$0")" && pwd)
rm -rf "$OUT"; mkdir -p "$OUT/hub" "$OUT/indent" "$OUT/kds"
sed "s/var BUILT_IN='__BABAI_DB__';/var BUILT_IN='$DB';/" "$HERE/dosa/index.html" > "$OUT/index.html"
cp "$HERE/indent/index.html" "$HERE/indent/plan.js" "$HERE/indent/xlsx.full.min.js" "$HERE/indent/xlsx.LICENSE.txt" "$OUT/indent/"
cp "$HERE/babai-hub/dist/babai-hub.exe" "$OUT/hub/"
V=$(grep -o 'version     = "[0-9.]*"' "$HERE/babai-hub/main.go" | grep -o '[0-9.]*')
SUM=$(sha256sum "$OUT/hub/babai-hub.exe" | cut -d' ' -f1)
printf '{"v":"%s","url":"babai-hub.exe","sha256":"%s"}\n' "$V" "$SUM" > "$OUT/hub/version.json"
# KDS app (AppUpdate.BUILD): the screens update from here, or from the hub's copy of it
APK="$HERE/kds-dosa-live/dist/Petpooja_KDS_V11_DOSA_LIVE.apk"
if [ -f "$APK" ]; then
  B=$(grep -o 'static final int BUILD = [0-9]*' "$HERE/kds-dosa-live/src/com/pp/kds/dosa/AppUpdate.java" | grep -o '[0-9]*$')
  cp "$APK" "$OUT/kds/"
  KSUM=$(sha256sum "$OUT/kds/Petpooja_KDS_V11_DOSA_LIVE.apk" | cut -d' ' -f1)
  printf '{"b":%s,"url":"Petpooja_KDS_V11_DOSA_LIVE.apk","sha256":"%s"}\n' "$B" "$KSUM" > "$OUT/kds/version.json"
fi
printf 'User-agent: *\nDisallow: /indent/\nDisallow: /hub/\nDisallow: /kds/\n' > "$OUT/robots.txt"
echo "site in $OUT (hub $V, KDS build ${B:-none})"
