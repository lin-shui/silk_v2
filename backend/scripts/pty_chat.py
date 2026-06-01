#!/usr/bin/env python3
"""
PTY bridge for claude CLI with Landlock sandboxing.

Forces real-time token streaming via --include-partial-messages,
parses stream-json output, and sandboxes the claude subprocess
with Landlock (Linux 6.7+ / ABI >= 4).

Usage: pty_chat.py <prompt_file> <workspace_dir>

  prompt_file   Path to the prompt file (MUST be inside workspace_dir)
  workspace_dir Root directory that the claude subprocess is allowed to access
"""

import json
import os
import pty
import resource
import select
import sys
import uuid

# ──────────────────────────────────────────────
# Landlock constants (Linux <linux/landlock.h>)
# ──────────────────────────────────────────────

# Syscall numbers (same on x86_64 and aarch64)
LANDLOCK_CREATE_RULESET = 444
LANDLOCK_ADD_RULE = 445
LANDLOCK_RESTRICT_SELF = 446

# File system access rights (ABI 1)
LANDLOCK_ACCESS_FS_EXECUTE = 1 << 0
LANDLOCK_ACCESS_FS_WRITE_FILE = 1 << 1
LANDLOCK_ACCESS_FS_READ_FILE = 1 << 2
LANDLOCK_ACCESS_FS_READ_DIR = 1 << 3
LANDLOCK_ACCESS_FS_REMOVE_DIR = 1 << 4
LANDLOCK_ACCESS_FS_REMOVE_FILE = 1 << 5
LANDLOCK_ACCESS_FS_MAKE_CHAR = 1 << 6
LANDLOCK_ACCESS_FS_MAKE_DIR = 1 << 7
LANDLOCK_ACCESS_FS_MAKE_REG = 1 << 8
LANDLOCK_ACCESS_FS_MAKE_SOCK = 1 << 9
LANDLOCK_ACCESS_FS_MAKE_FIFO = 1 << 10
LANDLOCK_ACCESS_FS_MAKE_BLOCK = 1 << 11
LANDLOCK_ACCESS_FS_MAKE_SYM = 1 << 12
# ABI 2
LANDLOCK_ACCESS_FS_REFER = 1 << 13
# ABI 3
LANDLOCK_ACCESS_FS_TRUNCATE = 1 << 14

# Rule types
LANDLOCK_RULE_PATH_BENEATH = 1

MAX_ACCESS_FS = (
    LANDLOCK_ACCESS_FS_EXECUTE |
    LANDLOCK_ACCESS_FS_WRITE_FILE |
    LANDLOCK_ACCESS_FS_READ_FILE |
    LANDLOCK_ACCESS_FS_READ_DIR |
    LANDLOCK_ACCESS_FS_REMOVE_DIR |
    LANDLOCK_ACCESS_FS_REMOVE_FILE |
    LANDLOCK_ACCESS_FS_MAKE_CHAR |
    LANDLOCK_ACCESS_FS_MAKE_DIR |
    LANDLOCK_ACCESS_FS_MAKE_REG |
    LANDLOCK_ACCESS_FS_MAKE_SOCK |
    LANDLOCK_ACCESS_FS_MAKE_FIFO |
    LANDLOCK_ACCESS_FS_MAKE_BLOCK |
    LANDLOCK_ACCESS_FS_MAKE_SYM |
    LANDLOCK_ACCESS_FS_REFER |
    LANDLOCK_ACCESS_FS_TRUNCATE
)

# Essential FS rights for claude runtime
ACCESS_RUNTIME = (
    LANDLOCK_ACCESS_FS_READ_FILE |
    LANDLOCK_ACCESS_FS_READ_DIR |
    LANDLOCK_ACCESS_FS_EXECUTE
)

# Full FS rights for workspace
ACCESS_WORKSPACE = (
    LANDLOCK_ACCESS_FS_READ_FILE |
    LANDLOCK_ACCESS_FS_WRITE_FILE |
    LANDLOCK_ACCESS_FS_READ_DIR |
    LANDLOCK_ACCESS_FS_EXECUTE |
    LANDLOCK_ACCESS_FS_REMOVE_DIR |
    LANDLOCK_ACCESS_FS_REMOVE_FILE |
    LANDLOCK_ACCESS_FS_MAKE_DIR |
    LANDLOCK_ACCESS_FS_MAKE_REG |
    LANDLOCK_ACCESS_FS_TRUNCATE
)


# ──────────────────────────────────────────────
# Landlock C-struct definitions (via ctypes)
# ──────────────────────────────────────────────

