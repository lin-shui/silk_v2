from __future__ import annotations

import asyncio
import json
import unittest

from bridge_common.host_ipc import HostIPC, HostIPCError


class HostIPCTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.host_to_adapter: asyncio.Queue[str] = asyncio.Queue()
        self.adapter_to_host: asyncio.Queue[str] = asyncio.Queue()

        async def read_line() -> str:
            return await self.host_to_adapter.get()

        async def write_line(line: str) -> None:
            await self.adapter_to_host.put(line)

        self.ipc = HostIPC(read_line=read_line, write_line=write_line)

    async def asyncTearDown(self) -> None:
        await self.ipc.close()

    async def test_bound_handshake_bidirectional_acp_health_and_shutdown(self) -> None:
        start_task = asyncio.create_task(self.ipc.start("codex"))
        await self.host_to_adapter.put(json.dumps({
            "jsonrpc": "2.0",
            "id": "host-1",
            "method": "host/initialize",
            "params": {
                "protocolVersion": "2",
                "nonce": "nonce-1",
                "agentInstanceId": "agent-1",
                "agentType": "codex",
                "hostVersion": "0.1.0",
                "agentCapabilities": ["PROMPT"],
            },
        }))
        initialization = await start_task
        handshake = json.loads(await self.adapter_to_host.get())
        self.assertEqual("nonce-1", handshake["result"]["nonce"])
        self.assertEqual("agent-1", initialization.agent_instance_id)

        await self.host_to_adapter.put(json.dumps({
            "jsonrpc": "2.0",
            "id": "host-acp-1",
            "method": "adapter/acp",
            "params": {"message": {"jsonrpc": "2.0", "id": 7, "method": "initialize", "params": {}}},
        }))
        inbound = await asyncio.wait_for(self.ipc.receive_acp(), timeout=1)
        self.assertEqual("initialize", inbound["method"])
        inbound_ack = json.loads(await self.adapter_to_host.get())
        self.assertEqual("host-acp-1", inbound_ack["id"])

        forward_task = asyncio.create_task(self.ipc.forward_acp({
            "jsonrpc": "2.0",
            "id": 7,
            "result": {"protocolVersion": "0.2"},
        }))
        forward_request = json.loads(await self.adapter_to_host.get())
        self.assertEqual("host/forwardAcp", forward_request["method"])
        self.assertEqual(7, forward_request["params"]["message"]["id"])
        await self.host_to_adapter.put(json.dumps({
            "jsonrpc": "2.0",
            "id": forward_request["id"],
            "result": {},
        }))
        await forward_task

        await self.host_to_adapter.put(json.dumps({
            "jsonrpc": "2.0",
            "id": "host-2",
            "method": "adapter/health",
            "params": {},
        }))
        health = json.loads(await self.adapter_to_host.get())
        self.assertEqual("ok", health["result"]["status"])

        await self.host_to_adapter.put(json.dumps({
            "jsonrpc": "2.0",
            "id": "host-3",
            "method": "adapter/shutdown",
            "params": {},
        }))
        shutdown = json.loads(await self.adapter_to_host.get())
        self.assertEqual("host-3", shutdown["id"])
        await asyncio.wait_for(self.ipc.wait_shutdown(), timeout=1)

    async def test_malformed_host_message_fails_pending_calls_and_shuts_down(self) -> None:
        start_task = asyncio.create_task(self.ipc.start("codex"))
        await self.host_to_adapter.put(json.dumps({
            "jsonrpc": "2.0",
            "id": "host-1",
            "method": "host/initialize",
            "params": {
                "protocolVersion": "2",
                "nonce": "nonce-1",
                "agentInstanceId": "agent-1",
                "agentType": "codex",
                "hostVersion": "0.1.0",
                "agentCapabilities": [],
            },
        }))
        await start_task
        await self.adapter_to_host.get()

        forward_task = asyncio.create_task(self.ipc.forward_acp({"jsonrpc": "2.0", "id": 1, "result": {}}))
        await self.adapter_to_host.get()
        await self.host_to_adapter.put("{invalid-json\n")

        await asyncio.wait_for(self.ipc.wait_shutdown(), timeout=1)
        with self.assertRaisesRegex(HostIPCError, "Host IPC read failed"):
            await forward_task

    async def test_host_eof_fails_pending_calls_without_timeout(self) -> None:
        start_task = asyncio.create_task(self.ipc.start("codex"))
        await self.host_to_adapter.put(json.dumps({
            "jsonrpc": "2.0",
            "id": "host-1",
            "method": "host/initialize",
            "params": {
                "protocolVersion": "2",
                "nonce": "nonce-1",
                "agentInstanceId": "agent-1",
                "agentType": "codex",
                "hostVersion": "0.1.0",
                "agentCapabilities": [],
            },
        }))
        await start_task
        await self.adapter_to_host.get()

        forward_task = asyncio.create_task(self.ipc.forward_acp({"jsonrpc": "2.0", "id": 1, "result": {}}))
        await self.adapter_to_host.get()
        await self.host_to_adapter.put("")

        with self.assertRaisesRegex(HostIPCError, "Host IPC closed"):
            await asyncio.wait_for(forward_task, timeout=1)


if __name__ == "__main__":
    unittest.main()
