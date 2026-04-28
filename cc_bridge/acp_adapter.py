#!/usr/bin/env python3
"""ACP Bridge Adapter: connects to Silk backend /agent-bridge via ACP over WebSocket."""

import asyncio
import json
import logging
import os
from typing import Any

import websockets

logger = logging.getLogger("acp_bridge")


class AcpBridgeAdapter:
    """
    Connects to silk backend's /agent-bridge endpoint using ACP protocol.
    Translates ACP JSON-RPC requests into local Claude CLI calls.
    """

    def __init__(self, ws_url: str, token: str):
        self.ws_url = f"{ws_url}?agentType=claude-code&token={token}"
        self.token = token
        self.ws: websockets.WebSocketClientProtocol | None = None
        self.next_id = 1
        self.pending: dict[int, asyncio.Future] = {}
        self.session_id: str | None = None
        self.cwd: str = "/"
        self.running = False

    async def run(self) -> None:
        logger.info("[ACP] Connecting to %s", self.ws_url)
        async with websockets.connect(self.ws_url) as ws:
            self.ws = ws
            await self._receive_loop()

    async def _receive_loop(self) -> None:
        async for message in self.ws:
            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                logger.warning("[ACP] Invalid JSON: %s", message[:200])
                continue

            msg_id = data.get("id")
            method = data.get("method")

            if msg_id is not None and method is None:
                # Response
                future = self.pending.pop(msg_id, None)
                if future and not future.done():
                    future.set_result(data)
            elif msg_id is not None and method is not None:
                # Server request (e.g., session/request_permission)
                await self._handle_server_request(msg_id, method, data.get("params"))
            elif method is not None:
                # Notification (e.g., session/cancel)
                await self._handle_notification(method, data.get("params"))

    async def _handle_server_request(self, msg_id: int, method: str, params: Any) -> None:
        if method == "session/request_permission":
            # Auto-approve for now
            await self._send_response(
                msg_id, {"outcome": {"kind": "selected", "optionId": "approve"}}
            )
        else:
            await self._send_error(msg_id, -32601, f"Method not found: {method}")

    async def _handle_notification(self, method: str, params: Any) -> None:
        if method == "session/cancel":
            self.running = False
            logger.info("[ACP] Received session/cancel")

    async def _send_response(self, msg_id: int, result: Any) -> None:
        await self.ws.send(
            json.dumps({"jsonrpc": "2.0", "id": msg_id, "result": result})
        )

    async def _send_error(self, msg_id: int, code: int, message: str) -> None:
        await self.ws.send(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "error": {"code": code, "message": message},
                }
            )
        )

    async def _call(self, method: str, params: Any) -> dict:
        msg_id = self.next_id
        self.next_id += 1
        future = asyncio.get_event_loop().create_future()
        self.pending[msg_id] = future
        await self.ws.send(
            json.dumps(
                {"jsonrpc": "2.0", "id": msg_id, "method": method, "params": params}
            )
        )
        return await asyncio.wait_for(future, timeout=30.0)

    # ---- ACP methods exposed to executor ----

    async def initialize(self) -> dict:
        return await self._call(
            "initialize",
            {
                "protocolVersion": "0.2",
                "clientCapabilities": {
                    "fs": {"readTextFile": True, "writeTextFile": True},
                    "terminal": False,
                },
            },
        )

    async def session_new(self, cwd: str) -> str:
        result = await self._call("session/new", {"cwd": cwd})
        self.session_id = result.get("result", {}).get("sessionId")
        self.cwd = cwd
        return self.session_id

    async def session_prompt(self, prompt: str) -> dict:
        self.running = True
        result = await self._call(
            "session/prompt",
            {
                "sessionId": self.session_id,
                "prompt": [{"type": "text", "text": prompt}],
            },
        )
        self.running = False
        return result

    async def session_cancel(self) -> None:
        await self.ws.send(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "method": "session/cancel",
                    "params": {"sessionId": self.session_id},
                }
            )
        )


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    url = os.environ.get("SILK_BRIDGE_URL", "ws://localhost:8006/agent-bridge")
    token = os.environ.get("SILK_BRIDGE_TOKEN", "")
    if not token:
        logger.error("SILK_BRIDGE_TOKEN not set")
        exit(1)
    adapter = AcpBridgeAdapter(url, token)
    asyncio.run(adapter.run())
