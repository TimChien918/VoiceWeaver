#!/usr/bin/env bash
set -euo pipefail

PORT="${GPT_SOVITS_PORT:-9880}"
PIDS="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN || true)"

if [[ -z "$PIDS" ]]; then
  echo "No GPT-SoVITS API server is listening on port $PORT."
  exit 0
fi

echo "Stopping GPT-SoVITS API server on port $PORT: $PIDS"
kill $PIDS
