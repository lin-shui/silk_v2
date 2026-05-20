"""Unit tests for cursor_acp_relay: JSON-RPC message parsing & routing."""
from __future__ import annotations

import os

# Set a dummy CURSOR_AGENT_PATH before importing the module to prevent
# _detect_agent_path() from raising SystemExit at module level.
os.environ.setdefault("CURSOR_AGENT_PATH", "/bin/echo")

import asyncio
import json
import pytest

from cursor_acp_relay import (
    parse_jsonrpc_line,
    MessageKind,
    AcpSubprocess,
)


# ---------------------------------------------------------------------------
# parse_jsonrpc_line
# ---------------------------------------------------------------------------

class TestParseJsonrpcLine:
    def test_notification(self):
        """Notification: has method, no id."""
        line = json.dumps({
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {"sessionId": "s1", "update": {"sessionUpdate": "agent_message_chunk"}},
        })
        kind, msg = parse_jsonrpc_line(line)
        assert kind == MessageKind.NOTIFICATION
        assert msg["method"] == "session/update"

    def test_response_success(self):
        """Response: has id, has result, no method."""
        line = json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"sessionId": "abc"}})
        kind, msg = parse_jsonrpc_line(line)
        assert kind == MessageKind.RESPONSE
        assert msg["result"]["sessionId"] == "abc"

    def test_response_error(self):
        """Error response: has id, has error, no method."""
        line = json.dumps({"jsonrpc": "2.0", "id": 2, "error": {"code": -32601, "message": "not found"}})
        kind, msg = parse_jsonrpc_line(line)
        assert kind == MessageKind.RESPONSE
        assert msg["error"]["code"] == -32601

    def test_server_request(self):
        """Server request: has both id and method."""
        line = json.dumps({
            "jsonrpc": "2.0", "id": 42,
            "method": "session/request_permission",
            "params": {"sessionId": "s1"},
        })
        kind, msg = parse_jsonrpc_line(line)
        assert kind == MessageKind.SERVER_REQUEST
        assert msg["method"] == "session/request_permission"
        assert msg["id"] == 42

    def test_invalid_json(self):
        """Invalid JSON returns UNKNOWN."""
        kind, msg = parse_jsonrpc_line("not json {{{")
        assert kind == MessageKind.UNKNOWN
        assert msg == {}

    def test_empty_line(self):
        """Empty/whitespace line returns UNKNOWN."""
        kind, msg = parse_jsonrpc_line("   ")
        assert kind == MessageKind.UNKNOWN

    def test_null_id_is_notification(self):
        """JSON-RPC with id=null is a notification, not a request."""
        line = json.dumps({"jsonrpc": "2.0", "id": None, "method": "session/update", "params": {}})
        kind, msg = parse_jsonrpc_line(line)
        assert kind == MessageKind.NOTIFICATION
