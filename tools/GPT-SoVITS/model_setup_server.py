#!/usr/bin/env python3
"""Local GPT-SoVITS model setup page for Speak My Life (formerly VoiceWeaver).

Run from the project root:
    ./tools/GPT-SoVITS/start_model_setup_page.sh

Then open:
    http://127.0.0.1:9890
"""

from __future__ import annotations

import cgi
import hashlib
import html
import json
import unicodedata
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# ── 跨平台 ──────────────────────────────────────────────────────────────
_IS_WIN = (os.name == "nt")
_IS_MAC = (sys.platform == "darwin")
_ADB_BIN = "adb.exe" if _IS_WIN else "adb"
_NOWIN = {"creationflags": 0x08000000} if _IS_WIN else {}   # CREATE_NO_WINDOW


# 凍結版(PyInstaller)時，本檔被解壓到暫存目錄，__file__ 不是真正的安裝根目錄。
# 由控制程式透過 SML_ROOT 指定真正的根（含 user_models / 設定 / 權重）。
ROOT = Path(os.environ.get("SML_ROOT") or Path(__file__).resolve().parent).resolve()
def _migrate_legacy_names():
    """更名搬遷（原名 VoiceWeaver）：舊設定檔 voiceweaver_tts_infer.yaml、舊參考音資料夾
    voiceweaver_custom/ 只有舊的就改名成新的。參考音資料夾在舊位置留捷徑——
    model_history.json 記的是絕對路徑，舊路徑要照樣解得開。改名失敗就沿用舊的。"""
    global CONFIG_PATH, REF_DIR
    try:
        import sml_migrate as _mig
    except Exception:
        return
    try:
        CONFIG_PATH = Path(_mig.migrate_path(
            str(CONFIG_PATH), str(CONFIG_PATH.with_name("voiceweaver_tts_infer.yaml"))))
        REF_DIR = Path(_mig.migrate_path(
            str(REF_DIR), str(REF_DIR.with_name("voiceweaver_custom")), link_old=True))
    except Exception:
        pass


def _derive_paths():
    global PROJECT_ROOT, CONFIG_PATH, REF_DIR, HISTORY_PATH
    try:
        PROJECT_ROOT = ROOT.parents[1]
    except IndexError:
        PROJECT_ROOT = ROOT
    CONFIG_PATH = ROOT / "GPT_SoVITS/configs/speakmylife_tts_infer.yaml"
    REF_DIR = ROOT / "ref_audio/speakmylife_custom"
    _migrate_legacy_names()
    HISTORY_PATH = ROOT / "user_models/model_history.json"
_derive_paths()

def set_root(path) -> None:
    """讓控制程式在啟動/安裝完成後把根目錄指到真正的安裝位置。"""
    global ROOT
    ROOT = Path(path).resolve()
    _derive_paths()
# Windows 用 %TEMP%；macOS 用 /private/tmp（afplay 需要真實路徑，不能用 symlink）
TEST_WAV_PATH = (
    Path(tempfile.gettempdir()) / "speakmylife_model_setup_test.wav"
    if _IS_WIN
    else Path("/private/tmp/speakmylife_model_setup_test.wav")
)
PORT = int(os.environ.get("SML_MODEL_SETUP_PORT", "9890"))
API_BASE = os.environ.get("GPT_SOVITS_API", "http://127.0.0.1:9880").rstrip("/")
SUPPORTED_VERSIONS = {"v1", "v2", "v3", "v4", "v2Pro", "v2ProPlus"}
SAMPLE_STEPS_BY_MODE = {"skip": 0, "turbo": 4, "fast": 8, "balanced": 16, "quality": 32}
MIN_MODEL_BYTES = 1024 * 1024


def safe_filename(name: str, default: str) -> str:
    clean = Path(name or default).name
    clean = re.sub(r"[^A-Za-z0-9._\-一-鿿]+", "_", clean).strip("._")
    return clean or default


def clean_stored_text(value: object) -> str:
    text = str(value or "").strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in {"'", '"'}:
        text = text[1:-1].strip()
    if "\\u" in text:
        try:
            text = text.encode("utf-8").decode("unicode_escape")
        except Exception:
            pass
    return text


