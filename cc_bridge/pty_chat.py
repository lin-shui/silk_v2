#!/usr/bin/env python3
"""
PTY bridge for claude CLI - forces line-buffered output via pseudo-terminal.

Usage:
    python3 pty_chat.py <prompt_file>

Reads the prompt from the specified file, spawns `claude -p <prompt>`
inside a PTY, and forwards output to stdout byte-by-byte for real-time streaming.
"""

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

    # Read prompt from file (to avoid shell escaping issues)
    try:
        with open(prompt_file, "r", encoding="utf-8") as f:
            prompt = f.read()
    except Exception as e:
        print(f"Error reading prompt file: {e}", file=sys.stderr)
        sys.exit(1)

    # Build claude command
    cmd = [
        "claude",
        "-p", prompt,
        "--session-id", str(uuid.uuid4()),
        "--disallowedTools", "Bash,Write,Edit,ExecuteCommand",
        "--no-chrome",
        "--permission-mode", "bypassPermissions",
    ]

    pid, fd = pty.fork()

    if pid == 0:
        # Child process: execute claude
        os.execvp(cmd[0], cmd)
        # If exec fails
        sys.exit(1)

    # Parent process: read from PTY and forward to stdout
    try:
        _forward_pty_output(fd, pid)
    finally:
        # Clean up
        try:
            os.close(fd)
        except OSError:
            pass
        try:
            os.waitpid(pid, 0)
        except OSError:
            pass


def _forward_pty_output(fd: int, pid: int) -> None:
    """Read PTY output and forward to stdout with immediate flush."""
    while True:
        try:
            r, _, _ = select.select([fd], [], [], 0.5)
            if r:
                data = os.read(fd, 4096)
                if not data:
                    break
                # Write to stdout (which may be piped to Java)
                sys.stdout.buffer.write(data)
                sys.stdout.buffer.flush()
        except (OSError, ValueError):
            break

    # Drain remaining
    try:
        while True:
            data = os.read(fd, 4096)
            if not data:
                break
            sys.stdout.buffer.write(data)
            sys.stdout.buffer.flush()
    except OSError:
        pass


if __name__ == "__main__":
    main()
