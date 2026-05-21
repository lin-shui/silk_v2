"""Manage `agent acp` subprocess and bidirectional JSON-RPC 2.0 over stdio.

Spawns the Cursor ACP process, provides async send/receive of JSON-RPC
messages over stdin/stdout, and handles subprocess lifecycle.
"""
from __future__ import annotations

import asyncio
import enum
import json
import logging
import os
import platform
import shutil
import time
from typing import Any, Callable, Coroutine

logger = logging.getLogger("cursor_relay")

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

CURSOR_AGENT_ARGS: list[str] = os.environ.get("CURSOR_AGENT_ARGS", "acp").split()
CURSOR_TIMEOUT: int = int(os.environ.get("CURSOR_TIMEOUT", "36000"))
CURSOR_IDLE_TIMEOUT: int = int(os.environ.get("CURSOR_IDLE_TIMEOUT", "1800"))  # 30 min
IDLE_REFRESH_S: float = 2.0


def _detect_agent_path() -> str:
    """Auto-detect the Cursor agent CLI executable path.

    Search order:
    1. CURSOR_AGENT_PATH env var (user override)
    2. ``agent`` on PATH
    3. Common install locations (platform-specific)
    """
    # 1. User explicitly set the path
    env_path = os.environ.get("CURSOR_AGENT_PATH")
    if env_path:
        if os.path.isfile(env_path) or shutil.which(env_path):
            return env_path
        logger.error("CURSOR_AGENT_PATH=%s but file not found", env_path)
        raise SystemExit(1)

    # 2. Try ``agent`` directly on PATH
    found = shutil.which("agent")
    if found:
        return found

    # 3. Probe well-known locations
    home = os.path.expanduser("~")
    candidates = [
        os.path.join(home, ".local", "bin", "agent"),
        "/usr/local/bin/agent",
    ]
    for path in candidates:
        if os.path.isfile(path):
            return path

    logger.error(
        "Cursor agent CLI not found. Install it or set CURSOR_AGENT_PATH."
    )
    raise SystemExit(1)


CURSOR_AGENT_COMMAND: str = _detect_agent_path()

# ---------------------------------------------------------------------------
# JSON-RPC message classification
# ---------------------------------------------------------------------------


class MessageKind(enum.Enum):
    NOTIFICATION = "notification"      # method present, id absent/null
    RESPONSE = "response"              # id present, method absent -> response to our call
    SERVER_REQUEST = "server_request"   # both id and method present -> agent asking us
    UNKNOWN = "unknown"


def parse_jsonrpc_line(line: str) -> tuple[MessageKind, dict[str, Any]]:
    """Classify a single JSON-RPC 2.0 line from stdout.

    Returns (kind, parsed_dict). On parse failure returns (UNKNOWN, {}).
    """
    stripped = line.strip()
    if not stripped:
        return MessageKind.UNKNOWN, {}
    try:
        msg: dict[str, Any] = json.loads(stripped)
    except (json.JSONDecodeError, ValueError):
        return MessageKind.UNKNOWN, {}

    has_method = "method" in msg
    has_id = "id" in msg and msg["id"] is not None

    if has_method and has_id:
        return MessageKind.SERVER_REQUEST, msg
    if has_method and not has_id:
        return MessageKind.NOTIFICATION, msg
    if has_id and not has_method:
        return MessageKind.RESPONSE, msg
    return MessageKind.UNKNOWN, msg


# Type aliases
NotificationHandler = Callable[[str, Any], Coroutine[Any, Any, None]]
ServerRequestHandler = Callable[[Any, str, Any], Coroutine[Any, Any, None]]


# ---------------------------------------------------------------------------
# AcpSubprocess
# ---------------------------------------------------------------------------


