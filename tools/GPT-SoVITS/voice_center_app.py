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
                "qrcode",   # 控制台「掃描連線」用，產生 QR Code 給手機掃
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

# ── 介面多語系（繁中／英／日／韓）：可在標題列「介面語言」獨立切換 ─────────────
UI_LANGS = [("zh-TW", "繁體中文"), ("en", "English"), ("ja", "日本語"), ("ko", "한국어")]

I18N = {
    # 標題列
    "ui.langLabel": {"zh-TW":"介面語言", "en":"Language", "ja":"言語", "ko":"언어"},
    "app.title":    {"zh-TW":"VoiceWeaver 語音中心", "en":"VoiceWeaver Voice Center", "ja":"VoiceWeaver ボイスセンター", "ko":"VoiceWeaver 보이스 센터"},
    "header.sub":   {"zh-TW":"關閉視窗即停止所有服務", "en":"Closing the window stops all services", "ja":"ウィンドウを閉じると全サービス停止", "ko":"창을 닫으면 모든 서비스 중지"},
    # 分頁
    "tab.main": {"zh-TW":"   控制台   ", "en":"   Console   ", "ja":"   コンソール   ", "ko":"   콘솔   "},
    "tab.adv":  {"zh-TW":"   模型 / 進階   ", "en":"   Models / Advanced   ", "ja":"   モデル / 詳細   ", "ko":"   모델 / 고급   "},
    "tab.help": {"zh-TW":"   說明   ", "en":"   Help   ", "ja":"   ヘルプ   ", "ko":"   도움말   "},
    # 服務名稱
    "svc.9880": {"zh-TW":"語音合成 (GPT-SoVITS)", "en":"Voice synthesis (GPT-SoVITS)", "ja":"音声合成 (GPT-SoVITS)", "ko":"음성 합성 (GPT-SoVITS)"},
    "svc.9881": {"zh-TW":"本機生圖 (SD-Turbo)", "en":"Local image gen (SD-Turbo)", "ja":"ローカル画像生成 (SD-Turbo)", "ko":"로컬 이미지 생성 (SD-Turbo)"},
    "svc.9882": {"zh-TW":"文字推論 (Qwen)", "en":"Text inference (Qwen)", "ja":"テキスト推論 (Qwen)", "ko":"텍스트 추론 (Qwen)"},
    # 服務狀態 / 按鈕（{port} 會被代入）
    "svc.running": {"zh-TW":"執行中 · 埠 {port}", "en":"Running · port {port}", "ja":"実行中 · ポート {port}", "ko":"실행 중 · 포트 {port}"},
    "svc.loading": {"zh-TW":"啟動中…", "en":"Starting…", "ja":"起動中…", "ko":"시작 중…"},
    "svc.stopped": {"zh-TW":"未啟動 · 埠 {port}", "en":"Stopped · port {port}", "ja":"停止中 · ポート {port}", "ko":"중지됨 · 포트 {port}"},
    "btn.start": {"zh-TW":"啟動", "en":"Start", "ja":"起動", "ko":"시작"},
    "btn.stop":  {"zh-TW":"停止", "en":"Stop", "ja":"停止", "ko":"중지"},
    # 連線卡
    "conn.title": {"zh-TW":"手機連線位址", "en":"Phone connection address", "ja":"スマホ接続アドレス", "ko":"휴대폰 연결 주소"},
    "conn.sub":   {"zh-TW":"手機 App 的「電腦語音中心」填入任一位址即可連線（點位址可複製）", "en":"In the phone app's “Computer Voice Center”, enter any address to connect (tap to copy).", "ja":"スマホアプリの「PCボイスセンター」にいずれかのアドレスを入力で接続（タップでコピー）。", "ko":"휴대폰 앱의 ‘컴퓨터 보이스 센터’에 아무 주소나 입력하면 연결 (탭하여 복사)."},
    "conn.usb":   {"zh-TW":"有線 USB", "en":"Wired USB", "ja":"有線 USB", "ko":"유선 USB"},
    "conn.phoneLang": {"zh-TW":"手機語言", "en":"Phone language", "ja":"スマホの言語", "ko":"휴대폰 언어"},
    "conn.qr":    {"zh-TW":"📷  掃描連線（顯示 QR Code）", "en":"📷  Scan to connect (show QR code)", "ja":"📷  スキャンで接続（QRコード表示）", "ko":"📷  스캔하여 연결 (QR 코드 표시)"},
    "conn.detecting": {"zh-TW":"偵測中…", "en":"Detecting…", "ja":"検出中…", "ko":"감지 중…"},
    "conn.usbNeed": {"zh-TW":"需 adb 連線", "en":"adb connection needed", "ja":"adb 接続が必要", "ko":"adb 연결 필요"},
    "conn.noWifi":  {"zh-TW":"未連 Wi-Fi", "en":"Wi-Fi not connected", "ja":"Wi-Fi 未接続", "ko":"Wi-Fi 미연결"},
    "conn.noTs":    {"zh-TW":"未連 Tailscale", "en":"Tailscale not connected", "ja":"Tailscale 未接続", "ko":"Tailscale 미연결"},
    # 控制台按鈕
    "main.sync": {"zh-TW":"🔁  同步聲音模型", "en":"🔁  Sync voice models", "ja":"🔁  音声モデルを同期", "ko":"🔁  음성 모델 동기화"},
    "main.quit": {"zh-TW":"停止全部並結束", "en":"Stop all & quit", "ja":"全停止して終了", "ko":"모두 중지 후 종료"},
    # 進階分頁
    "adv.curPrefix": {"zh-TW":"目前模型：偵測中…", "en":"Current model: detecting…", "ja":"現在のモデル：検出中…", "ko":"현재 모델: 감지 중…"},
    "adv.curFmt": {"zh-TW":"目前：{ver}  ·  {gpt}  ·  語音(9880)：{online}", "en":"Current: {ver}  ·  {gpt}  ·  Voice(9880): {online}", "ja":"現在：{ver}  ·  {gpt}  ·  音声(9880)：{online}", "ko":"현재: {ver}  ·  {gpt}  ·  음성(9880): {online}"},
    "adv.online":  {"zh-TW":"已連線", "en":"Connected", "ja":"接続済み", "ko":"연결됨"},
    "adv.offline": {"zh-TW":"未啟動", "en":"Stopped", "ja":"停止中", "ko":"중지됨"},
    "adv.curFail": {"zh-TW":"目前模型：讀取失敗", "en":"Current model: read failed", "ja":"現在のモデル：読み込み失敗", "ko":"현재 모델: 읽기 실패"},
    "adv.secVoice": {"zh-TW":"聲音模型", "en":"Voice models", "ja":"音声モデル", "ko":"음성 모델"},
    "adv.filter":   {"zh-TW":"篩選：", "en":"Filter:", "ja":"絞り込み：", "ko":"필터:"},
    "filter.all":   {"zh-TW":"全部", "en":"All", "ja":"すべて", "ko":"전체"},
    "adv.switch":   {"zh-TW":"✅ 切換為此模型", "en":"✅ Switch to this", "ja":"✅ このモデルに切替", "ko":"✅ 이 모델로 전환"},
    "adv.edit":     {"zh-TW":"✏️ 編輯", "en":"✏️ Edit", "ja":"✏️ 編集", "ko":"✏️ 편집"},
    "adv.reload":   {"zh-TW":"重新整理", "en":"Refresh", "ja":"更新", "ko":"새로고침"},
    "adv.delete":   {"zh-TW":"🗑 刪除", "en":"🗑 Delete", "ja":"🗑 削除", "ko":"🗑 삭제"},
    "adv.import":   {"zh-TW":"📁 匯入單個資料夾", "en":"📁 Import one folder", "ja":"📁 フォルダを1つ取込", "ko":"📁 폴더 하나 가져오기"},
    "adv.importBatch": {"zh-TW":"📂 批量匯入資料夾", "en":"📂 Batch import folders", "ja":"📂 フォルダを一括取込", "ko":"📂 폴더 일괄 가져오기"},
    "adv.stopgen":  {"zh-TW":"⏹ 中止合成", "en":"⏹ Stop synthesis", "ja":"⏹ 合成を中止", "ko":"⏹ 합성 중지"},
    "adv.secTest":  {"zh-TW":"測試合成", "en":"Test synthesis", "ja":"合成テスト", "ko":"합성 테스트"},
    "adv.testDefault": {"zh-TW":"你好，這是測試。", "en":"Hello, this is a test.", "ja":"こんにちは、これはテストです。", "ko":"안녕하세요, 테스트입니다."},
    "adv.audition": {"zh-TW":"🔊 試聽", "en":"🔊 Preview", "ja":"🔊 試聴", "ko":"🔊 미리듣기"},
    "adv.play":     {"zh-TW":"▶ 播放", "en":"▶ Play", "ja":"▶ 再生", "ko":"▶ 재생"},
    "adv.stopPlay": {"zh-TW":"⏸ 停止", "en":"⏸ Stop", "ja":"⏸ 停止", "ko":"⏸ 정지"},
    "adv.revealMac":{"zh-TW":"📂 在 Finder 顯示", "en":"📂 Show in Finder", "ja":"📂 Finder で表示", "ko":"📂 Finder에서 보기"},
    "adv.revealWin":{"zh-TW":"📂 在檔案總管顯示", "en":"📂 Show in Explorer", "ja":"📂 エクスプローラーで表示", "ko":"📂 탐색기에서 보기"},
    "adv.secUsb":   {"zh-TW":"USB 工具", "en":"USB tools", "ja":"USB ツール", "ko":"USB 도구"},
    "adv.reAdb":    {"zh-TW":"🔌 重新接手機(USB)", "en":"🔌 Reconnect phone (USB)", "ja":"🔌 スマホ再接続 (USB)", "ko":"🔌 휴대폰 재연결 (USB)"},
    "adv.installAdb":{"zh-TW":"⬇ 安裝 adb 工具", "en":"⬇ Install adb tools", "ja":"⬇ adb ツールを導入", "ko":"⬇ adb 도구 설치"},
    "adv.secTrain": {"zh-TW":"模型訓練", "en":"Model training", "ja":"モデル訓練", "ko":"모델 훈련"},
    "adv.train":    {"zh-TW":"🎓 自己訓練模型（官方 WebUI）", "en":"🎓 Train your own (official WebUI)", "ja":"🎓 自分で訓練（公式 WebUI）", "ko":"🎓 직접 훈련 (공식 WebUI)"},
    # 說明
    "help.text": {
        "zh-TW": HELP_TEXT,
        "en": """How to use

1. Open this app; the three services start automatically. Green = ready.

2. Connect the phone (the Console tab shows the actual address, pick one):
   • USB: phone uses http://127.0.0.1:9880
   • Wi-Fi: use the computer IP, e.g. http://192.168.x.x:9880
   • Remote: install Tailscale, use the 100.x address (tailscale.com/download)

3. Change voice: on the “Models / Advanced” tab pick one and press “Switch”;
   if there is no model, press “Import folder”.

4. When done, just close the window → all services stop.

Note: the GPT-SoVITS environment must be installed separately (~2GB);
      voice synthesis cannot start until it is installed.
""",
        "ja": """使い方

1. このアプリを開くと、3つのサービスが自動起動します。緑＝準備完了。

2. スマホ接続（「コンソール」タブに実際のアドレス表示、いずれか）：
   • USB：スマホに http://127.0.0.1:9880
   • Wi-Fi：PC の IP、例 http://192.168.x.x:9880
   • 遠隔：Tailscale を入れて 100.x アドレス（tailscale.com/download）

3. 音声切替：「モデル / 詳細」タブで選び「切替」；
   モデルが無ければ「フォルダを取込」。

4. 終了時はウィンドウを閉じるだけ → 全サービス停止。

注意：GPT-SoVITS 環境は別途インストールが必要（約2GB）。
      未インストールでは音声合成は起動できません。
""",
        "ko": """사용 방법

1. 이 앱을 열면 세 가지 서비스가 자동 시작됩니다. 초록 = 준비됨.

2. 휴대폰 연결 (콘솔 탭에 실제 주소 표시, 하나 선택):
   • USB: 휴대폰에 http://127.0.0.1:9880
   • Wi-Fi: 컴퓨터 IP, 예 http://192.168.x.x:9880
   • 원격: Tailscale 설치 후 100.x 주소 (tailscale.com/download)

3. 음성 변경: ‘모델 / 고급’ 탭에서 하나 선택 후 ‘전환’;
   모델이 없으면 ‘폴더 가져오기’.

4. 사용 후 창을 닫으면 → 모든 서비스 중지.

참고: GPT-SoVITS 환경은 별도 설치 필요 (약 2GB);
      미설치 시 음성 합성을 시작할 수 없습니다.
""",
    },
}


