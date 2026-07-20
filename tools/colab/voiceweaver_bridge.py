#!/usr/bin/env python3
"""VoiceWeaver 雲端橋接（Colab / 任何有 GPU 的機器）。

一個埠同時服務兩種客戶端，共用一條 ngrok/Cloudflare 通道：

  ── App（Android）── 直接講 GPT-SoVITS api_v2 協定，本橋接反向代理到 127.0.0.1:9880。
  ── Web（github.io，瀏覽器）── 講 9879 橋接協定（含 CORS）。

模型來源（二擇一，自動判斷）：
  A) 情緒資料夾結構（新）：<root>/<LANG>/<角色>/{*.ckpt, *.pth, reference_audios/**/emotions/【情緒】逐字稿.wav}
     每個角色有一組「情緒→參考音」，合成時依情緒切換參考音（換情緒＝換語氣）。
  B) model_history.json（舊）：單一參考音。

情緒：中立 / 开心 / 难过 / 恐惧 / 吃惊（合成時自動從文字偵測，或由 emotion 參數指定）。

環境變數：GSV_ROOT、HISTORY_PATH、API_BASE、IMAGE_BASE、TEXT_BASE、BRIDGE_PORT
"""
import http.server
import json
import os
import re
import socket
import socketserver
import threading
import time
import urllib.parse
import urllib.request

GSV_ROOT = os.environ.get("GSV_ROOT", ".").rstrip("/")
HISTORY_PATH = os.environ.get("HISTORY_PATH", f"{GSV_ROOT}/user_models/model_history.json")
MODELS_ROOT = os.environ.get("MODELS_ROOT", GSV_ROOT).rstrip("/")   # 情緒資料夾結構的根（含 ZH/EN/JA/KO）
API_BASE = os.environ.get("API_BASE", "http://127.0.0.1:9880").rstrip("/")
IMAGE_BASE = os.environ.get("IMAGE_BASE", "http://127.0.0.1:9881").rstrip("/")
TEXT_BASE = os.environ.get("TEXT_BASE", "http://127.0.0.1:9882").rstrip("/")
BRIDGE_PORT = int(os.environ.get("BRIDGE_PORT", "9879"))

PROXY_PREFIXES = ("/tts", "/set_gpt_weights", "/set_sovits_weights",
                  "/set_refer_audio", "/control", "/stop")
_LANG_TAG_TO_API = {"ZH": "zh", "EN": "en", "JA": "ja", "KO": "ko"}
LANG_DIRS = {"ZH", "EN", "JA", "KO"}

# ── 情緒 ─────────────────────────────────────────────
NEUTRAL = "中立"
# 標準情緒 → 檔名【】標籤的別名（繁簡都收），偵測與比對都用這張表正規化。
EMOTION_ALIASES = {
    "中立": ["中立", "平静", "平靜", "neutral"],
    "开心": ["开心", "開心", "高兴", "高興", "快乐", "快樂", "喜悦", "happy"],
    "难过": ["难过", "難過", "伤心", "傷心", "难受", "難受", "sad"],
    "恐惧": ["恐惧", "恐懼", "害怕", "惊恐", "驚恐", "fear"],
    "吃惊": ["吃惊", "吃驚", "惊讶", "驚訝", "惊喜", "驚喜", "surprise"],
}
_LABEL_TO_CANON = {a: canon for canon, al in EMOTION_ALIASES.items() for a in al}

