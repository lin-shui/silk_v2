"""Codex CLI executor — wraps `codex exec --json` and parses its JSONL stream.

Normalizes agent messages, reasoning, command/file edits, function calls,
built-in tool events, and terminal turn status for the ACP bridge layer.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import shlex
import signal
import time
from typing import Any, AsyncGenerator

logger = logging.getLogger("codex_bridge.executor")

# Seconds between idle heartbeat status updates (matches cc_bridge).
IDLE_REFRESH_S: float = 2.0

# Subprocess cleanup guards. The initial grace period gives Codex time to flush
# rollout/session data; the kill grace period bounds bridge-side hangs.
PROCESS_EXIT_GRACE_S: float = 30.0
PROCESS_KILL_GRACE_S: float = 5.0
STDERR_DRAIN_GRACE_S: float = 5.0

# Total execution timeout (seconds). Kill the subprocess if it exceeds this.
# Mirrors cc_bridge's CLAUDE_CODE_TIMEOUT. Default: 10 hours.
CODEX_TIMEOUT: int = int(os.environ.get("CODEX_TIMEOUT", "36000"))


# ---------------------------------------------------------------------------
# Pure parsing — testable without any subprocess.
# ---------------------------------------------------------------------------

def parse_jsonl_event(raw: dict[str, Any]) -> dict[str, Any]:
    """Map one Codex JSONL event dict to a normalized adapter event.

    Returns one of:
        {"kind": "thread_started", "thread_id": str}
        {"kind": "agent_message", "text": str}
        {"kind": "reasoning", "text": str}
        {"kind": "command_started", "tool_id": str, "command": str}
        {"kind": "command_completed", "tool_id": str, "command": str, "exit_code": int, "output": str}
        {"kind": "file_change_started", "tool_id": str, "paths": list[str], "kinds": list[str]}
        {"kind": "file_change_completed", "tool_id": str, "paths": list[str], "kinds": list[str]}
        {"kind": "function_call_started", "tool_id": str, "name": str, "arguments": str}
        {"kind": "function_call_completed", "tool_id": str, "name": str, "status": str, "output": str}
        {"kind": "tool_started", "tool_id": str, "tool_name": str, "input": str}
        {"kind": "tool_completed", "tool_id": str, "tool_name": str, "output": str}
        {"kind": "turn_completed", "input_tokens": int, "output_tokens": int, "reasoning_tokens": int}
        {"kind": "turn_failed", "error": str}
        {"kind": "error", "message": str, "transient": bool}
        {"kind": "ignore"}
    """
    t = raw.get("type")

    if t == "thread.started":
        return {"kind": "thread_started", "thread_id": raw.get("thread_id", "")}

    if t == "turn.started":
        return {"kind": "ignore"}

    if t in ("item.started", "item.completed"):
        item = raw.get("item") or {}
        item_type = item.get("type")
        item_id = item.get("id", "")

        # Agent message: only emitted as item.completed
        if item_type == "agent_message" and t == "item.completed":
            return {"kind": "agent_message", "text": item.get("text", "")}

        # Reasoning: only emitted as item.completed (when show_raw_agent_reasoning=true)
        if item_type == "reasoning" and t == "item.completed":
            text = _extract_reasoning_text(item)
            return {"kind": "reasoning", "text": text}

        # Command execution: paired item.started + item.completed
        if item_type == "command_execution":
            if t == "item.started":
                return {
                    "kind": "command_started",
                    "tool_id": item_id,
                    "command": item.get("command", ""),
                }
            return {
                "kind": "command_completed",
                "tool_id": item_id,
                "command": item.get("command", ""),
                "exit_code": int(item.get("exit_code") or 0),
                "output": item.get("aggregated_output", ""),
            }

        # File change: paired item.started + item.completed
        if item_type == "file_change":
            changes = item.get("changes") or []
            paths = [c.get("path", "") for c in changes]
            kinds_list = [c.get("kind", "") for c in changes]
            kind_name = "file_change_started" if t == "item.started" else "file_change_completed"
            return {
                "kind": kind_name,
                "tool_id": item_id,
                "paths": paths,
                "kinds": kinds_list,
            }

        # Function call (custom functions / MCP): paired started + completed
        if item_type == "function_call":
            name = item.get("name", "")
            if t == "item.started":
                return {
                    "kind": "function_call_started",
                    "tool_id": item_id,
                    "name": name,
                    "arguments": item.get("arguments", ""),
                }
            return {
                "kind": "function_call_completed",
                "tool_id": item_id,
                "name": name,
                "status": item.get("status", ""),
                "output": item.get("output", ""),
            }

        # Built-in tools: web_search, file_search, code_interpreter, computer_use, mcp_tool
        tool_name = _CODEX_TOOL_NAMES.get(item_type)
        if tool_name is not None:
            tool_input = _extract_tool_input(item)
            if t == "item.started":
                return {
                    "kind": "tool_started",
                    "tool_id": item_id,
                    "tool_name": tool_name,
                    "input": tool_input,
                }
            return {
                "kind": "tool_completed",
                "tool_id": item_id,
                "tool_name": tool_name,
                "output": tool_input,
            }

        return {"kind": "ignore"}

    if t == "turn.completed":
        usage = raw.get("usage") or {}
        return {
            "kind": "turn_completed",
            "input_tokens": int(usage.get("input_tokens") or 0),
            "output_tokens": int(usage.get("output_tokens") or 0),
            "reasoning_tokens": int(usage.get("reasoning_output_tokens") or 0),
        }

    if t == "turn.failed":
        err_msg = ""
        err_obj = raw.get("error")
        if isinstance(err_obj, dict):
            err_msg = err_obj.get("message", "")
        if not err_msg:
            err_msg = "turn failed (no details)"
        return {"kind": "turn_failed", "error": err_msg}

    if t == "error":
        msg = raw.get("message", "")
        transient = any(kw in msg for kw in ("Reconnecting", "Falling back"))
        return {"kind": "error", "message": msg, "transient": transient}

    return {"kind": "ignore"}


# Mapping of Codex item types to human-readable tool names
# (mirrors cc-connect codexToolNames)
_CODEX_TOOL_NAMES: dict[str, str] = {
    "web_search": "WebSearch",
    "file_search": "FileSearch",
    "code_interpreter": "CodeInterpreter",
    "computer_use": "ComputerUse",
    "mcp_tool": "MCP",
}


def _extract_tool_input(item: dict[str, Any]) -> str:
    """Extract a human-readable input/output string from a Codex tool item."""
    # web_search: action.queries[] or query
    action = item.get("action")
    if isinstance(action, dict):
        queries = action.get("queries")
        if isinstance(queries, list) and queries:
            parts = [str(q) for q in queries if q]
            if parts:
                return "\n".join(parts)
        query = action.get("query")
        if query:
            return str(query)
    query = item.get("query")
    if query:
        return str(query)
    name = item.get("name")
    if name:
        return str(name)
    return ""


def _extract_reasoning_text(item: dict[str, Any]) -> str:
    """Extract text from a reasoning item, handling both summary[] and text formats."""
    summary = item.get("summary")
    if isinstance(summary, list):
        parts = []
        for entry in summary:
            if isinstance(entry, dict):
                t = entry.get("type", "")
                if t == "summary_text":
                    text = entry.get("text", "")
                    if text:
                        parts.append(text)
            elif isinstance(entry, str) and entry.strip():
                parts.append(entry)
        if parts:
            return "\n".join(parts)
    return item.get("text", "")


def _proc_pid(proc: Any) -> str:
    pid = getattr(proc, "pid", None)
    return str(pid) if pid is not None else "?"


def _signal_name(sig: int) -> str:
    try:
        return signal.Signals(sig).name
    except ValueError:
        return str(sig)


def _send_signal(proc: Any, sig: int, *, phase: str) -> None:
    pid = getattr(proc, "pid", None)
    sig_name = _signal_name(sig)
    try:
        if os.name == "posix" and pid is not None:
            os.killpg(pid, sig)
            logger.warning(
                "sent %s to codex process group pgid=%s during %s",
                sig_name,
                pid,
                phase,
            )
            return
        if sig == signal.SIGKILL:
            proc.kill()
        else:
            proc.send_signal(sig)
        logger.warning(
            "sent %s to codex process pid=%s during %s",
            sig_name,
            _proc_pid(proc),
            phase,
        )
    except ProcessLookupError:
        logger.info(
            "codex process already exited before %s during %s: pid=%s",
            sig_name,
            phase,
            _proc_pid(proc),
        )


async def _wait_for_process_exit(proc: Any, *, timeout: float, phase: str) -> bool:
    if proc.returncode is not None:
        return True
    try:
        await asyncio.wait_for(proc.wait(), timeout=timeout)
        return True
    except asyncio.TimeoutError:
        logger.warning(
            "codex process still running after %.1fs during %s: pid=%s",
            timeout,
            phase,
            _proc_pid(proc),
        )
        return False


async def _drain_stderr_task(stderr_task: asyncio.Task[bytes], *, timeout: float, phase: str) -> bytes:
    try:
        return await asyncio.wait_for(asyncio.shield(stderr_task), timeout=timeout)
    except asyncio.TimeoutError:
        logger.warning(
            "timed out draining codex stderr after %.1fs during %s; cancelling stderr reader",
            timeout,
            phase,
        )
        stderr_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await stderr_task
        return b""


# ---------------------------------------------------------------------------
# Subprocess wrapper — used by adapter at runtime.
# ---------------------------------------------------------------------------

class CodexExecutor:
    """Spawns `codex exec --json` and yields parsed events."""

    def __init__(self, *, auto_approve: bool = True) -> None:
        self.auto_approve = auto_approve

    def _build_cmd(self, *, cwd: str, resume_thread_id: str | None) -> list[str]:
        # Use GLOBAL --cd (before `exec`) so the same shape works for both
        # fresh runs and `exec resume` (which does not accept --cd as a
        # subcommand flag).
        cmd: list[str] = ["codex", "--cd", cwd]
        if resume_thread_id:
            cmd += ["exec", "resume", resume_thread_id]
        else:
            cmd += ["exec"]
        # Common per-run flags after the subcommand:
        cmd += [
            "--json",
            "--skip-git-repo-check",
            "-c", "show_raw_agent_reasoning=true",
        ]
        if self.auto_approve:
            cmd.append("--dangerously-bypass-approvals-and-sandbox")
        cmd.append("-")
        return cmd

    async def run(
        self,
        *,
        prompt: str,
        cwd: str,
        resume_thread_id: str | None = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
        cmd = self._build_cmd(cwd=cwd, resume_thread_id=resume_thread_id)
        logger.info("codex exec: %s", shlex.join(cmd))

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            start_new_session=(os.name == "posix"),
            limit=10 * 1024 * 1024,  # 10 MB — Codex JSONL lines can exceed the 64 KB default
        )
        logger.info(
            "codex exec started: pid=%s cwd=%s resume=%s prompt_chars=%d",
            _proc_pid(proc),
            cwd,
            resume_thread_id or "-",
            len(prompt),
        )
        assert proc.stdin is not None
        proc.stdin.write(prompt.encode("utf-8"))
        await proc.stdin.drain()
        proc.stdin.close()

        # Drain stderr concurrently to avoid pipe-buffer deadlock when codex
        # writes large stderr (e.g. crash dumps).
        assert proc.stderr is not None
        stderr_task = asyncio.create_task(proc.stderr.read())

        yield {"kind": "_proc", "proc": proc}

        assert proc.stdout is not None

        # Timeout watchdog — kill subprocess after CODEX_TIMEOUT seconds
        # (mirrors cc_bridge's _timeout_watchdog).
        timeout_task = asyncio.create_task(self._timeout_watchdog(proc))

        # Phase-aware idle heartbeat (mirrors cc_bridge/executor.py)
        model_thinking = True
        phase_start_time = time.monotonic()
        last_kind = "_proc"
        saw_stdout_eof = False

        try:
            while True:
                try:
                    raw_line = await asyncio.wait_for(
                        proc.stdout.readline(),
                        timeout=IDLE_REFRESH_S,
                    )
                except asyncio.TimeoutError:
                    # No data within IDLE_REFRESH_S — emit heartbeat
                    if proc.returncode is not None:
                        break
                    elapsed = int(time.monotonic() - phase_start_time)
                    if model_thinking:
                        status = f"\U0001f4ad \u601d\u8003\u4e2d... (\u5df2\u7b49\u5f85 {elapsed}s)"
                    else:
                        status = f"\u23f3 \u6b63\u5728\u5904\u7406... (\u5df2\u7b49\u5f85 {elapsed}s)"
                    yield {"kind": "status_update", "text": status}
                    continue

                if not raw_line:
                    saw_stdout_eof = True
                    logger.info(
                        "codex stdout EOF: pid=%s returncode=%s last_kind=%s",
                        _proc_pid(proc),
                        proc.returncode,
                        last_kind,
                    )
                    break

                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    logger.warning("codex emitted non-JSON line: %r", line)
                    continue

                parsed = parse_jsonl_event(obj)

                # Phase transitions for heartbeat status text
                prev_thinking = model_thinking
                kind = parsed.get("kind")
                if kind:
                    last_kind = str(kind)
                if kind in ("command_started", "file_change_started", "agent_message",
                            "function_call_started", "tool_started"):
                    model_thinking = False
                elif kind in ("command_completed", "file_change_completed", "reasoning",
                              "function_call_completed", "tool_completed"):
                    model_thinking = True
                if model_thinking != prev_thinking:
                    phase_start_time = time.monotonic()

                yield parsed
        finally:
            timeout_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await timeout_task

            # Wait for process exit with a short grace period. If the caller
            # broke out of the loop (e.g. after turn_completed), we cannot
            # yield here (GeneratorExit would be raised). Just wait/kill and log.
            exit_phase = (
                "executor cleanup after stdout EOF"
                if saw_stdout_eof
                else "executor cleanup after caller break"
            )
            exited = await _wait_for_process_exit(
                proc,
                timeout=PROCESS_EXIT_GRACE_S,
                phase=exit_phase,
            )
            if not exited:
                _send_signal(proc, signal.SIGKILL, phase=exit_phase)
                exited = await _wait_for_process_exit(
                    proc,
                    timeout=PROCESS_KILL_GRACE_S,
                    phase=f"{exit_phase} after SIGKILL",
                )
                if not exited:
                    logger.error(
                        "codex process did not exit after SIGKILL; continuing cleanup without it: pid=%s last_kind=%s",
                        _proc_pid(proc),
                        last_kind,
                    )

            stderr_phase = exit_phase if exited else f"{exit_phase} (process still running)"
            stderr_bytes = await _drain_stderr_task(
                stderr_task,
                timeout=STDERR_DRAIN_GRACE_S,
                phase=stderr_phase,
            )
            rc = proc.returncode or 0
            if rc != 0:
                stderr_text = stderr_bytes.decode("utf-8", errors="replace")
                logger.warning(
                    "codex process exited with code %d: %s", rc, stderr_text[:500],
                )

    @staticmethod
    async def _timeout_watchdog(proc: asyncio.subprocess.Process) -> None:
        """Kill the subprocess after CODEX_TIMEOUT seconds."""
        try:
            await asyncio.sleep(CODEX_TIMEOUT)
            if proc.returncode is None:
                logger.warning(
                    "codex subprocess timed out (%ds), killing", CODEX_TIMEOUT,
                )
                _send_signal(proc, signal.SIGKILL, phase="timeout watchdog")
        except asyncio.CancelledError:
            pass


async def cancel_process(
    proc: Any,  # asyncio.subprocess.Process or compatible
    *,
    sigint_grace_seconds: float = 1.0,
    sigkill_grace_seconds: float = PROCESS_KILL_GRACE_S,
) -> None:
    """Cancel a running codex subprocess: SIGINT first, then SIGKILL after grace.

    Sends SIGINT to give codex a chance to flush its session file and exit
    cleanly. If the process hasn't exited within `sigint_grace_seconds`,
    escalates to SIGKILL, but never waits forever. No-op if the process is
    already dead.
    """
    if proc.returncode is not None:
        return  # already exited
    _send_signal(proc, signal.SIGINT, phase="cancel")
    exited = await _wait_for_process_exit(
        proc,
        timeout=sigint_grace_seconds,
        phase="cancel after SIGINT",
    )
    if exited:
        return
    logger.warning(
        "codex did not exit %.1fs after SIGINT, escalating to SIGKILL: pid=%s",
        sigint_grace_seconds,
        _proc_pid(proc),
    )
    _send_signal(proc, signal.SIGKILL, phase="cancel escalation")
    exited = await _wait_for_process_exit(
        proc,
        timeout=sigkill_grace_seconds,
        phase="cancel after SIGKILL",
    )
    if not exited:
        logger.error(
            "codex process did not exit after SIGKILL during cancel; continuing anyway: pid=%s",
            _proc_pid(proc),
        )