import ctypes

class _LandlockRulesetAttr(ctypes.Structure):
    _fields_ = [
        ("handled_access_fs", ctypes.c_uint64),
        ("handled_access_net", ctypes.c_uint64),
    ]

class _LandlockPathBeneath(ctypes.Structure):
    _fields_ = [
        ("allowed_access", ctypes.c_uint64),
        ("parent_fd", ctypes.c_int),
    ]


def _syscall(cmd: int, *args) -> int:
    """Wrapper around libc.syscall()."""
    libc = ctypes.CDLL(None, use_errno=True)
    result = libc.syscall(cmd, *args)
    if result < 0:
        err = ctypes.get_errno()
        raise RuntimeError(f"Landlock syscall {cmd} failed: errno={err}")
    return result


def _add_path_rule(ruleset_fd: int, path: str, access: int):
    """Add a path-beneath rule to the ruleset."""
    fd = os.open(path, os.O_RDONLY | os.O_CLOEXEC)
    try:
        rule = _LandlockPathBeneath(allowed_access=access, parent_fd=fd)
        _syscall(LANDLOCK_ADD_RULE, ruleset_fd,
                 LANDLOCK_RULE_PATH_BENEATH,
                 ctypes.byref(rule), 0)
    finally:
        os.close(fd)


def _get_landlock_abi() -> int:
    try:
        with open("/proc/sys/kernel/landlock/abi") as f:
            return int(f.read().strip())
    except (FileNotFoundError, ValueError):
        return 0


# ──────────────────────────────────────────────
# Landlock sandbox setup
# ──────────────────────────────────────────────

def apply_landlock(workspace_dir: str):
    """
    Restrict the current process (and all future children) via Landlock.

    After this call returns, the process is permanently jailed.
    Must be called BEFORE fork()/execvp().

    Raises RuntimeError if Landlock is present but too old (ABI 1-3).
    Skips silently if Landlock is completely absent (ABI 0) —
    this is common in containers/VMs where CONFIG_SECURITY_LANDLOCK=n.
    """
    abi = _get_landlock_abi()
    if abi == 0:
        # Landlock not compiled into kernel (e.g. container / VM without
        # CONFIG_SECURITY_LANDLOCK). Skip gracefully — no security, but
        # the process can still run.
        print("WARNING: Landlock not available (ABI=0, kernel support missing), "
              "skipping sandbox", file=sys.stderr)
        return
    if abi < 4:
        raise RuntimeError(
            f"Landlock ABI {abi} < 4 (kernel < 6.7). This system requires "
            f"Linux 6.7+ with Landlock enabled for process sandboxing."
        )

    # Resolve the workspace dir to a canonical absolute path
    ws = os.path.realpath(workspace_dir)

    # ── Create ruleset ──────────────────────────────────
    attr = _LandlockRulesetAttr(
        handled_access_fs=MAX_ACCESS_FS,
        handled_access_net=0,
    )
    ruleset_fd = _syscall(LANDLOCK_CREATE_RULESET,
                          ctypes.byref(attr), ctypes.sizeof(attr), 0)

    try:
        # ── Allow workspace (full read/write/execute) ──
        _add_path_rule(ruleset_fd, ws, ACCESS_WORKSPACE)

        # ── Allow system paths needed for claude runtime ──
        # claude is typically at /usr/local/bin/claude or /usr/bin/claude
        # Shared libraries are in /usr/lib, /lib, /lib64
        # System config: /etc (nsswitch, hosts, resolv, ld.so.cache)
        # Device nodes: /dev (null, urandom, random, pts, fd)
        for syspath in ["/usr", "/lib", "/lib64", "/etc", "/dev"]:
            if os.path.isdir(syspath):
                _add_path_rule(ruleset_fd, syspath, ACCESS_RUNTIME)

        # ── Apply restrictions (IRREVERSIBLE) ──
        _syscall(LANDLOCK_RESTRICT_SELF, ruleset_fd, 0)

    finally:
        os.close(ruleset_fd)


# ──────────────────────────────────────────────
# Resource limits
# ──────────────────────────────────────────────

def apply_resource_limits():
    """Apply rlimit before spawning claude subprocess."""
    limits = [
        (resource.RLIMIT_AS, (4 * 1024**3, 4 * 1024**3)),       # 4GB virtual memory
        (resource.RLIMIT_CPU, (600, 600)),                        # 10 min CPU
        (resource.RLIMIT_FSIZE, (200 * 1024**2, 200 * 1024**2)), # 200MB file writes
        (resource.RLIMIT_NOFILE, (256, 256)),                     # 256 open fds
        (resource.RLIMIT_NPROC, (4096, 4096)),                    # 4096 child processes
    ]
    for rsrc, (soft, hard) in limits:
        try:
            resource.setrlimit(rsrc, (soft, hard))
        except (ValueError, resource.error):
            pass  # some limits may be unavailable on some kernels