def display_model_name(name: str, gpt_rel: str = "", sovits_rel: str = "") -> str:
    raw = clean_stored_text(name)
    if not raw:
        raw = default_model_name(Path(clean_stored_text(gpt_rel)), Path(clean_stored_text(sovits_rel)))
    raw = raw.replace("_", " ").replace("-", " ")
    raw = re.sub(r"\.(ckpt|pth|wav)$", "", raw, flags=re.I)
    raw = re.sub(r"\s*/\s*.*$", "", raw)
    raw = re.sub(r"\b(e\d+|s\d+|l\d+)\b", "", raw, flags=re.I)
    raw = re.sub(r"\s+", " ", raw).strip(" -_/")
    lang = ""
    lang_match = re.search(r"\b(ZH|EN|JP|JA|KO|CN)\b", raw, flags=re.I)
    if lang_match:
        lang = lang_match.group(1).upper().replace("CN", "ZH").replace("JA", "JP")
        raw = re.sub(r"\b(ZH|EN|JP|JA|KO|CN)\b", "", raw, flags=re.I)
    raw = re.sub(r"\s+", " ", raw).strip()
    return f"{raw} {lang}".strip() or "未命名模型"


def normalize_history_record(record: dict) -> dict:
    gpt_rel = clean_stored_text(record.get("gpt_rel", ""))
    sovits_rel = clean_stored_text(record.get("sovits_rel", ""))
    ref_path = clean_stored_text(record.get("ref_path", ""))
    version = clean_stored_text(record.get("version", ""))
    normalized = dict(record)
    normalized["version"] = version
    normalized["gpt_rel"] = gpt_rel
    normalized["sovits_rel"] = sovits_rel
    normalized["ref_path"] = ref_path
    normalized["prompt"] = clean_stored_text(record.get("prompt", ""))
    normalized["name"] = display_model_name(record.get("name", ""), gpt_rel, sovits_rel)
    normalized["id"] = model_id(version, gpt_rel, sovits_rel, Path(ref_path)) if version and gpt_rel and sovits_rel and ref_path else clean_stored_text(record.get("id", ""))
    return normalized


def display_file(value: str) -> str:
    return Path(clean_stored_text(value)).name or clean_stored_text(value)


def ensure_dirs(version: str) -> tuple[Path, Path]:
    gpt_dir = ROOT / f"GPT_weights_{version}"
    sovits_dir = ROOT / f"SoVITS_weights_{version}"
    gpt_dir.mkdir(parents=True, exist_ok=True)
    sovits_dir.mkdir(parents=True, exist_ok=True)
    REF_DIR.mkdir(parents=True, exist_ok=True)
    return gpt_dir, sovits_dir


def detect_lang_from_text(text: str) -> str:
    """依參考文字的文字種類判斷語言，回傳 _KO/_JA/_ZH/_EN 或空字串。
    諺文→韓、假名→日、（無假名的）漢字→中、純拉丁字母→英。"""
    if not text:
        return ""
    if re.search(r"[가-힣]", text):            # 한글 諺文
        return "_KO"
    if re.search(r"[぀-ヿ]", text):            # ひらがな/カタカナ 假名
        return "_JA"
    if re.search(r"[一-鿿]", text):            # 漢字（無假名 → 視為中文）
        return "_ZH"
    if re.search(r"[A-Za-z]", text):                    # 拉丁字母
        return "_EN"
    return ""