# 從使用者要唸的文字自動判情緒的關鍵字（繁簡混收）。命中最多的勝；平手/沒中 → 中立。
_EMOTION_KEYWORDS = {
    "开心": ["開心", "开心", "高興", "高兴", "快樂", "快乐", "太好", "好耶", "謝謝", "谢谢", "感謝", "感谢",
            "喜歡", "喜欢", "愛你", "爱你", "棒", "讚", "赞", "哈哈", "耶", "幸福", "舒服", "😊", "😄", "❤"],
    "难过": ["難過", "难过", "傷心", "伤心", "想哭", "好累", "不想", "唉", "失望", "孤單", "孤单", "難受",
            "难受", "委屈", "痛", "😢", "😭"],
    "恐惧": ["害怕", "好怕", "救命", "危險", "危险", "不敢", "恐怖", "嚇", "吓", "緊張", "紧张", "求救", "😨", "😱"],
    "吃惊": ["什麼", "什么", "怎麼會", "怎么会", "天啊", "居然", "竟然", "哇", "真的假的", "不會吧", "不会吧",
            "太扯", "驚", "惊", "😲", "😮"],
}


def canon_emotion(label):
    """把檔名裡的【label】或任意情緒字串正規化成標準情緒；認不得就原樣回。"""
    return _LABEL_TO_CANON.get((label or "").strip(), (label or "").strip())


def detect_emotion(text):
    """從要唸的文字關鍵字自動判情緒；沒把握回中立。"""
    t = text or ""
    best, score = NEUTRAL, 0
    for emo, kws in _EMOTION_KEYWORDS.items():
        c = sum(1 for k in kws if k in t)
        if c > score:
            best, score = emo, c
    if score == 0:
        if "？" in t or "?" in t:
            return "吃惊"
        if "！" in t or "!" in t:
            return "开心"
    return best


def _lang_of_name(name):
    m = re.search(r"[ _](ZH|CN|EN|JA|JP|KO|KR)\b", name or "", re.IGNORECASE)
    if not m:
        return "ZH"
    t = m.group(1).upper()
    return {"CN": "ZH", "JP": "JA", "KR": "KO"}.get(t, t)


def _parse_ref_filename(fname):
    """『【情緒】逐字稿.wav』→ (canon情緒, 逐字稿)。沒有【】→ (中立, 檔名去副檔名)。"""
    stem = os.path.splitext(fname)[0]
    m = re.match(r"^\s*[【\[]\s*([^】\]]+?)\s*[】\]]\s*(.*)$", stem)
    if m:
        return canon_emotion(m.group(1)), m.group(2).strip()
    return NEUTRAL, stem.strip()


# ── 模型清單（含情緒對照）：情緒資料夾結構優先，否則退回 model_history.json ──
_models_cache = None


def _scan_emotion_structure():
    """掃 <root>/<LANG>/<角色>/：回 [{name, lang, gpt, sovits, emotions{canon:{ref,prompt}}}]。"""
    out = []
    if not os.path.isdir(MODELS_ROOT):
        return out
    for lang in sorted(os.listdir(MODELS_ROOT)):
        ldir = os.path.join(MODELS_ROOT, lang)
        if lang.upper() not in LANG_DIRS or not os.path.isdir(ldir):
            continue
        LANG = lang.upper()
        for char in sorted(os.listdir(ldir)):
            cdir = os.path.join(ldir, char)
            if not os.path.isdir(cdir):
                continue
            gpt = sovits = None
            for f in os.listdir(cdir):
                if f.lower().endswith(".ckpt"):
                    gpt = os.path.join(cdir, f)
                elif f.lower().endswith(".pth"):
                    sovits = os.path.join(cdir, f)
            if not gpt or not sovits:
                continue
            # reference_audios 底下任意深度找 *.wav/*.mp3/*.flac
            emotions = {}
            for root, _dirs, files in os.walk(os.path.join(cdir, "reference_audios")):
                for f in files:
                    if not f.lower().endswith((".wav", ".mp3", ".flac")):
                        continue
                    emo, prompt = _parse_ref_filename(f)
                    if emo not in emotions:   # 同情緒取第一顆
                        emotions[emo] = {"ref": os.path.join(root, f), "prompt": prompt}
            if not emotions:
                continue
            out.append({
                "name": f"{char} {LANG}", "character": char, "lang": LANG,
                "gpt": gpt, "sovits": sovits, "emotions": emotions,
            })
    return out


