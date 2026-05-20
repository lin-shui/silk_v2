"""Dispatch parsed Codex events to ACP `session/update` payloads.

This module is pure (no I/O, no asyncio) — given a parsed event from
`codex_executor.parse_jsonl_event` and a `DispatcherState`, it returns the
list of `update` dicts the adapter should emit as `session/update`
notifications. The adapter is responsible for the actual ACP send.

agent_message buffering strategy (aligned with cc-connect):
  - Incoming agent_messages are appended to ``pending_messages``.
  - When a tool-call event arrives, pending messages are flushed as
    ``agent_thought_chunk`` (transient status in the UI).
  - On ``turn_completed`` (or ``turn_failed``), pending messages are flushed
    as ``agent_message_chunk`` (the real reply visible to the user).
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger("codex_bridge.dispatcher")

# Show the first N filenames in file_change titles, then "..."
_FILE_CHANGE_TITLE_LIMIT = 3


@dataclass
class DispatcherState:
    """Per-session mutable state held across event dispatches."""

    accumulated: str = ""
    seen_tool_ids: set[str] = field(default_factory=set)
    thread_id: str | None = None
    pending_messages: list[str] = field(default_factory=list)


def dispatch_event(ev: dict[str, Any], state: DispatcherState) -> list[dict[str, Any]]:
    """Convert a parsed event into zero or more ACP `update` dicts.

    Mutates `state` (accumulated text, seen_tool_ids, thread_id, pending_messages).
    """
    kind = ev.get("kind")

    if kind == "thread_started":
        state.thread_id = ev.get("thread_id")
        return []

    # ── agent_message: buffer, don't emit immediately ─────────────
    if kind == "agent_message":
        text = ev.get("text", "")
        if text:
            state.pending_messages.append(text)
        return []

    # ── reasoning: emit as thought (does NOT trigger pending flush) ──
    if kind == "reasoning":
        return [{
            "sessionUpdate": "agent_thought_chunk",
            "content": {"type": "text", "text": ev.get("text", "")},
        }]

    # ── status_update (heartbeat): emit as thought ────────────────
    if kind == "status_update":
        return [{
            "sessionUpdate": "agent_thought_chunk",
            "content": {"type": "text", "text": ev.get("text", "")},
        }]

    # ── command_started: flush pending as thinking, then tool_call ──
    if kind == "command_started":
        updates = _flush_pending_as_thinking(state)
        tool_id = ev.get("tool_id", "")
        state.seen_tool_ids.add(tool_id)
        updates.append({
            "sessionUpdate": "tool_call",
            "tool": "Bash",
            "title": ev.get("command", ""),
            "toolCallId": tool_id,
        })
        return updates

    # ── command_completed ──────────────────────────────────────────
    if kind == "command_completed":
        tool_id = ev.get("tool_id", "")
        cmd = ev.get("command", "")
        exit_code = ev.get("exit_code", 0)
        marker = "\u2705" if exit_code == 0 else "\u274c"
        text = f"{cmd} \u2192 {marker} exit:{exit_code}"
        updates: list[dict[str, Any]] = []
        if tool_id not in state.seen_tool_ids:
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

    # ── file_change_started ────────────────────────────────────────
    if kind == "file_change_started":
        updates = _flush_pending_as_thinking(state)
        tool_id = ev.get("tool_id", "")
        state.seen_tool_ids.add(tool_id)
        updates.append({
            "sessionUpdate": "tool_call",
            "tool": "Edit",
            "title": _format_file_change_title(ev.get("paths") or []),
            "toolCallId": tool_id,
        })
        return updates

    # ── file_change_completed ──────────────────────────────────────
    if kind == "file_change_completed":
        tool_id = ev.get("tool_id", "")
        updates: list[dict[str, Any]] = []
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
            "content": {"type": "text", "text": "\u2705 \u5df2\u5e94\u7528"},
        })
        return updates

    # ── function_call_started ──────────────────────────────────────
    if kind == "function_call_started":
        updates = _flush_pending_as_thinking(state)
        tool_id = ev.get("tool_id", "")
        name = ev.get("name", "function")
        state.seen_tool_ids.add(tool_id)
        updates.append({
            "sessionUpdate": "tool_call",
            "tool": name,
            "title": ev.get("arguments", ""),
            "toolCallId": tool_id,
        })
        return updates

    # ── function_call_completed ────────────────────────────────────
    if kind == "function_call_completed":
        tool_id = ev.get("tool_id", "")
        name = ev.get("name", "function")
        status = ev.get("status", "")
        output = ev.get("output", "")
        success = status.lower() in ("completed", "success", "succeeded", "ok")
        marker = "\u2705" if success else "\u274c"
        text = f"{name} \u2192 {marker} {status}"
        if output:
            text += f"\n{output[:500]}"
        updates: list[dict[str, Any]] = []
        if tool_id not in state.seen_tool_ids:
            updates.append({
                "sessionUpdate": "tool_call",
                "tool": name,
                "title": "",
                "toolCallId": tool_id,
            })
            state.seen_tool_ids.add(tool_id)
        updates.append({
            "sessionUpdate": "tool_call_update",
            "toolCallId": tool_id,
            "content": {"type": "text", "text": text},
        })
        return updates

    # ── tool_started (web_search, file_search, etc.) ───────────────
    if kind == "tool_started":
        updates = _flush_pending_as_thinking(state)
        tool_id = ev.get("tool_id", "")
        tool_name = ev.get("tool_name", "Tool")
        state.seen_tool_ids.add(tool_id)
        updates.append({
            "sessionUpdate": "tool_call",
            "tool": tool_name,
            "title": ev.get("input", ""),
            "toolCallId": tool_id,
        })
        return updates

    # ── tool_completed ─────────────────────────────────────────────
    if kind == "tool_completed":
        tool_id = ev.get("tool_id", "")
        tool_name = ev.get("tool_name", "Tool")
        output = ev.get("output", "")
        updates: list[dict[str, Any]] = []
        if tool_id not in state.seen_tool_ids:
            updates.append({
                "sessionUpdate": "tool_call",
                "tool": tool_name,
                "title": output,
                "toolCallId": tool_id,
            })
            state.seen_tool_ids.add(tool_id)
        updates.append({
            "sessionUpdate": "tool_call_update",
            "toolCallId": tool_id,
            "content": {"type": "text", "text": f"{tool_name} \u2192 \u2705"},
        })
        return updates

    # ── turn_completed: flush pending as the real reply ─────────────
    if kind == "turn_completed":
        return _flush_pending_as_message(state)

    # ── turn_failed: flush pending as reply + emit error thought ────
    if kind == "turn_failed":
        updates = _flush_pending_as_message(state)
        error_text = ev.get("error", "turn failed")
        updates.append({
            "sessionUpdate": "agent_thought_chunk",
            "content": {"type": "text", "text": f"\u274c {error_text}"},
        })
        return updates

    # ── error: non-transient errors shown as thought ────────────────
    if kind == "error":
        if ev.get("transient"):
            return []
        msg = ev.get("message", "unknown error")
        logger.warning("codex error event: %s", msg)
        return [{
            "sessionUpdate": "agent_thought_chunk",
            "content": {"type": "text", "text": f"\u26a0\ufe0f {msg}"},
        }]

    # ignore and unknown: no UI updates
    return []


# ── internal helpers ──────────────────────────────────────────────────


def _flush_pending_as_thinking(state: DispatcherState) -> list[dict[str, Any]]:
    """Emit all buffered agent_messages as agent_thought_chunk (transient)."""
    updates: list[dict[str, Any]] = []
    for text in state.pending_messages:
        updates.append({
            "sessionUpdate": "agent_thought_chunk",
            "content": {"type": "text", "text": text},
        })
    state.pending_messages.clear()
    return updates


def _flush_pending_as_message(state: DispatcherState) -> list[dict[str, Any]]:
    """Emit all buffered agent_messages as agent_message_chunk (final reply)."""
    updates: list[dict[str, Any]] = []
    for text in state.pending_messages:
        updates.append({
            "sessionUpdate": "agent_message_chunk",
            "content": {"type": "text", "text": text},
        })
        state.accumulated += text
    state.pending_messages.clear()
    return updates


def _format_file_change_title(paths: list[str]) -> str:
    """`修改 N 个文件: a.kt, b.py` (truncate to 3 names + ... when N > 3)."""
    n = len(paths)
    basenames = [os.path.basename(p) for p in paths]
    if n <= _FILE_CHANGE_TITLE_LIMIT:
        listing = ", ".join(basenames)
    else:
        listing = ", ".join(basenames[:_FILE_CHANGE_TITLE_LIMIT]) + ", ..."
    return f"\u4fee\u6539 {n} \u4e2a\u6587\u4ef6: {listing}"
