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


# ---- agent_message (hold-and-flush model) ----

def test_agent_message_first_is_held_not_emitted():
    """First agent_message is held, not immediately emitted."""
    state = _new_state()
    updates = dispatch_event({"kind": "agent_message", "text": "hello"}, state)
    assert updates == []  # nothing emitted yet
    assert state.held_message == "hello"


def test_agent_message_second_emits_first_as_thought():
    """When a second agent_message arrives, the previous held one is emitted
    as agent_thought_chunk (transient commentary), and the new one is held."""
    state = _new_state()
    state.held_message = "first commentary"
    updates = dispatch_event({"kind": "agent_message", "text": "second commentary"}, state)
    assert len(updates) == 1
    assert updates[0] == {
        "sessionUpdate": "agent_thought_chunk",
        "content": {"type": "text", "text": "first commentary"},
    }
    assert state.held_message == "second commentary"


def test_agent_message_three_messages_only_last_survives_as_held():
    """With 3 agent_messages, the first 2 become thoughts, last is held."""
    state = _new_state()
    u1 = dispatch_event({"kind": "agent_message", "text": "msg1"}, state)
    u2 = dispatch_event({"kind": "agent_message", "text": "msg2"}, state)
    u3 = dispatch_event({"kind": "agent_message", "text": "msg3"}, state)
    assert u1 == []  # first held
    assert len(u2) == 1 and u2[0]["sessionUpdate"] == "agent_thought_chunk"  # msg1 flushed as thought
    assert len(u3) == 1 and u3[0]["content"]["text"] == "msg2"  # msg2 flushed as thought
    assert state.held_message == "msg3"


# ---- turn_completed flushes held message ----

def test_turn_completed_flushes_held_as_agent_message_chunk():
    state = _new_state()
    state.held_message = "final answer"
    updates = dispatch_event(
        {"kind": "turn_completed", "input_tokens": 1, "output_tokens": 1, "reasoning_tokens": 0},
        state,
    )
    assert updates == [{
        "sessionUpdate": "agent_message_chunk",
        "content": {"type": "text", "text": "final answer"},
    }]
    assert state.held_message is None
    assert state.accumulated == "final answer"


def test_turn_completed_without_held_emits_nothing():
    state = _new_state()
    updates = dispatch_event(
        {"kind": "turn_completed", "input_tokens": 1, "output_tokens": 1, "reasoning_tokens": 0},
        state,
    )
    assert updates == []


# ---- reasoning ----

def test_reasoning_emits_agent_thought_chunk():
    state = _new_state()
    updates = dispatch_event({"kind": "reasoning", "text": "**Thinking**\n..."}, state)
    assert updates == [{
        "sessionUpdate": "agent_thought_chunk",
        "content": {"type": "text", "text": "**Thinking**\n..."},
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
        "content": {"type": "text", "text": "/bin/bash -lc 'ls' → ✅ exit:0"},
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
        "content": {"type": "text", "text": "false → ❌ exit:1"},
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
    assert "✅ exit:0" in updates[1]["content"]["text"]


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
        "title": "修改 2 个文件: a.txt, b.txt",
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
    assert "10 个文件" in title
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
        "content": {"type": "text", "text": "✅ 已应用"},
    }]


# ---- turn_completed ----

# ---- ignore ----

def test_ignore_emits_no_updates():
    state = _new_state()
    updates = dispatch_event({"kind": "ignore"}, state)
    assert updates == []


# ---- end-to-end fixture ----

def test_full_tool_use_session_produces_expected_updates():
    """Dispatch the whole tool_use fixture; intermediate agent_messages become
    thought_chunks, only the last one (flushed by turn_completed) becomes
    agent_message_chunk."""
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
    # Exactly 1 agent_message_chunk (the final answer, flushed by turn_completed)
    assert kinds.count("agent_message_chunk") == 1
    # Intermediate commentaries become agent_thought_chunk
    assert kinds.count("agent_thought_chunk") >= 1
    # The final chunk should be the last update
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
    assert kinds.count("agent_message_chunk") == 1
