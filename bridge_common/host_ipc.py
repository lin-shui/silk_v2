"""The line-delimited JSON-RPC contract used by silk-agent managed adapters.

The adapter's stdout is reserved for this protocol. Diagnostics must use
logging/stderr. A Host initializes the child first, then carries ACP messages
between that bound child and its device-level backend WebSocket.
"""

from __future__ import annotations

import asyncio
import json
import sys
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

from bridge_common.unicode_safety import sanitize_json_value


PROTOCOL_VERSION = "2"


class HostIPCError(RuntimeError):
    """Raised when the Host IPC peer violates the local contract."""


@dataclass(frozen=True)
class HostInitialization:
    protocol_version: str
    nonce: str
    agent_instance_id: str
    agent_type: str
    host_version: str
    agent_capabilities: tuple[str, ...]


class HostIPC:
    """Async JSON-RPC client for a Host-owned adapter child process."""

    def __init__(
        self,
        *,
        read_line: Callable[[], Awaitable[str]] | None = None,
        write_line: Callable[[str], Awaitable[None]] | None = None,
    ) -> None:
        self._read_line = read_line or self._read_stdin_line
        self._write_line = write_line or self._write_stdout_line
        self._write_lock = asyncio.Lock()
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._next_id = 0
        self._reader_task: asyncio.Task[None] | None = None
        self._shutdown = asyncio.Event()
        self._acp_messages: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=64)
        self.initialization: HostInitialization | None = None

    async def start(self, expected_agent_type: str) -> HostInitialization:
        raw = await asyncio.wait_for(self._read_line(), timeout=5)
        request = self._decode(raw)
        if request.get("jsonrpc") != "2.0" or request.get("id") is None or request.get("method") != "host/initialize":
            raise HostIPCError("Host did not send host/initialize")
        params = request.get("params") or {}
        initialization = HostInitialization(
            protocol_version=str(params.get("protocolVersion", "")),
            nonce=str(params.get("nonce", "")),
            agent_instance_id=str(params.get("agentInstanceId", "")),
            agent_type=str(params.get("agentType", "")),
            host_version=str(params.get("hostVersion", "")),
            agent_capabilities=tuple(str(value) for value in params.get("agentCapabilities", [])),
        )
        if (
            initialization.protocol_version != PROTOCOL_VERSION
            or not initialization.nonce
            or not initialization.agent_instance_id
            or initialization.agent_type != expected_agent_type
        ):
            raise HostIPCError("Host initialization is invalid")
        self.initialization = initialization
        await self._send({
            "jsonrpc": "2.0",
            "id": request["id"],
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "nonce": initialization.nonce,
                "agentInstanceId": initialization.agent_instance_id,
                "adapterVersion": "python-bridge",
            },
        })
        self._reader_task = asyncio.create_task(self._reader_loop())
        return initialization

    async def receive_acp(self) -> dict[str, Any]:
        if self.initialization is None:
            raise HostIPCError("Host IPC is not initialized")
        return await self._acp_messages.get()

    async def forward_acp(self, message: dict[str, Any]) -> None:
        if not isinstance(message, dict):
            raise HostIPCError("ACP message must be a JSON object")
        await self._call("host/forwardAcp", {"message": message})

    async def wait_shutdown(self) -> None:
        await self._shutdown.wait()

    async def close(self) -> None:
        self._shutdown.set()
        self._fail_pending("Host IPC closed")
        if self._reader_task is not None:
            self._reader_task.cancel()
            try:
                await self._reader_task
            except asyncio.CancelledError:
                pass

    async def _reader_loop(self) -> None:
        try:
            while not self._shutdown.is_set():
                raw = await self._read_line()
                if not raw:
                    self._shutdown.set()
                    self._fail_pending("Host IPC closed")
                    return
                message = self._decode(raw)
                if message.get("id") is not None and "method" not in message:
                    request_id = str(message["id"])
                    future = self._pending.pop(request_id, None)
                    if future is not None and not future.done():
                        if "error" in message:
                            future.set_exception(HostIPCError(str(message["error"].get("message", "Host request failed"))))
                        else:
                            future.set_result(message.get("result") or {})
                    continue
                method = message.get("method")
                if method == "adapter/health":
                    await self._send({"jsonrpc": "2.0", "id": message.get("id"), "result": {"status": "ok"}})
                elif method == "adapter/acp":
                    acp_message = (message.get("params") or {}).get("message")
                    if not isinstance(acp_message, dict):
                        await self._send({
                            "jsonrpc": "2.0",
                            "id": message.get("id"),
                            "error": {"code": -32602, "message": "ACP message must be a JSON object"},
                        })
                        continue
                    try:
                        self._acp_messages.put_nowait(acp_message)
                    except asyncio.QueueFull:
                        await self._send({
                            "jsonrpc": "2.0",
                            "id": message.get("id"),
                            "error": {"code": -32001, "message": "Adapter ACP queue is full"},
                        })
                        continue
                    await self._send({"jsonrpc": "2.0", "id": message.get("id"), "result": {}})
                elif method == "adapter/shutdown":
                    await self._send({"jsonrpc": "2.0", "id": message.get("id"), "result": {}})
                    self._shutdown.set()
                    return
                else:
                    await self._send({
                        "jsonrpc": "2.0",
                        "id": message.get("id"),
                        "error": {"code": -32601, "message": "Adapter method is not supported"},
                    })
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # stdin EOF or malformed Host output
            self._shutdown.set()
            self._fail_pending(f"Host IPC read failed: {exc}")

    def _fail_pending(self, message: str) -> None:
        for future in self._pending.values():
            if not future.done():
                future.set_exception(HostIPCError(message))
        self._pending.clear()

    async def _call(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        self._next_id += 1
        request_id = f"adapter-{self._next_id}"
        loop = asyncio.get_running_loop()
        future: asyncio.Future[dict[str, Any]] = loop.create_future()
        self._pending[request_id] = future
        await self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        try:
            return await asyncio.wait_for(future, timeout=5)
        finally:
            self._pending.pop(request_id, None)

    async def _send(self, message: dict[str, Any]) -> None:
        async with self._write_lock:
            await self._write_line(json.dumps(sanitize_json_value(message), separators=(",", ":")) + "\n")

    @staticmethod
    def _decode(raw: str) -> dict[str, Any]:
        try:
            message = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise HostIPCError("Host sent invalid JSON") from exc
        if not isinstance(message, dict):
            raise HostIPCError("Host sent a non-object JSON-RPC message")
        return sanitize_json_value(message)

    @staticmethod
    async def _read_stdin_line() -> str:
        return await asyncio.to_thread(sys.stdin.readline)

    @staticmethod
    async def _write_stdout_line(line: str) -> None:
        sys.stdout.write(line)
        sys.stdout.flush()
