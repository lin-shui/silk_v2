#!/usr/bin/env python3
"""
Landlock sandbox launcher for the DeepSeek Harness (dsh) runtime.

Usage: dsh_sandbox.py <workspace_dir> <runtime_root> -- <runtime argv...>

  workspace_dir  Session workspace; the runtime gets full read/write/execute here
                 (chat history, KB manifest, .dsh_sessions JSONL, uploaded files).
  runtime_root   dsh runtime root (harness repo or bundled exe dir); read+execute
                 only (node binary, node_modules incl. ripgrep, cordis.yml).

The runtime keeps stdio inherited, so the Kotlin DshSdkClient can talk NDJSON
JSON-RPC over stdin/stdout exactly as before. Landlock is applied before exec;
if the kernel lacks Landlock (ABI 0, e.g. container without
CONFIG_SECURITY_LANDLOCK) the launcher warns and runs unsandboxed, matching the
behavior of backend/scripts/pty_chat.py.
"""

import ctypes
import os
import resource
import sys

# ──────────────────────────────────────────────
# Landlock constants (Linux <linux/landlock.h>)
# ──────────────────────────────────────────────
LANDLOCK_CREATE_RULESET = 444
LANDLOCK_ADD_RULE = 445
LANDLOCK_RESTRICT_SELF = 446

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
LANDLOCK_ACCESS_FS_REFER = 1 << 13
LANDLOCK_ACCESS_FS_TRUNCATE = 1 << 14

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

# Runtime (node + deps + cordis.yml): read/execute only
ACCESS_RUNTIME = (
    LANDLOCK_ACCESS_FS_READ_FILE |
    LANDLOCK_ACCESS_FS_READ_DIR |
    LANDLOCK_ACCESS_FS_EXECUTE
)

# Session workspace: full read/write/execute
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
    libc = ctypes.CDLL(None, use_errno=True)
    result = libc.syscall(cmd, *args)
    if result < 0:
        err = ctypes.get_errno()
        raise RuntimeError(f"Landlock syscall {cmd} failed: errno={err}")
    return result


def _add_path_rule(ruleset_fd: int, path: str, access: int):
    fd = os.open(path, os.O_RDONLY | os.O_CLOEXEC)
    try:
        rule = _LandlockPathBeneath(allowed_access=access, parent_fd=fd)
        _syscall(LANDLOCK_ADD_RULE, ruleset_fd, LANDLOCK_RULE_PATH_BENEATH, ctypes.byref(rule), 0)
    finally:
        os.close(fd)


def _get_landlock_abi() -> int:
    try:
        with open("/proc/sys/kernel/landlock/abi") as f:
            return int(f.read().strip())
    except (FileNotFoundError, ValueError):
        return 0


def _probe_landlock() -> bool:
    """/proc ABI 不可读（容器屏蔽）时，用 syscall 探测内核是否支持 Landlock。
    只创建 ruleset，不应用任何限制，探测进程不受影响。"""
    try:
        attr = _LandlockRulesetAttr(handled_access_fs=MAX_ACCESS_FS, handled_access_net=0)
        fd = _syscall(LANDLOCK_CREATE_RULESET, ctypes.byref(attr), ctypes.sizeof(attr), 0)
        os.close(fd)
        return True
    except RuntimeError:
        return False


def apply_landlock(workspace_dir: str, runtime_root: str):
    abi = _get_landlock_abi()
    if abi == 0:
        if not _probe_landlock():
            print(
                "WARNING: Landlock unavailable (kernel lacks the feature), "
                "running dsh runtime unsandboxed",
                file=sys.stderr,
            )
            return
        # /proc 被容器屏蔽但内核支持：继续走 syscall；restrict_self 失败时再降级
    elif abi < 4:
        raise RuntimeError(
            f"Landlock ABI {abi} < 4 (kernel < 6.7). dsh runtime sandbox requires Linux 6.7+."
        )

    ws = os.path.realpath(workspace_dir)
    rt = os.path.realpath(runtime_root)
    attr = _LandlockRulesetAttr(handled_access_fs=MAX_ACCESS_FS, handled_access_net=0)
    ruleset_fd = _syscall(LANDLOCK_CREATE_RULESET, ctypes.byref(attr), ctypes.sizeof(attr), 0)
    try:
        _add_path_rule(ruleset_fd, ws, ACCESS_WORKSPACE)
        _add_path_rule(ruleset_fd, rt, ACCESS_RUNTIME)
        # System paths needed by node/dsh: shared libs, DNS/nsswitch, device nodes,
        # and /proc (node/V8 reads process metadata).
        for syspath in ["/usr", "/lib", "/lib64", "/etc", "/dev", "/proc"]:
            if os.path.isdir(syspath):
                _add_path_rule(ruleset_fd, syspath, ACCESS_RUNTIME)
        try:
            _syscall(LANDLOCK_RESTRICT_SELF, ruleset_fd, 0)
        except RuntimeError as e:
            print(
                f"WARNING: Landlock restrict_self failed ({e}); running dsh runtime "
                "unsandboxed (container seccomp or kernel.landlock_restrict_self sysctl?)",
                file=sys.stderr,
            )
            return
    finally:
        os.close(ruleset_fd)


def apply_resource_limits():
    """Limits for a Node runtime (V8 + ripgrep spawns).
    No RLIMIT_AS: tsx/esbuild WebAssembly instantiation reserves large virtual
    address space and fails under an address-space cap."""
    limits = [
        (resource.RLIMIT_CPU, (900, 900)),                        # 15 min CPU
        (resource.RLIMIT_FSIZE, (200 * 1024**2, 200 * 1024**2)), # 200MB file writes
        (resource.RLIMIT_NOFILE, (1024, 1024)),                   # 1024 open fds
        (resource.RLIMIT_NPROC, (4096, 4096)),                    # 4096 child processes
    ]
    for rsrc, (soft, hard) in limits:
        try:
            resource.setrlimit(rsrc, (soft, hard))
        except (ValueError, resource.error):
            pass


def main():
    if len(sys.argv) < 5 or sys.argv[3] != "--":
        print(
            "Usage: dsh_sandbox.py <workspace_dir> <runtime_root> -- <runtime argv...>",
            file=sys.stderr,
        )
        sys.exit(2)
    workspace_dir, runtime_root = sys.argv[1], sys.argv[2]
    cmd = sys.argv[4:]

    apply_landlock(workspace_dir, runtime_root)
    apply_resource_limits()
    os.execvp(cmd[0], cmd)
    sys.exit(127)


if __name__ == "__main__":
    main()
