"""Unit tests for codex_dispatcher.dispatch_event."""
from __future__ import annotations

import json
from pathlib import Path

from codex_bridge.codex_dispatcher import DispatcherState, dispatch_event
from codex_bridge.codex_executor import parse_jsonl_event

FIXTURE_DIR = Path(__file__).parent / "fixtures"


def _new_state(accumulated: str = "") -> DispatcherState:
    return DispatcherState(accumulated=accumulated, seen_tool_ids=set(), thread_id=None)


# ---- thread_started ----

def test_thread_started_sets_state_no_updates():
    state = _new_state()
    updates = dispatch_event({"kind": "thread_started", "thread_id": "tid-1"}, state)
    assert updates == []
    assert state.thread_id == "tid-1"


# ---- agent_message (pending-messages buffering, cc-connect style) ----

def test_agent_message_is_buffered_not_emitted():
    """agent_message is buffered into pending_messages, nothing emitted."""
    state = _new_state()
    updates = dispatch_event({"kind": "agent_message", "text": "hello"}, state)
    assert updates == []
    assert state.pending_messages == ["hello"]


def test_multiple_agent_messages_all_buffered():
    """Multiple agent_messages all accumulate in pending_messages."""
    state = _new_state()
    u1 = dispatch_event({"kind": "agent_message", "text": "msg1"}, state)
    u2 = dispatch_event({"kind": "agent_message", "text": "msg2"}, state)
    u3 = dispatch_event({"kind": "agent_message", "text": "msg3"}, state)
    assert u1 == []
    assert u2 == []
    assert u3 == []
    assert state.pending_messages == ["msg1", "msg2", "msg3"]


def test_tool_call_flushes_pending_as_thinking():
    """When a tool-call event arrives, pending messages flush as thought_chunks."""
    state = _new_state()
    dispatch_event({"kind": "agent_message", "text": "commentary1"}, state)
    dispatch_event({"kind": "agent_message", "text": "commentary2"}, state)
    updates = dispatch_event(
        {"kind": "command_started", "tool_id": "item_1", "command": "ls"},
        state,
    )
    # First 2 are thought_chunks from flush, 3rd is the tool_call
    assert len(updates) == 3
    assert updates[0] == {
        "sessionUpdate": "agent_thought_chunk",
        "content": {"type": "text", "text": "commentary1"},
    }
    assert updates[1] == {
        "sessionUpdate": "agent_thought_chunk",
        "content": {"type": "text", "text": "commentary2"},
    }
    assert updates[2]["sessionUpdate"] == "tool_call"
    assert state.pending_messages == []


# ---- turn_completed flushes pending as real reply ----

def test_turn_completed_flushes_pending_as_agent_message_chunk():
    state = _new_state()
    dispatch_event({"kind": "agent_message", "text": "final answer"}, state)
    updates = dispatch_event(
        {"kind": "turn_completed", "input_tokens": 1, "output_tokens": 1, "reasoning_tokens": 0},
        state,
    )
    assert updates == [{
        "sessionUpdate": "agent_message_chunk",
        "content": {"type": "text", "text": "final answer"},
    }]
    assert state.pending_messages == []
    assert state.accumulated == "final answer"


def test_turn_completed_flushes_multiple_pending_as_chunks():
    """Multiple pending messages all become agent_message_chunk on turn_completed."""
    state = _new_state()
    dispatch_event({"kind": "agent_message", "text": "part1"}, state)
    dispatch_event({"kind": "agent_message", "text": "part2"}, state)
    updates = dispatch_event(
        {"kind": "turn_completed", "input_tokens": 1, "output_tokens": 1, "reasoning_tokens": 0},
        state,
    )
    assert len(updates) == 2
    assert updates[0]["sessionUpdate"] == "agent_message_chunk"
    assert updates[0]["content"]["text"] == "part1"
    assert updates[1]["sessionUpdate"] == "agent_message_chunk"
    assert updates[1]["content"]["text"] == "part2"
    assert state.accumulated == "part1part2"


def test_turn_completed_without_pending_emits_nothing():
    state = _new_state()
    updates = dispatch_event(
        {"kind": "turn_completed", "input_tokens": 1, "output_tokens": 1, "reasoning_tokens": 0},
        state,
    )
    assert updates == []


# ---- turn_failed ----

def test_turn_failed_flushes_pending_and_emits_error():
    """turn_failed should flush pending messages as agent_message_chunk,
    then emit an error thought_chunk."""
    state = _new_state()
    dispatch_event({"kind": "agent_message", "text": "partial"}, state)
    updates = dispatch_event({"kind": "turn_failed", "error": "rate limit"}, state)
    assert len(updates) == 2
    # First: pending flushed as message_chunk
    assert updates[0]["sessionUpdate"] == "agent_message_chunk"
    assert updates[0]["content"]["text"] == "partial"
    # Second: error as thought_chunk
    assert updates[1]["sessionUpdate"] == "agent_thought_chunk"
    assert "rate limit" in updates[1]["content"]["text"]
    assert state.pending_messages == []


