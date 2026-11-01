#!/usr/bin/env python3
"""VoiceWeaver 本機音訊理解伺服器（Mac 端，免金鑰）。

「AI 直接聽」原本只能走雲端（Gemini）。那條路有兩個問題：免費層額度很容易爆
（實測一次「直接聽」會平行打 3 次 API，連續用幾次就 HTTP 429），而且病人的語音
必須離開裝置。這支伺服器讓同一件事在自己的電腦上做完——不用金鑰、不出區網。

刻意跟 text_infer_server.py（9882）分開成獨立行程：
  * 兩者的 Python 環境不同（這支要 mlx-vlm，那支是 GPT-SoVITS 的 torch 環境）
  * 音訊模型吃記憶體（E4B 8-bit 約 8.5GB），要能單獨關掉而不影響文字重組與語音合成

端點：
    GET  /health     -> {ok, ready, model, loading, error}
    POST /understand body {"audio_base64": "...", "mime": "audio/aac",
                           "system": "...", "user": "..."} -> {"text": "..."}

環境變數：
    SML_AUDIO_MODEL（預設 lmstudio-community/gemma-4-E4B-it-MLX-8bit）
        實測 4-bit 會把「我牙齒痛」聽成「我餓肚子了」——對這個 App 是危險的錯法，
        所以預設用 8-bit。記憶體吃緊時可改 E2B，但要自己確認品質。
    SML_AUDIO_PORT（預設 9883）
    SML_AUDIO_MAX_TOKENS（預設 320）
    SML_AUDIO_KEEPWARM_SEC（預設 180；設 0 以外的值調整保溫間隔）
"""
from __future__ import annotations

import base64
import json
import os
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def _ensure_ffmpeg_on_path() -> str:
    """把 ffmpeg 找出來塞進 PATH。

    手機錄的是 AAC（Gemini 的 inline 音訊只收 wav/mp3/aac 等，不收 audio/mp4），
    而 mlx-vlm 底層要靠 ffmpeg 才解得開 AAC/M4A——沒有它就是一句
    「ffmpeg not found」的 500，App 只會默默退回雲端。

    使用者不必另外 brew install：GPT-SoVITS 的 conda 環境本來就帶了一份，
    直接借用。放在 PATH 尾端而不是開頭，避免蓋掉系統既有的工具。
    """
    from shutil import which

    if which("ffmpeg"):
        return which("ffmpeg")
    here = os.path.dirname(os.path.abspath(__file__))
    for cand in (
        os.path.join(os.path.dirname(here), "gpt-sovits-env", "bin"),
        os.path.join(os.path.dirname(here), "miniforge3", "bin"),
    ):
        if os.path.isfile(os.path.join(cand, "ffmpeg")):
            os.environ["PATH"] = os.environ.get("PATH", "") + os.pathsep + cand
            return os.path.join(cand, "ffmpeg")
    return ""


FFMPEG = _ensure_ffmpeg_on_path()

PORT = int(os.environ.get("SML_AUDIO_PORT", "9883"))
MODEL = os.environ.get(
    "SML_AUDIO_MODEL", "lmstudio-community/gemma-4-E4B-it-MLX-8bit"
)
# 3 個候選 + heard 塞不進 200 token，會被截斷成壞掉的 JSON
MAX_NEW_TOKENS = int(os.environ.get("SML_AUDIO_MAX_TOKENS", "320"))

_model = None
_processor = None
_load_err = ""
_loading = False

# MLX 的 GPU stream 綁在建立它的那條執行緒上——換一條執行緒去跑推論會直接炸
# 「RuntimeError: There is no Stream(gpu, 1) in current thread.」。
# 而 ThreadingHTTPServer 每個請求都開新執行緒，所以模型載入與推論一律丟進
# 這個「只有一條執行緒」的 executor 執行。附帶好處是推論自然被序列化：
# MLX 的統一記憶體跟 GPT-SoVITS 共用同一塊，兩個請求並行很容易把 16GB 機器逼進 swap。
_worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="mlx")


def _load_blocking() -> None:
    global _model, _processor, _load_err, _loading
    _loading = True
    try:
        from mlx_vlm import load

        t0 = time.time()
        print(f"[audio] 載入 {MODEL} …", flush=True)
        _model, _processor = load(MODEL)
        print(f"[audio] 載入完成，耗時 {time.time() - t0:.1f}s", flush=True)
    except Exception as e:  # noqa: BLE001 - 要把原因回給 App，不能吞掉
        _load_err = f"{type(e).__name__}: {e}"
        print(f"[audio] 載入失敗：{_load_err}", file=sys.stderr, flush=True)
    finally:
        _loading = False


def _understand_blocking(audio_path: str, system: str, user: str) -> str:
    from mlx_vlm import generate
    from mlx_vlm.prompt_utils import apply_chat_template

    formatted = apply_chat_template(
        _processor, _model.config, f"{system}\n\n{user}", num_audios=1
    )
    out = generate(
        _model,
        _processor,
        formatted,
        audio=[audio_path],
        max_tokens=MAX_NEW_TOKENS,
        temperature=0.3,
        verbose=False,
    )
    return out if isinstance(out, str) else getattr(out, "text", str(out))


