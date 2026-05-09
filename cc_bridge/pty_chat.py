#!/usr/bin/env python3
"""
PTY bridge for claude CLI - forces real-time token streaming via --include-partial-messages.
Parses stream-json output and extracts plain text to stdout.
"""

import json
import os
import pty
import select
import sys
import uuid


def main():
    if len(sys.argv) < 2:
        print("Usage: pty_chat.py <prompt_file>", file=sys.stderr)
        sys.exit(1)

    prompt_file = sys.argv[1]

    with open(prompt_file, "r", encoding="utf-8") as f:
        prompt = f.read()

    # --include-partial-messages enables real-time token-by-token output
    # requires --print and --output-format=stream-json
    cmd = [
        "claude",
        "-p", prompt,
        "--session-id", str(uuid.uuid4()),
        "--disallowedTools", "Bash,Write,Edit,ExecuteCommand",
        "--no-chrome",
        "--permission-mode", "bypassPermissions",
        "--print",
        "--output-format", "stream-json",
        "--verbose",
        "--include-partial-messages",
    ]

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


def _try_extract_text(line: str) -> str | None:
    """Try to parse a JSON line from claude stream-json and return the text content delta."""
    line = line.strip()
    if not line:
        return None
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return None

    # Stream events wrap content deltas
    if obj.get("type") != "stream_event":
        return None

    event = obj.get("event", {})
    if event.get("type") != "content_block_delta":
        return None

    delta = event.get("delta", {})
    if delta.get("type") == "text_delta":
        text = delta.get("text", "")
        if text:
            return text

    return None


def _forward_pty_output(fd: int, pid: int) -> None:
    """Read PTY output, parse stream-json, and forward plain text to stdout."""
    buf = ""
    while True:
        try:
            r, _, _ = select.select([fd], [], [], 0.5)
            if r:
                data = os.read(fd, 4096)
                if not data:
                    break
                buf += data.decode("utf-8", errors="replace")

                # Process complete lines (NDJSON)
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    text = _try_extract_text(line)
                    if text:
                        sys.stdout.write(text)
                        sys.stdout.flush()
        except (OSError, ValueError):
            break

    # Drain any remaining data
    try:
        while True:
            data = os.read(fd, 4096)
            if not data:
                break
            buf += data.decode("utf-8", errors="replace")
            while "\n" in buf:
                line, buf = buf.split("\n", 1)
                text = _try_extract_text(line)
                if text:
                    sys.stdout.write(text)
                    sys.stdout.flush()
    except OSError:
        pass


if __name__ == "__main__":
    main()
