#!/usr/bin/env python3
"""Executor: spawn claude CLI subprocess in stream-json stdin/stdout mode.

Uses --input-format stream-json and --permission-prompt-tool stdio for
bidirectional communication. Prompts are sent via stdin JSON; permissions
and AskUserQuestion interactions are handled via control_request/control_response
on stdout/stdin.
"""

from __future__ import annotations

import asyncio
import glob
import json
import logging
import os
import platform
import shutil
import time
from typing import Any, Callable, Coroutine

logger = logging.getLogger(__name__)

# Separate raw I/O logger — writes complete stdin/stdout JSON to cli_raw.log.
# Enable via BRIDGE_CLI_RAW_LOG=1 (or any truthy value).
# This logger is independent of BRIDGE_LOG_LEVEL so it won't pollute bridge.log.
_raw_logger: logging.Logger | None = None


def _init_raw_logger() -> logging.Logger | None:
    flag = os.environ.get("BRIDGE_CLI_RAW_LOG", "").strip().lower()
    if flag not in ("1", "true", "yes"):
        return None
    rl = logging.getLogger("cli_raw")
    rl.setLevel(logging.DEBUG)
    rl.propagate = False  # don't send to root / bridge.log
    log_dir = os.environ.get("BRIDGE_CLI_RAW_LOG_DIR", os.path.dirname(__file__))
    fh = logging.FileHandler(os.path.join(log_dir, "cli_raw.log"), encoding="utf-8")
    fh.setFormatter(logging.Formatter("%(asctime)s.%(msecs)03.0f %(message)s", datefmt="%H:%M:%S"))
    rl.addHandler(fh)
    return rl

# ---------------------------------------------------------------------------
# Configuration (env vars with defaults)
# ---------------------------------------------------------------------------


def _detect_claude_path() -> str:
    """Auto-detect the claude CLI executable path.

    Search order:
    1. CLAUDE_CODE_PATH env var (user override)
    2. ``claude`` on PATH (Linux/macOS default, Windows checks .cmd/.exe/.ps1)
    3. Common npm global install locations (platform-specific)
    """
    system = platform.system()

    # 1. User explicitly set the path
    env_path = os.environ.get("CLAUDE_CODE_PATH")
    if env_path:
        if os.path.isfile(env_path) or shutil.which(env_path):
            return env_path
        _die_claude_not_found(system, f"CLAUDE_CODE_PATH={env_path} but file not found")

    # 2. Try ``claude`` directly on PATH
    found = shutil.which("claude")
    if found:
        return found

    # 3. Probe well-known npm global install locations
    candidates: list[str] = []

    if system == "Windows":
        appdata = os.environ.get("APPDATA", "")
        if appdata:
            candidates.append(os.path.join(appdata, "npm", "claude.cmd"))
        home = os.path.expanduser("~")
        candidates.append(os.path.join(home, "AppData", "Roaming", "npm", "claude.cmd"))
        localappdata = os.environ.get("LOCALAPPDATA", "")
        if localappdata:
            candidates.append(os.path.join(localappdata, "fnm_multishells", "**", "claude.cmd"))
            candidates.append(os.path.join(localappdata, "pnpm", "claude.cmd"))
    elif system == "Darwin":
        home = os.path.expanduser("~")
        candidates += [
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            os.path.join(home, ".nvm", "versions", "node", "**", "bin", "claude"),
            os.path.join(home, ".volta", "bin", "claude"),
            os.path.join(home, ".local", "bin", "claude"),
        ]
    else:  # Linux
        home = os.path.expanduser("~")
        candidates += [
            "/usr/local/bin/claude",
            os.path.join(home, ".nvm", "versions", "node", "**", "bin", "claude"),
            os.path.join(home, ".volta", "bin", "claude"),
            os.path.join(home, ".local", "bin", "claude"),
            os.path.join(home, ".npm-global", "bin", "claude"),
        ]

    for pattern in candidates:
        if "**" in pattern:
            matches = glob.glob(pattern, recursive=True)
            if matches:
                return matches[0]
        elif os.path.isfile(pattern):
            return pattern

    _die_claude_not_found(system)
    return ""  # unreachable, for type checker


