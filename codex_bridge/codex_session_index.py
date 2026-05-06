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
