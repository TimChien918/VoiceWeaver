#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/sml_migrate.sh" ] && . "$ROOT/sml_migrate.sh"   # ~/VoiceWeaver-電腦端 → ~/Speak-My-Life-電腦端

export MPLCONFIGDIR="$ROOT/.cache/matplotlib"
mkdir -p "$MPLCONFIGDIR"

source "$ROOT/../miniforge3/etc/profile.d/conda.sh"
conda activate "$ROOT/../gpt-sovits-env"

cd "$ROOT"

python - <<'PY'
from pathlib import Path
import fastapi
import gradio
import librosa
import pyopenjtalk
import torch

root = Path.cwd()
user_models = root / "user_models" / "GPT-SoVits-V2-models"
pretrained = root / "GPT_SoVITS" / "pretrained_models"
g2pw = root / "GPT_SoVITS" / "text" / "G2PWModel"

print("GPT-SoVITS install check")
print(f"- torch: {torch.__version__}")
print(f"- gradio: {gradio.__version__}")
print(f"- fastapi: {fastapi.__version__}")
print(f"- pretrained models: {'ok' if pretrained.exists() else 'missing'}")
print(f"- G2PWModel: {'ok' if g2pw.exists() else 'missing'}")
print(f"- user model folder: {'ok' if user_models.exists() else 'missing'}")

pointer_files = []
for path in user_models.rglob("*"):
    if path.suffix.lower() in {".ckpt", ".pth"} and path.stat().st_size < 1024:
        pointer_files.append(path.relative_to(user_models))

if pointer_files:
    print("- user model weights: Git LFS pointer files detected")
    for path in pointer_files:
        print(f"  - {path}")
else:
    print("- user model weights: no tiny pointer files detected")

print("ok")
PY