def _die_claude_not_found(system: str, extra: str = "") -> None:
    """Print a helpful error message and exit."""
    msg = [
        "",
        "=" * 60,
        "  ERROR: Claude Code CLI not found!",
        "=" * 60,
    ]
    if extra:
        msg.append(f"  {extra}")
        msg.append("")
    msg.append("  Please try one of the following:")
    msg.append("")
    msg.append("  1. Install Claude Code:")
    msg.append("     npm install -g @anthropic-ai/claude-code")
    msg.append("")
    msg.append("  2. If already installed, find its path:")
    if system == "Windows":
        msg.append("     where claude.cmd")
        msg.append("     # or in PowerShell:")
        msg.append("     Get-Command claude")
    elif system == "Darwin":
        msg.append("     which claude")
    else:
        msg.append("     which claude")
    msg.append("")
    msg.append("  3. Set the path manually:")
    if system == "Windows":
        msg.append('     set CLAUDE_CODE_PATH=C:\\path\\to\\claude.cmd')
        msg.append("     python acp_adapter.py --server ... --token ...")
    else:
        msg.append("     CLAUDE_CODE_PATH=/path/to/claude python acp_adapter.py --server ... --token ...")
    msg.append("")
    msg.append("=" * 60)
    print("\n".join(msg), flush=True)
    raise SystemExit(1)


CLAUDE_CODE_PATH: str = _detect_claude_path()
_raw_logger = _init_raw_logger()
CLAUDE_CODE_MAX_TURNS: int = int(os.environ.get("CLAUDE_CODE_MAX_TURNS", "100"))
CLAUDE_CODE_TIMEOUT: int = int(os.environ.get("CLAUDE_CODE_TIMEOUT", "36000"))
CLAUDE_CODE_MAX_OUTPUT_CHARS: int = int(
    os.environ.get("CLAUDE_CODE_MAX_OUTPUT_CHARS", "30000")
)
CLAUDE_CODE_PERMISSION_MODE: str = os.environ.get(
    "CLAUDE_CODE_PERMISSION_MODE", "default"
).strip()

# Proxy for claude CLI subprocess only (does not affect bridge's own connections)
CLAUDE_HTTP_PROXY: str = os.environ.get("CLAUDE_HTTP_PROXY", "")
CLAUDE_HTTPS_PROXY: str = os.environ.get("CLAUDE_HTTPS_PROXY", "")

# Streaming throttle
STREAM_MIN_INTERVAL_S: float = 0.5
STREAM_MIN_CHARS: int = 50

# Idle status refresh
IDLE_REFRESH_S: float = 2.0

# ---------------------------------------------------------------------------
# Tool icons for stream-json parsing
# ---------------------------------------------------------------------------

TOOL_ICONS: dict[str, str] = {
    "Read": "\U0001f4d6",       # 📖
    "Write": "\u270d\ufe0f",    # ✍️
    "Edit": "\U0001f4dd",       # 📝
    "NotebookEdit": "\U0001f4d3",  # 📓
    "Bash": "\U0001f4bb",       # 💻
    "Glob": "\U0001f50d",       # 🔍
    "Grep": "\U0001f50d",       # 🔍
    "Task": "\U0001f916",       # 🤖
    "WebFetch": "\U0001f310",   # 🌐
    "Agent": "\U0001f916",      # 🤖
    "TodoWrite": "\U0001f4dd",  # 📝
}

PARAM_MAX: int = 60

# Type aliases
SendFn = Callable[[dict[str, Any]], Coroutine[Any, Any, None]]
PermissionCallbackFn = Callable[
    [str, str, dict[str, Any]],  # request_id, tool_name, tool_input
    Coroutine[Any, Any, dict[str, Any]],  # returns {"behavior": "allow/deny", ...}
]


# ---------------------------------------------------------------------------
# Stream-json parser (stateless, per-line)
# ---------------------------------------------------------------------------

class ParsedLine:
    """Result of parsing a single JSON line from claude CLI output."""

    __slots__ = (
        "text_chunk", "tool_logs", "tool_results", "meta",
        "control_request", "session_id",
    )

    def __init__(
        self,
        text_chunk: str = "",
        tool_logs: list[dict[str, Any]] | None = None,
        tool_results: list[dict[str, Any]] | None = None,
        meta: dict[str, Any] | None = None,
        control_request: dict[str, Any] | None = None,
        session_id: str = "",
    ) -> None:
        self.text_chunk = text_chunk
        self.tool_logs = tool_logs or []
        self.tool_results = tool_results or []
        self.meta = meta
        self.control_request = control_request
        self.session_id = session_id


