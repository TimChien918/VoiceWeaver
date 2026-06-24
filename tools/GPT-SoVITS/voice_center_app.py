#!/usr/bin/env python3
"""VoiceWeaver 語音中心 — Windows / macOS / Linux 原生控制台。"""
from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import webbrowser
import zipfile
import tkinter as tk
from tkinter import ttk, filedialog, simpledialog

# ── 凍結偵測（PyInstaller） ──────────────────────────────────────────────
IS_FROZEN = getattr(sys, "frozen", False)

# exe 本身所在目錄（打包版）或腳本所在目錄（開發版）
if IS_FROZEN:
    EXE_DIR = os.path.dirname(sys.executable)
    # PyInstaller 解壓的暫存目錄（bundled 資源）
    _MEIPASS = getattr(sys, "_MEIPASS", EXE_DIR)
else:
    EXE_DIR = os.path.dirname(os.path.abspath(__file__))
    _MEIPASS = EXE_DIR

# 服務腳本搜尋順序：
#   1. %LOCALAPPDATA%\VoiceWeaver\tools\GPT-SoVITS  (launch.ps1 安裝位置)
#   2. exe 同層目錄 / GPT-SoVITS
#   3. exe 同層目錄（開發者直接執行）
_SERVICE_ROOTS = [
    os.path.join(os.environ.get("LOCALAPPDATA", ""), "VoiceWeaver", "tools", "GPT-SoVITS"),
    os.path.join(EXE_DIR, "GPT-SoVITS"),
    EXE_DIR,
    _MEIPASS,
]

def _find_service_root() -> str:
    """找第一個有 api_v2.py 的目錄。"""
    for d in _SERVICE_ROOTS:
        if d and os.path.isfile(os.path.join(d, "api_v2.py")):
            return d
    return EXE_DIR   # fallback（服務會啟動失敗，但 UI 不崩潰）

ROOT = _find_service_root()

# ── 首次安裝位置（與 tools/win/launch.ps1 一致） ──────────────────────────
APPSUP    = os.path.join(os.environ.get("LOCALAPPDATA", ""), "VoiceWeaver")
TOOLS_DIR = os.path.join(APPSUP, "tools")
CONDA_DIR = os.path.join(TOOLS_DIR, "miniforge3")
ENV_DIR   = os.path.join(TOOLS_DIR, "gpt-sovits-env")
ENV_PY    = os.path.join(ENV_DIR, "python.exe")
ENV_PYW   = os.path.join(ENV_DIR, "pythonw.exe")
GS_DIR    = os.path.join(TOOLS_DIR, "GPT-SoVITS")
READY_MARK = os.path.join(APPSUP, ".ready")
CONTROL_VER = "3"

# GPT-SoVITS 推論引擎程式碼來源（官方上游，使用者已授權）
GPTSOVITS_CODE_URL = "https://codeload.github.com/RVC-Boss/GPT-SoVITS/zip/refs/heads/main"
# Miniforge（官方 conda-forge 發行版，內含 Python）
_MF_VER   = "24.11.3-2"
MF_URL    = f"https://github.com/conda-forge/miniforge/releases/download/{_MF_VER}/Miniforge3-{_MF_VER}-Windows-x86_64.exe"
# 可選：預打包環境（conda-pack 產物）的下載網址；設了就走「下載既成環境」快速路徑
ENV_TAR_URL = os.environ.get("VW_ENV_URL", "")
PY_VERSION = "3.10"

def env_installed() -> bool:
    """AI 環境與後端程式碼皆就緒。"""
    return (os.path.isfile(ENV_PY)
            and os.path.isfile(os.path.join(GS_DIR, "api_v2.py")))

# ── 跨平台 ──────────────────────────────────────────────────────────────
IS_WIN = (os.name == "nt")
IS_MAC = (sys.platform == "darwin")
ADB_BIN = "adb.exe" if IS_WIN else "adb"
POPEN_KW = {"creationflags": 0x08000000} if IS_WIN else {}

_PT_PLATFORM = "windows" if IS_WIN else ("darwin" if IS_MAC else "linux")
PLATFORM_TOOLS_URL = f"https://dl.google.com/android/repository/platform-tools-latest-{_PT_PLATFORM}.zip"
LOCAL_PT_DIR = os.path.join(os.path.dirname(ROOT), "platform-tools")
LOCAL_ADB = os.path.join(LOCAL_PT_DIR, ADB_BIN)

os.environ.setdefault("HF_HOME", os.path.join(ROOT, ".cache", "huggingface"))
os.environ.setdefault("MPLCONFIGDIR", os.path.join(ROOT, ".cache", "matplotlib"))
os.makedirs(os.environ["HF_HOME"], exist_ok=True)
os.makedirs(os.environ["MPLCONFIGDIR"], exist_ok=True)

# Windows 主控台預設 cp950（Big5）：子程序 print 含 ↔ → ⚠ 等字元會 UnicodeEncodeError 崩潰。
# 強制所有衍生的 Python 子程序（服務、模型同步、pip）都用 UTF-8 stdio。
os.environ["PYTHONUTF8"] = "1"
os.environ["PYTHONIOENCODING"] = "utf-8"

try:
    sys.path.insert(0, ROOT)
    sys.path.insert(0, _MEIPASS)
    # 告訴後端模組真正的安裝根（凍結版時 __file__ 在暫存目錄，會找錯 user_models/設定）
    os.environ["VOICEWEAVER_ROOT"] = ROOT
    import model_setup_server as cc
    CC_OK = True
except Exception as _e:
    cc = None
    CC_OK = False
    _CC_ERR = str(_e)

VOICE_CFG = "GPT_SoVITS/configs/voiceweaver_tts_infer.yaml"

# Python 直譯器（非凍結版直接用 sys.executable；凍結版啟動時背景搜尋）
_PY_LOCK = threading.Lock()
_PY_PATH: str = "" if IS_FROZEN else sys.executable

def _find_python_bg(callback=None):
    """背景執行緒搜尋系統 Python，找到後呼叫 callback(path)。"""
    def _work():
        global _PY_PATH
        for name in ("python3.12", "python3.11", "python3.10", "python3", "python"):
            p = shutil.which(name)
            if not p:
                continue
            try:
                r = subprocess.run([p, "--version"], capture_output=True, timeout=4, **POPEN_KW)
                if r.returncode == 0:
                    with _PY_LOCK:
                        _PY_PATH = p
                    if callback:
                        callback(p)
                    return
            except Exception:
                pass
        if callback:
            callback("")
    threading.Thread(target=_work, daemon=True).start()

def get_py() -> str:
    with _PY_LOCK:
        return _PY_PATH


# ── 首次安裝引導（全自動，從公開來源） ───────────────────────────────────
_INSTALL_LOG = os.path.join(APPSUP, "logs", "install.log")

def _ilog(msg: str):
    try:
        os.makedirs(os.path.dirname(_INSTALL_LOG), exist_ok=True)
        with open(_INSTALL_LOG, "a", encoding="utf-8") as f:
            import time as _t
            f.write(f"[{_t.strftime('%H:%M:%S')}] {msg}\n")
    except Exception:
        pass

def _download(url: str, dest: str, progress, label: str, base: float, span: float):
    """下載並回報進度（progress(frac, sub_msg)）。"""
    import urllib.request
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest):
        try: os.remove(dest)
        except OSError: pass
    _ilog(f"download {label}: {url}")
    def hook(blocknum, bs, total):
        if total and total > 0:
            frac = min(1.0, blocknum * bs / total)
            mb = total / (1024 * 1024)
            progress(base + span * frac, f"{label}  {int(frac*100)}%  ({mb:.0f} MB)")
    urllib.request.urlretrieve(url, dest, hook)

def _cuda_wheel_index() -> tuple[str, bool]:
    """偵測「現代 PyTorch 支援的」NVIDIA GPU。
    回 (pip index url, is_cuda)。需 compute capability >= 5.0（Maxwell 以後）——
    更舊的卡（如 Kepler GTX 7xx）新版 PyTorch CUDA 輪子不支援，會自動退回 CPU。"""
    try:
        r = subprocess.run(
            ["nvidia-smi", "--query-gpu=compute_cap", "--format=csv,noheader"],
            capture_output=True, text=True, timeout=8, **POPEN_KW)
        if r.returncode == 0:
            caps = []
            for line in (r.stdout or "").splitlines():
                line = line.strip()
                try: caps.append(float(line))
                except ValueError: pass
            if caps and max(caps) >= 5.0:
                return "https://download.pytorch.org/whl/cu121", True
    except Exception:
        pass
    return "https://download.pytorch.org/whl/cpu", False

