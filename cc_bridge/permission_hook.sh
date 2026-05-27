#!/bin/bash
# Claude Code PreToolUse Hook for Silk cc_bridge.
#
# Called by Claude CLI before each tool invocation.
# Reads permission request JSON from stdin, forwards to local PermissionServer,
# blocks until response.
#
# Exit codes:
#   0 = allow (reason on stdout for Claude to read)
#   2 = deny  (reason on stderr for Claude to see as error)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="${SILK_BRIDGE_DATA_DIR:-${SCRIPT_DIR}/data}"
LOG_FILE="${DATA_DIR}/silk_hook.log"

_log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG_FILE" 2>/dev/null || true
}

# Read stdin (permission request JSON)
INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tool_name','unknown'))" 2>/dev/null) || TOOL_NAME="unknown"
SESSION_ID=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('session_id',''))" 2>/dev/null) || SESSION_ID=""

_log "=== Permission request: tool=${TOOL_NAME} session=${SESSION_ID:0:8} ==="

# Whitelist: read-only and internal tools pass through
case "$TOOL_NAME" in
    Read|Glob|Grep|TodoWrite|WebSearch|WebFetch)
        _log "Read-only tool (${TOOL_NAME}), auto-allow"
        exit 0
        ;;
esac

# Read port file; missing = bridge not running = auto-allow
PORT_FILE="${DATA_DIR}/.silk_perm_port"
if [ ! -f "$PORT_FILE" ]; then
    _log "Port file not found (${PORT_FILE}), auto-allow"
    exit 0
fi
PORT=$(cat "$PORT_FILE")
_log "Port file found: port=${PORT}"

# Read timeout
TIMEOUT_FILE="${DATA_DIR}/.silk_perm_timeout"
if [ -f "$TIMEOUT_FILE" ]; then
    PERM_TIMEOUT=$(cat "$TIMEOUT_FILE")
else
    PERM_TIMEOUT=300
fi
CURL_MAX_TIME=$((PERM_TIMEOUT + 10))
_log "Timeout: perm=${PERM_TIMEOUT}s curl_max=${CURL_MAX_TIME}s"

# Forward to permission server (blocking)
CURL_ERR_FILE="${DATA_DIR}/silk_hook_curl_err.log"
CURL_EXIT=0
RESPONSE=$(echo "$INPUT" | curl -sS --noproxy 127.0.0.1 --max-time "$CURL_MAX_TIME" \
    -X POST \
    -H "Content-Type: application/json" \
    -d @- \
    "http://127.0.0.1:${PORT}/permission-request" 2>>"$CURL_ERR_FILE") || CURL_EXIT=$?

if [ -z "$RESPONSE" ]; then
    if [ $CURL_EXIT -eq 28 ]; then
        _log "curl timeout (exit=28), denying"
        echo "权限确认等待超时（${PERM_TIMEOUT}s），操作已拒绝" >&2
        exit 2
    fi
    _log "Server unreachable (exit=${CURL_EXIT}), denying (fail-close)"
    echo "权限服务器不可达（端口 ${PORT}），操作已拒绝" >&2
    exit 2
fi

_log "Response: ${RESPONSE}"

# Parse decision
DECISION=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('decision','deny'))" 2>/dev/null) || DECISION="deny"
REASON=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('reason',''))" 2>/dev/null) || REASON=""

if [ "$DECISION" = "allow" ]; then
    _log "Decision: allow (reason=${REASON})"
    if [ -n "$REASON" ]; then
        echo "$REASON"
    fi
    exit 0
else
    _log "Decision: deny (reason=${REASON})"
    if [ -n "$REASON" ]; then
        echo "$REASON" >&2
    else
        echo "用户拒绝了此操作" >&2
    fi
    exit 2
fi
