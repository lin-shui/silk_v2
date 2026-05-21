#!/usr/bin/env python3
"""Cursor ACP Bridge Adapter — relays ACP between Silk backend and `agent acp`.

Unlike cc_bridge/codex_bridge which translate proprietary CLI protocols into
ACP, this bridge is an ACP-to-ACP relay: the Cursor `agent acp` subprocess
natively speaks JSON-RPC 2.0 ACP on stdio, so we mostly forward messages
bidirectionally between the backend WebSocket and the subprocess stdio.

The main transformation needed is for permission requests:
- Cursor sends `session/request_permission` as a JSON-RPC **request** (with id)
- Silk backend expects `session/update` **notification** with sessionUpdate="permission_request"
- Resolution comes back from backend via `_silk/resolve_permission` request
- We convert it to a JSON-RPC **response** for the original permission request

Protocol direction (critical):
    Backend AcpClient → WebSocket → this adapter (ACP server) → stdio → agent acp
    agent acp → stdio → this adapter → WebSocket → Backend AcpClient
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import signal
import ssl
import sys
import uuid
from dataclasses import dataclass, field
from typing import Any

import websockets
from websockets.exceptions import ConnectionClosed

from cursor_acp_relay import AcpSubprocess
from fs_listing import list_directory

logger = logging.getLogger("cursor_bridge")

# Tool display icons (aligned with cc_bridge/executor.py)
TOOL_ICONS: dict[str, str] = {
    "Read": "\U0001f4d6",
    "Write": "\u270d\ufe0f",
    "Edit": "\U0001f4dd",
    "NotebookEdit": "\U0001f4d3",
    "Bash": "\U0001f4bb",
    "Glob": "\U0001f50d",
    "Grep": "\U0001f50d",
    "Task": "\U0001f916",
    "WebFetch": "\U0001f310",
    "Agent": "\U0001f916",
    "TodoWrite": "\U0001f4dd",
    "Find": "\U0001f50d",
}

PARAM_MAX: int = 60

_TERMINAL_STATUSES = frozenset({
    "completed", "complete", "succeeded", "success", "done",
    "failed", "error", "cancelled", "canceled",
})
_ERROR_STATUSES = frozenset({"failed", "error", "cancelled", "canceled"})
_IN_PROGRESS_STATUSES = frozenset({"in_progress", "pending", "running", ""})


# ---------------------------------------------------------------------------
# Per-ACP-session state
# ---------------------------------------------------------------------------


@dataclass
class ToolCallDisplay:
    """Remember first tool_call line for terminal tool_call_update (cc_bridge active_tool_ids)."""
    line: str
    tool: str
    kind: str


@dataclass
class AcpSession:
    """State held per ACP session (one per workflow group on backend)."""
    cwd: str
    # Cursor-side ACP session id (returned by Cursor's session/new)
    cursor_session_id: str | None = None
    # The AcpSubprocess instance bound to this session
    relay: AcpSubprocess | None = None
    cancelled: bool = False
    # Accumulated thinking text for this session (Cursor sends deltas,
    # but backend replaces-in-place via stableId so we must send full text)
    accumulated_thought: str = ""
    # toolCallId → display line captured at tool_call (like cc_bridge active_tool_ids)
    active_tools: dict[str, ToolCallDisplay] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# ACP Bridge Server
# ---------------------------------------------------------------------------


class CursorBridgeServer:
    """ACP server that relays between Silk backend and Cursor `agent acp`."""

    def __init__(
        self,
        ws_url: str,
        token: str,
        default_cwd: str,
        *,
        tls_insecure: bool = False,
    ) -> None:
        self.ws_url = _build_ws_url(ws_url, token)
        self.token = token
        self.default_cwd = _resolve_cwd(default_cwd)
        self.tls_insecure = tls_insecure

        self.ws: websockets.WebSocketClientProtocol | None = None
        self.sessions: dict[str, AcpSession] = {}

        # Pending permission requests: ctrl_request_id → asyncio.Future
        # Used to bridge Cursor's permission RPC ↔ Silk's _silk/resolve_permission
        self._pending_permissions: dict[str, asyncio.Future[dict[str, Any]]] = {}
        # Original permission options from Cursor (for building response)
        self._pending_perm_options: dict[str, list[dict[str, Any]]] = {}
        # Map: acp_session_id → cursor_request_id (for permission routing)
        self._pending_perm_rpc_ids: dict[str, Any] = {}

    # ------------------------------------------------------------------
    # Connection loop with exponential backoff
    # ------------------------------------------------------------------

    async def run(self) -> None:
        connect_kw: dict[str, Any] = {
            "ping_interval": 30,
            "ping_timeout": 10,
            "max_size": 10 * 1024 * 1024,
        }
        if self.ws_url.startswith("wss://") and self.tls_insecure:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            connect_kw["ssl"] = ctx

        delay = 1.0
        max_delay = 60.0

        while True:
            try:
                logger.info("[Bridge] Connecting to %s", self.ws_url)
                async with websockets.connect(self.ws_url, **connect_kw) as ws:
                    self.ws = ws
                    delay = 1.0
                    logger.info("[Bridge] Connected")
                    await self._receive_loop()
            except ConnectionClosed as exc:
                logger.warning("[Bridge] Connection closed: %s", exc)
            except (ConnectionRefusedError, OSError) as exc:
                logger.warning("[Bridge] Connection failed: %s", exc)
            except Exception as exc:
                logger.error("[Bridge] Unexpected error: %s", exc, exc_info=True)
            finally:
                self.ws = None
                await self._cleanup_all_sessions()
                self._fail_pending_permissions("Bridge disconnected")

            logger.info("[Bridge] Reconnecting in %.0fs", delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, max_delay)

    # ------------------------------------------------------------------
    # Receive loop (messages from backend)
    # ------------------------------------------------------------------

    async def _receive_loop(self) -> None:
        assert self.ws is not None
        async for raw in self.ws:
            if isinstance(raw, bytes):
                raw = raw.decode("utf-8", errors="replace")
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                logger.warning("[Bridge] Invalid JSON from backend: %s", str(raw)[:200])
                continue

            msg_id = msg.get("id")
            method = msg.get("method")

            logger.debug("[Bridge] ← backend: method=%s id=%s params=%s",
                         method, msg_id, str(msg.get("params"))[:300])

            if msg_id is not None and method is not None:
                task = asyncio.create_task(self._handle_request(msg_id, method, msg.get("params")))
                task.add_done_callback(_log_task_exception)
            elif method is not None:
                task = asyncio.create_task(self._handle_notification(method, msg.get("params")))
                task.add_done_callback(_log_task_exception)

    # ------------------------------------------------------------------
    # Send helpers (to backend WebSocket)
    # ------------------------------------------------------------------

    async def _send_response(self, msg_id: Any, result: Any) -> None:
        if self.ws is None:
            return
        await self.ws.send(json.dumps({"jsonrpc": "2.0", "id": msg_id, "result": result}))

    async def _send_error(self, msg_id: Any, code: int, message: str) -> None:
        if self.ws is None:
            return
        await self.ws.send(json.dumps({
            "jsonrpc": "2.0", "id": msg_id,
            "error": {"code": code, "message": message},
        }))

    async def _send_notification(self, method: str, params: Any) -> None:
        if self.ws is None:
            return
        logger.debug("[Bridge] → backend notif: method=%s params=%s",
                     method, str(params)[:300])
        await self.ws.send(json.dumps({"jsonrpc": "2.0", "method": method, "params": params}))

    # ------------------------------------------------------------------
    # Request routing (from backend)
    # ------------------------------------------------------------------

    async def _handle_request(self, msg_id: Any, method: str, params: Any) -> None:
        try:
            if method == "initialize":
                await self._handle_initialize(msg_id, params)
            elif method == "session/new":
                await self._handle_session_new(msg_id, params)
            elif method == "session/prompt":
                await self._handle_session_prompt(msg_id, params)
            elif method == "_silk/set_cwd":
                await self._handle_silk_set_cwd(msg_id, params)
            elif method == "_silk/list_dir":
                await self._handle_silk_list_dir(msg_id, params)
            elif method == "_silk/resolve_permission":
                await self._handle_silk_resolve_permission(msg_id, params)
            elif method == "_silk/resolve_question":
                await self._handle_silk_resolve_question(msg_id, params)
            elif method == "session/request_permission":
                # Backend's own permission request — auto-approve
                await self._send_response(
                    msg_id, {"outcome": {"kind": "selected", "optionId": "approve"}}
                )
            elif method == "_silk/compact":
                await self._send_error(msg_id, -32601, "Cursor does not support compact")
            elif method == "_silk/list_local_sessions":
                await self._send_response(msg_id, {"sessions": []})
            else:
                await self._send_error(msg_id, -32601, f"Method not found: {method}")
        except Exception as exc:
            logger.error("[Bridge] Handler error for %s: %s", method, exc, exc_info=True)
            try:
                await self._send_error(msg_id, -32000, f"Handler exception: {exc}")
            except Exception:
                pass

    async def _handle_notification(self, method: str, params: Any) -> None:
        if method == "session/cancel":
            await self._handle_session_cancel(params)
        else:
            logger.debug("[Bridge] Ignoring unknown notification: %s", method)

    # ------------------------------------------------------------------
    # initialize — respond to backend, don't spawn subprocess yet
    # ------------------------------------------------------------------

    async def _handle_initialize(self, msg_id: Any, params: Any) -> None:
        await self._send_response(msg_id, {
            "protocolVersion": "0.2",
            "agentCapabilities": {
                "loadSession": False,
                "promptCapabilities": {
                    "image": False,
                    "audio": False,
                    "embeddedContext": False,
                },
                "_silk": {
                    "compact": False,
                    "listLocalSessions": False,
                    "setCwd": True,
                    "listDir": True,
                    "resolveQuestion": True,
                    "resolvePermission": True,
                },
            },
        })

    # ------------------------------------------------------------------
    # session/new — 只记录 cwd，不 spawn 子进程（lazy spawn）
    # ------------------------------------------------------------------

    async def _handle_session_new(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        cwd = p.get("cwd") or self.default_cwd
        cwd = os.path.realpath(cwd)
        acp_session_id = str(uuid.uuid4())

        sess = AcpSession(cwd=cwd)
        self.sessions[acp_session_id] = sess

        logger.info("[Bridge] session/new (lazy): acp=%s cwd=%s", acp_session_id, cwd)
        await self._send_response(msg_id, {"sessionId": acp_session_id})

    # ------------------------------------------------------------------
    # _ensure_relay — Lazy spawn: start subprocess if not running
    # ------------------------------------------------------------------

    async def _ensure_relay(self, acp_session_id: str, sess: AcpSession) -> bool:
        """Ensure the Cursor subprocess is running for this session.

        If the relay is already alive, just return True.
        If dead or None, spawn a new one (initialize + session/new).
        Returns False on failure.
        """
        if sess.relay and sess.relay.alive:
            return True

        # Cleanup stale relay
        if sess.relay:
            await sess.relay.stop()
            sess.relay = None
            sess.cursor_session_id = None

        try:
            relay = AcpSubprocess(
                on_notification=self._make_notification_handler(acp_session_id),
                on_server_request=self._make_server_request_handler(acp_session_id),
            )
            await relay.start(sess.cwd)
            sess.relay = relay

            # 1. Initialize with Cursor
            init_result = await relay.call("initialize", {
                "protocolVersion": 1,
                "clientCapabilities": {
                    "fs": {"readTextFile": False, "writeTextFile": False},
                    "terminal": False,
                },
                "clientInfo": {"name": "silk-cursor-bridge", "version": "1.0.0"},
            })

            # V2 探测：记录 Cursor 是否支持 session/load
            caps = init_result.get("agentCapabilities", {})
            relay.supports_load_session = bool(caps.get("loadSession", False))
            logger.info(
                "[Bridge] Cursor initialized (loadSession=%s): %s",
                relay.supports_load_session, json.dumps(init_result)[:200],
            )

            # 2. Create session with Cursor
            new_result = await relay.call("session/new", {
                "cwd": sess.cwd,
                "mcpServers": [],
            })
            sess.cursor_session_id = new_result.get("sessionId", "")
            logger.info(
                "[Bridge] Cursor session created: acp=%s cursor=%s",
                acp_session_id, sess.cursor_session_id,
            )
            return True

        except Exception as exc:
            logger.error("[Bridge] Failed to start Cursor: %s", exc, exc_info=True)
            if sess.relay:
                await sess.relay.stop()
                sess.relay = None
            return False

    # ------------------------------------------------------------------
    # session/cancel
    # ------------------------------------------------------------------

    async def _handle_session_cancel(self, params: Any) -> None:
        sid = (params or {}).get("sessionId")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            return
        sess.cancelled = True
        if sess.relay and sess.relay.alive and sess.cursor_session_id:
            try:
                await sess.relay.send_notification("session/cancel", {
                    "sessionId": sess.cursor_session_id,
                })
            except Exception as exc:
                logger.warning("[Bridge] Failed to forward cancel: %s", exc)

    # ------------------------------------------------------------------
    # session/prompt — forward to Cursor, relay updates back
    # ------------------------------------------------------------------

    async def _handle_session_prompt(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        sid = p.get("sessionId")
        sess = self.sessions.get(sid)
        if sess is None:
            await self._send_error(msg_id, -32602, f"Unknown session: {sid}")
            return

        # Lazy spawn: ensure subprocess is running (auto-restart if crashed/idle-killed)
        if not await self._ensure_relay(sid, sess):
            await self._send_error(msg_id, -32000, "Failed to start Cursor subprocess")
            return

        prompt_blocks = p.get("prompt", []) or []
        prompt_text = "".join(
            b.get("text", "") for b in prompt_blocks
            if isinstance(b, dict) and b.get("type") == "text"
        )

        sess.cancelled = False
        sess.accumulated_thought = ""
        sess.active_tools.clear()

        logger.info("[Bridge] >>> session/prompt START msg_id=%s sid=%s prompt=%s",
                    msg_id, sid[:8] if sid else "?", prompt_text[:100])

        # Pause idle timer during prompt execution
        if sess.relay._idle_task and not sess.relay._idle_task.done():
            sess.relay._idle_task.cancel()

        try:
            result = await sess.relay.call(
                "session/prompt",
                {
                    "sessionId": sess.cursor_session_id,
                    "prompt": [{"type": "text", "text": prompt_text}],
                },
                timeout=36000.0,  # Essentially unlimited
            )
            stop_reason = result.get("stopReason", "end_turn")
            response: dict[str, Any] = {"stopReason": stop_reason}
            meta = result.get("meta")
            if meta:
                response["meta"] = meta
            logger.info("[Bridge] <<< session/prompt END msg_id=%s stopReason=%s",
                        msg_id, stop_reason)
            await self._send_response(msg_id, response)
        except asyncio.CancelledError:
            await self._send_response(msg_id, {"stopReason": "cancelled"})
        except Exception as exc:
            logger.error("[Bridge] session/prompt error: %s", exc, exc_info=True)
            if sess.cancelled:
                await self._send_response(msg_id, {"stopReason": "cancelled"})
            else:
                await self._send_error(msg_id, -32000, f"Cursor prompt error: {exc}")
        finally:
            # Restart idle timer after prompt completes
            if sess.relay and sess.relay.alive:
                sess.relay.reset_idle_timer()

    # ------------------------------------------------------------------
    # Cursor → Backend notification relay
    # ------------------------------------------------------------------

    def _make_notification_handler(self, acp_session_id: str):
        """Create handler for notifications from Cursor subprocess.

        Cursor speaks native ACP, but its session/update format differs from
        what the Silk backend (AcpUpdateMapper) expects in a few ways:

        1. tool_call: Cursor sends ``kind`` (e.g. "bash"), backend reads ``tool``
        2. tool_call_update: Cursor sends ``content`` as an ARRAY of blocks,
           backend expects a single ``{type, text}`` object
        3. thinking/reasoning: Cursor may send ``reasoning`` or
           ``agent_thinking_chunk`` instead of ``agent_thought_chunk``;
           backend uses a fixed stableId that replaces-in-place, so we must
           send the accumulated full text, not just the delta
        """

        async def handler(method: str, params: Any) -> None:
            if method == "session/update":
                if isinstance(params, dict):
                    params = dict(params)
                    params["sessionId"] = acp_session_id
                    update = params.get("update")
                    if isinstance(update, dict):
                        update = dict(update)
                        adapted = _adapt_cursor_update(
                            update, self.sessions.get(acp_session_id),
                        )
                        if adapted is None:
                            return
                        params["update"] = adapted
                update_type = params.get("update", {}).get("sessionUpdate", "?") if isinstance(params, dict) else "?"
                logger.debug("[Bridge] relay Cursor→backend: session/update type=%s", update_type)
                await self._send_notification("session/update", params)
            else:
                logger.debug("[Bridge] Ignoring Cursor notification: %s", method)

        return handler

    # ------------------------------------------------------------------
    # Cursor → Backend server request relay (permission + cursor/*)
    # ------------------------------------------------------------------

    def _make_server_request_handler(self, acp_session_id: str):
        """Create handler for server requests from Cursor subprocess."""

        async def handler(rpc_id: Any, method: str, params: Any) -> None:
            sess = self.sessions.get(acp_session_id)
            if sess is None or sess.relay is None:
                return

            if method == "session/request_permission":
                await self._handle_cursor_permission_request(
                    acp_session_id, sess, rpc_id, params,
                )
            elif method.startswith("cursor/"):
                # Cursor-specific extensions: acknowledge with empty response
                logger.debug("[Bridge] cursor/* extension ack: %s", method)
                await sess.relay.respond(rpc_id, {})
            else:
                logger.info("[Bridge] Unhandled Cursor server request: %s", method)
                await sess.relay.respond_error(rpc_id, -32601, f"Not implemented: {method}")

        return handler

    async def _handle_cursor_permission_request(
        self,
        acp_session_id: str,
        sess: AcpSession,
        rpc_id: Any,
        params: Any,
    ) -> None:
        """Convert Cursor's permission RPC to Silk's session/update notification.

        Cursor sends:
            {id: N, method: "session/request_permission", params: {
                sessionId, toolCall: {toolCallId, title, kind, rawInput},
                options: [{optionId, name, kind}, ...]
            }}

        We convert to Silk's expected format:
            session/update notification with sessionUpdate="permission_request"

        Then wait for backend's _silk/resolve_permission, and convert
        the decision back to a JSON-RPC response for Cursor.
        """
        p = params or {}
        tool_call = p.get("toolCall", {})
        options = p.get("options", [])

        tool_name = tool_call.get("title") or tool_call.get("kind", "Unknown")
        raw_input = tool_call.get("rawInput", {})
        tool_call_id = tool_call.get("toolCallId", "")

        # Summarize tool input for display
        tool_input_display = _summarize_tool_input(tool_call.get("kind", ""), raw_input)

        ctrl_request_id = str(uuid.uuid4())

        # Store state for resolution
        fut: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()
        self._pending_permissions[ctrl_request_id] = fut
        self._pending_perm_options[ctrl_request_id] = options
        self._pending_perm_rpc_ids[ctrl_request_id] = rpc_id

        logger.info(
            "[Bridge] Permission request: tool=%s, ctrl_id=%s, session=%s",
            tool_name, ctrl_request_id[:8], acp_session_id[:8],
        )

        # Forward as session/update notification to backend
        await self._send_notification("session/update", {
            "sessionId": acp_session_id,
            "update": {
                "sessionUpdate": "permission_request",
                "requestId": ctrl_request_id,
                "toolName": tool_name,
                "toolInput": {
                    "command": tool_input_display,
                } if tool_input_display else raw_input,
            },
        })

        # Wait for backend resolution
        try:
            decision = await asyncio.wait_for(fut, timeout=300.0)
        except asyncio.TimeoutError:
            logger.warning("[Bridge] Permission request timed out: %s", ctrl_request_id[:8])
            decision = {"decision": "deny"}
        finally:
            self._pending_permissions.pop(ctrl_request_id, None)
            self._pending_perm_options.pop(ctrl_request_id, None)
            self._pending_perm_rpc_ids.pop(ctrl_request_id, None)

        # Convert decision to Cursor's expected response format
        if decision.get("decision") == "allow":
            option_id = _pick_permission_option_id(True, options)
            response = {"outcome": {"outcome": "selected", "optionId": option_id}}
        else:
            option_id = _pick_permission_option_id(False, options)
            if option_id:
                response = {"outcome": {"outcome": "selected", "optionId": option_id}}
            else:
                response = {"outcome": {"outcome": "cancelled"}}

        await sess.relay.respond(rpc_id, response)

    # ------------------------------------------------------------------
    # _silk/resolve_permission — from backend
    # ------------------------------------------------------------------

    async def _handle_silk_resolve_permission(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        request_id = p.get("requestId", "")
        decision = p.get("decision", "deny")

        if not request_id:
            await self._send_error(msg_id, -32602, "Missing requestId")
            return

        fut = self._pending_permissions.get(request_id)
        if fut is None or fut.done():
            await self._send_error(msg_id, -32602, f"Unknown request: {request_id}")
            return

        fut.set_result({"decision": decision})
        logger.info("[Bridge] resolve_permission: %s → %s", request_id[:8], decision)
        await self._send_response(msg_id, {"ok": True})

    # ------------------------------------------------------------------
    # _silk/resolve_question — from backend (for ask_user_question)
    # ------------------------------------------------------------------

    async def _handle_silk_resolve_question(self, msg_id: Any, params: Any) -> None:
        """Resolve an AskUserQuestion.

        For Cursor, ask_user_question comes as a session/update notification
        that we relayed to the backend. The backend resolves via this method.
        Since Cursor doesn't use control_request/control_response like Claude,
        we need to find the pending permission future and resolve it.
        """
        p = params or {}
        request_id = p.get("requestId", "")
        answer = p.get("answer", "")

        if not request_id:
            await self._send_error(msg_id, -32602, "Missing requestId")
            return

        fut = self._pending_permissions.get(request_id)
        if fut is None or fut.done():
            await self._send_error(msg_id, -32602, f"Unknown request: {request_id}")
            return

        fut.set_result({"decision": "allow", "answer": answer})
        logger.info("[Bridge] resolve_question: %s", request_id[:8])
        await self._send_response(msg_id, {"ok": True})

    # ------------------------------------------------------------------
    # _silk/set_cwd
    # ------------------------------------------------------------------

    async def _handle_silk_set_cwd(self, msg_id: Any, params: Any) -> None:
        sid = (params or {}).get("sessionId")
        cwd = (params or {}).get("cwd")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            await self._send_error(msg_id, -32602, f"Unknown session: {sid}")
            return
        if not cwd:
            await self._send_error(msg_id, -32602, "Missing cwd")
            return
        resolved = os.path.realpath(cwd)
        if not os.path.isdir(resolved):
            await self._send_error(msg_id, -32602, f"Not a directory: {resolved}")
            return

        # Kill old subprocess, create new one with new cwd
        if sess.relay:
            await sess.relay.stop()
            sess.relay = None
        sess.cwd = resolved
        sess.cursor_session_id = None

        logger.info("[Bridge] _silk/set_cwd sid=%s cwd=%s", sid, resolved)
        await self._send_response(msg_id, {"ok": True, "path": resolved})

    # ------------------------------------------------------------------
    # _silk/list_dir
    # ------------------------------------------------------------------

    async def _handle_silk_list_dir(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        path = p.get("path") or self.default_cwd
        show_hidden = bool(p.get("showHidden", False))
        result = list_directory(path, show_hidden)
        if not result.get("success") and not p.get("path"):
            for fallback in (os.path.expanduser("~"), "/"):
                if fallback != path:
                    result = list_directory(fallback, show_hidden)
                    if result.get("success"):
                        break
        await self._send_response(msg_id, result)

    # ------------------------------------------------------------------
    # Cleanup
    # ------------------------------------------------------------------

    async def _cleanup_all_sessions(self) -> None:
        """Kill all Cursor subprocesses."""
        for sid, sess in list(self.sessions.items()):
            if sess.relay:
                try:
                    await sess.relay.stop()
                except Exception:
                    pass
                sess.relay = None
        self.sessions.clear()

    def _fail_pending_permissions(self, reason: str) -> None:
        for req_id, fut in list(self._pending_permissions.items()):
            if not fut.done():
                fut.set_result({"decision": "deny", "reason": reason})
        self._pending_permissions.clear()
        self._pending_perm_options.clear()
        self._pending_perm_rpc_ids.clear()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _adapt_cursor_update(
    update: dict[str, Any],
    sess: AcpSession | None,
) -> dict[str, Any] | None:
    """Transform Cursor's native session/update into the format AcpUpdateMapper expects.

    Returns None to drop an update (e.g. in_progress tool_call_update with no UI change).

    Handles format differences:
    1. tool_call: normalize fields + remember display line (cc_bridge active_tool_ids)
    2. tool_call_update: terminal line is ``{original_line} → ✅/❌`` like cc_bridge executor
    3. thinking: accumulate deltas for replace-in-place stableId
    """
    kind = update.get("sessionUpdate", "")

    # --- tool_call: format line + remember for terminal update (cc_bridge tool_log start) ---
    if kind == "tool_call":
        tool_call_id = update.get("toolCallId", "") or ""
        kind_raw = update.get("kind", "") or ""
        title = update.get("title", "") or ""
        raw_input = update.get("rawInput")
        tool = _normalize_tool_kind(kind_raw) or title or "Tool"
        line = _format_cursor_tool_line(kind_raw, title, raw_input)

        if sess is not None and tool_call_id and tool_call_id not in sess.active_tools:
            sess.active_tools[tool_call_id] = ToolCallDisplay(line=line, tool=tool, kind=kind_raw)

        update["tool"] = tool
        update["title"] = line
        logger.info(
            "[Adapt] tool_call: tool=%s line=%s toolCallId=%s status=%s",
            tool, line[:120], tool_call_id[:16] if tool_call_id else "?",
            update.get("status"),
        )
        return update

    # --- tool_call_update: cc_bridge-style terminal suffix on remembered line ---
    if kind == "tool_call_update":
        tool_call_id = update.get("toolCallId", "") or ""
        status = (update.get("status") or "").lower().strip()
        raw_content = update.get("content")
        result_text = _flatten_tool_content(raw_content)

        logger.info(
            "[Adapt] tool_call_update BEFORE: toolCallId=%s status=%s content_type=%s",
            tool_call_id[:16] if tool_call_id else "?",
            status, type(raw_content).__name__,
        )

        if status in _IN_PROGRESS_STATUSES:
            logger.debug("[Adapt] tool_call_update: drop in_progress (status=%s)", status)
            return None

        if status not in _TERMINAL_STATUSES:
            logger.debug("[Adapt] tool_call_update: drop unknown status=%s", status)
            return None

        info: ToolCallDisplay | None = None
        if sess is not None and tool_call_id:
            info = sess.active_tools.pop(tool_call_id, None)

        if info is not None:
            line = info.line
        else:
            line = _format_cursor_tool_line(
                update.get("kind", "") or "",
                update.get("title", "") or "",
                update.get("rawInput"),
            )

        is_error = (
            status in _ERROR_STATUSES
            or bool(update.get("isError"))
            or bool(update.get("is_error"))
        )
        if is_error:
            summary = ""
            if result_text:
                summary = result_text.strip().split("\n", 1)[0][:PARAM_MAX]
            suffix = f" \u2192 \u274c {summary}" if summary else " \u2192 \u274c"
        else:
            suffix = " \u2192 \u2705"

        terminal_line = f"{line}{suffix}"
        update["content"] = {"type": "text", "text": terminal_line}
        logger.info("[Adapt] tool_call_update AFTER: %s", terminal_line[:300])
        return update

    # --- thinking / reasoning → agent_thought_chunk with accumulated text ---
    if kind in ("reasoning", "reasoning_chunk", "thinking", "agent_thinking_chunk",
                "agent_thought_chunk"):
        content = update.get("content", {})
        delta = ""
        if isinstance(content, dict):
            delta = content.get("text", "")
        elif isinstance(content, str):
            delta = content
        # Also check top-level ``text`` field
        if not delta:
            delta = update.get("text", "")

        if sess is not None and delta:
            sess.accumulated_thought += delta
        full_text = sess.accumulated_thought if sess else delta

        update["sessionUpdate"] = "agent_thought_chunk"
        update["content"] = {"type": "text", "text": full_text}
        return update

    return update


def _build_ws_url(server: str, token: str) -> str:
    host = server.rstrip("/")
    lower = host.lower()
    if lower.startswith("wss://"):
        ws_scheme, host = "wss", host[6:]
    elif lower.startswith("https://"):
        ws_scheme, host = "wss", host[8:]
    elif lower.startswith("ws://"):
        ws_scheme, host = "ws", host[5:]
    elif lower.startswith("http://"):
        ws_scheme, host = "ws", host[7:]
    else:
        ws_scheme = "ws"
    return f"{ws_scheme}://{host}/agent-bridge?agentType=cursor&token={token}"


def _resolve_cwd(candidate: str) -> str:
    resolved = os.path.realpath(candidate)
    if os.path.isdir(resolved):
        return resolved
    home = os.path.expanduser("~")
    if os.path.isdir(home):
        return home
    return "/"


def _normalize_tool_kind(kind: str) -> str:
    """Map Cursor tool kinds to cc_bridge-style tool names for icons."""
    k = (kind or "").strip()
    if not k:
        return ""
    aliases = {
        "read file": "Read",
        "read": "Read",
        "write": "Write",
        "edit": "Edit",
        "bash": "Bash",
        "shell": "Bash",
        "glob": "Glob",
        "grep": "Grep",
        "find": "Find",
        "task": "Task",
        "webfetch": "WebFetch",
        "agent": "Agent",
        "todowrite": "TodoWrite",
    }
    return aliases.get(k.lower(), k)


def _format_cursor_tool_line(kind: str, title: str, raw_input: Any) -> str:
    """Format a tool invocation line like cc_bridge format_tool_call."""
    tool_name = _normalize_tool_kind(kind) or (title or "").strip() or "Tool"
    icon = TOOL_ICONS.get(tool_name, "\U0001f527")

    param = _summarize_tool_input(kind, raw_input)
    title_stripped = (title or "").strip()
    kind_lower = (kind or "").strip().lower()
    if not param and title_stripped and title_stripped.lower() != kind_lower:
        param = title_stripped

    if len(param) > PARAM_MAX:
        display = param[: PARAM_MAX - 3] + "..."
    else:
        display = param

    if display:
        return f"{icon} {tool_name} `{display}`"
    return f"{icon} {tool_name}"


def _flatten_tool_content(raw_content: Any) -> str:
    """Extract text from Cursor tool_call_update content (array, object, or string)."""
    if raw_content is None:
        return ""
    if isinstance(raw_content, str):
        return raw_content
    if isinstance(raw_content, dict):
        if raw_content.get("type") == "text":
            return str(raw_content.get("text", "") or "")
        return ""
    if isinstance(raw_content, list):
        parts: list[str] = []
        for block in raw_content:
            if isinstance(block, dict):
                inner = block.get("content")
                if isinstance(inner, dict):
                    t = inner.get("text", "")
                    if t:
                        parts.append(str(t))
                elif isinstance(block.get("text"), str):
                    parts.append(block["text"])
        return "\n".join(parts)
    return ""


def _summarize_tool_input(kind: str, raw_input: Any) -> str:
    """Extract human-readable summary from tool input."""
    if not isinstance(raw_input, dict):
        return ""
    for key in (
        "command", "file_path", "notebook_path", "path", "pattern",
        "description", "url", "query",
    ):
        val = raw_input.get(key)
        if isinstance(val, str) and val:
            return val
    for v in raw_input.values():
        if isinstance(v, str) and v:
            return v
    return ""


def _pick_permission_option_id(allow: bool, options: list[dict[str, Any]]) -> str:
    """Pick the best option ID for allow/deny from Cursor's options list.

    Follows cc-connect's logic: match by kind, then name, then position.
    """
    if not options:
        return ""

    target_kinds = ["allow"] if allow else ["reject", "deny"]
    for opt in options:
        opt_kind = (opt.get("kind") or "").lower()
        if any(t in opt_kind for t in target_kinds):
            return opt.get("optionId", "")

    for opt in options:
        opt_name = (opt.get("name") or "").lower()
        if any(t in opt_name for t in target_kinds):
            return opt.get("optionId", "")

    # Fallback: first for allow, last for deny
    if allow:
        return options[0].get("optionId", "")
    return options[-1].get("optionId", "")


def _log_task_exception(task: asyncio.Task) -> None:
    try:
        exc = task.exception()
    except (asyncio.CancelledError, asyncio.InvalidStateError):
        return
    if exc is not None:
        logger.error("[Bridge] Background task error: %s", exc, exc_info=exc)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Cursor ACP Bridge: relay ACP between Silk backend and `agent acp`",
    )
    parser.add_argument(
        "--server", required=True,
        help="Silk backend address, e.g. localhost:8006 or https://host:port",
    )
    parser.add_argument(
        "--token", required=True,
        help="Authentication token for the bridge connection",
    )
    parser.add_argument(
        "--working-dir", default=os.getcwd(),
        help="Default working directory for Cursor (default: cwd)",
    )
    parser.add_argument(
        "--log-level", default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Log level (default: INFO)",
    )
    parser.add_argument(
        "--tls-insecure", action="store_true",
        help="Skip TLS certificate verification (self-signed cert)",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    server = CursorBridgeServer(
        ws_url=args.server,
        token=args.token,
        default_cwd=args.working_dir,
        tls_insecure=bool(args.tls_insecure),
    )

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    shutdown_event = asyncio.Event()

    def _signal_handler() -> None:
        logger.info("[Bridge] Received shutdown signal")
        shutdown_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _signal_handler)
        except NotImplementedError:
            pass

    async def _runner() -> None:
        run_task = asyncio.create_task(server.run())
        shutdown_task = asyncio.create_task(shutdown_event.wait())
        done, pending = await asyncio.wait(
            {run_task, shutdown_task}, return_when=asyncio.FIRST_COMPLETED,
        )
        for t in pending:
            t.cancel()
        for t in pending:
            try:
                await t
            except asyncio.CancelledError:
                pass

    try:
        loop.run_until_complete(_runner())
    finally:
        loop.close()


if __name__ == "__main__":
    main()
