#!/usr/bin/env python3
"""Quick manual test for /agent-bridge endpoint."""
import asyncio
import json
import sys

import websockets


async def test(url: str, token: str):
    ws_url = f"{url}?agentType=claude-code&token={token}"
    print(f"Connecting to {ws_url} ...")

    async with websockets.connect(ws_url) as ws:
        print("Connected!")

        # 1. Read initialize request from server
        msg = await asyncio.wait_for(ws.recv(), timeout=5.0)
        data = json.loads(msg)
        print(f"<- Received: {json.dumps(data, indent=2)}")

        assert data.get("method") == "initialize", f"Expected initialize, got {data.get('method')}"
        req_id = data["id"]

        # 2. Respond to initialize
        resp = {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": "0.2",
                "agentCapabilities": {
                    "loadSession": True,
                    "promptCapabilities": {"image": False, "audio": False, "embeddedContext": False},
                    "_silk": {"compact": True, "listLocalSessions": True, "setCwd": False}
                }
            }
        }
        await ws.send(json.dumps(resp))
        print(f"-> Sent initialize response")

        # 3. Wait for any further messages (e.g., session/new when a prompt is triggered)
        print("Waiting for messages (press Ctrl+C to exit)...")
        try:
            while True:
                msg = await asyncio.wait_for(ws.recv(), timeout=30.0)
                data = json.loads(msg)
                print(f"<- Received: {json.dumps(data, indent=2)}")

                # Auto-respond to session/new
                if data.get("method") == "session/new":
                    req_id = data["id"]
                    await ws.send(json.dumps({
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "result": {"sessionId": "test-session-001"}
                    }))
                    print("-> Sent session/new response")

                # Auto-respond to session/prompt
                elif data.get("method") == "session/prompt":
                    req_id = data["id"]
                    await ws.send(json.dumps({
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "result": {"stopReason": "end_turn"}
                    }))
                    print("-> Sent session/prompt response")

        except asyncio.TimeoutError:
            print("No messages for 30s, exiting.")


if __name__ == "__main__":
    url = sys.argv[1] if len(sys.argv) > 1 else "ws://localhost:8006/agent-bridge"
    token = sys.argv[2] if len(sys.argv) > 2 else ""
    if not token:
        print("Usage: python3 test_acp_connection.py <ws_url> <token>")
        print("Example: python3 test_acp_connection.py ws://localhost:8006/agent-bridge your-token")
        sys.exit(1)
    asyncio.run(test(url, token))
