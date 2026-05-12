#!/usr/bin/env python3
"""Local HTTP server for PreToolUse hook permission requests."""

from __future__ import annotations

import json
import logging
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable

logger = logging.getLogger(__name__)


def _make_response(decision: str, reason: str = "") -> dict[str, str]:
    return {"decision": decision, "reason": reason}


class _RequestHandler(BaseHTTPRequestHandler):
    """Handle POST /permission-request from the hook script."""

    def log_message(self, format: str, *args: Any) -> None:
        logger.debug("[PermissionHTTP] %s", format % args)

    def do_POST(self) -> None:
        if self.path != "/permission-request":
            self.send_error(404)
            return

        content_length = int(self.headers.get("Content-Length", 0))
        if content_length == 0:
            self._respond_json(_make_response("deny", "empty request body"))
            return

        body = self.rfile.read(content_length)
        try:
            request_data = json.loads(body)
        except (json.JSONDecodeError, ValueError):
            self._respond_json(_make_response("deny", "invalid JSON"))
            return

        session_id = request_data.get("session_id", "")
        tool_name = request_data.get("tool_name", "")
        tool_input = request_data.get("tool_input", {})

        server: PermissionServer = self.server.perm_server  # type: ignore[attr-defined]

        # Unknown session → auto-allow (not a Silk-managed CLI process)
        if session_id not in server.known_sessions:
            logger.info(
                "[PermissionServer] Unknown session %s (known=%s), auto-allow",
                session_id[:8] if session_id else "?",
                [s[:8] for s in server.known_sessions],
            )
            self._respond_json(_make_response("allow"))
            return

        # Generate request ID and create blocking event
        request_id = str(uuid.uuid4())
        event = threading.Event()
        server.pending_requests[request_id] = {
            "event": event,
            "decision": None,
            "reason": "",
            "timeout": None,
        }

        logger.info(
            "[PermissionServer] Permission request: id=%s session=%s tool=%s",
            request_id[:8], session_id[:8], tool_name,
        )

        # Notify adapter (which sends ACP notification to backend)
        try:
            server.on_permission_request(
                session_id=session_id,
                request_id=request_id,
                tool_name=tool_name,
                tool_input=tool_input,
            )
        except Exception as e:
            logger.error("[PermissionServer] Callback failed: %s", e, exc_info=True)
            server.pending_requests.pop(request_id, None)
            self._respond_json(_make_response("deny", "notification failed"))
            return

        # Block until resolved or timeout
        entry = server.pending_requests.get(request_id)
        custom_timeout = entry.get("timeout") if entry else None
        timeout = custom_timeout if custom_timeout is not None else server.default_timeout
        responded = event.wait(timeout=timeout)

        pending = server.pending_requests.pop(request_id, None)
        if not responded or pending is None or pending["decision"] is None:
            logger.warning(
                "[PermissionServer] Timeout for request %s (%ds)",
                request_id[:8], timeout,
            )
            self._respond_json(
                _make_response("deny", f"用户未在 {timeout}s 内响应，自动拒绝")
            )
            return

        decision = pending["decision"]
        reason = pending.get("reason", "")
        logger.info(
            "[PermissionServer] Resolved: id=%s decision=%s", request_id[:8], decision,
        )
        self._respond_json(_make_response(decision, reason))

    def _respond_json(self, data: dict) -> None:
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class PermissionServer:
    """Manage the local HTTP server for hook permission requests.

    Lifecycle:
    1. ``start()`` → binds to 127.0.0.1 with OS-assigned port, starts daemon thread
    2. Hook scripts POST to /permission-request → handler blocks until ``resolve_request()``
    3. ``stop()`` → shuts down server and daemon thread
    """

    def __init__(
        self,
        on_permission_request: Callable[..., None],
        default_timeout: float = 300.0,
    ) -> None:
        self.on_permission_request = on_permission_request
        self.default_timeout = default_timeout
        self.known_sessions: set[str] = set()
        self.pending_requests: dict[str, dict[str, Any]] = {}
        self._httpd: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None

    @property
    def port(self) -> int | None:
        if self._httpd is None:
            return None
        return self._httpd.server_address[1]

    def start(self) -> int:
        """Start the server. Returns the assigned port."""
        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), _RequestHandler)
        self._httpd.perm_server = self  # type: ignore[attr-defined]
        port = self._httpd.server_address[1]
        self._thread = threading.Thread(
            target=self._httpd.serve_forever, daemon=True, name="perm-server",
        )
        self._thread.start()
        logger.info("[PermissionServer] Started on 127.0.0.1:%d", port)
        return port

    def stop(self) -> None:
        # Unblock pending requests FIRST — httpd.shutdown() waits for active
        # handler threads; if any are blocked on event.wait(), unblocking them
        # after shutdown() would deadlock.
        for entry in self.pending_requests.values():
            entry["decision"] = "deny"
            entry["reason"] = "server shutting down"
            entry["event"].set()
        self.pending_requests.clear()
        if self._httpd:
            self._httpd.shutdown()
            self._httpd = None
        if self._thread:
            self._thread.join(timeout=5)
            self._thread = None
        logger.info("[PermissionServer] Stopped")

    def register_session(self, cli_session_id: str) -> None:
        self.known_sessions.add(cli_session_id)

    def unregister_session(self, cli_session_id: str) -> None:
        self.known_sessions.discard(cli_session_id)

    def resolve_request(
        self, request_id: str, decision: str, reason: str = "",
    ) -> bool:
        """Resolve a pending permission request. Returns True if request was found."""
        entry = self.pending_requests.get(request_id)
        if entry is None:
            logger.warning(
                "[PermissionServer] resolve_request: unknown id %s", request_id[:8],
            )
            return False
        entry["decision"] = decision
        entry["reason"] = reason
        entry["event"].set()
        return True

    def set_request_timeout(self, request_id: str, timeout: float) -> None:
        entry = self.pending_requests.get(request_id)
        if entry:
            entry["timeout"] = timeout