def _i18n_pick(entry, lang):
    if not entry:
        return None
    if lang in entry:
        return entry[lang]
    base = (lang or "").split("-")[0]
    for k in entry:
        if k.split("-")[0] == base:
            return entry[k]
    return entry.get("zh-TW")


def ui_cfg_dir() -> str:
    if IS_WIN:
        d = APPSUP
    else:
        d = os.path.expanduser("~/Library/Application Support/VoiceWeaver")
    try: os.makedirs(d, exist_ok=True)
    except Exception: pass
    return d


def load_ui_lang() -> str:
    try:
        p = os.path.join(ui_cfg_dir(), ".ui_lang")
        if os.path.isfile(p):
            v = open(p, encoding="utf-8").read().strip()
            if any(code == v for code, _ in UI_LANGS):
                return v
    except Exception:
        pass
    return "zh-TW"


def save_ui_lang(lang: str) -> None:
    try:
        open(os.path.join(ui_cfg_dir(), ".ui_lang"), "w", encoding="utf-8").write(lang)
    except Exception:
        pass

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
        self.phone_lang  = {"tag": "zh-TW", "name": "繁體中文"}  # 手機語言（由 port 9879 同步）
        self.lang_filter = "ZH"                                   # 模型清單語言篩選（ZH/EN/JA/KO/ALL）
        self.adv_filtered: list[dict] = []                        # 篩選後的顯示清單（索引對應用）
        self._selected_card_idx: int | None = None                # 宮格選中的卡片索引
        self._model_card_items: list[dict] = []                   # 每張卡片的 widget 參考
        self._model_grid_frame = None                              # 宮格容器 Frame
        self._adv_canvas = None                                    # 進階分頁的捲動 canvas
        self._bridge_lock = threading.Lock()                       # 網頁橋接合成序列化
        self._bridge_voice = None                                  # 網頁橋接：目前載入的語音 {name,lang,ref,prompt}
        self.ui_lang = load_ui_lang()                              # 介面語言（可在標題列獨立切換、會記住）
        self._i18n: list[tuple] = []                               # 已註冊待翻譯的 widget [(widget, key)]
        self._help_txt = None                                      # 說明分頁 Text，retranslate 用

        root.title(self.t("app.title"))
        root.geometry("660x760")
        root.minsize(580, 640)
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self._track(root, "bg")

        style = ttk.Style()
        style.theme_use("clam")

        # ── 標題列：介面語言獨立切換（固定寬度＋只換色不變粗體 → 切換時不會位移）──
        topbar = tk.Frame(root, pady=6); topbar.pack(fill="x", padx=12, pady=(8, 0)); self._track(topbar, "bg")
        self._ui_lang_btns = {}
        for code, label in reversed(UI_LANGS):
            b = tk.Label(topbar, text=label, cursor="hand2", padx=8, pady=2, width=7,
                         font=(FONT_UI, SZ_SMALL))
            b.bind("<Button-1>", lambda e, c=code: self.set_ui_lang(c))
            b.pack(side="right", padx=2)
            self._ui_lang_btns[code] = b
        gl = tk.Label(topbar, text="🌐", font=(FONT_UI, SZ_BODY)); gl.pack(side="right", padx=(0, 6))
        self._track(gl, "fg")

        # ── 自製分頁列（取代 ttk.Notebook，選中可確實放大＋墊高凸出）──
        nb_frame = tk.Frame(root); nb_frame.pack(fill="both", expand=True)
        self._track(nb_frame, "bg")
        self._tabbar = tk.Frame(nb_frame); self._tabbar.pack(fill="x", padx=12, pady=(6, 0))
        self._track(self._tabbar, "bg")
        self._content = tk.Frame(nb_frame); self._content.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        self._track(self._content, "bg")

        self.tab_main = tk.Frame(self._content); self._track(self.tab_main, "bg")
        self.tab_adv  = tk.Frame(self._content); self._track(self.tab_adv,  "bg")
        self.tab_help = tk.Frame(self._content); self._track(self.tab_help, "bg")
        self._tabs = [("main", self.tab_main, "tab.main"),
                      ("adv",  self.tab_adv,  "tab.adv"),
                      ("help", self.tab_help, "tab.help")]
        self._tab_btns = {}
        for key, frame, tkey in self._tabs:
            b = tk.Label(self._tabbar, cursor="hand2", anchor="center")
            b.bind("<Button-1>", lambda e, k=key: self.select_tab(k))
            b.pack(side="left", padx=(0, 4), anchor="s")
            self._tab_btns[key] = (b, tkey)
        self._current_tab = None

        self._build_main(self.tab_main)
        self._build_adv(self.tab_adv)
        self._build_help(self.tab_help)

        self.apply_theme()
        self._sync_ui_lang_btns()
        self.select_tab("main")

        # 背景初始化（不阻塞 UI）
        self.root.after(100, self._bg_init)
        self.refresh()

    # ── 介面多語系 ────────────────────────────────────────────────
    def t(self, key: str, **fmt) -> str:
        """取目前介面語言的字串；有 {} 佔位可用 kwargs 代入。"""
        s = _i18n_pick(I18N.get(key), self.ui_lang)
        if s is None:
            return key
        return s.format(**fmt) if fmt else s

    def tr(self, widget, key: str):
        """註冊一個 widget 為可翻譯（text 屬性），立即套用目前語言，回傳該 widget。"""
        self._i18n.append((widget, key))
        try: widget.config(text=self.t(key))
        except Exception: pass
        return widget

    def _sync_ui_lang_btns(self):
        """標題列語言按鈕：目前語言高亮。"""
        t = self.theme()
        for code, b in getattr(self, "_ui_lang_btns", {}).items():
            # 只換顏色、字體不變（避免寬度改變造成整排位移）
            if code == self.ui_lang:
                b.configure(bg=C_BLUE, fg=C_WHITE)
            else:
                b.configure(bg=t["panel"], fg=t["fg"])

    def set_ui_lang(self, code: str):
        if code == self.ui_lang:
            return
        old_default = self.t("adv.testDefault")   # 切換前的測試預設句
        self.ui_lang = code
        save_ui_lang(code)
        self.retranslate()
        # 介面語言＝主語言：同步切換「聲音模型篩選」與「測試合成」語言
        base = code.split("-")[0]
        new_filter = {"zh":"ZH", "en":"EN", "ja":"JA", "ko":"KO"}.get(base, "ZH")
        try: self._set_lang_filter(new_filter)   # 模型清單只剩該語言、並重繪宮格
        except Exception: pass
        # 測試框若還是預設句（任何語言版本）就換成新語言預設句；使用者自行打過的就不動
        try:
            cur = self.test_entry.get().strip()
            all_defaults = {(_i18n_pick(I18N["adv.testDefault"], lc)) for lc, _ in UI_LANGS}
            if cur == "" or cur == old_default or cur in all_defaults:
                self.test_entry.delete(0, tk.END)
                self.test_entry.insert(0, self.t("adv.testDefault"))
        except Exception: pass

    def retranslate(self):
        """切換語言後重畫所有已註冊文字 + 分頁 + 標題 + 說明 + 動態狀態。"""
        # 視窗標題
        try: self.root.title(self.t("app.title"))
        except Exception: pass
        # 分頁列（自製）
        try: self._restyle_tabs()
        except Exception: pass
        # 已註冊 widget
        for w, key in self._i18n:
            try: w.config(text=self.t(key))
            except Exception: pass
        # 服務卡名稱
        for port, row in self.rows.items():
            try: row["nm"].config(text=self.t(f"svc.{port}")); row["name"] = self.t(f"svc.{port}")
            except Exception: pass
        # 語言篩選「全部」按鈕
        try:
            if "ALL" in getattr(self, "_lang_filter_btns", {}):
                self._lang_filter_btns["ALL"].config(text=self.t("filter.all"))
        except Exception: pass
        # 說明分頁
        if self._help_txt is not None:
            try:
                self._help_txt.config(state="normal"); self._help_txt.delete("1.0", "end")
                self._help_txt.insert("end", self.t("help.text")); self._help_txt.config(state="disabled")
            except Exception: pass
        self._sync_ui_lang_btns()
        # 動態區塊：服務狀態 / 連線位址會在下一個 refresh tick 自動以新語言重畫
        try: self.refresh()
        except Exception: pass

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
        self._start_lang_server()

    def _start_lang_server(self):
        """port 9879：手機語言同步 + 網頁橋接（皆帶 CORS，供 github.io 網頁從 localhost/Tailscale 呼叫）

          POST /lang    {"tag","name"}        手機語言推送（既有）
          GET  /health                        橋接狀態（網頁偵測連線用）
          GET  /voices                        模型清單 [{name,lang}]
          POST /switch  {"name","lang"}       切換 GPT-SoVITS 模型（載權重，較慢）
          POST /speak   {"text","lang"?}      用目前語音合成 → 回 audio/wav
        """
        import json, http.server
        app_ref = self

        class _Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a): pass  # 靜音 access log

            def _cors(self):
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "Content-Type")

            def _json(self, code, obj):
                body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
                self.send_response(code); self._cors()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try: self.wfile.write(body)
                except Exception: pass

            def _wav(self, data: bytes):
                self.send_response(200); self._cors()
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try: self.wfile.write(data)
                except Exception: pass

            def _body(self):
                length = int(self.headers.get("Content-Length", 0) or 0)
                raw = self.rfile.read(length) if length else b""
                try: return json.loads(raw) if raw else {}
                except Exception: return {}

            def do_OPTIONS(self):
                self.send_response(204); self._cors(); self.end_headers()

            def do_GET(self):
                if self.path == "/" or self.path.startswith("/health"):
                    cur = app_ref._bridge_voice or {}
                    return self._json(200, {
                        "ok": True, "service": "voiceweaver-bridge",
                        "current": cur.get("name", ""), "lang": cur.get("lang", ""),
                        # 電腦端三項運算的即時可用性（給網頁顯示「誰由電腦跑」）
                        "voice": port_up(9880), "image": port_up(9881), "text": port_up(9882),
                    })
                if self.path.startswith("/voices"):
                    try:
                        voices = [{"name": m.get("name", ""), "lang": app_ref._model_lang_tag(m)}
                                  for m in cc.load_history()]
                        return self._json(200, {"ok": True, "voices": voices})
                    except Exception as e:
                        return self._json(500, {"ok": False, "error": str(e)})
                self._json(404, {"ok": False, "error": "not found"})

            def do_POST(self):
                data = self._body()
                if self.path == "/lang":
                    try:
                        tag  = str(data.get("tag", "zh-TW"))[:10]
                        name = str(data.get("name", tag))[:20]
                        app_ref.phone_lang = {"tag": tag, "name": name}
                        new_filter = ("EN" if tag.startswith("en") else
                                      "JA" if tag.startswith("ja") else
                                      "KO" if tag.startswith("ko") else "ZH")
                        def _sync_ui(n=name, f=new_filter):
                            try: app_ref.phone_lang_lbl.config(text=n)
                            except Exception: pass
                            app_ref._set_lang_filter(f)
                        app_ref.root.after(0, _sync_ui)
                        return self._json(200, {"ok": True})
                    except Exception as e:
                        return self._json(400, {"ok": False, "error": str(e)})
                if self.path == "/switch":
                    ok, msg = app_ref._bridge_switch(str(data.get("name", "")), str(data.get("lang", "")))
                    return self._json(200 if ok else 500, {"ok": ok, "msg": msg})
                if self.path == "/speak":
                    text = str(data.get("text", "")).strip()
                    if not text:
                        return self._json(400, {"ok": False, "error": "empty text"})
                    wav, err = app_ref._bridge_speak(text, str(data.get("lang", "")),
                                                     str(data.get("name", "")))
                    if wav is None:
                        return self._json(500, {"ok": False, "error": err})
                    return self._wav(wav)
                if self.path == "/image":
                    prompt = str(data.get("prompt", "")).strip()
                    if not prompt:
                        return self._json(400, {"ok": False, "error": "empty prompt"})
                    png, err = app_ref._bridge_image(prompt, int(data.get("steps", 2) or 2))
                    if png is None:
                        return self._json(502, {"ok": False, "error": err})
                    self.send_response(200); self._cors()
                    self.send_header("Content-Type", "image/png")
                    self.send_header("Content-Length", str(len(png)))
                    self.end_headers()
                    try: self.wfile.write(png)
                    except Exception: pass
                    return
                if self.path == "/text":
                    out, err = app_ref._bridge_text(str(data.get("system", "")), str(data.get("user", "")))
                    if out is None:
                        return self._json(502, {"ok": False, "error": err})
                    return self._json(200, {"ok": True, "text": out})
                self._json(404, {"ok": False, "error": "not found"})

        def _serve():
            try:
                srv = http.server.ThreadingHTTPServer(("0.0.0.0", 9879), _Handler)
                srv.serve_forever()
            except Exception:
                pass
        threading.Thread(target=_serve, daemon=True).start()

    # ── 網頁橋接：切換 / 合成 ───────────────────────────────────
    def _bridge_find_voice(self, name: str, lang: str):
        norm = self._norm_lang(lang)
        hist = cc.load_history()
        cands = [m for m in hist if m.get("name") == name and (not norm or self._model_lang_tag(m) == norm)]
        if not cands:
            cands = [m for m in hist if m.get("name") == name]
        return cands[0] if cands else None

    def _bridge_switch(self, name: str, lang: str):
        from pathlib import Path
        try:
            m = self._bridge_find_voice(name, lang)
            if not m:
                return False, f"找不到模型「{name}」"
            gpt_rel = os.path.relpath(resolve_existing(os.path.join(ROOT, m["gpt_rel"])), ROOT)
            sov_rel = os.path.relpath(resolve_existing(os.path.join(ROOT, m["sovits_rel"])), ROOT)
            ref_abs = resolve_existing(m["ref_path"])
            cc.apply_runtime_model(m["version"], gpt_rel, sov_rel, Path(ref_abs), m.get("prompt", ""))
            cc.mark_model_used(m)
            self._bridge_voice = {"name": m.get("name", ""), "lang": self._model_lang_tag(m),
                                  "ref": ref_abs, "prompt": m.get("prompt", "")}
            return True, f"已切換到 {name}"
        except Exception as e:
            return False, str(e)

    def _bridge_speak(self, text: str, lang_hint: str = "", want_name: str = ""):
        from pathlib import Path
        with self._bridge_lock:
            try:
                cur = self._bridge_voice
                want_norm = self._norm_lang(lang_hint)
                # 指定了語音、且和目前載入的不同 → 先切換（惰性切換，只在真的換時才付出時間）
                if want_name and (not cur or cur.get("name") != want_name
                                  or (want_norm and self._norm_lang(cur.get("lang", "")) != want_norm)):
                    ok, msg = self._bridge_switch(want_name, lang_hint)
                    if not ok:
                        return None, msg
                # 沒有指定、也沒載入過 → 自動載入最近使用的那一個
                elif not cur:
                    hist = cc.load_history()
                    if not hist:
                        return None, "電腦上沒有任何語音模型"
                    ok, msg = self._bridge_switch(hist[0].get("name", ""), self._model_lang_tag(hist[0]))
                    if not ok:
                        return None, msg
                v = self._bridge_voice
                tag = self._norm_lang(lang_hint) or self._norm_lang(v.get("lang", "")) \
                      or self._detect_lang_from_text(v.get("prompt", "")) or "ZH"
                ok, msg = cc.run_tts_test(text, v.get("prompt", ""), Path(v["ref"]), 8, "_" + tag)
                if not ok:
                    return None, msg
                return cc.TEST_WAV_PATH.read_bytes(), None
            except Exception as e:
                return None, str(e)

    def _bridge_image(self, prompt: str, steps: int = 2):
        """代理到本機生圖伺服器(9881)，回 png bytes。"""
        import urllib.request, json as _json
        try:
            if not port_up(9881):
                return None, "電腦生圖伺服器(9881)未啟動"
            req = urllib.request.Request(
                "http://127.0.0.1:9881/generate",
                data=_json.dumps({"prompt": prompt, "steps": max(1, min(8, steps))}).encode("utf-8"),
                headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=180) as resp:
                return resp.read(), None
        except Exception as e:
            return None, str(e)

    def _bridge_text(self, system: str, user: str):
        """代理到本機文字推論伺服器(9882)，回 text。"""
        import urllib.request, json as _json
        try:
            if not port_up(9882):
                return None, "電腦文字伺服器(9882)未啟動"
            req = urllib.request.Request(
                "http://127.0.0.1:9882/reconstruct",
                data=_json.dumps({"system": system, "user": user}).encode("utf-8"),
                headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=120) as resp:
                obj = _json.loads(resp.read())
            if not obj.get("ok"):
                return None, obj.get("error", "文字推論失敗")
            return obj.get("text", ""), None
        except Exception as e:
            return None, str(e)

    def _copy_conn_url(self, key: str):
        """點連線列 → 複製位址到剪貼簿，並在 ⧉ 圖示閃一下「✓」。"""
        info = self.conn_rows.get(key)
        if not info:
            return
        url = info["url"].cget("text")
        if not url.startswith("http"):
            self.set_status("這個連線方式目前不可用，無法複製")
            return
        try:
            self.root.clipboard_clear(); self.root.clipboard_append(url)
        except Exception:
            pass
        self.set_status(f"已複製：{url}")
        cp = info.get("copy")
        if cp is not None:
            cp.config(text="✓", fg=C_GREEN)
            self.root.after(1200, lambda: cp.config(text="⧉", fg=self.theme()["muted"]))

    def _show_qr(self):
        lan = lan_ip(); ts = tailscale_ip()
        url = f"http://{lan}:9880" if lan else (f"http://{ts}:9880" if ts else "")
        if not url:
            import tkinter.messagebox
            tkinter.messagebox.showwarning("無法取得 IP", "電腦目前未連 Wi-Fi，也未連 Tailscale。\n請用 USB（http://127.0.0.1:9880）。")
            return
        dlg = tk.Toplevel(self.root)
        dlg.title(f"掃描 QR Code → {url}"); dlg.resizable(False, False); dlg.grab_set()
        t = self.theme(); dlg.configure(bg=t["bg"])
        try:
            import qrcode as _qr
            qr = _qr.QRCode(error_correction=_qr.constants.ERROR_CORRECT_M, box_size=8, border=3)
            qr.add_data(url); qr.make(fit=True)
            matrix = qr.get_matrix()
            box = 8; cv_size = len(matrix) * box
            cv = tk.Canvas(dlg, width=cv_size, height=cv_size, bg="white", highlightthickness=0)
            cv.pack(pady=12, padx=24)
            for r, row in enumerate(matrix):
                for c, cell in enumerate(row):
                    if cell:
                        x0, y0 = c*box, r*box
                        cv.create_rectangle(x0, y0, x0+box, y0+box, fill="black", outline="")
        except ImportError:
            tk.Label(dlg, text=f"手動輸入此網址：\n{url}",
                     font=(FONT_MONO, 14, "bold"), bg=t["bg"], fg="#00d4ff").pack(pady=24, padx=24)
        tk.Label(dlg, text=f"Wi-Fi：{url}", font=(FONT_MONO, SZ_SMALL), bg=t["bg"], fg=t["muted"]).pack(pady=(0, 4))
        if ts:
            tk.Label(dlg, text=f"Tailscale：http://{ts}:9880", font=(FONT_MONO, SZ_SMALL), bg=t["bg"], fg=t["muted"]).pack(pady=(0, 4))
        tk.Label(dlg, text="對準手機相機掃描，App 會自動填入 IP", font=(FONT_UI, SZ_SMALL), bg=t["bg"], fg=t["muted"]).pack(pady=(0, 4))
        self._btn(dlg, "關閉", dlg.destroy, C_RED).pack(pady=(4, 16))

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
        # 進度條全程用「來回跑動」(marquee) 表示「正在動」——因為 pip 安裝一個大套件
        # 可能好幾分鐘卡在同一個百分比，用固定值的進度條看起來像當機。
        # 實際進度改用「文字」顯示：里程碑 % ＋ 目前步驟（下載會帶自己的 % 與 MB）。
        now = _t.time()
        if (now - getattr(self, "_last_prog_t", 0)) < 0.15:
            if not (frac is not None and frac >= 0):   # 里程碑更新一律放行，只節流逐行輸出
                return
        self._last_prog_t = now
        if frac is not None and frac >= 0:
            self._iv_pct = int(max(0, min(100, frac * 100)))
        pct = getattr(self, "_iv_pct", 0)
        def upd():
            try:
                self.iv_sub.config(text=f"{pct}%　{msg}")
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
        # Notebook 整體：邊框色設成背景色 → 去掉 clam 預設的淺色「白邊」
        style.configure("TNotebook", background=t["bg"], borderwidth=0,
                        bordercolor=t["bg"], lightcolor=t["bg"], darkcolor=t["bg"])
        # 分頁：未選＝panel 底/灰字；選中＝藍底白字（清楚凸顯，不再凹陷；寬度不變故不位移）
        style.configure("TNotebook.Tab", background=t["panel"], foreground=t["muted"],
                        font=(FONT_UI, SZ_BODY), padding=[16, 8], borderwidth=0,
                        bordercolor=t["bg"], lightcolor=t["panel"], darkcolor=t["panel"])
        style.map("TNotebook.Tab",
                  background=[("selected", C_BLUE), ("active", t["card"])],
                  foreground=[("selected", C_WHITE), ("active", t["fg"])],
                  lightcolor=[("selected", C_BLUE)], darkcolor=[("selected", C_BLUE)],
                  bordercolor=[("selected", C_BLUE)],
                  # expand：選中的分頁往外脹大「凸出去」(左,上,右,下 多出的像素)
                  expand=[("selected", [4, 4, 4, 0])])
        # 移除分頁的虛線焦點框（選中時會出現一圈點點、看起來像凹陷/縮小）
        try:
            style.layout("TNotebook.Tab", [
                ("Notebook.tab", {"sticky": "nswe", "children": [
                    ("Notebook.padding", {"side": "top", "sticky": "nswe", "children": [
                        ("Notebook.label", {"side": "top", "sticky": ""})
                    ]})
                ]})
            ])
        except Exception:
            pass
        style.configure("TScrollbar", background=t["panel"], troughcolor=t["bg"],
                        borderwidth=0, bordercolor=t["bg"], arrowcolor=t["muted"])

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
        self._refresh_card_visuals()
        self._sync_ui_lang_btns()
        try: self._restyle_tabs()
        except Exception: pass

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
        ttl = tk.Label(hdr, font=(FONT_UI, SZ_H1, "bold")); ttl.pack(side="left")
        self.tr(ttl, "app.title"); self._track(ttl, "fg")
        sub = tk.Label(hdr, font=(FONT_UI, SZ_SMALL)); sub.pack(side="left", padx=(10, 0), pady=(6, 0))
        self.tr(sub, "header.sub"); self._track(sub, "muted")

        # 服務卡片
        svc_frame = tk.Frame(parent); svc_frame.pack(fill="x", padx=20, pady=(0, 12))
        self._track(svc_frame, "bg")
        for port, name, script, args, needs in SERVICES:
            self._service_card(svc_frame, port, name, script, args, needs)

        # 連線位址卡
        conn_card = tk.Frame(parent, padx=16, pady=14)
        conn_card.pack(fill="x", padx=20, pady=(0, 12)); self._track(conn_card, "panel")

        # 標題 + 副標
        head = tk.Frame(conn_card); head.pack(fill="x"); self._track(head, "panel")
        ico = tk.Label(head, text="📱", font=(FONT_UI, 18)); ico.pack(side="left", padx=(0, 8)); self._track(ico, "fg")
        tbox = tk.Frame(head); tbox.pack(side="left", fill="x", expand=True); self._track(tbox, "panel")
        ct = tk.Label(tbox, font=(FONT_UI, SZ_H2, "bold"), anchor="w")
        ct.pack(fill="x"); self.tr(ct, "conn.title"); self._track(ct, "fg")
        sub = tk.Label(tbox, font=(FONT_UI, SZ_SMALL), anchor="w", justify="left", wraplength=420)
        sub.pack(fill="x"); self.tr(sub, "conn.sub"); self._track(sub, "muted")

        spacer = tk.Frame(conn_card, height=8); spacer.pack(fill="x"); self._track(spacer, "panel")

        # 三條連線列（每列做成有底色的晶片，URL 可點擊複製）
        self.conn_rows = {}
        for key, icon, name, tkey in [
            ("usb",  "🔌", "有線 USB",  "conn.usb"),
            ("wifi", "📶", "Wi-Fi",     None),
            ("ts",   "🌐", "Tailscale", None),
        ]:
            chip = tk.Frame(conn_card, padx=10, pady=8); chip.pack(fill="x", pady=3); self._track(chip, "card")
            dot = tk.Label(chip, text="●", font=(FONT_UI, 13)); dot.pack(side="left", padx=(0, 8))
            self._track(dot, "muted")
            ic = tk.Label(chip, text=icon, font=(FONT_UI, 13)); ic.pack(side="left", padx=(0, 6)); self._track(ic, "fg")
            lbl = tk.Label(chip, text=name, font=(FONT_UI, SZ_SMALL, "bold"), width=10, anchor="w")
            lbl.pack(side="left"); self._track(lbl, "fg")
            if tkey: self.tr(lbl, tkey)
            url_lbl = tk.Label(chip, text=self.t("conn.detecting"), font=(FONT_MONO, SZ_SMALL), anchor="w", cursor="hand2")
            url_lbl.pack(side="left", fill="x", expand=True, padx=(4, 0)); self._track(url_lbl, "muted")
            cp = tk.Label(chip, text="⧉", font=(FONT_UI, 13), cursor="hand2")
            cp.pack(side="right"); self._track(cp, "muted")
            for w in (url_lbl, cp):
                w.bind("<Button-1>", lambda e, k=key: self._copy_conn_url(k))
            self.conn_rows[key] = {"dot": dot, "url": url_lbl, "copy": cp}

        # 分隔線
        sep = tk.Frame(conn_card, height=1); sep.pack(fill="x", pady=(8, 6)); self._track(sep, "border")

        # 手機語言列
        lang_row = tk.Frame(conn_card, padx=10, pady=6); lang_row.pack(fill="x"); self._track(lang_row, "card")
        lg = tk.Label(lang_row, text="🗣", font=(FONT_UI, 13)); lg.pack(side="left", padx=(0, 6)); self._track(lg, "fg")
        ll = tk.Label(lang_row, font=(FONT_UI, SZ_SMALL, "bold"), width=10, anchor="w")
        ll.pack(side="left"); self.tr(ll, "conn.phoneLang"); self._track(ll, "fg")
        self.phone_lang_lbl = tk.Label(lang_row, text="繁體中文", font=(FONT_UI, SZ_SMALL), anchor="w")
        self.phone_lang_lbl.pack(side="left", padx=(4, 0)); self._track(self.phone_lang_lbl, "fg")

        # QR Code 按鈕（全寬、醒目）
        self.tr(self._btn(conn_card, "", self._show_qr, C_GREEN), "conn.qr").pack(
            fill="x", pady=(12, 2))


        # 操作按鈕
        act = tk.Frame(parent); act.pack(fill="x", padx=20, pady=(0, 8)); self._track(act, "bg")
        self.tr(self._btn(act, "", self.manual_sync, C_BLUE), "main.sync").pack(
            side="left", expand=True, fill="x", padx=(0, 6))
        self.tr(self._btn(act, "", self.on_close, C_RED), "main.quit").pack(
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
        disp_name = self.t(f"svc.{port}")
        nm = tk.Label(info, text=disp_name, font=(FONT_UI, SZ_H2, "bold"), anchor="w")
        nm.pack(anchor="w"); self._track(nm, "fg")
        st = tk.Label(info, text=self.t("svc.stopped", port=port), font=(FONT_UI, SZ_SMALL), anchor="w")
        st.pack(anchor="w"); self._track(st, "muted")

        btn = tk.Label(card, text=self.t("btn.start"), fg=C_WHITE, bg=C_GREEN, cursor="hand2",
                       font=(FONT_UI, SZ_BODY, "bold"), padx=18, pady=8)
        btn.bind("<Button-1>", lambda e, p=port: self.toggle(p))
        btn.pack(side="right")

        self.rows[port] = {
            "dot": dot, "status": st, "btn": btn, "nm": nm,
            "name": disp_name, "script": script, "args": args, "needs": needs,
        }

    # ── 分頁②：模型 / 進階 ──────────────────────────────────────
    def _build_adv(self, outer):
        if not CC_OK:
            msg = tk.Label(outer, text=f"後端模組載入失敗：\n{_CC_ERR}",
                           font=(FONT_UI, SZ_BODY), wraplength=520, justify="left")
            msg.pack(pady=20, padx=20); self._track(msg, "muted"); return

        canvas = tk.Canvas(outer, highlightthickness=0, bd=0); self._track(canvas, "bg")
        self._adv_canvas = canvas
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

        def _section(key):
            f = tk.Frame(parent); f.pack(fill="x", padx=20, pady=(16, 4)); self._track(f, "bg")
            lb = tk.Label(f, font=(FONT_UI, SZ_H2, "bold"))
            lb.pack(anchor="w"); self.tr(lb, key); self._track(lb, "fg")

        # 目前模型狀態
        self.adv_api = tk.Label(parent, text=self.t("adv.curPrefix"),
                                font=(FONT_MONO, SZ_SMALL), anchor="w", justify="left")
        self.adv_api.pack(fill="x", padx=20, pady=(14, 0)); self._track(self.adv_api, "muted")

        _section("adv.secVoice")

        # ── 語言篩選按鈕 ──
        self._lang_filter_btns = {}
        lang_bar = tk.Frame(parent); lang_bar.pack(fill="x", padx=20, pady=(0, 6)); self._track(lang_bar, "bg")
        _flbl = tk.Label(lang_bar, font=(FONT_UI, SZ_SMALL)); _flbl.pack(side="left", padx=(0, 4))
        self.tr(_flbl, "adv.filter"); self._track(_flbl, "muted")
        for code, label in [("ALL", self.t("filter.all")), ("ZH","🇹🇼 中文"), ("EN","🇺🇸 English"), ("JA","🇯🇵 日本語"), ("KO","🇰🇷 한국어")]:
            is_sel = (code == self.lang_filter)
            bg = C_BLUE if is_sel else None
            fg = C_WHITE if is_sel else None
            if bg:
                btn = tk.Label(lang_bar, text=label, bg=bg, fg=fg, cursor="hand2",
                               font=(FONT_UI, SZ_SMALL, "bold"), padx=10, pady=4)
            else:
                btn = tk.Label(lang_bar, text=label, cursor="hand2",
                               font=(FONT_UI, SZ_SMALL), padx=10, pady=4)
                self._track(btn, "neutralbtn")
            btn.bind("<Button-1>", lambda e, c=code: self._set_lang_filter(c))
            btn.pack(side="left", padx=(0, 3))
            self._lang_filter_btns[code] = btn

        self._model_grid_frame = tk.Frame(parent)
        self._model_grid_frame.pack(fill="x", padx=20, pady=(0, 4))
        self._track(self._model_grid_frame, "bg")

        row1 = tk.Frame(parent); row1.pack(fill="x", padx=20, pady=(8, 4)); self._track(row1, "bg")
        self.tr(self._btn(row1, "", self.adv_switch, C_GREEN), "adv.switch").pack(side="left", expand=True, fill="x", padx=(0,4))
        self.tr(self._neutral_btn(row1, "", self.adv_edit), "adv.edit").pack(side="left", expand=True, fill="x", padx=(0,4))
        self.tr(self._neutral_btn(row1, "", self.adv_reload), "adv.reload").pack(side="left", expand=True, fill="x", padx=(0,4))
        self.tr(self._btn(row1, "", self.adv_delete, C_RED), "adv.delete").pack(side="left", expand=True, fill="x")

        row2 = tk.Frame(parent); row2.pack(fill="x", padx=20, pady=(4, 4)); self._track(row2, "bg")
        self.tr(self._btn(row2, "", self.adv_import, C_BLUE), "adv.import").pack(side="left", expand=True, fill="x", padx=(0,4))
        self.tr(self._btn(row2, "", self.adv_import_batch, C_AMBER), "adv.importBatch").pack(side="left", expand=True, fill="x", padx=(0,4))
        self.tr(self._neutral_btn(row2, "", self.adv_stopgen), "adv.stopgen").pack(side="left", expand=True, fill="x")

        _section("adv.secTest")
        tr = tk.Frame(parent); tr.pack(fill="x", padx=20, pady=(4, 4)); self._track(tr, "bg")
        self.test_entry = tk.Entry(tr, font=(FONT_UI, SZ_BODY))
        self.test_entry.insert(0, self.t("adv.testDefault"))
        self.test_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self._track(self.test_entry, "entry")
        self.tr(self._btn(tr, "", self.adv_test, C_BLUE), "adv.audition").pack(side="left")

        ta = tk.Frame(parent); ta.pack(fill="x", padx=20, pady=(4, 4)); self._track(ta, "bg")
        self.tr(self._neutral_btn(ta, "", self.adv_play), "adv.play").pack(side="left", expand=True, fill="x", padx=(0,4))
        self.tr(self._neutral_btn(ta, "", self.adv_stopplay), "adv.stopPlay").pack(side="left", expand=True, fill="x", padx=(0,4))
        self.tr(self._neutral_btn(ta, "", self.adv_reveal), ("adv.revealWin" if IS_WIN else "adv.revealMac")).pack(side="left", expand=True, fill="x")

        _section("adv.secUsb")
        row3 = tk.Frame(parent); row3.pack(fill="x", padx=20, pady=(4, 4)); self._track(row3, "bg")
        self.tr(self._neutral_btn(row3, "", self.adv_adb), "adv.reAdb").pack(side="left", expand=True, fill="x", padx=(0,4))
        self.tr(self._neutral_btn(row3, "", lambda: self.ensure_adb(manual=True)), "adv.installAdb").pack(side="left", expand=True, fill="x")

        _section("adv.secTrain")
        row3b = tk.Frame(parent); row3b.pack(fill="x", padx=20, pady=(4, 16)); self._track(row3b, "bg")
        self.tr(self._btn(row3b, "", self.open_training, C_AMBER), "adv.train").pack(fill="x")

        self.adv_status = tk.Label(parent, text="", font=(FONT_UI, SZ_SMALL),
                                   wraplength=580, justify="left", anchor="w")
        self.adv_status.pack(fill="x", padx=20, pady=(0, 14)); self._track(self.adv_status, "muted")

        self._adv_win = win   # 供切換分頁時校正寬度用
        self.adv_reload()

    def _fix_adv_canvas(self):
        """切到進階分頁時強制校正 canvas 內框寬度與捲動範圍，
        避免 Tk 在 tab 切換時第一個 <Configure> 以 1px 觸發、把內容壓成看不見。"""
        c = self._adv_canvas
        if c is None:
            return
        try:
            c.update_idletasks()
            w = c.winfo_width()
            if w > 1 and getattr(self, "_adv_win", None) is not None:
                c.itemconfig(self._adv_win, width=w)
            c.config(scrollregion=c.bbox("all"))
        except Exception:
            pass

    # ── 自製分頁：樣式 + 切換 ────────────────────────────────────
    def _restyle_tabs(self):
        """選中分頁＝藍底白字、更大字＋更高（凸出）；未選＝panel 灰字、較矮。"""
        t = self.theme()
        for key, (b, tkey) in getattr(self, "_tab_btns", {}).items():
            b.config(text=self.t(tkey))
            if key == self._current_tab:
                b.config(bg=C_BLUE, fg=C_WHITE, font=(FONT_UI, SZ_BODY + 2, "bold"),
                         padx=22, pady=11)   # 大字＋高 → 明顯凸出
            else:
                b.config(bg=t["panel"], fg=t["muted"], font=(FONT_UI, SZ_BODY),
                         padx=16, pady=6)

    def select_tab(self, key: str):
        if key not in self._tab_btns:
            return
        self._current_tab = key
        self._restyle_tabs()
        for k, frame, _ in self._tabs:
            frame.pack_forget()
        dict((k, f) for k, f, _ in self._tabs)[key].pack(fill="both", expand=True)
        if key == "adv":
            # 多次校正 canvas 寬度/捲動範圍——避免切到進階分頁時第一個 <Configure>
            # 以 1px 觸發、把內容壓成看不見（呈現空白）。分幾個時間點重試最保險。
            self._fix_adv_canvas()
            self.root.after_idle(self._fix_adv_canvas)
            for _d in (40, 150, 350):
                self.root.after(_d, self._fix_adv_canvas)

    # ── 分頁③：說明 ─────────────────────────────────────────────
    def _build_help(self, parent):
        wrap = tk.Frame(parent); wrap.pack(fill="both", expand=True, padx=16, pady=16)
        self._track(wrap, "bg")
        txt = tk.Text(wrap, wrap="word", font=(FONT_UI, SZ_BODY), relief="flat", bd=0,
                      padx=16, pady=14, highlightthickness=0, spacing1=3, spacing3=5)
        txt.pack(side="left", fill="both", expand=True)
        sb = ttk.Scrollbar(wrap, command=txt.yview); sb.pack(side="right", fill="y")
        txt.config(yscrollcommand=sb.set); self._track(txt, "text")
        txt.insert("end", self.t("help.text")); txt.config(state="disabled")
        self._help_txt = txt   # retranslate 用

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

    # ── 語言標籤萃取 ──────────────────────────────────────────────
    @staticmethod
    def _norm_lang(raw: str) -> str:
        """把 _ZH / _JA / CN / JP / KR 等格式標準化成 ZH/EN/JA/KO 或空字串。"""
        t = raw.strip().upper().lstrip("_")
        return {"CN": "ZH", "JP": "JA", "KR": "KO"}.get(t, t) if t in ("ZH","EN","JA","JP","KO","KR","CN") else ""

    @staticmethod
    def _detect_lang_from_text(text: str) -> str:
        """依文字種類判斷語言：諺文→KO、假名→JA、漢字→ZH、純拉丁→EN。"""
        if not text: return ""
        if re.search(r"[가-힣]", text): return "KO"
        if re.search(r"[぀-ヿ]", text): return "JA"
        if re.search(r"[一-鿿]", text): return "ZH"
        if re.search(r"[A-Za-z]", text): return "EN"
        return ""

    @classmethod
    def _model_lang_tag(cls, m: dict) -> str:
        """回傳 ZH/EN/JA/KO 或空字串。
        優先讀已存的 lang 欄位；沒有就從參考文字的文字種類偵測；再退回名稱標籤。"""
        stored = cls._norm_lang(m.get("lang", ""))
        if stored:
            return stored
        # 退回①：參考文字（逐字稿）的文字種類 — 最可靠
        det = cls._detect_lang_from_text(m.get("prompt","") or os.path.basename(m.get("ref_path","")))
        if det:
            return det
        # 退回②：從 name/version 字串偵測
        text = (m.get("name","") + " " + m.get("version","")).upper()
        for tag in ("EN","JA","JP","KO","KR","ZH","CN"):
            if f"_{tag}" in text or f" {tag}" in text:
                if   tag in ("ZH","CN"): return "ZH"
                elif tag in ("JA","JP"): return "JA"
                elif tag in ("KO","KR"): return "KO"
                else: return tag
        return ""   # 無標籤 → 不過濾

    def _set_lang_filter(self, code: str):
        self.lang_filter = code
        # 更新按鈕樣式
        for c, btn in getattr(self, "_lang_filter_btns", {}).items():
            if c == code:
                btn.configure(bg=C_BLUE, fg=C_WHITE, font=(FONT_UI, SZ_SMALL, "bold"))
            else:
                t = self.theme()
                btn.configure(bg=t["panel"], fg=t["fg"], font=(FONT_UI, SZ_SMALL))
        self.adv_reload()

    def adv_reload(self):
        if not CC_OK: return
        try: self.adv_history = cc.load_history()
        except Exception as exc:
            self.set_adv_status(f"讀取模型清單失敗：{exc}"); self.adv_history = []

        # 套用語言篩選
        if self.lang_filter == "ALL":
            self.adv_filtered = list(self.adv_history)
        else:
            self.adv_filtered = [
                m for m in self.adv_history
                if self._model_lang_tag(m) in (self.lang_filter, "")
            ]

        # 若選中的模型在新清單裡不存在，重置選擇
        if self._selected_card_idx is not None and self._selected_card_idx >= len(self.adv_filtered):
            self._selected_card_idx = None

        self.root.after(0, self._build_card_grid)

        total = len(self.adv_history); shown = len(self.adv_filtered)
        if total != shown:
            self.set_adv_status(f"顯示 {shown}/{total} 個模型（篩選：{self.lang_filter}）")

        def upd():
            try:
                ver, gpt, sov = cc.get_current_config()
                gpt_name = os.path.basename(gpt) if gpt else "—"
                online = self.t("adv.online") if port_up(9880) else self.t("adv.offline")
                txt = self.t("adv.curFmt", ver=(ver or "—"), gpt=gpt_name, online=online)
            except Exception:
                txt = self.t("adv.curFail")
            self.root.after(0, lambda: self.adv_api.config(text=txt))
        threading.Thread(target=upd, daemon=True).start()

    def _selected_model(self):
        if self._selected_card_idx is None or self._selected_card_idx >= len(self.adv_filtered):
            self.set_adv_status("請先點選一個模型卡片"); return None
        return self.adv_filtered[self._selected_card_idx]

    # ── 宮格卡片 ──────────────────────────────────────────────────
    _LANG_DISPLAY = {"ZH": "中文", "EN": "English", "JA": "日本語", "KO": "한국어"}

    def _build_card_grid(self):
        """清空並重建聲音模型宮格（2 欄）。"""
        if self._model_grid_frame is None:
            return
        # 記住目前捲動位置，重建後還原（避免每次操作都跳回頂端）
        scroll_top = None
        if self._adv_canvas is not None:
            try: scroll_top = self._adv_canvas.yview()[0]
            except Exception: scroll_top = None

        for w in self._model_grid_frame.winfo_children():
            w.destroy()
        self._model_card_items = []

        t = self.theme()
        self._model_grid_frame.columnconfigure(0, weight=1)
        self._model_grid_frame.columnconfigure(1, weight=1)

        for i, m in enumerate(self.adv_filtered):
            row, col = divmod(i, 2)
            lang_tag  = self._model_lang_tag(m)
            lang_text = self._LANG_DISPLAY.get(lang_tag, lang_tag or "")
            name      = m.get("name", "?")
            is_sel    = (i == self._selected_card_idx)

            border_bg = C_BLUE  if is_sel else t["border"]
            card_bg   = t["listsel"] if is_sel else t["card"]
            name_fg   = C_WHITE if is_sel else t["fg"]
            muted_fg  = C_WHITE if is_sel else t["muted"]

            outer = tk.Frame(self._model_grid_frame, bg=border_bg, cursor="hand2", padx=1, pady=1)
            outer.grid(row=row, column=col, padx=4, pady=4, sticky="nsew")

            inner = tk.Frame(outer, bg=card_bg, cursor="hand2", padx=10, pady=7)
            inner.pack(fill="both", expand=True)

            name_lbl = tk.Label(inner, text=name, font=(FONT_UI, SZ_BODY, "bold"),
                                bg=card_bg, fg=name_fg, anchor="w", cursor="hand2", wraplength=170)
            name_lbl.pack(fill="x")

            lang_lbl = tk.Label(inner, text=lang_text, font=(FONT_UI, SZ_SMALL),
                                bg=card_bg, fg=muted_fg, anchor="w", cursor="hand2")
            lang_lbl.pack(fill="x")

            for w in (outer, inner, name_lbl, lang_lbl):
                w.bind("<Button-1>", lambda e, idx=i: self._select_card(idx))

            self._model_card_items.append({
                "outer": outer, "inner": inner,
                "name_lbl": name_lbl, "lang_lbl": lang_lbl,
            })

        # 還原捲動位置（等版面重排與 scrollregion 更新後再設定）
        if scroll_top is not None and self._adv_canvas is not None:
            def _restore(c=self._adv_canvas, y=scroll_top):
                try:
                    c.update_idletasks()
                    c.configure(scrollregion=c.bbox("all"))
                    c.yview_moveto(y)
                except Exception:
                    pass
            self.root.after_idle(_restore)

    @staticmethod
    def _lang_tagged_name(filename: str, lang: str) -> str:
        """若檔名未含任何語言標籤，加上 LANG_ 前綴，避免不同語言同名模型互相覆蓋。"""
        if not lang:
            return filename
        up = filename.upper()
        if any(t in up for t in ("_ZH", "_EN", "_JA", "_JP", "_KO", "_KR", "_CN",
                                 "ZH_", "EN_", "JA_", "JP_", "KO_", "KR_", "CN_")):
            return filename
        return f"{lang}_{filename}"

    def _select_card(self, idx: int):
        self._selected_card_idx = idx
        self._refresh_card_visuals()

    def _refresh_card_visuals(self):
        t = self.theme()
        for i, item in enumerate(self._model_card_items):
            is_sel   = (i == self._selected_card_idx)
            border_bg = C_BLUE  if is_sel else t["border"]
            card_bg   = t["listsel"] if is_sel else t["card"]
            name_fg   = C_WHITE if is_sel else t["fg"]
            muted_fg  = C_WHITE if is_sel else t["muted"]
            try:
                item["outer"].configure(bg=border_bg)
                item["inner"].configure(bg=card_bg)
                item["name_lbl"].configure(bg=card_bg, fg=name_fg)
                item["lang_lbl"].configure(bg=card_bg, fg=muted_fg)
            except Exception:
                pass

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

    # ── 通用模型欄位編輯對話框 ─────────────────────────────────
    def _model_edit_dialog(self, title: str, initial: dict) -> dict | None:
        """
        顯示可編輯 name/version/.ckpt/.pth/ref 音檔/ref 文字的對話框。
        傳入 initial dict（同模型 history record 格式），返回使用者確認後的 dict 或 None。
        initial 可含 gpt_path / sovits_path（原始絕對路徑，掃描結果用）
        或 gpt_rel / sovits_rel（已存紀錄的相對路徑）。
        """
        from pathlib import Path
        t = self.theme()
        result = [None]

        dlg = tk.Toplevel(self.root)
        dlg.title(title)
        dlg.geometry("660x560")
        dlg.resizable(False, False)
        dlg.configure(bg=t["bg"])
        dlg.transient(self.root); dlg.grab_set()

        # ── 欄位輔助函數 ──
        def _row(label, pady=(4, 2)):
            f = tk.Frame(dlg, bg=t["bg"]); f.pack(fill="x", padx=24, pady=pady)
            tk.Label(f, text=label, font=(FONT_UI, SZ_SMALL), bg=t["bg"], fg=t["muted"],
                     width=14, anchor="w").pack(side="left")
            return f

        def _entry_var(label, default="", pady=(4, 2)):
            row = _row(label, pady)
            var = tk.StringVar(value=default)
            tk.Entry(row, textvariable=var, font=(FONT_MONO, SZ_SMALL),
                     bg=t["panel"], fg=t["fg"], insertbackground=t["fg"],
                     relief="flat", bd=4).pack(side="left", fill="x", expand=True)
            return var

        def _file_row(label, default="", filetypes=None, pady=(4, 2), on_pick=None):
            row = _row(label, pady)
            var = tk.StringVar(value=default)
            tk.Entry(row, textvariable=var, font=(FONT_MONO, SZ_SMALL),
                     bg=t["panel"], fg=t["fg"], insertbackground=t["fg"],
                     relief="flat", bd=4).pack(side="left", fill="x", expand=True, padx=(0, 6))
            def browse():
                ft = filetypes or [("All", "*.*")]
                # 若目前路徑有效，以其所在目錄為起點
                cur = var.get()
                init_dir = os.path.dirname(cur) if cur and os.path.exists(os.path.dirname(cur)) else None
                kw = {"filetypes": ft}
                if init_dir: kw["initialdir"] = init_dir
                path = filedialog.askopenfilename(**kw)
                if path:
                    var.set(path)
                    if on_pick: on_pick(path)
            browse_btn = tk.Label(row, text="瀏覽…", bg=C_BLUE, fg=C_WHITE, cursor="hand2",
                                  font=(FONT_UI, SZ_SMALL, "bold"), padx=10, pady=5)
            browse_btn.bind("<Button-1>", lambda e: browse())
            browse_btn.pack(side="left")
            return var

        def _prompt_from_audio(path):
            """從音檔檔名萃取逐字稿（去掉【情緒】前綴），與掃描邏輯一致。"""
            import unicodedata
            stem = unicodedata.normalize("NFC", Path(path).stem)
            stem = re.sub(r"^[【\[〖（(][^】\]〗）)]*[】\]〗）)]\s*", "", stem)
            return stem.strip()

        def _dropdown(label, options, default, pady=(4, 2)):
            row = _row(label, pady)
            var = tk.StringVar(value=default)
            ttk.OptionMenu(row, var, default, *options).pack(side="left")
            return var

        # ── 標題 ──
        tk.Label(dlg, text=title, font=(FONT_UI, SZ_H2, "bold"),
                 bg=t["bg"], fg=t["fg"]).pack(anchor="w", padx=24, pady=(16, 10))

        # ── 各欄位 ──
        v_name   = _entry_var("名稱",       initial.get("name", ""))
        v_ver    = _dropdown ("版本",       sorted(cc.SUPPORTED_VERSIONS), initial.get("version", "v4"))
        auto_lang = self._model_lang_tag(initial)
        v_lang   = _dropdown ("語言",       ["ZH", "EN", "JA", "KO"], auto_lang or "ZH")

        gpt_default = initial.get("gpt_path") or (
            str(Path(ROOT) / initial["gpt_rel"]) if initial.get("gpt_rel") else "")
        v_gpt    = _file_row ("GPT (.ckpt)", gpt_default,
                              [("GPT 模型", "*.ckpt"), ("All", "*.*")])

        sov_default = initial.get("sovits_path") or (
            str(Path(ROOT) / initial["sovits_rel"]) if initial.get("sovits_rel") else "")
        v_sov    = _file_row ("SoVITS (.pth)", sov_default,
                              [("SoVITS 模型", "*.pth"), ("All", "*.*")])

        v_prompt = None  # 先佔位，供 on_pick 閉包延遲取用
        v_ref    = _file_row ("參考音檔",   initial.get("ref_path", ""),
                              [("音檔", "*.wav *.mp3 *.flac"), ("All", "*.*")],
                              on_pick=lambda p: v_prompt.set(_prompt_from_audio(p)) if v_prompt else None)
        v_prompt = _entry_var("參考文字",   initial.get("prompt", ""))

        # ── 確認 / 取消 ──
        sep = tk.Frame(dlg, bg=t["border"], height=1); sep.pack(fill="x", padx=24, pady=(14, 0))
        btn_bar = tk.Frame(dlg, bg=t["bg"]); btn_bar.pack(fill="x", padx=24, pady=12)
        def _confirm():
            result[0] = {
                "name":        v_name.get().strip(),
                "version":     v_ver.get(),
                "lang":        v_lang.get(),
                "gpt_path":    v_gpt.get().strip(),
                "sovits_path": v_sov.get().strip(),
                "ref_path":    v_ref.get().strip(),
                "prompt":      v_prompt.get().strip(),
            }
            dlg.destroy()
        self._btn(btn_bar, "✅ 確認", _confirm, C_GREEN).pack(side="left", expand=True, fill="x", padx=(0, 8))
        self._btn(btn_bar, "取消",    dlg.destroy, C_RED ).pack(side="left", expand=True, fill="x")

        self.root.wait_window(dlg)
        return result[0]

    # ── 匯入（單個，先編輯再匯入）──────────────────────────────
    def adv_import(self):
        import shutil
        from pathlib import Path

        folder = filedialog.askdirectory(title="選擇聲音模型資料夾（含 .ckpt 與 .pth）")
        if not folder: return

        # 先掃描，取得預設值
        info = cc.scan_model_folder(Path(folder))
        initial = {
            "name":        info.get("name", Path(folder).name),
            "version":     info.get("version", "v4"),
            "gpt_path":    info.get("gpt_path", ""),
            "sovits_path": info.get("sovits_path", ""),
            "ref_path":    info.get("ref_path", ""),
            "prompt":      info.get("prompt", ""),
        }

        # 打開編輯對話框讓使用者調整
        edited = self._model_edit_dialog("匯入聲音模型", initial)
        if not edited: return

        self.set_adv_status(f"匯入「{edited['name']}」中…")
        def task():
            ver = edited["version"]
            lang_val = edited.get("lang", "") or "ZH"
            gpt_src = Path(edited["gpt_path"])
            sov_src = Path(edited["sovits_path"])
            if not gpt_src.exists(): return False, f"找不到 .ckpt：{gpt_src}"
            if not sov_src.exists(): return False, f"找不到 .pth：{sov_src}"
            gpt_dir, sovits_dir = cc.ensure_dirs(ver)
            # 加語言前綴避免不同語言同名模型互相覆蓋
            gpt_dst = gpt_dir / self._lang_tagged_name(gpt_src.name, lang_val)
            sov_dst = sovits_dir / self._lang_tagged_name(sov_src.name, lang_val)
            if not gpt_dst.exists(): shutil.copy2(gpt_src, gpt_dst)
            if not sov_dst.exists(): shutil.copy2(sov_src, sov_dst)
            ref_path = Path(edited["ref_path"]) if edited["ref_path"] else None
            if ref_path and ref_path.exists():
                cc.REF_DIR.mkdir(parents=True, exist_ok=True)
                ref_dst = cc.REF_DIR / self._lang_tagged_name(ref_path.name, lang_val)
                if not ref_dst.exists(): shutil.copy2(ref_path, ref_dst)
                ref_path = ref_dst
            gpt_rel = os.path.relpath(gpt_dst, ROOT)
            sov_rel = os.path.relpath(sov_dst, ROOT)
            cc.update_config(ver, gpt_rel, sov_rel)
            cc.remember_model(edited["name"], ver, gpt_rel, sov_rel,
                              ref_path or Path(""), edited["prompt"])
            # 把語言標籤寫入 history（remember_model 不接受 lang 參數）
            hist = cc.load_history()
            for r in hist:
                if r.get("gpt_rel") == gpt_rel and r.get("sovits_rel") == sov_rel:
                    r["lang"] = lang_val; break
            cc.save_history(hist)
            ok, _ = cc.apply_runtime_model(ver, gpt_rel, sov_rel,
                                           ref_path or Path(""), edited["prompt"])
            return True, f"已匯入「{edited['name']}」（{ver}）" + ("，已載入" if ok else "；請重啟語音服務")
        self._run_bg(task)

    # ── 編輯現有模型 ───────────────────────────────────────────
    def adv_edit(self):
        import shutil
        from pathlib import Path

        m = self._selected_model()
        if not m: return

        edited = self._model_edit_dialog("編輯聲音模型", m)
        if not edited: return

        self.set_adv_status(f"更新「{edited['name']}」中…")
        def task():
            ver = edited["version"]
            gpt_src = Path(edited["gpt_path"])
            sov_src = Path(edited["sovits_path"])
            if not gpt_src.exists(): return False, f"找不到 .ckpt：{gpt_src}"
            if not sov_src.exists(): return False, f"找不到 .pth：{sov_src}"

            # 若使用者換了檔案才複製，否則直接用相對路徑
            gpt_dir, sovits_dir = cc.ensure_dirs(ver)
            gpt_dst = gpt_dir / gpt_src.name
            sov_dst = sovits_dir / sov_src.name
            if not gpt_dst.exists(): shutil.copy2(gpt_src, gpt_dst)
            if not sov_dst.exists(): shutil.copy2(sov_src, sov_dst)

            ref_path = Path(edited["ref_path"]) if edited["ref_path"] else None
            if ref_path and ref_path.exists():
                cc.REF_DIR.mkdir(parents=True, exist_ok=True)
                ref_dst = cc.REF_DIR / ref_path.name
                if not ref_dst.exists(): shutil.copy2(ref_path, ref_dst)
                ref_path = ref_dst

            gpt_rel = os.path.relpath(gpt_dst, ROOT)
            sov_rel = os.path.relpath(sov_dst, ROOT)

            # 更新歷史紀錄
            records = cc.load_history()
            for r in records:
                if r.get("id") == m.get("id"):
                    r["name"]       = edited["name"] or r["name"]
                    r["version"]    = ver
                    r["lang"]       = edited.get("lang", "")
                    r["gpt_rel"]    = gpt_rel
                    r["sovits_rel"] = sov_rel
                    r["ref_path"]   = str(ref_path) if ref_path else r.get("ref_path","")
                    r["prompt"]     = edited["prompt"]
                    break
            cc.save_history(records)
            cc.update_config(ver, gpt_rel, sov_rel)
            ok, _ = cc.apply_runtime_model(ver, gpt_rel, sov_rel,
                                           ref_path or Path(""), edited["prompt"])
            return True, f"已更新「{edited['name']}」" + ("，已重新載入" if ok else "；請重啟語音服務")
        self._run_bg(task)

    def adv_import_batch(self):
        """批量掃描一個父資料夾，把所有含 .ckpt + .pth 的子資料夾全部匯入。"""
        parent_dir = filedialog.askdirectory(title="選擇包含多個聲音模型的父資料夾")
        if not parent_dir: return

        from pathlib import Path
        parent_path = Path(parent_dir)

        # 找出所有有效子資料夾（至少含一個 .ckpt 或 .pth）
        candidates = sorted([
            d for d in parent_path.iterdir()
            if d.is_dir() and (any(d.glob("*.ckpt")) or any(d.glob("*.pth")))
        ])

        if not candidates:
            self.set_adv_status("未找到任何含有 .ckpt / .pth 的子資料夾")
            return

        # ── 進度視窗 ──
        t = self.theme()
        dlg = tk.Toplevel(self.root)
        dlg.title("批量匯入進度")
        dlg.geometry("520x360")
        dlg.configure(bg=t["bg"])
        dlg.transient(self.root)
        dlg.grab_set()

        tk.Label(dlg, text=f"找到 {len(candidates)} 個候選資料夾，匯入中…",
                 font=(FONT_UI, SZ_BODY, "bold"), bg=t["bg"], fg=t["fg"]).pack(pady=(16, 4), padx=16, anchor="w")

        bar = ttk.Progressbar(dlg, length=480, mode="determinate", maximum=len(candidates))
        bar.pack(pady=(0, 8), padx=16)

        log_frame = tk.Frame(dlg, bg=t["bg"]); log_frame.pack(fill="both", expand=True, padx=16, pady=(0, 8))
        log_box = tk.Text(log_frame, font=(FONT_MONO, SZ_SMALL), relief="flat", bd=0,
                          state="disabled", height=12, bg=t["panel"], fg=t["fg"])
        log_sb = ttk.Scrollbar(log_frame, command=log_box.yview)
        log_box.configure(yscrollcommand=log_sb.set)
        log_sb.pack(side="right", fill="y")
        log_box.pack(side="left", fill="both", expand=True)

        close_btn = self._btn(dlg, "關閉", dlg.destroy, C_RED)
        close_btn.pack(pady=(0, 12))
        close_btn.configure(state="disabled")

        def _log(msg: str):
            def upd():
                log_box.configure(state="normal")
                log_box.insert("end", msg + "\n")
                log_box.see("end")
                log_box.configure(state="disabled")
            dlg.after(0, upd)

        def _work():
            import shutil
            ok_count = err_count = skip_count = 0
            for i, folder in enumerate(candidates):
                _log(f"[{i+1}/{len(candidates)}] {folder.name} …")
                try:
                    info = cc.scan_model_folder(folder)
                    if not info.get("ok"):
                        _log(f"  ✗ {info.get('error', '掃描失敗')}")
                        err_count += 1
                    else:
                        ver = info["version"]
                        lang_val = self._norm_lang(info.get("lang", "")) or "ZH"
                        gpt_dir, sovits_dir = cc.ensure_dirs(ver)
                        gpt_src = Path(info["gpt_path"])
                        sov_src = Path(info["sovits_path"])
                        # 加語言前綴避免不同語言同名模型互相覆蓋
                        gpt_dst = gpt_dir / self._lang_tagged_name(gpt_src.name, lang_val)
                        sov_dst = sovits_dir / self._lang_tagged_name(sov_src.name, lang_val)
                        already = gpt_dst.exists() and sov_dst.exists()
                        if already:
                            _log(f"  — 已存在（{lang_val}），略過")
                            skip_count += 1
                        else:
                            if not gpt_dst.exists(): shutil.copy2(gpt_src, gpt_dst)
                            if not sov_dst.exists(): shutil.copy2(sov_src, sov_dst)
                            ref_src = info.get("ref_path", "")
                            ref_path = Path(ref_src) if ref_src else None
                            if ref_path:
                                cc.REF_DIR.mkdir(parents=True, exist_ok=True)
                                ref_dst = cc.REF_DIR / self._lang_tagged_name(ref_path.name, lang_val)
                                if not ref_dst.exists(): shutil.copy2(ref_path, ref_dst)
                                ref_path = ref_dst
                            gpt_rel = os.path.relpath(gpt_dst, ROOT)
                            sov_rel = os.path.relpath(sov_dst, ROOT)
                            cc.remember_model(info["name"], ver, gpt_rel, sov_rel,
                                              ref_path or Path(""), info.get("prompt", ""))
                            # 把語言標籤寫入 history
                            hist = cc.load_history()
                            for r in hist:
                                if r.get("gpt_rel") == gpt_rel and r.get("sovits_rel") == sov_rel:
                                    r["lang"] = lang_val; break
                            cc.save_history(hist)
                            _log(f"  ✓ 已匯入「{info['name']}」[{lang_val}]（{ver}）")
                            ok_count += 1
                except Exception as exc:
                    _log(f"  ✗ 例外：{exc}")
                    err_count += 1
                dlg.after(0, lambda v=i + 1: bar.configure(value=v))

            summary = f"完成：{ok_count} 成功 / {skip_count} 略過 / {err_count} 失敗"
            _log(f"\n{summary}")
            def _done():
                close_btn.configure(state="normal")
                self.set_adv_status(summary)
                self.adv_reload()
            dlg.after(0, _done)

        threading.Thread(target=_work, daemon=True).start()

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
        ip = (ip or "").strip()
        try:
            with open(self.PHONE_IP_FILE, "w", encoding="utf-8") as f:
                f.write(ip)
        except Exception:
            pass
        try:
            if ip and hasattr(self, "phone_ip_var") and self.phone_ip_var.get().strip() != ip:
                self.root.after(0, lambda: self.phone_ip_var.set(ip))
        except Exception:
            pass

    def _load_phone_ip(self) -> str:
        try:
            with open(self.PHONE_IP_FILE, encoding="utf-8") as f:
                return f.read().strip()
        except Exception:
            return ""

    def _current_ip_field(self) -> str:
        """UI 欄位目前的手機 IP（使用者可手動改）；沒有就回記憶檔。"""
        try:
            v = self.phone_ip_var.get().strip()
            if v:
                return v
        except Exception:
            pass
        return self._load_phone_ip()

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

    def _read_wlan_ip(self, serial: str) -> str:
        """透過 adb 讀手機 wlan0 的 IPv4（USB 或已連線的無線裝置皆可）。"""
        if not self.adb or not serial:
            return ""
        try:
            out = subprocess.run([self.adb, "-s", serial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0"],
                                 capture_output=True, text=True, timeout=6, **POPEN_KW).stdout
            m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
            return m.group(1) if m else ""
        except Exception:
            return ""

    def _scan_lan_for_adb(self) -> str:
        """掃描電腦所在區網的 :5555（曾開過無線 adb 的手機），回傳第一個開埠的 IP。"""
        base = lan_ip()
        if not base or base.count(".") != 3:
            return ""
        prefix = base.rsplit(".", 1)[0] + "."
        self_last = base.rsplit(".", 1)[1]

        def check(host):
            if host.endswith("." + self_last):
                return ""
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                    s.settimeout(0.25)
                    if s.connect_ex((host, 5555)) == 0:
                        return host
            except Exception:
                pass
            return ""

        import concurrent.futures
        hosts = [prefix + str(i) for i in range(1, 255)]
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=64) as ex:
                for r in ex.map(check, hosts):
                    if r:
                        return r
        except Exception:
            pass
        return ""

    def _mdns_phone_ip(self) -> str:
        """Android 11+ 無線偵錯會用 mDNS 廣播；用 adb mdns services 找手機 IP。"""
        if not self.adb:
            return ""
        try:
            out = subprocess.run([self.adb, "mdns", "services"], capture_output=True, text=True, timeout=6, **POPEN_KW).stdout
            for l in out.splitlines():
                if "_adb" in l:
                    m = re.search(r"(\d+\.\d+\.\d+\.\d+)", l)
                    if m:
                        return m.group(1)
        except Exception:
            pass
        return ""

    def _autodetect_phone_ip_blocking(self) -> str:
        """全自動偵測手機 IP：USB 讀 wlan0 → 區網掃 :5555 → mDNS → 記憶檔。"""
        usb = self._usb_serial()
        if usb:
            ip = self._read_wlan_ip(usb)
            if ip:
                return ip
        return self._scan_lan_for_adb() or self._mdns_phone_ip() or self._load_phone_ip()

    def autodetect_phone_ip(self):
        """背景自動偵測並填入 IP 欄位（UI 按鈕 / 啟動時呼叫）。"""
        self.set_status("自動偵測手機 IP 中…")
        def work():
            ip = self._autodetect_phone_ip_blocking()
            if ip:
                self._save_phone_ip(ip)
                self.root.after(0, lambda: (self.phone_ip_var.set(ip), self.set_status(f"已自動填入手機 IP：{ip}")))
            else:
                self.root.after(0, lambda: self.set_status("找不到手機 IP——請用 USB 接一次，或在欄位手動輸入"))
        threading.Thread(target=work, daemon=True).start()

    def _arm_wireless(self, serial: str) -> None:
        """USB 連接時：讀手機 Wi-Fi IP 並開啟無線 adb（埠 5555），記住端點供日後無線同步。"""
        if not self.adb or not serial:
            return
        ip = self._read_wlan_ip(serial)
        if not ip:
            return
        try:
            subprocess.run([self.adb, "-s", serial, "tcpip", "5555"], capture_output=True, timeout=8, **POPEN_KW)
        except Exception:
            pass
        self._save_phone_ip(ip)

    def _maybe_autosync(self):
        if not self.adb: return
        def _check():
            try:
                usb = self._usb_serial()
                if usb:
                    self.root.after(0, self.sync_models)                 # 有線（USB）
                    self._arm_wireless(usb)                              # 學習手機 Wi-Fi 端點＋填欄位
                    return
                ip = self._autodetect_phone_ip_blocking()                # 沒 USB → 全自動偵測
                if ip:
                    self._save_phone_ip(ip)
                    self.root.after(0, lambda: (self.phone_ip_var.set(ip),
                                                self.set_status(f"未接 USB，自動無線同步（{ip}）…"),
                                                self.sync_models(ip)))
                else:
                    self.root.after(0, lambda: self.set_status(
                        "提示：先用 USB 接一次（會自動開啟無線並記住 IP），之後免插線即可全自動無線同步"))
            except Exception: pass
        threading.Thread(target=_check, daemon=True).start()

    def manual_sync(self):
        """手動同步：USB 優先；沒插線則用 IP 欄位（可手動改），空白就先自動偵測。"""
        usb = self._usb_serial()
        if usb:
            self.sync_models()
            threading.Thread(target=lambda: self._arm_wireless(usb), daemon=True).start()
            return
        ip = self._current_ip_field()
        if ip:
            self._save_phone_ip(ip)
            self.sync_models(ip)
            return
        self.set_status("未接 USB 且 IP 欄位空白，自動偵測中…")
        def work():
            found = self._autodetect_phone_ip_blocking()
            if found:
                self._save_phone_ip(found)
                self.root.after(0, lambda: (self.phone_ip_var.set(found), self.sync_models(found)))
            else:
                self.root.after(0, lambda: self.set_status("找不到手機——請在 IP 欄位手動輸入，或用 USB 接一次"))
        threading.Thread(target=work, daemon=True).start()

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
                row["status"].config(text=self.t("svc.running", port=port), fg=C_GREEN)
                row["btn"].config(text=self.t("btn.stop"), bg=C_RED)
            elif loading:
                row["dot"].config(fg=C_AMBER)
                row["status"].config(text=self.t("svc.loading"), fg=C_AMBER)
                row["btn"].config(text=self.t("btn.stop"), bg=C_RED)
            else:
                row["dot"].config(fg=t["muted"])
                if "找不到" not in (row["status"].cget("text") or "") \
                        and "需先" not in (row["status"].cget("text") or "") \
                        and "等待" not in (row["status"].cget("text") or ""):
                    row["status"].config(text=self.t("svc.stopped", port=port), fg=t["muted"])
                row["btn"].config(text=self.t("btn.start"), bg=C_GREEN)

        if not self.adb:
            self.adb = ""
        lan = lan_ip(); ts = tailscale_ip()
        adb_ok = bool(self.adb)

        usb_url  = "http://127.0.0.1:9880" if adb_ok else self.t("conn.usbNeed")
        wifi_url = f"http://{lan}:9880" if lan else self.t("conn.noWifi")
        ts_url   = f"http://{ts}:9880" if ts else self.t("conn.noTs")

        for key, url, ok in [
            ("usb",  usb_url,  adb_ok),
            ("wifi", wifi_url, bool(lan)),
            ("ts",   ts_url,   bool(ts)),
        ]:
            self.conn_rows[key]["dot"].config(fg=(C_GREEN if ok else t["muted"]))
            self.conn_rows[key]["url"].config(text=url, fg=(t["fg"] if ok else t["muted"]))
            cp = self.conn_rows[key].get("copy")
            if cp is not None:
                cp.config(text=("⧉" if ok else ""), fg=t["muted"])
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
    # DPI 感知：高解析(150%/175%)螢幕不再被 Windows 拉伸成模糊 → 字體邊緣清晰
    if IS_WIN:
        try:
            import ctypes
            try: ctypes.windll.shcore.SetProcessDpiAwareness(1)
            except Exception: ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass
    root = tk.Tk()
    root.withdraw()                        # 先藏起來，等版面全部建好再一次顯示 → 開啟乾淨、不會看到逐塊拼出來
    try: root.tk.call("tk", "scaling", 1.5)
    except Exception: pass
    App(root)
    root.update_idletasks()                # 強制完成一次排版
    root.deiconify()                       # 完整成形後才亮出視窗
    root.mainloop()


if __name__ == "__main__":
    main()
