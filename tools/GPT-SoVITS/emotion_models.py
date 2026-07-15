#!/usr/bin/env python3
"""情緒感知語音模型：共用掃描器 + 情緒偵測。

api_v2.py（/models）與 voice_center_app.py（web 橋接合成）共用這支，
與 Colab 端 voiceweaver_bridge.py 同一套邏輯（換參考音＝換語氣）。

資料夾結構（依語言分，每角色一組情緒參考音）：
    <root>/<LANG>/<角色>/
        *.ckpt                                    GPT 權重
        *.pth                                     SoVITS 權重
        reference_audios/**/emotions/【情緒】逐字稿.wav   多顆，一情緒一顆

情緒根目錄：環境變數 VOICEWEAVER_EMOTION_ROOT（沒設就沒有情緒模型，呼叫端退回舊 model_history）。
"""
import os
import re

NEUTRAL = "中立"
LANG_DIRS = {"ZH", "EN", "JA", "KO"}

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


def scan_emotion_models(root=None):
    """掃 <root>/<LANG>/<角色>/ → [{name, character, lang, gpt, sovits, emotions{canon:{ref,prompt}}}]。"""
    root = root or emotion_root()
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
    root = root or emotion_root()
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
        lines.append("   （還沒有任何角色資料夾；把 <語言>/<角色>/ 放進來即可）")
    return "\n".join(lines)


def ensure_template(root=None):
    """在 <root> 底下建好 ZH/EN/JA/KO 空資料夾（缺才建）。回新建的語言清單。"""
    root = root or emotion_root()
    if not root:
        return []
    created = []
    for lang in ("ZH", "EN", "JA", "KO"):
        d = os.path.join(root, lang)
        if not os.path.isdir(d):
            os.makedirs(d, exist_ok=True)
            created.append(lang)
    return created


if __name__ == "__main__":   # 快速自測：印健檢報告
    print(format_report(scan_report()))