def scan_model_folder(folder: Path) -> dict:
    """自動辨識模型資料夾，回傳可帶入表單的欄位。
    結構例：我的聲音_ZH/{我的聲音_ZH-e10.ckpt, 我的聲音_ZH_e10_s860_l32.pth, reference_audios/中文/emotions/【默认】…wav}
    """
    ckpts = sorted(folder.rglob("*.ckpt"), key=lambda p: p.stat().st_size, reverse=True)
    pths = sorted(folder.rglob("*.pth"), key=lambda p: p.stat().st_size, reverse=True)
    wavs = list(folder.rglob("*.wav"))
    if not ckpts:
        return {"ok": False, "error": "資料夾內找不到 GPT 模型 (.ckpt)"}
    if not pths:
        return {"ok": False, "error": "資料夾內找不到 SoVITS 模型 (.pth)"}
    gpt = ckpts[0]
    sovits = pths[0]
    ref = next((w for w in wavs if any(k in w.name for k in ("默认", "默認", "default", "默"))), None) \
          or (wavs[0] if wavs else None)
    prompt = ""
    if ref is not None:
        stem = unicodedata.normalize("NFC", ref.stem)
        stem = re.sub(r"^[【\[〖（(][^】\]〗）)]*[】\]〗）)]\s*", "", stem)
        prompt = stem.strip()
    folder_name = unicodedata.normalize("NFC", folder.name)
    # 語言偵測（由最可靠到最寬鬆）：
    #   ① 參考文字的文字種類（諺文/假名/漢字/拉丁）— 最準
    #   ② 資料夾名稱的明確標籤  ③ 參考音檔路徑的語言子資料夾  ④ 預設中文
    lang = detect_lang_from_text(prompt)
    if not lang:
        for tag in ("_ZH", "_CN", "_EN", "_JA", "_JP", "_KO", "_KR"):
            if tag in folder_name.upper():
                lang = {"_CN": "_ZH", "_JP": "_JA", "_KR": "_KO"}.get(tag, tag)
                break
    if not lang:
        probe = unicodedata.normalize("NFC", str(ref) if ref else (str(wavs[0]) if wavs else ""))
        if   any(k in probe for k in ("日语", "日語", "日文", "japanese")): lang = "_JA"
        elif any(k in probe for k in ("韩语", "韓語", "韓文", "韩文", "korean")): lang = "_KO"
        elif any(k in probe for k in ("英语", "英文", "english")): lang = "_EN"
        elif any(k in probe for k in ("中文", "汉语", "漢語", "chinese")): lang = "_ZH"
    if not lang:
        lang = "_ZH"
    parent = folder.parent.name
    version = parent if parent in SUPPORTED_VERSIONS else "v4"
    return {
        "ok": True,
        "name": folder_name,
        "lang": lang,
        "version": version,
        "gpt_path": str(gpt),
        "sovits_path": str(sovits),
        "ref_path": str(ref) if ref else "",
        "prompt": prompt,
        "test_text": "好。",
    }


def load_history() -> list[dict]:
    if not HISTORY_PATH.exists():
        return []
    raw = HISTORY_PATH.read_text(encoding="utf-8")
    try:
        data = json.loads(raw)
        if not isinstance(data, list):
            data = []
    except Exception:
        # 檔案損壞（例如並行寫入造成多段 JSON 黏接）：盡力救回所有物件
        data = _salvage_history(raw)
        if data:
            try: save_history(data)   # 立刻寫回乾淨版本
            except Exception: pass
    return [normalize_history_record(item) for item in data if isinstance(item, dict)]


def _salvage_history(raw: str) -> list[dict]:
    """從損壞的 JSON 文字盡量逐段抽出 dict 物件，依 id 去重。"""
    dec = json.JSONDecoder()
    raw = raw.lstrip()
    idx = 0
    by_id = {}
    extras = []
    while idx < len(raw):
        try:
            obj, end = dec.raw_decode(raw, idx)
        except json.JSONDecodeError:
            idx += 1
            continue
        if isinstance(obj, dict):
            key = obj.get("id")
            (by_id.__setitem__(key, obj) if key else extras.append(obj))
        idx = end
        while idx < len(raw) and raw[idx] in " \n\t\r,":
            idx += 1
    return list(by_id.values()) + extras


def save_history(records: list[dict]) -> None:
    # 原子寫入：先寫到同目錄的暫存檔，再 os.replace 取代，避免並行/中斷寫入造成檔案損壞
    HISTORY_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(records, ensure_ascii=False, indent=2) + "\n"
    tmp = HISTORY_PATH.with_suffix(HISTORY_PATH.suffix + f".tmp.{os.getpid()}")
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(payload)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, HISTORY_PATH)   # 同檔案系統上是原子操作
    finally:
        try:
            if tmp.exists():
                tmp.unlink()
        except Exception:
            pass


def model_id(version: str, gpt_rel: str, sovits_rel: str, ref_path: Path) -> str:
    raw = f"{version}|{gpt_rel}|{sovits_rel}|{ref_path}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]


def default_model_name(gpt_path: Path, sovits_path: Path) -> str:
    gpt_name = gpt_path.stem.replace("_", " ")
    sovits_name = sovits_path.stem.replace("_", " ")
    if gpt_name and gpt_name in sovits_name:
        return gpt_name
    return f"{gpt_name} / {sovits_name}".strip(" /")


