#!/usr/bin/env python3
"""Codex Bridge Adapter — ACP server bridging Silk backend to Codex CLI.

Connects to backend `/agent-bridge` via WebSocket, speaks ACP (JSON-RPC 2.0).
Receives requests, delegates Codex CLI execution to :mod:`codex_executor`,
pushes ``session/update`` notifications back during streaming.

M1: text-only prompt/response. Tool-call mapping, /cancel, session resume,
and `_silk/*` extensions are deferred to M2/M3.
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

from codex_dispatcher import DispatcherState, dispatch_event
from codex_executor import CodexExecutor, cancel_process
from fs_listing import list_directory
from codex_session_index import list_local_sessions

logger = logging.getLogger("codex_bridge")


# ---------------------------------------------------------------------------
# Per-ACP-session state
# ---------------------------------------------------------------------------


@dataclass
class AcpSession:
    """State held per ACP session id (one per workflow group on backend)."""

    cwd: str
    cc_session_id: str | None = None  # Codex CLI's thread_id (from thread.started event)
    accumulated: str = ""              # full streamed text so far — used to compute deltas
    cancelled: bool = False
    seen_tool_ids: set[str] = field(default_factory=set)  # M2: dedup tool_call vs tool_call_update
    proc_handle: Any | None = None     # M2: live codex subprocess for /cancel


# ---------------------------------------------------------------------------
# ACP server
# ---------------------------------------------------------------------------


class AcpAgentServer:
    """ACP server that translates backend requests into Codex CLI execution."""

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
        self.executor = CodexExecutor(auto_approve=os.environ.get("CODEX_AUTO_APPROVE", "1") not in ("0", "false", "False"))
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
        """Respond to ACP initialize: declare protocol version and capabilities."""
        result = {
            "protocolVersion": "0.2",
            "agentCapabilities": {
                "loadSession": False,
                "promptCapabilities": {
                    "image": False,
                    "audio": False,
                    "embeddedContext": False,
                },
                "_silk": {
                    "compact": False,           # Codex has no compact concept
                    "listLocalSessions": True,  # M3
                    "setCwd": True,             # M3
                    "listDir": True,            # M3
                },
            },
        }
        await self._send_response(msg_id, result)

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
            # backend seed: resume old Codex thread (from WorkflowPersistence)
            sess.cc_session_id = cc_session_id
        self.sessions[acp_session_id] = sess
        logger.info(
            "[ACP] session/new: %s cwd=%s cc_seed=%s",
            acp_session_id, cwd, (cc_session_id or "")[:8],
        )
        await self._send_response(msg_id, {"sessionId": acp_session_id})

    # ------------------------------------------------------------------
    # session/cancel (notification)
    # ------------------------------------------------------------------

    async def _handle_session_cancel(self, params: Any) -> None:
        """Mark session cancelled and SIGINT the live codex subprocess.

        Sends SIGINT to give codex a chance to flush its session file. If it
        doesn't exit within 1s, escalates to SIGKILL via cancel_process.
        No-op when no prompt is currently running for this session.
        """
        acp_session_id = (params or {}).get("sessionId")
        sess = self.sessions.get(acp_session_id)
        if sess is None:
            logger.info("cancel: unknown sessionId %s", acp_session_id)
            return
        sess.cancelled = True
        proc = sess.proc_handle
        if proc is None:
            logger.info("cancel: no in-flight codex proc for %s", acp_session_id)
            return
        logger.info("cancel: SIGINT codex proc for %s", acp_session_id)
        try:
            await cancel_process(proc, sigint_grace_seconds=1.0)
        except Exception as exc:
            logger.warning("cancel: failed to terminate codex proc: %s", exc)

    # ------------------------------------------------------------------
    # session/prompt — the core: stream executor events as session/update
    # ------------------------------------------------------------------

    async def _handle_session_prompt(self, msg_id: Any, params: Any) -> None:
        """Run a Codex prompt and stream tool/message updates back."""
        acp_session_id = params.get("sessionId")
        prompt_blocks = params.get("prompt") or []
        sess = self.sessions.get(acp_session_id)
        if sess is None:
            await self._send_error(msg_id, -32602, f"unknown sessionId: {acp_session_id}")
            return

        prompt_text = "\n".join(
            b.get("text", "") for b in prompt_blocks if b.get("type") == "text"
        )
        if not prompt_text.strip():
            await self._send_error(msg_id, -32602, "empty prompt")
            return

        sess.cancelled = False
        sess.accumulated = ""
        sess.seen_tool_ids = set()
        sess.proc_handle = None
        notify = self._make_notify_send(acp_session_id)

        # M2: dispatcher state mirrors per-session mutable fields the
        # dispatcher needs. accumulated / seen_tool_ids are kept in sync.
        dstate = DispatcherState(
            accumulated="",
            seen_tool_ids=sess.seen_tool_ids,  # share the set so cancel can inspect it
            thread_id=sess.cc_session_id,
        )

        usage: dict[str, int] = {"input_tokens": 0, "output_tokens": 0, "reasoning_tokens": 0}
        stop_reason = "end_turn"
        error_text: str | None = None

        try:
            async for ev in self.executor.run(
                prompt=prompt_text,
                cwd=sess.cwd,
                resume_thread_id=sess.cc_session_id,
            ):
                kind = ev.get("kind")

                # Control events from executor (not parsed JSONL):
                if kind == "_proc":
                    sess.proc_handle = ev["proc"]
                    continue
                if kind == "_done":
                    if ev["exit_code"] != 0 and not sess.cancelled:
                        error_text = (
                            f"codex exec exited with code {ev['exit_code']}: "
                            f"{ev['stderr'][:500]}"
                        )
                    continue

                # Capture turn_completed usage before delegating (dispatcher
                # returns [] for it but we still need the numbers for the
                # final response):
                if kind == "turn_completed":
                    usage = {
                        "input_tokens": ev["input_tokens"],
                        "output_tokens": ev["output_tokens"],
                        "reasoning_tokens": ev["reasoning_tokens"],
                    }

                # Delegate to dispatcher for ACP update mapping:
                for update in dispatch_event(ev, dstate):
                    await notify(update)

                # Sync dispatcher's mutated state back into AcpSession:
                sess.accumulated = dstate.accumulated
                if dstate.thread_id and not sess.cc_session_id:
                    sess.cc_session_id = dstate.thread_id
        except Exception as exc:
            logger.exception("codex prompt loop failed: %s", exc)
            error_text = f"adapter error: {exc}"
        finally:
            sess.proc_handle = None  # M2: clear handle once prompt finishes

        if error_text is not None:
            await self._send_error(msg_id, -32000, error_text)
            return

        if sess.cancelled:
            stop_reason = "cancelled"

        result = {
            "stopReason": stop_reason,
            "meta": {
                "ccSessionId": sess.cc_session_id or "",
                "inputTokens": usage["input_tokens"],
                "outputTokens": usage["output_tokens"],
                "reasoningTokens": usage["reasoning_tokens"],
            },
        }
        await self._send_response(msg_id, result)

    def _make_notify_send(self, acp_session_id: str):
        """Build a callback that emits ACP `session/update` notifications."""

        async def send(update: dict) -> None:
            await self._send_notification(
                "session/update",
                {
                    "sessionId": acp_session_id,
                    "update": update,
                },
            )

        return send

    # ------------------------------------------------------------------
    # session/request_permission — auto-approve for now
    # ------------------------------------------------------------------

    async def _handle_request_permission(self, msg_id: Any, params: Any) -> None:
        await self._send_response(
            msg_id, {"outcome": {"kind": "selected", "optionId": "approve"}}
        )

    # ------------------------------------------------------------------
    # _silk/* extensions — M1: all return Method Not Found
    # ------------------------------------------------------------------

    async def _handle_silk_list_sessions(self, msg_id: Any, params: Any) -> None:
        """Return list of recent codex sessions from ~/.codex/sessions/.

        Backend frontend renders this as session history selector.
        """
        sessions = list_local_sessions()
        logger.debug("[ACP] _silk/list_local_sessions count=%d", len(sessions))
        await self._send_response(msg_id, {"sessions": sessions})

    async def _handle_silk_set_cwd(self, msg_id: Any, params: Any) -> None:
        """Update session cwd; invalidate cc_session_id (cwd change ≡ /new for codex).

        Codex resume must run in the original session's cwd, so changing cwd
        breaks resume by definition. We null cc_session_id; next prompt spawns
        a fresh codex session.
        """
        p = params or {}
        sid = p.get("sessionId")
        cwd = p.get("cwd")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            await self._send_error(msg_id, -32602, f"unknown session: {sid}")
            return
        if not cwd:
            await self._send_error(msg_id, -32602, "missing cwd")
            return
        if not os.path.isdir(cwd):
            await self._send_error(msg_id, -32602, f"not a directory: {cwd}")
            return
        resolved = os.path.realpath(cwd)
        sess.cwd = resolved
        sess.cc_session_id = None  # cwd change invalidates codex session
        sess.accumulated = ""
        sess.seen_tool_ids = set()
        logger.info("[ACP] _silk/set_cwd sid=%s cwd=%s", sid, resolved)
        await self._send_response(msg_id, {"ok": True, "path": resolved})

    async def _handle_silk_list_dir(self, msg_id: Any, params: Any) -> None:
        """List a directory's contents (used by Silk UI's folder picker)."""
        p = params or {}
        path = p.get("path") or self.default_cwd
        show_hidden = bool(p.get("showHidden", False))
        result = list_directory(path, show_hidden)
        logger.debug("[ACP] _silk/list_dir path=%s success=%s", path, result.get("success"))
        await self._send_response(msg_id, result)

    async def _handle_silk_compact(self, msg_id: Any, params: Any) -> None:
        await self._send_error(msg_id, -32601, "Method not found (Codex has no compact concept)")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_ws_url(server: str, token: str) -> str:
    """Normalize server address to ws:// or wss:// URL."""
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
    agent_type = os.environ.get("CODEX_AGENT_TYPE", "codex")
    return f"{ws_scheme}://{host}/agent-bridge?agentType={agent_type}&token={token}"


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
    parser = argparse.ArgumentParser(description="Silk ACP bridge for Codex CLI")
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
        help="Default working directory for Codex CLI (default: cwd)",
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
