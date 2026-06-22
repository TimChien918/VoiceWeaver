#!/usr/bin/env python3
"""VoiceWeaver 語音中心 — 原生桌面控制台（不用網頁）。

分頁：
  ① 控制台：啟動/停止各服務（語音 9880 / 生圖 9881 / 文字 9882）、連線位址、同步聲音模型。
  ② 模型 / 進階：把原本網頁控制中心的功能搬成原生介面——聲音模型清單（切換/刪除）、
     從資料夾匯入模型、測試合成、USB 重接；並提供「自己訓練模型（官方 WebUI）」按鈕。

重點：
  - 介面自動跟著系統深色/淺色切換。
  - 按鈕用可上色的 Label（macOS 的 tk.Button 不吃 bg）。
  - 關閉視窗 → 所有伺服器（含語音合成）自動停止。
  - 進階分頁直接重用既有後端函式（model_setup_server.py），不重寫權重複製等敏感邏輯。
"""
from __future__ import annotations

import os
import re
import socket
import subprocess
import sys
import threading
import webbrowser
import zipfile
import tkinter as tk
from tkinter import ttk, filedialog, simpledialog

ROOT = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable

# ── 跨平台 ──────────────────────────────────────────────────────────────
IS_WIN = (os.name == "nt")
IS_MAC = (sys.platform == "darwin")
ADB_BIN = "adb.exe" if IS_WIN else "adb"
# Windows 下生成子行程不要彈出黑色主控台視窗。
POPEN_KW = {"creationflags": 0x08000000} if IS_WIN else {}   # CREATE_NO_WINDOW

# USB 連線工具（adb）：找不到時自動下載 Google 官方 platform-tools 到本機。
_PT_PLATFORM = "windows" if IS_WIN else ("darwin" if IS_MAC else "linux")
PLATFORM_TOOLS_URL = f"https://dl.google.com/android/repository/platform-tools-latest-{_PT_PLATFORM}.zip"
LOCAL_PT_DIR = os.path.join(os.path.dirname(ROOT), "platform-tools")   # 與 GPT-SoVITS 同層
LOCAL_ADB = os.path.join(LOCAL_PT_DIR, ADB_BIN)

os.environ.setdefault("HF_HOME", os.path.join(ROOT, ".cache", "huggingface"))
os.environ.setdefault("MPLCONFIGDIR", os.path.join(ROOT, ".cache", "matplotlib"))
os.makedirs(os.environ["HF_HOME"], exist_ok=True)
os.makedirs(os.environ["MPLCONFIGDIR"], exist_ok=True)

# 重用後端函式庫（同目錄 model_setup_server.py，網頁已移除、只剩函式）。
try:
    import model_setup_server as cc
    CC_OK = True
except Exception as _e:  # noqa: BLE001
    cc = None
    CC_OK = False
    _CC_ERR = str(_e)

VOICE_CFG = "GPT_SoVITS/configs/voiceweaver_tts_infer.yaml"

# 預設 0.0.0.0（手機 Wi-Fi 需要）；只用 USB(adb reverse) 的人可設 VOICEWEAVER_BIND=127.0.0.1 鎖本機。
_BIND = os.environ.get("VOICEWEAVER_BIND", "0.0.0.0")

SERVICES = [
    ("9880", "語音合成（GPT-SoVITS）",
     [PY, "api_v2.py", "-a", _BIND, "-p", "9880", "-c", VOICE_CFG], True),
    ("9881", "本機生圖（SD-Turbo）", [PY, "image_gen_server.py"], False),
    ("9882", "文字推論（Qwen）", [PY, "text_infer_server.py"], False),
]

FONT = "Helvetica"
THEMES = {
    "dark":  dict(bg="#1c1c1e", card="#2c2c2e", fg="#ffffff", muted="#98989f",
                  neutral="#3a3a3c", listbg="#2c2c2e", listsel="#0a84ff"),
    "light": dict(bg="#f2f2f7", card="#ffffff", fg="#1c1c1e", muted="#6c6c70",
                  neutral="#e5e5ea", listbg="#ffffff", listsel="#0a84ff"),
}
GREEN = "#34c759"; RED = "#ff3b30"; BLUE = "#0a84ff"; AMBER = "#ff9500"; WHITE = "#ffffff"

HELP_TEXT = """使用說明

1. 開啟本 App，三個服務會自動啟動，綠燈＝就緒。

2. 手機連線（「控制台」分頁會顯示實際位址，擇一）：
   • USB：手機填 http://127.0.0.1:9880
   • Wi-Fi：填電腦 IP，例 http://192.168.x.x:9880
   • 外網：裝 Tailscale 後填 100.x 位址（tailscale.com/download）

3. 換聲音：到「模型 / 進階」分頁選一個按「切換」；
   沒有模型就按「從資料夾匯入」。

4. 用完直接關閉視窗 → 自動停止所有服務。

提示：改過設定要「關掉視窗再重開」才會生效。
"""


def system_is_dark() -> bool:
    if IS_WIN:
        try:
            import winreg
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                               r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize")
            val, _ = winreg.QueryValueEx(k, "AppsUseLightTheme")
            return val == 0
        except Exception:
            return False
    try:
        r = subprocess.run(["defaults", "read", "-g", "AppleInterfaceStyle"],
                           capture_output=True, text=True, timeout=2)
        return r.returncode == 0 and r.stdout.strip() == "Dark"
    except Exception:
        return False


