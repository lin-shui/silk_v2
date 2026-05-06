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


def test_item_completed_unknown_type_is_ignored_in_m1():
    raw = {
        "type": "item.completed",
        "item": {"id": "item_1", "type": "reasoning", "text": "thinking..."},
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
