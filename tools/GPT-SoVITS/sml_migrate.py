#!/usr/bin/env python3
"""拾語者 Speak My Life（原名 VoiceWeaver）更名後的「舊資料自動搬遷」——電腦端／Colab 共用。

三端（Android、網頁、電腦）同一套規則：
  1. 先找新名字；有就用新的（舊的若也在，一律不動）。
  2. 沒有新的、但有舊的 → 把舊的改名成新的（一次性，之後就走第 1 條）。
  3. 改名失敗 → 直接沿用舊的（絕不能讓使用者的曲庫／設定消失）。
  4. 兩個都沒有 → 用新名字（呼叫端需要時再建立）。

這支只用標準庫，沒有任何副作用（import 時什麼都不做），方便單元測試。
測試：python3 test_sml_migrate.py
"""
from __future__ import annotations

import os
import subprocess
import sys

NEW_NAME = "Speak-My-Life"          # 檔名／資料夾／Drive 根資料夾用的英文識別名
OLD_NAME = "VoiceWeaver"            # 舊名（只用來找舊資料）
NEW_DOT = ".speakmylife"            # 家目錄下的隱藏設定資料夾
OLD_DOT = ".voiceweaver"
DESKTOP_HOME_NEW = NEW_NAME + "-電腦端"
DESKTOP_HOME_OLD = OLD_NAME + "-電腦端"

IS_WIN = (os.name == "nt")


def _log(msg: str) -> None:
    try:
        print(f"[migrate] {msg}", file=sys.stderr, flush=True)
    except Exception:  # noqa: BLE001
        pass


def _exists(p: str) -> bool:
    # lexists：壞掉的捷徑也算「有東西」，不能直接蓋過去
    return bool(p) and os.path.lexists(p)


def _make_dir_link(link: str, target: str) -> bool:
    """在 link 建一個指向 target 的資料夾捷徑。macOS/Linux 用 symlink；
    Windows 先試 symlink（要開發者模式），不行再用 junction（一般權限即可）。"""
    try:
        os.symlink(target, link, target_is_directory=True)
        return True
    except Exception:  # noqa: BLE001
        pass
    if IS_WIN:
        try:
            r = subprocess.run(["cmd", "/c", "mklink", "/J", link, target],
                               capture_output=True, timeout=15,
                               creationflags=0x08000000)
            return r.returncode == 0 and os.path.isdir(link)
        except Exception:  # noqa: BLE001
            pass
    return False


def migrate_path(new: str, old: str, link_old: bool = False) -> str:
    """本機資料夾（或檔案）的一次性改名。回「之後該用的路徑」。

    link_old=True：改名後在舊位置留一個捷徑指向新位置。用在裡面有程式寫死
    絕對路徑的資料夾（conda 環境、model_history.json 的參考音路徑…）——
    舊路徑照樣解得開，不必重裝。捷徑建不起來就把改名退回去、沿用舊路徑。
    """
    if _exists(new):
        return new
    if not old or not (os.path.isdir(old) or os.path.isfile(old)):
        return new
    if os.path.islink(old):
        # 舊位置本身就是捷徑（例如上次留下的、或使用者自己做的）→ 不搬，照用
        return old
    try:
        parent = os.path.dirname(new)
        if parent:
            os.makedirs(parent, exist_ok=True)
        os.rename(old, new)
    except Exception as e:  # noqa: BLE001
        _log(f"改名失敗，沿用舊路徑 {old}：{e}")
        return old
    if link_old and os.path.isdir(new):
        if not _make_dir_link(old, new):
            try:
                os.rename(new, old)
                _log(f"無法在舊位置留捷徑，已退回、沿用舊路徑 {old}")
                return old
            except Exception as e:  # noqa: BLE001
                _log(f"留捷徑失敗且無法退回（{e}）；改用新路徑 {new}")
                return new
    _log(f"已把 {old} 改名為 {new}")
    return new


def remap_legacy_path(path: str, pairs) -> str:
    """把記在設定檔裡的舊絕對路徑換成新位置（新位置確實存在才換）。

    pairs：[(舊前綴, 新前綴), …]。用在 .emotion_root 這類記住使用者選過的資料夾。
    """
    if not path:
        return path
    norm = os.path.normpath(path)
    for old, new in pairs:
        if not old or not new:
            continue
        o = os.path.normpath(old)
        if norm == o or norm.startswith(o + os.sep):
            cand = os.path.normpath(new) + norm[len(o):]
            if os.path.exists(cand):
                return cand
    return path


# ── 固定位置 ──────────────────────────────────────────────────────────────
def home_dir() -> str:
    return os.path.expanduser("~")


