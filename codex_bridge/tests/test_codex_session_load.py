"""Tests for codex_adapter._handle_session_load + find_session_file helper."""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path
from typing import Any
from unittest.mock import patch

import pytest

# Make codex_bridge package importable as flat modules (mirrors how the
# adapter is launched at runtime).
_PKG = Path(__file__).resolve().parents[1]
if str(_PKG) not in sys.path:
    sys.path.insert(0, str(_PKG))

from codex_session_index import find_session_file  # noqa: E402
import codex_adapter  # noqa: E402


def _write_rollout(root: Path, thread_id: str, *, cwd: str = "/tmp") -> Path:
    dir_ = root / "2026" / "05" / "06"
    dir_.mkdir(parents=True, exist_ok=True)
    f = dir_ / f"rollout-2026-05-06T10-00-00-{thread_id}.jsonl"
    f.write_text(
        json.dumps({
            "type": "session_meta",
            "payload": {
                "id": thread_id,
                "cwd": cwd,
                "timestamp": "2026-05-06T10:00:00.000Z",
            },
        }) + "\n",
        encoding="utf-8",
    )
    return f


# --------------------------------------------------------------------------
# find_session_file
# --------------------------------------------------------------------------


def test_find_session_file_hit_via_filename(tmp_path):
    f = _write_rollout(tmp_path, "abc-123")
    assert find_session_file("abc-123", sessions_root=tmp_path) == f


def test_find_session_file_miss(tmp_path):
    _write_rollout(tmp_path, "abc-123")
    assert find_session_file("does-not-exist", sessions_root=tmp_path) is None


def test_find_session_file_missing_root(tmp_path):
    assert find_session_file("abc", sessions_root=tmp_path / "absent") is None


def test_find_session_file_empty_thread_id(tmp_path):
    _write_rollout(tmp_path, "abc-123")
    assert find_session_file("", sessions_root=tmp_path) is None


# --------------------------------------------------------------------------
# AcpAgentServer._handle_session_load — exercised without a real WebSocket
# --------------------------------------------------------------------------


class _FakeServer:
    """Just enough of AcpAgentServer to exercise _handle_session_load.

    We bind the real method to this stub so we don't have to spin up a
    websocket. Captured responses go into ``self.sent``.
    """

    def __init__(self, default_cwd: str) -> None:
        self.default_cwd = default_cwd
        self.sessions: dict[str, codex_adapter.AcpSession] = {}
        self.sent: list[tuple[str, Any]] = []  # ("response"/"error", payload)

    async def _send_response(self, msg_id: Any, result: Any) -> None:
        self.sent.append(("response", {"id": msg_id, "result": result}))

    async def _send_error(self, msg_id: Any, code: int, message: str) -> None:
        self.sent.append(("error", {"id": msg_id, "code": code, "message": message}))

    # bind real handler
    _handle_session_load = codex_adapter.AcpAgentServer._handle_session_load


def test_handle_session_load_success(tmp_path):
    _write_rollout(tmp_path, "thread-42", cwd=str(tmp_path))
    server = _FakeServer(default_cwd=str(tmp_path))

    with patch.object(codex_adapter, "find_session_file",
                      lambda tid: tmp_path if tid == "thread-42" else None):
        asyncio.run(server._handle_session_load(
            1, {"sessionId": "thread-42", "cwd": str(tmp_path)}
        ))

    assert len(server.sent) == 1
    kind, payload = server.sent[0]
    assert kind == "response"
    assert payload["result"]["loaded"] is True
    new_acp_id = payload["result"]["sessionId"]
    assert new_acp_id in server.sessions
    assert server.sessions[new_acp_id].cc_session_id == "thread-42"


def test_handle_session_load_missing_file_returns_error(tmp_path):
    server = _FakeServer(default_cwd=str(tmp_path))

    with patch.object(codex_adapter, "find_session_file", lambda tid: None):
        asyncio.run(server._handle_session_load(
            7, {"sessionId": "ghost", "cwd": str(tmp_path)}
        ))

    assert len(server.sent) == 1
    kind, payload = server.sent[0]
    assert kind == "error"
    assert payload["code"] == -32602
    assert "ghost" in payload["message"]
    assert server.sessions == {}


def test_handle_session_load_missing_session_id(tmp_path):
    server = _FakeServer(default_cwd=str(tmp_path))
    asyncio.run(server._handle_session_load(9, {"cwd": str(tmp_path)}))
    kind, payload = server.sent[0]
    assert kind == "error"
    assert payload["code"] == -32602
    assert "missing sessionId" in payload["message"]
