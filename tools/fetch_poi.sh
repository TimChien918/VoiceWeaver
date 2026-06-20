#!/usr/bin/env bash
# 抓 OpenStreetMap POI → 過濾 → 打進 app/src/main/assets/poi/taiwan_pois.tsv.gz
#
# 用法：
#   ./tools/fetch_poi.sh              # 預設大台北 (24.90,121.30,25.30,121.80)
#   ./tools/fetch_poi.sh 22.5 23.0 120.2 120.7   # 自訂 bbox: S N W E
#
# 第一次跑前確保安裝 python3 + curl。沒了。**完全免費、無 API key**，
# 唯一限制是 OSM 公開伺服器有時 504（重跑就好）。
set -euo pipefail

SOUTH="${1:-24.90}"
NORTH="${2:-25.30}"
WEST="${3:-121.30}"
EAST="${4:-121.80}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
ASSET="$ROOT/app/src/main/assets/poi/taiwan_pois.tsv"

echo "→ Fetching POIs in bbox ($SOUTH,$WEST)..($NORTH,$EAST) from Overpass…"
cat > "$TMP/query.txt" <<EOF
[out:json][timeout:180];
(
  nwr["amenity"]($SOUTH,$WEST,$NORTH,$EAST);
  nwr["shop"]($SOUTH,$WEST,$NORTH,$EAST);
  nwr["healthcare"]($SOUTH,$WEST,$NORTH,$EAST);
);
out tags center;
EOF

ENDPOINTS=(
  "https://overpass-api.de/api/interpreter"
  "https://overpass.kumi.systems/api/interpreter"
  "https://overpass.osm.ch/api/interpreter"
)
for ep in "${ENDPOINTS[@]}"; do
  echo "  trying $ep ..."
  curl -s -A "VoiceWeaver-Build/1.0" --connect-timeout 10 --max-time 300 \
    --data-urlencode "data@$TMP/query.txt" "$ep" > "$TMP/raw.json"
  SIZE=$(wc -c < "$TMP/raw.json")
  if [[ "$SIZE" -gt 100000 ]]; then
    echo "  ✅ got $SIZE bytes from $ep"
    break
  fi
  echo "  ⚠ only $SIZE bytes (probably error); trying next mirror…"
done

if [[ "$SIZE" -lt 100000 ]]; then
  echo "❌ All mirrors failed. Retry later." >&2
  exit 1
fi

echo "→ Filtering + gzip → $ASSET"
python3 "$ROOT/tools/filter_poi.py" "$TMP/raw.json" "$ASSET"
ls -la "$ASSET"
echo "✅ Done. Rebuild APK to bundle the new POI set."
