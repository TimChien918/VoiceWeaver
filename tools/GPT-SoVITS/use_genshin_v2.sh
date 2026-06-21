#!/usr/bin/env bash
# ⚠ 注意：server 的 voiceweaver_tts_infer.yaml custom 區段已改為 v4 角色模型（胡桃/钟离），
#   日常切換請改用 ./use_voice_v4.sh。這個腳本會把 server 切回 v2 權重系，
#   App 端的中文 ref/prompt 是針對 v4 角色配的，切到 v2 後音色會不對。
#
# 熱切換 SoVITS server 載入的權重 → 從 V1 pretrained 換成 Genshin V2 多角色模型。
#
# 多角色模型是否像指定角色，取決於模型相容性與你提供的合法參考音檔。
# 若 server log 出現大量 missing_keys / unexpected_keys，代表權重架構不相容，聲音會異常。
#
# 用法：./use_genshin_v2.sh [zh|en|ja|base]
#   zh → Wuthering Waves CN 1.3
#   en → Genshin Impact EN 5.1
#   ja → Genshin Impact JA 5.1
#   base → 官方 v2 底模（最穩定，但不是角色音色）
#
# 預設 en。
set -euo pipefail

LANG="${1:-en}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
DST_SOV="$ROOT/SoVITS_weights_v2"
DST_GPT="$ROOT/GPT_weights_v2"
mkdir -p "$DST_SOV" "$DST_GPT"

hot_swap() {
  local sov_api_path="$1"
  local gpt_api_path="$2"
  local response

  echo "→ Hot-swap SoVITS weight via /set_sovits_weights …"
  response="$(curl -s -G --data-urlencode "weights_path=$sov_api_path" \
    "http://127.0.0.1:9880/set_sovits_weights")"
  echo "$response"
  if [[ "$response" != *'"success"'* ]]; then
    echo "❌ SoVITS weight switch failed." >&2
    exit 3
  fi

  echo "→ Hot-swap GPT weight via /set_gpt_weights …"
  response="$(curl -s -G --data-urlencode "weights_path=$gpt_api_path" \
    "http://127.0.0.1:9880/set_gpt_weights")"
  echo "$response"
  if [[ "$response" != *'"success"'* ]]; then
    echo "❌ GPT weight switch failed." >&2
    exit 4
  fi
}

if [[ "$LANG" == "base" ]]; then
  hot_swap \
    "GPT_SoVITS/pretrained_models/gsv-v2final-pretrained/s2G2333k.pth" \
    "GPT_SoVITS/pretrained_models/gsv-v2final-pretrained/s1bert25hz-5kh-longer-epoch=12-step=369668.ckpt"
  echo "✅ Requested weight switch for official v2 base."
  exit 0
fi

case "$LANG" in
  zh) SOV_REL="Wuthering_Waves/CN/SV_WutheringWaves_CN_1.3.pth"
      GPT_REL="Wuthering_Waves/CN/GPT_WutheringWaves_CN_1.3.ckpt" ;;
  en) SOV_REL="Genshin_Impact/EN/SV_GenshinImpact_EN_5.1.pth"
      GPT_REL="Genshin_Impact/EN/GPT_GenshinImpact_EN_5.1.ckpt" ;;
  ja) SOV_REL="Genshin_Impact/JA/SV_GenshinImpact_JA_5.1.pth"
      GPT_REL="Genshin_Impact/JA/GPT_GenshinImpact_JA_5.1.ckpt" ;;
  *)  echo "unsupported lang: $LANG (expected zh/en/ja/base)" >&2; exit 1 ;;
esac

find_real_weight() {
  local rel="$1"
  local root
  for root in "$ROOT/user_models/_msc_cache" "$ROOT/user_models/GPT-SoVits-V2-models"; do
    local found
    found="$(find "$root" -path "*/$rel" -type f -size +1M -print 2>/dev/null | head -n 1 || true)"
    if [[ -n "$found" ]]; then
      echo "$found"
      return 0
    fi
  done
  return 1
}

SOV_SRC="$(find_real_weight "$SOV_REL" || true)"
GPT_SRC="$(find_real_weight "$GPT_REL" || true)"
SOV_DST="$DST_SOV/$(basename "$SOV_REL")"
GPT_DST="$DST_GPT/$(basename "$GPT_REL")"

# 檢查 source 不是 LFS pointer stub
check_real() {
  local f="$1"
  if [[ ! -f "$f" ]]; then echo "missing: $f" >&2; return 1; fi
  local sz=$(stat -f %z "$f")
  if [[ "$sz" -lt 1024 ]]; then
    echo "still LFS pointer ($sz bytes): $f" >&2
    echo "  → 等 ModelScope 下載完，或自行用 git-lfs / aria2 補完整檔" >&2
    return 1
  fi
  return 0
}
check_real "$SOV_SRC" || exit 2
check_real "$GPT_SRC" || exit 2

echo "→ Copy SoVITS weight: $SOV_DST"
cp -nv "$SOV_SRC" "$SOV_DST" || true
echo "→ Copy GPT weight: $GPT_DST"
cp -nv "$GPT_SRC" "$GPT_DST" || true

# 用 /set_*_weights API 熱切換（不必重啟 server）
# 注意 api_v2.py 的設定路徑 是相對於 GPT-SoVITS 工作目錄
SOV_API_PATH="SoVITS_weights_v2/$(basename "$SOV_REL")"
GPT_API_PATH="GPT_weights_v2/$(basename "$GPT_REL")"

hot_swap "$SOV_API_PATH" "$GPT_API_PATH"
echo "✅ Requested weight switch for $LANG."
echo "請檢查 server log：若出現大量 missing_keys / unexpected_keys，這組權重不相容，請切回官方 base 或換相容模型。"
