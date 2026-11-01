#!/usr/bin/env bash
# 啟動本機音訊理解伺服器（「AI 直接聽」的免金鑰版本）。
#
# 跟 start_text_infer.sh 的差別：這支用自己的 venv（audio-understand-env），
# 不進 gpt-sovits-env。mlx-vlm 會拉一整套自己的 numpy/transformers，
# 混進 GPT-SoVITS 的環境有很高機率弄壞語音合成。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/sml_migrate.sh" ] && . "$ROOT/sml_migrate.sh"   # ~/VoiceWeaver-電腦端 → ~/Speak-My-Life-電腦端
PORT="${SML_AUDIO_PORT:-9883}"
VENV="${SML_AUDIO_VENV:-$ROOT/../audio-understand-env}"

export HF_HOME="${HF_HOME:-$ROOT/.cache/huggingface}"
mkdir -p "$HF_HOME"

if [ ! -x "$VENV/bin/python" ]; then
  echo "找不到音訊理解用的 Python 環境：$VENV"
  echo "建立方式："
  echo "  \"$ROOT/../gpt-sovits-env/bin/python3\" -m venv \"$VENV\""
  echo "  \"$VENV/bin/python\" -m pip install mlx-vlm"
  exit 1
fi

cd "$ROOT"
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "本機音訊理解伺服器埠 $PORT 已被占用："
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN
  exit 1
fi

exec "$VENV/bin/python" audio_infer_server.py
