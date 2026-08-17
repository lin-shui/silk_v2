from __future__ import annotations

import asyncio
import json
import os
import sys

os.environ.setdefault("CLAUDE_CODE_PATH", sys.executable)
from cc_bridge.executor import Executor, _die_claude_not_found


class _Writer:
    def __init__(self) -> None:
        self.data = bytearray()

    def write(self, value: bytes) -> None:
        self.data.extend(value)

    async def drain(self) -> None:
        return None


class _Process:
    def __init__(self) -> None:
        self.stdin = _Writer()


def test_write_stdin_sanitizes_lone_surrogate_before_utf8_encoding():
    process = _Process()
    asyncio.run(Executor()._write_stdin(process, {"message": "before\udca4after"}))

    decoded = json.loads(bytes(process.stdin.data).decode("utf-8"))
    assert decoded["message"] == "before�after"


def test_missing_cli_error_is_written_to_stderr(capsys):
    try:
        _die_claude_not_found("Linux")
    except SystemExit as error:
        assert error.code == 1
    else:
        raise AssertionError("missing CLI helper must exit")

    assert "Claude Code CLI not found" in capsys.readouterr().err
