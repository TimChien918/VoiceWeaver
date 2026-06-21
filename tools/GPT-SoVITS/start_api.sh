#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
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

CONFIG_PATH="${GPT_SOVITS_CONFIG:-GPT_SoVITS/configs/voiceweaver_tts_infer.yaml}"
python api_v2.py -a 0.0.0.0 -p "$PORT" -c "$CONFIG_PATH"
