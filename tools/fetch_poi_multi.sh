#!/usr/bin/env bash
# 多區域 POI 抓取 → 合併成單一 TSV。
#
# 用途：把全台灣 + 美國主要都會區的 OSM POI 都包進 assets/poi/taiwan_pois.tsv
# 之所以保留檔名 taiwan_pois.tsv：避免改名動到 OfflinePoiIndex.ASSET_PATH。
# 之後可以把 OfflinePoiIndex 的常數重新命名為 worldwide_pois.tsv，這裡先不動。
#
# 用法：
#   ./tools/fetch_poi_multi.sh          # 預設：全台灣 + 6 個美國都會
#   REGIONS=tw_only ./tools/fetch_poi_multi.sh   # 只台灣
#   REGIONS=us_only ./tools/fetch_poi_multi.sh   # 只美國
#
# 一次完整抓需 5-15 分鐘（看 Overpass 公開伺服器負載）。失敗會試下一面鏡子。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
ASSET="$ROOT/app/src/main/assets/poi/taiwan_pois.tsv"
MERGED="$TMP/merged.tsv"
: > "$MERGED"

ENDPOINTS=(
  "https://overpass-api.de/api/interpreter"
  "https://overpass.kumi.systems/api/interpreter"
  "https://overpass.osm.ch/api/interpreter"
)

# region 定義：name SOUTH NORTH WEST EAST
# 台灣（北中南東一氣呵成的 bbox，已驗證 ~133k POI）
TW=("taiwan 21.85 25.35 119.80 122.05")

# 美國主要都會（每個 bbox 取都會圈內最密集區，避免抓爆 Overpass）
# 大都會被切成 2-3 個小 bbox：sfbay / houston / boston / dc 原始一次抓太大會被三家鏡子拒
# （Overpass 一個 query > ~12M 元素時公開伺服器會 timeout 並回 695 bytes 的錯誤頁）
US=(
  "nyc_man    40.70 40.88 -74.02 -73.90"   # 曼哈頓 + 上城
  "nyc_brk    40.55 40.72 -74.05 -73.85"   # 布魯克林 + 皇后區南
  "la_west    34.00 34.15 -118.70 -118.30" # 西好萊塢 / 聖塔莫尼卡
  "la_dt      33.95 34.10 -118.35 -118.15" # 洛城市中心
  "la_east    33.65 34.05 -118.20 -117.65" # 長堤 / Anaheim
  "sf_city    37.70 37.83 -122.55 -122.35" # 舊金山市
  "sf_oakl    37.70 37.90 -122.35 -122.15" # 奧克蘭 / 柏克萊
  "sf_pen     37.20 37.60 -122.45 -122.05" # 灣區半島 / 聖荷西
  "chicago    41.65 42.05 -87.95 -87.50"   # 芝加哥（驗證可一次抓完）
  "houston_w  29.55 30.05 -95.80 -95.45"   # 休士頓西
  "houston_e  29.55 30.05 -95.50 -95.05"   # 休士頓東
  "boston     42.30 42.42 -71.20 -70.95"   # 波士頓（縮緯度範圍）
  "cam_som    42.35 42.50 -71.20 -71.05"   # 劍橋 / 薩默維爾
  "seattle    47.45 47.78 -122.45 -122.20" # 西雅圖
  "miami      25.65 26.05 -80.45 -80.10"   # 邁阿密
  "dc_north   38.90 39.05 -77.20 -76.95"   # 華府北 + 馬里蘭
  "dc_south   38.78 38.95 -77.15 -76.85"   # 華府南 + 維吉尼亞
  "atlanta    33.65 33.90 -84.50 -84.25"   # 亞特蘭大
)

case "${REGIONS:-both}" in
  tw_only)  ALL=("${TW[@]}") ;;
  us_only)  ALL=("${US[@]}") ;;
  both|*)   ALL=("${TW[@]}" "${US[@]}") ;;
esac

fetch_region() {
  local name="$1" south="$2" north="$3" west="$4" east="$5"
  local out="$TMP/${name}.tsv"

  echo "→ [$name] bbox ($south,$west)..($north,$east)"
  cat > "$TMP/query.txt" <<EOF
[out:json][timeout:180];
(
  nwr["amenity"]($south,$west,$north,$east);
  nwr["shop"]($south,$west,$north,$east);
  nwr["healthcare"]($south,$west,$north,$east);
);
out tags center;
EOF

  local size=0
  for ep in "${ENDPOINTS[@]}"; do
    echo "  trying $ep …"
    if curl -s -A "VoiceWeaver-Build/1.0" --connect-timeout 10 --max-time 300 \
        --data-urlencode "data@$TMP/query.txt" "$ep" > "$TMP/raw.json"; then
      size=$(wc -c < "$TMP/raw.json")
      if [[ "$size" -gt 100000 ]]; then
        echo "  ✅ $size bytes"
        break
      fi
    fi
    echo "  ⚠ failed or too small ($size); next mirror…"
  done

  if [[ "$size" -lt 100000 ]]; then
    echo "  ❌ [$name] all mirrors failed — skipping" >&2
    return 0
  fi
  python3 "$ROOT/tools/filter_poi.py" "$TMP/raw.json" "$out"
  cat "$out" >> "$MERGED"
}

for line in "${ALL[@]}"; do
  # shellcheck disable=SC2086
  fetch_region $line
done

echo "→ De-dup + write to $ASSET"
# 去重：同經緯度 + 名稱的條目只留一筆（不同 bbox 邊緣會重疊）
sort -u "$MERGED" > "$ASSET"
echo "✅ $(wc -l < "$ASSET") POIs total → $ASSET"
ls -lh "$ASSET"
