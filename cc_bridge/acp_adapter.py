#!/usr/bin/env python3
"""ACP Bridge Adapter — ACP server that bridges Silk backend to Claude CLI.

Connects to backend `/agent-bridge` via WebSocket, speaks ACP (Zed Agent Client
Protocol) over JSON-RPC. Receives requests/notifications from the backend,
delegates Claude CLI execution to :mod:`cc_bridge.executor`, and pushes
``session/update`` notifications back during streaming.

Protocol direction (critical):
    backend `AcpClient` is the ACP **client** — it sends requests.
    this adapter is the ACP **server** — it handles requests + pushes
    notifications. The adapter never originates requests.
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

from executor import Executor
from fs_listing import list_directory
from session_manager import SessionManager

logger = logging.getLogger("acp_bridge")


# ---------------------------------------------------------------------------
# Per-ACP-session state
# ---------------------------------------------------------------------------


@dataclass
class AcpSession:
    """State held per ACP session id (one per workflow group on backend)."""

    cwd: str
    cc_session_id: str | None = None  # Claude CLI's real session id (from `complete.meta`)
    accumulated: str = ""  # full streamed text so far — used to compute deltas
    seen_tool_ids: set[str] = field(default_factory=set)
    cancelled: bool = False
    request_id: str | None = None  # current in-flight executor request id


# ---------------------------------------------------------------------------
# ACP server
# ---------------------------------------------------------------------------


class AcpAgentServer:
    """ACP server that translates backend requests into Claude CLI execution."""

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
        self.default_cwd = os.path.realpath(default_cwd)
        self.tls_insecure = tls_insecure

        self.ws: websockets.WebSocketClientProtocol | None = None
        self.executor = Executor()
        self.session_manager = SessionManager()
        self.sessions: dict[str, AcpSession] = {}

    # ------------------------------------------------------------------
    # Connect loop with exponential backoff
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
            logger.warning(
                "[ACP] TLS certificate verification disabled (self-signed/internal use only)"
            )

        delay = 1.0
        max_delay = 60.0

        while True:
            try:
                logger.info("[ACP] Connecting to %s", self.ws_url)
                async with websockets.connect(self.ws_url, **connect_kw) as ws:
                    self.ws = ws
                    delay = 1.0
                    logger.info("[ACP] Connected")
                    await self._receive_loop()
                logger.info("[ACP] WebSocket closed cleanly")
            except ConnectionClosed as exc:
                logger.warning("[ACP] Connection closed: %s", exc)
            except (ConnectionRefusedError, OSError) as exc:
                logger.warning("[ACP] Connection failed: %s", exc)
            except Exception as exc:
                logger.error("[ACP] Unexpected error: %s", exc, exc_info=True)
            finally:
                self.ws = None

            logger.info("[ACP] Reconnecting in %.0fs", delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, max_delay)

    # ------------------------------------------------------------------
    # Receive loop — fire-and-forget dispatch so notifications never block
    # ------------------------------------------------------------------

    async def _receive_loop(self) -> None:
        assert self.ws is not None
        async for raw in self.ws:
            if isinstance(raw, bytes):
                raw = raw.decode("utf-8", errors="replace")
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                logger.warning("[ACP] Invalid JSON: %s", str(raw)[:200])
                continue

            msg_id = msg.get("id")
            method = msg.get("method")

            if msg_id is not None and method is not None:
                # Incoming request — dispatch in a task so we don't block reads
                # (critical: a session/cancel notification must reach us while a
                # session/prompt request is still being processed)
                task = asyncio.create_task(
                    self._handle_request(msg_id, method, msg.get("params"))
                )
                task.add_done_callback(_log_task_exception)
            elif method is not None:
                # Notification (no id, no response expected)
                task = asyncio.create_task(
                    self._handle_notification(method, msg.get("params"))
                )
                task.add_done_callback(_log_task_exception)
            # Responses (id without method) cannot occur — the adapter never
            # sends requests, so the backend never replies to us.

    # ------------------------------------------------------------------
    # Send helpers
    # ------------------------------------------------------------------

    async def _send_response(self, msg_id: Any, result: Any) -> None:
        if self.ws is None:
            return
        await self.ws.send(
            json.dumps({"jsonrpc": "2.0", "id": msg_id, "result": result})
        )

    async def _send_error(self, msg_id: Any, code: int, message: str) -> None:
        if self.ws is None:
            return
        await self.ws.send(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "error": {"code": code, "message": message},
                }
            )
        )

    async def _send_notification(self, method: str, params: Any) -> None:
        if self.ws is None:
            return
        await self.ws.send(
            json.dumps({"jsonrpc": "2.0", "method": method, "params": params})
        )

    # ------------------------------------------------------------------
    # Request routing
    # ------------------------------------------------------------------

    async def _handle_request(self, msg_id: Any, method: str, params: Any) -> None:
        try:
            if method == "initialize":
                await self._handle_initialize(msg_id, params)
            elif method == "session/new":
                await self._handle_session_new(msg_id, params)
            elif method == "session/load":
                await self._handle_session_load(msg_id, params)
            elif method == "session/prompt":
                await self._handle_session_prompt(msg_id, params)
            elif method == "session/request_permission":
                await self._handle_request_permission(msg_id, params)
            elif method == "_silk/compact":
                await self._handle_silk_compact(msg_id, params)
            elif method == "_silk/list_local_sessions":
                await self._handle_silk_list_sessions(msg_id, params)
            elif method == "_silk/set_cwd":
                await self._handle_silk_set_cwd(msg_id, params)
            elif method == "_silk/list_dir":
                await self._handle_silk_list_dir(msg_id, params)
            else:
                await self._send_error(msg_id, -32601, f"Method not found: {method}")
        except Exception as exc:
            logger.error("[ACP] Handler error for %s: %s", method, exc, exc_info=True)
            try:
                await self._send_error(msg_id, -32000, f"Handler exception: {exc}")
            except Exception:
                pass

    async def _handle_notification(self, method: str, params: Any) -> None:
        if method == "session/cancel":
            await self._handle_session_cancel(params)
        else:
            logger.debug("[ACP] Ignoring unknown notification: %s", method)

    # ------------------------------------------------------------------
    # initialize
    # ------------------------------------------------------------------

    async def _handle_initialize(self, msg_id: Any, params: Any) -> None:
        await self._send_response(
            msg_id,
            {
                "protocolVersion": "0.2",
                "agentCapabilities": {
                    "loadSession": True,
                    "promptCapabilities": {
                        "image": False,
                        "audio": False,
                        "embeddedContext": False,
                    },
                    "_silk": {
                        "compact": True,
                        "listLocalSessions": True,
                        "setCwd": True,
                        "listDir": True,
                    },
                },
            },
        )

    # ------------------------------------------------------------------
    # session/new
    # ------------------------------------------------------------------

    async def _handle_session_new(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        cwd = p.get("cwd") or self.default_cwd
        cc_session_id = p.get("ccSessionId")
        acp_session_id = str(uuid.uuid4())
        sess = AcpSession(cwd=os.path.realpath(cwd))
        if cc_session_id:
            # backend seed：续旧 CC session（重启后从 WorkflowPersistence 拿来的 cc_session_id）
            # 下次 session/prompt 会因 sess.cc_session_id 非空而走 resume=True
            sess.cc_session_id = cc_session_id
        self.sessions[acp_session_id] = sess
        logger.info(
            "[ACP] session/new: %s cwd=%s cc_seed=%s",
            acp_session_id, cwd, (cc_session_id or "")[:8],
        )
        await self._send_response(msg_id, {"sessionId": acp_session_id})

    # ------------------------------------------------------------------
    # session/load — resume a tracked Claude CLI session by id (or prefix)
    # ------------------------------------------------------------------

    async def _handle_session_load(self, msg_id: Any, params: Any) -> None:
        """Bind a backend ACP session to a known Claude CLI session.

        ``params.sessionId`` is the Claude CLI session id (or a unique prefix
        thereof) — Silk persists these in ``~/.silk/cc_sessions.json`` via
        :class:`SessionManager`. We look it up there; if found, we mint a
        fresh ACP UUID and seed ``cc_session_id`` so the next prompt runs
        ``claude --resume <session_id>``. Working directory falls back to the
        record's persisted ``workingDir`` when the caller's ``cwd`` is empty.
        """
        p = params or {}
        prefix = p.get("sessionId")
        cwd = p.get("cwd") or self.default_cwd
        if not prefix:
            await self._send_error(msg_id, -32602, "missing sessionId")
            return
        record = await asyncio.to_thread(self.session_manager.resume_session, prefix)
        if record is None:
            await self._send_error(
                msg_id, -32602, f"claude session not found: {prefix}"
            )
            return
        cc_session_id = record.get("sessionId") or prefix
        # Prefer the caller's cwd; fall back to the persisted workingDir
        # (Silk often calls session/load right after a fresh workflow open
        # where no cwd has been negotiated yet).
        if not cwd:
            cwd = record.get("workingDir") or self.default_cwd
        acp_session_id = str(uuid.uuid4())
        sess = AcpSession(cwd=os.path.realpath(cwd), cc_session_id=cc_session_id)
        self.sessions[acp_session_id] = sess
        logger.info(
            "[ACP] session/load: acp=%s cc_sid=%s",
            acp_session_id, cc_session_id[:8],
        )
        await self._send_response(
            msg_id, {"sessionId": acp_session_id, "loaded": True}
        )
    # ------------------------------------------------------------------

    async def _handle_session_cancel(self, params: Any) -> None:
        sid = (params or {}).get("sessionId")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            logger.debug("[ACP] session/cancel for unknown session: %s", sid)
            return
        sess.cancelled = True
        killed = await self.executor.cancel()
        logger.info("[ACP] session/cancel sid=%s killed=%s", sid, killed)

    # ------------------------------------------------------------------
    # session/prompt — the core: stream executor events as session/update
    # ------------------------------------------------------------------

    async def _handle_session_prompt(self, msg_id: Any, params: Any) -> None:
        sid = (params or {}).get("sessionId")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            await self._send_error(msg_id, -32602, f"Unknown session: {sid}")
            return

        # Extract prompt text from ACP content blocks
        prompt_blocks = (params or {}).get("prompt", []) or []
        prompt_text = "".join(
            b.get("text", "")
            for b in prompt_blocks
            if isinstance(b, dict) and b.get("type") == "text"
        )

        # Reset per-prompt state
        sess.accumulated = ""
        sess.seen_tool_ids.clear()
        sess.cancelled = False
        request_id = str(uuid.uuid4())
        sess.request_id = request_id

        # Terminal-event capture
        final_result: dict[str, Any] = {}
        done = asyncio.Event()

        # NOTE: on_session_upsert is called *synchronously* by the executor —
        # must be a regular `def`, not `async def`.
        def on_session_upsert(cc_sid: str, wdir: str, title: str) -> None:
            sess.cc_session_id = cc_sid

        notify_send = self._make_notify_send(sid)

        async def send_with_terminal_capture(payload: dict) -> None:
            evt = payload.get("type", "")
            if evt == "complete":
                logger.info("[ACP] executor complete sid=%s meta=%s", sid, payload.get("meta"))
                final_result["stopReason"] = "end_turn"
                final_result["meta"] = payload.get("meta") or {}
                done.set()
            elif evt == "cancelled":
                logger.info("[ACP] executor cancelled sid=%s", sid)
                final_result["stopReason"] = "cancelled"
                done.set()
            elif evt == "error":
                logger.warning("[ACP] executor error sid=%s err=%s", sid, payload.get("error"))
                final_result["error"] = {
                    "code": -32000,
                    "message": payload.get("error") or "executor error",
                }
                done.set()
            else:
                await notify_send(payload)

        # Resume an existing CC session if we already have a cc_session_id;
        # otherwise create a fresh one.
        if sess.cc_session_id:
            cc_session_id = sess.cc_session_id
            resume = True
        else:
            cc_session_id = str(uuid.uuid4())
            resume = False

        logger.info(
            "[ACP] session/prompt sid=%s cc_sid=%s resume=%s prompt_len=%d",
            sid, cc_session_id, resume, len(prompt_text),
        )

        executor_task = asyncio.create_task(
            self.executor.execute_prompt(
                send=send_with_terminal_capture,
                request_id=request_id,
                prompt=prompt_text,
                session_id=cc_session_id,
                working_dir=sess.cwd,
                resume=resume,
                on_session_upsert=on_session_upsert,
            )
        )
        executor_task.add_done_callback(_log_task_exception)

        await done.wait()

        if "error" in final_result:
            logger.info("[ACP] sending prompt error response sid=%s msg_id=%s", sid, msg_id)
            await self._send_error(
                msg_id,
                final_result["error"]["code"],
                final_result["error"]["message"],
            )
        else:
            logger.info(
                "[ACP] sending prompt response sid=%s msg_id=%s stopReason=%s",
                sid, msg_id, final_result["stopReason"],
            )
            # 把 cc_session_id（Claude CLI 真实 session id）和耗时/费用/轮次通过 meta 报回 backend，
            # backend 用 ccSessionId 持久化用于 resume，其余字段格式化成会话末尾的"⏱ ..."提示行。
            response_payload: dict[str, Any] = {"stopReason": final_result["stopReason"]}
            executor_meta = final_result.get("meta") or {}
            cc_sid = executor_meta.get("sessionId") or sess.cc_session_id
            meta_out: dict[str, Any] = {}
            if cc_sid:
                meta_out["ccSessionId"] = cc_sid
            for k in ("costUsd", "durationMs", "numTurns"):
                if k in executor_meta:
                    meta_out[k] = executor_meta[k]
            if meta_out:
                response_payload["meta"] = meta_out
            await self._send_response(msg_id, response_payload)

        sess.request_id = None

    def _make_notify_send(self, acp_session_id: str):
        """Build a `send`-style callback that emits ACP `session/update` events."""
        sess = self.sessions[acp_session_id]

        async def send(payload: dict) -> None:
            evt = payload.get("type", "")

            if evt == "stream_text":
                full = payload.get("text", "") or ""
                delta = full[len(sess.accumulated):]
                sess.accumulated = full
                if not delta:
                    return
                await self._send_notification(
                    "session/update",
                    {
                        "sessionId": acp_session_id,
                        "update": {
                            "sessionUpdate": "agent_message_chunk",
                            "content": {"type": "text", "text": delta},
                        },
                    },
                )

            elif evt == "tool_log":
                stable_id = payload.get("stableId")
                log = payload.get("log", "") or ""

                if stable_id and stable_id not in sess.seen_tool_ids:
                    sess.seen_tool_ids.add(stable_id)
                    # log is pre-formatted by executor (e.g. "Bash: ls -la")
                    tool_name, sep, _rest = log.partition(":")
                    tool = (tool_name.strip() if sep else "Tool") or "Tool"
                    await self._send_notification(
                        "session/update",
                        {
                            "sessionId": acp_session_id,
                            "update": {
                                "sessionUpdate": "tool_call",
                                "tool": tool,
                                "title": log,
                                "toolCallId": stable_id,
                            },
                        },
                    )
                elif stable_id:
                    # Subsequent updates to the same tool call (status / result)
                    await self._send_notification(
                        "session/update",
                        {
                            "sessionId": acp_session_id,
                            "update": {
                                "sessionUpdate": "tool_call_update",
                                "toolCallId": stable_id,
                                "content": {"type": "text", "text": log},
                            },
                        },
                    )
                else:
                    # No stable id → treat as thinking/thought chunk
                    await self._send_notification(
                        "session/update",
                        {
                            "sessionId": acp_session_id,
                            "update": {
                                "sessionUpdate": "agent_thought_chunk",
                                "content": {"type": "text", "text": log},
                            },
                        },
                    )

            elif evt == "status_update":
                await self._send_notification(
                    "session/update",
                    {
                        "sessionId": acp_session_id,
                        "update": {
                            "sessionUpdate": "agent_thought_chunk",
                            "content": {
                                "type": "text",
                                "text": payload.get("status", "") or "",
                            },
                        },
                    },
                )
            # complete / cancelled / error are handled by send_with_terminal_capture

        return send

    # ------------------------------------------------------------------
    # session/request_permission — auto-approve for now
    # ------------------------------------------------------------------

    async def _handle_request_permission(self, msg_id: Any, params: Any) -> None:
        await self._send_response(
            msg_id, {"outcome": {"kind": "selected", "optionId": "approve"}}
        )

    # ------------------------------------------------------------------
    # _silk/* extensions
    # ------------------------------------------------------------------

    async def _handle_silk_list_sessions(self, msg_id: Any, params: Any) -> None:
        sessions = self.session_manager.list_sessions()
        await self._send_response(msg_id, {"sessions": sessions})

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
        sess.cwd = resolved
        # cwd change invalidates the underlying CC session — next prompt will
        # spawn a fresh one.
        sess.cc_session_id = None
        logger.info("[ACP] _silk/set_cwd sid=%s cwd=%s", sid, resolved)
        await self._send_response(msg_id, {"ok": True, "path": resolved})

    async def _handle_silk_list_dir(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        path = p.get("path") or self.default_cwd
        show_hidden = bool(p.get("showHidden", False))
        result = list_directory(path, show_hidden)
        logger.debug("[ACP] _silk/list_dir path=%s success=%s", path, result.get("success"))
        await self._send_response(msg_id, result)

    async def _handle_silk_compact(self, msg_id: Any, params: Any) -> None:
        sid = (params or {}).get("sessionId")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            await self._send_error(msg_id, -32602, f"Unknown session: {sid}")
            return
        if not sess.cc_session_id:
            await self._send_error(msg_id, -32000, "no active session to compact")
            return

        done = asyncio.Event()
        capture: dict[str, Any] = {}

        async def send(payload: dict) -> None:
            evt = payload.get("type", "")
            if evt in ("complete", "cancelled"):
                done.set()
            elif evt == "error":
                capture["error"] = payload.get("error") or "compact error"
                done.set()

        def _noop_upsert(cc_sid: str, wdir: str, title: str) -> None:
            return None

        compact_task = asyncio.create_task(
            self.executor.execute_prompt(
                send=send,
                request_id=str(uuid.uuid4()),
                prompt="/compact",
                session_id=sess.cc_session_id,
                working_dir=sess.cwd,
                resume=True,
                on_session_upsert=_noop_upsert,
            )
        )
        compact_task.add_done_callback(_log_task_exception)

        await done.wait()
        if "error" in capture:
            await self._send_error(msg_id, -32000, capture["error"])
        else:
            await self._send_response(msg_id, {"ok": True})


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_ws_url(server: str, token: str) -> str:
    """Normalize server address to ws:// or wss:// URL (mirrors bridge_agent.py)."""
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
    return f"{ws_scheme}://{host}/agent-bridge?agentType=claude-code&token={token}"


