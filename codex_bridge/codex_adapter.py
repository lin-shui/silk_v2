#!/usr/bin/env python3
"""Codex Bridge Adapter — ACP server bridging Silk backend to Codex CLI.

Receives ACP JSON-RPC objects over protected stdio IPC while ``silk-agent`` owns
the authenticated multiplexed backend WebSocket. Requests delegate Codex CLI execution to
:mod:`codex_executor`, with ``session/update`` notifications streamed back.

Supports prompt streaming, cancellation, session resume, and Silk-specific
directory/session helpers exposed via `_silk/*`.
"""

from __future__ import annotations

import argparse
import asyncio
import time
import logging
import os
import signal
import sys
import uuid
from dataclasses import dataclass, field
from typing import Any


_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)
from bridge_common.host_ipc import HostIPC
from bridge_common.execution_policy import ExecutionPolicy, resolve_prompt_working_directory

from codex_dispatcher import DispatcherState, dispatch_event
from codex_executor import CodexExecutor, cancel_process
from fs_listing import list_directory
from git_ops import git_status, git_diff
from codex_session_index import find_session_file, list_local_sessions, _parse_rollout_head

logger = logging.getLogger("codex_bridge")


# ---------------------------------------------------------------------------
# Per-ACP-session state
# ---------------------------------------------------------------------------