def _run(cmd, progress=None, sub=""):
    """執行子程序，串流輸出到進度列（容錯，不丟例外給呼叫端判斷）。"""
    _ilog("run: " + " ".join(str(c) for c in cmd))
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, encoding="utf-8", errors="replace", **POPEN_KW)
    for line in p.stdout:
        line = line.rstrip()
        if line:
            _ilog("  " + line)
            if progress and sub:
                progress(-1, f"{sub}：{line[:60]}")
    p.wait()
    return p.returncode

def run_bootstrap(progress):
    """首次安裝：Miniforge → 建環境 → pip 依賴 → 抓後端程式碼。全程公開來源。
    progress(frac, msg)：frac<0 表示維持目前進度（不確定步驟）。"""
    os.makedirs(TOOLS_DIR, exist_ok=True)
    _ilog("===== bootstrap start =====")

    if not os.path.isfile(ENV_PY):
        # 快速路徑：預打包環境（若提供網址）
        if ENV_TAR_URL:
            tar = os.path.join(TOOLS_DIR, "gpt-sovits-env.tar.gz")
            _download(ENV_TAR_URL, tar, progress, "下載 AI 環境", 0.05, 0.45)
            progress(0.55, "解壓 AI 環境…")
            if os.path.isdir(ENV_DIR):
                shutil.rmtree(ENV_DIR, ignore_errors=True)
            os.makedirs(ENV_DIR, exist_ok=True)
            import tarfile
            with tarfile.open(tar, "r:gz") as t:
                t.extractall(ENV_DIR)
            unpack = os.path.join(ENV_DIR, "Scripts", "conda-unpack.exe")
            if os.path.isfile(unpack):
                progress(0.7, "環境重定位（conda-unpack）…")
                _run([unpack], progress, "重定位")
        else:
            # 自建：Miniforge → conda 建環境 → pip
            conda_bat = os.path.join(CONDA_DIR, "condabin", "conda.bat")
            if not os.path.isfile(conda_bat):
                mf = os.path.join(os.environ.get("TEMP", TOOLS_DIR), "Miniforge3-setup.exe")
                _download(MF_URL, mf, progress, "下載 Miniforge", 0.02, 0.18)
                progress(0.2, "安裝 Miniforge（靜默，約 1 分鐘）…")
                # /S 為非同步安裝
                subprocess.Popen([mf, "/InstallationType=JustMe", "/AddToPath=0",
                                  "/RegisterPython=0", "/S", f"/D={CONDA_DIR}"], **POPEN_KW)
                import time as _t
                for _ in range(150):
                    if os.path.isfile(conda_bat):
                        break
                    _t.sleep(2)
            if not os.path.isfile(conda_bat):
                raise RuntimeError("Miniforge 安裝失敗")

            progress(0.35, f"建立 Python {PY_VERSION} 環境…")
            rc = _run([conda_bat, "create", "-y", "-p", ENV_DIR, f"python={PY_VERSION}"],
                      progress, "建立環境")
            if not os.path.isfile(ENV_PY):
                raise RuntimeError(f"建立環境失敗（rc={rc}）")

            _run([ENV_PY, "-m", "pip", "install", "--upgrade", "pip"], progress, "pip")

            # ── 核心依賴：torch/diffusers/transformers 用「互相相容」的釘選版本 ──
            #    torch 2.4.1：避開新版 torchaudio 強制 torchcodec(需 FFmpeg DLL) 的問題；
            #    diffusers 0.31：相容 torch 2.4；transformers<=4.50：GPT-SoVITS(peft) 需求。
            #    偵測到支援的 NVIDIA GPU 就裝 CUDA 版 torch（約 2.5GB），否則 CPU 版。
            torch_index, is_cuda = _cuda_wheel_index()
            if is_cuda:
                progress(0.45, "偵測到 NVIDIA GPU，安裝 CUDA 版 AI 依賴（約 2.5GB）…")
                _ilog("偵測到支援的 NVIDIA GPU → 安裝 CUDA(cu121) 版 torch")
            else:
                progress(0.45, "安裝核心 AI 依賴（CPU 版 torch / diffusers / transformers …）")
            core_groups = [
                ["--index-url", torch_index, "torch==2.4.1", "torchaudio==2.4.1"],
                ["numpy<2", "Pillow", "safetensors"],
                ["diffusers==0.31.0", "transformers>=4.43,<=4.50", "accelerate"],
                ["fastapi", "uvicorn"],
            ]
            for g in core_groups:
                _run([ENV_PY, "-m", "pip", "install"] + g, progress, "核心依賴")
            chk = _run([ENV_PY, "-c",
                        "import torch, diffusers, transformers, fastapi, PIL"], None)
            if chk != 0:
                raise RuntimeError("核心 AI 依賴安裝失敗（torch / diffusers / transformers）。"
                                   "請確認網路後重試，或查看安裝紀錄。")

            # ── 語音額外依賴（GPT-SoVITS 9880 用，逐項安裝、失敗不中止）──
            progress(0.62, "安裝語音合成元件…")
            voice_pkgs = [
                "scipy", "librosa==0.10.2", "soundfile", "numba", "gradio<5", "einops",
                "ffmpeg-python", "onnxruntime", "tqdm", "funasr", "cn2an", "pypinyin",
                "g2p_en", "modelscope", "sentencepiece", "peft<0.18.0", "chardet",
                "PyYAML", "psutil", "jieba", "split-lang", "fast_langdetect",
                "wordsegment", "rotary_embedding_torch", "ToJyutping", "opencc",
                "x_transformers", "torchmetrics<=1.5", "pydantic<=2.10.6",
                "ctranslate2>=4.0,<5", "av>=11", "pytorch-lightning",
            ]
            for pkg in voice_pkgs:
                _run([ENV_PY, "-m", "pip", "install", pkg], progress, "語音元件")
            # pyopenjtalk：日語前端用，Windows 無預編譯輪子需 C++ 編譯——盡力而為，失敗略過
            progress(0.80, "安裝日語元件（可選，失敗會自動略過）…")
            if _run([ENV_PY, "-m", "pip", "install", "pyopenjtalk"], progress, "日語元件") != 0:
                _ilog("pyopenjtalk 安裝失敗（需 C++ 編譯器）——已略過，不影響生圖/文字/中文語音。")
            # jieba_fast：中文前端直接 import 它，但 Windows 無預編譯輪子 → 用 jieba 做相容 shim
            _install_jieba_fast_shim()

    # 後端程式碼：自帶在安裝包/exe 內（私有 repo，不從網路取），複製到安裝位置
    if not os.path.isfile(os.path.join(GS_DIR, "image_gen_server.py")) or _read_mark() != CONTROL_VER:
        progress(0.92, "部署後端程式碼…")
        src_backend = os.path.join(_MEIPASS, "backend")
        if not os.path.isdir(src_backend):
            raise RuntimeError("找不到內建後端程式碼（backend/）")
        os.makedirs(GS_DIR, exist_ok=True)
        _merge_tree(src_backend, GS_DIR, skip_dirs={"__pycache__"})
        # Windows 版後端程式庫（在 _MEIPASS 根目錄）
        for fn in ("model_setup_server.py",):
            b = os.path.join(_MEIPASS, fn)
            if os.path.isfile(b):
                shutil.copy2(b, os.path.join(GS_DIR, fn))

    # ── GPT-SoVITS 推論引擎程式碼（語音 9880 用；使用者授權自官方上游 RVC-Boss）──
    #    私有 repo 只有薄工具層，引擎本體(TTS_infer_pack/text/tools)取自官方上游。
    if not os.path.isfile(os.path.join(GS_DIR, "GPT_SoVITS", "TTS_infer_pack", "TTS.py")):
        try:
            _provision_gptsovits_engine(progress)
        except Exception as exc:
            _ilog(f"語音引擎部署失敗（不影響生圖/文字）：{exc}")

    _write_mark(CONTROL_VER)
    progress(1.0, "安裝完成")
    _ilog("===== bootstrap done =====")