def _env_tls_insecure() -> bool:
    return os.environ.get("BRIDGE_TLS_INSECURE", "").strip().lower() in (
        "1", "true", "yes",
    )


def _log_task_exception(task: asyncio.Task) -> None:
    try:
        exc = task.exception()
    except (asyncio.CancelledError, asyncio.InvalidStateError):
        return
    if exc is not None:
        logger.error("[ACP] Background task error: %s", exc, exc_info=exc)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(
        description="ACP Bridge Adapter: ACP server bridging Silk backend to Claude CLI",
    )
    parser.add_argument(
        "--server",
        required=True,
        help="Silk backend, e.g. localhost:8006 or https://host:port (HTTPS uses WSS)",
    )
    parser.add_argument(
        "--token",
        required=True,
        help="Authentication token for the bridge connection",
    )
    parser.add_argument(
        "--working-dir",
        default=os.getcwd(),
        help="Default working directory for claude CLI (default: cwd)",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Log level (default: INFO)",
    )
    parser.add_argument(
        "--tls-insecure",
        action="store_true",
        help="WSS 时不校验服务端证书（自签证书场景）；也可用环境变量 BRIDGE_TLS_INSECURE=1",
    )
    args = parser.parse_args()
    tls_insecure = bool(args.tls_insecure) or _env_tls_insecure()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    server = AcpAgentServer(
        ws_url=args.server,
        token=args.token,
        default_cwd=args.working_dir,
        tls_insecure=tls_insecure,
    )

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    shutdown_event = asyncio.Event()

    def _signal_handler() -> None:
        logger.info("[ACP] Received shutdown signal")
        shutdown_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _signal_handler)
        except NotImplementedError:
            # Windows / restricted env
            pass

    async def _runner() -> None:
        run_task = asyncio.create_task(server.run())
        shutdown_task = asyncio.create_task(shutdown_event.wait())
        done, pending = await asyncio.wait(
            {run_task, shutdown_task}, return_when=asyncio.FIRST_COMPLETED
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
