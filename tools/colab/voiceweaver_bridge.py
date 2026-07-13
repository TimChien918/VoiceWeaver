#!/usr/bin/env python3
"""VoiceWeaver 雲端橋接（Colab / 任何有 GPU 的機器）。

一個埠同時服務兩種客戶端，共用一條 Cloudflare/ngrok 通道：

  ── App（Android）── 直接講 GPT-SoVITS api_v2 協定，本橋接「反向代理」到 127.0.0.1:9880：
      GET  /health                    （app 只看 HTTP 200 判活）
      GET  /models                    角色清單（app 動態顯示、切換用）
      GET  /set_gpt_weights?...       切 GPT 權重
      GET  /set_sovits_weights?...    切 SoVITS 權重
      GET|POST /tts                   合成
      GET  /control /stop /set_refer_audio …（其餘 api_v2 路由全透傳）

  ── Web（github.io，瀏覽器）── 講語音中心 9879 橋接協定，需要 CORS：
      GET  /health   → {ok, voice, image, text, current, lang}
      GET  /voices   → {ok, voices:[{name, lang}]}
      POST /switch   {name, lang}        載對應權重
      POST /speak    {text, name?, lang?} → audio/wav

環境變數：
  GSV_ROOT      GPT-SoVITS 專案根目錄（gpt_rel/sovits_rel 相對於此）
  HISTORY_PATH  model_history.json 路徑（預設 $GSV_ROOT/user_models/model_history.json）
  API_BASE      api_v2 位址（預設 http://127.0.0.1:9880）
  BRIDGE_PORT   本橋接埠（預設 9879）
"""
import http.server
import json
import os
import re
import socketserver
import threading
import urllib.parse
import urllib.request

GSV_ROOT = os.environ.get("GSV_ROOT", ".").rstrip("/")
HISTORY_PATH = os.environ.get("HISTORY_PATH", f"{GSV_ROOT}/user_models/model_history.json")
API_BASE = os.environ.get("API_BASE", "http://127.0.0.1:9880").rstrip("/")
BRIDGE_PORT = int(os.environ.get("BRIDGE_PORT", "9879"))
IMAGE_BASE = os.environ.get("IMAGE_BASE", "http://127.0.0.1:9881").rstrip("/")   # SD-Turbo 生圖
TEXT_BASE = os.environ.get("TEXT_BASE", "http://127.0.0.1:9882").rstrip("/")     # Qwen 文字/評分


def _port_up(base):
    """探測某本機服務是否在線（給 /health 動態回報 voice/image/text 三項可用性）。"""
    import socket
    try:
        u = urllib.parse.urlparse(base)
        with socket.create_connection((u.hostname, u.port or 80), timeout=0.6):
            return True
    except Exception:
        return False

# api_v2 原生路由 → 一律透傳（app 走這些）。/models 不在此列：官方 api_v2 沒有這路由，
# 由本橋接直接讀 model_history.json 產生（app 與 web 共用同一份清單）。
PROXY_PREFIXES = ("/tts", "/set_gpt_weights", "/set_sovits_weights",
                  "/set_refer_audio", "/control", "/stop")

_LANG_TAG_TO_API = {"ZH": "zh", "EN": "en", "JA": "ja", "KO": "ko"}
_lock = threading.Lock()
_current = {"name": "", "lang": ""}   # 目前載入的角色（惰性切換用）