def _install_jieba_fast_shim() -> None:
    """中文前端 import jieba_fast，但 Windows 無預編譯輪子。用純 Python jieba 做相容 shim。"""
    try:
        out = subprocess.run([ENV_PY, "-c",
            "import site,sys; sys.stdout.write(site.getsitepackages()[-1])"],
            capture_output=True, text=True, encoding="utf-8", **POPEN_KW)
        sp = (out.stdout or "").strip()
        if not sp or not os.path.isdir(sp):
            sp = os.path.join(ENV_DIR, "Lib", "site-packages")
        pkg = os.path.join(sp, "jieba_fast")
        os.makedirs(pkg, exist_ok=True)
        with open(os.path.join(pkg, "__init__.py"), "w", encoding="utf-8") as f:
            f.write("import jieba as _j\nfrom jieba import *\n"
                    "globals().update({k: getattr(_j, k) for k in dir(_j) "
                    "if not k.startswith('__')})\n")
        with open(os.path.join(pkg, "posseg.py"), "w", encoding="utf-8") as f:
            f.write("from jieba.posseg import *\nimport jieba.posseg as _p\n"
                    "globals().update({k: getattr(_p, k) for k in dir(_p) "
                    "if not k.startswith('__')})\n")
        _ilog("jieba_fast shim 已建立")
    except Exception as exc:
        _ilog(f"建立 jieba_fast shim 失敗：{exc}")

def _provision_gptsovits_engine(progress) -> None:
    progress(0.94, "部署語音引擎程式碼…")
    zip_path = os.path.join(os.environ.get("TEMP", TOOLS_DIR), "gptsovits_src.zip")
    _download(GPTSOVITS_CODE_URL, zip_path, progress, "下載語音引擎", 0.94, 0.04)
    tmp = os.path.join(os.environ.get("TEMP", TOOLS_DIR), "gptsovits_src")
    if os.path.isdir(tmp):
        shutil.rmtree(tmp, ignore_errors=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(tmp)
    top = next((os.path.join(tmp, d) for d in os.listdir(tmp)
                if os.path.isdir(os.path.join(tmp, d))), None)
    if not top:
        raise RuntimeError("語音引擎壓縮檔結構非預期")
    # 部署引擎程式碼，保留使用者同步來的 configs / pretrained_models
    _merge_tree(os.path.join(top, "GPT_SoVITS"),
                os.path.join(GS_DIR, "GPT_SoVITS"),
                skip_dirs={"configs", "pretrained_models", "__pycache__"})
    _merge_tree(os.path.join(top, "tools"), os.path.join(GS_DIR, "tools"),
                skip_dirs={"__pycache__"})
    shutil.rmtree(tmp, ignore_errors=True)
    try: os.remove(zip_path)
    except OSError: pass
    _ilog("語音引擎程式碼已部署")

def _merge_tree(src: str, dst: str, skip_dirs: set):
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        rel = os.path.relpath(root, src)
        target = dst if rel == "." else os.path.join(dst, rel)
        os.makedirs(target, exist_ok=True)
        for f in files:
            try: shutil.copy2(os.path.join(root, f), os.path.join(target, f))
            except Exception: pass

def _read_mark() -> str:
    try:
        with open(READY_MARK, encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""

def _write_mark(v: str):
    try:
        os.makedirs(APPSUP, exist_ok=True)
        with open(READY_MARK, "w", encoding="utf-8") as f:
            f.write(v)
    except Exception:
        pass


# 預設 0.0.0.0（手機 Wi-Fi 需要）；只用 USB(adb reverse) 的人可設 VOICEWEAVER_BIND=127.0.0.1 鎖本機。
_BIND = os.environ.get("VOICEWEAVER_BIND", "0.0.0.0")

SERVICES = [
    ("9880", "語音合成 (GPT-SoVITS)",  "api_v2.py",           ["-a", _BIND, "-p", "9880", "-c", VOICE_CFG], True),
    ("9881", "本機生圖 (SD-Turbo)",     "image_gen_server.py", [], False),
    ("9882", "文字推論 (Qwen)",          "text_infer_server.py",[], False),
]

# ── 視覺設計 ─────────────────────────────────────────────────────────────
FONT_UI   = "Microsoft JhengHei UI" if IS_WIN else "PingFang TC"
FONT_MONO = "Consolas" if IS_WIN else "Menlo"
SZ_H1, SZ_H2, SZ_BODY, SZ_SMALL = 18, 13, 12, 10

THEMES = {
    "dark":  dict(bg="#1a1a2e", panel="#16213e", card="#0f3460",
                  fg="#e0e0e0", muted="#7a7a9a", border="#2a2a4a",
                  listbg="#16213e", listsel="#533483"),
    "light": dict(bg="#f0f4ff", panel="#ffffff", card="#e8eeff",
                  fg="#1a1a3e", muted="#6666aa", border="#c8d0f0",
                  listbg="#ffffff", listsel="#6c63ff"),
}
C_GREEN  = "#00c96e"
C_RED    = "#ff4757"
C_BLUE   = "#6c63ff"
C_AMBER  = "#ffa502"
C_WHITE  = "#ffffff"
C_DIMMED = "#555577"

HELP_TEXT = """使用說明

1. 開啟本 App，三個服務會自動啟動，綠燈＝就緒。

2. 手機連線（「控制台」分頁會顯示實際位址，擇一）：
   • USB：手機填 http://127.0.0.1:9880
   • Wi-Fi：填電腦 IP，例 http://192.168.x.x:9880
   • 外網：裝 Tailscale 後填 100.x 位址（tailscale.com/download）

3. 換聲音：到「模型 / 進階」分頁選一個按「切換」；
   沒有模型就按「從資料夾匯入」。

4. 用完直接關閉視窗 → 自動停止所有服務。

注意：GPT-SoVITS 環境需另外安裝（約 2GB），
      未安裝時語音合成服務無法啟動。
"""

# ── 單一實例（Windows Mutex） ────────────────────────────────────────────
_WIN_MUTEX = None

def _acquire_single_instance() -> bool:
    if not IS_WIN:
        return True
    import ctypes
    global _WIN_MUTEX
    _WIN_MUTEX = ctypes.windll.kernel32.CreateMutexW(None, False, "VoiceWeaverVoiceCenterMutex_v1")
    if ctypes.windll.kernel32.GetLastError() == 183:   # ERROR_ALREADY_EXISTS
        try:
            hwnd = ctypes.windll.user32.FindWindowW(None, "VoiceWeaver 語音中心")
            if hwnd:
                ctypes.windll.user32.ShowWindow(hwnd, 9)
                ctypes.windll.user32.SetForegroundWindow(hwnd)
        except Exception:
            pass
        return False
    return True

# ── 工具函式 ─────────────────────────────────────────────────────────────
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
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0

def pids_on_port(port) -> list[str]:
    port = str(port)
    try:
        if IS_WIN:
            out = subprocess.run(["netstat", "-ano", "-p", "TCP"],
                                 capture_output=True, text=True, timeout=5, **POPEN_KW).stdout
            pids = []
            for line in out.splitlines():
                parts = line.split()
                if (len(parts) >= 5 and parts[0].upper() == "TCP"
                        and parts[1].endswith(":" + port) and parts[3].upper() == "LISTENING"
                        and parts[-1].isdigit() and parts[-1] != "0"):
                    pids.append(parts[-1])
            return list(dict.fromkeys(pids))
        out = subprocess.run(["lsof", "-tiTCP:" + port, "-sTCP:LISTEN"],
                             capture_output=True, text=True, timeout=4).stdout
        return out.split()
    except Exception:
        return []

def have_voice_models() -> bool:
    d = os.path.join(ROOT, "GPT_SoVITS", "pretrained_models")
    return os.path.isdir(d) and bool(os.listdir(d))

def find_adb_bg(callback=None):
    """背景執行緒搜尋 adb，找到後呼叫 callback(path)。"""
    def _work():
        cands = (
            [LOCAL_ADB,
             os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
             r"C:\platform-tools\adb.exe"]
            if IS_WIN else
            [LOCAL_ADB,
             os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
             "/opt/homebrew/bin/adb", "/usr/local/bin/adb"]
        )
        for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            if os.environ.get(env):
                cands.insert(0, os.path.join(os.environ[env], "platform-tools", ADB_BIN))
        cands.append(ADB_BIN)
        for c in cands:
            try:
                c = os.path.expanduser(c)
                if subprocess.run([c, "version"], capture_output=True, timeout=4, **POPEN_KW).returncode == 0:
                    if callback:
                        callback(c)
                    return
            except Exception:
                pass
        if callback:
            callback("")
    threading.Thread(target=_work, daemon=True).start()

def install_adb_bg(callback=None):
    def _work():
        try:
            parent = os.path.dirname(LOCAL_PT_DIR)
            os.makedirs(parent, exist_ok=True)
            zpath = LOCAL_PT_DIR + ".zip"
            ok = False
            try:
                r = subprocess.run(["curl", "-fsSL", "-o", zpath, PLATFORM_TOOLS_URL],
                                   timeout=300, **POPEN_KW)
                ok = r.returncode == 0 and os.path.exists(zpath)
            except Exception:
                ok = False
            if not ok:
                import urllib.request
                urllib.request.urlretrieve(PLATFORM_TOOLS_URL, zpath)
                ok = os.path.exists(zpath)
            if ok:
                with zipfile.ZipFile(zpath) as z:
                    z.extractall(parent)
                try: os.remove(zpath)
                except OSError: pass
                if os.path.exists(LOCAL_ADB):
                    if not IS_WIN:
                        os.chmod(LOCAL_ADB, 0o755)
                    if subprocess.run([LOCAL_ADB, "version"], capture_output=True, timeout=8, **POPEN_KW).returncode == 0:
                        if callback: callback(LOCAL_ADB); return
        except Exception:
            pass
        if callback: callback("")
    threading.Thread(target=_work, daemon=True).start()

def lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80)); ip = s.getsockname()[0]
        finally:
            s.close()
        if ip and not ip.startswith("127."): return ip
    except Exception: pass
    return ""