# ──────────────────────────────────────────────
# PTY streaming (unchanged from original)
# ──────────────────────────────────────────────

def _process_line(line: str, state: dict) -> str:
    line = line.strip()
    if not line:
        return ""
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return ""

    msg_type = obj.get("type", "")

    # ── Handle claude 2.x "assistant" response ──
    # Only used as fallback when stream_event format didn't emit text
    # (claude 2.x outputs BOTH formats; stream_event handles text_delta emission,
    #  assistant type would duplicate it)
    if msg_type == "assistant":
        msg = obj.get("message", {})
        if obj.get("error"):
            # Auth error: no stream_event text was emitted, use fallback
            return "[AuthError: " + str(obj["error"]) + "]\n"
        return ""

    # ── Handle claude 2.x "result" (completion marker) ──
    if msg_type == "result":
        return ""

    # ── Handle claude 2.x "system" events ──
    if msg_type == "system":
        return ""

    # ── Legacy stream_event format (claude 2.x also emits this!) ──
    if msg_type != "stream_event":
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
            state["thinking_marker_emitted"] = False
        elif block_type == "text":
            state["in_text"] = True
            result = _flush_thinking(state)
            return result + "\n\n<!--END_THINKING-->\n\n"
        elif block_type == "tool_use":
            state["in_tool_use"] = True
            name = block.get("name", "tool")
            # Flush any pending thinking before tool
            result = _flush_thinking(state)
            if result:
                result += "\n\n<!--END_THINKING-->\n"
            return result + "\n<!--TOOL name=\"" + name + "\"-->\n"
        return ""

    elif event_type == "content_block_delta":
        delta = event.get("delta", {})
        delta_type = delta.get("type", "")

        if delta_type == "thinking_delta" and state.get("in_thinking"):
            text = delta.get("thinking", "")
            if text:
                state["thinking_buf"].append(text)
                acc = "".join(state["thinking_buf"])
                em = state["thinking_emitted_len"]
                if len(acc) - em >= 250:
                    new_text = acc[em:]
                    state["thinking_emitted_len"] = len(acc)
                    if new_text:
                        # Wrap first batch in <!--THINKING--> marker for frontend timer
                        if not state.get("thinking_marker_emitted"):
                            state["thinking_marker_emitted"] = True
                            return "\n<!--THINKING-->\n" + new_text
                        return new_text
            return ""

        if delta_type == "text_delta":
            state["had_text_delta"] = True
            return delta.get("text", "")

        if delta_type == "input_json_delta" and state.get("in_tool_use"):
            partial = delta.get("partial_json", "")
            if partial:
                return partial
            return ""

        return ""

    elif event_type == "content_block_stop":
        was_tool = state.get("in_tool_use", False)
        state["in_thinking"] = False
        state["in_text"] = False
        state["in_tool_use"] = False
        if was_tool:
            return "\n<!--END_TOOL-->\n"
        return ""

    return ""


def _flush_thinking(state: dict) -> str:
    buf = state.get("thinking_buf")
    if not buf:
        return ""
    acc = "".join(buf)
    em = state["thinking_emitted_len"]
    if em >= len(acc):
        return ""
    new_text = acc[em:]
    state["thinking_emitted_len"] = len(acc)
    # Prepend opening marker on first flush
    if not state.get("thinking_marker_emitted"):
        state["thinking_marker_emitted"] = True
        return "\n<!--THINKING-->\n" + new_text.lstrip()
    return new_text.lstrip()


def _forward_pty_output(fd: int, pid: int) -> None:
    state = {"in_thinking": False, "in_text": False, "thinking_buf": None, "had_text_delta": False, "in_tool_use": False}
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


# ──────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────

