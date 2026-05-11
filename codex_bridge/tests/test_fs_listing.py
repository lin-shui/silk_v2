"""Tests for codex_bridge.fs_listing.list_directory."""
from __future__ import annotations

import os
from pathlib import Path

import pytest

from codex_bridge.fs_listing import list_directory


def test_list_directory_returns_required_keys(tmp_path: Path):
    (tmp_path / "a.txt").write_text("a")
    (tmp_path / "subdir").mkdir()
    result = list_directory(str(tmp_path), show_hidden=False)
    assert result["success"] is True
    assert result["path"] == str(tmp_path)
    assert "parent" in result
    assert "segments" in result
    assert "separator" in result
    assert "entries" in result
    names = sorted(e["name"] for e in result["entries"])
    assert "a.txt" in names
    assert "subdir" in names


def test_list_directory_marks_dirs(tmp_path: Path):
    (tmp_path / "x.txt").write_text("x")
    (tmp_path / "d").mkdir()
    result = list_directory(str(tmp_path), show_hidden=False)
    by_name = {e["name"]: e for e in result["entries"]}
    assert by_name["x.txt"]["isDir"] is False
    assert by_name["d"]["isDir"] is True


def test_list_directory_hides_dotfiles_by_default(tmp_path: Path):
    (tmp_path / ".hidden").write_text("h")
    (tmp_path / "visible.txt").write_text("v")
    result = list_directory(str(tmp_path), show_hidden=False)
    names = [e["name"] for e in result["entries"]]
    assert ".hidden" not in names
    assert "visible.txt" in names


def test_list_directory_shows_dotfiles_when_requested(tmp_path: Path):
    (tmp_path / ".hidden").write_text("h")
    result = list_directory(str(tmp_path), show_hidden=True)
    names = [e["name"] for e in result["entries"]]
    assert ".hidden" in names


def test_list_directory_returns_error_for_missing_path():
    result = list_directory("/nonexistent_path_xyz_12345", show_hidden=False)
    assert result["success"] is False
    assert "error" in result


def test_list_directory_returns_error_for_file_path(tmp_path: Path):
    f = tmp_path / "file.txt"
    f.write_text("content")
    result = list_directory(str(f), show_hidden=False)
    assert result["success"] is False


def test_list_directory_segments_match_path(tmp_path: Path):
    """segments + separator should reconstruct the path."""
    sub = tmp_path / "a" / "b"
    sub.mkdir(parents=True)
    result = list_directory(str(sub), show_hidden=False)
    sep = result["separator"]
    rebuilt = sep + sep.join(result["segments"])
    assert os.path.realpath(rebuilt) == os.path.realpath(str(sub))


def test_list_directory_parent_is_one_level_up(tmp_path: Path):
    sub = tmp_path / "child"
    sub.mkdir()
    result = list_directory(str(sub), show_hidden=False)
    assert result["parent"] == str(tmp_path)


def test_list_directory_root_parent_is_none(tmp_path: Path):
    """Listing filesystem root should have parent == None (or empty)."""
    result = list_directory("/", show_hidden=False)
    assert result["parent"] in (None, "")
