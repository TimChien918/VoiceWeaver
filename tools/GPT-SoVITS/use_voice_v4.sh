#!/usr/bin/env bash
# 熱切換 SoVITS server 的 v4 角色權重（胡桃 / 钟离）。
#
# 用法：./use_voice_v4.sh [胡桃|钟离]   （預設 胡桃）
#
# 切換後 App 不用改設定：App 在「測試連線」時會打 /health 偵測目前載入的角色，
# 自動改用該角色的參考音檔與逐字稿（見 LocalSovitsHelper.ZH_VOICES）。
# 切換完記得在 App 裡按一次「測試連線」讓它重新偵測。
set -euo pipefail

CHAR="${1:-胡桃}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
API="http://127.0.0.1:9880"

case "$CHAR" in
  胡桃)
    GPT="GPT_weights_v4/胡桃_ZH-e10.ckpt"
    SOV="SoVITS_weights_v4/胡桃_ZH_e10_s860_l32.pth"
    ;;
  钟离|鍾離)
    GPT="GPT_weights_v4/钟离_ZH-e10.ckpt"
    SOV="SoVITS_weights_v4/钟离_ZH_e10_s990_l32.pth"
    ;;
  *)
    echo "未知角色：$CHAR（支援：胡桃、钟离）" >&2
    exit 1
    ;;
esac

for f in "$GPT" "$SOV"; do
  if [[ ! -f "$ROOT/$f" ]]; then
    echo "❌ 權重檔不存在：$ROOT/$f" >&2
    exit 2
  fi
done

echo "→ /set_sovits_weights → $SOV"
resp="$(curl -s -G --data-urlencode "weights_path=$SOV" "$API/set_sovits_weights")"
echo "$resp"
[[ "$resp" == *'"success"'* ]] || { echo "❌ SoVITS 權重切換失敗" >&2; exit 3; }

echo "→ /set_gpt_weights → $GPT"
resp="$(curl -s -G --data-urlencode "weights_path=$GPT" "$API/set_gpt_weights")"
echo "$resp"
[[ "$resp" == *'"success"'* ]] || { echo "❌ GPT 權重切換失敗" >&2; exit 4; }

echo "✅ 已切換到 $CHAR (v4)。"
curl -s "$API/health"; echo
echo "提醒：到 App 設定按一次「測試連線」，App 會自動跟上這個角色的 ref/prompt。"
