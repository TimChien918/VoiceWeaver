#!/usr/bin/env python3
"""情緒感知語音模型：共用掃描器 + 情緒偵測。

api_v2.py（/models）與 voice_center_app.py（web 橋接合成）共用這支，
與 Colab 端 voiceweaver_bridge.py 同一套邏輯（換參考音＝換語氣）。

資料夾結構（依語言分，每角色一組情緒參考音）：
    <root>/Models/<LANG>/<角色>/
        *.ckpt                                    GPT 權重
        *.pth                                     SoVITS 權重
        reference_audios/**/emotions/【情緒】逐字稿.wav   多顆，一情緒一顆
    <root>/AcousticModels/<uid>/<樣板id>.json     聲波模型（專屬發音的關鍵字樣板）
    <root>/catalog.json                           曲庫索引（路徑一律相對 <root>）

**Models/ 這一層是後來加的。** 舊版把 ZH/EN/JA/KO 直接放在 <root> 底下，
但 <root> 後來還要放聲波模型，語音模型跟聲波模型混在同一層會看不出誰是誰。
[models_root] 負責這件事的相容：有 Models/ 就用，沒有就當舊版直接用 <root>——
所以既有使用者不搬家也不會壞，搬了也照樣運作。

情緒根目錄：環境變數 VOICEWEAVER_EMOTION_ROOT（沒設就沒有情緒模型，呼叫端退回舊 model_history）。
"""
import json
import os
import re
import time

NEUTRAL = "中立"
CATALOG_NAME = "catalog.json"   # 雲端曲庫目錄索引（放在曲庫根目錄）
LANG_DIRS = {"ZH", "EN", "JA", "KO"}

MODELS_DIR = "Models"            # 語音模型（GPT-SoVITS 聲文權重＋參考音）
ACOUSTIC_DIR = "AcousticModels"  # 聲波模型（專屬發音的 MFCC 樣板）

EMOTION_ALIASES = {
    "中立": ["中立", "平静", "平靜", "neutral"],
    "开心": ["开心", "開心", "高兴", "高興", "快乐", "快樂", "喜悦", "happy"],
    "难过": ["难过", "難過", "伤心", "傷心", "难受", "難受", "sad"],
    "恐惧": ["恐惧", "恐懼", "害怕", "惊恐", "驚恐", "fear"],
    "吃惊": ["吃惊", "吃驚", "惊讶", "驚訝", "惊喜", "驚喜", "surprise"],
}
_LABEL_TO_CANON = {a: c for c, al in EMOTION_ALIASES.items() for a in al}

_EMOTION_KEYWORDS = {
    "开心": ["開心", "开心", "高興", "高兴", "快樂", "快乐", "太好", "好耶", "謝謝", "谢谢", "感謝", "感谢",
            "喜歡", "喜欢", "愛你", "爱你", "棒", "讚", "赞", "哈哈", "耶", "幸福", "舒服"],
    "难过": ["難過", "难过", "傷心", "伤心", "想哭", "好累", "不想", "唉", "失望", "孤單", "孤单", "難受",
            "难受", "委屈", "痛"],
    "恐惧": ["害怕", "好怕", "救命", "危險", "危险", "不敢", "恐怖", "嚇", "吓", "緊張", "紧张", "求救"],
    "吃惊": ["什麼", "什么", "怎麼會", "怎么会", "天啊", "居然", "竟然", "哇", "真的假的", "不會吧", "不会吧",
            "太扯", "驚", "惊"],
}


def canon_emotion(label):
    return _LABEL_TO_CANON.get((label or "").strip(), (label or "").strip() or NEUTRAL)


def detect_emotion(text):
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


def parse_ref_filename(fname):
    """『【情緒】逐字稿.wav』→ (canon情緒, 逐字稿)。無【】→ (中立, 檔名去副檔名)。"""
    stem = os.path.splitext(fname)[0]
    m = re.match(r"^\s*[【\[]\s*([^】\]]+?)\s*[】\]]\s*(.*)$", stem)
    if m:
        return canon_emotion(m.group(1)), m.group(2).strip()
    return NEUTRAL, stem.strip()


def emotion_root():
    return (os.environ.get("VOICEWEAVER_EMOTION_ROOT") or "").rstrip("/")


