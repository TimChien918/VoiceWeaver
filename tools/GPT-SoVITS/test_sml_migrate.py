#!/usr/bin/env python3
"""sml_migrate 的單元測試（純標準庫）：python3 test_sml_migrate.py"""
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sml_migrate as m  # noqa: E402


class LocalFolderTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="sml_mig_")
        self.new = os.path.join(self.home, m.DESKTOP_HOME_NEW)
        self.old = os.path.join(self.home, m.DESKTOP_HOME_OLD)

    def tearDown(self):
        shutil.rmtree(self.home, ignore_errors=True)

    def _mk_old(self):
        os.makedirs(os.path.join(self.old, "GPT-SoVITS"))
        open(os.path.join(self.old, "GPT-SoVITS", "a.txt"), "w").write("old")

    def test_only_old_is_renamed_and_linked(self):
        self._mk_old()
        with mock.patch.dict(os.environ, {"SML_GS_HOME": ""}):
            got = m.desktop_home(self.home)
        self.assertEqual(got, self.new)
        self.assertEqual(open(os.path.join(self.new, "GPT-SoVITS", "a.txt")).read(), "old")
        # 舊位置留了捷徑：寫死舊路徑的 conda 照樣找得到
        self.assertTrue(os.path.islink(self.old))
        self.assertTrue(os.path.isfile(os.path.join(self.old, "GPT-SoVITS", "a.txt")))
        # 冪等：第二次直接用新的
        self.assertEqual(m.desktop_home(self.home), self.new)

    def test_both_exist_uses_new_and_keeps_old(self):
        self._mk_old()
        os.makedirs(self.new)
        self.assertEqual(m.desktop_home(self.home), self.new)
        self.assertTrue(os.path.isdir(self.old) and not os.path.islink(self.old))

    def test_rename_failure_falls_back_to_old(self):
        self._mk_old()
        with mock.patch("os.rename", side_effect=PermissionError("busy")):
            self.assertEqual(m.desktop_home(self.home), self.old)
        self.assertFalse(os.path.exists(self.new))

    def test_link_failure_reverts_rename(self):
        self._mk_old()
        with mock.patch.object(m, "_make_dir_link", return_value=False):
            self.assertEqual(m.desktop_home(self.home), self.old)
        self.assertTrue(os.path.isdir(self.old) and not os.path.islink(self.old))
        self.assertFalse(os.path.exists(self.new))

    def test_neither_exists_returns_new_without_creating(self):
        self.assertEqual(m.desktop_home(self.home), self.new)
        self.assertFalse(os.path.exists(self.new))

    def test_plain_file_migration(self):
        old = os.path.join(self.home, "voiceweaver_tts_infer.yaml")
        new = os.path.join(self.home, "speakmylife_tts_infer.yaml")
        open(old, "w").write("custom: {}")
        self.assertEqual(m.migrate_path(new, old), new)
        self.assertFalse(os.path.exists(old))
        self.assertEqual(open(new).read(), "custom: {}")

    def test_mount_root_colab_style(self):
        mydrive = os.path.join(self.home, "MyDrive")
        os.makedirs(os.path.join(mydrive, m.OLD_NAME, "Models"))
        got = m.mount_root(mydrive)
        self.assertEqual(got, os.path.join(mydrive, m.NEW_NAME))
        self.assertTrue(os.path.isdir(os.path.join(got, "Models")))
        self.assertFalse(os.path.lexists(os.path.join(mydrive, m.OLD_NAME)))  # Drive 不留捷徑

    def test_remap_legacy_path(self):
        os.makedirs(os.path.join(self.new, "library"))
        stored = os.path.join(self.old, "library")
        self.assertEqual(m.remap_legacy_path(stored, [(self.old, self.new)]),
                         os.path.join(self.new, "library"))
        other = os.path.join(self.home, "elsewhere")
        self.assertEqual(m.remap_legacy_path(other, [(self.old, self.new)]), other)


class FakeDrive:
    """最外層資料夾的假 Drive：{id: name}；can_rename=False 模擬別人建的資料夾。"""

    def __init__(self, folders, can_rename=True):
        self.folders = dict(folders)
        self.can_rename = can_rename
        self.created = []

    def lister(self, name):
        return lambda: [{"id": i, "name": n} for i, n in self.folders.items() if n == name]

    def rename(self, fid, name):
        if not self.can_rename:
            raise PermissionError("403 insufficientFilePermissions")
        self.folders[fid] = name

    def create(self):
        fid = f"new{len(self.created)}"
        self.created.append(fid)
        self.folders[fid] = m.NEW_NAME
        return fid

    def resolve(self, create=True):
        return m.resolve_drive_root(self.lister(m.NEW_NAME), self.lister(m.OLD_NAME),
                                    lambda c: c[0]["id"], self.rename,
                                    self.create if create else None)


class DriveRootTest(unittest.TestCase):
    def test_new_exists_old_untouched(self):
        d = FakeDrive({"A": m.NEW_NAME, "B": m.OLD_NAME})
        self.assertEqual(d.resolve(), "A")
        self.assertEqual(d.folders["B"], m.OLD_NAME)

    def test_only_old_gets_renamed(self):
        d = FakeDrive({"B": m.OLD_NAME})
        self.assertEqual(d.resolve(), "B")
        self.assertEqual(d.folders["B"], m.NEW_NAME)
        self.assertEqual(d.created, [])
        self.assertEqual(d.resolve(), "B")          # 之後直接找到新名字

    def test_rename_failure_keeps_using_old(self):
        d = FakeDrive({"B": m.OLD_NAME}, can_rename=False)
        self.assertEqual(d.resolve(), "B")
        self.assertEqual(d.folders["B"], m.OLD_NAME)
        self.assertEqual(d.created, [])              # 絕不另建一個空曲庫

    def test_neither_creates_or_returns_none(self):
        self.assertIsNone(FakeDrive({}).resolve(create=False))
        d = FakeDrive({})
        self.assertEqual(d.resolve(), "new0")


class PhoneDirTest(unittest.TestCase):
    def _fake_sh(self, dirs, mv_ok=True):
        def sh(cmd):
            if cmd.startswith("[ -d"):
                p = cmd.split("'")[1]
                return "Y\n" if p in dirs else "N\n"
            if " mv " in cmd and mv_ok:
                dirs.discard(f"{m.PHONE_BASE_OLD}/voice_models")
                dirs.add(f"{m.PHONE_BASE_NEW}/voice_models")
            return ""
        return sh

    def test_phone_three_cases(self):
        new, old = f"{m.PHONE_BASE_NEW}/voice_models", f"{m.PHONE_BASE_OLD}/voice_models"
        self.assertEqual(m.migrate_phone_dir(self._fake_sh({new, old})), new)
        self.assertEqual(m.migrate_phone_dir(self._fake_sh({old})), new)
        self.assertEqual(m.migrate_phone_dir(self._fake_sh({old}, mv_ok=False)), old)
        self.assertEqual(m.migrate_phone_dir(self._fake_sh(set())), new)


if __name__ == "__main__":
    unittest.main(verbosity=2)