def _load_history_models():
    """舊 model_history.json → 統一成含 emotions（單一情緒＝中立）的清單。"""
    try:
        with open(HISTORY_PATH, encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        return []
    out = []
    for r in (data if isinstance(data, list) else []):
        ref = r.get("ref_path", "")
        out.append({
            "name": r.get("name", ""), "character": r.get("name", ""),
            "lang": _lang_of_name(r.get("name", "")),
            "gpt": r.get("gpt_rel", ""), "sovits": r.get("sovits_rel", ""),
            "emotions": {NEUTRAL: {"ref": ref, "prompt": r.get("prompt", "")}},
        })
    return out


def load_models(force=False):
    global _models_cache
    if _models_cache is not None and not force:
        return _models_cache
    m = _scan_emotion_structure()
    if not m:
        m = _load_history_models()
    _models_cache = m
    return m


def _find(name):
    for m in load_models():
        if m["name"] == name or m.get("character") == name:
            return m
    return None


def _load_or_build_catalog():
    """雲端曲庫目錄索引：優先讀 MODELS_ROOT/catalog.json（notebook cell 8 寫的），
    沒有就從 load_models() 現掃組一份（與 Mac emotion_models.build_catalog 同格式）。"""
    try:
        with open(os.path.join(MODELS_ROOT, "catalog.json"), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        pass

    def _s(p):
        try:
            return os.path.getsize(p)
        except OSError:
            return 0
    cat = {"version": 1, "updatedAt": int(time.time() * 1000),
           "characters": [], "summary": {"characters": 0, "ready": 0, "bytes": 0}}
    for m in load_models():
        gpt = {"path": m.get("gpt", ""), "size": _s(m.get("gpt", ""))} if m.get("gpt") else None
        sov = {"path": m.get("sovits", ""), "size": _s(m.get("sovits", ""))} if m.get("sovits") else None
        refs = [{"path": v["ref"], "emotion": k, "prompt": v.get("prompt", ""), "size": _s(v["ref"])}
                for k, v in m.get("emotions", {}).items()]
        ok = bool(gpt and sov and refs)
        b = (gpt or {}).get("size", 0) + (sov or {}).get("size", 0) + sum(r["size"] for r in refs)
        cat["characters"].append({"name": m.get("name", ""), "character": m.get("character", m.get("name", "")),
                                  "lang": m.get("lang", ""), "ok": ok,
                                  "emotions": sorted(m.get("emotions", {}).keys()),
                                  "gpt": gpt, "sovits": sov, "refs": refs, "bytes": b})
        cat["summary"]["characters"] += 1
        cat["summary"]["ready"] += 1 if ok else 0
        cat["summary"]["bytes"] += b
    return cat


def _pick_ref(model, emotion):
    """挑該角色在指定情緒的參考音；沒有就退中立、再退任一顆。"""
    emo = canon_emotion(emotion) if emotion else None
    ems = model.get("emotions", {})
    for key in (emo, NEUTRAL):
        if key and key in ems:
            return key, ems[key]
    if ems:
        k = next(iter(ems)); return k, ems[k]
    return None, None


# ── 呼叫 api_v2 ──────────────────────────────────────
_lock = threading.Lock()
_current = {"name": "", "emotion": ""}


def _api_get(path):
    with urllib.request.urlopen(f"{API_BASE}{path}", timeout=120) as r:
        return r.status, r.read()


def _port_up(base):
    try:
        u = urllib.parse.urlparse(base)
        with socket.create_connection((u.hostname, u.port or 80), timeout=0.6):
            return True
    except Exception:
        return False


def _switch(name):
    """載入角色的 GPT+SoVITS 權重（惰性）。回 (ok, msg)。情緒不需切權重，只換參考音。"""
    with _lock:
        if _current["name"] == name:
            return True, "already loaded"
        m = _find(name)
        if not m:
            return False, f"找不到角色：{name}"
        if not m.get("gpt") or not m.get("sovits"):
            return False, f"角色 {name} 缺少權重"
        try:
            for ep, wp in (("/set_sovits_weights", m["sovits"]), ("/set_gpt_weights", m["gpt"])):
                q = urllib.parse.urlencode({"weights_path": wp})
                code, body = _api_get(f"{ep}?{q}")
                if code != 200:
                    return False, f"{ep} HTTP {code}: {body[:160].decode('utf-8','replace')}"
            _current["name"] = name
            return True, "switched"
        except Exception as e:
            return False, str(e)


def _speak(text, name, lang, emotion):
    """合成 → (wav_bytes, err, used_emotion)。emotion 空 → 從 text 自動偵測。"""
    if name:
        ok, msg = _switch(name)
        if not ok:
            return None, msg, ""
    elif not _current["name"]:
        ms = load_models()
        if not ms:
            return None, "雲端上沒有任何語音模型", ""
        ok, msg = _switch(ms[0]["name"])
        if not ok:
            return None, msg, ""
    m = _find(_current["name"]) or {}
    used = canon_emotion(emotion) if emotion else detect_emotion(text)
    ekey, ref = _pick_ref(m, used)
    if not ref:
        return None, "此角色沒有任何參考音", ""
    tag = (lang or m.get("lang") or "ZH").upper()
    api_lang = _LANG_TAG_TO_API.get(tag, "zh")
    payload = {
        "text": text, "text_lang": api_lang,
        "ref_audio_path": ref["ref"], "prompt_text": ref["prompt"], "prompt_lang": api_lang,
        "media_type": "wav", "streaming_mode": False, "text_split_method": "cut5",
        "batch_size": 1, "top_k": 20, "top_p": 0.95, "temperature": 1.05,
        "repetition_penalty": 1.15, "fragment_interval": 0.18,
        "parallel_infer": True, "split_bucket": False, "sample_steps": 16, "speed_factor": 1.02,
    }
    try:
        req = urllib.request.Request(f"{API_BASE}/tts", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=300) as r:
            data = r.read()
        if len(data) < 1024:
            return None, data.decode("utf-8", "replace"), ekey
        _current["emotion"] = ekey
        return data, None, ekey
    except Exception as e:
        return None, str(e), ekey


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        # ngrok-skip-browser-warning：網頁繞過 ngrok 免費版攔截頁用的自訂標頭，
        # 不放行的話瀏覽器 preflight 會被拒 → 網頁永遠連不上 Colab。
        self.send_header("Access-Control-Allow-Headers", "Content-Type, ngrok-skip-browser-warning")

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code); self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers(); self.wfile.write(b)

    def _wav(self, data, emotion=""):
        self.send_response(200); self._cors()
        self.send_header("Content-Type", "audio/wav")
        if emotion:
            self.send_header("X-Emotion", urllib.parse.quote(emotion))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers(); self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length", "0") or "0")
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return {}

    def _proxy(self):
        try:
            code, body = _api_get(self.path)
            self.send_response(code); self._cors()
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
        except Exception as e:
            self._json(502, {"ok": False, "error": str(e)})

    def do_OPTIONS(self):
        self.send_response(204); self._cors()
        self.send_header("Content-Length", "0"); self.end_headers()

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/health"):
            m = _find(_current["name"]) if _current["name"] else None
            return self._json(200, {
                "ok": True, "service": "voiceweaver-cloud-bridge",
                "current": _current["name"], "emotion": _current["emotion"],
                "voice": _port_up(API_BASE), "image": _port_up(IMAGE_BASE), "text": _port_up(TEXT_BASE),
                "t2s_weights_path": (m or {}).get("gpt", ""),
                "vits_weights_path": (m or {}).get("sovits", ""),
            })
        if path == "/voices":
            vs = [{"name": m["name"], "lang": m["lang"],
                   "emotions": sorted(m.get("emotions", {}).keys())} for m in load_models()]
            return self._json(200, {"ok": True, "voices": vs})
        if path == "/models":
            out = []
            for m in load_models():
                neu = m.get("emotions", {}).get(NEUTRAL) or next(iter(m.get("emotions", {}).values()), {})
                out.append({"name": m["name"], "lang": m["lang"],
                            "gpt_rel": m.get("gpt", ""), "sovits_rel": m.get("sovits", ""),
                            "ref_path": neu.get("ref", ""), "prompt": neu.get("prompt", ""),
                            "emotions": {k: {"ref_path": v["ref"], "prompt": v["prompt"]}
                                         for k, v in m.get("emotions", {}).items()}})
            return self._json(200, {"ok": True, "models": out})
        if path == "/emotions":   # 目前角色可用的情緒清單（給 UI 手動覆寫用）
            m = _find(_current["name"]) if _current["name"] else None
            return self._json(200, {"ok": True,
                                    "emotions": sorted((m or {}).get("emotions", {}).keys()),
                                    "all": list(EMOTION_ALIASES.keys())})
        if path == "/catalog":   # 雲端曲庫目錄索引（給手機 Apple Music 式瀏覽整個曲庫）
            return self._json(200, {"ok": True, "catalog": _load_or_build_catalog()})
        if path == "/prepare":   # 隨選下載：讀該角色檔案，觸發 Drive 掛載串流下載到 Colab 本地
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            who = (q.get("character") or q.get("name") or [""])[0]
            m = _find(who)
            if not m:
                return self._json(404, {"ok": False, "error": "找不到角色"})
            total = files = 0
            for p in [m.get("gpt", ""), m.get("sovits", "")] + [v["ref"] for v in m.get("emotions", {}).values()]:
                try:
                    with open(p, "rb") as f:
                        while True:
                            chunk = f.read(1024 * 1024)
                            if not chunk:
                                break
                            total += len(chunk)
                    files += 1
                except OSError:
                    pass
            return self._json(200, {"ok": files > 0, "name": m.get("name", ""),
                                    "lang": m.get("lang", ""), "bytes": total, "files": files})
        if path.startswith(PROXY_PREFIXES):
            return self._proxy()
        self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        data = self._body()
        if path == "/switch":
            ok, msg = _switch(str(data.get("name", "")))
            return self._json(200 if ok else 500, {"ok": ok, "msg": msg})
        if path == "/detect_emotion":
            return self._json(200, {"ok": True, "emotion": detect_emotion(str(data.get("text", "")))})
        if path == "/speak":
            text = str(data.get("text", "")).strip()
            if not text:
                return self._json(400, {"ok": False, "error": "empty text"})
            wav, err, emo = _speak(text, str(data.get("name", "")), str(data.get("lang", "")),
                                   str(data.get("emotion", "")))
            if wav is None:
                return self._json(500, {"ok": False, "error": err})
            return self._wav(wav, emo)
        if path == "/image":
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
        if path == "/text":
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
        if path.startswith("/tts"):
            return self._proxy_post()
        self._json(404, {"ok": False, "error": "not found"})

    def _proxy_post(self):
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(n)
            req = urllib.request.Request(f"{API_BASE}{self.path}", data=raw,
                                         headers={"Content-Type": self.headers.get("Content-Type", "application/json")},
                                         method="POST")
            with urllib.request.urlopen(req, timeout=300) as r:
                body = r.read(); ctype = r.headers.get("Content-Type", "application/octet-stream")
            self.send_response(200); self._cors()
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
        except Exception as e:
            self._json(502, {"ok": False, "error": str(e)})


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


def main():
    n = len(load_models())
    srv = ThreadingServer(("0.0.0.0", BRIDGE_PORT), Handler)
    print(f"[bridge] VoiceWeaver 雲端橋接 :{BRIDGE_PORT} → api_v2 {API_BASE}｜掃到 {n} 個角色", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
