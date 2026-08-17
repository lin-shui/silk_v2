from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from typing import Any


_PKG = Path(__file__).resolve().parents[1]
if str(_PKG) not in sys.path:
    sys.path.insert(0, str(_PKG))

from acp_adapter import AcpAgentServer, AcpSession, is_missing_resume_session_error


def test_detects_missing_resume_session_with_leading_warning():
    message = (
        "warning: an API key takes precedence over login\n"
        "No conversation found with session ID: 370d7279-169e-439a-80a3-b343cae72a53"
    )
    assert is_missing_resume_session_error(message)


def test_does_not_retry_authentication_or_unrelated_errors():
    assert not is_missing_resume_session_error("Failed to authenticate. API Error: 403")
    assert not is_missing_resume_session_error("context deadline exceeded")
    assert not is_missing_resume_session_error(None)


def test_missing_resume_is_retried_once_with_fresh_session(tmp_path: Path):
    calls: list[tuple[str, bool]] = []

    class FakeExecutor:
        async def execute_prompt(
            self,
            send,
            request_id: str,
            prompt: str,
            session_id: str,
            working_dir: str,
            resume: bool = False,
            on_session_upsert=None,
            on_permission_request=None,
            execution_policy=None,
        ) -> None:
            calls.append((session_id, resume))
            if resume:
                await send({
                    "type": "error",
                    "error": (
                        "warning: API key takes precedence\n"
                        f"No conversation found with session ID: {session_id}"
                    ),
                })
                return
            if on_session_upsert is not None:
                on_session_upsert(session_id, working_dir, prompt)
            await send({
                "type": "complete",
                "text": "RECOVERED",
                "meta": {"sessionId": session_id, "numTurns": 1},
            })

    server = AcpAgentServer(str(tmp_path), host_ipc=object())  # type: ignore[arg-type]
    server.executor = FakeExecutor()  # type: ignore[assignment]
    acp_session_id = "acp-session"
    stale_session_id = "370d7279-169e-439a-80a3-b343cae72a53"
    server.sessions[acp_session_id] = AcpSession(
        cwd=str(tmp_path),
        cli_session_id=stale_session_id,
    )
    responses: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []

    async def send_response(msg_id: Any, result: dict[str, Any]) -> None:
        responses.append(result)

    async def send_error(msg_id: Any, code: int, message: str) -> None:
        errors.append({"code": code, "message": message})

    server._send_response = send_response  # type: ignore[method-assign]
    server._send_error = send_error  # type: ignore[method-assign]

    asyncio.run(server._handle_session_prompt("request-1", {
        "sessionId": acp_session_id,
        "prompt": [{"type": "text", "text": "hello"}],
        "_silk": {
            "protocolVersion": 1,
            "executionPolicy": {
                "readFile": False,
                "writeFile": False,
                "runCommand": False,
            },
        },
    }))

    assert len(calls) == 2
    assert calls[0] == (stale_session_id, True)
    assert calls[1][1] is False
    assert calls[1][0] != stale_session_id
    assert errors == []
    assert responses[0]["stopReason"] == "end_turn"
    assert responses[0]["meta"]["cliSessionId"] == calls[1][0]
