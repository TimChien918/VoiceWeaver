#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/sml_migrate.sh" ] && . "$ROOT/sml_migrate.sh"   # ~/VoiceWeaver-電腦端 → ~/Speak-My-Life-電腦端
PORT="${GPT_SOVITS_PORT:-9880}"

export MPLCONFIGDIR="$ROOT/.cache/matplotlib"
mkdir -p "$MPLCONFIGDIR"

source "$ROOT/../miniforge3/etc/profile.d/conda.sh"
conda activate "$ROOT/../gpt-sovits-env"

cd "$ROOT"
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "GPT-SoVITS API port $PORT is already in use:"
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN
  echo
  echo "Stop the old server first:"
  echo "  ./tools/GPT-SoVITS/stop_api.sh"
  exit 1
fi

# 更名搬遷：舊設定檔 voiceweaver_tts_infer.yaml 只有舊的就改名（失敗沿用舊的）
NEW_CFG="GPT_SoVITS/configs/speakmylife_tts_infer.yaml"
OLD_CFG="GPT_SoVITS/configs/voiceweaver_tts_infer.yaml"
if [ ! -e "$NEW_CFG" ] && [ -f "$OLD_CFG" ]; then
  mv "$OLD_CFG" "$NEW_CFG" 2>/dev/null || NEW_CFG="$OLD_CFG"
fi
CONFIG_PATH="${GPT_SOVITS_CONFIG:-$NEW_CFG}"
python api_v2.py -a 0.0.0.0 -p "$PORT" -c "$CONFIG_PATH"