def remember_model(name: str, version: str, gpt_rel: str, sovits_rel: str, ref_path: Path, prompt: str) -> None:
    now = time.strftime("%Y-%m-%d %H:%M:%S")
    records = load_history()
    mid = model_id(version, gpt_rel, sovits_rel, ref_path)
    record = {
        "id": mid,
        "name": display_model_name(name, gpt_rel, sovits_rel),
        "version": version,
        "gpt_rel": gpt_rel,
        "sovits_rel": sovits_rel,
        "ref_path": str(ref_path),
        "prompt": prompt,
        "updated_at": now,
        "last_used_at": now,
    }
    for index, old in enumerate(records):
        if old.get("id") == mid:
            record["created_at"] = old.get("created_at", now)
            records[index] = record
            break
    else:
        record["created_at"] = now
        records.append(record)
    records.sort(key=lambda item: item.get("last_used_at", item.get("updated_at", "")), reverse=True)
    save_history(records)


def get_history_model(mid: str) -> dict:
    model = next((item for item in load_history() if item.get("id") == mid), None)
    if not model:
        raise ValueError("找不到這筆模型記錄")
    return model


def mark_model_used(model: dict) -> None:
    records = load_history()
    now = time.strftime("%Y-%m-%d %H:%M:%S")
    for item in records:
        if item.get("id") == model.get("id"):
            item["last_used_at"] = now
            break
    records.sort(key=lambda item: item.get("last_used_at", item.get("updated_at", "")), reverse=True)
    save_history(records)


def validate_model(version: str, gpt_rel: str, sovits_rel: str, ref_path: Path, prompt: str) -> None:
    if version not in SUPPORTED_VERSIONS:
        raise ValueError("模型記錄版本不支援")
    if not (ROOT / gpt_rel).exists():
        raise ValueError(f"GPT 權重不存在：{gpt_rel}")
    if not (ROOT / sovits_rel).exists():
        raise ValueError(f"SoVITS 權重不存在：{sovits_rel}")
    if not ref_path.exists():
        raise ValueError(f"參考音檔不存在：{ref_path}")
    if not prompt:
        raise ValueError("這筆模型記錄缺少參考音檔逐字稿")


def apply_runtime_model(version: str, gpt_rel: str, sovits_rel: str, ref_path: Path, prompt: str) -> tuple[bool, list[str]]:
    update_config(version, gpt_rel, sovits_rel)
    patch_local_helper(prompt, ref_path)
    swap_logs = api_hot_swap(gpt_rel, sovits_rel)
    return api_matches_target(version, gpt_rel, sovits_rel), swap_logs


def runtime_result(switched: bool) -> str:
    if switched:
        return "Result: API 已載入目標模型"
    return "Result: API 尚未載入目標模型，請先按「中止合成」或重啟 GPT-SoVITS API"


def get_current_config() -> tuple[str, str, str]:
    text = CONFIG_PATH.read_text(encoding="utf-8")
    block = re.search(r"custom:\n(.*?)(?:\n\S|\Z)", text, flags=re.S)
    if not block:
        return "", "", ""
    body = block.group(1)
    version = re.search(r"  version:\s*(.+)", body)
    gpt = re.search(r"  t2s_weights_path:\s*(.+)", body)
    sovits = re.search(r"  vits_weights_path:\s*(.+)", body)
    return (
        clean_stored_text(version.group(1)) if version else "",
        clean_stored_text(gpt.group(1)) if gpt else "",
        clean_stored_text(sovits.group(1)) if sovits else "",
    )


def get_current_ref_and_prompt():
    helper = PROJECT_ROOT / "app/src/main/java/com/example/ai/LocalSovitsHelper.kt"
    if not helper.exists():
        return None, ""
    text = helper.read_text(encoding="utf-8")
    ref = re.search(r'const val MAC_REF_ZH_PATH = "(.*?)"', text)
    prompt = re.search(r'const val PROMPT_ZH = "(.*?)"', text)
    ref_path = Path(ref.group(1)) if ref else None
    prompt_text = prompt.group(1).replace('\\"', '"').replace("\\\\", "\\") if prompt else ""
    return ref_path, prompt_text