def test_turn_failed_without_pending_emits_only_error():
    state = _new_state()
    updates = dispatch_event({"kind": "turn_failed", "error": "bad request"}, state)
    assert len(updates) == 1
    assert updates[0]["sessionUpdate"] == "agent_thought_chunk"
    assert "bad request" in updates[0]["content"]["text"]


# ---- error event ----

def test_error_transient_is_ignored():
    state = _new_state()
    updates = dispatch_event(
        {"kind": "error", "message": "Reconnecting...", "transient": True},
        state,
    )
    assert updates == []


def test_error_non_transient_emits_thought():
    state = _new_state()
    updates = dispatch_event(
        {"kind": "error", "message": "API error", "transient": False},
        state,
    )
    assert len(updates) == 1
    assert updates[0]["sessionUpdate"] == "agent_thought_chunk"
    assert "API error" in updates[0]["content"]["text"]


# ---- reasoning ----

def test_reasoning_emits_agent_thought_chunk():
    state = _new_state()
    updates = dispatch_event({"kind": "reasoning", "text": "**Thinking**\n..."}, state)
    assert updates == [{
        "sessionUpdate": "agent_thought_chunk",
        "content": {"type": "text", "text": "**Thinking**\n..."},
    }]


# ---- status_update (idle heartbeat) ----

def test_status_update_emits_agent_thought_chunk():
    state = _new_state()
    updates = dispatch_event(
        {"kind": "status_update", "text": "\U0001f4ad \u601d\u8003\u4e2d... (\u5df2\u7b49\u5f85 3s)"},
        state,
    )
    assert updates == [{
        "sessionUpdate": "agent_thought_chunk",
        "content": {"type": "text", "text": "\U0001f4ad \u601d\u8003\u4e2d... (\u5df2\u7b49\u5f85 3s)"},
    }]


# ---- command_execution ----

def test_command_started_emits_tool_call_and_remembers_id():
    state = _new_state()
    updates = dispatch_event(
        {"kind": "command_started", "tool_id": "item_1", "command": "/bin/bash -lc 'ls'"},
        state,
    )
    assert updates == [{
        "sessionUpdate": "tool_call",
        "tool": "Bash",
        "title": "/bin/bash -lc 'ls'",
        "toolCallId": "item_1",
    }]
    assert "item_1" in state.seen_tool_ids


def test_command_completed_after_started_emits_tool_call_update():
    state = _new_state()
    state.seen_tool_ids.add("item_1")  # simulate started already seen
    updates = dispatch_event(
        {
            "kind": "command_completed",
            "tool_id": "item_1",
            "command": "/bin/bash -lc 'ls'",
            "exit_code": 0,
            "output": "file1\n",
        },
        state,
    )
    assert updates == [{
        "sessionUpdate": "tool_call_update",
        "toolCallId": "item_1",
        "content": {"type": "text", "text": "/bin/bash -lc 'ls' \u2192 \u2705 exit:0"},
    }]


def test_command_completed_failure_emits_x_mark():
    state = _new_state()
    state.seen_tool_ids.add("item_2")
    updates = dispatch_event(
        {
            "kind": "command_completed",
            "tool_id": "item_2",
            "command": "false",
            "exit_code": 1,
            "output": "",
        },
        state,
    )
    assert updates == [{
        "sessionUpdate": "tool_call_update",
        "toolCallId": "item_2",
        "content": {"type": "text", "text": "false \u2192 \u274c exit:1"},
    }]


def test_command_completed_without_started_emits_both():
    """If we missed started (e.g., adapter started mid-stream), still show
    sensible UI: emit tool_call followed by tool_call_update."""
    state = _new_state()
    updates = dispatch_event(
        {
            "kind": "command_completed",
            "tool_id": "item_99",
            "command": "ls",
            "exit_code": 0,
            "output": "",
        },
        state,
    )
    assert len(updates) == 2
    assert updates[0]["sessionUpdate"] == "tool_call"
    assert updates[0]["toolCallId"] == "item_99"
    assert updates[1]["sessionUpdate"] == "tool_call_update"
    assert "\u2705 exit:0" in updates[1]["content"]["text"]


# ---- file_change ----

def test_file_change_started_emits_tool_call_with_file_list():
    state = _new_state()
    updates = dispatch_event(
        {
            "kind": "file_change_started",
            "tool_id": "item_4",
            "paths": ["/tmp/a.txt", "/tmp/b.txt"],
            "kinds": ["add", "modify"],
        },
        state,
    )
    assert updates == [{
        "sessionUpdate": "tool_call",
        "tool": "Edit",
        "title": "\u4fee\u6539 2 \u4e2a\u6587\u4ef6: a.txt, b.txt",
        "toolCallId": "item_4",
    }]
    assert "item_4" in state.seen_tool_ids


def test_file_change_started_truncates_long_lists():
    state = _new_state()
    paths = [f"/tmp/file{i}.txt" for i in range(10)]
    kinds = ["modify"] * 10
    updates = dispatch_event(
        {"kind": "file_change_started", "tool_id": "x", "paths": paths, "kinds": kinds},
        state,
    )
    title = updates[0]["title"]
    # Show count; truncate filename list to first 3 + "..."
    assert "10 \u4e2a\u6587\u4ef6" in title
    assert title.count("file") == 3  # first 3 names appear
    assert "..." in title


