#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PORT="${VOICEWEAVER_IMAGE_PORT:-9881}"

export MPLCONFIGDIR="$ROOT/.cache/matplotlib"
export HF_HOME="${HF_HOME:-$ROOT/.cache/huggingface}"
mkdir -p "$MPLCONFIGDIR" "$HF_HOME"

source "$ROOT/../miniforge3/etc/profile.d/conda.sh"
conda activate "$ROOT/../gpt-sovits-env"

cd "$ROOT"
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "本機生圖伺服器埠 $PORT 已被占用："
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN
  exit 1
fi

exec python image_gen_server.py