def tailscale_ip() -> str:
    cli = (["tailscale.exe", os.path.expandvars(r"%ProgramFiles%\Tailscale\tailscale.exe")]
           if IS_WIN else
           ["/Applications/Tailscale.app/Contents/MacOS/Tailscale",
            "/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale", "tailscale"])
    for c in cli:
        try:
            r = subprocess.run([c, "ip", "-4"], capture_output=True, text=True, timeout=3, **POPEN_KW)
            lines = [l.strip() for l in r.stdout.splitlines() if l.strip()]
            if r.returncode == 0 and lines: return lines[0]
        except Exception: pass
    try:
        cmd = ["ipconfig"] if IS_WIN else ["ifconfig"]
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=3, **POPEN_KW).stdout
        for m in re.finditer(r"(100\.\d+\.\d+\.\d+)", out):
            ip = m.group(1)
            if 64 <= int(ip.split(".")[1]) <= 127: return ip
    except Exception: pass
    return ""

def resolve_existing(path: str) -> str:
    import unicodedata
    if not path or os.path.exists(path): return path
    p = path.replace("\\", "/")
    for marker in ("/ref_audio/", "/user_models/", "/SoVITS_weights_", "/GPT_weights_", "/GPT_SoVITS/"):
        idx = p.find(marker)
        if idx != -1:
            cand = os.path.join(ROOT, p[idx + 1:])
            if os.path.exists(cand): return cand
            path = cand; break
    if os.path.exists(path): return path
    d, base = os.path.dirname(path), os.path.basename(path)
    if not os.path.isdir(d): return path
    for norm in ("NFC", "NFD"):
        cand = os.path.join(d, unicodedata.normalize(norm, base))
        if os.path.exists(cand): return cand
    try:
        files = os.listdir(d)
        tb = unicodedata.normalize("NFC", base)
        for f in files:
            if unicodedata.normalize("NFC", f) == tb: return os.path.join(d, f)
    except OSError: pass
    return path


