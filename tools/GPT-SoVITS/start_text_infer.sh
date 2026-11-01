#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/sml_migrate.sh" ] && . "$ROOT/sml_migrate.sh"   # ~/VoiceWeaver-電腦端 → ~/Speak-My-Life-電腦端
PORT="${SML_TEXT_PORT:-9882}"

export MPLCONFIGDIR="$ROOT/.cache/matplotlib"
export HF_HOME="${HF_HOME:-$ROOT/.cache/huggingface}"
mkdir -p "$MPLCONFIGDIR" "$HF_HOME"

source "$ROOT/../miniforge3/etc/profile.d/conda.sh"
conda activate "$ROOT/../gpt-sovits-env"

cd "$ROOT"
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "本機文字推論伺服器埠 $PORT 已被占用："
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN
  exit 1
fi

exec python text_infer_server.py