def models_root(root=None):
    """語音模型實際所在的資料夾。

    優先序：已經有 <root>/Models 就用它；否則若是舊版面（語言資料夾直接躺在
    <root>）就沿用 <root>；兩者皆非（全新或空的曲庫）一律回 <root>/Models，
    **即使它還不存在**——新資料要落在新版面，不然第一次下載就又長回舊版面。

    唯一的分層判斷點。掃描、健檢、建索引、隨選下載全都走這裡，
    才不會出現「某一支看新路徑、另一支看舊路徑」的半搬家狀態。
    """
    root = root if root is not None else emotion_root()
    if not root:
        return ""
    sub = os.path.join(root, MODELS_DIR)
    if os.path.isdir(sub):
        return sub
    if is_legacy_layout(root):
        return root
    return sub


def acoustic_root(root=None, create=False):
    """聲波模型資料夾 <root>/AcousticModels。[create] 為真時缺了就建。"""
    root = root if root is not None else emotion_root()
    if not root:
        return ""
    d = os.path.join(root, ACOUSTIC_DIR)
    if create:
        os.makedirs(d, exist_ok=True)
    return d


def is_legacy_layout(root=None):
    """舊版版面：語言資料夾直接躺在 <root>，還沒有 Models/。"""
    root = root if root is not None else emotion_root()
    if not root or not os.path.isdir(root):
        return False
    if os.path.isdir(os.path.join(root, MODELS_DIR)):
        return False
    return any(os.path.isdir(os.path.join(root, l)) for l in LANG_DIRS)


def migrate_layout(root=None):
    """把舊版 <root>/<LANG> 搬進 <root>/Models/<LANG>。回 (搬了幾個, 錯誤訊息)。

    同一個檔案系統內是改目錄項目，不是複製——曲庫幾十 GB 也是瞬間完成。
    已經是新版面就直接回 (0, "")，重複呼叫安全。
    """
    root = root if root is not None else emotion_root()
    if not is_legacy_layout(root):
        return 0, ""
    dest = os.path.join(root, MODELS_DIR)
    try:
        os.makedirs(dest, exist_ok=True)
        moved = 0
        for lang in sorted(LANG_DIRS):
            src = os.path.join(root, lang)
            if not os.path.isdir(src):
                continue
            dst = os.path.join(dest, lang)
            if os.path.isdir(dst):
                # 兩邊都有：不覆蓋，把舊的內容併進去，衝突的留在原地讓使用者自己看
                for name in os.listdir(src):
                    s, d = os.path.join(src, name), os.path.join(dst, name)
                    if not os.path.exists(d):
                        os.rename(s, d)
                if not os.listdir(src):
                    os.rmdir(src)
            else:
                os.rename(src, dst)
            moved += 1
        return moved, ""
    except OSError as e:
        return 0, str(e)


def scan_emotion_models(root=None):
    """掃 <root>/Models/<LANG>/<角色>/ → [{name, character, lang, gpt, sovits, emotions{canon:{ref,prompt}}}]。"""
    root = models_root(root or emotion_root())
    out = []
    if not root or not os.path.isdir(root):
        return out
    for lang in sorted(os.listdir(root)):
        ldir = os.path.join(root, lang)
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
            emotions = {}
            for r, _d, files in os.walk(os.path.join(cdir, "reference_audios")):
                for f in files:
                    if not f.lower().endswith((".wav", ".mp3", ".flac")):
                        continue
                    emo, prompt = parse_ref_filename(f)
                    if emo not in emotions:
                        emotions[emo] = {"ref": os.path.join(r, f), "prompt": prompt}
            if not emotions:
                continue
            out.append({"name": f"{char} {LANG}", "character": char, "lang": LANG,
                        "gpt": gpt, "sovits": sovits, "emotions": emotions})
    return out


def pick_ref(model, emotion):
    """挑角色在指定情緒的參考音；沒有退中立、再退任一。回 (canon情緒, {ref,prompt}) 或 (None, None)。"""
    emo = canon_emotion(emotion) if emotion else None
    ems = model.get("emotions", {})
    for key in (emo, NEUTRAL):
        if key and key in ems:
            return key, ems[key]
    if ems:
        k = next(iter(ems))
        return k, ems[k]
    return None, None