def understand(audio_path: str, system: str, user: str) -> str:
    """在 MLX 專用執行緒上跑推論並等它做完。"""
    global _last_used
    _last_used = time.time()
    return _worker.submit(_understand_blocking, audio_path, system, user).result()


# ── 保溫 ────────────────────────────────────────────────────────────────
# 實測：閒置一段時間後的第一次請求要 56 秒，第二、三次只要 5 秒。
# 原因不是模型慢，是 8.5GB 的權重在 16GB 機器上被系統換出去了（同一台還住著
# GPT-SoVITS 與 SD-Turbo），下一次請求得整包換回來。
#
# 對這個 App 來說 56 秒是不能接受的——病人按下「說完了」之後盯著轉圈，
# 而且他多半是偶爾用一次，正好每次都踩在冷啟動上。
# 定期跑一個極短的推論把權重留在記憶體裡，代價很低。
_last_used = time.time()
KEEPWARM_INTERVAL = float(os.environ.get("SML_AUDIO_KEEPWARM_SEC", "180"))


def _keep_warm_loop() -> None:
    # 檢查間隔一定要「明顯短於」保溫門檻。
    # 第一版兩者都用 KEEPWARM_INTERVAL：醒來時若距上次使用未滿門檻就跳過，
    # 於是實際第一次保溫最晚被拖到兩倍時間才發生，等於形同虛設。
    check_every = max(15.0, KEEPWARM_INTERVAL / 6)
    global _last_used
    while True:
        time.sleep(check_every)
        if _model is None or time.time() - _last_used < KEEPWARM_INTERVAL:
            continue        # 剛用過就不必多此一舉
        try:
            _worker.submit(_touch_blocking).result(timeout=120)
            # 保溫本身也算「碰過」，否則會每 check_every 秒觸發一次
            _last_used = time.time()
        except Exception as e:  # noqa: BLE001
            print(f"[audio] 保溫失敗（不影響服務）：{type(e).__name__}: {e}", flush=True)
            _last_used = time.time()   # 失敗就別狂試，等下一輪


def _touch_blocking() -> None:
    """跑一個 1 token 的純文字生成，只為了讓權重被碰到、不被換出去。"""
    from mlx_vlm import generate
    from mlx_vlm.prompt_utils import apply_chat_template

    t0 = time.time()
    formatted = apply_chat_template(_processor, _model.config, "hi", num_audios=0)
    generate(_model, _processor, formatted, max_tokens=1, verbose=False)
    print(f"[audio] 保溫 {time.time() - t0:.1f}s", flush=True)


class Handler(BaseHTTPRequestHandler):
    def _json(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path.split("?")[0] != "/health":
            self._json(404, {"ok": False, "error": "not found"})
            return
        self._json(
            200,
            {
                "ok": True,
                # ffmpeg 缺席時「模型載入成功」但每一次解碼都會失敗，
                # 對 App 來說跟沒就緒沒兩樣——寧可在這裡就說不行，不要騙它送過來。
                "ready": _model is not None and bool(FFMPEG),
                "loading": _loading,
                "model": MODEL,
                "ffmpeg": FFMPEG or "",
                "error": _load_err or ("" if FFMPEG else "找不到 ffmpeg，無法解碼手機錄的 AAC"),
            },
        )

    def do_POST(self) -> None:
        if self.path.split("?")[0] != "/understand":
            self._json(404, {"ok": False, "error": "not found"})
            return
        if _model is None:
            # 還在載入 / 載入失敗都要講清楚，App 才知道該等還是該退回雲端
            self._json(
                503,
                {"ok": False, "loading": _loading, "error": _load_err or "模型尚未載入完成"},
            )
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(n) or b"{}")
            b64 = payload.get("audio_base64") or ""
            if not b64:
                self._json(400, {"ok": False, "error": "缺少 audio_base64"})
                return
            # 副檔名要對得上真實容器：App 錄的是裸 AAC（audio/aac），
            # 給錯副檔名解碼器會直接拒收。
            mime = payload.get("mime", "audio/aac")
            ext = {"audio/aac": ".aac", "audio/wav": ".wav", "audio/mp4": ".m4a",
                   "audio/mpeg": ".mp3", "audio/ogg": ".ogg"}.get(mime, ".aac")
            raw = base64.b64decode(b64)
            with tempfile.NamedTemporaryFile(suffix=ext, delete=False) as f:
                f.write(raw)
                path = f.name
            try:
                t0 = time.time()
                text = understand(path, payload.get("system", ""), payload.get("user", ""))
                print(f"[audio] 完成 {len(raw)//1024}KB，耗時 {time.time()-t0:.1f}s", flush=True)
                self._json(200, {"ok": True, "text": text})
            finally:
                # 語音是敏感資料，用完立刻刪，跟 App 端同一個原則
                try:
                    os.unlink(path)
                except OSError:
                    pass
        except Exception as e:  # noqa: BLE001
            self._json(500, {"ok": False, "error": f"{type(e).__name__}: {e}"})

    def log_message(self, fmt: str, *args) -> None:
        pass    # 預設每個請求印一行，太吵；真正要看的東西上面已經自己印了


def main() -> None:
    _worker.submit(_load_blocking)   # 同一條 MLX 執行緒載入，見 _worker 註解
    threading.Thread(target=_keep_warm_loop, daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[audio] 音訊理解伺服器啟動於 :{PORT}（模型 {MODEL}）", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