def ensure_current_model_in_history() -> None:
    version, gpt_rel, sovits_rel = get_current_config()
    ref_path, prompt = get_current_ref_and_prompt()
    if not version or not gpt_rel or not sovits_rel or not ref_path or not ref_path.exists() or not prompt:
        return
    mid = model_id(version, gpt_rel, sovits_rel, ref_path)
    if any(item.get("id") == mid for item in load_history()):
        return
    name = default_model_name(Path(gpt_rel), Path(sovits_rel)) or "目前模型"
    remember_model(name, version, gpt_rel, sovits_rel, ref_path, prompt)


def save_upload(field, destination: Path) -> int:
    with destination.open("wb") as out:
        shutil.copyfileobj(field.file, out)
    return destination.stat().st_size


def field_has_file(form: cgi.FieldStorage, name: str) -> bool:
    if name not in form:
        return False
    field = form[name]
    return bool(getattr(field, "filename", None))


def copy_upload_or_local_path(form: cgi.FieldStorage, upload_name: str, path_name: str, destination_dir: Path, default_name: str, suffix: str) -> tuple[Path, int, str]:
    if field_has_file(form, upload_name):
        field = form[upload_name]
        filename = safe_filename(field.filename, default_name)
        if not filename.endswith(suffix):
            filename += suffix
        destination = destination_dir / filename
        size = save_upload(field, destination)
        return destination, size, "upload"

    raw_path = form.getfirst(path_name, "").strip()
    if not raw_path:
        raise ValueError(f"請選擇 {suffix} 檔案，或貼上本機檔案路徑")
    source = Path(os.path.expanduser(raw_path)).resolve()
    if not source.exists() or not source.is_file():
        raise ValueError(f"找不到檔案：{source}")
    filename = safe_filename(source.name, default_name)
    if not filename.endswith(suffix):
        raise ValueError(f"檔案副檔名必須是 {suffix}：{source}")
    destination = destination_dir / filename
    if source != destination:
        shutil.copy2(source, destination)
    return destination, destination.stat().st_size, "local path"


def update_config(version: str, gpt_rel: str, sovits_rel: str) -> None:
    text = CONFIG_PATH.read_text(encoding="utf-8")
    # 由控制程式偵測：有支援的 NVIDIA GPU 時走 cuda + 半精度（快很多），否則 cpu。
    device = os.environ.get("SML_DEVICE", "cpu")
    is_half = "true" if device == "cuda" else "false"
    replacement = (
        "custom:\n"
        "  bert_base_path: GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large\n"
        "  cnhuhbert_base_path: GPT_SoVITS/pretrained_models/chinese-hubert-base\n"
        f"  device: {device}\n"
        f"  is_half: {is_half}\n"
        f"  t2s_weights_path: {gpt_rel}\n"
        f"  version: {version}\n"
        f"  vits_weights_path: {sovits_rel}\n"
    )
    updated, count = re.subn(
        r"custom:\n"
        r"  bert_base_path:.*?\n"
        r"  cnhuhbert_base_path:.*?\n"
        r"  device:.*?\n"
        r"  is_half:.*?\n"
        r"  t2s_weights_path:.*?\n"
        r"  version:.*?\n"
        r"  vits_weights_path:.*?\n",
        replacement,
        text,
        count=1,
        flags=re.S,
    )
    if count != 1:
        raise RuntimeError("找不到 speakmylife_tts_infer.yaml 的 custom 區塊")
    CONFIG_PATH.write_text(updated, encoding="utf-8")


def patch_local_helper(prompt: str, ref_path: Path) -> None:
    helper = PROJECT_ROOT / "app/src/main/java/com/example/ai/LocalSovitsHelper.kt"
    if not helper.exists():
        return
    text = helper.read_text(encoding="utf-8")
    escaped_prompt = (
        prompt.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", " ")
        .strip()
    )
    escaped_ref = str(ref_path).replace("\\", "\\\\")
    text = re.sub(
        r'const val MAC_REF_ZH_PATH = ".*?"',
        f'const val MAC_REF_ZH_PATH = "{escaped_ref}"',
        text,
        count=1,
    )
    text = re.sub(
        r'const val PROMPT_ZH = ".*?"',
        f'const val PROMPT_ZH = "{escaped_prompt}"',
        text,
        count=1,
    )
    helper.write_text(text, encoding="utf-8")