def scan_report(root=None):
    """健檢 <root>：逐角色列出缺權重／缺參考音／檔名沒【情緒】等問題。

    回 {root, exists, langs, characters:[{lang,character,ok,gpt,sovits,
        emotions[],untagged[],issues[]}], summary{characters,ready,issues}}。
    與 scan_emotion_models 不同：這支「連壞掉的角色也列出來」，方便提示使用者怎麼修。
    """
    root = models_root(root or emotion_root())
    rep = {"root": root, "exists": bool(root) and os.path.isdir(root),
           "langs": [], "characters": [],
           "summary": {"characters": 0, "ready": 0, "issues": 0}}
    if not rep["exists"]:
        return rep
    for lang in sorted(os.listdir(root)):
        ldir = os.path.join(root, lang)
        if lang.upper() not in LANG_DIRS or not os.path.isdir(ldir):
            continue
        LANG = lang.upper()
        rep["langs"].append(LANG)
        for char in sorted(os.listdir(ldir)):
            cdir = os.path.join(ldir, char)
            if not os.path.isdir(cdir):
                continue
            gpt = sovits = None
            for f in os.listdir(cdir):
                if f.lower().endswith(".ckpt"):
                    gpt = f
                elif f.lower().endswith(".pth"):
                    sovits = f
            emotions, untagged, issues = [], [], []
            refdir = os.path.join(cdir, "reference_audios")
            n_refs = 0
            for r, _d, files in os.walk(refdir):
                for f in files:
                    if not f.lower().endswith((".wav", ".mp3", ".flac")):
                        continue
                    n_refs += 1
                    emo, _prompt = parse_ref_filename(f)
                    if emo not in emotions:
                        emotions.append(emo)
                    stem = os.path.splitext(f)[0]
                    if not re.match(r"^\s*[【\[]", stem):
                        untagged.append(f)   # 沒【情緒】前綴 → 一律當中立，提醒使用者補
            if not gpt:
                issues.append("缺 GPT 權重（*.ckpt）")
            if not sovits:
                issues.append("缺 SoVITS 權重（*.pth）")
            if n_refs == 0:
                issues.append("reference_audios 底下沒有任何參考音（*.wav/mp3/flac）")
            elif untagged:
                issues.append(f"{len(untagged)} 個參考音檔名沒有【情緒】前綴，會被當中立")
            ok = bool(gpt and sovits and n_refs > 0)
            rep["characters"].append({
                "lang": LANG, "character": char, "ok": ok,
                "gpt": gpt or "", "sovits": sovits or "",
                "emotions": sorted(emotions), "untagged": untagged, "issues": issues})
            rep["summary"]["characters"] += 1
            rep["summary"]["ready"] += 1 if ok else 0
            rep["summary"]["issues"] += len(issues)
    return rep


def format_report(rep):
    """把 scan_report 的結果轉成好讀的多行文字（給 notebook / 控制中心印）。"""
    if not rep.get("root"):
        return "（未設定情緒資料夾 VOICEWEAVER_EMOTION_ROOT；改用舊 model_history）"
    if not rep.get("exists"):
        return f"❌ 找不到資料夾：{rep['root']}"
    s = rep["summary"]
    lines = [f"📂 {rep['root']}",
             f"   語言 {rep['langs'] or '（無）'}；角色 {s['characters']} 個，"
             f"可用 {s['ready']}，問題 {s['issues']} 則"]
    for c in rep["characters"]:
        mark = "✅" if c["ok"] else "⚠️"
        emos = "／".join(c["emotions"]) if c["emotions"] else "（無）"
        lines.append(f"   {mark} {c['lang']}/{c['character']}：情緒 {emos}")
        for it in c["issues"]:
            lines.append(f"        • {it}")
    if s["characters"] == 0:
        lines.append("   （還沒有任何角色資料夾；把 Models/<語言>/<角色>/ 放進來即可）")
    return "\n".join(lines)


def ensure_template(root=None):
    """建好 <root>/Models/{ZH,EN,JA,KO} 與 <root>/AcousticModels（缺才建）。回新建的語言清單。

    舊版面（語言直接在 <root>）不動它——那是使用者已經放好東西的地方，
    在旁邊多長一個空的 Models/ 只會讓人搞不清楚該把檔案放哪一邊。
    要換新版面請走 [migrate_layout]。
    """
    root = root or emotion_root()
    if not root:
        return []
    acoustic_root(root, create=True)
    base = root if is_legacy_layout(root) else os.path.join(root, MODELS_DIR)
    created = []
    for lang in ("ZH", "EN", "JA", "KO"):
        d = os.path.join(base, lang)
        if not os.path.isdir(d):
            os.makedirs(d, exist_ok=True)
            created.append(lang)
    return created


# ── 雲端曲庫目錄索引（catalog.json）──────────────────────────
# 「像 Apple Music 的曲庫清單」：Mac 與 Colab 兩個掃描端都產出同一份。
# 路徑一律「相對曲庫根」，所以哪一端寫的都一樣、可互換（隨選下載/上傳都靠它）。

def _size(path):
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


