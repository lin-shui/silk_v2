"""Shared filesystem directory-listing helper used by acp_adapter.py
(ACP _silk/list_dir extension).

Returns a dict matching the `dir_listing` payload (minus the
``type`` and ``requestId`` envelope fields which the caller adds back when
needed). The ACP path uses the dict as-is — JSON-RPC handles its own envelope.
"""

from __future__ import annotations

import os
from typing import Any


def list_directory(
    path: str,
    show_hidden: bool = False,
    max_entries: int = 500,
) -> dict[str, Any]:
    """List subdirectories under ``path``.

    Response shape::

        {
          "success": bool,
          "path": str,                 # resolved absolute path (on success)
          "parent": str | None,        # parent path, None if at filesystem root
          "segments": [str, ...],      # path components (first is "/" on unix
                                       # or "C:\\" on windows)
          "separator": str,            # os.sep ("/" or "\\")
          "entries": [{"name": str, "isDir": True}, ...],
          "truncated": bool,           # True if more than max_entries existed
          "error": str | None,         # only set when success=False
        }
    """
    try:
        resolved = os.path.realpath(os.path.expanduser(path))
    except OSError as exc:
        return {
            "success": False,
            "path": path,
            "error": f"解析路径失败: {exc.strerror or exc}（可能包含循环符号链接或无权访问）",
        }
    except Exception as exc:
        return {
            "success": False,
            "path": path,
            "error": f"解析路径失败: {exc}",
        }

    if not os.path.isdir(resolved):
        return {
            "success": False,
            "path": resolved,
            "error": f"目录不存在: {resolved}",
        }

    try:
        names = os.listdir(resolved)
    except PermissionError as exc:
        return {
            "success": False,
            "path": resolved,
            "error": f"权限不足: {exc}",
        }
    except OSError as exc:
        return {
            "success": False,
            "path": resolved,
            "error": f"读取目录失败: {exc}",
        }

    entries: list[dict[str, Any]] = []
    for name in names:
        if not show_hidden and name.startswith("."):
            continue
        full = os.path.join(resolved, name)
        try:
            if os.path.isdir(full):
                entries.append({"name": name, "isDir": True})
        except OSError:
            continue

    entries.sort(key=lambda e: e["name"].lower())
    truncated = len(entries) > max_entries
    if truncated:
        entries = entries[:max_entries]

    parent = os.path.dirname(resolved)
    parent_out: str | None = parent if parent != resolved else None

    segments: list[str] = []
    if os.name == "nt":
        drive, rest = os.path.splitdrive(resolved)
        if drive:
            segments.append(drive + os.sep)
        segments.extend(p for p in rest.split(os.sep) if p)
    else:
        segments.append("/")
        segments.extend(p for p in resolved.split("/") if p)

    return {
        "success": True,
        "path": resolved,
        "parent": parent_out,
        "segments": segments,
        "separator": os.sep,
        "entries": entries,
        "truncated": truncated,
    }
