#!/usr/bin/env python3
"""把內建句庫全部用 GPT-SoVITS 預先合成成音檔。

為什麼要預合成：這些句子是固定的，每次都現場合成等於讓使用者為同一句話
反覆等 10 秒以上，而且需要電腦端的伺服器開著。預先產好之後，App 命中捷徑
就能立刻播出——離線、零延遲。

用法：先確認 GPT-SoVITS 在 127.0.0.1:9880，然後 python3 synthesize.py
"""
import json, os, sys, time, urllib.parse, urllib.request

BASE = "http://127.0.0.1:9880"
ROOT = os.path.dirname(os.path.abspath(__file__))
LIB = os.path.join(ROOT, "..", "..", "app", "src", "main", "assets", "phrase_library_en.json")
OUT = os.path.join(ROOT, "wav")
VOICE_KEYWORD = "花火"          # 模型名稱裡要包含的字
EMOTION = "中立"                 # 求穩定：溝通用語不該帶情緒色彩


def http_json(path, payload=None, **params):
    if payload is None:
        url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
        return urllib.request.urlopen(url, timeout=600)
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=600)


def main():
    models = json.load(http_json("/models"))["models"]
    voice = next((m for m in models if VOICE_KEYWORD in m["name"]), None)
    if not voice:
        sys.exit(f"找不到含「{VOICE_KEYWORD}」的模型")
    emo = voice.get("emotions", {}).get(EMOTION) or {
        "ref_path": voice["ref_path"], "prompt": voice["prompt"]}
    print(f"嗓音 {voice['name']}  情緒 {EMOTION}")

    # 參考音檔與逐字稿必須成對決定。拆開來各自取預設值會發出怪聲——
    # 那是這個專案踩過的坑，這裡刻意從同一個 emotion 物件取兩個欄位。
    ref_path, prompt = emo["ref_path"], emo["prompt"]

    http_json("/set_gpt_weights", weights_path=voice["gpt_rel"])
    http_json("/set_sovits_weights", weights_path=voice["sovits_rel"])
    print("模型已切換")

    entries = json.load(open(LIB, encoding="utf-8"))
    os.makedirs(OUT, exist_ok=True)
    manifest, failed = [], []

    for i, e in enumerate(entries, 1):
        target = os.path.join(OUT, e["id"] + ".wav")
        if os.path.exists(target) and os.path.getsize(target) > 10_000:
            print(f"[{i}/{len(entries)}] {e['id']}: 已存在，略過")
            manifest.append({"id": e["id"], "file": e["id"] + ".wav", "text": e["sentence"]})
            continue
        t0 = time.time()
        try:
            data = http_json("/tts", payload={
                "text": e["sentence"], "text_lang": "en",
                "ref_audio_path": ref_path, "prompt_text": prompt, "prompt_lang": "en",
                "media_type": "wav", "speed_factor": 1.0, "sample_steps": 32,
                "text_split_method": "cut5", "batch_size": 1,
            }).read()
        except Exception as ex:
            print(f"[{i}/{len(entries)}] {e['id']}: 失敗 {ex}")
            failed.append(e["id"]); continue
        if len(data) < 10_000:
            print(f"[{i}/{len(entries)}] {e['id']}: 回傳過小（{len(data)}B），視為失敗")
            failed.append(e["id"]); continue
        open(target, "wb").write(data)
        manifest.append({"id": e["id"], "file": e["id"] + ".wav", "text": e["sentence"]})
        print(f"[{i}/{len(entries)}] {e['id']}: {len(data)//1024}KB  {time.time()-t0:.1f}s")

    json.dump(manifest, open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"\n完成 {len(manifest)}/{len(entries)}；失敗 {failed or '無'}")


if __name__ == "__main__":
    main()
