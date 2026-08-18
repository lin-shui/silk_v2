"""Codex app-server transport used for managed Silk prompts.

Unlike ``codex exec --json``, app-server keeps stdin open and exposes native
pre-execution approval requests.  This module translates its JSON-RPC stream
back into the normalized events consumed by :mod:`codex_dispatcher`.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import signal
import time
from collections.abc import Awaitable, Callable
from typing import Any, AsyncGenerator

from bridge_common.execution_policy import (
    APPROVAL_REQUIRED,
    AUTONOMOUS,
    ExecutionPolicy,
)
from bridge_common.unicode_safety import sanitize_text

try:
    from codex_executor import CODEX_TIMEOUT, IDLE_REFRESH_S, parse_jsonl_event
except ModuleNotFoundError:
    from codex_bridge.codex_executor import CODEX_TIMEOUT, IDLE_REFRESH_S, parse_jsonl_event

logger = logging.getLogger("codex_bridge.app_server")

ApprovalHandler = Callable[[str, dict[str, Any], dict[str, Any] | None], Awaitable[bool]]
QuestionHandler = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]

_APP_SERVER_ITEM_TYPES = {
    "agentMessage": "agent_message",
    "commandExecution": "command_execution",
    "fileChange": "file_change",
    "reasoning": "reasoning",
    "webSearch": "web_search",
}

_APPROVAL_METHODS = {
    "item/commandExecution/requestApproval",
    "item/fileChange/requestApproval",
    "item/permissions/requestApproval",
}


def build_app_server_command(execution_policy: ExecutionPolicy) -> list[str]:
    """Build the app-server command while preserving the user's Codex config."""
    cmd = ["codex", "app-server", "--stdio"]
    if not execution_policy.can_run_codex_shell:
        cmd.extend(("--disable", "shell_tool", "--disable", "unified_exec"))
    return cmd


def build_thread_request(
    *,
    cwd: str,
    resume_thread_id: str | None,
    execution_policy: ExecutionPolicy,
) -> tuple[str, dict[str, Any]]:
    """Map the Silk ceiling onto Codex's per-thread native controls."""
    params: dict[str, Any] = {
        "cwd": cwd,
        "runtimeWorkspaceRoots": [cwd],
        "sandbox": execution_policy.codex_sandbox,
    }
    if resume_thread_id:
        method = "thread/resume"
        params["threadId"] = resume_thread_id
        params["excludeTurns"] = True
    else:
        method = "thread/start"

    if execution_policy.access_mode == APPROVAL_REQUIRED:
        # Codex's on-request policy only asks when the operation would leave
        # the active sandbox. Keeping the native sandbox read-only makes file
        # changes and shell writes observable before they start, matching
        # Claude's manual permission flow.
        params["sandbox"] = "read-only"
        params["approvalPolicy"] = "on-request"
        params["approvalsReviewer"] = "user"
    elif execution_policy.automatic_execution:
        # This only removes native approval prompts. The Silk-derived sandbox,
        # workspace root and disabled shell features above stay in force.
        params["approvalPolicy"] = "never"
        params["approvalsReviewer"] = "user"
    elif execution_policy.access_mode != AUTONOMOUS:
        # A read-only/chat-only Silk ceiling must not be expandable through a
        # native approval. Autonomous intentionally retains the user's stricter
        # native Codex approval policy.
        params["approvalPolicy"] = "never"
        params["approvalsReviewer"] = "user"
    return method, params


def parse_app_server_notification(
    method: str,
    params: dict[str, Any],
    usage: dict[str, int] | None = None,
) -> dict[str, Any]:
    """Translate one app-server notification into an executor event."""
    if method == "thread/started":
        thread = params.get("thread") or {}
        return {"kind": "thread_started", "thread_id": thread.get("id", "")}

    if method in ("item/started", "item/completed"):
        item = dict(params.get("item") or {})
        item_type = item.get("type")
        if item_type in ("mcpToolCall", "dynamicToolCall"):
            item["type"] = "function_call"
            item["name"] = item.get("tool") or "MCP"
            arguments = item.get("arguments", "")
            item["arguments"] = (
                arguments if isinstance(arguments, str)
                else json.dumps(arguments, ensure_ascii=False, separators=(",", ":"))
            )
            result = item.get("result")
            error = item.get("error")
            item["output"] = _display_json(result if result is not None else error)
        else:
            item["type"] = _APP_SERVER_ITEM_TYPES.get(str(item_type), item_type)
        item["aggregated_output"] = item.get("aggregatedOutput") or ""
        exit_code = item.get("exitCode")
        if item.get("type") == "command_execution" and exit_code is None:
            exit_code = 0 if item.get("status") == "completed" else 1
        item["exit_code"] = exit_code
        return parse_jsonl_event({
            "type": "item.started" if method == "item/started" else "item.completed",
            "item": item,
        })

    if method == "turn/completed":
        turn = params.get("turn") or {}
        if turn.get("status") == "failed":
            error = turn.get("error") or {}
            if isinstance(error, dict):
                message = error.get("message") or error.get("additionalDetails")
            else:
                message = str(error)
            return {"kind": "turn_failed", "error": message or "turn failed"}
        totals = usage or {}
        return {
            "kind": "turn_completed",
            "input_tokens": int(totals.get("input_tokens") or 0),
            "output_tokens": int(totals.get("output_tokens") or 0),
            "reasoning_tokens": int(totals.get("reasoning_tokens") or 0),
        }

    if method == "error":
        error = params.get("error") or params
        message = error.get("message", "") if isinstance(error, dict) else str(error)
        return {"kind": "error", "message": message, "transient": False}

    return {"kind": "ignore"}


