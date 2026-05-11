"""Filesystem listing helper for codex_bridge `_silk/list_dir` extension.

Returns the same response shape as cc_bridge/fs_listing.py so the backend
parses it identically across agent types.
"""
from __future__ import annotations

import logging
import os
from typing import Any

logger = logging.getLogger("codex_bridge.fs_listing")

_MAX_ENTRIES = 1000  # safety cap; UI truncates anyway


def list_directory(path: str, show_hidden: bool) -> dict[str, Any]:
    """List immediate children of `path`.

    Returns dict shape:
        {
          "success": bool,
          "path": str,                 # canonical absolute path
          "parent": str | None,         # parent path or None at root
          "segments": list[str],        # path split by separator (no leading empty)
          "separator": str,
          "entries": list[{"name": str, "isDir": bool, "size": int}],
          "truncated": bool,
          "error": str (only on failure),
        }
    """
    sep = os.sep
    try:
        if not os.path.exists(path):
            return {"success": False, "error": f"path not found: {path}"}
        if not os.path.isdir(path):
            return {"success": False, "error": f"not a directory: {path}"}
        canonical = os.path.realpath(path)
        parent = os.path.dirname(canonical)
        if parent == canonical:
            parent = None  # at filesystem root

        segments = [s for s in canonical.split(sep) if s]

        try:
            names = os.listdir(canonical)
        except PermissionError as e:
            return {"success": False, "error": f"permission denied: {e}"}

        if not show_hidden:
            names = [n for n in names if not n.startswith(".")]

        truncated = False
        if len(names) > _MAX_ENTRIES:
            names = sorted(names)[:_MAX_ENTRIES]
            truncated = True
        else:
            names.sort()

        entries: list[dict[str, Any]] = []
        for name in names:
            full = os.path.join(canonical, name)
            try:
                is_dir = os.path.isdir(full)
                size = os.path.getsize(full) if not is_dir else 0
            except OSError:
                # Permission / broken symlink — skip but keep listing
                continue
            entries.append({"name": name, "isDir": is_dir, "size": size})

        return {
            "success": True,
            "path": canonical,
            "parent": parent,
            "segments": segments,
            "separator": sep,
            "entries": entries,
            "truncated": truncated,
        }
    except Exception as exc:
        logger.exception("list_directory failed for %s", path)
        return {"success": False, "error": str(exc)}
