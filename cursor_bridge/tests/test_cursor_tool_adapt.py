"""Unit tests for cursor_adapter tool_call / tool_call_update adaptation."""
from __future__ import annotations

import os

os.environ.setdefault("CURSOR_AGENT_PATH", "/bin/echo")

from cursor_adapter import (
    AcpSession,
    ToolCallDisplay,
    _adapt_cursor_update,
)


def _session() -> AcpSession:
    return AcpSession(cwd="/tmp")


class TestToolCallAdapt:
    def test_tool_call_remembers_line_and_sets_title(self):
        sess = _session()
        out = _adapt_cursor_update({
            "sessionUpdate": "tool_call",
            "toolCallId": "tc1",
            "kind": "bash",
            "title": "ls -la",
            "rawInput": {"command": "ls -la"},
        }, sess)

        assert out is not None
        assert out["tool"] == "Bash"
        assert "`ls -la`" in out["title"]
        assert sess.active_tools["tc1"].line == out["title"]

    def test_terminal_completed_appends_checkmark_like_cc(self):
        sess = _session()
        sess.active_tools["tc1"] = ToolCallDisplay(
            line="\U0001f4bb Bash `ls -la`",
            tool="Bash",
            kind="bash",
        )
        out = _adapt_cursor_update({
            "sessionUpdate": "tool_call_update",
            "toolCallId": "tc1",
            "status": "completed",
            "content": None,
        }, sess)

        assert out is not None
        assert out["content"]["text"] == "\U0001f4bb Bash `ls -la` \u2192 \u2705"
        assert "tc1" not in sess.active_tools

    def test_terminal_failed_uses_error_summary_from_content(self):
        sess = _session()
        sess.active_tools["tc2"] = ToolCallDisplay(
            line="\U0001f4bb Bash `false`",
            tool="Bash",
            kind="bash",
        )
        out = _adapt_cursor_update({
            "sessionUpdate": "tool_call_update",
            "toolCallId": "tc2",
            "status": "failed",
            "content": [{"content": {"type": "text", "text": "command not found\nmore"}}],
        }, sess)

        assert out is not None
        assert "\u2192 \u274c command not found" in out["content"]["text"]
        assert "more" not in out["content"]["text"]

    def test_in_progress_update_is_dropped(self):
        sess = _session()
        sess.active_tools["tc3"] = ToolCallDisplay(
            line="\U0001f4d6 Read `foo.txt`",
            tool="Read",
            kind="Read",
        )
        out = _adapt_cursor_update({
            "sessionUpdate": "tool_call_update",
            "toolCallId": "tc3",
            "status": "in_progress",
            "content": None,
        }, sess)

        assert out is None
        assert "tc3" in sess.active_tools

    def test_completed_without_prior_tool_call_still_formats_line(self):
        out = _adapt_cursor_update({
            "sessionUpdate": "tool_call_update",
            "toolCallId": "tc-miss",
            "status": "completed",
            "kind": "bash",
            "title": "echo hi",
            "content": None,
        }, _session())

        assert out is not None
        assert "`echo hi`" in out["content"]["text"]
        assert out["content"]["text"].endswith("\u2192 \u2705")
