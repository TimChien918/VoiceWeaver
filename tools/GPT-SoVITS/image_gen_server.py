#!/usr/bin/env python3
"""VoiceWeaver 本機生圖伺服器（Mac 端，免金鑰）。

跟 GPT-SoVITS 一樣在「電腦本機」運算：手機透過 USB(adb reverse) 或 Wi-Fi 連到 :9881，
不需要任何雲端 API 金鑰。用 diffusers + Apple MPS 跑 SD-Turbo（快、512px、適合 AAC 圖卡）。

端點：
    GET  /health             -> {ok, ready, model, device, error}
    POST /generate           body {"prompt": "...", "steps": 2}  -> image/png

模型可用環境變數覆蓋：
    IMAGE_GEN_MODEL（預設 stabilityai/sd-turbo；想更精緻可用 stabilityai/sdxl-turbo）
    IMAGE_GEN_STEPS（預設 2）
    VOICEWEAVER_IMAGE_PORT（預設 9881）
"""
from __future__ import annotations

import io
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def self_info() -> dict:
    """回報本機主機名與 Tailscale IP（100.64.0.0/10），給手機自動加入電腦清單用。"""
    host = socket.gethostname().split(".")[0] or "電腦"
    tsip = ""
    try:
        out = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=4).stdout
        for m in re.finditer(r"inet (100\.\d+\.\d+\.\d+)", out):
            ip = m.group(1)
            if 64 <= int(ip.split(".")[1]) <= 127:   # 100.64.0.0/10
                tsip = ip
                break
    except Exception:
        pass
    return {"ok": True, "hostname": host, "tailscale_ip": tsip}

PORT = int(os.environ.get("VOICEWEAVER_IMAGE_PORT", "9881"))
MODEL = os.environ.get("IMAGE_GEN_MODEL", "stabilityai/sd-turbo")
DEFAULT_STEPS = int(os.environ.get("IMAGE_GEN_STEPS", "2"))

_pipe = None
_lock = threading.Lock()       # 單張序列化，避免 MPS 記憶體爆掉
_load_err = ""
_device = "cpu"
_busy = 0                      # 進行中的生圖數（給控制中心顯示「合成進度」）
_done = 0                     # 累計完成張數
_last_prompt = ""


def load_pipe() -> None:
    """背景載入模型（首次會自動下載），讓 server 能立刻回應 /health。"""
    global _pipe, _load_err, _device
    try:
        import torch
        from diffusers import AutoPipelineForText2Image

        # 裝置優先序：CUDA（Windows/Linux NVIDIA）→ MPS（Apple）→ CPU。
        if torch.cuda.is_available():
            _device = "cuda"
        elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
            _device = "mps"
        else:
            _device = "cpu"
        dtype = torch.float16 if _device in ("cuda", "mps") else torch.float32
        # 優先抓 fp16 變體（體積減半、GPU 適用）；沒有 fp16 變體的模型再退回預設權重
        try:
            pipe = AutoPipelineForText2Image.from_pretrained(
                MODEL, torch_dtype=dtype, variant="fp16", use_safetensors=True
            )
        except Exception:
            pipe = AutoPipelineForText2Image.from_pretrained(MODEL, torch_dtype=dtype)
        pipe = pipe.to(_device)
        pipe.set_progress_bar_config(disable=True)
        # AAC 用途：關掉 safety checker，避免誤判把圖塗黑
        if hasattr(pipe, "safety_checker"):
            pipe.safety_checker = None
        _pipe = pipe
        print(f"[image] model ready: {MODEL} on {_device}", flush=True)
    except Exception as exc:  # noqa: BLE001
        _load_err = str(exc)
        print(f"[image] load failed: {exc}", flush=True)


def generate_png(prompt: str, steps: int) -> bytes:
    global _busy, _done, _last_prompt
    _busy += 1
    _last_prompt = prompt
    print(f"[image] generating ({_busy} in queue): {prompt[:60]}", flush=True)
    try:
        with _lock:
            image = _pipe(
                prompt=prompt,
                num_inference_steps=max(1, min(steps, 8)),
                guidance_scale=0.0,   # turbo 系列固定用 0
            ).images[0]
        buf = io.BytesIO()
        image.save(buf, format="PNG")
        _done += 1
        print(f"[image] done (total {_done}): {prompt[:40]}", flush=True)
        return buf.getvalue()
    finally:
        _busy -= 1


class Handler(BaseHTTPRequestHandler):
    def _json(self, code: int, obj: dict) -> None:
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self) -> None:
        if self.path.startswith("/whoami"):
            self._json(200, self_info())
            return
        if self.path.startswith("/bench"):
            # 吞吐量量測：回固定大小位元組讓手機端計時估算連線速度（手機 OffloadManager 用）。
            n = 200_000
            if "bytes=" in self.path:
                try:
                    n = max(1, min(int(self.path.split("bytes=")[1].split("&")[0]), 5_000_000))
                except Exception:  # noqa: BLE001
                    n = 200_000
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(n))
            self.end_headers()
            self.wfile.write(b"\0" * n)
            return
        if self.path.startswith("/health"):
            self._json(200, {
                "ok": True,
                "ready": _pipe is not None,
                "model": MODEL,
                "device": _device,
                "error": _load_err,
                "busy": _busy,
                "generating": _busy > 0,
                "done": _done,
                "last_prompt": _last_prompt,
            })
        else:
            self._json(404, {"ok": False})

    def do_POST(self) -> None:
        if not self.path.startswith("/generate"):
            self._json(404, {"ok": False})
            return
        if _pipe is None:
            self._json(503, {"ok": False, "error": _load_err or "模型尚未載入完成，請稍候再試"})
            return
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
            body = json.loads(self.rfile.read(n) or b"{}")
            prompt = (body.get("prompt") or "").strip()
            steps = int(body.get("steps") or DEFAULT_STEPS)
            if not prompt:
                self._json(400, {"ok": False, "error": "缺少 prompt"})
                return
            png = generate_png(prompt, steps)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(png)))
            self.end_headers()
            self.wfile.write(png)
        except Exception as exc:  # noqa: BLE001
            self._json(500, {"ok": False, "error": str(exc)})

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), fmt % args))


def main() -> None:
    threading.Thread(target=load_pipe, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"VoiceWeaver image server: http://0.0.0.0:{PORT} (model {MODEL})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