def approval_response(method: str, allowed: bool, params: dict[str, Any]) -> dict[str, Any]:
    """Build the method-specific response expected by Codex app-server."""
    if method in {
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
    }:
        return {"decision": "accept" if allowed else "decline"}
    if method == "item/permissions/requestApproval":
        return {
            "permissions": params.get("permissions") if allowed else {},
            "scope": "turn",
        }
    if method in {"execCommandApproval", "applyPatchApproval"}:
        return {
            "decision": "approved" if allowed else {
                "denied": {"rejection": "Denied by Silk permission policy."}
            }
        }
    return {}


class CodexAppServerExecutor:
    """Run one managed turn through a short-lived app-server process."""

    async def run(
        self,
        *,
        prompt: str,
        cwd: str,
        resume_thread_id: str | None,
        execution_policy: ExecutionPolicy,
        approval_handler: ApprovalHandler | None,
        question_handler: QuestionHandler | None = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
        proc = await asyncio.create_subprocess_exec(
            *build_app_server_command(execution_policy),
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            start_new_session=(os.name == "posix"),
            limit=10 * 1024 * 1024,
        )
        assert proc.stdin is not None
        assert proc.stdout is not None
        assert proc.stderr is not None
        stderr_task = asyncio.create_task(proc.stderr.read())
        yield {"kind": "_proc", "proc": proc}

        items: dict[str, dict[str, Any]] = {}
        usage = {"input_tokens": 0, "output_tokens": 0, "reasoning_tokens": 0}
        last_activity = time.monotonic()
        deadline = last_activity + CODEX_TIMEOUT

        try:
            await self._send_request(proc, 1, "initialize", {
                "clientInfo": {"name": "silk", "version": "1"},
                "capabilities": {"experimentalApi": True},
            })
            await self._read_response(proc, 1)

            thread_method, thread_params = build_thread_request(
                cwd=cwd,
                resume_thread_id=resume_thread_id,
                execution_policy=execution_policy,
            )
            await self._send_request(proc, 2, thread_method, thread_params)
            thread_response = await self._read_response(proc, 2)
            thread = (thread_response.get("result") or {}).get("thread") or {}
            thread_id = thread.get("id") or resume_thread_id
            if not thread_id:
                raise RuntimeError("Codex app-server did not return a thread id")
            yield {"kind": "thread_started", "thread_id": thread_id}

            await self._send_request(proc, 3, "turn/start", {
                "threadId": thread_id,
                "input": [{"type": "text", "text": sanitize_text(prompt)}],
                "cwd": cwd,
                "runtimeWorkspaceRoots": [cwd],
            })
            await self._read_response(proc, 3)

            while True:
                if time.monotonic() >= deadline:
                    raise TimeoutError(f"Codex app-server turn timed out after {CODEX_TIMEOUT}s")
                try:
                    raw_line = await asyncio.wait_for(
                        proc.stdout.readline(),
                        timeout=IDLE_REFRESH_S,
                    )
                except asyncio.TimeoutError:
                    if proc.returncode is not None:
                        break
                    elapsed = int(time.monotonic() - last_activity)
                    yield {"kind": "status_update", "text": f"💭 思考中... (已等待 {elapsed}s)"}
                    continue

                if not raw_line:
                    break
                last_activity = time.monotonic()
                message = _decode_message(raw_line)
                if message is None:
                    continue

                method = message.get("method")
                params = message.get("params") or {}
                if method in _APPROVAL_METHODS or method in {
                    "execCommandApproval", "applyPatchApproval",
                }:
                    item = items.get(str(params.get("itemId") or ""))
                    allowed = False
                    if approval_handler is not None:
                        allowed = await approval_handler(method, params, item)
                    await self._send_result(
                        proc,
                        message.get("id"),
                        approval_response(method, allowed, params),
                    )
                    continue

                if method == "item/tool/requestUserInput":
                    answers = (
                        await question_handler(params)
                        if question_handler is not None else {"answers": {}}
                    )
                    await self._send_result(proc, message.get("id"), answers)
                    continue
                if method == "mcpServer/elicitation/request":
                    await self._send_result(proc, message.get("id"), {"action": "decline"})
                    continue
                if method and message.get("id") is not None:
                    await self._send_error(proc, message.get("id"), -32601, f"Unsupported request: {method}")
                    continue
                if not method:
                    continue

                if method in ("item/started", "item/completed"):
                    item = params.get("item") or {}
                    item_id = item.get("id")
                    if item_id:
                        items[str(item_id)] = item
                elif method == "thread/tokenUsage/updated":
                    raw_usage = ((params.get("tokenUsage") or {}).get("last") or {})
                    usage = {
                        "input_tokens": int(raw_usage.get("inputTokens") or 0),
                        "output_tokens": int(raw_usage.get("outputTokens") or 0),
                        "reasoning_tokens": int(raw_usage.get("reasoningOutputTokens") or 0),
                    }

                parsed = parse_app_server_notification(method, params, usage)
                if parsed.get("kind") != "ignore":
                    yield parsed
                if method == "turn/completed":
                    break
        finally:
            await _close_process(proc)
            stderr_bytes = await _drain_stderr(stderr_task)
            if proc.returncode not in (None, 0) and stderr_bytes:
                logger.warning(
                    "codex app-server exited with code %s (stderr_chars=%d)",
                    proc.returncode,
                    len(stderr_bytes.decode("utf-8", errors="replace")),
                )

    @staticmethod
    async def _send_request(
        proc: asyncio.subprocess.Process,
        request_id: int,
        method: str,
        params: dict[str, Any],
    ) -> None:
        await _write_message(proc, {
            "jsonrpc": "2.0", "id": request_id, "method": method, "params": params,
        })

    @staticmethod
    async def _send_result(proc: asyncio.subprocess.Process, request_id: Any, result: Any) -> None:
        await _write_message(proc, {"jsonrpc": "2.0", "id": request_id, "result": result})

    @staticmethod
    async def _send_error(
        proc: asyncio.subprocess.Process,
        request_id: Any,
        code: int,
        message: str,
    ) -> None:
        await _write_message(proc, {
            "jsonrpc": "2.0", "id": request_id,
            "error": {"code": code, "message": message},
        })

    @staticmethod
    async def _read_response(proc: asyncio.subprocess.Process, request_id: int) -> dict[str, Any]:
        assert proc.stdout is not None
        while True:
            raw_line = await proc.stdout.readline()
            if not raw_line:
                raise RuntimeError("Codex app-server exited during initialization")
            message = _decode_message(raw_line)
            if message is None or message.get("id") != request_id or message.get("method"):
                continue
            if "error" in message:
                error = message.get("error") or {}
                raise RuntimeError(error.get("message") or f"Codex request {request_id} failed")
            return message


async def _write_message(proc: asyncio.subprocess.Process, message: dict[str, Any]) -> None:
    if proc.stdin is None or proc.stdin.is_closing():
        raise RuntimeError("Codex app-server stdin is closed")
    encoded = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
    proc.stdin.write(encoded)
    await proc.stdin.drain()


def _decode_message(raw_line: bytes) -> dict[str, Any] | None:
    try:
        value = json.loads(raw_line.decode("utf-8", errors="replace"))
    except json.JSONDecodeError:
        logger.warning("codex app-server emitted non-JSON output (%d bytes)", len(raw_line))
        return None
    return value if isinstance(value, dict) else None


def _display_json(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


async def _close_process(proc: asyncio.subprocess.Process) -> None:
    if proc.stdin is not None and not proc.stdin.is_closing():
        proc.stdin.close()
    if proc.returncode is not None:
        return
    try:
        await asyncio.wait_for(proc.wait(), timeout=5.0)
        return
    except asyncio.TimeoutError:
        pass
    try:
        if os.name == "posix" and proc.pid is not None:
            os.killpg(proc.pid, signal.SIGTERM)
        else:
            proc.terminate()
    except ProcessLookupError:
        return
    try:
        await asyncio.wait_for(proc.wait(), timeout=2.0)
    except asyncio.TimeoutError:
        proc.kill()
        await proc.wait()


async def _drain_stderr(task: asyncio.Task[bytes]) -> bytes:
    try:
        return await asyncio.wait_for(asyncio.shield(task), timeout=2.0)
    except asyncio.TimeoutError:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        return b""