def port_up(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.4)
        return s.connect_ex(("127.0.0.1", port)) == 0


def pids_on_port(port) -> list[str]:
    """跨平台找出正在 LISTEN 指定埠的行程 PID。"""
    port = str(port)
    try:
        if IS_WIN:
            out = subprocess.run(["netstat", "-ano", "-p", "TCP"],
                                 capture_output=True, text=True, timeout=5, **POPEN_KW).stdout
            pids = []
            for line in out.splitlines():
                parts = line.split()
                if len(parts) >= 5 and parts[0].upper() == "TCP" \
                        and parts[1].endswith(":" + port) and parts[3].upper() == "LISTENING":
                    if parts[-1].isdigit() and parts[-1] != "0":
                        pids.append(parts[-1])
            return list(dict.fromkeys(pids))
        out = subprocess.run(["lsof", "-tiTCP:" + port, "-sTCP:LISTEN"],
                             capture_output=True, text=True, timeout=4).stdout
        return out.split()
    except Exception:
        return []


# 其他可能有完整資料（含 4.6G pretrained_models）的安裝位置，供共用避免重複佔空間
ALT_GS_HOMES = [
    os.path.expanduser("~/VoiceWeaver-電腦端/GPT-SoVITS"),
    os.path.expanduser("~/AndroidStudioProjects/VoiceWeaver/tools/GPT-SoVITS"),
]


def ensure_pretrained() -> bool:
    """確保 GPT_SoVITS/pretrained_models（語音基礎模型，9880 啟動必需）存在。
    本機若已有其他安裝（如 ~/VoiceWeaver-電腦端）→ 用 symlink 共用，避免重複 4.6G。"""
    pm = os.path.join(ROOT, "GPT_SoVITS", "pretrained_models")
    if os.path.isdir(pm) and os.listdir(pm):
        return True
    for alt_gs in ALT_GS_HOMES:
        alt = os.path.join(alt_gs, "GPT_SoVITS", "pretrained_models")
        if (os.path.realpath(alt) != os.path.realpath(pm)
                and os.path.isdir(alt) and os.listdir(alt)):
            try:
                os.makedirs(os.path.dirname(pm), exist_ok=True)
                if os.path.islink(pm) or os.path.exists(pm):
                    try:
                        os.unlink(pm)
                    except OSError:
                        pass
                os.symlink(alt, pm)
                return True
            except OSError:
                pass
    return os.path.isdir(pm) and bool(os.listdir(pm))


def have_voice_models() -> bool:
    ensure_pretrained()
    d = os.path.join(ROOT, "GPT_SoVITS", "pretrained_models")
    return os.path.isdir(d) and bool(os.listdir(d))