def desktop_home(home: str | None = None) -> str:
    """電腦端執行資料夾：~/Speak-My-Life-電腦端（舊 ~/VoiceWeaver-電腦端 會改名，
    舊位置留捷徑——conda／miniforge 內寫死的路徑照樣能用）。
    可用環境變數 SML_GS_HOME 指定別處（指定了就完全不搬）。"""
    env = os.environ.get("SML_GS_HOME", "")
    if env:
        return env
    home = home or home_dir()
    return migrate_path(os.path.join(home, DESKTOP_HOME_NEW),
                        os.path.join(home, DESKTOP_HOME_OLD), link_old=True)


def app_support_dir(create: bool = False) -> str:
    """App 自己的儲存空間：
    macOS／Linux ~/Library/Application Support/Speak-My-Life、Windows %LOCALAPPDATA%\\Speak-My-Life。
    舊的 …/VoiceWeaver 會改名並留捷徑（裡面的 Python 環境有寫死路徑）。"""
    if IS_WIN:
        base = os.environ.get("LOCALAPPDATA", "") or os.path.join(home_dir(), "AppData", "Local")
    else:
        base = os.path.join(home_dir(), "Library", "Application Support")
    d = migrate_path(os.path.join(base, NEW_NAME), os.path.join(base, OLD_NAME), link_old=True)
    if create:
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:  # noqa: BLE001
            pass
    return d


def dot_dir(create: bool = False) -> str:
    """家目錄的隱藏設定資料夾 ~/.speakmylife（舊 ~/.voiceweaver 會搬過來）。
    Windows 放 %APPDATA%\\Speak-My-Life（舊 %APPDATA%\\VoiceWeaver）。"""
    if IS_WIN:
        base = os.environ.get("APPDATA", "") or home_dir()
        d = migrate_path(os.path.join(base, NEW_NAME), os.path.join(base, OLD_NAME))
    else:
        d = migrate_path(os.path.join(home_dir(), NEW_DOT), os.path.join(home_dir(), OLD_DOT))
    if create:
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:  # noqa: BLE001
            pass
    return d


def mount_root(parent: str) -> str:
    """掛載式雲端硬碟（Colab 的 /content/drive/MyDrive、Drive for Desktop 的「我的雲端硬碟」）
    底下的曲庫根：<parent>/Speak-My-Life；只有舊的就 os.rename（等同在 Drive 上改名）。"""
    return migrate_path(os.path.join(parent, NEW_NAME), os.path.join(parent, OLD_NAME))


# ── Google Drive API（drive.file）────────────────────────────────────────
def resolve_drive_root(list_new, list_old, pick, rename, create=None):
    """Drive 曲庫根資料夾 id。

    list_new()/list_old()：回該名字在雲端硬碟最外層的候選 [{id,name}, …]
    pick(cands)          ：多個同名時挑「裝著曲庫的那個」，回 id
    rename(fid, name)    ：Drive files.update 改名（drive.file 只能改 App 自己建的；
                           別人建的會丟例外 → 沿用舊資料夾）
    create()             ：兩個都沒有時建立新的；None 表示不建、回 None
    資料夾 id 改名前後不變，所以改名成功或失敗都回同一個 id。
    """
    new = list_new()
    if new:
        return pick(new)
    old = list_old()
    if old:
        fid = pick(old)
        try:
            rename(fid, NEW_NAME)
            _log(f"已把 Google Drive 的 {OLD_NAME}/ 改名為 {NEW_NAME}/")
        except Exception as e:  # noqa: BLE001
            _log(f"Google Drive 的 {OLD_NAME}/ 改名失敗，沿用舊資料夾：{e}")
        return fid
    return create() if create else None


# ── 手機（adb）────────────────────────────────────────────────────────────
PHONE_BASE_NEW = "/sdcard/" + NEW_NAME
PHONE_BASE_OLD = "/sdcard/" + OLD_NAME


def migrate_phone_dir(sh, sub: str = "voice_models") -> str:
    """手機上的同步資料夾：/sdcard/Speak-My-Life/<sub>；只有舊的 /sdcard/VoiceWeaver/<sub>
    就用 mv 搬過去（同一個儲存區，只改目錄項目、不複製內容）。失敗沿用舊的。

    sh(cmd) -> str：在手機上跑一行 shell 指令並回 stdout（adb shell）。
    """
    new = f"{PHONE_BASE_NEW}/{sub}"
    old = f"{PHONE_BASE_OLD}/{sub}"

    def is_dir(p):
        return "Y" in (sh(f"[ -d '{p}' ] && echo Y || echo N") or "")

    if is_dir(new):
        return new
    if not is_dir(old):
        return new
    sh(f"mkdir -p '{PHONE_BASE_NEW}' && mv '{old}' '{new}' && rmdir '{PHONE_BASE_OLD}' 2>/dev/null")
    if is_dir(new) and not is_dir(old):
        _log(f"已把手機 {old} 搬到 {new}")
        return new
    _log(f"手機資料夾搬移失敗，沿用 {old}")
    return old