# ── 主 App ───────────────────────────────────────────────────────────────
class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.procs: dict[str, subprocess.Popen] = {}
        self.rows: dict[str, dict] = {}
        self.adb: str = ""
        self.dark = system_is_dark()
        self.themed: list[tuple] = []
        self.adv_history: list[dict] = []

        root.title("VoiceWeaver 語音中心")
        root.geometry("660x720")
        root.minsize(580, 620)
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self._track(root, "bg")

        style = ttk.Style()
        style.theme_use("clam")

        nb_frame = tk.Frame(root); nb_frame.pack(fill="both", expand=True)
        self._track(nb_frame, "bg")
        self.nb = ttk.Notebook(nb_frame)
        self.nb.pack(fill="both", expand=True, padx=10, pady=(8, 10))

        self.tab_main = tk.Frame(self.nb); self._track(self.tab_main, "bg")
        self.tab_adv  = tk.Frame(self.nb); self._track(self.tab_adv,  "bg")
        self.tab_help = tk.Frame(self.nb); self._track(self.tab_help, "bg")
        self.nb.add(self.tab_main, text="   控制台   ")
        self.nb.add(self.tab_adv,  text="   模型 / 進階   ")
        self.nb.add(self.tab_help, text="   說明   ")

        self._build_main(self.tab_main)
        self._build_adv(self.tab_adv)
        self._build_help(self.tab_help)

        self.apply_theme()

        # 背景初始化（不阻塞 UI）
        self.root.after(100, self._bg_init)
        self.refresh()

    # ── 背景初始化 ────────────────────────────────────────────────
    def _bg_init(self):
        """UI 立刻可用；首次（凍結版且未安裝）則進入自動安裝流程。"""
        if IS_FROZEN and not env_installed():
            self._begin_first_install()
            return
        if IS_FROZEN:
            self._activate_install()   # 指向已安裝的 AI 環境
            self._ensure_engine_bg()   # 語音引擎若上次安裝缺漏，背景補齊（不擋生圖/文字）
        self.set_status("初始化中…")
        self._start_all_services()
        find_adb_bg(callback=self._on_adb_found)

    def _ensure_engine_bg(self):
        """已安裝但語音引擎程式碼缺漏時（首次安裝該步驟失敗）背景補部署，之後啟動語音。"""
        engine = os.path.join(GS_DIR, "GPT_SoVITS", "TTS_infer_pack", "TTS.py")
        if os.path.isfile(engine):
            return
        def work():
            try:
                self.root.after(0, lambda: self.set_status("背景補裝語音引擎…"))
                _provision_gptsovits_engine(lambda f, m: None)
                _install_jieba_fast_shim()
                self.root.after(0, lambda: self.set_status("語音引擎已就緒"))
                if have_voice_models() and not port_up(9880):
                    self.root.after(0, lambda: self.start("9880"))
            except Exception as exc:
                _ilog(f"背景補裝語音引擎失敗：{exc}")
        threading.Thread(target=work, daemon=True).start()

    def _activate_install(self):
        """把全域 ROOT / Python 指向首次安裝好的環境與後端程式碼。"""
        global ROOT, _PY_PATH
        ROOT = GS_DIR
        with _PY_LOCK:
            _PY_PATH = ENV_PY
        os.environ["VOICEWEAVER_ROOT"] = GS_DIR
        if CC_OK:
            try: cc.set_root(GS_DIR)
            except Exception: pass

    def _on_adb_found(self, path: str):
        self.adb = path
        if path:
            self.root.after(500, self._adb_reverse_bg)
            self.root.after(1500, self._maybe_autosync)

    def _start_all_services(self):
        self._detect_device_bg()
        for port, name, script, args, needs in SERVICES:
            self.start(port)
        self.root.after(500, self._maybe_autosync)

    def _detect_device_bg(self):
        """背景偵測安裝好的環境是否能用 CUDA；可用就讓語音設定改寫成 GPU。"""
        py = get_py()
        if not py or not os.path.isfile(py):
            return
        def work():
            try:
                r = subprocess.run(
                    [py, "-c", "import torch;print('cuda' if torch.cuda.is_available() else 'cpu')"],
                    capture_output=True, text=True, timeout=30, **POPEN_KW)
                if (r.stdout or "").strip() == "cuda":
                    os.environ["VOICEWEAVER_DEVICE"] = "cuda"   # 行程共用，cc 也讀得到
                    self.root.after(0, lambda: self.set_status("已啟用 GPU 加速（NVIDIA CUDA）"))
            except Exception:
                pass
        threading.Thread(target=work, daemon=True).start()

    # ── 首次安裝畫面 ──────────────────────────────────────────────
    def _begin_first_install(self):
        self._show_install_view()
        self._last_prog_t = 0.0
        def work():
            try:
                run_bootstrap(self._install_progress)
                self._activate_install()
                self.root.after(0, self._finish_install)
            except Exception as exc:
                import traceback
                _ilog("bootstrap exception:\n" + traceback.format_exc())
                emsg = str(exc).strip() or exc.__class__.__name__
                self.root.after(0, lambda m=emsg: self._install_failed(m))
        threading.Thread(target=work, daemon=True).start()

    def _show_install_view(self):
        t = self.theme()
        self.iv = tk.Frame(self.root, bg=t["bg"])
        self.iv.place(relx=0, rely=0, relwidth=1, relheight=1)
        tk.Label(self.iv, text="VoiceWeaver 首次安裝", font=(FONT_UI, SZ_H1, "bold"),
                 bg=t["bg"], fg=t["fg"]).pack(pady=(110, 8))
        tk.Label(self.iv, text="正在自動下載並安裝本機 AI 環境（約 2GB）——只需這一次。",
                 font=(FONT_UI, SZ_BODY), bg=t["bg"], fg=t["muted"]).pack(pady=(0, 26))
        self.iv_bar = ttk.Progressbar(self.iv, length=440, mode="indeterminate")
        self.iv_bar.pack(pady=(0, 12)); self.iv_bar.start(14)
        self.iv_sub = tk.Label(self.iv, text="準備中…", font=(FONT_MONO, SZ_SMALL),
                               bg=t["bg"], fg=t["muted"])
        self.iv_sub.pack()
        self.iv_hint = tk.Label(
            self.iv, text="完成後會自動開啟控制台，安裝期間請保持網路連線。",
            font=(FONT_UI, SZ_SMALL), bg=t["bg"], fg=C_DIMMED)
        self.iv_hint.pack(pady=(30, 0))

    def _install_progress(self, frac, msg):
        import time as _t
        # 不確定步驟（frac<0，例如逐行 pip 輸出）節流，避免洪水更新
        now = _t.time()
        if (frac is None or frac < 0) and (now - getattr(self, "_last_prog_t", 0)) < 0.2:
            return
        self._last_prog_t = now
        def upd():
            try:
                if frac is not None and frac >= 0:
                    self.iv_bar.stop()
                    self.iv_bar.config(mode="determinate")
                    self.iv_bar["value"] = max(0, min(100, frac * 100))
                self.iv_sub.config(text=msg)
            except Exception:
                pass
        self.root.after(0, upd)

    def _finish_install(self):
        try: self.iv.destroy()
        except Exception: pass
        self.set_status("安裝完成，啟動服務中…")
        self._start_all_services()
        find_adb_bg(callback=self._on_adb_found)

    def _install_failed(self, err: str):
        try:
            self.iv_bar.stop()
            self.iv_bar.config(mode="determinate"); self.iv_bar["value"] = 0
            self.iv_sub.config(text=f"安裝未完成：{err}", fg=C_RED)
            self.iv_hint.config(text=f"詳細紀錄：{_INSTALL_LOG}")
            bar = tk.Frame(self.iv, bg=self.theme()["bg"]); bar.pack(pady=(20, 0))
            self._btn(bar, "重試安裝", self._retry_install, C_BLUE).pack(side="left", padx=6)
            self._neutral_btn(bar, "開啟安裝紀錄",
                              lambda: os.startfile(_INSTALL_LOG)  # noqa
                              if os.path.exists(_INSTALL_LOG) else None).pack(side="left", padx=6)
        except Exception:
            pass

    def _retry_install(self):
        try: self.iv.destroy()
        except Exception: pass
        self._begin_first_install()

    # ── 主題 ─────────────────────────────────────────────────────
    def _track(self, w, role):
        self.themed.append((w, role))

    def theme(self) -> dict:
        return THEMES["dark" if self.dark else "light"]

    def apply_theme(self):
        t = self.theme()
        style = ttk.Style()
        nb_bg = t["panel"]
        style.configure("TNotebook", background=t["bg"], borderwidth=0)
        style.configure("TNotebook.Tab", background=t["panel"], foreground=t["muted"],
                        font=(FONT_UI, SZ_BODY), padding=[14, 6])
        style.map("TNotebook.Tab",
                  background=[("selected", t["bg"]), ("active", t["card"])],
                  foreground=[("selected", t["fg"]), ("active", t["fg"])])
        style.configure("TScrollbar", background=t["panel"], troughcolor=t["bg"], borderwidth=0)

        for w, role in self.themed:
            try:
                cfg = {}
                if   role == "bg":       cfg = {"bg": t["bg"]}
                elif role == "panel":    cfg = {"bg": t["panel"]}
                elif role == "card":     cfg = {"bg": t["card"]}
                elif role == "fg":       cfg = {"bg": w.master.cget("bg"), "fg": t["fg"]}
                elif role == "muted":    cfg = {"bg": w.master.cget("bg"), "fg": t["muted"]}
                elif role == "neutralbtn": cfg = {"bg": t["panel"], "fg": t["fg"]}
                elif role == "list":     cfg = {"bg": t["listbg"], "fg": t["fg"],
                                                "selectbackground": t["listsel"], "selectforeground": C_WHITE}
                elif role == "entry":    cfg = {"bg": t["panel"], "fg": t["fg"], "insertbackground": t["fg"]}
                elif role == "text":     cfg = {"bg": t["panel"], "fg": t["fg"]}
                if cfg: w.configure(**cfg)
            except Exception:
                pass

    # ── 按鈕工廠 ─────────────────────────────────────────────────
    def _btn(self, parent, text, cmd, bg, fg=C_WHITE, size=SZ_BODY):
        b = tk.Label(parent, text=text, fg=fg, bg=bg, cursor="hand2",
                     font=(FONT_UI, size, "bold"), padx=14, pady=8)
        b.bind("<Button-1>", lambda e: cmd())
        b.bind("<Enter>",    lambda e: b.configure(bg=self._lighten(bg)))
        b.bind("<Leave>",    lambda e: b.configure(bg=bg))
        return b

    def _neutral_btn(self, parent, text, cmd, size=SZ_BODY):
        b = tk.Label(parent, text=text, cursor="hand2",
                     font=(FONT_UI, size), padx=14, pady=8)
        b.bind("<Button-1>", lambda e: cmd())
        self._track(b, "neutralbtn")
        return b

    @staticmethod
    def _lighten(hex_color: str) -> str:
        try:
            r = int(hex_color[1:3], 16); g = int(hex_color[3:5], 16); b = int(hex_color[5:7], 16)
            r = min(255, r + 30); g = min(255, g + 30); b = min(255, b + 30)
            return f"#{r:02x}{g:02x}{b:02x}"
        except Exception:
            return hex_color

    # ── 分頁①：控制台 ───────────────────────────────────────────
    def _build_main(self, parent):
        # 標題區
        hdr = tk.Frame(parent, pady=16); hdr.pack(fill="x", padx=20); self._track(hdr, "bg")
        tk.Label(hdr, text="VoiceWeaver 語音中心",
                 font=(FONT_UI, SZ_H1, "bold")).pack(side="left")
        self._track(hdr.children[list(hdr.children)[-1]], "fg")
        sub = tk.Label(hdr, text="關閉視窗即停止所有服務",
                       font=(FONT_UI, SZ_SMALL)); sub.pack(side="left", padx=(10, 0), pady=(6, 0))
        self._track(sub, "muted")

        # 服務卡片
        svc_frame = tk.Frame(parent); svc_frame.pack(fill="x", padx=20, pady=(0, 12))
        self._track(svc_frame, "bg")
        for port, name, script, args, needs in SERVICES:
            self._service_card(svc_frame, port, name, script, args, needs)

        # 連線位址卡
        conn_card = tk.Frame(parent, padx=16, pady=12)
        conn_card.pack(fill="x", padx=20, pady=(0, 12)); self._track(conn_card, "panel")
        tk.Label(conn_card, text="手機連線位址",
                 font=(FONT_UI, SZ_H2, "bold")).pack(anchor="w"); self._track(conn_card.children[list(conn_card.children)[-1]], "fg")
        self.conn_label = tk.Label(conn_card, text="偵測中…", justify="left",
                                   font=(FONT_MONO, SZ_SMALL), anchor="w")
        self.conn_label.pack(anchor="w", pady=(6, 0)); self._track(self.conn_label, "muted")

        # 操作按鈕
        act = tk.Frame(parent); act.pack(fill="x", padx=20, pady=(0, 8)); self._track(act, "bg")
        self._btn(act, "🔁  同步聲音模型", self.manual_sync, C_BLUE).pack(
            side="left", expand=True, fill="x", padx=(0, 6))
        self._btn(act, "停止全部並結束",    self.on_close,    C_RED).pack(
            side="left", expand=True, fill="x")

        self.status = tk.Label(parent, text="", font=(FONT_UI, SZ_SMALL), anchor="w")
        self.status.pack(fill="x", padx=20, pady=(4, 12)); self._track(self.status, "muted")

    def _service_card(self, parent, port, name, script, args, needs):
        t = self.theme()
        card = tk.Frame(parent, pady=10, padx=14); card.pack(fill="x", pady=4)
        self._track(card, "panel")

        left = tk.Frame(card); left.pack(side="left", fill="x", expand=True); self._track(left, "panel")
        dot  = tk.Label(left, text="●", font=(FONT_UI, 18)); dot.pack(side="left", padx=(0, 10))
        self._track(dot, "muted")

        info = tk.Frame(left); info.pack(side="left"); self._track(info, "panel")
        nm = tk.Label(info, text=name, font=(FONT_UI, SZ_H2, "bold"), anchor="w")
        nm.pack(anchor="w"); self._track(nm, "fg")
        st = tk.Label(info, text=f"未啟動 · 埠 {port}", font=(FONT_UI, SZ_SMALL), anchor="w")
        st.pack(anchor="w"); self._track(st, "muted")

        btn = tk.Label(card, text="啟動", fg=C_WHITE, bg=C_GREEN, cursor="hand2",
                       font=(FONT_UI, SZ_BODY, "bold"), padx=18, pady=8)
        btn.bind("<Button-1>", lambda e, p=port: self.toggle(p))
        btn.pack(side="right")

        self.rows[port] = {
            "dot": dot, "status": st, "btn": btn,
            "name": name, "script": script, "args": args, "needs": needs,
        }

    # ── 分頁②：模型 / 進階 ──────────────────────────────────────
    def _build_adv(self, outer):
        if not CC_OK:
            msg = tk.Label(outer, text=f"後端模組載入失敗：\n{_CC_ERR}",
                           font=(FONT_UI, SZ_BODY), wraplength=520, justify="left")
            msg.pack(pady=20, padx=20); self._track(msg, "muted"); return

        canvas = tk.Canvas(outer, highlightthickness=0, bd=0); self._track(canvas, "bg")
        vsb = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        parent = tk.Frame(canvas); self._track(parent, "bg")
        win = canvas.create_window((0, 0), window=parent, anchor="nw")
        parent.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfig(win, width=e.width))
        def _wheel(e): canvas.yview_scroll(-1 if e.delta > 0 else 1, "units")
        canvas.bind("<Enter>", lambda e: canvas.bind_all("<MouseWheel>", _wheel))
        canvas.bind("<Leave>", lambda e: canvas.unbind_all("<MouseWheel>"))

        def _section(title):
            f = tk.Frame(parent); f.pack(fill="x", padx=20, pady=(16, 4)); self._track(f, "bg")
            lb = tk.Label(f, text=title, font=(FONT_UI, SZ_H2, "bold"))
            lb.pack(anchor="w"); self._track(lb, "fg")

        # 目前模型狀態
        self.adv_api = tk.Label(parent, text="目前模型：偵測中…",
                                font=(FONT_MONO, SZ_SMALL), anchor="w", justify="left")
        self.adv_api.pack(fill="x", padx=20, pady=(14, 0)); self._track(self.adv_api, "muted")

        _section("聲音模型")
        listwrap = tk.Frame(parent); listwrap.pack(fill="x", padx=20); self._track(listwrap, "bg")
        self.model_list = tk.Listbox(listwrap, height=5, font=(FONT_UI, SZ_BODY),
                                     activestyle="none", highlightthickness=0, bd=0)
        self.model_list.pack(side="left", fill="both", expand=True)
        self._track(self.model_list, "list")
        tk.Scrollbar(listwrap, command=self.model_list.yview).pack(side="right", fill="y")
        self.model_list.config(yscrollcommand=lambda *a: None)

        row1 = tk.Frame(parent); row1.pack(fill="x", padx=20, pady=(8, 4)); self._track(row1, "bg")
        self._btn(row1,     "✅ 切換為此模型", self.adv_switch, C_GREEN).pack(side="left", expand=True, fill="x", padx=(0,4))
        self._neutral_btn(row1, "重新命名", self.adv_rename).pack(side="left", expand=True, fill="x", padx=(0,4))
        self._neutral_btn(row1, "重新整理", self.adv_reload).pack(side="left", expand=True, fill="x", padx=(0,4))
        self._btn(row1,     "🗑 刪除",      self.adv_delete, C_RED).pack(side="left", expand=True, fill="x")

        row2 = tk.Frame(parent); row2.pack(fill="x", padx=20, pady=(4, 4)); self._track(row2, "bg")
        self._btn(row2, "📁 從資料夾匯入模型", self.adv_import, C_BLUE).pack(side="left", expand=True, fill="x", padx=(0,4))
        self._neutral_btn(row2, "⏹ 中止合成", self.adv_stopgen).pack(side="left", expand=True, fill="x")

        _section("測試合成")
        tr = tk.Frame(parent); tr.pack(fill="x", padx=20, pady=(4, 4)); self._track(tr, "bg")
        self.test_entry = tk.Entry(tr, font=(FONT_UI, SZ_BODY))
        self.test_entry.insert(0, "你好，這是測試。")
        self.test_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self._track(self.test_entry, "entry")
        self._btn(tr, "🔊 試聽", self.adv_test, C_BLUE).pack(side="left")

        ta = tk.Frame(parent); ta.pack(fill="x", padx=20, pady=(4, 4)); self._track(ta, "bg")
        self._neutral_btn(ta, "▶ 播放",  self.adv_play).pack(side="left", expand=True, fill="x", padx=(0,4))
        self._neutral_btn(ta, "⏸ 停止",  self.adv_stopplay).pack(side="left", expand=True, fill="x", padx=(0,4))
        reveal_label = "📂 在檔案總管顯示" if IS_WIN else "📂 在 Finder 顯示"
        self._neutral_btn(ta, reveal_label, self.adv_reveal).pack(side="left", expand=True, fill="x")

        _section("USB 工具")
        row3 = tk.Frame(parent); row3.pack(fill="x", padx=20, pady=(4, 4)); self._track(row3, "bg")
        self._neutral_btn(row3, "🔌 重新接手機(USB)", self.adv_adb).pack(side="left", expand=True, fill="x", padx=(0,4))
        self._neutral_btn(row3, "⬇ 安裝 adb 工具", lambda: self.ensure_adb(manual=True)).pack(side="left", expand=True, fill="x")

        _section("模型訓練")
        row3b = tk.Frame(parent); row3b.pack(fill="x", padx=20, pady=(4, 16)); self._track(row3b, "bg")
        self._btn(row3b, "🎓 自己訓練模型（官方 WebUI）", self.open_training, C_AMBER).pack(fill="x")

        self.adv_status = tk.Label(parent, text="", font=(FONT_UI, SZ_SMALL),
                                   wraplength=580, justify="left", anchor="w")
        self.adv_status.pack(fill="x", padx=20, pady=(0, 14)); self._track(self.adv_status, "muted")

        self.adv_reload()

    # ── 分頁③：說明 ─────────────────────────────────────────────
    def _build_help(self, parent):
        wrap = tk.Frame(parent); wrap.pack(fill="both", expand=True, padx=16, pady=16)
        self._track(wrap, "bg")
        txt = tk.Text(wrap, wrap="word", font=(FONT_UI, SZ_BODY), relief="flat", bd=0,
                      padx=16, pady=14, highlightthickness=0, spacing1=3, spacing3=5)
        txt.pack(side="left", fill="both", expand=True)
        sb = ttk.Scrollbar(wrap, command=txt.yview); sb.pack(side="right", fill="y")
        txt.config(yscrollcommand=sb.set); self._track(txt, "text")
        txt.insert("end", HELP_TEXT); txt.config(state="disabled")

    # ── 模型 / 進階 邏輯 ────────────────────────────────────────
    def set_adv_status(self, text):
        try: self.adv_status.config(text=text)
        except Exception: pass

    def _run_bg(self, fn, done_msg_prefix=""):
        def work():
            try:
                ok, msg = fn()
                txt = f"{done_msg_prefix}{msg}"
            except Exception as exc:
                txt = f"失敗：{exc}"
            self.root.after(0, lambda: (self.set_adv_status(txt), self.adv_reload()))
        threading.Thread(target=work, daemon=True).start()

    def adv_reload(self):
        if not CC_OK: return
        try: self.adv_history = cc.load_history()
        except Exception as exc:
            self.set_adv_status(f"讀取模型清單失敗：{exc}"); self.adv_history = []
        self.model_list.delete(0, tk.END)
        for m in self.adv_history:
            used = m.get("last_used_at", "")
            self.model_list.insert(tk.END,
                f"{m.get('name','?')}  [{m.get('version','')}]"
                + (f"  · {used}" if used else ""))
        def upd():
            try:
                ver, gpt, sov = cc.get_current_config()
                gpt_name = os.path.basename(gpt) if gpt else "—"
                online = "已連線" if port_up(9880) else "未啟動"
                txt = f"目前：{ver or '—'}  ·  {gpt_name}  ·  語音(9880)：{online}"
            except Exception:
                txt = "目前模型：讀取失敗"
            self.root.after(0, lambda: self.adv_api.config(text=txt))
        threading.Thread(target=upd, daemon=True).start()

    def _selected_model(self):
        sel = self.model_list.curselection()
        if not sel: self.set_adv_status("請先在清單選一個模型"); return None
        return self.adv_history[sel[0]]

    def adv_switch(self):
        m = self._selected_model()
        if not m: return
        self.set_adv_status(f"切換為「{m.get('name')}」中…")
        def task():
            from pathlib import Path
            gpt_rel = os.path.relpath(resolve_existing(os.path.join(ROOT, m["gpt_rel"])), ROOT)
            sov_rel = os.path.relpath(resolve_existing(os.path.join(ROOT, m["sovits_rel"])), ROOT)
            ref_abs = resolve_existing(m["ref_path"])
            cc.validate_model(m["version"], gpt_rel, sov_rel, Path(ref_abs), m["prompt"])
            ok, _ = cc.apply_runtime_model(m["version"], gpt_rel, sov_rel, Path(ref_abs), m["prompt"])
            cc.mark_model_used(m)
            return ok, ("已切換並載入：" + m.get("name","") if ok else "已更新設定；可重啟語音服務")
        self._run_bg(task)

    def adv_delete(self):
        m = self._selected_model()
        if not m: return
        def task():
            records = [r for r in cc.load_history() if r.get("id") != m.get("id")]
            cc.save_history(records)
            return True, f"已移除「{m.get('name')}」"
        self._run_bg(task)

    def adv_import(self):
        folder = filedialog.askdirectory(title="選擇聲音模型資料夾（含 .ckpt 與 .pth）")
        if not folder: return
        self.set_adv_status("掃描並匯入中…")
        def task():
            import shutil
            from pathlib import Path
            info = cc.scan_model_folder(Path(folder))
            if not info.get("ok"): return False, info.get("error","掃描失敗")
            ver = info["version"]
            gpt_dir, sovits_dir = cc.ensure_dirs(ver)
            gpt_src, sov_src = Path(info["gpt_path"]), Path(info["sovits_path"])
            gpt_dst = gpt_dir / gpt_src.name; sov_dst = sovits_dir / sov_src.name
            if not gpt_dst.exists(): shutil.copy2(gpt_src, gpt_dst)
            if not sov_dst.exists(): shutil.copy2(sov_src, sov_dst)
            ref_src = info.get("ref_path","")
            ref_path = Path(ref_src) if ref_src else None
            if ref_path:
                cc.REF_DIR.mkdir(parents=True, exist_ok=True)
                ref_dst = cc.REF_DIR / ref_path.name
                if not ref_dst.exists(): shutil.copy2(ref_path, ref_dst)
                ref_path = ref_dst
            gpt_rel = os.path.relpath(gpt_dst, ROOT); sov_rel = os.path.relpath(sov_dst, ROOT)
            cc.update_config(ver, gpt_rel, sov_rel)
            cc.remember_model(info["name"], ver, gpt_rel, sov_rel, ref_path or Path(""), info.get("prompt",""))
            ok, _ = cc.apply_runtime_model(ver, gpt_rel, sov_rel, ref_path or Path(""), info.get("prompt",""))
            return True, f"已匯入「{info['name']}」（{ver}）" + ("，已載入" if ok else "；請重啟語音服務")
        self._run_bg(task)

    def adv_test(self):
        m = self._selected_model()
        if not m: return
        text = self.test_entry.get().strip() or "你好。"
        self.set_adv_status("合成測試中…")
        def task():
            from pathlib import Path
            lang = "_ZH"
            for tag in ("_ZH","_EN","_JA","_KO"):
                if tag in (m.get("version","") + m.get("name","")).upper(): lang = tag; break
            ref_abs = resolve_existing(m["ref_path"])
            ok, msg = cc.run_tts_test(text, m.get("prompt",""), Path(ref_abs), 8, lang)
            if ok: cc.play_test_wav()
            return ok, ("已合成並播放" if ok else f"合成失敗：{msg}")
        self._run_bg(task)

    def adv_rename(self):
        m = self._selected_model()
        if not m: return
        new = simpledialog.askstring("重新命名", "新名稱：", initialvalue=m.get("name",""), parent=self.root)
        if not new or not new.strip(): return
        def task():
            records = cc.load_history()
            for r in records:
                if r.get("id") == m.get("id"): r["name"] = new.strip()
            cc.save_history(records)
            return True, f"已重新命名為「{new.strip()}」"
        self._run_bg(task)

    def adv_stopgen(self):
        self.set_adv_status("中止合成中…")
        self._run_bg(lambda: cc.api_stop_generation())

    def adv_play(self):
        self._run_bg(lambda: cc.play_test_wav())

    def adv_stopplay(self):
        self._run_bg(lambda: cc.stop_test_wav_playback())

    def adv_reveal(self):
        self._run_bg(lambda: cc.reveal_test_wav())

    def adv_adb(self):
        if not self.adb:
            self.ensure_adb(manual=True); return
        self.set_adv_status("重新接手機(USB)中…")
        def task():
            try:
                out = subprocess.run([self.adb, "devices"], capture_output=True, text=True,
                                     timeout=8, **POPEN_KW).stdout
                serials = [l.split("\t")[0] for l in out.splitlines()[1:] if "\tdevice" in l]
                for s in serials:
                    for pp in ("9880","9881","9882"):
                        subprocess.run([self.adb,"-s",s,"reverse",f"tcp:{pp}",f"tcp:{pp}"],
                                       capture_output=True, timeout=8, **POPEN_KW)
                ok = bool(serials)
            except Exception: ok = False
            return ok, ("已轉接 USB（手機可用 127.0.0.1）" if ok else "未偵測到 USB 手機")
        self._run_bg(task)

    def open_training(self):
        webui = os.path.join(ROOT, "webui.py")
        if not os.path.exists(webui):
            self.set_adv_status("找不到 webui.py（需安裝完整 GPT-SoVITS）"); return
        if port_up(9874):
            webbrowser.open("http://127.0.0.1:9874")
            self.set_adv_status("官方訓練介面已開啟"); return
        self.set_adv_status("正在啟動官方訓練介面，約 10 秒後自動開啟…")
        try:
            subprocess.Popen([get_py() or sys.executable, "webui.py"], cwd=ROOT,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **POPEN_KW)
        except Exception as exc:
            self.set_adv_status(f"啟動失敗：{exc}"); return
        def wait_open():
            import time as _t
            for _ in range(30):
                if port_up(9874):
                    webbrowser.open("http://127.0.0.1:9874")
                    self.root.after(0, lambda: self.set_adv_status("官方訓練介面已開啟")); return
                _t.sleep(1)
        threading.Thread(target=wait_open, daemon=True).start()

    # ── 服務控制 ─────────────────────────────────────────────────
    def _build_cmd(self, port: str) -> list[str]:
        """組合服務啟動指令（支援凍結版 + 開發版）。"""
        row = self.rows[port]
        script_path = os.path.join(ROOT, row["script"])
        py = get_py() or sys.executable
        if port == "9880":
            return [py, script_path, "-a", _BIND, "-p", "9880",
                    "-c", os.path.join(ROOT, VOICE_CFG)]
        return [py, script_path] + row["args"]

    def start(self, port):
        if port_up(int(port)): return
        row = self.rows[port]
        py = get_py()
        if not py:
            row["status"].config(text="等待 Python 環境…", fg=C_AMBER); return
        script_path = os.path.join(ROOT, row["script"])
        if not os.path.isfile(script_path):
            row["status"].config(text=f"找不到 {row['script']}（需安裝 GPT-SoVITS）", fg=C_AMBER); return
        if row["needs"] and not have_voice_models():
            row["status"].config(text="需先匯入聲音模型（進階分頁）", fg=C_AMBER); return
        try:
            cmd = self._build_cmd(port)
            p = subprocess.Popen(cmd, cwd=ROOT,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **POPEN_KW)
            self.procs[port] = p
            self.set_status(f"啟動 {row['name']} …")
        except Exception as exc:
            row["status"].config(text=f"啟動失敗：{exc}", fg=C_RED)

    def stop(self, port):
        p = self.procs.pop(port, None)
        if p:
            try: p.terminate()
            except Exception: pass
        try:
            for pid in pids_on_port(port):
                if IS_WIN:
                    subprocess.run(["taskkill","/F","/PID",pid],
                                   capture_output=True, timeout=4, **POPEN_KW)
                else:
                    subprocess.run(["kill", pid], capture_output=True, timeout=4)
        except Exception: pass
        name = self.rows[port]["name"] if port in self.rows else f"服務 {port}"
        self.set_status(f"已停止 {name}")

    def toggle(self, port):
        if port_up(int(port)) or port in self.procs:
            self.stop(port)
        else:
            self.start(port)
        self.root.after(400, self.refresh)

    # ── 工具方法 ──────────────────────────────────────────────────
    def set_status(self, text):
        try: self.status.config(text=text)
        except Exception: pass

    def ensure_adb(self, manual=False):
        if self.adb and not manual: return
        self.set_status("正在安裝 USB 工具（adb）…")
        def cb(path):
            self.adb = path
            msg = "USB 工具(adb)已就緒" if path else "adb 安裝失敗，請檢查網路"
            self.root.after(0, lambda: (self.set_status(msg), self.set_adv_status(msg)))
            if path: self._adb_reverse_bg()
        if self.adb:
            cb(self.adb)
        else:
            install_adb_bg(callback=cb)

    def _adb_reverse_bg(self):
        if not self.adb: return
        def _work():
            try:
                out = subprocess.run([self.adb, "devices"], capture_output=True,
                                     text=True, timeout=6, **POPEN_KW).stdout
                serials = [l.split("\t")[0] for l in out.splitlines()[1:] if "\tdevice" in l]
                for s in serials:
                    for pp in ("9880","9881","9882"):
                        subprocess.run([self.adb,"-s",s,"reverse",f"tcp:{pp}",f"tcp:{pp}"],
                                       capture_output=True, timeout=6, **POPEN_KW)
            except Exception: pass
        threading.Thread(target=_work, daemon=True).start()

    # ── 無線端點記憶（USB 接上時學習手機 Wi-Fi IP，之後免插線即可無線同步） ──
    PHONE_IP_FILE = os.path.join(ROOT, ".phone_wifi")

    def _save_phone_ip(self, ip: str) -> None:
        try:
            with open(self.PHONE_IP_FILE, "w", encoding="utf-8") as f:
                f.write(ip.strip())
        except Exception:
            pass

    def _load_phone_ip(self) -> str:
        try:
            with open(self.PHONE_IP_FILE, encoding="utf-8") as f:
                return f.read().strip()
        except Exception:
            return ""

    def _usb_serial(self) -> str:
        """目前 USB 裝置序號（無線 serial 含 . 或 :）；無則空字串。"""
        if not self.adb:
            return ""
        try:
            out = subprocess.run([self.adb, "devices"], capture_output=True, text=True, timeout=6, **POPEN_KW).stdout
            for l in out.splitlines()[1:]:
                if "\tdevice" in l:
                    s = l.split("\t")[0]
                    if "." not in s and ":" not in s:
                        return s
        except Exception:
            pass
        return ""

    def _arm_wireless(self, serial: str) -> None:
        """USB 連接時：讀手機 Wi-Fi IP 並開啟無線 adb（埠 5555），記住端點供日後無線同步。"""
        if not self.adb or not serial:
            return
        try:
            ipout = subprocess.run([self.adb, "-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0"],
                                   capture_output=True, text=True, timeout=6, **POPEN_KW).stdout
            m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", ipout)
            if not m:
                return
            ip = m.group(1)
            subprocess.run([self.adb, "-s", serial, "tcpip", "5555"], capture_output=True, timeout=8, **POPEN_KW)
            self._save_phone_ip(ip)
        except Exception:
            pass

    def _maybe_autosync(self):
        if not self.adb: return
        def _check():
            try:
                usb = self._usb_serial()
                if usb:
                    self.root.after(0, self.sync_models)                 # 有線（USB）
                    self._arm_wireless(usb)                              # 學習手機 Wi-Fi 端點
                else:
                    ip = self._load_phone_ip()
                    if ip:
                        self.root.after(0, lambda: self.sync_models(ip))  # 無線（Wi-Fi）
                    else:
                        self.root.after(0, lambda: self.set_status(
                            "提示：先用 USB 接一次（會自動開啟無線），之後免插線即可無線同步；或按「🔁 同步」"))
            except Exception: pass
        threading.Thread(target=_check, daemon=True).start()

    def manual_sync(self):
        """手動同步：USB 優先；沒插線則用記住的手機 Wi-Fi IP，再沒有就詢問 IP。"""
        usb = self._usb_serial()
        if usb:
            self.sync_models()
            threading.Thread(target=lambda: self._arm_wireless(usb), daemon=True).start()
            return
        ip = self._load_phone_ip()
        if not ip:
            ip = simpledialog.askstring("無線同步", "未偵測到 USB。\n請輸入手機 Wi-Fi IP（例 192.168.1.50）：", parent=self.root)
            if ip and ip.strip():
                self._save_phone_ip(ip.strip())
        if ip and ip.strip():
            self.sync_models(ip.strip())
        else:
            self.set_status("已取消：沒有 USB 也沒有手機 IP")

    def sync_models(self, wifi_ip: str = ""):
        script = os.path.join(ROOT, "sync_voice_models.py")
        if not os.path.exists(script):
            self.set_status("找不到同步程式（sync_voice_models.py）")
            return
        if not self.adb:
            self.set_status("尚未偵測到 adb，無法同步——請用 USB 接上手機或先安裝 adb（進階分頁）")
            return
        via = f"無線 {wifi_ip}" if wifi_ip else "USB"
        self.set_status(f"同步聲音模型中（{via}）…（首次較久）")
        def work():
            try:
                py = get_py() or sys.executable
                env = dict(os.environ)
                env["ADB"] = self.adb
                env["PYTHONUTF8"] = "1"
                env["PYTHONIOENCODING"] = "utf-8"
                cmd = [py, script]
                if wifi_ip:
                    cmd += ["--wifi", wifi_ip]
                r = subprocess.run(cmd, capture_output=True,
                                   encoding="utf-8", errors="replace",
                                   timeout=14400, env=env, **POPEN_KW)
                out_tail = [l for l in (r.stdout or "").splitlines() if l.strip()]
                err_tail = [l for l in (r.stderr or "").splitlines() if l.strip()]
                if r.returncode != 0:
                    detail = err_tail[-1] if err_tail else (out_tail[-1] if out_tail else "未知錯誤")
                    msg = f"同步失敗：{detail}"
                else:
                    msg = out_tail[-1] if out_tail else "同步完成"
            except Exception as exc:
                msg = f"同步失敗：{exc}"
            self.root.after(0, lambda: self.set_status(msg))
            if have_voice_models() and not port_up(9880):
                self.root.after(0, lambda: self.start("9880"))
        threading.Thread(target=work, daemon=True).start()

    # ── 刷新循環 ─────────────────────────────────────────────────
    def refresh(self):
        d = system_is_dark()
        if d != self.dark:
            self.dark = d; self.apply_theme()
        t = self.theme()

        for port, row in self.rows.items():
            up      = port_up(int(port))
            loading = not up and port in self.procs and self.procs[port].poll() is None
            if up:
                row["dot"].config(fg=C_GREEN)
                row["status"].config(text=f"執行中 · 埠 {port}", fg=C_GREEN)
                row["btn"].config(text="停止", bg=C_RED)
            elif loading:
                row["dot"].config(fg=C_AMBER)
                row["status"].config(text="啟動中…", fg=C_AMBER)
                row["btn"].config(text="停止", bg=C_RED)
            else:
                row["dot"].config(fg=t["muted"])
                if "找不到" not in (row["status"].cget("text") or "") \
                        and "需先" not in (row["status"].cget("text") or "") \
                        and "等待" not in (row["status"].cget("text") or ""):
                    row["status"].config(text=f"未啟動 · 埠 {port}", fg=t["muted"])
                row["btn"].config(text="啟動", bg=C_GREEN)

        if not self.adb:
            self.adb = ""   # 留給背景執行緒更新
        lan = lan_ip(); ts = tailscale_ip()
        adb_ok = bool(self.adb)
        lines = [
            f"{'✅' if adb_ok else '⚠️'}  有線(USB)：http://127.0.0.1:9880"
            + ("" if adb_ok else "  ← 需 adb"),
            f"📶  無線(Wi-Fi)：" + (f"http://{lan}:9880" if lan else "未連 Wi-Fi"),
            f"🌐  全域(Tailscale)：" + (f"http://{ts}:9880" if ts else "未連線"),
        ]
        self.conn_label.config(text="\n".join(lines))
        self.root.after(2500, self.refresh)

    def on_close(self):
        self.set_status("正在停止所有服務…")
        self.root.update_idletasks()
        for port, *_ in SERVICES:
            self.stop(port)
        self.stop("9890")
        self.root.destroy()


# ── 入口 ─────────────────────────────────────────────────────────────────
def main():
    if not _acquire_single_instance():
        return
    root = tk.Tk()
    try: root.tk.call("tk", "scaling", 1.5)
    except Exception: pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