def main():
    claude_path = "claude"
    args = sys.argv[1:]
    # Parse optional --claude-path before positional args
    for i, arg in enumerate(args[:]):
        if arg == "--claude-path" and i + 1 < len(args):
            claude_path = args[i + 1]
            # Remove --claude-path and its value from positional args
            args = args[:i] + args[i + 2:]
            break

    if len(args) < 2:
        print(f"Usage: {sys.argv[0]} [--claude-path <path>] <prompt_file> <workspace_dir>", file=sys.stderr)
        sys.exit(1)

    prompt_file = args[0]
    workspace_dir = args[1]

    # Validate paths
    if not os.path.isfile(prompt_file):
        print(f"ERROR: prompt_file not found: {prompt_file}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isdir(workspace_dir):
        print(f"ERROR: workspace_dir not found: {workspace_dir}", file=sys.stderr)
        sys.exit(1)

    # Verify prompt_file is inside workspace_dir
    prompt_real = os.path.realpath(prompt_file)
    ws_real = os.path.realpath(workspace_dir)
    if not prompt_real.startswith(ws_real + "/") and prompt_real != ws_real + "/" + os.path.basename(prompt_real):
        # More robust check: resolve the prompt relative to workspace
        prompt_rel = os.path.relpath(prompt_real, ws_real)
        if prompt_rel.startswith(".."):
            print(f"ERROR: prompt_file must be inside workspace_dir", file=sys.stderr)
            print(f"  prompt:   {prompt_real}", file=sys.stderr)
            print(f"  workspace: {ws_real}", file=sys.stderr)
            sys.exit(1)

    with open(prompt_file, "r", encoding="utf-8") as f:
        prompt = f.read()

    # ── Step 1: Apply resource limits ──
    # Skipped when Landlock is unavailable (ABI=0) to avoid compatibility issues
    # in containers/VMs without full kernel feature support.
    if _get_landlock_abi() > 0:
        apply_resource_limits()

    # ── Step 2: Open PTY before Landlock ──
    # pty.fork() internally does open("/dev/ptmx") which would be
    # blocked by Landlock. So we open the PTY pair first, then
    # set up Landlock, then fork manually.
    try:
        import pty as _pty_module
        master_fd, slave_fd = _pty_module.openpty()
    except OSError as e:
        print(f"ERROR: Could not open PTY: {e}", file=sys.stderr)
        sys.exit(1)

    # ── Step 3: Landlock sandbox (IRREVERSIBLE, Linux 6.7+) ──
    # After this point, the process can only access ws_real + system paths.
    # On macOS/non-Linux, Landlock is not available; skip gracefully.
    # Set SILK_DISABLE_LANDLOCK=true to skip Landlock even on Linux
    #   (useful in dev containers / VMs without Landlock kernel support).
    if sys.platform == 'linux':
        disable_landlock = os.environ.get('SILK_DISABLE_LANDLOCK', '').strip().lower()
        if disable_landlock in ('true', '1', 'yes'):
            print("WARNING: SILK_DISABLE_LANDLOCK is set, skipping sandbox", file=sys.stderr)
        else:
            try:
                apply_landlock(ws_real)
            except RuntimeError as e:
                print(f"FATAL: Landlock setup failed: {e}", file=sys.stderr)
                print(f"HINT: Set SILK_DISABLE_LANDLOCK=true to skip sandbox on systems without Landlock support.", file=sys.stderr)
                os.close(master_fd)
                os.close(slave_fd)
                sys.exit(1)
    else:
        print("WARNING: Landlock not available on this platform, skipping sandbox", file=sys.stderr)

    # ── Step 4: Build claude command ──
    cmd = [
        claude_path,
        "-p", prompt,
        "--session-id", str(uuid.uuid4()),
        "--disallowedTools", "Bash,Write,Edit,ExecuteCommand",
        "--no-chrome",
        "--print",
        "--output-format", "stream-json",
        "--verbose",
        "--include-partial-messages",
    ]

    # Permissions are now controlled by --settings file (claude-strict/settings.json),
    # not command-line flags, so the user can configure allow/deny rules there.

    # ── Step 5: Fork ──
    pid = os.fork()

    if pid == 0:
        # ── Child process ──
        os.close(master_fd)

        # Make the PTY slave the controlling terminal
        os.setsid()

        # Duplicate slave PTY onto stdin/stdout/stderr
        for fd in (0, 1, 2):
            os.dup2(slave_fd, fd)
        if slave_fd > 2:
            os.close(slave_fd)

        # Execute claude CLI (Landlock restrictions survive execve)
        os.execvp(cmd[0], cmd)
        # If execvp returns, it failed
        sys.exit(1)

    # ── Parent process ──
    os.close(slave_fd)

    try:
        _forward_pty_output(master_fd, pid)
    finally:
        try:
            os.close(master_fd)
        except OSError:
            pass
        try:
            os.waitpid(pid, 0)
        except OSError:
            pass


if __name__ == "__main__":
    main()
