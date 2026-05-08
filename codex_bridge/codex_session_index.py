"""Scan ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl and extract metadata.

Returns the list shape used by the cc_bridge SessionManager (sessionId,
workingDir, title, createdAt, lastActivity) so the backend can render
codex sessions identically to claude-code sessions in `_silk/list_local_sessions`.
"""
from __future__ import annotations

import json
import logging
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

logger = logging.getLogger("codex_bridge.session_index")

_DEFAULT_SESSIONS_ROOT = Path.home() / ".codex" / "sessions"
_TITLE_MAX_LEN = 100


def list_local_sessions(
    *,
    sessions_root: Path | None = None,
    max_age_days: int = 30,
) -> list[dict[str, Any]]:
    """Return list of session metadata dicts, sorted by lastActivity desc.

    Each entry: {"sessionId", "workingDir", "title", "createdAt", "lastActivity"}
    """
    root = sessions_root or _DEFAULT_SESSIONS_ROOT
    if not root.exists():
        return []

    cutoff = time.time() - max_age_days * 86400
    out: list[dict[str, Any]] = []

    for jsonl_file in root.rglob("rollout-*.jsonl"):
        try:
            mtime = jsonl_file.stat().st_mtime
            if mtime < cutoff:
                continue
            meta = _parse_rollout_head(jsonl_file)
            if meta is None:
                continue
            meta["lastActivity"] = _iso_from_epoch(mtime)
            out.append(meta)
        except Exception as exc:
            logger.warning("skipping %s: %s", jsonl_file, exc)
            continue

    out.sort(key=lambda s: s.get("lastActivity", ""), reverse=True)
    return out


def _parse_rollout_head(file_path: Path) -> dict[str, Any] | None:
    """Read enough lines to extract sessionId, workingDir, createdAt, title.

    Returns None if the file isn't a valid rollout (no session_meta first line).
    """
    session_id = ""
    cwd = ""
    created_at = ""
    title = ""

    with open(file_path, "r", encoding="utf-8", errors="replace") as fh:
        for i, line in enumerate(fh):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                if i == 0:
                    return None  # first line not JSON → not a rollout
                continue
            t = obj.get("type")
            payload = obj.get("payload") or {}
            if t == "session_meta":
                session_id = payload.get("id", "")
                cwd = payload.get("cwd", "")
                created_at = payload.get("timestamp", "")
                if not session_id:
                    return None  # malformed
            elif t == "event_msg" and payload.get("type") == "user_message":
                msg = payload.get("message", "") or ""
                title = msg[:_TITLE_MAX_LEN]
                break  # we have everything; stop reading

            # Soft cap: don't read more than ~200 lines looking for first user msg
            if i > 200:
                break

    if not session_id:
        return None
    return {
        "sessionId": session_id,
        "workingDir": cwd,
        "title": title,
        "createdAt": created_at,
    }


def _iso_from_epoch(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, tz=timezone.utc).replace(microsecond=0).isoformat()


def find_session_file(
    thread_id: str,
    *,
    sessions_root: Path | None = None,
) -> Path | None:
    """Locate the rollout-*.jsonl file whose session_meta.id matches *thread_id*.

    Supports both exact match and prefix match. When *thread_id* is a prefix
    of the actual thread id, returns the matching rollout file.
    If multiple sessions match a prefix, returns the most recently modified.
    Returns None when no rollout matches.
    """
    if not thread_id:
        return None
    root = sessions_root or _DEFAULT_SESSIONS_ROOT
    if not root.exists():
        return None

    # Fast path: filename contains the thread_id (exact match)
    for candidate in root.rglob(f"rollout-*-{thread_id}.jsonl"):
        return candidate

    # Prefix path: filename contains the prefix
    prefix_candidates: list[tuple[float, Path]] = []
    for candidate in root.rglob(f"rollout-*-{thread_id}*.jsonl"):
        try:
            mtime = candidate.stat().st_mtime
            prefix_candidates.append((mtime, candidate))
        except Exception:
            continue

    if prefix_candidates:
        prefix_candidates.sort(key=lambda t: t[0], reverse=True)
        return prefix_candidates[0][1]

    # Slow path: parse session_meta head — check both exact and prefix
    slow_candidates: list[tuple[float, Path]] = []
    for candidate in root.rglob("rollout-*.jsonl"):
        try:
            meta = _parse_rollout_head(candidate)
        except Exception:
            continue
        sid = meta.get("sessionId", "") if meta else ""
        if sid == thread_id or sid.startswith(thread_id):
            try:
                mtime = candidate.stat().st_mtime
                slow_candidates.append((mtime, candidate))
            except Exception:
                slow_candidates.append((0, candidate))

    if slow_candidates:
        slow_candidates.sort(key=lambda t: t[0], reverse=True)
        return slow_candidates[0][1]

    return None
