#!/usr/bin/env python3
"""VoiceWeaver 聲音模型「真雙向」同步（電腦 ↔ 手機固定資料夾）。

概念：聲音模型同時備份在手機與電腦；USB 連接時自動同步：
  - 某一邊「缺少」檔案（新增）→ 自動複製到另一邊。
  - 某一邊「刪除」了檔案 → 另一邊也自動刪除（刪除會傳播）。

怎麼分辨「新增」與「刪除」：用一份「基準快照」(.sync_state.json，記上次同步時存在哪些檔)。
  - 檔案只在 A：若在基準裡 → 是 B 刪掉的 → 從 A 刪；不在基準裡 → 是 A 新增的 → 複製到 B。
  - 第一次同步（沒有基準）→ 只做聯集、絕不刪除，並建立基準。

安全閥：
  - 手機讀不到檔案清單（連線異常）時，整輪不刪除。
  - 單輪刪除數若超過 max(20, 基準 50%) → 視為異常，該輪不刪除（只記錄警告）。
  - 多 transport（USB+無線）自動優先選 USB。

用法：
  python sync_voice_models.py            # 自動選裝置
  python sync_voice_models.py -s SERIAL  # 指定裝置
  ADB=/path/to/adb python sync_voice_models.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys

# Windows 主控台預設 cp950（Big5）：print 含 ↔ → ⚠ 等字元會 UnicodeEncodeError 崩潰。
# 強制 UTF-8 輸出（standalone 執行時的保險；由 App 啟動時 env 也已設 PYTHONUTF8）。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

LOCAL_ROOT = os.path.dirname(os.path.abspath(__file__))
PHONE_ROOT = "/sdcard/VoiceWeaver/voice_models"
STATE_FILE = os.path.join(LOCAL_ROOT, ".sync_state.json")   # 本機基準快照（每台電腦各自一份）

IS_WIN = (os.name == "nt")
ADB_BIN = "adb.exe" if IS_WIN else "adb"
POPEN_KW = {"creationflags": 0x08000000} if IS_WIN else {}   # CREATE_NO_WINDOW（Windows 不彈黑窗）
SUBDIRS = [
    "user_models",
    "ref_audio",
    "GPT_weights_v2",
    "GPT_weights_v4",
    "SoVITS_weights_v2",
    "SoVITS_weights_v4",
    "GPT_SoVITS/pretrained_models",   # 語音基礎模型(~4.6G)：隨身帶，插上任何電腦都能合成語音
]


def find_adb() -> str:
    if os.environ.get("ADB") and os.path.exists(os.environ["ADB"]):
        return os.environ["ADB"]
    if IS_WIN:
        cands = [
            os.path.join(os.path.dirname(LOCAL_ROOT), "platform-tools", "adb.exe"),  # App 自動安裝
            os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
            r"C:\platform-tools\adb.exe",
        ]
    else:
        cands = [
            os.path.join(os.path.dirname(LOCAL_ROOT), "platform-tools", "adb"),       # App 自動安裝
            os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
        ]
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            cands.insert(0, os.path.join(os.environ[env], "platform-tools", ADB_BIN))
    cands.append(ADB_BIN)   # 最後試 PATH
    for c in cands:
        try:
            if subprocess.run([c, "version"], capture_output=True, timeout=6, **POPEN_KW).returncode == 0:
                return c
        except Exception:
            pass
    return ""


def list_devices(adb: str) -> list[str]:
    try:
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=8, **POPEN_KW).stdout
    except Exception:
        return []
    return [ln.split("\t")[0] for ln in out.splitlines()[1:] if "\tdevice" in ln]


def pick_device(adb: str, override: str | None) -> str:
    devs = list_devices(adb)
    if override:
        return override if override in devs else ""
    if not devs:
        return ""
    # 優先 USB：無線 transport 的 serial 含 '.' 或 ':'（例 adb-XXX._adb-tls-connect._tcp）
    usb = [d for d in devs if "." not in d and ":" not in d]
    return usb[0] if usb else devs[0]


def adb_sh(adb: str, serial: str, cmd: str) -> str:
    try:
        r = subprocess.run([adb, "-s", serial, "shell", cmd],
                           capture_output=True, text=True, timeout=120, **POPEN_KW)
        return r.stdout
    except Exception:
        return ""


def phone_inventory(adb: str, serial: str, sub: str) -> dict[str, tuple[int, int]]:
    """回傳 {relpath: (size, mtime)}；資料夾不存在則空。"""
    base = f"{PHONE_ROOT}/{sub}"
    out = adb_sh(adb, serial,
                 f"cd '{base}' 2>/dev/null && find . -type f -exec stat -c '%s %Y %n' {{}} + 2>/dev/null")
    inv: dict[str, tuple[int, int]] = {}
    for ln in out.splitlines():
        parts = ln.strip().split(" ", 2)
        if len(parts) != 3:
            continue
        try:
            size, mtime = int(parts[0]), int(parts[1])
        except ValueError:
            continue
        rel = parts[2]
        if rel.startswith("./"):
            rel = rel[2:]
        if rel:
            inv[rel] = (size, mtime)
    return inv


def local_inventory(sub: str) -> dict[str, tuple[int, int]]:
    base = os.path.join(LOCAL_ROOT, sub)
    inv: dict[str, tuple[int, int]] = {}
    if not os.path.isdir(base):
        return inv
    for root, _dirs, files in os.walk(base, followlinks=True):
        for fn in files:
            fp = os.path.join(root, fn)
            try:
                st = os.stat(fp)
            except OSError:
                continue
            # rel 一律用正斜線，與手機(Android)一致 → 基準鍵跨 Mac/Windows 才比對得上
            rel = os.path.relpath(fp, base).replace(os.sep, "/")
            inv[rel] = (st.st_size, int(st.st_mtime))
    return inv


def _local_path(sub: str, rel: str) -> str:
    """把正斜線 rel 還原成本機路徑（Windows 用反斜線）。"""
    return os.path.join(LOCAL_ROOT, sub, *rel.split("/"))


def push(adb: str, serial: str, sub: str, rel: str) -> bool:
    src = _local_path(sub, rel)
    dst = f"{PHONE_ROOT}/{sub}/{rel}"
    parent = dst.rsplit("/", 1)[0]
    adb_sh(adb, serial, f"mkdir -p '{parent}'")
    try:
        return subprocess.run([adb, "-s", serial, "push", src, dst],
                              capture_output=True, timeout=1800, **POPEN_KW).returncode == 0
    except Exception:
        return False


def pull(adb: str, serial: str, sub: str, rel: str) -> bool:
    src = f"{PHONE_ROOT}/{sub}/{rel}"
    dst = _local_path(sub, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    try:
        return subprocess.run([adb, "-s", serial, "pull", src, dst],
                              capture_output=True, timeout=1800, **POPEN_KW).returncode == 0
    except Exception:
        return False


def phone_rm(adb: str, serial: str, sub: str, rel: str) -> bool:
    return adb_sh(adb, serial, f"rm -f '{PHONE_ROOT}/{sub}/{rel}'") is not None


def local_rm(sub: str, rel: str) -> bool:
    try:
        os.remove(_local_path(sub, rel))
        return True
    except OSError:
        return False


def load_baseline() -> set | None:
    """回傳上次同步時存在的 'sub/rel' 集合；沒有檔（首次）回 None。"""
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return set(json.load(f))
    except Exception:
        return None


def save_baseline(keys: set) -> None:
    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(sorted(keys), f, ensure_ascii=False)
    except Exception:
        pass


def phone_reachable(adb: str, serial: str) -> bool:
    return adb_sh(adb, serial, "echo OK").strip() == "OK"


def main() -> int:
    override = None
    if "-s" in sys.argv:
        i = sys.argv.index("-s")
        if i + 1 < len(sys.argv):
            override = sys.argv[i + 1]

    adb = find_adb()
    if not adb:
        print("[sync] 找不到 adb，略過聲音模型同步", flush=True)
        return 0
    serial = pick_device(adb, override)
    if not serial:
        print("[sync] 沒有可用裝置，略過聲音模型同步", flush=True)
        return 0
    if not phone_reachable(adb, serial):
        print("[sync] 手機無回應，略過（不刪除）", flush=True)
        return 0
    print(f"[sync] 裝置 {serial}：開始雙向同步 ↔ {PHONE_ROOT}", flush=True)
    adb_sh(adb, serial, f"mkdir -p '{PHONE_ROOT}'")

    baseline = load_baseline()
    first_run = baseline is None
    if first_run:
        baseline = set()

    # 收集兩邊清單
    local_all: dict[str, dict] = {}
    phone_all: dict[str, dict] = {}
    for sub in SUBDIRS:
        local_all[sub] = local_inventory(sub)
        phone_all[sub] = phone_inventory(adb, serial, sub)

    # 先算出要刪的清單，套安全閥後再執行（避免連線異常造成大量誤刪）
    del_local: list[tuple[str, str]] = []
    del_phone: list[tuple[str, str]] = []
    copy_push: list[tuple[str, str]] = []
    copy_pull: list[tuple[str, str]] = []
    skipped = 0
    for sub in SUBDIRS:
        local, phone = local_all[sub], phone_all[sub]
        for rel in set(local) | set(phone):
            key = f"{sub}/{rel}"
            lv, pv = local.get(rel), phone.get(rel)
            if lv and pv:
                if lv[0] == pv[0]:
                    skipped += 1
                elif lv[1] >= pv[1]:
                    copy_push.append((sub, rel))
                else:
                    copy_pull.append((sub, rel))
            elif lv and not pv:
                if (not first_run) and key in baseline:
                    del_local.append((sub, rel))     # 手機刪了 → 電腦也刪
                else:
                    copy_push.append((sub, rel))      # 電腦新增 → 推到手機
            elif pv and not lv:
                if (not first_run) and key in baseline:
                    del_phone.append((sub, rel))      # 電腦刪了 → 手機也刪
                else:
                    copy_pull.append((sub, rel))      # 手機新增 → 拉到電腦

    # 安全閥：刪除數異常多 → 該輪不刪除
    total_del = len(del_local) + len(del_phone)
    cap = max(20, len(baseline) // 2)
    if total_del > cap:
        print(f"[sync] ⚠ 偵測到要刪除 {total_del} 個檔（超過上限 {cap}），疑似異常，本輪不刪除", flush=True)
        del_local, del_phone = [], []

    pushed = sum(1 for s, r in copy_push if push(adb, serial, s, r))
    pulled = sum(1 for s, r in copy_pull if pull(adb, serial, s, r))
    dl = sum(1 for s, r in del_local if local_rm(s, r))
    dp = sum(1 for s, r in del_phone if phone_rm(adb, serial, s, r))

    # 重建基準＝同步後實際存在的檔（兩邊應一致）
    present = set()
    for sub in SUBDIRS:
        local = local_inventory(sub)
        phone = phone_inventory(adb, serial, sub)
        for rel in set(local) | set(phone):
            present.add(f"{sub}/{rel}")
    save_baseline(present)

    print(f"[sync] 完成：電腦→手機 {pushed}、手機→電腦 {pulled}、"
          f"刪除(電腦 {dl}/手機 {dp})、未變 {skipped}"
          + ("（首次：只聯集不刪除）" if first_run else ""), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
