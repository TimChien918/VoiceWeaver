#!/usr/bin/env python3
"""Local GPT-SoVITS model setup page for VoiceWeaver.

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


ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = ROOT.parents[1]
CONFIG_PATH = ROOT / "GPT_SoVITS/configs/voiceweaver_tts_infer.yaml"
REF_DIR = ROOT / "ref_audio/voiceweaver_custom"
HISTORY_PATH = ROOT / "user_models/model_history.json"
TEST_WAV_PATH = Path("/private/tmp/voiceweaver_model_setup_test.wav")
PORT = int(os.environ.get("VOICEWEAVER_MODEL_SETUP_PORT", "9890"))
API_BASE = os.environ.get("GPT_SOVITS_API", "http://127.0.0.1:9880").rstrip("/")
SUPPORTED_VERSIONS = {"v1", "v2", "v3", "v4", "v2Pro", "v2ProPlus"}
SAMPLE_STEPS_BY_MODE = {"skip": 0, "turbo": 4, "fast": 8, "balanced": 16, "quality": 32}
MIN_MODEL_BYTES = 1024 * 1024


def safe_filename(name: str, default: str) -> str:
    clean = Path(name or default).name
    clean = re.sub(r"[^A-Za-z0-9._\-\u4e00-\u9fff]+", "_", clean).strip("._")
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


def scan_model_folder(folder: Path) -> dict:
    """自動辨識模型資料夾，回傳可帶入表單的欄位。
    結構例：胡桃_ZH/{胡桃_ZH-e10.ckpt, 胡桃_ZH_e10_s860_l32.pth, reference_audios/中文/emotions/【默认】…wav}
    """
    # GPT .ckpt / SoVITS .pth：取資料夾內最大的（排除 train log 等）
    ckpts = sorted(folder.rglob("*.ckpt"), key=lambda p: p.stat().st_size, reverse=True)
    pths = sorted(folder.rglob("*.pth"), key=lambda p: p.stat().st_size, reverse=True)
    wavs = list(folder.rglob("*.wav"))
    if not ckpts:
        return {"ok": False, "error": "資料夾內找不到 GPT 模型 (.ckpt)"}
    if not pths:
        return {"ok": False, "error": "資料夾內找不到 SoVITS 模型 (.pth)"}
    gpt = ckpts[0]
    sovits = pths[0]
    # 參考音檔：優先「默认/默認/default」，否則第一個
    ref = next((w for w in wavs if any(k in w.name for k in ("默认", "默認", "default", "默"))), None) \
          or (wavs[0] if wavs else None)
    # 逐字稿：從 wav 檔名取，去掉副檔名與開頭的【情緒】標記。
    # macOS 檔名是分解形(NFD)，韓/日會被拆成 jamo/組字 → 正規化成 NFC 才正確顯示與合成。
    prompt = ""
    if ref is not None:
        stem = unicodedata.normalize("NFC", ref.stem)
        stem = re.sub(r"^[【\[〖（(][^】\]〗）)]*[】\]〗）)]\s*", "", stem)  # 去開頭【默认】等
        prompt = stem.strip()
    # 名稱與語言：資料夾名（如 胡桃_ZH），NFC 正規化；版本：上層資料夾名（v4…）
    folder_name = unicodedata.normalize("NFC", folder.name)
    lang = "_ZH"
    for tag in ("_ZH", "_CN", "_EN", "_JA", "_JP", "_KO", "_KR"):
        if tag in folder_name.upper():
            lang = {"_CN": "_ZH", "_JP": "_JA", "_KR": "_KO"}.get(tag, tag)
            break
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
    try:
        data = json.loads(HISTORY_PATH.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            return []
        return [normalize_history_record(item) for item in data if isinstance(item, dict)]
    except Exception:
        return []


def save_history(records: list[dict]) -> None:
    HISTORY_PATH.parent.mkdir(parents=True, exist_ok=True)
    HISTORY_PATH.write_text(
        json.dumps(records, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


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


def history_html() -> str:
    ensure_current_model_in_history()
    records = load_history()
    runtime_version, runtime_gpt, runtime_sovits = api_health_weights()
    config_version, config_gpt, config_sovits = get_current_config()
    cur_version = runtime_version or config_version
    cur_gpt = runtime_gpt or config_gpt
    cur_sovits = runtime_sovits or config_sovits
    if not records:
        return """
  <div class="panel">
    <b>已使用模型</b>
    <p>還沒有記錄。第一次套用模型成功後，這裡會自動出現一鍵切換清單。</p>
  </div>"""
    rows: list[str] = []
    for item in records:
        gpt_rel = clean_stored_text(item.get("gpt_rel", ""))
        sovits_rel = clean_stored_text(item.get("sovits_rel", ""))
        ref_path = clean_stored_text(item.get("ref_path", ""))
        active = (
            item.get("version") == cur_version
            and gpt_rel == cur_gpt
            and sovits_rel == cur_sovits
        )
        badge = '<span class="badge active">API 使用中</span>' if active and runtime_gpt else ('<span class="badge active">設定中</span>' if active else '<span class="badge">已記住</span>')
        rows.append(
            f"""
      <div class="model-card">
        <div style="flex:1">
          <div class="model-title">{html.escape(item.get("name", "未命名模型"))} {badge}</div>
          <div class="model-meta">版本：{html.escape(item.get("version", ""))}</div>
          <div class="model-meta">GPT：<code>{html.escape(display_file(gpt_rel))}</code></div>
          <div class="model-meta">SoVITS：<code>{html.escape(display_file(sovits_rel))}</code></div>
          <div class="model-meta">參考音檔：<code>{html.escape(display_file(ref_path))}</code></div>
          <div class="model-meta">上次使用：{html.escape(item.get("last_used_at", item.get("updated_at", "")))}</div>
          <details class="edit-box">
            <summary>編輯</summary>
            <form method="post" action="/edit">
              <input type="hidden" name="id" value="{html.escape(item.get("id", ""))}" />
              <label>模型名稱</label>
              <input name="name" value="{html.escape(item.get("name", ""))}" />
              <label>參考音檔逐字稿（prompt）</label>
              <textarea name="prompt" rows="2">{html.escape(clean_stored_text(item.get("prompt", "")))}</textarea>
              <button type="submit">儲存</button>
            </form>
          </details>
        </div>
        <div class="card-actions">
          <form class="inline-form" method="post" action="/switch">
            <input type="hidden" name="id" value="{html.escape(item.get("id", ""))}" />
            <button type="submit" {'disabled' if active else ''}>切換</button>
          </form>
          <form class="inline-form" method="post" action="/delete" onsubmit="return confirm('確定刪除？會一併刪除權重檔 .ckpt/.pth/.wav（無法復原）。其他模型仍在用的共用檔會自動保留。');">
            <input type="hidden" name="id" value="{html.escape(item.get("id", ""))}" />
            <button type="submit" class="danger">刪除</button>
          </form>
        </div>
      </div>"""
        )
    return f"""
  <div class="panel">
    <b>已使用模型</b>
    <p>套用成功的模型會自動記住。之後按「切換」即可更新設定與 GPT-SoVITS API，不需要重新選檔。</p>
    <div class="model-list">{''.join(rows)}</div>
  </div>"""


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
        raise ValueError(f"請選擇 {suffix} 檔案，或貼上 Mac 本機檔案路徑")
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
    replacement = (
        "custom:\n"
        "  bert_base_path: GPT_SoVITS/pretrained_models/chinese-roberta-wwm-ext-large\n"
        "  cnhuhbert_base_path: GPT_SoVITS/pretrained_models/chinese-hubert-base\n"
        "  device: cpu\n"
        "  is_half: false\n"
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
        raise RuntimeError("找不到 voiceweaver_tts_infer.yaml 的 custom 區塊")
    CONFIG_PATH.write_text(updated, encoding="utf-8")


def patch_local_helper(prompt: str, ref_path: Path) -> None:
    helper = PROJECT_ROOT / "app/src/main/java/com/example/ai/LocalSovitsHelper.kt"
    if not helper.exists():
        # 發行版（.dmg 安裝）沒有 Android 原始碼，跳過即可
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
            subprocess.Popen(["explorer", "/select,", str(TEST_WAV_PATH)])
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
    # 跨平台：UDP「假連線」取對外網卡 IP。
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
    if not _IS_WIN:                          # mac/linux 後援列舉介面
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
    """找 Tailscale CLI（Windows / macOS App Store / 獨立版 / Homebrew 版）。"""
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
    """Tailscale 狀態：讓手機可不靠區網、用全域網（4G/5G/外網）連回電腦。
    回傳是否安裝、是否登入(取得 100.x IP)、IP、官方下載網址。"""
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
    # 同一支手機可能同時有 USB + 無線兩個 transport，必須用 -s 指定，否則 adb 會報多裝置錯誤
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
    """有線/無線 adb：對每個 transport 都設定 reverse 9880，手機用 localhost:9880 連回 Mac。"""
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
    """手動觸發聲音模型雙向同步（電腦 ↔ 手機固定資料夾）。"""
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
    """本機生圖伺服器(9881)狀態，給控制中心顯示「合成進度」。"""
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
    """本機文字推論伺服器(9882)狀態：連線夠快時電腦接手句子重組。"""
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
# 原本這裡有 page()/Handler/main()，會在 9890 提供網頁。功能已全部搬進原生
# App（voice_center_app.py 的「模型 / 進階」分頁）。本檔現在只當「後端函式庫」
# 供 App import 重用（load_history / scan_model_folder / apply_runtime_model /
# run_tts_test / play_test_wav / setup_adb_reverse / conn_state … ）。
# 直接執行本檔不會再啟動任何網頁伺服器。
if __name__ == "__main__":
    print("此檔已改為後端函式庫；網頁控制中心已移除，請改用 VoiceWeaver 語音中心 App。")
