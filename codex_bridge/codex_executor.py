"""Codex CLI executor — wraps `codex exec --json` and parses its JSONL stream.

M1 scope: text-only prompt/response. tool_call mapping (command_execution,
file_change, reasoning) is deferred to M2.
"""
from __future__ import annotations

import asyncio
import json
import logging
import shlex
import time
from dataclasses import dataclass
from typing import Any, AsyncGenerator

logger = logging.getLogger("codex_bridge.executor")

# Seconds between idle heartbeat status updates (matches cc_bridge).
IDLE_REFRESH_S: float = 2.0


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
        {"kind": "turn_completed", "input_tokens": int, "output_tokens": int, "reasoning_tokens": int}
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
            return {"kind": "reasoning", "text": item.get("text", "")}

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

        return {"kind": "ignore"}

    if t == "turn.completed":
        usage = raw.get("usage") or {}
        return {
            "kind": "turn_completed",
            "input_tokens": int(usage.get("input_tokens") or 0),
            "output_tokens": int(usage.get("output_tokens") or 0),
            "reasoning_tokens": int(usage.get("reasoning_output_tokens") or 0),
        }

    return {"kind": "ignore"}


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

        # Phase-aware idle heartbeat (mirrors cc_bridge/executor.py)
        model_thinking = True
        phase_start_time = time.monotonic()

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
                    break  # EOF

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
                if kind in ("command_started", "file_change_started", "agent_message"):
                    model_thinking = False
                elif kind in ("command_completed", "file_change_completed", "reasoning"):
                    model_thinking = True
                if model_thinking != prev_thinking:
                    phase_start_time = time.monotonic()

                yield parsed
        finally:
            rc = await proc.wait()
            stderr_bytes = await stderr_task
            yield {
                "kind": "_done",
                "exit_code": rc,
                "stderr": stderr_bytes.decode("utf-8", errors="replace"),
            }


async def cancel_process(
    proc: Any,  # asyncio.subprocess.Process or compatible
    *,
    sigint_grace_seconds: float = 1.0,
) -> None:
    """Cancel a running codex subprocess: SIGINT first, then SIGKILL after grace.

    Sends SIGINT to give codex a chance to flush its session file and exit
    cleanly. If the process hasn't exited within `sigint_grace_seconds`,
    escalates to SIGKILL. No-op if the process is already dead.
    """
    if proc.returncode is not None:
        return  # already exited
    import signal as _signal
    proc.send_signal(_signal.SIGINT)
    try:
        await asyncio.wait_for(proc.wait(), timeout=sigint_grace_seconds)
    except asyncio.TimeoutError:
        logger.warning("codex did not exit %ss after SIGINT, escalating to SIGKILL", sigint_grace_seconds)
        proc.kill()
        await proc.wait()
