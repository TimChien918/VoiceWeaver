#!/usr/bin/env python3
"""拾語者 Speak My Life 本機文字推論伺服器（Mac 端，免金鑰）。

當手機與電腦連線夠快（有線 USB 或高速 Wi-Fi）時，拾語者會把「句子重組」這項
文字運算也交給電腦（tier-FULL），盡量減少手機負擔。這支伺服器就是電腦端的文字大腦：
用 transformers 在 Apple MPS 上跑一個小型 instruct 模型（預設 Qwen2.5-1.5B-Instruct，
多語：中／英／日／韓），首次執行會自動下載（免金鑰）。

端點：
    GET  /health        -> {ok, ready, model, device, error}
    POST /reconstruct   body {"system": "...", "user": "..."} -> {"text": "..."}

環境變數：
    TEXT_INFER_MODEL（預設 Qwen/Qwen2.5-1.5B-Instruct；想更快可用 Qwen/Qwen2.5-0.5B-Instruct）
    SML_TEXT_PORT（預設 9882）
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("SML_TEXT_PORT", "9882"))
MODEL = os.environ.get("TEXT_INFER_MODEL", "Qwen/Qwen2.5-1.5B-Instruct")
MAX_NEW_TOKENS = int(os.environ.get("TEXT_INFER_MAX_TOKENS", "96"))

_model = None
_tok = None
_lock = threading.Lock()       # 序列化推論，避免 MPS 記憶體爆掉
_load_err = ""
_device = "cpu"


def load_model() -> None:
    """背景載入模型（首次會自動下載），讓 server 能立刻回應 /health。"""
    global _model, _tok, _load_err, _device
    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer

        # 裝置優先序：CUDA（Windows/Linux NVIDIA）→ MPS（Apple）→ CPU。
        if torch.cuda.is_available():
            _device = "cuda"
        elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
            _device = "mps"
        else:
            _device = "cpu"
        dtype = torch.float16 if _device in ("cuda", "mps") else torch.float32
        _tok = AutoTokenizer.from_pretrained(MODEL)
        model = AutoModelForCausalLM.from_pretrained(MODEL, torch_dtype=dtype)
        model = model.to(_device)
        model.eval()
        _model = model
        print(f"[text] model ready: {MODEL} on {_device}", flush=True)
    except Exception as exc:  # noqa: BLE001
        _load_err = str(exc)
        print(f"[text] load failed: {exc}", flush=True)


def reconstruct(system: str, user: str, temperature: float = 0.5) -> str:
    import torch

    # 評分類任務會傳低溫（0.1~0.2）求穩定；夾在安全範圍
    temperature = max(0.05, min(1.5, float(temperature or 0.5)))
    messages = []
    if system.strip():
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": user})
    with _lock:
        prompt = _tok.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True
        )
        inputs = _tok(prompt, return_tensors="pt").to(_device)
        with torch.no_grad():
            out = _model.generate(
                **inputs,
                max_new_tokens=MAX_NEW_TOKENS,
                do_sample=True,
                temperature=temperature,
                top_p=0.9,
                pad_token_id=_tok.eos_token_id,
            )
        gen = out[0][inputs["input_ids"].shape[1]:]
        text = _tok.decode(gen, skip_special_tokens=True).strip()
    text = (text or "").strip()
    # JSON 輸出（復健評分等結構化任務）必須完整保留——多行 JSON 砍到第一行會整包壞掉，
    # 客戶端解析失敗就悄悄掉到別的評分來源，分數忽高忽低。
    # 模型常包 ```json 圍欄或加前言，所以不能只看開頭：取第一個 { 到最後一個 }，能 json 解析才算。
    if "{" in text:
        si, ei = text.find("{"), text.rfind("}")
        if ei > si:
            cand = text[si:ei + 1]
            try:
                json.loads(cand)
                return cand
            except Exception:
                pass
    # 其餘（AAC 組句等「一句話」需求）維持只取第一行、去引號
    text = text.splitlines()[0].strip() if text else ""
    return text.strip().strip("「」\"'")


class Handler(BaseHTTPRequestHandler):
    def _json(self, code: int, obj: dict) -> None:
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self) -> None:
        if self.path.startswith("/health"):
            self._json(200, {
                "ok": True,
                "ready": _model is not None,
                "model": MODEL,
                "device": _device,
                "error": _load_err,
            })
        else:
            self._json(404, {"ok": False})

    def do_POST(self) -> None:
        if not self.path.startswith("/reconstruct"):
            self._json(404, {"ok": False})
            return
        if _model is None:
            self._json(503, {"ok": False, "error": _load_err or "模型尚未載入完成，請稍候再試"})
            return
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
            body = json.loads(self.rfile.read(n) or b"{}")
            system = (body.get("system") or "").strip()
            user = (body.get("user") or "").strip()
            if not user:
                self._json(400, {"ok": False, "error": "缺少 user"})
                return
            temperature = body.get("temperature", 0.5)
            text = reconstruct(system, user, temperature)
            self._json(200, {"ok": True, "text": text})
        except Exception as exc:  # noqa: BLE001
            self._json(500, {"ok": False, "error": str(exc)})

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), fmt % args))


def main() -> None:
    threading.Thread(target=load_model, daemon=True).start()
    # 預設 0.0.0.0（手機 Wi-Fi 需要）；只用 USB(adb reverse) 的人可設 SML_BIND=127.0.0.1 鎖本機。
    bind = os.environ.get("SML_BIND", "0.0.0.0")
    server = ThreadingHTTPServer((bind, PORT), Handler)
    print(f"Speak My Life text server: http://{bind}:{PORT} (model {MODEL})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