def api_get(path: str, timeout: float = 8.0) -> tuple[int, str]:
    try:
        with urllib.request.urlopen(f"{API_BASE}{path}", timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except Exception as exc:
        return 0, str(exc)


def api_health_weights() -> tuple[str, str, str]:
    status, body = api_get("/health", timeout=2.0)
    if not status:
        return "", "", ""
    try:
        data = json.loads(body)
    except Exception:
        return "", "", ""
    return (
        clean_stored_text(data.get("version", "")),
        clean_stored_text(data.get("t2s_weights_path", "")),
        clean_stored_text(data.get("vits_weights_path", "")),
    )


def api_stop_generation() -> tuple[bool, str]:
    status, body = api_get("/stop", timeout=3.0)
    if status == 200:
        return True, f"Stop: HTTP {status} {body}"
    return False, f"Stop: HTTP {status} {body}"


def api_hot_swap(gpt_rel: str, sovits_rel: str) -> list[str]:
    logs: list[str] = []
    stopped, stop_log = api_stop_generation()
    logs.append(stop_log)
    if stopped:
        time.sleep(0.25)
    for label, endpoint, rel in (
        ("SoVITS", "/set_sovits_weights", sovits_rel),
        ("GPT", "/set_gpt_weights", gpt_rel),
    ):
        query = urllib.parse.urlencode({"weights_path": rel})
        status, body = api_get(f"{endpoint}?{query}", timeout=60.0)
        logs.append(f"{label}: HTTP {status} {body}")
    runtime_version, runtime_gpt, runtime_sovits = api_health_weights()
    logs.append(f"Health: version={runtime_version or '-'} GPT={display_file(runtime_gpt) or '-'} SoVITS={display_file(runtime_sovits) or '-'}")
    return logs


def api_matches_target(version: str, gpt_rel: str, sovits_rel: str) -> bool:
    runtime_version, runtime_gpt, runtime_sovits = api_health_weights()
    return runtime_version == version and runtime_gpt == gpt_rel and runtime_sovits == sovits_rel


LANG_TAG_TO_API = {"_ZH": "zh", "_EN": "en", "_JA": "ja", "_KO": "ko"}


def run_tts_test(text: str, prompt: str, ref_path: Path, sample_steps: int, lang_tag: str = "_ZH") -> tuple[bool, str]:
    out = TEST_WAV_PATH
    api_lang = LANG_TAG_TO_API.get(lang_tag, "zh")
    payload = {
        "text": text,
        "text_lang": api_lang,
        "ref_audio_path": str(ref_path),
        "prompt_text": prompt,
        "prompt_lang": api_lang,
        "media_type": "wav",
        "streaming_mode": False,
        "text_split_method": "cut5",
        "batch_size": 1,
        "top_k": 20,
        "top_p": 0.95,
        "temperature": 1.05,
        "repetition_penalty": 1.15,
        "fragment_interval": 0.18,
        "parallel_infer": True,
        "split_bucket": False,
        "sample_steps": sample_steps,
        "speed_factor": 1.02,
    }
    req = urllib.request.Request(
        f"{API_BASE}/tts",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=300.0) as resp:
            data = resp.read()
        out.write_bytes(data)
        if len(data) < 1024:
            return False, data.decode("utf-8", "replace")
        with wave.open(str(out), "rb") as wav:
            info = f"{wav.getnchannels()}ch {wav.getframerate()}Hz {wav.getnframes()} frames"
        return True, f"{out} ({info}, {out.stat().st_size} bytes, sample_steps={sample_steps})"
    except Exception as exc:
        return False, f"{exc}（v4/CPU 若超時，請把測試句縮短，或等 server log 完成後再試）"


def test_wav_status() -> str:
    if not TEST_WAV_PATH.exists():
        return "尚未產生測試音檔"
    size_kb = TEST_WAV_PATH.stat().st_size / 1024
    modified = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(TEST_WAV_PATH.stat().st_mtime))
    return f"{TEST_WAV_PATH} · {size_kb:.1f} KB · {modified}"


