"""Unit tests for the Silk ACP bridge git_ops helper (working-tree inspection)."""
import os
import subprocess
import tempfile
import unittest

from git_ops import git_status, git_diff


def _run(cwd, *args):
    subprocess.run(args, cwd=cwd, check=True, capture_output=True)


def _make_repo():
    d = tempfile.mkdtemp(prefix="silk_git_test_")
    _run(d, "git", "init", "-q")
    _run(d, "git", "config", "user.email", "t@t.t")
    _run(d, "git", "config", "user.name", "t")
    with open(os.path.join(d, "kept.txt"), "w") as f:
        f.write("line1\nline2\nline3\n")
    with open(os.path.join(d, "gone.txt"), "w") as f:
        f.write("bye\n")
    _run(d, "git", "add", "-A")
    _run(d, "git", "commit", "-qm", "init")
    return d


class GitStatusTest(unittest.IsolatedAsyncioTestCase):
    async def test_reports_modified_added_deleted_untracked(self):
        d = _make_repo()
        # modify a tracked file
        with open(os.path.join(d, "kept.txt"), "w") as f:
            f.write("line1\nCHANGED\nline3\nline4\n")
        # delete a tracked file
        os.remove(os.path.join(d, "gone.txt"))
        # add a brand new untracked file
        with open(os.path.join(d, "new.txt"), "w") as f:
            f.write("a\nb\n")

        res = await git_status(d)
        self.assertTrue(res["success"])
        self.assertTrue(res["isGitRepo"])
        by_path = {f["path"]: f for f in res["files"]}
        self.assertEqual(by_path["kept.txt"]["status"], "modified")
        self.assertEqual(by_path["gone.txt"]["status"], "deleted")
        self.assertEqual(by_path["new.txt"]["status"], "untracked")
        self.assertEqual(by_path["new.txt"]["additions"], 2)

    async def test_non_repo_returns_isGitRepo_false(self):
        d = tempfile.mkdtemp(prefix="silk_nongit_")
        res = await git_status(d)
        self.assertTrue(res["success"])
        self.assertFalse(res["isGitRepo"])
        self.assertEqual(res["files"], [])

    async def test_non_ascii_filename_unescaped(self):
        # core.quotePath=false: non-ASCII (e.g. Chinese) paths must come back as real
        # UTF-8, not C-quoted ("\344..."), so they match the filesystem and numstat.
        d = _make_repo()
        fname = "笔记-α.txt"
        with open(os.path.join(d, fname), "w", encoding="utf-8") as f:
            f.write("一\n二\n")
        res = await git_status(d)
        by_path = {f["path"]: f for f in res["files"]}
        self.assertIn(fname, by_path)
        self.assertEqual(by_path[fname]["status"], "untracked")
        self.assertEqual(by_path[fname]["additions"], 2)

    async def test_reports_renamed_with_old_path(self):
        d = _make_repo()
        os.rename(os.path.join(d, "kept.txt"), os.path.join(d, "renamed.txt"))
        _run(d, "git", "add", "-A")
        res = await git_status(d)
        by_path = {f["path"]: f for f in res["files"]}
        self.assertIn("renamed.txt", by_path)
        self.assertEqual(by_path["renamed.txt"]["status"], "renamed")
        self.assertEqual(by_path["renamed.txt"]["oldPath"], "kept.txt")


class GitDiffTest(unittest.IsolatedAsyncioTestCase):
    async def test_tracked_modification_patch(self):
        d = _make_repo()
        with open(os.path.join(d, "kept.txt"), "w") as f:
            f.write("line1\nCHANGED\nline3\n")
        res = await git_diff(d, "kept.txt")
        self.assertTrue(res["success"])
        self.assertFalse(res["isBinary"])
        self.assertIn("-line2", res["patch"])
        self.assertIn("+CHANGED", res["patch"])

    async def test_untracked_file_shows_whole_file_added(self):
        d = _make_repo()
        with open(os.path.join(d, "new.txt"), "w") as f:
            f.write("alpha\nbeta\n")
        res = await git_diff(d, "new.txt")
        self.assertTrue(res["success"])
        self.assertIn("+alpha", res["patch"])
        self.assertIn("+beta", res["patch"])

    async def test_binary_file_reports_is_binary(self):
        d = _make_repo()
        with open(os.path.join(d, "blob.bin"), "wb") as f:
            f.write(b"\x00\x01\x02\x00\xff")
        res = await git_diff(d, "blob.bin")
        self.assertTrue(res["success"])
        self.assertTrue(res["isBinary"])
        self.assertEqual(res["patch"], "")

    async def test_non_ascii_filename_diff(self):
        d = _make_repo()
        fname = "说明.txt"
        with open(os.path.join(d, fname), "w", encoding="utf-8") as f:
            f.write("旧\n")
        _run(d, "git", "add", "-A")
        _run(d, "git", "commit", "-qm", "add cn")
        with open(os.path.join(d, fname), "w", encoding="utf-8") as f:
            f.write("新\n")
        res = await git_diff(d, fname)
        self.assertTrue(res["success"])
        self.assertFalse(res["isBinary"])
        self.assertIn("-旧", res["patch"])
        self.assertIn("+新", res["patch"])