class AcpSubprocess:
    """Manage `agent acp` as a child process, speak JSON-RPC 2.0 over stdio.

    Lifecycle:
        1. start(cwd) -- spawn subprocess, start read loop
        2. call(method, params) -- send JSON-RPC request, await response
        3. send_notification(method, params) -- fire-and-forget
        4. respond(id, result) / respond_error(id, code, msg) -- answer server requests
        5. stop() -- kill subprocess
    """

    def __init__(
        self,
        on_notification: NotificationHandler | None = None,
        on_server_request: ServerRequestHandler | None = None,
    ) -> None:
        self._process: asyncio.subprocess.Process | None = None
        self._next_id: int = 0
        self._pending: dict[int, asyncio.Future[dict[str, Any]]] = {}
        self._on_notification = on_notification
        self._on_server_request = on_server_request
        self._read_task: asyncio.Task | None = None
        self._timeout_task: asyncio.Task | None = None
        self._idle_task: asyncio.Task | None = None
        self._alive: bool = False
        self.supports_load_session: bool = False  # V2 probe: whether Cursor supports session/load

    @property
    def alive(self) -> bool:
        return self._alive

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def start(self, cwd: str) -> None:
        """Spawn `agent acp` subprocess and start the read loop."""
        cmd = [CURSOR_AGENT_COMMAND] + CURSOR_AGENT_ARGS
        logger.info("[Relay] Spawning: %s in %s", " ".join(cmd), cwd)

        self._process = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=cwd,
            limit=10 * 1024 * 1024,
        )
        self._alive = True
        self._read_task = asyncio.create_task(self._read_loop())
        self._read_task.add_done_callback(self._on_read_done)
        self._timeout_task = asyncio.create_task(self._timeout_watchdog())

    async def stop(self) -> None:
        """Kill subprocess and cleanup."""
        self._alive = False
        if self._timeout_task and not self._timeout_task.done():
            self._timeout_task.cancel()
        proc = self._process
        if proc is not None and proc.returncode is None:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(proc.wait(), timeout=5.0)
            except asyncio.TimeoutError:
                pass
        # Fail all pending calls
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(ConnectionError("subprocess stopped"))
        self._pending.clear()
        self._process = None

    # ------------------------------------------------------------------
    # Send: client -> agent
    # ------------------------------------------------------------------

    async def call(
        self, method: str, params: Any = None, *, timeout: float = 30.0,
    ) -> dict[str, Any]:
        """Send JSON-RPC request and await response. Returns result dict."""
        if not self._alive or self._process is None:
            raise ConnectionError("subprocess not running")
        self._next_id += 1
        msg_id = self._next_id
        fut: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()
        self._pending[msg_id] = fut

        msg = {"jsonrpc": "2.0", "id": msg_id, "method": method}
        if params is not None:
            msg["params"] = params
        await self._write(msg)

        try:
            result = await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError:
            self._pending.pop(msg_id, None)
            raise TimeoutError(f"RPC {method} timed out after {timeout}s")
        finally:
            self._pending.pop(msg_id, None)
        return result

    async def send_notification(self, method: str, params: Any = None) -> None:
        """Send a notification (no id, no response expected)."""
        msg: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        await self._write(msg)

    async def respond(self, msg_id: Any, result: Any) -> None:
        """Send success response to a server request."""
        await self._write({"jsonrpc": "2.0", "id": msg_id, "result": result})

    async def respond_error(self, msg_id: Any, code: int, message: str) -> None:
        """Send error response to a server request."""
        await self._write({
            "jsonrpc": "2.0", "id": msg_id,
            "error": {"code": code, "message": message},
        })

    # ------------------------------------------------------------------
    # Read loop
    # ------------------------------------------------------------------

    async def _read_loop(self) -> None:
        """Continuously read stdout, classify lines, dispatch."""
        assert self._process is not None and self._process.stdout is not None
        while self._alive:
            try:
                raw = await asyncio.wait_for(
                    self._process.stdout.readline(), timeout=IDLE_REFRESH_S,
                )
            except asyncio.TimeoutError:
                if self._process.returncode is not None:
                    break
                continue
            if not raw:
                break

            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                continue

            kind, msg = parse_jsonrpc_line(line)

            if kind == MessageKind.RESPONSE:
                msg_id = msg.get("id")
                fut = self._pending.get(msg_id)
                if fut is not None and not fut.done():
                    if "error" in msg:
                        fut.set_exception(
                            RuntimeError(f"RPC error {msg['error'].get('code')}: {msg['error'].get('message')}")
                        )
                    else:
                        fut.set_result(msg.get("result", {}))

            elif kind == MessageKind.NOTIFICATION:
                if self._on_notification:
                    try:
                        await self._on_notification(msg["method"], msg.get("params"))
                    except Exception as exc:
                        logger.error("[Relay] notification handler error: %s", exc, exc_info=True)

            elif kind == MessageKind.SERVER_REQUEST:
                if self._on_server_request:
                    try:
                        await self._on_server_request(msg["id"], msg["method"], msg.get("params"))
                    except Exception as exc:
                        logger.error("[Relay] server request handler error: %s", exc, exc_info=True)
                        try:
                            await self.respond_error(msg["id"], -32000, str(exc))
                        except Exception:
                            pass
                else:
                    # No handler -- auto-ack unknown server requests
                    try:
                        await self.respond(msg["id"], {})
                    except Exception:
                        pass

        self._alive = False
        logger.info("[Relay] Read loop ended (process rc=%s)", self._process.returncode if self._process else "?")

    def _on_read_done(self, task: asyncio.Task) -> None:
        try:
            exc = task.exception()
        except (asyncio.CancelledError, asyncio.InvalidStateError):
            return
        if exc:
            logger.error("[Relay] Read loop crashed: %s", exc, exc_info=exc)
        self._alive = False
        # Fail all pending
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(ConnectionError("read loop ended"))
        self._pending.clear()

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    async def _write(self, msg: dict[str, Any]) -> None:
        """Write JSON-RPC message to stdin as newline-delimited JSON."""
        proc = self._process
        if proc is None or proc.stdin is None:
            raise ConnectionError("subprocess not running")
        data = json.dumps(msg, ensure_ascii=False) + "\n"
        proc.stdin.write(data.encode("utf-8"))
        await proc.stdin.drain()

    def reset_idle_timer(self) -> None:
        """Reset the idle timer. Call after each prompt completes."""
        if self._idle_task and not self._idle_task.done():
            self._idle_task.cancel()
        if self._alive:
            self._idle_task = asyncio.create_task(self._idle_watchdog())

    async def _idle_watchdog(self) -> None:
        """Kill subprocess after CURSOR_IDLE_TIMEOUT seconds of inactivity."""
        try:
            await asyncio.sleep(CURSOR_IDLE_TIMEOUT)
            if self._alive:
                logger.info("[Relay] Subprocess idle for %ds, reclaiming", CURSOR_IDLE_TIMEOUT)
                await self.stop()
        except asyncio.CancelledError:
            pass

    async def _timeout_watchdog(self) -> None:
        try:
            await asyncio.sleep(CURSOR_TIMEOUT)
            logger.warning("[Relay] Subprocess timed out (%ds), killing", CURSOR_TIMEOUT)
            await self.stop()
        except asyncio.CancelledError:
            pass