def format_tool_call(tool_name: str, input_obj: dict[str, Any]) -> str:
    """Format a tool call for display: icon + name + primary param."""
    icon = TOOL_ICONS.get(tool_name, "\U0001f527")  # 🔧

    # Pick the most descriptive parameter
    param = ""
    for key in (
        "file_path", "notebook_path", "command", "pattern",
        "path", "description", "url",
    ):
        val = input_obj.get(key)
        if val is not None and isinstance(val, str):
            param = val
            break
    if not param:
        for v in input_obj.values():
            if isinstance(v, str):
                param = v
                break

    if len(param) > PARAM_MAX:
        display = param[: PARAM_MAX - 3] + "..."
    else:
        display = param

    if display:
        return f"{icon} {tool_name} `{display}`"
    return f"{icon} {tool_name}"


def parse_line(json_line: str) -> ParsedLine:
    """Parse a single JSON line from the claude CLI stream-json output."""
    try:
        data: dict[str, Any] = json.loads(json_line)
    except (json.JSONDecodeError, ValueError):
        return ParsedLine()

    event_type = data.get("type")
    if event_type == "assistant":
        return _parse_assistant(data)
    if event_type == "user":
        return _parse_user(data)
    if event_type == "result":
        return _parse_result(data)
    if event_type == "system":
        return _parse_system(data)
    if event_type == "control_request":
        return _parse_control_request(data)
    return ParsedLine()


def _parse_assistant(data: dict[str, Any]) -> ParsedLine:
    blocks = (data.get("message") or {}).get("content") or []
    text_parts: list[str] = []
    tool_logs: list[dict[str, Any]] = []
    session_id = data.get("session_id", "")

    for block in blocks:
        if isinstance(block, str):
            text_parts.append(block)
            continue
        if not isinstance(block, dict):
            continue

        block_type = block.get("type")
        if block_type == "text":
            text_parts.append(block.get("text", ""))
        elif block_type == "thinking":
            tool_logs.append({
                "line": "\U0001f4ad \u601d\u8003...",
                "toolUseId": None,
                "toolName": "thinking",
            })
        elif block_type == "tool_use":
            name = block.get("name", "Unknown")
            input_obj = block.get("input") or {}
            tool_id = block.get("id", "")
            tool_logs.append({
                "line": format_tool_call(name, input_obj),
                "toolUseId": tool_id,
                "toolName": name,
            })

    text = "\n\n".join(p for p in text_parts if p)
    return ParsedLine(text_chunk=text, tool_logs=tool_logs, session_id=session_id)


def _parse_user(data: dict[str, Any]) -> ParsedLine:
    blocks = (data.get("message") or {}).get("content") or []
    results: list[dict[str, Any]] = []

    for block in blocks:
        if not isinstance(block, dict):
            continue
        if block.get("type") != "tool_result":
            continue

        tool_use_id = block.get("tool_use_id", "")
        is_error = block.get("is_error", False)

        raw_content = block.get("content")
        if isinstance(raw_content, str):
            content_str = raw_content
        elif isinstance(raw_content, list):
            parts = []
            for el in raw_content:
                if isinstance(el, dict) and el.get("type") == "text":
                    parts.append(el.get("text", ""))
            content_str = "\n".join(parts)
        else:
            content_str = ""

        summary = ""
        if is_error and content_str:
            first_line = content_str.strip().split("\n", 1)[0]
            summary = first_line[:PARAM_MAX]

        results.append({
            "toolUseId": tool_use_id,
            "isError": is_error,
            "summary": summary,
            "content": content_str,
        })

    return ParsedLine(tool_results=results)


def _parse_result(data: dict[str, Any]) -> ParsedLine:
    meta = {
        "costUsd": data.get("cost_usd", 0.0),
        "durationMs": data.get("duration_ms", 0),
        "numTurns": data.get("num_turns", 0),
        "sessionId": data.get("session_id", ""),
    }
    result_text = data.get("result", "")
    if not isinstance(result_text, str):
        result_text = ""
    return ParsedLine(text_chunk=result_text, meta=meta)