def play_test_wav() -> tuple[bool, str]:
    if not TEST_WAV_PATH.exists():
        return False, f"找不到測試音檔：{TEST_WAV_PATH}"
    try:
        if _IS_WIN:
            import winsound
            winsound.PlaySound(str(TEST_WAV_PATH),
                               winsound.SND_FILENAME | winsound.SND_ASYNC)
        elif _IS_MAC:
            subprocess.Popen(["/usr/bin/afplay", str(TEST_WAV_PATH)],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            subprocess.Popen(["aplay", str(TEST_WAV_PATH)],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True, f"正在播放：{TEST_WAV_PATH}"
    except Exception as exc:
        return False, f"播放失敗：{exc}"


def stop_test_wav_playback() -> tuple[bool, str]:
    try:
        if _IS_WIN:
            import winsound
            winsound.PlaySound(None, winsound.SND_PURGE)
            return True, f"已停止測試音檔播放：{TEST_WAV_PATH}"
        killer = ["/usr/bin/pkill", "-f", f"afplay {TEST_WAV_PATH}"] if _IS_MAC \
            else ["pkill", "-f", "aplay"]
        result = subprocess.run(killer, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, check=False)
        if result.returncode in {0, 1}:
            return True, f"已停止測試音檔播放：{TEST_WAV_PATH}"
        return False, f"停止播放失敗：pkill exit {result.returncode}"
    except Exception as exc:
        return False, f"停止播放失敗：{exc}"


def reveal_test_wav() -> tuple[bool, str]:
    if not TEST_WAV_PATH.exists():
        return False, f"找不到測試音檔：{TEST_WAV_PATH}"
    try:
        if _IS_WIN:
            subprocess.Popen(["explorer", "/select,", str(TEST_WAV_PATH)], **_NOWIN)
            return True, f"已在檔案總管顯示：{TEST_WAV_PATH}"
        opener = ["/usr/bin/open", "-R"] if _IS_MAC else ["xdg-open"]
        target = [str(TEST_WAV_PATH)] if _IS_MAC else [str(TEST_WAV_PATH.parent)]
        subprocess.Popen(opener + target,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True, f"已顯示：{TEST_WAV_PATH}"
    except Exception as exc:
        return False, f"開啟檔案總管失敗：{exc}"


def format_health(status: int, body: str) -> str:
    if not status:
        return f"未連線：{body}"
    try:
        data = json.loads(body)
        version = clean_stored_text(data.get("version", ""))
        device = clean_stored_text(data.get("device", ""))
        gpt = display_file(clean_stored_text(data.get("t2s_weights_path", "")))
        sovits = display_file(clean_stored_text(data.get("vits_weights_path", "")))
        parts = [f"已連線 HTTP {status}"]
        if version:
            parts.append(f"版本 {version}")
        if device:
            parts.append(f"裝置 {device}")
        if gpt:
            parts.append(f"GPT {gpt}")
        if sovits:
            parts.append(f"SoVITS {sovits}")
        return " · ".join(parts)
    except Exception:
        return f"HTTP {status} {body}"


if _IS_WIN:
    ADB_CANDIDATES = [
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
        r"C:\platform-tools\adb.exe",
    ]
else:
    ADB_CANDIDATES = [
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
    ]


def find_adb() -> str:
    for c in ADB_CANDIDATES:
        if os.path.exists(c):
            return c
    return shutil.which(_ADB_BIN) or ""


def lan_ips() -> list[str]:
    ips: list[str] = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        finally:
            s.close()
        if ip and not ip.startswith("127."):
            ips.append(ip)
    except Exception:
        pass
    if not _IS_WIN:
        for iface in ("en0", "en1", "en2"):
            try:
                r = subprocess.run(["ipconfig", "getifaddr", iface],
                                   capture_output=True, text=True, timeout=2)
                ip = r.stdout.strip()
                if ip and ip not in ips:
                    ips.append(ip)
            except Exception:
                pass
    return ips


TAILSCALE_DOWNLOAD_URL = "https://tailscale.com/download"


def find_tailscale() -> str:
    cands = (
        ("tailscale.exe",
         os.path.expandvars(r"%ProgramFiles%\Tailscale\tailscale.exe"))
        if _IS_WIN else
        ("/Applications/Tailscale.app/Contents/MacOS/Tailscale",
         "/opt/homebrew/bin/tailscale",
         "/usr/local/bin/tailscale",
         "tailscale")
    )
    for c in cands:
        try:
            if subprocess.run([c, "version"], capture_output=True, text=True,
                              timeout=3, **_NOWIN).returncode == 0:
                return c
        except Exception:
            pass
    return ""


def tailscale_status() -> dict:
    ts = find_tailscale()
    if not ts:
        return {"installed": False, "running": False, "ip": "", "download_url": TAILSCALE_DOWNLOAD_URL}
    ip = ""
    try:
        r = subprocess.run([ts, "ip", "-4"], capture_output=True, text=True, timeout=3)
        lines = [ln.strip() for ln in r.stdout.splitlines() if ln.strip()]
        ip = lines[0] if lines else ""
    except Exception:
        ip = ""
    return {"installed": True, "running": bool(ip), "ip": ip, "download_url": TAILSCALE_DOWNLOAD_URL}


def adb_devices(adb: str) -> list[str]:
    try:
        dev = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=4)
        return [ln.split("\t")[0] for ln in dev.stdout.splitlines()[1:] if "\tdevice" in ln]
    except Exception:
        return []


def adb_status() -> dict:
    adb = find_adb()
    if not adb:
        return {"adb": False, "devices": [], "reverse": False}
    devices = adb_devices(adb)
    reverse = False
    for d in devices:
        try:
            rl = subprocess.run([adb, "-s", d, "reverse", "--list"], capture_output=True, text=True, timeout=4)
            if "tcp:9880" in rl.stdout:
                reverse = True
                break
        except Exception:
            pass
    return {"adb": True, "devices": devices, "reverse": reverse}


def setup_adb_reverse() -> tuple[bool, str]:
    adb = find_adb()
    if not adb:
        return False, "找不到 adb（請確認已安裝 Android platform-tools）"
    devices = adb_devices(adb)
    if not devices:
        return False, "沒有偵測到已授權的手機（USB 或無線 adb）"
    ok_list: list[str] = []
    for d in devices:
        try:
            out = subprocess.run([adb, "-s", d, "reverse", "tcp:9880", "tcp:9880"], capture_output=True, text=True, timeout=6)
            if out.returncode == 0:
                ok_list.append(d)
        except Exception:
            pass
    if ok_list:
        return True, f"已轉接 {len(ok_list)} 個 transport → localhost:9880：{', '.join(ok_list)}"
    return False, "adb reverse 全部失敗，請重插線或重新授權"


def sync_voice_models_action() -> tuple[bool, str]:
    script = ROOT / "sync_voice_models.py"
    if not script.exists():
        return False, "找不到 sync_voice_models.py"
    try:
        r = subprocess.run([sys.executable, str(script)],
                           capture_output=True, text=True, timeout=1800)
    except Exception as exc:  # noqa: BLE001
        return False, f"同步執行失敗：{exc}"
    tail = [ln for ln in r.stdout.splitlines() if ln.strip()]
    summary = tail[-1] if tail else "（無輸出）"
    return (r.returncode == 0), summary


def image_server_status() -> dict:
    try:
        with urllib.request.urlopen("http://127.0.0.1:9881/health", timeout=2.0) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
        return {
            "up": True,
            "ready": bool(data.get("ready")),
            "generating": bool(data.get("generating")),
            "busy": int(data.get("busy", 0)),
            "done": int(data.get("done", 0)),
            "model": clean_stored_text(data.get("model", "")),
            "last_prompt": clean_stored_text(data.get("last_prompt", "")),
        }
    except Exception:
        return {"up": False, "ready": False, "generating": False, "busy": 0, "done": 0, "model": "", "last_prompt": ""}


def text_server_status() -> dict:
    try:
        with urllib.request.urlopen("http://127.0.0.1:9882/health", timeout=2.0) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
        return {
            "up": True,
            "ready": bool(data.get("ready")),
            "model": clean_stored_text(data.get("model", "")),
        }
    except Exception:
        return {"up": False, "ready": False, "model": ""}


def conn_state() -> dict:
    health_status, health_body = api_get("/health", timeout=2.0)
    return {
        "api": bool(health_status),
        "api_text": format_health(health_status, health_body),
        "lan": lan_ips(),
        "tailscale": tailscale_status(),
        "adb": adb_status(),
        "image": image_server_status(),
        "text": text_server_status(),
    }


# ── 網頁控制中心已移除（2026-06-20）────────────────────────────────────────
# 本檔現在只當「後端函式庫」供 voice_center_app.py import 重用。
if __name__ == "__main__":
    print("此檔已改為後端函式庫；請改用拾語者語音中心 App（voice_center_app.py）。")
