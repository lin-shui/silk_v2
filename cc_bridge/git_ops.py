"""Git working-tree inspection helpers for the Silk ACP bridge.

Computes "working tree vs HEAD" status and per-file unified diffs, mirroring the
VSCode Source Control view. Used by the _silk/git_status and _silk/git_diff
extensions. All functions run git in the given cwd (the bridge session's cwd).
"""
from __future__ import annotations

import asyncio
import os
from typing import Any

MAX_PATCH_BYTES = 256 * 1024

# porcelain status code (first non-space char of XY) -> normalized wire word
_STATUS_WORDS = {
    "M": "modified",
    "A": "added",
    "D": "deleted",
    "R": "renamed",
    "C": "copied",
    "U": "unmerged",
    "T": "type_changed",
    "?": "untracked",
}


async def _run_git(args: list[str], cwd: str) -> tuple[int, str, str]:
    # core.quotePath=false: keep non-ASCII (e.g. Chinese) paths unescaped so they match
    # --numstat keys and the real filesystem. LANG=C: stable English git strings
    # (e.g. "Binary files") regardless of the bridge host locale.
    proc = await asyncio.create_subprocess_exec(
        "git", "-c", "core.quotePath=false", *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd=cwd,
        env={**os.environ, "LANG": "C", "LC_ALL": "C"},
    )
    out, err = await proc.communicate()
    code = proc.returncode if proc.returncode is not None else -1
    return code, out.decode("utf-8", errors="replace"), err.decode("utf-8", errors="replace")


async def _is_git_repo(cwd: str) -> bool:
    if not cwd or not os.path.isdir(cwd):
        return False
    code, out, _ = await _run_git(["rev-parse", "--is-inside-work-tree"], cwd)
    return code == 0 and out.strip() == "true"


async def _branch_and_head(cwd: str) -> tuple[str, str]:
    """(branch, shortHead) for the working tree. branch is "" on detached HEAD;
    shortHead is the abbreviated commit. Any git error degrades to "" so callers
    can treat both as best-effort metadata."""
    _, branch, _ = await _run_git(["branch", "--show-current"], cwd)
    code, head, _ = await _run_git(["rev-parse", "--short", "HEAD"], cwd)
    return branch.strip(), (head.strip() if code == 0 else "")


def _count_untracked(abs_path: str) -> tuple[int, int, bool]:
    """(additions, deletions, binary) for an untracked file: whole file is added."""
    try:
        with open(abs_path, "rb") as fh:
            data = fh.read()
    except OSError:
        return (0, 0, False)
    if b"\x00" in data:
        return (0, 0, True)
    if not data:
        return (0, 0, False)
    lines = data.count(b"\n") + (0 if data.endswith(b"\n") else 1)
    return (lines, 0, False)


async def git_status(cwd: str) -> dict[str, Any]:
    if not await _is_git_repo(cwd):
        return {"success": True, "isGitRepo": False, "cwd": cwd, "files": []}

    branch, head = await _branch_and_head(cwd)

    # ± counts for tracked changes: lines look like "adds\tdels\tpath"; binary -> "-\t-\tpath"
    counts: dict[str, tuple[int, int, bool]] = {}
    code, out, _ = await _run_git(["diff", "--numstat", "HEAD"], cwd)
    if code == 0:
        for line in out.splitlines():
            parts = line.split("\t")
            if len(parts) >= 3:
                a, d, path = parts[0], parts[1], "\t".join(parts[2:])
                binary = a == "-" and d == "-"
                counts[path] = (0 if binary else int(a or 0), 0 if binary else int(d or 0), binary)

    files: list[dict[str, Any]] = []
    _, out, _ = await _run_git(["status", "--porcelain"], cwd)
    for line in out.splitlines():
        if len(line) < 4:
            continue
        xy = line[:2]
        rest = line[3:]
        old_path = None
        path = rest
        if " -> " in rest:  # rename / copy: "old -> new"
            old_path, path = rest.split(" -> ", 1)
        if "?" in xy:
            word = "untracked"
        else:
            code_char = (xy.strip()[:1] or xy[0])
            word = _STATUS_WORDS.get(code_char, "modified")
        add, dele, binary = counts.get(path, (0, 0, False))
        if word == "untracked":
            add, dele, binary = _count_untracked(os.path.join(cwd, path))
        files.append({
            "path": path,
            "oldPath": old_path,
            "status": word,
            "additions": add,
            "deletions": dele,
            "binary": binary,
        })
    return {"success": True, "isGitRepo": True, "cwd": cwd,
            "branch": branch, "head": head, "files": files}


async def git_diff(cwd: str, path: str) -> dict[str, Any]:
    if not await _is_git_repo(cwd):
        return {"success": False, "isGitRepo": False, "filePath": path,
                "patch": "", "isBinary": False, "truncated": False, "error": "not a git repository"}

    # tracked changes vs HEAD
    _, out, _ = await _run_git(["diff", "HEAD", "--", path], cwd)
    if not out.strip():
        # likely untracked: show the whole file as added (--no-index exits 1 on differences)
        _, out2, _ = await _run_git(["diff", "--no-index", "--", os.devnull, path], cwd)
        if out2.strip():
            out = out2

    is_binary = "Binary files" in out or "GIT binary patch" in out
    truncated = False
    encoded = out.encode("utf-8")
    if len(encoded) > MAX_PATCH_BYTES:
        out = encoded[:MAX_PATCH_BYTES].decode("utf-8", errors="ignore")
        truncated = True

    return {
        "success": True,
        "isGitRepo": True,
        "filePath": path,
        "patch": "" if is_binary else out,
        "isBinary": is_binary,
        "truncated": truncated,
    }
