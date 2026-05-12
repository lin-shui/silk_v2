"""Tests for cc_session_index.list_local_sessions and find_session_file."""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

import pytest

# sys.path hack to import flat modules (same pattern as codex_bridge tests)
_PKG = Path(__file__).resolve().parents[1]
if str(_PKG) not in sys.path:
    sys.path.insert(0, str(_PKG))

from cc_session_index import find_session_file, list_local_sessions


# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------

def _write_cc_session(
    root: Path,
    *,
    project_dir: str,
    session_id: str,
    cwd: str,
    content: str | None,
    timestamp: str | None = None,
) -> Path:
    """Create a minimal .jsonl file mimicking Claude Code CLI session layout.

    Layout: root / <project_dir> / <session_id>.jsonl
    """
    ts = timestamp or "2026-05-08T10:00:00.000Z"
    dir_ = root / project_dir
    dir_.mkdir(parents=True, exist_ok=True)
    file_ = dir_ / f"{session_id}.jsonl"

    lines: list[str] = []
    # queue-operation/enqueue
    enqueue: dict = {
        "type": "queue-operation",
        "operation": "enqueue",
        "timestamp": ts,
        "sessionId": session_id,
    }
    if content is not None:
        enqueue["content"] = content
    lines.append(json.dumps(enqueue))

    # queue-operation/dequeue
    lines.append(json.dumps({
        "type": "queue-operation",
        "operation": "dequeue",
        "timestamp": ts,
        "sessionId": session_id,
    }))

    # user message line (includes cwd)
    if content is not None:
        lines.append(json.dumps({
            "parentUuid": None,
            "type": "user",
            "message": {"role": "user", "content": content},
            "cwd": cwd,
            "sessionId": session_id,
        }))
    else:
        # Even without content, write a line with cwd
        lines.append(json.dumps({
            "parentUuid": None,
            "type": "user",
            "message": {"role": "user", "content": ""},
            "cwd": cwd,
            "sessionId": session_id,
        }))

    file_.write_text("\n".join(lines) + "\n")
    return file_


# ---------------------------------------------------------------------------
# list_local_sessions tests
# ---------------------------------------------------------------------------

class TestListLocalSessions:

    def test_list_returns_empty_when_dir_missing(self, tmp_path):
        result = list_local_sessions(projects_root=tmp_path / "nonexistent")
        assert result == []

    def test_list_extracts_metadata(self, tmp_path):
        _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="4b2a83fc-5e73-408f-99df-29afe2e5eb0c",
            cwd="/home/user/proj",
            content="hello world",
            timestamp="2026-05-08T10:00:00.000Z",
        )
        result = list_local_sessions(projects_root=tmp_path)
        assert len(result) == 1
        s = result[0]
        assert s["sessionId"] == "4b2a83fc-5e73-408f-99df-29afe2e5eb0c"
        assert s["workingDir"] == "/home/user/proj"
        assert s["title"] == "hello world"
        assert s["createdAt"] == "2026-05-08T10:00:00.000Z"
        assert "lastActivity" in s

    def test_list_truncates_long_titles(self, tmp_path):
        long_msg = "a" * 500
        _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="abc-123",
            cwd="/proj",
            content=long_msg,
        )
        result = list_local_sessions(projects_root=tmp_path)
        assert len(result) == 1
        assert len(result[0]["title"]) <= 100

    def test_list_handles_missing_content(self, tmp_path):
        """Session with only dequeue op (no content in enqueue) yields empty title."""
        _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="abc-123",
            cwd="/proj",
            content=None,
        )
        result = list_local_sessions(projects_root=tmp_path)
        assert len(result) == 1
        assert result[0]["title"] == ""

    def test_list_sorted_by_lastActivity_desc(self, tmp_path):
        old_f = _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="old-session",
            cwd="/proj",
            content="old",
            timestamp="2026-05-01T10:00:00.000Z",
        )
        new_f = _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="new-session",
            cwd="/proj",
            content="new",
            timestamp="2026-05-08T10:00:00.000Z",
        )
        # Force old file to have old mtime
        old_time = time.time() - 5 * 86400
        os.utime(old_f, (old_time, old_time))
        result = list_local_sessions(projects_root=tmp_path)
        assert [s["sessionId"] for s in result] == ["new-session", "old-session"]

    def test_list_skips_malformed_files(self, tmp_path):
        """A file with non-JSON first line should be skipped, not crash."""
        bad_dir = tmp_path / "-home-user-proj"
        bad_dir.mkdir(parents=True)
        (bad_dir / "bad-session.jsonl").write_text("not valid json\n")

        _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="good-session",
            cwd="/proj",
            content="hi",
        )
        result = list_local_sessions(projects_root=tmp_path)
        assert len(result) == 1
        assert result[0]["sessionId"] == "good-session"

    def test_list_filters_by_age(self, tmp_path):
        """Sessions older than max_age_days should be excluded."""
        f = _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="ancient",
            cwd="/proj",
            content="x",
        )
        sixty_days_ago = time.time() - 60 * 86400
        os.utime(f, (sixty_days_ago, sixty_days_ago))
        result = list_local_sessions(projects_root=tmp_path, max_age_days=30)
        assert len(result) == 0

    def test_list_skips_subagent_files(self, tmp_path):
        """Files inside <session-id>/subagents/ must NOT appear."""
        proj_dir = tmp_path / "-home-user-proj"
        proj_dir.mkdir(parents=True)
        # Create a subagent file (nested under session-id/subagents/)
        subagent_dir = proj_dir / "parent-session-id" / "subagents"
        subagent_dir.mkdir(parents=True)
        (subagent_dir / "sub-agent-id.jsonl").write_text(
            json.dumps({
                "type": "queue-operation",
                "operation": "enqueue",
                "timestamp": "2026-05-08T10:00:00.000Z",
                "sessionId": "sub-agent-id",
                "content": "subagent task",
            }) + "\n"
        )

        # Create a valid top-level session
        _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="main-session",
            cwd="/proj",
            content="main task",
        )
        result = list_local_sessions(projects_root=tmp_path)
        assert len(result) == 1
        assert result[0]["sessionId"] == "main-session"

    def test_list_spans_multiple_project_dirs(self, tmp_path):
        """Sessions from different project dirs both appear."""
        _write_cc_session(
            tmp_path,
            project_dir="-proj-a",
            session_id="session-a",
            cwd="/proj-a",
            content="task a",
        )
        _write_cc_session(
            tmp_path,
            project_dir="-proj-b",
            session_id="session-b",
            cwd="/proj-b",
            content="task b",
        )
        result = list_local_sessions(projects_root=tmp_path)
        ids = {s["sessionId"] for s in result}
        assert ids == {"session-a", "session-b"}


