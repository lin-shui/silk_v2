"""Unit tests for codex_executor JSONL → ACP-event parsing."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from codex_bridge.codex_executor import parse_jsonl_event

FIXTURE = Path(__file__).parent / "fixtures" / "jsonl_simple_chat.jsonl"


def _load_fixture_lines() -> list[dict]:
    return [json.loads(ln) for ln in FIXTURE.read_text().splitlines() if ln.strip()]


def test_thread_started_extracts_thread_id():
    raw = {"type": "thread.started", "thread_id": "abc-123"}
    ev = parse_jsonl_event(raw)
    assert ev == {"kind": "thread_started", "thread_id": "abc-123"}


def test_turn_started_is_ignored():
    raw = {"type": "turn.started"}
    ev = parse_jsonl_event(raw)
    assert ev == {"kind": "ignore"}


def test_item_completed_agent_message_yields_text():
    raw = {
        "type": "item.completed",
        "item": {"id": "item_0", "type": "agent_message", "text": "hello"},
    }
    ev = parse_jsonl_event(raw)
    assert ev == {"kind": "agent_message", "text": "hello"}


def test_item_completed_unknown_item_type_is_ignored():
    # Tests that a truly unrecognised item type is silently ignored.
    # (M1 used "reasoning" here; M2 now parses reasoning, so we use a future
    # placeholder type instead.)
    raw = {
        "type": "item.completed",
        "item": {"id": "item_1", "type": "some_future_item_type", "data": "..."},
    }
    ev = parse_jsonl_event(raw)
    assert ev == {"kind": "ignore"}


def test_turn_completed_extracts_usage():
    raw = {
        "type": "turn.completed",
        "usage": {
            "input_tokens": 100,
            "output_tokens": 50,
            "reasoning_output_tokens": 20,
            "cached_input_tokens": 80,
        },
    }
    ev = parse_jsonl_event(raw)
    assert ev == {
        "kind": "turn_completed",
        "input_tokens": 100,
        "output_tokens": 50,
        "reasoning_tokens": 20,
    }


def test_turn_completed_without_usage_is_safe():
    raw = {"type": "turn.completed"}
    ev = parse_jsonl_event(raw)
    assert ev == {"kind": "turn_completed", "input_tokens": 0, "output_tokens": 0, "reasoning_tokens": 0}


def test_unknown_top_level_type_is_ignored():
    raw = {"type": "some.future.type", "foo": "bar"}
    ev = parse_jsonl_event(raw)
    assert ev == {"kind": "ignore"}


def test_real_fixture_round_trip():
    events = [parse_jsonl_event(raw) for raw in _load_fixture_lines()]
    kinds = [e["kind"] for e in events]
    assert "thread_started" in kinds
    assert "agent_message" in kinds
    assert "turn_completed" in kinds
    for e in events:
        if e["kind"] == "agent_message":
            assert isinstance(e["text"], str) and e["text"]


# ---- M2: new event types ----

def test_command_execution_started():
    raw = {
        "type": "item.started",
        "item": {
            "id": "item_1",
            "type": "command_execution",
            "command": "/bin/bash -lc 'ls'",
            "aggregated_output": "",
            "exit_code": None,
            "status": "in_progress",
        },
    }
    ev = parse_jsonl_event(raw)
    assert ev == {
        "kind": "command_started",
        "tool_id": "item_1",
        "command": "/bin/bash -lc 'ls'",
    }


def test_command_execution_completed_success():
    raw = {
        "type": "item.completed",
        "item": {
            "id": "item_1",
            "type": "command_execution",
            "command": "/bin/bash -lc 'ls'",
            "aggregated_output": "file1\nfile2\n",
            "exit_code": 0,
            "status": "completed",
        },
    }
    ev = parse_jsonl_event(raw)
    assert ev == {
        "kind": "command_completed",
        "tool_id": "item_1",
        "command": "/bin/bash -lc 'ls'",
        "exit_code": 0,
        "output": "file1\nfile2\n",
    }


def test_command_execution_completed_failure():
    raw = {
        "type": "item.completed",
        "item": {
            "id": "item_2",
            "type": "command_execution",
            "command": "false",
            "aggregated_output": "",
            "exit_code": 1,
            "status": "completed",
        },
    }
    ev = parse_jsonl_event(raw)
    assert ev["kind"] == "command_completed"
    assert ev["exit_code"] == 1


def test_file_change_started():
    raw = {
        "type": "item.started",
        "item": {
            "id": "item_4",
            "type": "file_change",
            "changes": [
                {"path": "/tmp/a.txt", "kind": "add"},
                {"path": "/tmp/b.txt", "kind": "modify"},
            ],
            "status": "in_progress",
        },
    }
    ev = parse_jsonl_event(raw)
    assert ev == {
        "kind": "file_change_started",
        "tool_id": "item_4",
        "paths": ["/tmp/a.txt", "/tmp/b.txt"],
        "kinds": ["add", "modify"],
    }


def test_file_change_completed():
    raw = {
        "type": "item.completed",
        "item": {
            "id": "item_4",
            "type": "file_change",
            "changes": [{"path": "/tmp/a.txt", "kind": "add"}],
            "status": "completed",
        },
    }
    ev = parse_jsonl_event(raw)
    assert ev == {
        "kind": "file_change_completed",
        "tool_id": "item_4",
        "paths": ["/tmp/a.txt"],
        "kinds": ["add"],
    }


def test_reasoning_yields_text():
    raw = {
        "type": "item.completed",
        "item": {
            "id": "item_0",
            "type": "reasoning",
            "text": "**Planning**\n\nI need to think about...",
        },
    }
    ev = parse_jsonl_event(raw)
    assert ev == {
        "kind": "reasoning",
        "text": "**Planning**\n\nI need to think about...",
    }


def test_tool_use_fixture_round_trip():
    """Parse the captured tool_use fixture; ensure all expected kinds appear."""
    fixture = Path(__file__).parent / "fixtures" / "jsonl_tool_use.jsonl"
    raws = [json.loads(ln) for ln in fixture.read_text().splitlines() if ln.strip()]
    events = [parse_jsonl_event(r) for r in raws]
    kinds = {e["kind"] for e in events}
    assert "thread_started" in kinds
    assert "agent_message" in kinds
    assert "command_started" in kinds
    assert "command_completed" in kinds
    assert "file_change_started" in kinds
    assert "file_change_completed" in kinds
    assert "turn_completed" in kinds


def test_reasoning_fixture_round_trip():
    fixture = Path(__file__).parent / "fixtures" / "jsonl_with_reasoning.jsonl"
    raws = [json.loads(ln) for ln in fixture.read_text().splitlines() if ln.strip()]
    events = [parse_jsonl_event(r) for r in raws]
    kinds = {e["kind"] for e in events}
    assert "reasoning" in kinds


# ---- M2: cmd builder ----

from codex_bridge.codex_executor import CodexExecutor


def test_build_cmd_uses_global_cd_for_fresh():
    """Fresh run: codex --cd <cwd> exec --json ... -"""
    ex = CodexExecutor(auto_approve=True)
    cmd = ex._build_cmd(cwd="/tmp", resume_thread_id=None)
    # --cd MUST come BEFORE the `exec` subcommand (global flag).
    cd_idx = cmd.index("--cd")
    exec_idx = cmd.index("exec")
    assert cd_idx < exec_idx, f"expected --cd before exec, got {cmd}"
    assert cmd[cd_idx + 1] == "/tmp"
    # Reasoning flag still present
    assert "show_raw_agent_reasoning=true" in cmd
    # Stdin marker
    assert cmd[-1] == "-"


def test_build_cmd_uses_global_cd_for_resume():
    """Resume: codex --cd <cwd> exec resume <id> --json ... -"""
    ex = CodexExecutor(auto_approve=True)
    cmd = ex._build_cmd(cwd="/tmp", resume_thread_id="abc-123")
    cd_idx = cmd.index("--cd")
    exec_idx = cmd.index("exec")
    resume_idx = cmd.index("resume")
    assert cd_idx < exec_idx < resume_idx, f"expected --cd<exec<resume, got {cmd}"
    assert cmd[cd_idx + 1] == "/tmp"
    # session id immediately after `resume`
    assert cmd[resume_idx + 1] == "abc-123"
    # Reasoning flag present
    assert "show_raw_agent_reasoning=true" in cmd
    assert cmd[-1] == "-"


# ---- M2: cancel_process ----

import asyncio
import signal


class _FakeProcess:
    """Minimal stand-in for asyncio.subprocess.Process for cancel tests."""

    def __init__(self, *, dies_on_sigint: bool, sigint_delay: float = 0.0) -> None:
        self.dies_on_sigint = dies_on_sigint
        self.sigint_delay = sigint_delay
        self.returncode: int | None = None
        self.signals_received: list[int] = []
        self._wait_future: asyncio.Future[int] | None = None

    def _ensure_future(self) -> asyncio.Future[int]:
        if self._wait_future is None:
            self._wait_future = asyncio.get_running_loop().create_future()
        return self._wait_future

    def send_signal(self, sig: int) -> None:
        self.signals_received.append(sig)
        if sig == signal.SIGINT and self.dies_on_sigint:
            async def die() -> None:
                if self.sigint_delay:
                    await asyncio.sleep(self.sigint_delay)
                self.returncode = 130  # 128 + SIGINT
                fut = self._ensure_future()
                if not fut.done():
                    fut.set_result(130)
            asyncio.create_task(die())

    def kill(self) -> None:
        self.signals_received.append(signal.SIGKILL)
        self.returncode = -9
        fut = self._ensure_future()
        if not fut.done():
            fut.set_result(-9)

    async def wait(self) -> int:
        if self.returncode is not None:
            return self.returncode
        return await self._ensure_future()


def test_cancel_sends_sigint_when_proc_alive():
    from codex_bridge.codex_executor import cancel_process
    async def _go():
        proc = _FakeProcess(dies_on_sigint=True, sigint_delay=0.1)
        await cancel_process(proc, sigint_grace_seconds=1.0)
        return proc
    proc = asyncio.run(_go())
    assert signal.SIGINT in proc.signals_received
    assert signal.SIGKILL not in proc.signals_received
    assert proc.returncode == 130


def test_cancel_escalates_to_sigkill_when_sigint_ignored():
    from codex_bridge.codex_executor import cancel_process
    async def _go():
        proc = _FakeProcess(dies_on_sigint=False)
        await cancel_process(proc, sigint_grace_seconds=0.2)
        return proc
    proc = asyncio.run(_go())
    assert signal.SIGINT in proc.signals_received
    assert signal.SIGKILL in proc.signals_received
    assert proc.returncode == -9


def test_cancel_noop_when_proc_already_dead():
    from codex_bridge.codex_executor import cancel_process
    async def _go():
        proc = _FakeProcess(dies_on_sigint=False)
        proc.returncode = 0
        await cancel_process(proc, sigint_grace_seconds=0.2)
        return proc
    proc = asyncio.run(_go())
    assert proc.signals_received == []  # no signal sent to dead proc
