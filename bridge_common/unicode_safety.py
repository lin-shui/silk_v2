"""Helpers for safely crossing JSON and UTF-8 subprocess boundaries.

JSON permits escaped UTF-16 surrogate code units, while Python keeps an
isolated surrogate in the resulting ``str``.  Such a value cannot be encoded
as UTF-8 and would otherwise make an otherwise valid ACP prompt fail.
"""

from __future__ import annotations

from typing import Any


_REPLACEMENT = "\ufffd"


def sanitize_text(value: str) -> str:
    """Return *value* with surrogate pairs decoded and lone surrogates replaced.

    Valid pairs (for example an escaped emoji from JSON) are preserved as the
    corresponding Unicode scalar.  An unpaired high/low surrogate is malformed
    input; replacing it keeps the protocol available without changing normal
    Chinese, emoji, or other Unicode text.
    """

    result: list[str] = []
    index = 0
    while index < len(value):
        code = ord(value[index])
        if 0xD800 <= code <= 0xDBFF:
            if index + 1 < len(value):
                low = ord(value[index + 1])
                if 0xDC00 <= low <= 0xDFFF:
                    result.append(chr(0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)))
                    index += 2
                    continue
            result.append(_REPLACEMENT)
        elif 0xDC00 <= code <= 0xDFFF:
            result.append(_REPLACEMENT)
        else:
            result.append(value[index])
        index += 1
    return "".join(result)


def sanitize_json_value(value: Any) -> Any:
    """Recursively sanitize strings in a JSON-compatible value."""

    if isinstance(value, str):
        return sanitize_text(value)
    if isinstance(value, list):
        return [sanitize_json_value(item) for item in value]
    if isinstance(value, tuple):
        return tuple(sanitize_json_value(item) for item in value)
    if isinstance(value, dict):
        return {
            sanitize_text(key) if isinstance(key, str) else key: sanitize_json_value(item)
            for key, item in value.items()
        }
    return value