@dataclass
class AcpSession:
    """State held per ACP session id (one per workflow group on backend)."""

    cwd: str
    cli_session_id: str | None = None  # Codex CLI's thread_id (from thread.started event)
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
        default_cwd: str,
        *,
        host_ipc: HostIPC,
    ) -> None:
        self.default_cwd = os.path.realpath(default_cwd)
        self.host_ipc = host_ipc
        self.executor = CodexExecutor(auto_approve=os.environ.get("CODEX_AUTO_APPROVE", "1") not in ("0", "false", "False"))
        self.sessions: dict[str, AcpSession] = {}

    # ------------------------------------------------------------------
    # Host IPC loop
    # ------------------------------------------------------------------

    async def run(self) -> None:
        logger.info("[ACP] Running behind silk-agent Host multiplexed WSS")
        while True:
            await self._dispatch_message(await self.host_ipc.receive_acp())

    # ------------------------------------------------------------------
    # Receive dispatch
    # ------------------------------------------------------------------

    async def _dispatch_message(self, msg: dict[str, Any]) -> None:
        if not isinstance(msg, dict):
            logger.warning("[ACP] Ignoring non-object message")
            return

        msg_id = msg.get("id")
        method = msg.get("method")

        if msg_id is not None and method is not None:
            task = asyncio.create_task(
                self._handle_request(msg_id, method, msg.get("params"))
            )
            task.add_done_callback(_log_task_exception)
        elif method is not None:
            task = asyncio.create_task(
                self._handle_notification(method, msg.get("params"))
            )
            task.add_done_callback(_log_task_exception)

    # ------------------------------------------------------------------
    # Send helpers
    # ------------------------------------------------------------------

    async def _send_response(self, msg_id: Any, result: Any) -> None:
        await self._send_message({"jsonrpc": "2.0", "id": msg_id, "result": result})

    async def _send_error(self, msg_id: Any, code: int, message: str) -> None:
        await self._send_message({
            "jsonrpc": "2.0",
            "id": msg_id,
            "error": {"code": code, "message": message},
        })

    async def _send_notification(self, method: str, params: Any) -> None:
        await self._send_message({"jsonrpc": "2.0", "method": method, "params": params})

    async def _send_message(self, message: dict[str, Any]) -> None:
        await self.host_ipc.forward_acp(message)

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
            elif method == "_silk/git_status":
                await self._handle_silk_git_status(msg_id, params)
            elif method == "_silk/git_diff":
                await self._handle_silk_git_diff(msg_id, params)
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
                # M4 Task 5: advertise session/load support to backend
                "loadSession": True,
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
                    "gitStatus": True,
                    "gitDiff": True,
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
        cli_session_id = p.get("cliSessionId")
        acp_session_id = str(uuid.uuid4())
        sess = AcpSession(cwd=os.path.realpath(cwd))
        if cli_session_id:
            # backend seed: resume old Codex thread (from WorkflowPersistence)
            sess.cli_session_id = cli_session_id
        self.sessions[acp_session_id] = sess
        logger.info(
            "[ACP] session/new: %s cwd=%s cli_seed=%s",
            acp_session_id, cwd, (cli_session_id or "")[:8],
        )
        await self._send_response(msg_id, {"sessionId": acp_session_id})

    # ------------------------------------------------------------------
    # session/load — resume a known codex thread by id
    # ------------------------------------------------------------------

    async def _handle_session_load(self, msg_id: Any, params: Any) -> None:
        """Bind a backend ACP session to an existing codex rollout.

        Verifies that the rollout file for ``params.sessionId`` (the codex
        ``thread_id``) exists in ``~/.codex/sessions/``. If found, mints a
        fresh ACP UUID and stores it as a new :class:`AcpSession` whose
        ``cli_session_id`` is set to the supplied thread id. The next
        ``session/prompt`` will then run ``codex exec resume <thread_id>``
        and inherit history.
        """
        p = params or {}
        thread_id = p.get("sessionId")
        cwd = p.get("cwd") or self.default_cwd
        if not thread_id:
            await self._send_error(msg_id, -32602, "missing sessionId")
            return
        # rglob can be slow on huge ~/.codex/sessions/ trees; offload to a
        # worker thread so the receive loop never stalls.
        rollout = await asyncio.to_thread(find_session_file, thread_id)
        if rollout is None:
            await self._send_error(
                msg_id,
                -32602,
                f"codex session not found: {thread_id}",
            )
            return
        # Extract full thread_id from the rollout file metadata
        actual_thread_id = thread_id
        meta = _parse_rollout_head(rollout)
        if meta and meta.get("sessionId"):
            actual_thread_id = meta["sessionId"]
        acp_session_id = str(uuid.uuid4())
        sess = AcpSession(cwd=os.path.realpath(cwd), cli_session_id=actual_thread_id)
        self.sessions[acp_session_id] = sess
        logger.info(
            "[ACP] session/load: acp=%s thread_id=%s rollout=%s",
            acp_session_id, actual_thread_id[:8], rollout.name,
        )
        await self._send_response(
            msg_id, {"sessionId": acp_session_id, "loaded": True}
        )
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
        t0 = time.monotonic()
        acp_session_id = params.get("sessionId")
        prompt_blocks = params.get("prompt") or []
        execution_policy = ExecutionPolicy.from_acp_prompt(
            params,
            managed_connection=self.host_ipc is not None,
        )
        sess = self.sessions.get(acp_session_id)
        if sess is None:
            await self._send_error(msg_id, -32602, f"unknown sessionId: {acp_session_id}")
            return

        try:
            working_dir, used_default = resolve_prompt_working_directory(
                sess.cwd,
                self.default_cwd,
                execution_policy,
            )
        except ValueError as exc:
            await self._send_error(msg_id, -32602, str(exc))
            return
        if used_default:
            logger.warning(
                "[ACP] session cwd is unavailable on this device; using local default for message-only prompt"
            )
            sess.cwd = working_dir
            # A resumed thread belongs to its original device/path.
            sess.cli_session_id = None

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

        dstate = DispatcherState(
            accumulated="",
            seen_tool_ids=sess.seen_tool_ids,
            thread_id=sess.cli_session_id,
        )

        usage: dict[str, int] = {"input_tokens": 0, "output_tokens": 0, "reasoning_tokens": 0}
        stop_reason = "end_turn"
        error_text: str | None = None
        response_sent = False
        proc_handle = None  # local ref to detect stale proc_handle in finally

        try:
            async for ev in self.executor.run(
                prompt=prompt_text,
                cwd=sess.cwd,
                resume_thread_id=sess.cli_session_id,
                execution_policy=execution_policy,
            ):
                kind = ev.get("kind")

                # Control event from executor:
                if kind == "_proc":
                    proc_handle = ev["proc"]
                    sess.proc_handle = proc_handle
                    continue

                # turn_completed: dispatch updates, then send response immediately
                if kind == "turn_completed":
                    usage = {
                        "input_tokens": ev["input_tokens"],
                        "output_tokens": ev["output_tokens"],
                        "reasoning_tokens": ev["reasoning_tokens"],
                    }
                    for update in dispatch_event(ev, dstate):
                        await notify(update)
                    sess.accumulated = dstate.accumulated
                    if dstate.thread_id and not sess.cli_session_id:
                        sess.cli_session_id = dstate.thread_id

                    # Send response right away — don't wait for process exit
                    await self._send_prompt_response(msg_id, sess, usage, "end_turn",
                                                     duration_ms=int((time.monotonic() - t0) * 1000))
                    response_sent = True
                    break

                # turn_failed: dispatch updates, then send response immediately
                if kind == "turn_failed":
                    logger.warning("codex turn failed: %s", ev.get("error", ""))
                    for update in dispatch_event(ev, dstate):
                        await notify(update)
                    sess.accumulated = dstate.accumulated
                    if dstate.thread_id and not sess.cli_session_id:
                        sess.cli_session_id = dstate.thread_id

                    await self._send_prompt_response(msg_id, sess, usage, "end_turn",
                                                     duration_ms=int((time.monotonic() - t0) * 1000))
                    response_sent = True
                    break

                # All other events: delegate to dispatcher
                for update in dispatch_event(ev, dstate):
                    await notify(update)

                sess.accumulated = dstate.accumulated
                if dstate.thread_id and not sess.cli_session_id:
                    sess.cli_session_id = dstate.thread_id

        except Exception as exc:
            logger.exception("codex prompt loop failed: %s", exc)
            error_text = f"adapter error: {exc}"
        finally:
            # Only clear proc_handle if it still belongs to this invocation.
            # A new prompt task may have already replaced it with a new process.
            if sess.proc_handle is proc_handle:
                sess.proc_handle = None

        # Fallback: send response if not already sent
        # (process exited without turn_completed/turn_failed, or exception)
        if not response_sent:
            # Flush any buffered messages before responding
            if dstate.pending_messages:
                logger.warning(
                    "codex ended without turn end event, flushing %d pending messages",
                    len(dstate.pending_messages),
                )
                for update in dispatch_event({"kind": "turn_completed"}, dstate):
                    await notify(update)
                sess.accumulated = dstate.accumulated

            if error_text is not None:
                await self._send_error(msg_id, -32000, error_text)
            else:
                if sess.cancelled:
                    stop_reason = "cancelled"
                await self._send_prompt_response(msg_id, sess, usage, stop_reason,
                                                 duration_ms=int((time.monotonic() - t0) * 1000))

    def _send_prompt_response(
        self,
        msg_id: Any,
        sess: AcpSession,
        usage: dict[str, int],
        stop_reason: str,
        duration_ms: int = 0,
    ):
        """Build and send the session/prompt JSON-RPC response."""
        result = {
            "stopReason": stop_reason,
            "meta": {
                "cliSessionId": sess.cli_session_id or "",
                "inputTokens": usage["input_tokens"],
                "outputTokens": usage["output_tokens"],
                "reasoningTokens": usage["reasoning_tokens"],
                "durationMs": duration_ms,
            },
        }
        return self._send_response(msg_id, result)

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
    # _silk/* extensions supported by the Codex bridge
    # ------------------------------------------------------------------

    async def _handle_silk_list_sessions(self, msg_id: Any, params: Any) -> None:
        """Return list of recent codex sessions from ~/.codex/sessions/.

        Backend frontend renders this as session history selector.
        Filters by cwd when provided by backend.
        """
        cwd = os.path.realpath((params or {}).get("cwd") or "")
        sessions = list_local_sessions()
        if cwd:
            sessions = [s for s in sessions if os.path.realpath(s.get("workingDir", "")) == cwd]
        logger.debug("[ACP] _silk/list_local_sessions count=%d (cwd=%s)", len(sessions), cwd or "all")
        await self._send_response(msg_id, {"sessions": sessions})

    async def _handle_silk_set_cwd(self, msg_id: Any, params: Any) -> None:
        """Update session cwd; invalidate cli_session_id (cwd change ≡ /new for codex).

        Codex resume must run in the original session's cwd, so changing cwd
        breaks resume by definition. We null cli_session_id; next prompt spawns
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
        sess.cli_session_id = None  # cwd change invalidates codex session
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

    async def _handle_silk_git_status(self, msg_id: Any, params: Any) -> None:
        sid = (params or {}).get("sessionId")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            await self._send_error(msg_id, -32602, f"Unknown session: {sid}")
            return
        try:
            result = await git_status(sess.cwd)
        except Exception as exc:
            await self._send_error(msg_id, -32000, f"git_status failed: {exc}")
            return
        logger.debug("[ACP] _silk/git_status sid=%s files=%d", sid, len(result.get("files", [])))
        await self._send_response(msg_id, result)

    async def _handle_silk_git_diff(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        sid = p.get("sessionId")
        path = p.get("path")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            await self._send_error(msg_id, -32602, f"Unknown session: {sid}")
            return
        if not path:
            await self._send_error(msg_id, -32602, "Missing path")
            return
        try:
            result = await git_diff(sess.cwd, path)
        except Exception as exc:
            await self._send_error(msg_id, -32000, f"git_diff failed: {exc}")
            return
        logger.debug("[ACP] _silk/git_diff sid=%s path=%s", sid, path)
        await self._send_response(msg_id, result)

    async def _handle_silk_compact(self, msg_id: Any, params: Any) -> None:
        await self._send_error(msg_id, -32601, "Method not found (Codex has no compact concept)")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


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
        "--silk-host-stdio",
        action="store_true",
        help="Run as a silk-agent managed Adapter using Host IPC",
    )
    args = parser.parse_args()
    if not args.silk_host_stdio:
        parser.error("direct Bridge mode is retired; launch this Adapter through silk-agent")

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
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
        host_ipc = HostIPC()
        await host_ipc.start("codex")
        server = AcpAgentServer(
            default_cwd=args.working_dir,
            host_ipc=host_ipc,
        )
        run_task = asyncio.create_task(server.run())
        shutdown_task = asyncio.create_task(shutdown_event.wait())
        wait_tasks = {run_task, shutdown_task}
        wait_tasks.add(asyncio.create_task(host_ipc.wait_shutdown()))
        done, pending = await asyncio.wait(wait_tasks, return_when=asyncio.FIRST_COMPLETED)
        try:
            for t in pending:
                t.cancel()
            for t in pending:
                try:
                    await t
                except asyncio.CancelledError:
                    pass
            for t in done:
                t.result()
        finally:
            await host_ipc.close()

    try:
        loop.run_until_complete(_runner())
    finally:
        # asyncio installs a wakeup fd for loop-managed Unix signal handlers.
        # Remove the handlers before closing the loop so a late Host SIGTERM
        # cannot write into an already-closed fd and emit a spurious traceback.
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.remove_signal_handler(sig)
            except NotImplementedError:
                pass
        loop.close()


if __name__ == "__main__":
    main()
