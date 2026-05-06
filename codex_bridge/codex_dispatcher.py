"""Dispatch parsed Codex events to ACP `session/update` payloads.

This module is pure (no I/O, no asyncio) — given a parsed event from
`codex_executor.parse_jsonl_event` and a `DispatcherState`, it returns the
list of `update` dicts the adapter should emit as `session/update`
notifications. The adapter is responsible for the actual ACP send.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any


# Show the first N filenames in file_change titles, then "..."
_FILE_CHANGE_TITLE_LIMIT = 3


@dataclass
class DispatcherState:
    """Per-session mutable state held across event dispatches."""

    accumulated: str = ""
    seen_tool_ids: set[str] = field(default_factory=set)
    thread_id: str | None = None
    held_message: str | None = None  # buffered agent_message; flushed on turn_completed


def dispatch_event(ev: dict[str, Any], state: DispatcherState) -> list[dict[str, Any]]:
    """Convert a parsed event into zero or more ACP `update` dicts.

    Mutates `state` (accumulated text, seen_tool_ids, thread_id).
    """
    kind = ev.get("kind")

    if kind == "thread_started":
        state.thread_id = ev.get("thread_id")
        return []

    if kind == "agent_message":
        # Codex emits multiple SEPARATE agent_message items per turn
        # (commentary between tool calls + final answer). If we sent each
        # as agent_message_chunk, the backend would concatenate them all.
        #
        # Strategy: HOLD the current message. When the NEXT agent_message
        # arrives, emit the PREVIOUS held message as agent_thought_chunk
        # (transient status — UI replaces it). On turn_completed, flush
        # the last held message as agent_message_chunk (the real reply).
        updates: list[dict[str, Any]] = []
        if state.held_message is not None:
            # Emit previous held message as transient thought/status
            updates.append({
                "sessionUpdate": "agent_thought_chunk",
                "content": {"type": "text", "text": state.held_message},
            })
        state.held_message = ev.get("text", "")
        return updates

    if kind == "reasoning":
        return [{
            "sessionUpdate": "agent_thought_chunk",
            "content": {"type": "text", "text": ev.get("text", "")},
        }]

    if kind == "command_started":
        tool_id = ev.get("tool_id", "")
        state.seen_tool_ids.add(tool_id)
        return [{
            "sessionUpdate": "tool_call",
            "tool": "Bash",
            "title": ev.get("command", ""),
            "toolCallId": tool_id,
        }]

    if kind == "command_completed":
        tool_id = ev.get("tool_id", "")
        cmd = ev.get("command", "")
        exit_code = ev.get("exit_code", 0)
        marker = "✅" if exit_code == 0 else "❌"
        text = f"{cmd} → {marker} exit:{exit_code}"
        updates: list[dict[str, Any]] = []
        if tool_id not in state.seen_tool_ids:
            # We never saw `started`; emit both for sensible UI
            updates.append({
                "sessionUpdate": "tool_call",
                "tool": "Bash",
                "title": cmd,
                "toolCallId": tool_id,
            })
            state.seen_tool_ids.add(tool_id)
        updates.append({
            "sessionUpdate": "tool_call_update",
            "toolCallId": tool_id,
            "content": {"type": "text", "text": text},
        })
        return updates

    if kind == "file_change_started":
        tool_id = ev.get("tool_id", "")
        state.seen_tool_ids.add(tool_id)
        return [{
            "sessionUpdate": "tool_call",
            "tool": "Edit",
            "title": _format_file_change_title(ev.get("paths") or []),
            "toolCallId": tool_id,
        }]

    if kind == "file_change_completed":
        tool_id = ev.get("tool_id", "")
        updates = []
        if tool_id not in state.seen_tool_ids:
            updates.append({
                "sessionUpdate": "tool_call",
                "tool": "Edit",
                "title": _format_file_change_title(ev.get("paths") or []),
                "toolCallId": tool_id,
            })
            state.seen_tool_ids.add(tool_id)
        updates.append({
            "sessionUpdate": "tool_call_update",
            "toolCallId": tool_id,
            "content": {"type": "text", "text": "✅ 已应用"},
        })
        return updates

    if kind == "turn_completed":
        # Flush the held agent_message as the real reply
        updates: list[dict[str, Any]] = []
        if state.held_message is not None:
            updates.append({
                "sessionUpdate": "agent_message_chunk",
                "content": {"type": "text", "text": state.held_message},
            })
            state.accumulated = state.held_message
            state.held_message = None
        return updates

    # ignore and unknown: no UI updates
    return []


def _format_file_change_title(paths: list[str]) -> str:
    """`修改 N 个文件: a.kt, b.py` (truncate to 3 names + ... when N > 3)."""
    n = len(paths)
    basenames = [os.path.basename(p) for p in paths]
    if n <= _FILE_CHANGE_TITLE_LIMIT:
        listing = ", ".join(basenames)
    else:
        listing = ", ".join(basenames[:_FILE_CHANGE_TITLE_LIMIT]) + ", ..."
    return f"修改 {n} 个文件: {listing}"
