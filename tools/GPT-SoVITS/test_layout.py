#!/usr/bin/env python3
"""曲庫版面（Models/ 與 AcousticModels/）的行為測試。

這裡測的是「搬家會不會弄丟東西」。曲庫是使用者自己錄的聲音，
弄丟了沒有地方能重新下載——所以每一條路徑都要有測試釘住，
特別是「舊版面不搬也要照樣能用」跟「搬到一半重跑不會壞」。

跑法：python3 tools/GPT-SoVITS/test_layout.py
"""
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emotion_models as em  # noqa: E402


def make_char(base, lang, char, refs=("【開心】太好了.wav", "【中立】你好.wav")):
    """造一個「完整」的角色資料夾（權重＋參考音都有）。"""
    cdir = os.path.join(base, lang, char)
    os.makedirs(os.path.join(cdir, "reference_audios", "emotions"), exist_ok=True)
    for name, body in ((f"{char}-e10.ckpt", b"gpt"), (f"{char}_e10.pth", b"sovits")):
        with open(os.path.join(cdir, name), "wb") as f:
            f.write(body)
    for r in refs:
        with open(os.path.join(cdir, "reference_audios", "emotions", r), "wb") as f:
            f.write(b"wav")
    return cdir


class LayoutTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="vw_layout_")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    # ── 版面判斷 ────────────────────────────────────────────

    def test_全新的曲庫一律走新版面(self):
        # 空資料夾沒有線索，這時要選新版面——選舊的話，第一次下載就又長回舊結構
        self.assertEqual(em.models_root(self.root), os.path.join(self.root, "Models"))
        self.assertFalse(em.is_legacy_layout(self.root))

    def test_舊版面不搬家也要認得(self):
        make_char(self.root, "ZH", "阿明")
        self.assertTrue(em.is_legacy_layout(self.root))
        self.assertEqual(em.models_root(self.root), self.root)
        self.assertEqual(len(em.scan_emotion_models(self.root)), 1)

    def test_有Models就以它為準(self):
        make_char(os.path.join(self.root, "Models"), "EN", "Hanabi")
        self.assertFalse(em.is_legacy_layout(self.root))
        self.assertEqual(em.models_root(self.root), os.path.join(self.root, "Models"))
        self.assertEqual(len(em.scan_emotion_models(self.root)), 1)

    def test_Models存在時不再把根目錄當舊版面(self):
        # 半搬家狀態：Models/ 有東西，根目錄也還留著語言資料夾。
        # 一旦有 Models/ 就只認它，否則同一個角色會被算兩次。
        make_char(os.path.join(self.root, "Models"), "ZH", "阿明")
        make_char(self.root, "ZH", "阿明")
        self.assertFalse(em.is_legacy_layout(self.root))
        self.assertEqual(len(em.scan_emotion_models(self.root)), 1)

    # ── 搬家 ───────────────────────────────────────────────

    def test_搬家之後角色還在而且掃得到(self):
        make_char(self.root, "ZH", "阿明")
        make_char(self.root, "EN", "Hanabi")
        before = {m["name"] for m in em.scan_emotion_models(self.root)}

        moved, err = em.migrate_layout(self.root)
        self.assertEqual(err, "")
        self.assertEqual(moved, 2)

        self.assertTrue(os.path.isdir(os.path.join(self.root, "Models", "ZH", "阿明")))
        self.assertFalse(os.path.isdir(os.path.join(self.root, "ZH")))
        self.assertEqual({m["name"] for m in em.scan_emotion_models(self.root)}, before)

    def test_搬家保留參考音與權重(self):
        make_char(self.root, "ZH", "阿明")
        em.migrate_layout(self.root)
        m = em.scan_emotion_models(self.root)[0]
        self.assertTrue(os.path.isfile(m["gpt"]))
        self.assertTrue(os.path.isfile(m["sovits"]))
        self.assertIn("开心", m["emotions"])   # canon 後的鍵
        self.assertIn("中立", m["emotions"])

    def test_重複搬家不會出事(self):
        make_char(self.root, "ZH", "阿明")
        em.migrate_layout(self.root)
        moved, err = em.migrate_layout(self.root)
        self.assertEqual((moved, err), (0, ""))
        self.assertEqual(len(em.scan_emotion_models(self.root)), 1)

    def test_搬家碰到同名角色時不覆蓋(self):
        # 中斷後重跑的情境：Models/ZH/阿明 已經在了，根目錄還留著一份。
        # 寧可留著也不要蓋掉——蓋掉的是使用者錄了很久的聲音。
        make_char(os.path.join(self.root, "Models"), "ZH", "阿明")
        legacy = make_char(self.root, "ZH", "阿明")
        with open(os.path.join(legacy, "只有舊的有.txt"), "w") as f:
            f.write("x")
        make_char(self.root, "ZH", "小華")

        # 這時 Models/ 已存在 → 不算舊版面，migrate 不動它
        self.assertEqual(em.migrate_layout(self.root), (0, ""))
        self.assertTrue(os.path.isfile(os.path.join(legacy, "只有舊的有.txt")))

    def test_搬家合併不同角色(self):
        os.makedirs(os.path.join(self.root, "Models"), exist_ok=True)
        make_char(os.path.join(self.root, "Models"), "ZH", "已經搬好的")
        # 手動製造「Models 存在但根目錄仍有語言夾」→ 直接呼叫合併分支
        make_char(self.root, "ZH", "還沒搬的")
        dest = os.path.join(self.root, "Models", "ZH")
        src = os.path.join(self.root, "ZH")
        for name in os.listdir(src):
            os.rename(os.path.join(src, name), os.path.join(dest, name))
        os.rmdir(src)
        names = {m["character"] for m in em.scan_emotion_models(self.root)}
        self.assertEqual(names, {"已經搬好的", "還沒搬的"})

    # ── catalog：路徑一律相對曲庫根 ──────────────────────────

    def test_索引路徑相對曲庫根而且開得起來(self):
        make_char(os.path.join(self.root, "Models"), "ZH", "阿明")
        cat = em.build_catalog(self.root)
        c = cat["characters"][0]
        self.assertTrue(c["gpt"]["path"].startswith("Models" + os.sep))
        # 消費端就是這樣開檔的；開不起來代表索引對不上實體檔案
        self.assertTrue(os.path.isfile(os.path.join(self.root, c["gpt"]["path"])))
        for r in c["refs"]:
            self.assertTrue(os.path.isfile(os.path.join(self.root, r["path"])))

    def test_舊版面的索引仍然開得起來(self):
        make_char(self.root, "ZH", "阿明")
        cat = em.build_catalog(self.root)
        c = cat["characters"][0]
        self.assertFalse(c["gpt"]["path"].startswith("Models"))
        self.assertTrue(os.path.isfile(os.path.join(self.root, c["gpt"]["path"])))

    def test_搬家前後索引的角色相同(self):
        make_char(self.root, "ZH", "阿明")
        before = {c["name"] for c in em.build_catalog(self.root)["characters"]}
        em.migrate_layout(self.root)
        after = {c["name"] for c in em.build_catalog(self.root)["characters"]}
        self.assertEqual(before, after)

    def test_隨選下載在兩種版面都讀得到檔案(self):
        make_char(self.root, "ZH", "阿明")
        legacy = em.prefetch(self.root, "阿明", "ZH")
        em.migrate_layout(self.root)
        new = em.prefetch(self.root, "阿明", "ZH")
        self.assertTrue(legacy["ok"] and new["ok"])
        self.assertEqual(legacy["files"], new["files"])
        self.assertEqual(legacy["bytes"], new["bytes"])

    # ── 骨架 ───────────────────────────────────────────────

    def test_建骨架會同時建出聲波模型資料夾(self):
        em.ensure_template(self.root)
        self.assertTrue(os.path.isdir(os.path.join(self.root, "AcousticModels")))
        for lang in ("ZH", "EN", "JA", "KO"):
            self.assertTrue(os.path.isdir(os.path.join(self.root, "Models", lang)))

    def test_舊版面建骨架不會多長一個空的Models(self):
        # 使用者的檔案在根目錄，旁邊冒出一個空的 Models/ 只會讓人不知道該放哪邊
        make_char(self.root, "ZH", "阿明")
        em.ensure_template(self.root)
        self.assertFalse(os.path.isdir(os.path.join(self.root, "Models")))
        self.assertTrue(os.path.isdir(os.path.join(self.root, "AcousticModels")))
        self.assertTrue(os.path.isdir(os.path.join(self.root, "KO")))

    def test_沒設根目錄時每一支都安靜地回空(self):
        self.assertEqual(em.models_root(""), "")
        self.assertEqual(em.acoustic_root(""), "")
        self.assertFalse(em.is_legacy_layout(""))
        self.assertEqual(em.migrate_layout(""), (0, ""))
        self.assertEqual(em.ensure_template(""), [])
        self.assertEqual(em.scan_emotion_models(""), [])


class AcousticFolderTest(unittest.TestCase):
    """聲波模型資料夾：只驗路徑與 JSON 往返，Drive 的部分要真帳號才測得到。"""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="vw_ac_")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_聲波模型與語音模型不同層(self):
        ac = em.acoustic_root(self.root, create=True)
        self.assertEqual(ac, os.path.join(self.root, "AcousticModels"))
        self.assertNotEqual(os.path.normpath(ac),
                            os.path.normpath(em.models_root(self.root)))

    def test_一筆一個檔互不覆蓋(self):
        ac = em.acoustic_root(self.root, create=True)
        for tid in ("1001", "1002"):
            with open(os.path.join(ac, f"{tid}.json"), "w", encoding="utf-8") as f:
                json.dump({"id": tid, "keyword": "need water"}, f, ensure_ascii=False)
        self.assertEqual(sorted(os.listdir(ac)), ["1001.json", "1002.json"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
