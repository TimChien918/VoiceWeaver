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
  python sync_voice_models.py                     # 自動選裝置（USB 優先）
  python sync_voice_models.py -s SERIAL           # 指定裝置
  python sync_voice_models.py --wifi 192.168.1.50 # 無線：像 USB 但走 Wi-Fi（埠預設 5555）
  python sync_voice_models.py --pair 192.168.1.50:37000 123456 --wifi 192.168.1.50:40000
                                                  # Android 11+ 首次：先配對再連線
  ADB=/path/to/adb python sync_voice_models.py

無線（Wi-Fi）連線怎麼開：
  方式 A（Android 11+，免插線）：手機「開發者選項 → 無線偵錯」開啟 → 進去看「IP 位址與埠」
    和「使用配對碼配對裝置」。第一次用 --pair <配對IP:埠> <配對碼>，之後用 --wifi <IP:連線埠>。
  方式 B（先插一次 USB）：`adb tcpip 5555` 後拔線，再 `--wifi <手機IP>` 即可（重開機需重來）。
  原理：和 USB 同一套雙向同步邏輯，只是 transport 換成 TCP/IP——不經任何雲端、不需常開伺服器。
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

# ── 輸出多語系（由 App 以環境變數 SML_LANG 傳入；狀態列顯示對應語言）──
_LANG = (os.environ.get("SML_LANG", "zh-TW") or "zh-TW").split("-")[0]
_STR = {
    "pair":       {"en": "[sync] Wireless pairing {t}: {o}", "ja": "[sync] 無線ペアリング {t}：{o}", "ko": "[sync] 무선 페어링 {t}: {o}"},
    "pair_fail":  {"en": "[sync] Wireless pairing failed: {e}", "ja": "[sync] 無線ペアリング失敗：{e}", "ko": "[sync] 무선 페어링 실패: {e}"},
    "connect":    {"en": "[sync] Wireless connect {t}: {o}", "ja": "[sync] 無線接続 {t}：{o}", "ko": "[sync] 무선 연결 {t}: {o}"},
    "no_adb":     {"en": "[sync] adb not found; skipping voice-model sync", "ja": "[sync] adb が見つからず、音声モデル同期をスキップ", "ko": "[sync] adb 없음, 음성 모델 동기화 건너뜀"},
    "no_dev":     {"en": "[sync] No available device; skipping voice-model sync", "ja": "[sync] 利用可能な端末なし、同期をスキップ", "ko": "[sync] 사용 가능한 기기 없음, 동기화 건너뜀"},
    "no_resp":    {"en": "[sync] Phone not responding; skipped (no deletion)", "ja": "[sync] スマホ無応答、スキップ（削除なし）", "ko": "[sync] 휴대폰 무응답, 건너뜀 (삭제 안 함)"},
    "start":      {"en": "[sync] Device {s}: starting two-way sync ↔ {r}", "ja": "[sync] 端末 {s}：双方向同期を開始 ↔ {r}", "ko": "[sync] 기기 {s}: 양방향 동기화 시작 ↔ {r}"},
    "folders":    {"en": "[sync] Sync folders (auto-found {n}): {l}", "ja": "[sync] 同期フォルダ（自動探索 {n}）：{l}", "ko": "[sync] 동기화 폴더 (자동 탐색 {n}): {l}"},
    "cap":        {"en": "[sync] ⚠ Would delete {d} files (over the {c} cap) — looks abnormal, skipping deletion this round", "ja": "[sync] ⚠ {d} 個の削除を検出（上限 {c} 超過）、異常のため今回は削除せず", "ko": "[sync] ⚠ {d}개 삭제 감지 (상한 {c} 초과), 이상으로 이번엔 삭제 안 함"},
    "done":       {"en": "[sync] Done: PC→phone {a}, phone→PC {b}, deleted(PC {dl}/phone {dp}), unchanged {u}", "ja": "[sync] 完了：PC→スマホ {a}、スマホ→PC {b}、削除(PC {dl}/スマホ {dp})、未変更 {u}", "ko": "[sync] 완료: PC→폰 {a}, 폰→PC {b}, 삭제(PC {dl}/폰 {dp}), 변경 없음 {u}"},
    "done_first": {"en": " (first run: union only, no deletion)", "ja": "（初回：統合のみ削除なし）", "ko": " (첫 실행: 통합만, 삭제 없음)"},
}
def _t(key: str, **kw) -> str:
    row = _STR.get(key, {})
    tmpl = row.get(_LANG)          # 查無語言（含 zh）→ 用下面各處保留的中文預設
    return tmpl.format(**kw) if tmpl else None