def _load_history():
    try:
        with open(HISTORY_PATH, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def _lang_of(rec):
    """從角色名尾綴推語言標籤（ZH/EN/JA/KO）；沒有就 ZH。"""
    name = str(rec.get("name", ""))
    m = re.search(r"[ _](ZH|CN|EN|JA|JP|KO|KR)\b", name, re.IGNORECASE)
    if not m:
        return "ZH"
    t = m.group(1).upper()
    return {"CN": "ZH", "JP": "JA", "KR": "KO"}.get(t, t)


def _find(name, lang=""):
    hist = _load_history()
    want = (lang or "").upper().replace("CN", "ZH").replace("JP", "JA").replace("KR", "KO")
    cands = [r for r in hist if r.get("name") == name]
    if want:
        exact = [r for r in cands if _lang_of(r) == want]
        if exact:
            return exact[0]
    return cands[0] if cands else None


def _api_get(path):
    with urllib.request.urlopen(f"{API_BASE}{path}", timeout=120) as r:
        return r.status, r.read()


def _switch(name, lang):
    """載入指定角色的 GPT + SoVITS 權重（惰性：與目前相同就跳過）。回 (ok, msg)。"""
    with _lock:
        if _current["name"] == name and (not lang or _current["lang"] == _lang_of({"name": f"x {lang}"})):
            return True, "already loaded"
        rec = _find(name, lang)
        if not rec:
            return False, f"找不到角色：{name}"
        gpt = rec.get("gpt_rel", "")
        sovits = rec.get("sovits_rel", "")
        if not gpt or not sovits:
            return False, f"角色 {name} 缺少權重路徑"
        try:
            for ep, wp in (("/set_sovits_weights", sovits), ("/set_gpt_weights", gpt)):
                q = urllib.parse.urlencode({"weights_path": wp})
                code, body = _api_get(f"{ep}?{q}")
                if code != 200:
                    return False, f"{ep} HTTP {code}: {body[:160].decode('utf-8','replace')}"
            _current["name"] = name
            _current["lang"] = _lang_of(rec)
            return True, "switched"
        except Exception as e:
            return False, str(e)


def _remap_ref(ref_path):
    """model_history 的 ref_path 是原機器絕對路徑；在本機不存在時，
    嘗試以檔名在 GSV_ROOT/ref_audio 底下遞迴找回。"""
    if ref_path and os.path.exists(ref_path):
        return ref_path
    base = os.path.basename(ref_path or "")
    if not base:
        return ref_path
    for root, _dirs, files in os.walk(os.path.join(GSV_ROOT, "ref_audio")):
        if base in files:
            return os.path.join(root, base)
    return ref_path


def _speak(text, name, lang):
    """合成一段 → 回 (wav_bytes, err)。指定角色會先惰性切換。"""
    if name:
        ok, msg = _switch(name, lang)
        if not ok:
            return None, msg
    elif not _current["name"]:
        hist = _load_history()
        if not hist:
            return None, "雲端上沒有任何語音模型"
        ok, msg = _switch(hist[0].get("name", ""), _lang_of(hist[0]))
        if not ok:
            return None, msg
    rec = _find(_current["name"]) or {}
    tag = (lang or _current["lang"] or "ZH").upper()
    api_lang = _LANG_TAG_TO_API.get(tag, "zh")
    payload = {
        "text": text, "text_lang": api_lang,
        "ref_audio_path": _remap_ref(rec.get("ref_path", "")),
        "prompt_text": rec.get("prompt", ""), "prompt_lang": api_lang,
        "media_type": "wav", "streaming_mode": False, "text_split_method": "cut5",
        "batch_size": 1, "top_k": 20, "top_p": 0.95, "temperature": 1.05,
        "repetition_penalty": 1.15, "fragment_interval": 0.18,
        "parallel_infer": True, "split_bucket": False, "sample_steps": 16,
        "speed_factor": 1.02,
    }
    try:
        req = urllib.request.Request(
            f"{API_BASE}/tts", data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=300) as r:
            data = r.read()
        if len(data) < 1024:
            return None, data.decode("utf-8", "replace")
        return data, None
    except Exception as e:
        return None, str(e)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code); self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _wav(self, data):
        self.send_response(200); self._cors()
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length", "0") or "0")
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return {}

    def _proxy(self):
        """把 api_v2 原生路由透傳到 127.0.0.1:9880（app 用）。"""
        try:
            code, body = _api_get(self.path)
            self.send_response(code); self._cors()
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self._json(502, {"ok": False, "error": str(e)})

    def do_OPTIONS(self):
        self.send_response(204); self._cors()
        self.send_header("Content-Length", "0"); self.end_headers()

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/" or path == "/health":
            cur = _find(_current["name"]) if _current["name"] else None
            return self._json(200, {
                "ok": True, "service": "voiceweaver-cloud-bridge",
                "current": _current["name"], "lang": _current["lang"],
                # 三項能力動態偵測：notebook 有跑哪個服務就回報哪個為 True（自由選擇）
                "voice": _port_up(API_BASE), "image": _port_up(IMAGE_BASE), "text": _port_up(TEXT_BASE),
                # app 靠 /health body 內含的權重檔名比對出「目前是哪個角色」→ 自動高亮
                "t2s_weights_path": (cur or {}).get("gpt_rel", ""),
                "vits_weights_path": (cur or {}).get("sovits_rel", ""),
            })
        if path == "/voices":
            voices = [{"name": r.get("name", ""), "lang": _lang_of(r)} for r in _load_history()]
            return self._json(200, {"ok": True, "voices": voices})
        if path == "/models":
            # app 期望的形狀（官方 api_v2 無此路由，本橋接從 model_history.json 產生）
            out = [{"name": r.get("name", ""), "version": r.get("version", ""),
                    "gpt_rel": r.get("gpt_rel", ""), "sovits_rel": r.get("sovits_rel", ""),
                    "ref_path": r.get("ref_path", ""), "prompt": r.get("prompt", "")}
                   for r in _load_history()]
            return self._json(200, {"ok": True, "models": out})
        if path.startswith(PROXY_PREFIXES):
            return self._proxy()
        self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        data = self._body()
        if path == "/switch":
            ok, msg = _switch(str(data.get("name", "")), str(data.get("lang", "")))
            return self._json(200 if ok else 500, {"ok": ok, "msg": msg})
        if path == "/speak":
            text = str(data.get("text", "")).strip()
            if not text:
                return self._json(400, {"ok": False, "error": "empty text"})
            wav, err = _speak(text, str(data.get("name", "")), str(data.get("lang", "")))
            if wav is None:
                return self._json(500, {"ok": False, "error": err})
            return self._wav(wav)
        if path == "/image":                  # web 生圖 → 代理到 SD-Turbo(9881)，回 PNG
            prompt = str(data.get("prompt", "")).strip()
            if not prompt:
                return self._json(400, {"ok": False, "error": "empty prompt"})
            try:
                body = json.dumps({"prompt": prompt, "steps": int(data.get("steps", 2) or 2)}).encode()
                req = urllib.request.Request(f"{IMAGE_BASE}/generate", data=body,
                                             headers={"Content-Type": "application/json"}, method="POST")
                with urllib.request.urlopen(req, timeout=180) as r:
                    png = r.read()
                self.send_response(200); self._cors()
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(png)))
                self.end_headers(); self.wfile.write(png)
                return
            except Exception as e:
                return self._json(502, {"ok": False, "error": str(e)})
        if path == "/text":                   # web 文字/評分 → 代理到 Qwen(9882)
            try:
                fwd = {"system": data.get("system", ""), "user": data.get("user", "")}
                if "temperature" in data:
                    fwd["temperature"] = data["temperature"]
                req = urllib.request.Request(f"{TEXT_BASE}/reconstruct", data=json.dumps(fwd).encode(),
                                             headers={"Content-Type": "application/json"}, method="POST")
                with urllib.request.urlopen(req, timeout=120) as r:
                    return self._json(200, json.loads(r.read()))
            except Exception as e:
                return self._json(502, {"ok": False, "error": str(e)})
        if path.startswith("/tts"):          # api_v2 POST /tts（app 也可能走 POST）
            return self._proxy_post()
        self._json(404, {"ok": False, "error": "not found"})

    def _proxy_post(self):
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(n)
            req = urllib.request.Request(
                f"{API_BASE}{self.path}", data=raw,
                headers={"Content-Type": self.headers.get("Content-Type", "application/json")},
                method="POST")
            with urllib.request.urlopen(req, timeout=300) as r:
                body = r.read()
                ctype = r.headers.get("Content-Type", "application/octet-stream")
            self.send_response(200); self._cors()
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self._json(502, {"ok": False, "error": str(e)})


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


def main():
    srv = ThreadingServer(("0.0.0.0", BRIDGE_PORT), Handler)
    print(f"[bridge] VoiceWeaver 雲端橋接啟動 :{BRIDGE_PORT} → api_v2 {API_BASE}", flush=True)
    print(f"[bridge] 模型清單：{HISTORY_PATH}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
