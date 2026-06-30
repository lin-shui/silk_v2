#!/usr/bin/env python3
"""
PTY bridge for claude CLI - forces real-time token streaming via --include-partial-messages.
Parses stream-json output, streams thinking and text deltas incrementally to stdout.
"""

import argparse
import json
import os
import pty
import select
import sys
import uuid


def main():
    parser = argparse.ArgumentParser(description="PTY bridge for claude CLI")
    parser.add_argument("prompt_file", help="Path to prompt text file")
    parser.add_argument("--permission-mode", dest="permission_mode", default=None,
                        choices=["dontAsk", "bypassPermissions"],
                        help="Claude CLI permission mode")
    parser.add_argument("--disallowed-tools", dest="disallowed_tools",
                        default="Bash,Write,Edit,ExecuteCommand",
                        help="Comma-separated list of disallowed tools")
    args = parser.parse_args()

    prompt_file = args.prompt_file

    with open(prompt_file, "r", encoding="utf-8") as f:
        prompt = f.read()

    cmd = [
        "claude",
        "-p", prompt,
        "--session-id", str(uuid.uuid4()),
        "--no-chrome",
        "--print",
        "--output-format", "stream-json",
        "--verbose",
        "--include-partial-messages",
    ]

    permission_mode = args.permission_mode

    if permission_mode == "dontAsk":
        # dontAsk 模式自带只读 + 目录沙盒，不需要 --disallowedTools
        cmd.extend(["--permission-mode", "dontAsk"])
    else:
        # 默认行为：禁用危险工具
        cmd.extend(["--disallowedTools", args.disallowed_tools])
        # root 用户下 claude CLI 拒绝 --permission-mode bypassPermissions，
        # 非 root 时加上可跳过权限交互提示。
        if os.getuid() != 0:
            cmd.extend(["--permission-mode", permission_mode or "bypassPermissions"])

    pid, fd = pty.fork()

    if pid == 0:
        os.execvp(cmd[0], cmd)
        sys.exit(1)

    try:
        _forward_pty_output(fd, pid)
    finally:
        try:
            os.close(fd)
        except OSError:
            pass
        try:
            os.waitpid(pid, 0)
        except OSError:
            pass


def _process_line(line: str, state: dict) -> str:
    """Process a JSON line and update state. Returns text to emit (may be empty)."""
    line = line.strip()
    if not line:
        return ""
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return ""

    if obj.get("type") != "stream_event":
        return ""

    event = obj.get("event", {})
    event_type = event.get("type", "")

    if event_type == "content_block_start":
        block = event.get("content_block", {})
        block_type = block.get("type", "")
        if block_type == "thinking":
            state["in_thinking"] = True
            state["thinking_buf"] = []
            state["thinking_emitted_len"] = 0
        elif block_type == "text":
            state["in_text"] = True
            # Flush remaining thinking and insert marker
            result = _flush_thinking(state)
            return result + "\n\n<!--THINKING_END-->\n\n"
        return ""

    elif event_type == "content_block_delta":
        delta = event.get("delta", {})
        delta_type = delta.get("type", "")

        if delta_type == "thinking_delta" and state.get("in_thinking"):
            text = delta.get("thinking", "")
            if text:
                state["thinking_buf"].append(text)
                # Periodically flush new thinking for progressive display
                acc = "".join(state["thinking_buf"])
                em = state["thinking_emitted_len"]
                if len(acc) - em >= 250:
                    new_text = acc[em:]
                    state["thinking_emitted_len"] = len(acc)
                    if new_text:
                        return new_text
            return ""

        if delta_type == "text_delta":
            return delta.get("text", "")

        return ""

    elif event_type == "content_block_stop":
        state["in_thinking"] = False
        state["in_text"] = False
        return ""

    return ""


def _flush_thinking(state: dict) -> str:
    """Flush any remaining buffered thinking content as plain text."""
    buf = state.get("thinking_buf")
    if not buf:
        return ""
    acc = "".join(buf)
    em = state["thinking_emitted_len"]
    if em >= len(acc):
        return ""
    new_text = acc[em:]
    state["thinking_emitted_len"] = len(acc)
    new_text = new_text.lstrip()
    return new_text


def _forward_pty_output(fd: int, pid: int) -> None:
    """Read PTY output, parse stream-json, and forward plain text to stdout."""
    state = {"in_thinking": False, "in_text": False, "thinking_buf": None}
    buf = ""

    while True:
        try:
            r, _, _ = select.select([fd], [], [], 0.5)
            if r:
                data = os.read(fd, 4096)
                if not data:
                    break
                buf += data.decode("utf-8", errors="replace")

                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    text = _process_line(line, state)
                    if text:
                        sys.stdout.write(text)
                        sys.stdout.flush()
        except (OSError, ValueError):
            break

    try:
        while True:
            data = os.read(fd, 4096)
            if not data:
                break
            buf += data.decode("utf-8", errors="replace")
            while "\n" in buf:
                line, buf = buf.split("\n", 1)
                text = _process_line(line, state)
                if text:
                    sys.stdout.write(text)
                    sys.stdout.flush()
    except OSError:
        pass


if __name__ == "__main__":
    main()
