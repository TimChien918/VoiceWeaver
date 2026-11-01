"""Firebase IP Beacon — 每次語音中心啟動或 IP 變動時，把電腦 LAN/Tailscale IP
寫到 Firestore devices/{uid}，讓手機 App 不需手動填 IP。

認證策略：匿名 Firebase Auth（REST API，只需 Web API Key）。
uid 與 refresh token 存在 ~/.speakmylife/beacon_token.json（Windows：%APPDATA%\Speak-My-Life）；
更名前的 ~/.voiceweaver（%APPDATA%\VoiceWeaver）第一次啟動時自動搬過來。
首次執行讀不到 Web API Key 時，會從 SML_FIREBASE_WEB_API_KEY 環境變數或設定檔讀取。
"""
from __future__ import annotations

import json
import os
import platform
import socket
import subprocess
import time
import threading
import urllib.request
import urllib.error

# ── 設定檔路徑（跨平台）────────────────────────────────────────────────────
try:
    import sys as _sys
    _sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import sml_migrate as _mig
    _BASE = _mig.dot_dir()           # 新 ~/.speakmylife；只有舊的就改名搬過來，失敗沿用舊的
except Exception:  # noqa: BLE001
    if platform.system() == "Windows":
        _BASE = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "Speak-My-Life")
    else:
        _BASE = os.path.join(os.path.expanduser("~"), ".speakmylife")

TOKEN_FILE  = os.path.join(_BASE, "beacon_token.json")
CONFIG_FILE = os.path.join(_BASE, "firebase_config.json")

os.makedirs(_BASE, exist_ok=True)

# ── Firebase REST helpers ─────────────────────────────────────────────────
def _post(url: str, body: dict, headers: dict | None = None) -> dict:
    data = json.dumps(body).encode()
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method="POST")
    with urllib.request.urlopen(req, timeout=12) as r:
        return json.loads(r.read())

def _patch(url: str, body: dict, id_token: str) -> dict:
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {id_token}"},
        method="PATCH"
    )
    with urllib.request.urlopen(req, timeout=12) as r:
        return json.loads(r.read())

# ── 設定讀取 ──────────────────────────────────────────────────────────────
def load_firebase_config() -> dict:
    """讀取 Web API Key + Project ID。
    來源優先順序：
      1. ~/.speakmylife/firebase_config.json
      2. 環境變數 SML_FIREBASE_WEB_API_KEY / SML_FIREBASE_PROJECT_ID
    """
    cfg: dict = {}
    if os.path.isfile(CONFIG_FILE):
        try:
            cfg = json.loads(open(CONFIG_FILE).read())
        except Exception:
            pass
    cfg.setdefault("apiKey",     os.environ.get("SML_FIREBASE_WEB_API_KEY", ""))
    cfg.setdefault("projectId",  os.environ.get("SML_FIREBASE_PROJECT_ID", ""))
    return cfg

def save_firebase_config(api_key: str, project_id: str):
    with open(CONFIG_FILE, "w") as f:
        json.dump({"apiKey": api_key, "projectId": project_id}, f, indent=2)

# ── Token 管理 ────────────────────────────────────────────────────────────
def _load_token() -> dict:
    try:
        return json.loads(open(TOKEN_FILE).read())
    except Exception:
        return {}

def _save_token(data: dict):
    with open(TOKEN_FILE, "w") as f:
        json.dump(data, f)

def _anon_signin(api_key: str) -> dict:
    """匿名登入，回傳 {idToken, refreshToken, localId}。"""
    return _post(
        f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={api_key}",
        {"returnSecureToken": True}
    )

def _refresh(api_key: str, refresh_token: str) -> dict:
    """刷新 ID token，回傳 {id_token, refresh_token, user_id}。"""
    return _post(
        f"https://securetoken.googleapis.com/v1/token?key={api_key}",
        {"grant_type": "refresh_token", "refresh_token": refresh_token}
    )