def find_adb() -> str:
    if IS_WIN:
        cands = [
            LOCAL_ADB,                                  # App 自動安裝的（優先）
            os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
            os.path.expandvars(r"%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
            r"C:\platform-tools\adb.exe",
        ]
    else:
        cands = [
            LOCAL_ADB,                                  # App 自動安裝的（優先）
            "~/Library/Android/sdk/platform-tools/adb",
            "~/Library/Android/Sdk/platform-tools/adb",
            "/opt/homebrew/bin/adb", "/usr/local/bin/adb",
            "~/Android/Sdk/platform-tools/adb",
        ]
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            cands.insert(0, os.path.join(os.environ[env], "platform-tools", ADB_BIN))
    cands.append(ADB_BIN)  # 最後試 PATH
    for c in cands:
        c = os.path.expanduser(c)
        try:
            if subprocess.run([c, "version"], capture_output=True, timeout=6, **POPEN_KW).returncode == 0:
                return c
        except Exception:
            pass
    return ""


def resolve_existing(path: str) -> str:
    """容錯找出實際存在的檔案：
    (a) 路徑指向舊位置（專案/安裝搬家）→ 取已知子目錄起的相對段，接到現在的 ROOT。
    (b) 簡繁、NFC/NFD 正規化差異 → 同目錄整檔名/尾段比對。"""
    import unicodedata
    if not path:
        return path
    if os.path.exists(path):
        return path
    # (a) 重映舊路徑到現在的 ROOT（例如歷史存的是 .../AndroidStudioProjects/.../ref_audio/...）
    p = path.replace("\\", "/")
    for marker in ("/ref_audio/", "/user_models/", "/SoVITS_weights_",
                   "/GPT_weights_", "/GPT_SoVITS/"):
        idx = p.find(marker)
        if idx != -1:
            cand = os.path.join(ROOT, p[idx + 1:])
            if os.path.exists(cand):
                return cand
            path = cand          # 用重映後的位置繼續做檔名容錯
            break
    if os.path.exists(path):
        return path
    d, base = os.path.dirname(path), os.path.basename(path)
    if not os.path.isdir(d):
        return path
    for norm in ("NFC", "NFD"):
        cand = os.path.join(d, unicodedata.normalize(norm, base))
        if os.path.exists(cand):
            return cand
    try:
        files = os.listdir(d)
    except OSError:
        return path
    tb = unicodedata.normalize("NFC", base)
    for f in files:
        if unicodedata.normalize("NFC", f) == tb:
            return os.path.join(d, f)
    # 尾段比對：如 「我的聲音_ZH_e10_s1400_l32.pth」→ 用「_ZH_e10_s1400_l32.pth」找到 NFD/NFC 變體
    if "_" in base:
        suffix = base[base.index("_"):]
        cands = [f for f in files if f.endswith(suffix)]
        if len(cands) == 1:
            return os.path.join(d, cands[0])
    return path


def install_adb() -> str:
    """自動下載 Google 官方 platform-tools（含 adb）到本機並回傳 adb 路徑。
    用系統 curl 下載（conda 版 Python 常缺 CA 憑證、urllib 會 SSL 失敗）；
    curl/zip 下載不帶 quarantine 屬性，可直接執行、不會被 Gatekeeper 擋。"""
    try:
        parent = os.path.dirname(LOCAL_PT_DIR)
        os.makedirs(parent, exist_ok=True)
        zpath = LOCAL_PT_DIR + ".zip"
        ok = False
        # 優先用系統 curl（Win10+ 內建 curl.exe）；失敗再退回 urllib。
        try:
            r = subprocess.run(["curl", "-fsSL", "-o", zpath, PLATFORM_TOOLS_URL],
                               timeout=300, **POPEN_KW)
            ok = (r.returncode == 0 and os.path.exists(zpath))
        except Exception:
            ok = False
        if not ok:
            try:
                import urllib.request
                urllib.request.urlretrieve(PLATFORM_TOOLS_URL, zpath)
                ok = os.path.exists(zpath)
            except Exception:
                ok = False
        if not ok:
            return ""
        with zipfile.ZipFile(zpath) as z:
            z.extractall(parent)        # zip 內含頂層 platform-tools/ 目錄
        try:
            os.remove(zpath)
        except OSError:
            pass
        if os.path.exists(LOCAL_ADB):
            if not IS_WIN:
                os.chmod(LOCAL_ADB, 0o755)
                # 同層的 lib 也給執行權
                for fn in os.listdir(LOCAL_PT_DIR):
                    fp = os.path.join(LOCAL_PT_DIR, fn)
                    if os.path.isfile(fp):
                        try:
                            os.chmod(fp, 0o755)
                        except OSError:
                            pass
            if subprocess.run([LOCAL_ADB, "version"], capture_output=True, timeout=8, **POPEN_KW).returncode == 0:
                return LOCAL_ADB
    except Exception:
        pass
    return ""


def lan_ip() -> str:
    # 跨平台：用一個 UDP socket「假連線」到外部位址，取本機對外網卡 IP（不會真的送封包）。
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
    if not IS_WIN:                          # mac/linux 後援
        for iface in ("en0", "en1", "en2"):
            try:
                r = subprocess.run(["ipconfig", "getifaddr", iface],
                                   capture_output=True, text=True, timeout=2)
                if r.stdout.strip():
                    return r.stdout.strip()
            except Exception:
                pass
    return ""


def tailscale_ip() -> str:
    # 1) 最可靠：呼叫 Tailscale CLI（跨平台，多個可能路徑）。
    cli = (["tailscale.exe",
            os.path.expandvars(r"%ProgramFiles%\Tailscale\tailscale.exe")]
           if IS_WIN else
           ["/Applications/Tailscale.app/Contents/MacOS/Tailscale",
            "/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale", "tailscale"])
    for c in cli:
        try:
            r = subprocess.run([c, "ip", "-4"], capture_output=True, text=True, timeout=4, **POPEN_KW)
            lines = [l.strip() for l in r.stdout.splitlines() if l.strip()]
            if r.returncode == 0 and lines:
                return lines[0]
        except Exception:
            pass
    # 2) 後援：從網路介面找 Tailscale 的 100.64.0.0/10（CGNAT）位址。
    try:
        cmd = ["ipconfig"] if IS_WIN else ["ifconfig"]
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=4, **POPEN_KW).stdout
        for m in re.finditer(r"(100\.\d+\.\d+\.\d+)", out):
            ip = m.group(1)
            if 64 <= int(ip.split(".")[1]) <= 127:        # 100.64.0.0/10
                return ip
    except Exception:
        pass
    return ""


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.procs: dict[str, subprocess.Popen] = {}
        self.rows: dict[str, dict] = {}
        self.adb = find_adb()
        self.dark = system_is_dark()
        self.themed: list[tuple] = []
        self.adv_history: list[dict] = []

        root.title("VoiceWeaver 語音中心")
        root.geometry("600x680")
        root.minsize(540, 600)
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self._track(root, "bg")

        self.nb = ttk.Notebook(root)
        self.nb.pack(fill="both", expand=True, padx=6, pady=6)
        self.tab_main = tk.Frame(self.nb); self._track(self.tab_main, "bg")
        self.tab_adv = tk.Frame(self.nb); self._track(self.tab_adv, "bg")
        self.tab_help = tk.Frame(self.nb); self._track(self.tab_help, "bg")
        self.nb.add(self.tab_main, text="  控制台  ")
        self.nb.add(self.tab_adv, text="  模型 / 進階  ")
        self.nb.add(self.tab_help, text="  說明  ")

        self._build_main(self.tab_main)
        self._build_adv(self.tab_adv)
        self._build_help(self.tab_help)

        self.apply_theme()
        self.root.after(300, self.autostart)
        self.refresh()

    # ── 主題 ─────────────────────────────────────────────────────
    def _track(self, w, role):
        self.themed.append((w, role))

    def apply_theme(self):
        t = THEMES["dark" if self.dark else "light"]
        for w, role in self.themed:
            try:
                if role == "bg":
                    w.configure(bg=t["bg"])
                elif role == "card":
                    w.configure(bg=t["card"])
                elif role == "title":
                    w.configure(bg=w.master.cget("bg"), fg=t["fg"])
                elif role == "fg":
                    w.configure(bg=w.master.cget("bg"), fg=t["fg"])
                elif role == "muted":
                    w.configure(bg=w.master.cget("bg"), fg=t["muted"])
                elif role == "neutralbtn":
                    w.configure(bg=t["neutral"], fg=t["fg"])
                elif role == "list":
                    w.configure(bg=t["listbg"], fg=t["fg"], selectbackground=t["listsel"], selectforeground=WHITE)
                elif role == "entry":
                    w.configure(bg=t["card"], fg=t["fg"], insertbackground=t["fg"])
                elif role == "text":
                    w.configure(bg=t["card"], fg=t["fg"])
            except Exception:
                pass

    # ── 按鈕（Label，可上色）─────────────────────────────────────
    def _accent_btn(self, parent, text, cmd, color):
        b = tk.Label(parent, text=text, fg=WHITE, bg=color, cursor="hand2",
                     font=(FONT, 12, "bold"), padx=12, pady=9)
        b.bind("<Button-1>", lambda e: cmd())
        return b

    def _neutral_btn(self, parent, text, cmd):
        b = tk.Label(parent, text=text, cursor="hand2", font=(FONT, 12), padx=12, pady=9)
        b.bind("<Button-1>", lambda e: cmd())
        self._track(b, "neutralbtn")
        return b

    # ── 分頁①：控制台 ───────────────────────────────────────────
    def _build_main(self, parent):
        self.title_lbl = tk.Label(parent, text="VoiceWeaver 語音中心", font=(FONT, 19, "bold"))
        self.title_lbl.pack(pady=(14, 2)); self._track(self.title_lbl, "title")
        sub = tk.Label(parent, text="本機服務（關閉視窗會自動停止所有服務）", font=(FONT, 11))
        sub.pack(pady=(0, 10)); self._track(sub, "muted")

        for port, name, cmd, needs in SERVICES:
            self._service_row(parent, port, name, cmd, needs)

        conn = tk.Frame(parent); conn.pack(fill="x", padx=16, pady=(8, 6)); self._track(conn, "card")
        ch = tk.Label(conn, text="手機連線位址", font=(FONT, 12, "bold"))
        ch.pack(anchor="w", padx=12, pady=(10, 2)); self._track(ch, "fg")
        self.conn_label = tk.Label(conn, text="偵測中…", justify="left", font=(FONT, 11), anchor="w")
        self.conn_label.pack(fill="x", padx=12, pady=(0, 10)); self._track(self.conn_label, "muted")

        acts = tk.Frame(parent); acts.pack(fill="x", padx=16, pady=(4, 6)); self._track(acts, "bg")
        self._accent_btn(acts, "🔁 同步聲音模型", self.sync_models, BLUE).pack(side="left", expand=True, fill="x", padx=4)
        self._accent_btn(acts, "全部停止並結束", self.on_close, RED).pack(side="left", expand=True, fill="x", padx=4)

        self.status = tk.Label(parent, text="", font=(FONT, 10)); self.status.pack(pady=(2, 8)); self._track(self.status, "muted")

    def _service_row(self, parent, port, name, cmd, needs):
        card = tk.Frame(parent); card.pack(fill="x", padx=16, pady=4); self._track(card, "card")
        dot = tk.Label(card, text="●", font=(FONT, 16)); dot.pack(side="left", padx=(12, 8)); self._track(dot, "muted")
        info = tk.Frame(card); info.pack(side="left", fill="x", expand=True, pady=8); self._track(info, "card")
        nm = tk.Label(info, text=name, font=(FONT, 13, "bold"), anchor="w"); nm.pack(anchor="w"); self._track(nm, "fg")
        st = tk.Label(info, text="未啟動", font=(FONT, 10), anchor="w"); st.pack(anchor="w"); self._track(st, "muted")
        btn = tk.Label(card, text="啟動", fg=WHITE, bg=GREEN, cursor="hand2",
                       font=(FONT, 12, "bold"), width=6, padx=8, pady=8)
        btn.bind("<Button-1>", lambda e, p=port: self.toggle(p))
        btn.pack(side="right", padx=12, pady=8)
        self.rows[port] = {"dot": dot, "status": st, "btn": btn, "name": name, "cmd": cmd, "needs": needs}

    # ── 分頁②：模型 / 進階 ──────────────────────────────────────
    def _build_adv(self, outer):
        if not CC_OK:
            lb = tk.Label(outer, text=f"後端模組載入失敗：{_CC_ERR}", font=(FONT, 11), wraplength=500, justify="left")
            lb.pack(pady=12, padx=16); self._track(lb, "muted")
            return

        # 可捲動容器：控制項較多，避免超出視窗高度時被切掉（看不到下面的按鈕）。
        canvas = tk.Canvas(outer, highlightthickness=0, bd=0); self._track(canvas, "bg")
        vsb = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        parent = tk.Frame(canvas); self._track(parent, "bg")
        win = canvas.create_window((0, 0), window=parent, anchor="nw")
        parent.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfig(win, width=e.width))
        def _wheel(e):
            canvas.yview_scroll(-1 if e.delta > 0 else 1, "units")
        canvas.bind("<Enter>", lambda e: canvas.bind_all("<MouseWheel>", _wheel))
        canvas.bind("<Leave>", lambda e: canvas.unbind_all("<MouseWheel>"))

        self.adv_api = tk.Label(parent, text="目前模型：偵測中…", font=(FONT, 11), anchor="w", justify="left")
        self.adv_api.pack(fill="x", padx=16, pady=(10, 2)); self._track(self.adv_api, "muted")

        hdr = tk.Label(parent, text="聲音模型（選一個 → 切換／重新命名／刪除）", font=(FONT, 13, "bold"))
        hdr.pack(anchor="w", padx=16, pady=(8, 4)); self._track(hdr, "fg")

        listwrap = tk.Frame(parent); listwrap.pack(fill="x", padx=16); self._track(listwrap, "bg")
        self.model_list = tk.Listbox(listwrap, height=5, font=(FONT, 12), activestyle="none",
                                     highlightthickness=0, bd=0)
        self.model_list.pack(side="left", fill="both", expand=True)
        self._track(self.model_list, "list")
        sb = ttk.Scrollbar(listwrap, command=self.model_list.yview); sb.pack(side="right", fill="y")
        self.model_list.config(yscrollcommand=sb.set)

        row1 = tk.Frame(parent); row1.pack(fill="x", padx=16, pady=6); self._track(row1, "bg")
        self._accent_btn(row1, "切換為此模型", self.adv_switch, GREEN).pack(side="left", expand=True, fill="x", padx=3)
        self._neutral_btn(row1, "重新命名", self.adv_rename).pack(side="left", expand=True, fill="x", padx=3)
        self._neutral_btn(row1, "重新整理", self.adv_reload).pack(side="left", expand=True, fill="x", padx=3)
        self._accent_btn(row1, "刪除", self.adv_delete, RED).pack(side="left", expand=True, fill="x", padx=3)

        row2 = tk.Frame(parent); row2.pack(fill="x", padx=16, pady=6); self._track(row2, "bg")
        self._accent_btn(row2, "📁 從資料夾匯入模型", self.adv_import, BLUE).pack(side="left", expand=True, fill="x", padx=3)
        self._neutral_btn(row2, "⏹ 中止合成", self.adv_stopgen).pack(side="left", expand=True, fill="x", padx=3)

        # 測試合成
        tr = tk.Frame(parent); tr.pack(fill="x", padx=16, pady=(10, 4)); self._track(tr, "bg")
        tl = tk.Label(tr, text="測試合成：", font=(FONT, 12)); tl.pack(side="left"); self._track(tl, "fg")
        self.test_entry = tk.Entry(tr, font=(FONT, 12)); self.test_entry.insert(0, "你好，這是測試。")
        self.test_entry.pack(side="left", fill="x", expand=True, padx=6); self._track(self.test_entry, "entry")
        self._accent_btn(tr, "🔊 試聽", self.adv_test, BLUE).pack(side="left", padx=3)

        # 測試音檔操作
        ta = tk.Frame(parent); ta.pack(fill="x", padx=16, pady=(4, 4)); self._track(ta, "bg")
        self._neutral_btn(ta, "▶ 播放測試音檔", self.adv_play).pack(side="left", expand=True, fill="x", padx=3)
        self._neutral_btn(ta, "⏸ 停止播放", self.adv_stopplay).pack(side="left", expand=True, fill="x", padx=3)
        self._neutral_btn(ta, "📂 在 Finder 顯示", self.adv_reveal).pack(side="left", expand=True, fill="x", padx=3)

        # 工具列
        row3 = tk.Frame(parent); row3.pack(fill="x", padx=16, pady=(10, 4)); self._track(row3, "bg")
        self._neutral_btn(row3, "🔌 重新接手機(USB)", self.adv_adb).pack(side="left", expand=True, fill="x", padx=3)
        self._neutral_btn(row3, "⬇ 安裝 USB 工具(adb)", lambda: self.ensure_adb(manual=True)).pack(side="left", expand=True, fill="x", padx=3)

        row3b = tk.Frame(parent); row3b.pack(fill="x", padx=16, pady=(0, 8)); self._track(row3b, "bg")
        self._accent_btn(row3b, "🎓 自己訓練模型（官方）", self.open_training, AMBER).pack(side="left", expand=True, fill="x", padx=3)

        self.adv_status = tk.Label(parent, text="", font=(FONT, 10), wraplength=540, justify="left")
        self.adv_status.pack(fill="x", padx=16, pady=(2, 10)); self._track(self.adv_status, "muted")

        self.adv_reload()

    # ── 分頁③：說明 ─────────────────────────────────────────────
    def _build_help(self, parent):
        wrap = tk.Frame(parent); wrap.pack(fill="both", expand=True, padx=12, pady=12); self._track(wrap, "bg")
        txt = tk.Text(wrap, wrap="word", font=(FONT, 12), relief="flat", bd=0,
                      padx=14, pady=12, highlightthickness=0, spacing1=2, spacing3=4)
        txt.pack(side="left", fill="both", expand=True)
        sb = ttk.Scrollbar(wrap, command=txt.yview); sb.pack(side="right", fill="y")
        txt.config(yscrollcommand=sb.set)
        self._track(txt, "text")

        txt.insert("end", HELP_TEXT)
        txt.config(state="disabled")   # 唯讀

    def set_adv_status(self, text):
        try:
            self.adv_status.config(text=text)
        except Exception:
            pass

    def _run_bg(self, fn, done_msg_prefix=""):
        def work():
            try:
                ok, msg = fn()
                txt = f"{done_msg_prefix}{msg}"
            except Exception as exc:  # noqa: BLE001
                txt = f"{done_msg_prefix}失敗：{exc}"
            self.root.after(0, lambda: (self.set_adv_status(txt), self.adv_reload()))
        threading.Thread(target=work, daemon=True).start()

    def adv_reload(self):
        if not CC_OK:
            return
        try:
            self.adv_history = cc.load_history()
        except Exception as exc:  # noqa: BLE001
            self.set_adv_status(f"讀取模型清單失敗：{exc}")
            self.adv_history = []
        self.model_list.delete(0, tk.END)
        for m in self.adv_history:
            name = m.get("name", "?")
            ver = m.get("version", "")
            used = m.get("last_used_at", "")
            self.model_list.insert(tk.END, f"{name}   [{ver}]" + (f"   · 最近用 {used}" if used else ""))
        # 目前載入的模型（讀設定檔）+ 語音服務狀態
        def upd():
            try:
                ver, gpt, sov = cc.get_current_config()
                gpt_name = os.path.basename(gpt) if gpt else "—"
                online = "已連線" if port_up(9880) else "未啟動"
                txt = f"目前模型：{ver or '—'} · {gpt_name}   ·   語音服務(9880)：{online}"
            except Exception:
                txt = "目前模型：讀取失敗"
            self.root.after(0, lambda: self.adv_api.config(text=txt))
        threading.Thread(target=upd, daemon=True).start()

    def _selected_model(self):
        sel = self.model_list.curselection()
        if not sel:
            self.set_adv_status("請先在清單選一個模型")
            return None
        return self.adv_history[sel[0]]

    def adv_switch(self):
        m = self._selected_model()
        if not m:
            return
        self.set_adv_status(f"切換為「{m.get('name')}」中…")
        def task():
            from pathlib import Path
            # 容錯解析實際檔案（處理簡繁/正規化差異，例如 納↔纳）
            gpt_rel = os.path.relpath(resolve_existing(os.path.join(ROOT, m["gpt_rel"])), ROOT)
            sov_rel = os.path.relpath(resolve_existing(os.path.join(ROOT, m["sovits_rel"])), ROOT)
            ref_abs = resolve_existing(m["ref_path"])
            cc.validate_model(m["version"], gpt_rel, sov_rel, Path(ref_abs), m["prompt"])
            ok, logs = cc.apply_runtime_model(m["version"], gpt_rel, sov_rel, Path(ref_abs), m["prompt"])
            # 把更正後的路徑寫回歷史，之後就不會再對不到
            if (gpt_rel, sov_rel, ref_abs) != (m["gpt_rel"], m["sovits_rel"], m["ref_path"]):
                recs = cc.load_history()
                for r in recs:
                    if r.get("id") == m.get("id"):
                        r["gpt_rel"], r["sovits_rel"], r["ref_path"] = gpt_rel, sov_rel, ref_abs
                cc.save_history(recs)
            cc.mark_model_used(m)
            return ok, ("已切換並載入：" + m.get("name", "") if ok else "已更新設定；API 尚未熱載入（可重啟語音服務）")
        self._run_bg(task)

    def adv_delete(self):
        m = self._selected_model()
        if not m:
            return
        def task():
            records = [r for r in cc.load_history() if r.get("id") != m.get("id")]
            cc.save_history(records)
            return True, f"已從清單移除「{m.get('name')}」（檔案保留未刪）"
        self._run_bg(task)

    def adv_import(self):
        folder = filedialog.askdirectory(title="選擇聲音模型資料夾（內含 .ckpt 與 .pth）")
        if not folder:
            return
        self.set_adv_status("掃描並匯入中…")
        def task():
            import shutil
            from pathlib import Path
            info = cc.scan_model_folder(Path(folder))
            if not info.get("ok"):
                return False, info.get("error", "掃描失敗")
            ver = info["version"]
            gpt_dir, sovits_dir = cc.ensure_dirs(ver)
            gpt_src = Path(info["gpt_path"]); sov_src = Path(info["sovits_path"])
            gpt_dst = gpt_dir / gpt_src.name
            sov_dst = sovits_dir / sov_src.name
            if not gpt_dst.exists():
                shutil.copy2(gpt_src, gpt_dst)
            if not sov_dst.exists():
                shutil.copy2(sov_src, sov_dst)
            ref_src = info.get("ref_path", "")
            ref_path = Path(ref_src) if ref_src else None
            if ref_path is not None:
                cc.REF_DIR.mkdir(parents=True, exist_ok=True)
                ref_dst = cc.REF_DIR / ref_path.name
                if not ref_dst.exists():
                    shutil.copy2(ref_path, ref_dst)
                ref_path = ref_dst
            gpt_rel = os.path.relpath(gpt_dst, ROOT)
            sov_rel = os.path.relpath(sov_dst, ROOT)
            prompt = info.get("prompt", "")
            cc.update_config(ver, gpt_rel, sov_rel)
            cc.remember_model(info["name"], ver, gpt_rel, sov_rel, ref_path or Path(""), prompt)
            ok, _ = cc.apply_runtime_model(ver, gpt_rel, sov_rel, ref_path or Path(""), prompt)
            return True, f"已匯入「{info['name']}」（{ver}）" + ("，並載入" if ok else "；可重啟語音服務套用")
        self._run_bg(task)

    def adv_test(self):
        m = self._selected_model()
        if not m:
            return
        text = self.test_entry.get().strip() or "你好。"
        self.set_adv_status("合成測試中…")
        def task():
            from pathlib import Path
            lang = "_ZH"
            for tag in ("_ZH", "_EN", "_JA", "_KO"):
                if tag in (m.get("version", "") + m.get("name", "")).upper():
                    lang = tag; break
            ref_abs = resolve_existing(m["ref_path"])
            ok, msg = cc.run_tts_test(text, m.get("prompt", ""), Path(ref_abs), 8, lang)
            if ok:
                cc.play_test_wav()
            return ok, ("已合成並播放" if ok else f"合成失敗：{msg}")
        self._run_bg(task)

    def adv_rename(self):
        m = self._selected_model()
        if not m:
            return
        new = simpledialog.askstring("重新命名模型", "新名稱：", initialvalue=m.get("name", ""), parent=self.root)
        if not new or not new.strip():
            return
        new = new.strip()
        def task():
            records = cc.load_history()
            for r in records:
                if r.get("id") == m.get("id"):
                    r["name"] = new
            cc.save_history(records)
            return True, f"已重新命名為「{new}」"
        self._run_bg(task)

    def adv_stopgen(self):
        self.set_adv_status("中止合成中…")
        self._run_bg(lambda: cc.api_stop_generation())

    def adv_play(self):
        self.set_adv_status("播放測試音檔…")
        self._run_bg(lambda: cc.play_test_wav())

    def adv_stopplay(self):
        self._run_bg(lambda: cc.stop_test_wav_playback())

    def adv_reveal(self):
        self._run_bg(lambda: cc.reveal_test_wav())

    def adv_adb(self):
        if not self.adb:
            self.ensure_adb(manual=True)   # 沒 adb 先自動安裝，裝完會自動轉接
            return
        self.set_adv_status("重新接手機(USB)中…")
        def task():
            ok = False
            try:
                out = subprocess.run([self.adb, "devices"], capture_output=True, text=True, timeout=8, **POPEN_KW).stdout
                serials = [l.split("\t")[0] for l in out.splitlines()[1:] if "\tdevice" in l]
                for s in serials:
                    for pp in ("9880", "9881", "9882"):
                        subprocess.run([self.adb, "-s", s, "reverse", f"tcp:{pp}", f"tcp:{pp}"],
                                       capture_output=True, timeout=8, **POPEN_KW)
                ok = bool(serials)
            except Exception:
                pass
            return ok, ("已轉接 USB（手機可用 127.0.0.1）" if ok else "未偵測到 USB 手機（請接線並開 USB 偵錯）")
        self._run_bg(task)

    def open_training(self):
        """啟動 GPT-SoVITS 官方訓練/推理 WebUI（Gradio），用於自己訓練模型。
        埠 9874；起來後自動開瀏覽器。需要訓練用的官方相依與資料集。"""
        webui = os.path.join(ROOT, "webui.py")
        if not os.path.exists(webui):
            self.set_adv_status("找不到 webui.py（官方訓練介面）")
            return
        if port_up(9874):
            webbrowser.open("http://127.0.0.1:9874")
            self.set_adv_status("官方訓練介面已開啟（http://127.0.0.1:9874）")
            return
        self.set_adv_status("正在啟動官方訓練介面（Gradio），約 10 秒後自動開啟瀏覽器…")
        try:
            subprocess.Popen([PY, "webui.py"], cwd=ROOT,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **POPEN_KW)
        except Exception as exc:  # noqa: BLE001
            self.set_adv_status(f"啟動失敗：{exc}")
            return

        def wait_open():
            for _ in range(30):
                if port_up(9874):
                    webbrowser.open("http://127.0.0.1:9874")
                    self.root.after(0, lambda: self.set_adv_status("官方訓練介面已開啟（http://127.0.0.1:9874）"))
                    return
                import time as _t; _t.sleep(1)
            self.root.after(0, lambda: self.set_adv_status("訓練介面啟動較久，請稍候再按一次"))
        threading.Thread(target=wait_open, daemon=True).start()

    # ── 服務控制 ─────────────────────────────────────────────────
    def start(self, port):
        if port_up(int(port)):
            return
        row = self.rows[port]
        if row["needs"] and not have_voice_models():
            row["status"].config(text="需先匯入聲音模型（進階分頁）", fg=AMBER)
            return
        try:
            p = subprocess.Popen(row["cmd"], cwd=ROOT, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, **POPEN_KW)
            self.procs[port] = p
            self.set_status(f"啟動 {row['name']} …")
        except Exception as exc:  # noqa: BLE001
            row["status"].config(text=f"啟動失敗：{exc}", fg=RED)

    def stop(self, port):
        p = self.procs.pop(port, None)
        if p:
            try:
                p.terminate()
            except Exception:
                pass
        try:
            for pid in pids_on_port(port):
                if IS_WIN:
                    subprocess.run(["taskkill", "/F", "/PID", pid],
                                   capture_output=True, timeout=4, **POPEN_KW)
                else:
                    subprocess.run(["kill", pid], capture_output=True, timeout=4)
        except Exception:
            pass
        name = self.rows[port]["name"] if port in self.rows else f"服務 {port}"
        self.set_status(f"已停止 {name}")

    def toggle(self, port):
        if port_up(int(port)) or port in self.procs:
            self.stop(port)
        else:
            self.start(port)
        self.root.after(400, self.refresh)

    # ── 自動化 ───────────────────────────────────────────────────
    def autostart(self):
        for port, *_ in SERVICES:
            self.start(port)
        if not self.adb:
            self.ensure_adb()          # 沒有 adb → 自動下載安裝
        self.root.after(1500, self.adb_reverse)
        self.root.after(2500, self.maybe_autosync)

    def _has_usb_device(self) -> bool:
        """有「USB」transport（序號不含 . 或 :，即非無線 adb）才算。"""
        if not self.adb:
            return False
        try:
            out = subprocess.run([self.adb, "devices"], capture_output=True, text=True, timeout=6, **POPEN_KW).stdout
            for l in out.splitlines()[1:]:
                if "\tdevice" in l:
                    s = l.split("\t")[0]
                    if "." not in s and ":" not in s:
                        return True
        except Exception:
            pass
        return False

    def maybe_autosync(self):
        # 只在「USB」連接時自動同步（無線 adb 傳 2.7G 太慢，會卡很久）；
        # 沒接 USB 時不自動同步，改由使用者按「🔁 同步聲音模型」。
        if self._has_usb_device():
            self.sync_models()
        else:
            self.set_status("提示：USB 接上時會自動同步聲音模型；目前未接 USB（需要時可按「🔁 同步聲音模型」）")

    def ensure_adb(self, manual=False):
        """確保有 adb；沒有就自動下載 Google platform-tools（背景）。"""
        if self.adb and not manual:
            return
        self.set_status("正在安裝 USB 連線工具（adb）…")
        def work():
            path = find_adb() or install_adb()
            self.adb = path
            msg = "USB 連線工具(adb)已就緒" if path else "USB 工具安裝失敗（請檢查網路）"
            self.root.after(0, lambda: (self.set_status(msg), self.set_adv_status(msg)))
            if path:
                self.adb_reverse()
        threading.Thread(target=work, daemon=True).start()

    def adb_reverse(self):
        if not self.adb:
            return
        def work():
            try:
                out = subprocess.run([self.adb, "devices"], capture_output=True, text=True, timeout=6, **POPEN_KW).stdout
                serials = [l.split("\t")[0] for l in out.splitlines()[1:] if "\tdevice" in l]
                for s in serials:
                    for pp in ("9880", "9881", "9882"):
                        subprocess.run([self.adb, "-s", s, "reverse", f"tcp:{pp}", f"tcp:{pp}"],
                                       capture_output=True, timeout=6, **POPEN_KW)
            except Exception:
                pass
        threading.Thread(target=work, daemon=True).start()

    def sync_models(self):
        script = os.path.join(ROOT, "sync_voice_models.py")
        if not os.path.exists(script):
            return
        self.set_status("同步聲音模型中…（含語音基礎模型，首次較久）")
        def work():
            try:
                env = dict(os.environ)
                if self.adb:
                    env["ADB"] = self.adb     # 讓同步腳本也用到（可能是自動安裝的）adb
                r = subprocess.run([PY, script], capture_output=True, text=True, timeout=14400, env=env, **POPEN_KW)
                tail = [l for l in r.stdout.splitlines() if l.strip()]
                msg = tail[-1] if tail else "同步完成"
            except Exception as exc:  # noqa: BLE001
                msg = f"同步失敗：{exc}"
            self.root.after(0, lambda: self.set_status(msg))
            # 同步後若語音基礎模型已就緒、9880 還沒起來 → 自動啟動語音
            if have_voice_models() and not port_up(9880):
                self.root.after(0, lambda: self.start("9880"))
        threading.Thread(target=work, daemon=True).start()

    # ── 狀態刷新 ─────────────────────────────────────────────────
    def set_status(self, text):
        try:
            self.status.config(text=text)
        except Exception:
            pass

    def refresh(self):
        d = system_is_dark()
        if d != self.dark:
            self.dark = d
            self.apply_theme()
        t = THEMES["dark" if self.dark else "light"]

        for port, row in self.rows.items():
            up = port_up(int(port))
            loading = (not up) and (port in self.procs and self.procs[port].poll() is None)
            if up:
                row["dot"].config(fg=GREEN); row["status"].config(text=f"執行中 · 埠 {port}", fg=GREEN)
                row["btn"].config(text="停止", bg=RED)
            elif loading:
                row["dot"].config(fg=AMBER); row["status"].config(text="載入中…", fg=AMBER)
                row["btn"].config(text="停止", bg=RED)
            else:
                row["dot"].config(fg=t["muted"]); row["status"].config(text=f"未啟動 · 埠 {port}", fg=t["muted"])
                row["btn"].config(text="啟動", bg=GREEN)

        if not self.adb:            # 初次沒抓到 → 之後每次刷新再試（自我修復）
            self.adb = find_adb()
        lan = lan_ip(); ts = tailscale_ip()
        lines = ["有線(USB)：手機填 http://127.0.0.1:9880" + ("  ✅ 已接" if self.adb else "  ⚠️ 未偵測到 adb（請裝 Android platform-tools）")]
        lines.append(f"無線(Wi-Fi)：http://{lan}:9880" if lan else "無線(Wi-Fi)：未連 Wi-Fi")
        lines.append(f"全域網(Tailscale)：http://{ts}:9880" if ts
                     else "全域網(Tailscale，選用)：未連線 → 電腦也要裝、登入同帳號並連線（tailscale.com/download）")
        self.conn_label.config(text="\n".join(lines))
        self.root.after(2500, self.refresh)

    def on_close(self):
        self.set_status("正在停止所有服務…")
        self.root.update_idletasks()
        for port, *_ in SERVICES:
            self.stop(port)
        self.stop("9890")
        self.root.destroy()


def main():
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", 1.4)
    except Exception:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