LOCAL_ROOT = os.path.dirname(os.path.abspath(__file__))
PHONE_ROOT = "/sdcard/VoiceWeaver/voice_models"
STATE_FILE = os.path.join(LOCAL_ROOT, ".sync_state.json")   # 本機基準快照（每台電腦各自一份）

IS_WIN = (os.name == "nt")
ADB_BIN = "adb.exe" if IS_WIN else "adb"
POPEN_KW = {"creationflags": 0x08000000} if IS_WIN else {}   # CREATE_NO_WINDOW（Windows 不彈黑窗）
# 會自動同步的「頂層」資料夾前綴（聲音模型相關）。用前綴白名單避免誤掃 source/快取/暫存
# (GPT_SoVITS 原始碼樹、Docker、TEMP、__pycache__…)。任何符合前綴的資料夾——含未來新版本
# (GPT_weights_v3 / SoVITS_weights_v2Pro / *ProPlus…)、新錄的 ref_audio、新訓練的 user_models——
# 都會自動納入，不必再維護寫死清單。這就是「新增檔案也會同步」的關鍵。
SUBDIR_PREFIXES = ("GPT_weights", "SoVITS_weights", "ref_audio", "user_models")
# 一定同步的巢狀特例：語音基礎模型(~4.6G)，隨身帶、插上任何電腦都能合成語音。
EXTRA_SUBDIRS = ["GPT_SoVITS/pretrained_models"]


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


def discover_subdirs(adb: str, serial: str) -> list[str]:
    """聯集「電腦端」與「手機」voice_models 底下、符合白名單前綴的頂層資料夾，外加基礎模型特例。

    取代過去寫死的清單——使用者新訓練/匯入任何版本的權重（v2Pro/v3/ProPlus…）或新錄音，
    只要落在 SUBDIR_PREFIXES 命中的頂層資料夾，下次同步就會自動被發現並雙向同步。
    """
    found: set[str] = set(EXTRA_SUBDIRS)
    # 本機頂層
    if os.path.isdir(LOCAL_ROOT):
        for name in os.listdir(LOCAL_ROOT):
            if name.startswith(SUBDIR_PREFIXES) and os.path.isdir(os.path.join(LOCAL_ROOT, name)):
                found.add(name)
    # 手機頂層
    out = adb_sh(adb, serial, f"ls -1 '{PHONE_ROOT}' 2>/dev/null")
    for name in out.splitlines():
        name = name.strip()
        if name and name.startswith(SUBDIR_PREFIXES):
            found.add(name)
    return sorted(found)


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


def adb_pair(adb: str, target: str, code: str) -> None:
    """Android 11+ 無線偵錯首次配對。target=HOST:PORT（「無線偵錯」頁的配對 IP:埠），code=6 位配對碼。"""
    try:
        out = subprocess.run([adb, "pair", target, code], capture_output=True, text=True, timeout=25, **POPEN_KW).stdout
        print(_t("pair", t=target, o=out.strip()) or f"[sync] 無線配對 {target}：{out.strip()}", flush=True)
    except Exception as e:
        print(_t("pair_fail", e=e) or f"[sync] 無線配對失敗：{e}", flush=True)


def adb_connect(adb: str, target: str) -> str:
    """以 Wi-Fi 連上手機（像 USB 但走無線）。target=HOST 或 HOST:PORT，無埠預設 5555。
    成功回傳實際 serial（HOST:PORT），失敗回傳空字串。"""
    if ":" not in target:
        target += ":5555"
    try:
        out = subprocess.run([adb, "connect", target], capture_output=True, text=True, timeout=12, **POPEN_KW).stdout
    except Exception:
        return ""
    print(_t("connect", t=target, o=out.strip()) or f"[sync] 無線連線 {target}：{out.strip()}", flush=True)
    return target if ("connected to" in out or "already connected" in out) else ""