def get_id_token(api_key: str) -> tuple[str, str]:
    """取得有效 ID token。如有快取先嘗試刷新，失敗則重新匿名登入。
    回傳 (id_token, uid)。
    """
    tok = _load_token()
    if tok.get("refresh_token"):
        try:
            r = _refresh(api_key, tok["refresh_token"])
            uid = r.get("user_id") or tok.get("localId", "")
            tok["idToken"]      = r["id_token"]
            tok["refreshToken"] = r["refresh_token"]
            tok["localId"]      = uid
            _save_token(tok)
            return r["id_token"], uid
        except Exception:
            pass
    # 重新匿名登入
    r = _anon_signin(api_key)
    _save_token({"idToken": r["idToken"], "refreshToken": r["refreshToken"], "localId": r["localId"]})
    return r["idToken"], r["localId"]

# ── IP 偵測 ───────────────────────────────────────────────────────────────
def _lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        finally:
            s.close()
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass
    return ""

def _tailscale_ip() -> str:
    cli = (["tailscale.exe"] if platform.system() == "Windows"
           else ["/Applications/Tailscale.app/Contents/MacOS/Tailscale",
                 "/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale", "tailscale"])
    for c in cli:
        try:
            r = subprocess.run([c, "ip", "-4"], capture_output=True, text=True, timeout=3)
            lines = [l.strip() for l in r.stdout.splitlines() if l.strip()]
            if r.returncode == 0 and lines:
                return lines[0]
        except Exception:
            pass
    return ""

# ── Firestore 寫入 ────────────────────────────────────────────────────────
def _write_to_firestore(project_id: str, id_token: str, uid: str,
                        lan: str, ts_ip: str, port: int = 9880):
    def url_of(ip: str) -> str:
        return f"http://{ip}:{port}" if ip else ""

    fields: dict = {
        "label":        {"stringValue": platform.node()},
        "lanUrl":       {"stringValue": url_of(lan)},
        "tailscaleUrl": {"stringValue": url_of(ts_ip)},
        "updatedAt":    {"integerValue": str(int(time.time() * 1000))}
    }
    url = (f"https://firestore.googleapis.com/v1/projects/{project_id}"
           f"/databases/(default)/documents/devices/{uid}")
    _patch(url, {"fields": fields}, id_token)

# ── 公開 API ──────────────────────────────────────────────────────────────
_beacon_thread: threading.Thread | None = None
_stop_event = threading.Event()

def start_beacon(port: int = 9880, interval: int = 60,
                 on_ready: "callable[[str, str], None] | None" = None):
    """背景執行緒定期更新 Firestore。
    on_ready(uid, lan_url) 在第一次成功寫入後呼叫（可用來在 UI 顯示 device code）。
    """
    global _beacon_thread, _stop_event
    _stop_event.clear()

    def _run():
        cfg = load_firebase_config()
        api_key    = cfg.get("apiKey", "")
        project_id = cfg.get("projectId", "")
        if not api_key or not project_id:
            return   # 未設定 Firebase，靜默退出

        first_ok = False
        while not _stop_event.is_set():
            try:
                id_token, uid = get_id_token(api_key)
                lan  = _lan_ip()
                ts   = _tailscale_ip()
                _write_to_firestore(project_id, id_token, uid, lan, ts, port)
                if not first_ok:
                    first_ok = True
                    if on_ready:
                        on_ready(uid, f"http://{lan}:{port}" if lan else "")
            except Exception as e:
                print(f"[BeaconError] {e}")
            _stop_event.wait(interval)

    _beacon_thread = threading.Thread(target=_run, daemon=True, name="firebase-ip-beacon")
    _beacon_thread.start()

def stop_beacon():
    _stop_event.set()

def get_device_uid() -> str:
    """回傳本機存的 uid（供 UI 顯示裝置代號）。"""
    return _load_token().get("localId", "")

def is_configured() -> bool:
    cfg = load_firebase_config()
    return bool(cfg.get("apiKey") and cfg.get("projectId"))