def build_catalog(root=None):
    """掃曲庫根 → 目錄索引 dict（含每角色的權重/參考音相對路徑與大小）。

    回 {version, updatedAt(ms), characters:[{name,character,lang,ok,emotions[],
        gpt{path,size}, sovits{path,size}, refs:[{path,emotion,prompt,size}], bytes}],
        summary{characters,ready,bytes}}。
    """
    root = root or emotion_root()
    cat = {"version": 1, "updatedAt": int(time.time() * 1000),
           "characters": [], "summary": {"characters": 0, "ready": 0, "bytes": 0}}
    # 掃描在 Models/（或舊版面的 <root>），但路徑一律相對「曲庫根」——
    # 消費端只會 open(<root>/<rel>)，兩種版面產出的索引都直接可用。
    mroot = models_root(root)
    if not root or not os.path.isdir(mroot):
        return cat
    for lang in sorted(os.listdir(mroot)):
        ldir = os.path.join(mroot, lang)
        if lang.upper() not in LANG_DIRS or not os.path.isdir(ldir):
            continue
        LANG = lang.upper()
        for char in sorted(os.listdir(ldir)):
            cdir = os.path.join(ldir, char)
            if not os.path.isdir(cdir):
                continue
            gpt = sovits = None
            for f in os.listdir(cdir):
                ap = os.path.join(cdir, f)
                if f.lower().endswith(".ckpt"):
                    gpt = {"path": os.path.relpath(ap, root), "size": _size(ap)}
                elif f.lower().endswith(".pth"):
                    sovits = {"path": os.path.relpath(ap, root), "size": _size(ap)}
            refs, emotions = [], []
            for r, _d, files in os.walk(os.path.join(cdir, "reference_audios")):
                for f in sorted(files):
                    if not f.lower().endswith((".wav", ".mp3", ".flac")):
                        continue
                    ap = os.path.join(r, f)
                    emo, prompt = parse_ref_filename(f)
                    if emo not in emotions:
                        emotions.append(emo)
                    refs.append({"path": os.path.relpath(ap, root), "emotion": emo,
                                 "prompt": prompt, "size": _size(ap)})
            ok = bool(gpt and sovits and refs)
            b = (gpt or {}).get("size", 0) + (sovits or {}).get("size", 0) + sum(r["size"] for r in refs)
            cat["characters"].append({
                "name": f"{char} {LANG}", "character": char, "lang": LANG, "ok": ok,
                "emotions": sorted(emotions), "gpt": gpt, "sovits": sovits,
                "refs": refs, "bytes": b})
            cat["summary"]["characters"] += 1
            cat["summary"]["ready"] += 1 if ok else 0
            cat["summary"]["bytes"] += b
    return cat


def write_catalog(root=None):
    """把目錄索引寫進 <root>/catalog.json。回 (catalog, 路徑) 或 (None, "")。"""
    root = root or emotion_root()
    if not root or not os.path.isdir(root):
        return None, ""
    cat = build_catalog(root)
    path = os.path.join(root, CATALOG_NAME)
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(cat, f, ensure_ascii=False, indent=1)
    except OSError:
        return cat, ""
    return cat, path


def load_catalog(root=None):
    """讀 <root>/catalog.json；沒有就回 None（呼叫端可改 build_catalog 即時掃）。"""
    root = root or emotion_root()
    if not root:
        return None
    try:
        with open(os.path.join(root, CATALOG_NAME), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def prefetch(root=None, character=None, lang=None):
    """讀取某角色的權重與參考音，強制把 Drive 串流檔案materialize到運算端本地。

    隨選下載的核心：Colab 掛載 / Mac Drive for Desktop 都是「用到才下載」，
    這支主動整支讀一遍就等於把該角色先抓下來。回 {ok, character, lang, bytes, files}。
    """
    root = root or emotion_root()
    res = {"ok": False, "character": character, "lang": (lang or "").upper(), "bytes": 0, "files": 0}
    if not root or not os.path.isdir(root) or not character:
        return res
    want = (lang or "").upper()
    target = None
    for c in build_catalog(root)["characters"]:
        if c["character"] == character or c["name"] == character:
            if not want or c["lang"] == want:
                target = c
                break
    if not target:
        return res
    res["lang"] = target["lang"]
    rels = []
    if target.get("gpt"):
        rels.append(target["gpt"]["path"])
    if target.get("sovits"):
        rels.append(target["sovits"]["path"])
    rels += [r["path"] for r in target.get("refs", [])]
    total = 0
    for rel in rels:
        try:
            with open(os.path.join(root, rel), "rb") as f:
                while True:
                    chunk = f.read(1024 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
            res["files"] += 1
        except OSError:
            pass
    res["bytes"] = total
    res["ok"] = res["files"] > 0
    return res


if __name__ == "__main__":   # 快速自測：印健檢報告
    print(format_report(scan_report()))
