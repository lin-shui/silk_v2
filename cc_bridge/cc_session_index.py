"""Scan ~/.claude/projects/<encoded-project-path>/<uuid>.jsonl and extract metadata.

Claude Code CLI stores session logs as JSONL files:
    ~/.claude/projects/<encoded-project-path>/<uuid>.jsonl

where <encoded-project-path> encodes `/` as `-` and the filename stem IS
the session UUID.  Sub-agent logs live in <uuid>/subagents/ and must be
excluded.

Returns the same metadata shape as codex_bridge.codex_session_index (sessionId,
workingDir, title, createdAt, lastActivity) so the backend can render
Claude Code sessions in `_silk/list_local_sessions`.
"""
from __future__ import annotations

import json
import logging
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

logger = logging.getLogger("cc_bridge.session_index")

_DEFAULT_PROJECTS_ROOT = Path.home() / ".claude" / "projects"
_TITLE_MAX_LEN = 100


def list_local_sessions(
    *,
    projects_root: Path | None = None,
    max_age_days: int = 30,
) -> list[dict[str, Any]]:
    """Return list of session metadata dicts, sorted by lastActivity desc.

    Each entry: {"sessionId", "workingDir", "title", "createdAt", "lastActivity"}
    """
    root = projects_root or _DEFAULT_PROJECTS_ROOT
    if not root.exists():
        return []

    cutoff = time.time() - max_age_days * 86400
    out: list[dict[str, Any]] = []

    for project_dir in root.iterdir():
        if not project_dir.is_dir():
            continue
        # Use glob (NOT rglob) to stay at the top level — excludes subagent files
        for jsonl_file in project_dir.glob("*.jsonl"):
            try:
                mtime = jsonl_file.stat().st_mtime
                if mtime < cutoff:
                    continue
                meta = _parse_session_head(jsonl_file)
                if meta is None:
                    continue
                meta["lastActivity"] = _iso_from_epoch(mtime)
                out.append(meta)
            except Exception as exc:
                logger.warning("skipping %s: %s", jsonl_file, exc)
                continue

    out.sort(key=lambda s: s.get("lastActivity", ""), reverse=True)
    return out


def find_session_file(
    session_id_or_prefix: str,
    *,
    projects_root: Path | None = None,
) -> dict[str, Any] | None:
    """Locate a Claude Code session file by UUID or prefix.

    Returns {"sessionId", "workingDir", "title", "createdAt", "lastActivity", "path"}
    or None when no match is found.  When the prefix matches multiple files,
    the most recently modified one wins.
    """
    if not session_id_or_prefix:
        return None
    root = projects_root or _DEFAULT_PROJECTS_ROOT
    if not root.exists():
        return None

    prefix = session_id_or_prefix
    candidates: list[tuple[float, Path]] = []

    for project_dir in root.iterdir():
        if not project_dir.is_dir():
            continue
        for candidate in project_dir.glob(f"{prefix}*.jsonl"):
            try:
                mtime = candidate.stat().st_mtime
                candidates.append((mtime, candidate))
            except Exception:
                continue

    # Sort by mtime desc so the most recent match is tried first
    candidates.sort(key=lambda t: t[0], reverse=True)

    for mtime, candidate in candidates:
        try:
            meta = _parse_session_head(candidate)
        except Exception:
            continue
        if meta is not None:
            meta["lastActivity"] = _iso_from_epoch(mtime)
            meta["path"] = candidate
            return meta

    return None


def _parse_session_head(file_path: Path) -> dict[str, Any] | None:
    """Read the head of a Claude Code session .jsonl to extract metadata.

    Extracts:
      - sessionId  — from the first queue-operation/enqueue line
      - workingDir — from the first line containing a "cwd" key
      - title      — from the enqueue's "content" or first "user" message content
      - createdAt  — from the first timestamp found

    Falls back to the filename stem as sessionId if no sessionId is found
    in the file content.  Stops reading after 200 lines.

    Returns {"sessionId", "workingDir", "title", "createdAt"} or None.
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
                    return None  # first line not JSON -> not a valid session
                continue

            obj_type = obj.get("type")

            # Extract sessionId and createdAt from first enqueue
            if (
                obj_type == "queue-operation"
                and obj.get("operation") == "enqueue"
                and not session_id
            ):
                session_id = obj.get("sessionId", "")
                created_at = obj.get("timestamp", "")
                content = obj.get("content")
                if content:
                    title = content[:_TITLE_MAX_LEN]

            # Extract cwd from first line that has it
            if not cwd and "cwd" in obj:
                cwd = obj["cwd"]

            # Extract title from first user message if not already set
            if not title and obj_type == "user":
                msg = obj.get("message")
                if isinstance(msg, dict):
                    msg_content = msg.get("content", "")
                elif isinstance(msg, str):
                    msg_content = msg
                else:
                    msg_content = ""
                if msg_content:
                    title = msg_content[:_TITLE_MAX_LEN]

            # If we have all fields, stop early
            if session_id and cwd and title and created_at:
                break

            # Soft cap: don't read more than ~200 lines
            if i > 200:
                break

    # Fallback: use filename stem as sessionId
    if not session_id:
        session_id = file_path.stem

    if not session_id:
        return None

    return {
        "sessionId": session_id,
        "workingDir": cwd,
        "title": title,
        "createdAt": created_at,
    }


def _iso_from_epoch(epoch: float) -> str:
    """Convert epoch timestamp to ISO 8601 string (UTC, no microseconds)."""
    return datetime.fromtimestamp(epoch, tz=timezone.utc).replace(microsecond=0).isoformat()