def test_file_change_completed_emits_tool_call_update():
    state = _new_state()
    state.seen_tool_ids.add("item_4")
    updates = dispatch_event(
        {
            "kind": "file_change_completed",
            "tool_id": "item_4",
            "paths": ["/tmp/a.txt"],
            "kinds": ["add"],
        },
        state,
    )
    assert updates == [{
        "sessionUpdate": "tool_call_update",
        "toolCallId": "item_4",
        "content": {"type": "text", "text": "\u2705 \u5df2\u5e94\u7528"},
    }]


# ---- function_call ----

def test_function_call_started_flushes_pending_and_emits_tool_call():
    state = _new_state()
    dispatch_event({"kind": "agent_message", "text": "let me call a function"}, state)
    updates = dispatch_event(
        {"kind": "function_call_started", "tool_id": "fc_1", "name": "my_func", "arguments": '{"x":1}'},
        state,
    )
    assert len(updates) == 2
    assert updates[0]["sessionUpdate"] == "agent_thought_chunk"  # flushed pending
    assert updates[1] == {
        "sessionUpdate": "tool_call",
        "tool": "my_func",
        "title": '{"x":1}',
        "toolCallId": "fc_1",
    }


def test_function_call_completed_emits_tool_call_update():
    state = _new_state()
    state.seen_tool_ids.add("fc_1")
    updates = dispatch_event(
        {
            "kind": "function_call_completed",
            "tool_id": "fc_1",
            "name": "my_func",
            "status": "completed",
            "output": "result data",
        },
        state,
    )
    assert len(updates) == 1
    assert updates[0]["sessionUpdate"] == "tool_call_update"
    assert updates[0]["toolCallId"] == "fc_1"
    assert "\u2705" in updates[0]["content"]["text"]


# ---- tool_started / tool_completed (web_search, etc.) ----

def test_tool_started_flushes_pending_and_emits_tool_call():
    state = _new_state()
    dispatch_event({"kind": "agent_message", "text": "searching..."}, state)
    updates = dispatch_event(
        {"kind": "tool_started", "tool_id": "ws_1", "tool_name": "WebSearch", "input": "python docs"},
        state,
    )
    assert len(updates) == 2
    assert updates[0]["sessionUpdate"] == "agent_thought_chunk"  # flushed pending
    assert updates[1] == {
        "sessionUpdate": "tool_call",
        "tool": "WebSearch",
        "title": "python docs",
        "toolCallId": "ws_1",
    }


def test_tool_completed_emits_tool_call_update():
    state = _new_state()
    state.seen_tool_ids.add("ws_1")
    updates = dispatch_event(
        {"kind": "tool_completed", "tool_id": "ws_1", "tool_name": "WebSearch", "output": "python docs"},
        state,
    )
    assert len(updates) == 1
    assert updates[0]["sessionUpdate"] == "tool_call_update"
    assert "WebSearch" in updates[0]["content"]["text"]


# ---- ignore ----

def test_ignore_emits_no_updates():
    state = _new_state()
    updates = dispatch_event({"kind": "ignore"}, state)
    assert updates == []


# ---- end-to-end fixture ----

def test_full_tool_use_session_produces_expected_updates():
    """Dispatch the whole tool_use fixture; pending agent_messages before tool
    calls become thought_chunks, after last tool call become agent_message_chunk
    via turn_completed."""
    fixture = FIXTURE_DIR / "jsonl_tool_use.jsonl"
    state = _new_state()
    parsed = [parse_jsonl_event(json.loads(ln)) for ln in fixture.read_text().splitlines() if ln.strip()]
    all_updates = []
    for ev in parsed:
        all_updates.extend(dispatch_event(ev, state))

    kinds = [u["sessionUpdate"] for u in all_updates]
    # Expect: tool_call + tool_call_update for Bash and Edit
    assert kinds.count("tool_call") >= 2
    assert kinds.count("tool_call_update") >= 2
    # At least 1 agent_message_chunk (the final answer, flushed by turn_completed)
    assert kinds.count("agent_message_chunk") >= 1
    # The final chunk should be agent_message_chunk
    assert kinds[-1] == "agent_message_chunk"


def test_full_reasoning_session_emits_thought_chunk():
    """Reasoning fixture: reasoning items + agent_message. The reasoning event
    emits thought_chunk immediately; the agent_message is held and flushed
    by turn_completed as agent_message_chunk."""
    fixture = FIXTURE_DIR / "jsonl_with_reasoning.jsonl"
    state = _new_state()
    parsed = [parse_jsonl_event(json.loads(ln)) for ln in fixture.read_text().splitlines() if ln.strip()]
    all_updates = []
    for ev in parsed:
        all_updates.extend(dispatch_event(ev, state))
    kinds = [u["sessionUpdate"] for u in all_updates]
    assert "agent_thought_chunk" in kinds
    assert "agent_message_chunk" in kinds
    assert kinds.count("agent_message_chunk") >= 1
