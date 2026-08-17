#!/usr/bin/env python3
"""ACP Bridge Adapter — ACP server that bridges Silk backend to Claude CLI.

Receives ACP JSON-RPC objects over protected stdio IPC while ``silk-agent``
owns the authenticated multiplexed backend WebSocket. Requests delegate Claude CLI execution to
:mod:`cc_bridge.executor`, with ``session/update`` notifications streamed back.

Permission interactions use --permission-prompt-tool stdio: when Claude CLI
needs permission or asks a question, the executor receives a control_request
on stdout, calls back into this adapter, which forwards to the backend and
awaits the user decision before writing a control_response to stdin.

Protocol direction (critical):
    backend `AcpClient` is the ACP **client** — it sends requests.
    this adapter is the ACP **server** — it handles requests + pushes
    notifications. The adapter never originates requests.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal
import sys
import time
import uuid
from dataclasses import dataclass, field
from typing import Any


_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)
from bridge_common.host_ipc import HostIPC
from bridge_common.execution_policy import ExecutionPolicy, resolve_prompt_working_directory

from executor import Executor
from fs_listing import list_directory
from git_ops import git_status, git_diff
from cc_session_index import find_session_file, list_local_sessions

logger = logging.getLogger("acp_bridge")


def is_missing_resume_session_error(message: object) -> bool:
    """Return whether Claude rejected a resume because the local session is gone."""

    if not isinstance(message, str):
        return False
    normalized = " ".join(message.lower().split())
    return any(
        marker in normalized
        for marker in (
            "no conversation found with session id",
            "conversation not found for session id",
        )
    )


# ---------------------------------------------------------------------------
# Per-ACP-session state
# ---------------------------------------------------------------------------


@dataclass
class AcpSession:
    """State held per ACP session id (one per workflow group on backend)."""

    cwd: str
    cli_session_id: str | None = None
    accumulated: str = ""
    seen_tool_ids: set[str] = field(default_factory=set)
    cancelled: bool = False
    request_id: str | None = None


# ---------------------------------------------------------------------------
# ACP server
# ---------------------------------------------------------------------------


class AcpAgentServer:
    """ACP server that translates backend requests into Claude CLI execution."""

    def __init__(
        self,
        default_cwd: str,
        *,
        host_ipc: HostIPC,
    ) -> None:
        self.default_cwd = self._resolve_default_cwd(default_cwd)
        self.host_ipc = host_ipc
        self.executor = Executor()
        self.sessions: dict[str, AcpSession] = {}
        self._cli_to_acp: dict[str, str] = {}

        # Pending permission/question requests: request_id -> asyncio.Future
        self._pending_permissions: dict[str, asyncio.Future[dict[str, Any]]] = {}
        # Original tool input for each pending request (needed for allow response)
        self._pending_tool_inputs: dict[str, dict[str, Any]] = {}

    # ------------------------------------------------------------------
    # default_cwd helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _resolve_default_cwd(candidate: str) -> str:
        """Validate *candidate* and fall back to home / ``/`` if it doesn't exist."""
        resolved = os.path.realpath(candidate)
        if os.path.isdir(resolved):
            return resolved
        home = os.path.expanduser("~")
        if os.path.isdir(home):
            logger.warning(
                "[ACP] default_cwd %s does not exist, falling back to %s",
                resolved, home,
            )
            return home
        logger.warning(
            "[ACP] default_cwd %s does not exist, falling back to /",
            resolved,
        )
        return "/"

    # ------------------------------------------------------------------
    # Permission callback (called by executor on control_request)
    # ------------------------------------------------------------------

    async def _on_permission_request(
        self,
        acp_session_id: str,
        ctrl_request_id: str,
        tool_name: str,
        tool_input: dict[str, Any],
        execution_policy: ExecutionPolicy | None = None,
    ) -> dict[str, Any]:
        """Handle a permission request from the executor.

        For AskUserQuestion: forwards questions to backend, waits for user
        answers, returns allow with updatedInput containing answers.

        For other tools: forwards to backend for permission decision,
        waits for allow/deny response.

        ``acp_session_id`` is bound via closure in ``_handle_session_prompt``
        so that concurrent sessions are never mixed up.
        """

        if tool_name == "AskUserQuestion":
            return await self._handle_ask_user_question_ctrl(
                acp_session_id, ctrl_request_id, tool_input,
            )

        if tool_name == "ExitPlanMode":
            return await self._handle_exit_plan_mode_ctrl(
                acp_session_id, ctrl_request_id, tool_input,
            )

        if execution_policy is not None:
            session = self.sessions.get(acp_session_id)
            reason = execution_policy.denied_tool_reason(
                tool_name,
                tool_input,
                session.cwd if session is not None else self.default_cwd,
            )
            if reason is not None:
                logger.warning(
                    "[ACP] Denying tool outside Silk execution policy: tool=%s session=%s",
                    tool_name,
                    acp_session_id[:8],
                )
                return {"behavior": "deny", "message": reason}

        # Auto-allow safe read-only network tools without prompting.
        # Ported from the legacy permission_hook.sh whitelist (commit 016c93d):
        # WebSearch/WebFetch are not in Claude CLI's default allow-list, so we
        # approve them here to avoid a permission prompt on every web search.
        if tool_name in ("WebSearch", "WebFetch"):
            logger.info(
                "[ACP] Auto-allowing read-only tool %s (no prompt), session=%s",
                tool_name, acp_session_id[:8],
            )
            return {"behavior": "allow", "updatedInput": tool_input}

        # Regular tool permission request
        future: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()
        self._pending_permissions[ctrl_request_id] = future
        self._pending_tool_inputs[ctrl_request_id] = tool_input

        logger.info(
            "[ACP] Permission request: forwarding tool=%s, request=%s, session=%s",
            tool_name, ctrl_request_id[:8], acp_session_id[:8],
        )
        await self._send_notification("session/update", {
            "sessionId": acp_session_id,
            "update": {
                "sessionUpdate": "permission_request",
                "requestId": ctrl_request_id,
                "toolName": tool_name,
                "toolInput": tool_input,
            },
        })

        try:
            result = await asyncio.wait_for(future, timeout=300.0)
        except asyncio.TimeoutError:
            logger.warning("[ACP] Permission request timed out: %s", ctrl_request_id[:8])
            result = {"behavior": "deny", "message": "Permission request timed out."}
        finally:
            self._pending_permissions.pop(ctrl_request_id, None)
            self._pending_tool_inputs.pop(ctrl_request_id, None)

        return result

    async def _handle_ask_user_question_ctrl(
        self,
        acp_session_id: str,
        ctrl_request_id: str,
        tool_input: dict[str, Any],
    ) -> dict[str, Any]:
        """Handle AskUserQuestion via control_request/control_response."""
        questions = tool_input.get("questions", [])
        if not questions:
            q = tool_input.get("question", "")
            if isinstance(q, str) and q:
                questions = [q]
        if not questions:
            logger.warning(
                "[ACP] AskUserQuestion: no valid questions in tool_input"
            )
            return {"behavior": "deny", "message": "AskUserQuestion: no valid questions."}

        future: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()
        self._pending_permissions[ctrl_request_id] = future
        # 存原始 tool_input（含 questions），resolve 时构造 updatedInput 需要它
        self._pending_tool_inputs[ctrl_request_id] = tool_input

        logger.info(
            "[ACP] AskUserQuestion: forwarding %d question(s), request=%s, session=%s",
            len(questions), ctrl_request_id[:8], acp_session_id[:8],
        )
        await self._send_notification("session/update", {
            "sessionId": acp_session_id,
            "update": {
                "sessionUpdate": "ask_user_question",
                "requestId": ctrl_request_id,
                "questions": questions,
            },
        })

        try:
            result = await asyncio.wait_for(
                future, timeout=300.0 * len(questions),
            )
        except asyncio.TimeoutError:
            logger.warning(
                "[ACP] AskUserQuestion timed out: %s", ctrl_request_id[:8]
            )
            result = {"behavior": "deny", "message": "AskUserQuestion timed out."}
        finally:
            self._pending_permissions.pop(ctrl_request_id, None)
            self._pending_tool_inputs.pop(ctrl_request_id, None)

        return result

    async def _handle_exit_plan_mode_ctrl(
        self,
        acp_session_id: str,
        ctrl_request_id: str,
        tool_input: dict[str, Any],
    ) -> dict[str, Any]:
        """Handle ExitPlanMode: show plan for user review with allow/deny/feedback."""
        plan_content = tool_input.get("plan", "")

        future: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()
        self._pending_permissions[ctrl_request_id] = future
        self._pending_tool_inputs[ctrl_request_id] = tool_input

        logger.info(
            "[ACP] ExitPlanMode: forwarding plan review, request=%s, session=%s, plan_len=%d",
            ctrl_request_id[:8], acp_session_id[:8], len(plan_content),
        )
        await self._send_notification("session/update", {
            "sessionId": acp_session_id,
            "update": {
                "sessionUpdate": "plan_review",
                "requestId": ctrl_request_id,
                "planContent": plan_content,
                "toolInput": tool_input,
            },
        })

        try:
            result = await asyncio.wait_for(future, timeout=600.0)
        except asyncio.TimeoutError:
            logger.warning(
                "[ACP] ExitPlanMode review timed out: %s", ctrl_request_id[:8]
            )
            result = {"behavior": "deny", "message": "Plan review timed out."}
        finally:
            self._pending_permissions.pop(ctrl_request_id, None)
            self._pending_tool_inputs.pop(ctrl_request_id, None)

        return result

    # ------------------------------------------------------------------
    # Host IPC loop
    # ------------------------------------------------------------------

    async def run(self) -> None:
        self._loop = asyncio.get_event_loop()

        logger.info("[ACP] Running behind silk-agent Host multiplexed WSS")
        while True:
            await self._dispatch_message(await self.host_ipc.receive_acp())

    def _fail_pending_permissions(self, reason: str) -> None:
        """Fail all pending permission futures (e.g. on disconnect)."""
        for req_id, fut in list(self._pending_permissions.items()):
            if not fut.done():
                fut.set_result({"behavior": "deny", "message": reason})
        self._pending_permissions.clear()
        self._pending_tool_inputs.clear()

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
            elif method == "_silk/resolve_question":
                await self._handle_silk_resolve_question(msg_id, params)
            elif method == "_silk/resolve_permission":
                await self._handle_silk_resolve_permission(msg_id, params)
            elif method == "_silk/resolve_plan_review":
                await self._handle_silk_resolve_plan_review(msg_id, params)
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
                        "resolveQuestion": True,
                        "gitStatus": True,
                        "gitDiff": True,
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
        cli_session_id = p.get("cliSessionId")
        acp_session_id = str(uuid.uuid4())
        sess = AcpSession(cwd=os.path.realpath(cwd))
        if cli_session_id:
            sess.cli_session_id = cli_session_id
        self.sessions[acp_session_id] = sess
        logger.info(
            "[ACP] session/new: %s cwd=%s cli_seed=%s",
            acp_session_id, cwd, (cli_session_id or "")[:8],
        )
        await self._send_response(msg_id, {"sessionId": acp_session_id})

    # ------------------------------------------------------------------
    # session/load
    # ------------------------------------------------------------------

    async def _handle_session_load(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        prefix = p.get("sessionId")
        cwd = p.get("cwd") or self.default_cwd
        if not prefix:
            await self._send_error(msg_id, -32602, "missing sessionId")
            return
        record = await asyncio.to_thread(find_session_file, prefix)
        if record is None:
            await self._send_error(
                msg_id, -32602, f"claude session not found: {prefix}"
            )
            return
        cli_session_id = record["sessionId"]
        if not cwd:
            cwd = record.get("workingDir") or self.default_cwd
        acp_session_id = str(uuid.uuid4())
        sess = AcpSession(cwd=os.path.realpath(cwd), cli_session_id=cli_session_id)
        self.sessions[acp_session_id] = sess
        logger.info(
            "[ACP] session/load: acp=%s cli_sid=%s",
            acp_session_id, cli_session_id[:8],
        )
        await self._send_response(
            msg_id, {"sessionId": acp_session_id, "loaded": True}
        )

    # ------------------------------------------------------------------
    # session/cancel
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
    # session/prompt
    # ------------------------------------------------------------------

    async def _handle_session_prompt(self, msg_id: Any, params: Any) -> None:
        t0 = time.monotonic()
        sid = (params or {}).get("sessionId")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            await self._send_error(msg_id, -32602, f"Unknown session: {sid}")
            return

        prompt_blocks = (params or {}).get("prompt", []) or []
        execution_policy = ExecutionPolicy.from_acp_prompt(
            params,
            managed_connection=self.host_ipc is not None,
        )
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
            # A CLI session belongs to its original device/path and must not be
            # resumed after a cross-device fallback.
            sess.cli_session_id = None
        prompt_text = "".join(
            b.get("text", "")
            for b in prompt_blocks
            if isinstance(b, dict) and b.get("type") == "text"
        )

        sess.accumulated = ""
        sess.seen_tool_ids.clear()
        sess.cancelled = False
        request_id = str(uuid.uuid4())
        sess.request_id = request_id

        def on_session_upsert(cli_sid: str, wdir: str, title: str) -> None:
            sess.cli_session_id = cli_sid

        notify_send = self._make_notify_send(sid)

        async def run_executor_once(cli_sid: str, resume_flag: bool) -> dict[str, Any]:
            final_result: dict[str, Any] = {}
            done = asyncio.Event()

            logger.info(
                "[ACP] session/prompt sid=%s cli_sid=%s resume=%s prompt_len=%d",
                sid, cli_sid, resume_flag, len(prompt_text),
            )

            async def send_with_terminal_capture(payload: dict) -> None:
                evt = payload.get("type", "")
                if evt == "complete":
                    logger.info(
                        "[ACP] executor complete sid=%s meta=%s",
                        sid, payload.get("meta"),
                    )
                    final_result["stopReason"] = "end_turn"
                    final_result["meta"] = payload.get("meta") or {}
                    final_result["text"] = payload.get("text") or ""
                    done.set()
                elif evt == "cancelled":
                    logger.info("[ACP] executor cancelled sid=%s", sid)
                    final_result["stopReason"] = "cancelled"
                    done.set()
                elif evt == "error":
                    logger.warning(
                        "[ACP] executor error sid=%s err=%s",
                        sid, payload.get("error"),
                    )
                    final_result["error"] = {
                        "code": -32000,
                        "message": payload.get("error") or "executor error",
                    }
                    done.set()
                else:
                    await notify_send(payload)

            executor_task = asyncio.create_task(
                self.executor.execute_prompt(
                    send=send_with_terminal_capture,
                    request_id=request_id,
                    prompt=prompt_text,
                    session_id=cli_sid,
                    working_dir=sess.cwd,
                    resume=resume_flag,
                    on_session_upsert=on_session_upsert,
                    on_permission_request=lambda rid, tn, ti, _sid=sid, _policy=execution_policy: self._on_permission_request(
                        _sid,
                        rid,
                        tn,
                        ti,
                        _policy,
                    ),
                    execution_policy=execution_policy,
                )
            )
            executor_task.add_done_callback(_log_task_exception)
            await done.wait()
            return final_result

        if sess.cli_session_id:
            cli_session_id = sess.cli_session_id
            resume = True
        else:
            cli_session_id = str(uuid.uuid4())
            resume = False

        final_result = await run_executor_once(cli_session_id, resume)

        # A backend workflow can retain a CLI session ID after the device's
        # local Claude history has been removed or after switching devices.
        # Recover only from explicit missing-session or empty zero-turn resume
        # results; authentication and other execution errors remain visible.
        missing_resume_session = resume and is_missing_resume_session_error(
            (final_result.get("error") or {}).get("message")
        )
        empty_resume = (
            resume
            and "error" not in final_result
            and final_result.get("stopReason") == "end_turn"
            and not (final_result.get("text") or "").strip()
            and int((final_result.get("meta") or {}).get("numTurns", 0)) == 0
        )
        if missing_resume_session or empty_resume:
            fresh_cli_sid = str(uuid.uuid4())
            sess.cli_session_id = fresh_cli_sid
            logger.warning(
                "[ACP] Unavailable Claude resume sid=%s missing=%s empty=%s; retrying with fresh session",
                sid,
                missing_resume_session,
                empty_resume,
            )
            final_result = await run_executor_once(fresh_cli_sid, False)

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
            response_payload: dict[str, Any] = {"stopReason": final_result["stopReason"]}
            executor_meta = final_result.get("meta") or {}
            cli_sid = executor_meta.get("sessionId") or sess.cli_session_id
            meta_out: dict[str, Any] = {}
            if cli_sid:
                meta_out["cliSessionId"] = cli_sid
            for k in ("costUsd", "numTurns"):
                if k in executor_meta:
                    meta_out[k] = executor_meta[k]
            meta_out["durationMs"] = int((time.monotonic() - t0) * 1000)
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
        cwd = os.path.realpath((params or {}).get("cwd") or "")
        sessions = await asyncio.to_thread(list_local_sessions)
        if cwd:
            sessions = [s for s in sessions if os.path.realpath(s.get("workingDir", "")) == cwd]
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
        if not os.path.isdir(cwd):
            await self._send_error(msg_id, -32602, f"Not a directory on this Agent device: {cwd}")
            return
        resolved = os.path.realpath(cwd)
        sess.cwd = resolved
        sess.cli_session_id = None
        logger.info("[ACP] _silk/set_cwd sid=%s cwd=%s", sid, resolved)
        await self._send_response(msg_id, {"ok": True, "path": resolved})

    async def _handle_silk_list_dir(self, msg_id: Any, params: Any) -> None:
        p = params or {}
        explicit_path = p.get("path")
        path = explicit_path or self.default_cwd
        show_hidden = bool(p.get("showHidden", False))
        result = list_directory(path, show_hidden)
        if not result.get("success") and not explicit_path:
            for fallback in (os.path.expanduser("~"), "/"):
                if fallback != path:
                    result = list_directory(fallback, show_hidden)
                    if result.get("success"):
                        logger.info(
                            "[ACP] _silk/list_dir: default %s unavailable, fell back to %s",
                            path, fallback,
                        )
                        break
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
        sid = (params or {}).get("sessionId")
        sess = self.sessions.get(sid) if sid else None
        if sess is None:
            await self._send_error(msg_id, -32602, f"Unknown session: {sid}")
            return
        if not sess.cli_session_id:
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

        def _noop_upsert(cli_sid: str, wdir: str, title: str) -> None:
            return None

        compact_task = asyncio.create_task(
            self.executor.execute_prompt(
                send=send,
                request_id=str(uuid.uuid4()),
                prompt="/compact",
                session_id=sess.cli_session_id,
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

    async def _handle_silk_resolve_question(self, msg_id: Any, params: Any) -> None:
        """Resolve an AskUserQuestion request with the user's answer."""
        p = params or {}
        request_id = p.get("requestId", "")
        answers = p.get("answers")

        if not request_id:
            await self._send_error(msg_id, -32602, "Missing requestId")
            return

        future = self._pending_permissions.get(request_id)
        if future is None or future.done():
            logger.warning("[ACP] resolve_question: unknown request %s", request_id[:8])
            await self._send_error(msg_id, -32602, f"Unknown request: {request_id}")
            return

        # 构造 AskUserQuestion 的 control_response.updatedInput：
        # 保留原始 tool_input（含 questions 数组）+ answers 为 {问题文本: 答案} 的 map。
        # 这是 Claude Code CLI 期望的格式（参照 cc-connect buildAskQuestionResponse）；
        # 缺失原始 input 或 answers 非 map 时回退到旧的 answer 字符串以兼容。
        original = self._pending_tool_inputs.get(request_id) or {}
        updated_input: dict[str, Any] = dict(original)
        if isinstance(answers, dict):
            updated_input["answers"] = answers
        else:
            updated_input["answers"] = p.get("answer", "")
        future.set_result({
            "behavior": "allow",
            "updatedInput": updated_input,
        })
        logger.info("[ACP] resolve_question: resolved %s", request_id[:8])
        await self._send_response(msg_id, {"ok": True})

    async def _handle_silk_resolve_permission(self, msg_id: Any, params: Any) -> None:
        """Resolve a tool permission request with allow/deny."""
        p = params or {}
        request_id = p.get("requestId", "")
        decision = p.get("decision", "deny")
        reason = p.get("reason", "")

        if not request_id:
            await self._send_error(msg_id, -32602, "Missing requestId")
            return

        future = self._pending_permissions.get(request_id)
        if future is None or future.done():
            logger.warning(
                "[ACP] resolve_permission: unknown request %s", request_id[:8]
            )
            await self._send_error(msg_id, -32602, f"Unknown request: {request_id}")
            return

        if decision == "allow":
            original_input = self._pending_tool_inputs.get(request_id, {})
            future.set_result({"behavior": "allow", "updatedInput": original_input})
        else:
            future.set_result({
                "behavior": "deny",
                "message": reason or "User denied this tool use.",
            })

        logger.info(
            "[ACP] resolve_permission: resolved %s decision=%s",
            request_id[:8], decision,
        )
        await self._send_response(msg_id, {"ok": True})

    async def _handle_silk_resolve_plan_review(self, msg_id: Any, params: Any) -> None:
        """Resolve an ExitPlanMode plan review request.

        Params:
            requestId: the control_request id
            decision: "allow" | "deny" | "deny_with_feedback"
            feedback: user feedback text (used when decision is "deny_with_feedback")
        """
        p = params or {}
        request_id = p.get("requestId", "")
        decision = p.get("decision", "deny")
        feedback = p.get("feedback", "")

        if not request_id:
            await self._send_error(msg_id, -32602, "Missing requestId")
            return

        future = self._pending_permissions.get(request_id)
        if future is None or future.done():
            logger.warning(
                "[ACP] resolve_plan_review: unknown request %s", request_id[:8]
            )
            await self._send_error(msg_id, -32602, f"Unknown request: {request_id}")
            return

        if decision == "allow":
            original_input = self._pending_tool_inputs.get(request_id, {})
            future.set_result({"behavior": "allow", "updatedInput": original_input})
        elif decision == "deny_with_feedback":
            message = (
                f"User rejected the plan and provided feedback:\n\n"
                f"{feedback}\n\n"
                f"Please revise the plan based on this feedback, "
                f"then call ExitPlanMode again for re-review."
            )
            future.set_result({"behavior": "deny", "message": message})
        else:
            future.set_result({
                "behavior": "deny",
                "message": "User rejected the plan.",
            })

        logger.info(
            "[ACP] resolve_plan_review: resolved %s decision=%s",
            request_id[:8], decision,
        )
        await self._send_response(msg_id, {"ok": True})


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
    parser = argparse.ArgumentParser(
        description="ACP Bridge Adapter: ACP server bridging Silk backend to Claude CLI",
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
            pass

    async def _runner() -> None:
        host_ipc = HostIPC()
        await host_ipc.start("claude-code")
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
