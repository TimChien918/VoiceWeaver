#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/sml_migrate.sh" ] && . "$ROOT/sml_migrate.sh"   # ~/VoiceWeaver-電腦端 → ~/Speak-My-Life-電腦端
cd "$ROOT/../.."

# 控制中心只用標準庫；優先用 GPT-SoVITS 環境的 python，沒有再退回系統 python3。
PY="python3"
if [ -x "$ROOT/../gpt-sovits-env/bin/python" ]; then
  PY="$ROOT/../gpt-sovits-env/bin/python"
fi

exec "$PY" "$ROOT/model_setup_server.py"
