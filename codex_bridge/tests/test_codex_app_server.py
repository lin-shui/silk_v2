from __future__ import annotations

import sys
import asyncio
from pathlib import Path

from bridge_common.execution_policy import (
    APPROVAL_REQUIRED,
    AUTOMATIC,
    AUTONOMOUS,
    READ_ONLY,
    ExecutionPolicy,
)

_PKG = Path(__file__).resolve().parents[1]
if str(_PKG) not in sys.path:
    sys.path.insert(0, str(_PKG))

from codex_adapter import (  # noqa: E402
    AcpAgentServer,
    _codex_permission_tool,
    _codex_policy_denial,
)
from codex_app_server import (  # noqa: E402
    approval_response,
    build_app_server_command,
    build_thread_request,
    parse_app_server_notification,
)


def test_read_only_thread_cannot_expand_through_native_approval(tmp_path):
    method, params = build_thread_request(
        cwd=str(tmp_path),
        resume_thread_id=None,
        execution_policy=ExecutionPolicy(access_mode=READ_ONLY, read_file=True),
    )
    assert method == "thread/start"
    assert params["sandbox"] == "read-only"
    assert params["approvalPolicy"] == "never"
    assert params["runtimeWorkspaceRoots"] == [str(tmp_path)]


def test_approval_mode_routes_reviews_to_user(tmp_path):
    method, params = build_thread_request(
        cwd=str(tmp_path),
        resume_thread_id="thread-1",
        execution_policy=ExecutionPolicy(
            access_mode=APPROVAL_REQUIRED,
            read_file=True,
            write_file=True,
            run_command=True,
        ),
    )
    assert method == "thread/resume"
    assert params["threadId"] == "thread-1"
    assert params["approvalPolicy"] == "on-request"
    assert params["approvalsReviewer"] == "user"
    assert params["sandbox"] == "read-only"


def test_autonomous_keeps_stricter_native_approval_policy(tmp_path):
    _, params = build_thread_request(
        cwd=str(tmp_path),
        resume_thread_id=None,
        execution_policy=ExecutionPolicy(
            access_mode=AUTONOMOUS,
            read_file=True,
            write_file=True,
            run_command=True,
        ),
    )
    assert "approvalPolicy" not in params
    assert params["sandbox"] == "workspace-write"


def test_automatic_agent_mode_disables_approval_without_disabling_sandbox(tmp_path):
    _, params = build_thread_request(
        cwd=str(tmp_path),
        resume_thread_id=None,
        execution_policy=ExecutionPolicy(
            access_mode=AUTONOMOUS,
            agent_permission_mode=AUTOMATIC,
            read_file=True,
            write_file=True,
            run_command=True,
        ),
    )
    assert params["approvalPolicy"] == "never"
    assert params["sandbox"] == "workspace-write"


def test_app_server_command_disables_shell_without_binding_grant():
    cmd = build_app_server_command(ExecutionPolicy(read_file=True))
    assert cmd[:3] == ["codex", "app-server", "--stdio"]
    assert "shell_tool" in cmd
    assert "unified_exec" in cmd


def test_app_server_command_keeps_shell_with_full_codex_grant():
    cmd = build_app_server_command(
        ExecutionPolicy(read_file=True, write_file=True, run_command=True)
    )
    assert "shell_tool" not in cmd
    assert "unified_exec" not in cmd


def test_command_item_notification_uses_existing_dispatcher_shape():
    event = parse_app_server_notification(
        "item/completed",
        {
            "item": {
                "type": "commandExecution",
                "id": "exec-1",
                "command": "pwd",
                "aggregatedOutput": "/work\n",
                "exitCode": 0,
            }
        },
    )
    assert event == {
        "kind": "command_completed",
        "tool_id": "exec-1",
        "command": "pwd",
        "exit_code": 0,
        "output": "/work\n",
    }


def test_declined_command_is_not_reported_as_success():
    event = parse_app_server_notification(
        "item/completed",
        {
            "item": {
                "type": "commandExecution",
                "id": "exec-2",
                "command": "touch denied",
                "status": "declined",
                "exitCode": None,
            }
        },
    )
    assert event["kind"] == "command_completed"
    assert event["exit_code"] == 1


def test_turn_completion_maps_latest_usage():
    event = parse_app_server_notification(
        "turn/completed",
        {"turn": {"status": "completed"}},
        {"input_tokens": 10, "output_tokens": 4, "reasoning_tokens": 2},
    )
    assert event == {
        "kind": "turn_completed",
        "input_tokens": 10,
        "output_tokens": 4,
        "reasoning_tokens": 2,
    }


def test_native_approval_responses_are_method_specific():
    assert approval_response(
        "item/commandExecution/requestApproval", True, {}
    ) == {"decision": "accept"}
    assert approval_response(
        "item/fileChange/requestApproval", False, {}
    ) == {"decision": "decline"}
    assert approval_response(
        "item/permissions/requestApproval", True, {"permissions": {"network": {"enabled": True}}}
    ) == {"permissions": {"network": {"enabled": True}}, "scope": "turn"}


def test_file_approval_card_contains_changed_paths(tmp_path):
    item = {
        "changes": [
            {"path": str(tmp_path / "a.txt"), "kind": "add"},
            {"path": str(tmp_path / "b.txt"), "kind": "modify"},
        ]
    }
    tool_name, tool_input = _codex_permission_tool(
        "item/fileChange/requestApproval", {}, item
    )
    assert tool_name == "Edit"
    assert "a.txt" in tool_input["file_path"]
    assert "b.txt" in tool_input["file_path"]


def test_file_approval_is_denied_outside_workspace(tmp_path):
    policy = ExecutionPolicy(
        access_mode=APPROVAL_REQUIRED,
        read_file=True,
        write_file=True,
        run_command=True,
    )
    reason = _codex_policy_denial(
        "item/fileChange/requestApproval",
        {},
        {"changes": [{"path": str(tmp_path.parent / "outside.txt")}]},
        policy,
        str(tmp_path),
    )
    assert reason is not None
    assert "bound workspace" in reason


def test_command_approval_requires_command_grant(tmp_path):
    reason = _codex_policy_denial(
        "item/commandExecution/requestApproval",
        {"cwd": str(tmp_path)},
        None,
        ExecutionPolicy(access_mode=READ_ONLY, read_file=True),
        str(tmp_path),
    )
    assert reason == "Silk binding does not grant command execution."


def test_codex_question_request_round_trips_through_silk_extension():
    async def run():
        server = AcpAgentServer.__new__(AcpAgentServer)
        server._pending_questions = {}
        sent = []

        async def send_notification(method, params):
            sent.append((method, params))

        async def send_response(msg_id, result):
            sent.append(("response", msg_id, result))

        async def send_error(msg_id, code, message):
            raise AssertionError((msg_id, code, message))

        server._send_notification = send_notification
        server._send_response = send_response
        server._send_error = send_error
        task = asyncio.create_task(server._on_question_request("sid", {
            "questions": [{
                "id": "language",
                "header": "Language",
                "question": "Which language?",
                "options": [{"label": "Python", "description": ""}],
            }],
        }))
        while not sent:
            await asyncio.sleep(0)
        request_id = sent[0][1]["update"]["requestId"]
        await server._handle_silk_resolve_question(9, {
            "requestId": request_id,
            "answers": {"Which language?": "Python"},
        })
        return await task, sent

    result, sent = asyncio.run(run())
    assert result == {"answers": {"language": {"answers": ["Python"]}}}
    assert sent[-1] == ("response", 9, {"ok": True})