def main() -> int:
    override = None
    if "-s" in sys.argv:
        i = sys.argv.index("-s")
        if i + 1 < len(sys.argv):
            override = sys.argv[i + 1]

    adb = find_adb()
    if not adb:
        print(_t("no_adb") or "[sync] 找不到 adb，略過聲音模型同步", flush=True)
        return 0

    # ── 無線（Wi-Fi）連線：像 USB 但走無線，不需雲端、不需常開伺服器 ──
    if "--pair" in sys.argv:
        i = sys.argv.index("--pair")
        if i + 2 < len(sys.argv):
            adb_pair(adb, sys.argv[i + 1], sys.argv[i + 2])
    if "--wifi" in sys.argv:
        i = sys.argv.index("--wifi")
        if i + 1 < len(sys.argv):
            tgt = adb_connect(adb, sys.argv[i + 1])
            if tgt and not override:
                override = tgt   # 直接鎖定這台無線裝置

    serial = pick_device(adb, override)
    if not serial:
        print(_t("no_dev") or "[sync] 沒有可用裝置，略過聲音模型同步", flush=True)
        return 0
    if not phone_reachable(adb, serial):
        print(_t("no_resp") or "[sync] 手機無回應，略過（不刪除）", flush=True)
        return 0
    print(_t("start", s=serial, r=PHONE_ROOT) or f"[sync] 裝置 {serial}：開始雙向同步 ↔ {PHONE_ROOT}", flush=True)
    adb_sh(adb, serial, f"mkdir -p '{PHONE_ROOT}'")

    # 自動探索要同步的子資料夾（取代寫死清單，使新增的權重版本/錄音自動納入）
    subdirs = discover_subdirs(adb, serial)
    print(_t("folders", n=len(subdirs), l=', '.join(subdirs)) or f"[sync] 同步資料夾（自動探索 {len(subdirs)}）：{', '.join(subdirs)}", flush=True)

    baseline = load_baseline()
    first_run = baseline is None
    if first_run:
        baseline = set()

    # 收集兩邊清單
    local_all: dict[str, dict] = {}
    phone_all: dict[str, dict] = {}
    for sub in subdirs:
        local_all[sub] = local_inventory(sub)
        phone_all[sub] = phone_inventory(adb, serial, sub)

    # 先算出要刪的清單，套安全閥後再執行（避免連線異常造成大量誤刪）
    del_local: list[tuple[str, str]] = []
    del_phone: list[tuple[str, str]] = []
    copy_push: list[tuple[str, str]] = []
    copy_pull: list[tuple[str, str]] = []
    skipped = 0
    for sub in subdirs:
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
        print(_t("cap", d=total_del, c=cap) or f"[sync] ⚠ 偵測到要刪除 {total_del} 個檔（超過上限 {cap}），疑似異常，本輪不刪除", flush=True)
        del_local, del_phone = [], []

    pushed = sum(1 for s, r in copy_push if push(adb, serial, s, r))
    pulled = sum(1 for s, r in copy_pull if pull(adb, serial, s, r))
    dl = sum(1 for s, r in del_local if local_rm(s, r))
    dp = sum(1 for s, r in del_phone if phone_rm(adb, serial, s, r))

    # 重建基準＝同步後實際存在的檔（兩邊應一致）
    present = set()
    for sub in subdirs:
        local = local_inventory(sub)
        phone = phone_inventory(adb, serial, sub)
        for rel in set(local) | set(phone):
            present.add(f"{sub}/{rel}")
    save_baseline(present)

    _done = _t("done", a=pushed, b=pulled, dl=dl, dp=dp, u=skipped)
    if _done:
        if first_run: _done += (_t("done_first") or "")
        print(_done, flush=True)
    else:
        print(f"[sync] 完成：電腦→手機 {pushed}、手機→電腦 {pulled}、"
              f"刪除(電腦 {dl}/手機 {dp})、未變 {skipped}"
              + ("（首次：只聯集不刪除）" if first_run else ""), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