def _parse_system(data: dict[str, Any]) -> ParsedLine:
    subtype = data.get("subtype", "")
    session_id = data.get("session_id", "")
    if subtype == "compact_boundary":
        pre_tokens = (data.get("compact_metadata") or {}).get("pre_tokens", 0)
        if pre_tokens > 0:
            text = f"\u4e0a\u4e0b\u6587\u5df2\u538b\u7f29\uff08\u538b\u7f29\u524d {pre_tokens:,} tokens\uff09"
        else:
            text = "\u4e0a\u4e0b\u6587\u5df2\u538b\u7f29"
        return ParsedLine(text_chunk=text, session_id=session_id)
    return ParsedLine(session_id=session_id)


def _parse_control_request(data: dict[str, Any]) -> ParsedLine:
    """Parse a control_request event (permission prompt via stdio)."""
    request_id = data.get("request_id", "")
    request = data.get("request", {})
    return ParsedLine(control_request={
        "request_id": request_id,
        "subtype": request.get("subtype", ""),
        "tool_name": request.get("tool_name", ""),
        "input": request.get("input", {}),
    })


# ---------------------------------------------------------------------------
# Executor: manage subprocess + streaming
# ---------------------------------------------------------------------------

class Executor:
    """Manage a single claude CLI subprocess at a time.

    Uses --input-format stream-json mode: the process stays alive for one
    prompt turn. Prompt is sent via stdin JSON, output is read from stdout,
    and permission interactions happen via control_request/control_response.
    """

    def __init__(self) -> None:
        self._process: asyncio.subprocess.Process | None = None
        self._cancel_requested: bool = False

    # ------------------------------------------------------------------
    # Cancel
    # ------------------------------------------------------------------

    async def cancel(self) -> bool:
        """Kill the running subprocess. Return True if a process was killed."""
        self._cancel_requested = True
        proc = self._process
        if proc is not None and proc.returncode is None:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
            logger.info("[Executor] Subprocess killed by cancel request")
            return True
        return False

    # ------------------------------------------------------------------
    # Execute
    # ------------------------------------------------------------------

    async def execute_prompt(
        self,
        send: SendFn,
        request_id: str,
        prompt: str,
        session_id: str,
        working_dir: str,
        resume: bool = False,
        on_session_upsert: Callable[[str, str, str], None] | None = None,
        on_permission_request: PermissionCallbackFn | None = None,
    ) -> None:
        """Spawn claude CLI and stream parsed events to *send*.

        Parameters
        ----------
        send:
            Async callback to send a JSON dict over the WebSocket.
        request_id:
            Unique ID for this request, echoed in every event.
        prompt:
            The user prompt (or "/compact").
        session_id:
            The CC session UUID.
        working_dir:
            Working directory for the subprocess.
        resume:
            If True, use ``--resume`` instead of fresh session.
        on_session_upsert:
            Optional callback ``(session_id, working_dir, title)`` to
            persist session metadata when a result meta arrives.
        on_permission_request:
            Async callback for permission/AskUserQuestion requests.
            Called with (request_id, tool_name, tool_input).
            Must return a dict: {"behavior": "allow", "updatedInput": {...}}
            or {"behavior": "deny", "message": "..."}.
        """
        self._cancel_requested = False

        # Persist session eagerly
        if on_session_upsert is not None:
            title = prompt[:50] + "\u2026" if len(prompt) > 50 else prompt
            on_session_upsert(session_id, working_dir, title)

        cmd = self._build_command(session_id, resume)
        logger.info(
            "[Executor] Spawning subprocess: cmd=%s, cwd=%s",
            " ".join(f'"{c}"' for c in cmd),
            working_dir,
        )

        # Build subprocess env: inherit current env + inject proxy for claude only
        sub_env: dict[str, str] | None = None
        if CLAUDE_HTTP_PROXY or CLAUDE_HTTPS_PROXY:
            sub_env = os.environ.copy()
            if CLAUDE_HTTP_PROXY:
                sub_env["http_proxy"] = CLAUDE_HTTP_PROXY
                sub_env["HTTP_PROXY"] = CLAUDE_HTTP_PROXY
            if CLAUDE_HTTPS_PROXY:
                sub_env["https_proxy"] = CLAUDE_HTTPS_PROXY
                sub_env["HTTPS_PROXY"] = CLAUDE_HTTPS_PROXY

        try:
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=working_dir,
                env=sub_env,
                limit=10 * 1024 * 1024,  # 10 MB
            )
        except Exception as exc:
            logger.error("[Executor] Failed to start claude CLI: %s", exc)
            await send({
                "type": "error",
                "requestId": request_id,
                "error": f"\u542f\u52a8 Claude Code \u5931\u8d25: {exc}",
                "exitCode": -1,
                "stderr": str(exc),
            })
            return

        self._process = process

        # Send the prompt via stdin
        try:
            await self._write_stdin(process, {
                "type": "user",
                "message": {"role": "user", "content": prompt},
            })
        except Exception as exc:
            logger.error("[Executor] Failed to write prompt to stdin: %s", exc)
            await send({
                "type": "error",
                "requestId": request_id,
                "error": f"\u53d1\u9001 prompt \u5931\u8d25: {exc}",
                "exitCode": -1,
                "stderr": str(exc),
            })
            try:
                process.kill()
            except ProcessLookupError:
                pass
            return

        # -- Timeout watchdog --
        timeout_task = asyncio.create_task(self._timeout_watchdog(process))

        # -- State for stream processing --
        accumulated_text = ""
        last_meta: dict[str, Any] | None = None
        active_tool_ids: dict[str, dict[str, str]] = {}
        last_push_time = time.monotonic()
        last_push_len = 0

        # Phase-aware status
        model_thinking = True
        phase_start_time = time.monotonic()

        should_break = False

        try:
            assert process.stdout is not None
            line_count = 0

            while not should_break:
                try:
                    raw_bytes = await asyncio.wait_for(
                        process.stdout.readline(),
                        timeout=IDLE_REFRESH_S,
                    )
                except asyncio.TimeoutError:
                    if process.returncode is not None:
                        break
                    elapsed = int(time.monotonic() - phase_start_time)
                    if model_thinking:
                        status = f"\U0001f4ad \u601d\u8003\u4e2d... (\u5df2\u7b49\u5f85 {elapsed}s)"
                    else:
                        status = f"\u23f3 \u6b63\u5728\u5904\u7406... (\u5df2\u7b49\u5f85 {elapsed}s)"
                    await send({
                        "type": "status_update",
                        "requestId": request_id,
                        "status": status,
                    })
                    continue

                if not raw_bytes:
                    break

                line_count += 1
                line = raw_bytes.decode("utf-8", errors="replace").strip().replace("\r", "")
                if not line:
                    continue
                if not line.startswith("{"):
                    logger.info("[Executor] Non-JSON output: %s", line[:200])
                    continue

                if _raw_logger:
                    _raw_logger.debug("STDOUT <<< %s", line)
                parsed = parse_line(line)

                # ---- Control request (permission / AskUserQuestion) ----
                if parsed.control_request:
                    ctrl = parsed.control_request
                    ctrl_request_id = ctrl["request_id"]
                    tool_name = ctrl["tool_name"]
                    tool_input = ctrl["input"]

                    logger.info(
                        "[Executor] control_request: tool=%s, request_id=%s",
                        tool_name, ctrl_request_id[:8] if ctrl_request_id else "?",
                    )

                    if on_permission_request:
                        try:
                            result = await on_permission_request(
                                ctrl_request_id, tool_name, tool_input,
                            )
                        except Exception as exc:
                            logger.error(
                                "[Executor] permission callback error: %s", exc
                            )
                            result = {
                                "behavior": "deny",
                                "message": f"Permission callback error: {exc}",
                            }
                    else:
                        result = {"behavior": "allow", "updatedInput": tool_input}

                    await self._write_control_response(process, ctrl_request_id, result)
                    phase_start_time = time.monotonic()
                    continue

                # ---- Track session ID from system events ----
                if parsed.session_id:
                    session_id = parsed.session_id
                    if on_session_upsert:
                        title = prompt[:50] + "\u2026" if len(prompt) > 50 else prompt
                        on_session_upsert(session_id, working_dir, title)

                # ---- Phase state transitions ----
                has_tool_results = bool(parsed.tool_results)
                has_assistant_output = bool(parsed.tool_logs) or bool(parsed.text_chunk)
                prev_thinking = model_thinking
                prev_phase_start = phase_start_time

                if has_tool_results:
                    model_thinking = True
                if has_assistant_output:
                    model_thinking = False
                if model_thinking != prev_thinking:
                    phase_start_time = time.monotonic()

                if has_assistant_output and prev_thinking:
                    thinking_duration = int(time.monotonic() - prev_phase_start)
                    for tool_log in parsed.tool_logs:
                        if tool_log.get("toolName") == "thinking":
                            updated = (
                                f"\U0001f4ad \u601d\u8003\u5b8c\u6210 "
                                f"(\u7528\u65f6 {thinking_duration}s)"
                            )
                            await send({
                                "type": "tool_log",
                                "requestId": request_id,
                                "log": updated,
                                "stableId": "cc_thinking",
                            })

                # ---- Tool logs ----
                for tool_log in parsed.tool_logs:
                    tool_use_id = tool_log.get("toolUseId")
                    if tool_use_id:
                        active_tool_ids[tool_use_id] = {
                            "line": tool_log["line"],
                            "toolName": tool_log.get("toolName", ""),
                        }
                    if tool_log.get("toolName") == "thinking":
                        continue
                    await send({
                        "type": "tool_log",
                        "requestId": request_id,
                        "log": tool_log["line"],
                        "stableId": tool_use_id,
                    })

                # ---- Tool results ----
                for result in parsed.tool_results:
                    tool_use_id = result.get("toolUseId", "")
                    info = active_tool_ids.pop(tool_use_id, None)
                    if info is not None:
                        original_line = info["line"]
                        summary = result.get("summary", "")
                        if result.get("isError"):
                            suffix = f" \u2192 \u274c {summary}" if summary else " \u2192 \u274c"
                        else:
                            suffix = " \u2192 \u2705"
                        await send({
                            "type": "tool_log",
                            "requestId": request_id,
                            "log": f"{original_line}{suffix}",
                            "stableId": tool_use_id,
                        })

                # ---- Text accumulation ----
                should_append = (
                    parsed.text_chunk
                    and (parsed.meta is None or not accumulated_text)
                )
                if should_append:
                    accumulated_text += parsed.text_chunk

                    if len(accumulated_text) > CLAUDE_CODE_MAX_OUTPUT_CHARS:
                        accumulated_text = accumulated_text[:CLAUDE_CODE_MAX_OUTPUT_CHARS]
                        logger.warning(
                            "[Executor] Output exceeded %d chars, killing process",
                            CLAUDE_CODE_MAX_OUTPUT_CHARS,
                        )
                        try:
                            process.kill()
                        except ProcessLookupError:
                            pass
                        await send({
                            "type": "stream_text",
                            "requestId": request_id,
                            "text": accumulated_text,
                        })
                        await send({
                            "type": "tool_log",
                            "requestId": request_id,
                            "log": (
                                f"\u26a0\ufe0f \u8f93\u51fa\u5df2\u622a\u65ad"
                                f"\uff08\u8d85\u8fc7 {CLAUDE_CODE_MAX_OUTPUT_CHARS}"
                                f" \u5b57\u7b26\u4e0a\u9650\uff09"
                            ),
                            "stableId": None,
                        })
                        should_break = True
                        continue

                    now = time.monotonic()
                    new_chars = len(accumulated_text) - last_push_len
                    if (
                        now - last_push_time >= STREAM_MIN_INTERVAL_S
                        or new_chars >= STREAM_MIN_CHARS
                    ):
                        await send({
                            "type": "stream_text",
                            "requestId": request_id,
                            "text": accumulated_text,
                        })
                        last_push_time = now
                        last_push_len = len(accumulated_text)

                # ---- Meta (result event) ----
                if parsed.meta is not None:
                    last_meta = parsed.meta
                    if on_session_upsert and parsed.meta.get("sessionId"):
                        title = prompt[:50] + "\u2026" if len(prompt) > 50 else prompt
                        on_session_upsert(
                            parsed.meta["sessionId"],
                            working_dir,
                            title,
                        )
                    # stream-json mode: process won't exit until we close
                    # stdin, so break out of the read loop on result.
                    should_break = True

            # Push remaining text
            if len(accumulated_text) > last_push_len:
                await send({
                    "type": "stream_text",
                    "requestId": request_id,
                    "text": accumulated_text,
                })

            # Close stdin to signal the process to exit
            if process.stdin:
                try:
                    process.stdin.close()
                    await process.stdin.wait_closed()
                except Exception:
                    pass

            logger.info(
                "[Executor] stdout finished (%d lines), waiting for process exit...",
                line_count,
            )
            try:
                await asyncio.wait_for(process.wait(), timeout=30.0)
            except asyncio.TimeoutError:
                logger.warning("[Executor] Process did not exit in time, killing")
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
                await process.wait()

            timeout_task.cancel()
            exit_code = process.returncode or 0

            stderr_text = ""
            if process.stderr:
                try:
                    stderr_bytes = await process.stderr.read()
                    stderr_text = stderr_bytes.decode("utf-8", errors="replace").strip()
                except Exception:
                    pass

            if exit_code != 0:
                logger.warning(
                    "[Executor] Exit code=%d, stderr=%s",
                    exit_code, stderr_text[:500] if stderr_text else "(empty)",
                )

            if self._cancel_requested:
                await send({"type": "cancelled", "requestId": request_id})
            elif exit_code != 0 and not accumulated_text:
                error_msg = stderr_text or (
                    f"Claude Code \u8fdb\u7a0b\u5f02\u5e38\u9000\u51fa (code={exit_code})"
                )
                await send({
                    "type": "error",
                    "requestId": request_id,
                    "error": error_msg,
                    "exitCode": exit_code,
                    "stderr": stderr_text,
                })
            else:
                await send({
                    "type": "complete",
                    "requestId": request_id,
                    "text": accumulated_text,
                    "meta": last_meta,
                })

        except asyncio.CancelledError:
            timeout_task.cancel()
            if process.returncode is None:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
            raise
        except Exception as exc:
            timeout_task.cancel()
            if process.returncode is None:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
            logger.error("[Executor] Error processing subprocess output: %s", exc)
            await send({
                "type": "error",
                "requestId": request_id,
                "error": f"\u5904\u7406 Claude Code \u8f93\u51fa\u5f02\u5e38: {exc}",
                "exitCode": -1,
                "stderr": str(exc),
            })
        finally:
            self._process = None

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _build_command(self, session_id: str, resume: bool) -> list[str]:
        """Build the claude CLI command for stream-json stdin/stdout mode."""
        claude_args: list[str] = [
            CLAUDE_CODE_PATH,
            "--output-format", "stream-json",
            "--input-format", "stream-json",
            "--permission-prompt-tool", "stdio",
            "--verbose",
            "--max-turns", str(CLAUDE_CODE_MAX_TURNS),
        ]
        if CLAUDE_CODE_PERMISSION_MODE.lower() not in {"", "none", "off", "false", "0"}:
            claude_args.extend(["--permission-mode", CLAUDE_CODE_PERMISSION_MODE])
        if resume:
            claude_args.extend(["--resume", session_id])
        return claude_args

    async def _write_stdin(
        self, process: asyncio.subprocess.Process, payload: dict[str, Any]
    ) -> None:
        """Write a JSON message to the process stdin."""
        assert process.stdin is not None
        data = json.dumps(payload, ensure_ascii=False) + "\n"
        if _raw_logger:
            _raw_logger.debug("STDIN  >>> %s", data.rstrip())
        process.stdin.write(data.encode("utf-8"))
        await process.stdin.drain()

    async def _write_control_response(
        self,
        process: asyncio.subprocess.Process,
        ctrl_request_id: str,
        result: dict[str, Any],
    ) -> None:
        """Write a control_response to stdin in response to a control_request."""
        behavior = result.get("behavior", "deny")

        if behavior == "allow":
            perm_response = {
                "behavior": "allow",
                "updatedInput": result.get("updatedInput", {}),
            }
        else:
            perm_response = {
                "behavior": "deny",
                "message": result.get("message", "User denied this tool use."),
            }

        control_response = {
            "type": "control_response",
            "response": {
                "subtype": "success",
                "request_id": ctrl_request_id,
                "response": perm_response,
            },
        }

        logger.debug(
            "[Executor] Writing control_response: request_id=%s, behavior=%s",
            ctrl_request_id[:8] if ctrl_request_id else "?", behavior,
        )
        await self._write_stdin(process, control_response)

    async def _timeout_watchdog(self, process: asyncio.subprocess.Process) -> None:
        """Kill the subprocess after CLAUDE_CODE_TIMEOUT seconds."""
        try:
            await asyncio.sleep(CLAUDE_CODE_TIMEOUT)
            if process.returncode is None:
                logger.warning(
                    "[Executor] Subprocess timed out (%ds), killing",
                    CLAUDE_CODE_TIMEOUT,
                )
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
        except asyncio.CancelledError:
            pass
