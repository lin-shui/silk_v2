"""Tests for codex_session_index.list_local_sessions."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from codex_bridge.codex_session_index import find_session_file, list_local_sessions


def _write_rollout(
    root: Path,
    *,
    date: str,                           # "2026/05/06"
    timestamp: str,                       # "2026-05-06T10-00-00"
    thread_id: str,
    cwd: str,
    first_user_message: str | None,
    iso_timestamp: str | None = None,
) -> Path:
    """Write a synthetic rollout-*.jsonl file under root mimicking real layout."""
    dir_ = root / date
    dir_.mkdir(parents=True, exist_ok=True)
    file_ = dir_ / f"rollout-{timestamp}-{thread_id}.jsonl"
    lines = []
    lines.append(json.dumps({
        "type": "session_meta",
        "payload": {
            "id": thread_id,
            "cwd": cwd,
            "timestamp": iso_timestamp or "2026-05-06T10:00:00.000Z",
            "cli_version": "0.125.0",
        },
    }))
    if first_user_message is not None:
        lines.append(json.dumps({
            "type": "event_msg",
            "payload": {"type": "user_message", "message": first_user_message},
        }))
    file_.write_text("\n".join(lines) + "\n")
    return file_


def test_list_local_sessions_returns_empty_when_dir_missing(tmp_path):
    result = list_local_sessions(sessions_root=tmp_path / "nonexistent")
    assert result == []


def test_list_local_sessions_extracts_metadata(tmp_path):
    _write_rollout(
        tmp_path,
        date="2026/05/06",
        timestamp="2026-05-06T10-00-00",
        thread_id="abc-123",
        cwd="/proj/foo",
        first_user_message="hello world",
        iso_timestamp="2026-05-06T10:00:00.000Z",
    )
    result = list_local_sessions(sessions_root=tmp_path)
    assert len(result) == 1
    s = result[0]
    assert s["sessionId"] == "abc-123"
    assert s["workingDir"] == "/proj/foo"
    assert s["title"] == "hello world"
    assert s["createdAt"] == "2026-05-06T10:00:00.000Z"
    assert "lastActivity" in s


def test_list_local_sessions_truncates_long_titles(tmp_path):
    long_msg = "a" * 500
    _write_rollout(
        tmp_path,
        date="2026/05/06",
        timestamp="2026-05-06T10-00-00",
        thread_id="abc-123",
        cwd="/proj",
        first_user_message=long_msg,
    )
    result = list_local_sessions(sessions_root=tmp_path)
    assert len(result[0]["title"]) <= 100


def test_list_local_sessions_handles_missing_user_message(tmp_path):
    _write_rollout(
        tmp_path,
        date="2026/05/06",
        timestamp="2026-05-06T10-00-00",
        thread_id="abc",
        cwd="/proj",
        first_user_message=None,
    )
    result = list_local_sessions(sessions_root=tmp_path)
    assert len(result) == 1
    assert result[0]["title"] == ""


def test_list_local_sessions_sorted_by_lastActivity_desc(tmp_path):
    _write_rollout(
        tmp_path, date="2026/05/01", timestamp="2026-05-01T10-00-00",
        thread_id="old", cwd="/p", first_user_message="old",
        iso_timestamp="2026-05-01T10:00:00.000Z",
    )
    _write_rollout(
        tmp_path, date="2026/05/06", timestamp="2026-05-06T10-00-00",
        thread_id="new", cwd="/p", first_user_message="new",
        iso_timestamp="2026-05-06T10:00:00.000Z",
    )
    result = list_local_sessions(sessions_root=tmp_path)
    assert [s["sessionId"] for s in result] == ["new", "old"]


def test_list_local_sessions_skips_malformed_files(tmp_path):
    """A rollout file with non-JSON first line should be skipped, not crash."""
    bad = tmp_path / "2026" / "05" / "06"
    bad.mkdir(parents=True)
    (bad / "rollout-bad.jsonl").write_text("not valid json\n")
    # And a valid one alongside
    _write_rollout(
        tmp_path, date="2026/05/06", timestamp="2026-05-06T11-00-00",
        thread_id="good", cwd="/p", first_user_message="hi",
    )
    result = list_local_sessions(sessions_root=tmp_path)
    assert len(result) == 1
    assert result[0]["sessionId"] == "good"


def test_list_local_sessions_filters_by_age_default_30_days(tmp_path):
    """Sessions older than max_age_days should be excluded."""
    import os
    import time
    f = _write_rollout(
        tmp_path, date="2025/01/01", timestamp="2025-01-01T10-00-00",
        thread_id="ancient", cwd="/p", first_user_message="x",
        iso_timestamp="2025-01-01T10:00:00.000Z",
    )
    # Force ancient mtime (60 days ago)
    sixty_days_ago = time.time() - 60 * 86400
    os.utime(f, (sixty_days_ago, sixty_days_ago))
    result = list_local_sessions(sessions_root=tmp_path, max_age_days=30)
    assert len(result) == 0


def test_find_session_file_prefix_match(tmp_path):
    """Prefix of thread_id should find the session."""
    _write_rollout(
        tmp_path, date="2026/05/06", timestamp="2026-05-06T10-00-00",
        thread_id="abc-123-456-789", cwd="/proj", first_user_message="hi",
    )
    result = find_session_file("abc-123", sessions_root=tmp_path)
    assert result is not None
    assert result.name.endswith(".jsonl")


def test_find_session_file_prefix_ambiguous_returns_match(tmp_path):
    """When prefix matches multiple, return one (not None)."""
    _write_rollout(
        tmp_path, date="2026/05/06", timestamp="2026-05-06T10-00-00",
        thread_id="abc-111", cwd="/proj", first_user_message="first",
    )
    _write_rollout(
        tmp_path, date="2026/05/06", timestamp="2026-05-06T11-00-00",
        thread_id="abc-222", cwd="/proj", first_user_message="second",
    )
    result = find_session_file("abc", sessions_root=tmp_path)
    assert result is not None