# ---------------------------------------------------------------------------
# find_session_file tests
# ---------------------------------------------------------------------------

class TestFindSessionFile:

    def test_find_exact_match(self, tmp_path):
        uuid = "4b2a83fc-5e73-408f-99df-29afe2e5eb0c"
        _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id=uuid,
            cwd="/home/user/proj",
            content="hello",
            timestamp="2026-05-08T10:00:00.000Z",
        )
        result = find_session_file(uuid, projects_root=tmp_path)
        assert result is not None
        assert result["sessionId"] == uuid
        assert "path" in result

    def test_find_prefix_match(self, tmp_path):
        uuid = "4b2a83fc-5e73-408f-99df-29afe2e5eb0c"
        _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id=uuid,
            cwd="/home/user/proj",
            content="hello",
        )
        result = find_session_file("4b2a83fc", projects_root=tmp_path)
        assert result is not None
        assert result["sessionId"] == uuid

    def test_find_miss(self, tmp_path):
        _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id="4b2a83fc-5e73-408f-99df-29afe2e5eb0c",
            cwd="/proj",
            content="hello",
        )
        result = find_session_file("ffffffff", projects_root=tmp_path)
        assert result is None

    def test_find_empty_prefix(self, tmp_path):
        result = find_session_file("", projects_root=tmp_path)
        assert result is None

    def test_find_missing_root(self, tmp_path):
        result = find_session_file("abc", projects_root=tmp_path / "nonexistent")
        assert result is None

    def test_find_ambiguous_prefix_returns_most_recent(self, tmp_path):
        """When prefix matches two sessions, most recently modified wins."""
        uuid_old = "aabb0000-1111-2222-3333-444444444444"
        uuid_new = "aabb0000-5555-6666-7777-888888888888"

        old_f = _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id=uuid_old,
            cwd="/proj",
            content="old session",
        )
        new_f = _write_cc_session(
            tmp_path,
            project_dir="-home-user-proj",
            session_id=uuid_new,
            cwd="/proj",
            content="new session",
        )
        # Make old file older
        old_time = time.time() - 10 * 86400
        os.utime(old_f, (old_time, old_time))

        result = find_session_file("aabb0000", projects_root=tmp_path)
        assert result is not None
        assert result["sessionId"] == uuid_new
