"""Codex CLI executor — wraps `codex exec --json` and parses its JSONL stream.

M1 scope: text-only prompt/response. tool_call mapping (command_execution,
file_change, reasoning) is deferred to M2.
"""
from __future__ import annotations

import asyncio
import json
import logging
import shlex
from dataclasses import dataclass
from typing import Any, AsyncGenerator

logger = logging.getLogger("codex_bridge.executor")


# ---------------------------------------------------------------------------
# Pure parsing — testable without any subprocess.
# ---------------------------------------------------------------------------

def parse_jsonl_event(raw: dict[str, Any]) -> dict[str, Any]:
    """Map one Codex JSONL event dict to a normalized adapter event.

    Returns one of:
        {"kind": "thread_started", "thread_id": str}
        {"kind": "agent_message", "text": str}
        {"kind": "turn_completed", "input_tokens": int, "output_tokens": int, "reasoning_tokens": int}
        {"kind": "ignore"}
    """
    t = raw.get("type")

    if t == "thread.started":
        return {"kind": "thread_started", "thread_id": raw.get("thread_id", "")}

    if t == "turn.started":
        return {"kind": "ignore"}

    if t == "item.completed":
        item = raw.get("item") or {}
        item_type = item.get("type")
        if item_type == "agent_message":
            return {"kind": "agent_message", "text": item.get("text", "")}
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
        cmd: list[str] = ["codex", "exec", "--json", "--skip-git-repo-check", "--cd", cwd]
        if self.auto_approve:
            cmd.append("--dangerously-bypass-approvals-and-sandbox")
        if resume_thread_id:
            cmd = ["codex", "exec", "resume", resume_thread_id, "--json", "--skip-git-repo-check", "--cd", cwd]
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
        try:
            async for raw_line in proc.stdout:
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    logger.warning("codex emitted non-JSON line: %r", line)
                    continue
                yield parse_jsonl_event(obj)
        finally:
            rc = await proc.wait()
            stderr_bytes = await stderr_task
            yield {
                "kind": "_done",
                "exit_code": rc,
                "stderr": stderr_bytes.decode("utf-8", errors="replace"),
            }
