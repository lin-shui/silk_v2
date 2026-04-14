#!/bin/bash
# ============================================================
# CC Bridge 管理脚本
# ============================================================
# 用法:
#   ./bridge.sh start   - 后台启动 Bridge
#   ./bridge.sh stop    - 停止 Bridge
#   ./bridge.sh restart - 重启 Bridge
#   ./bridge.sh status  - 查看 Bridge 状态
#   ./bridge.sh logs    - 查看日志 (tail -f)
# ============================================================

BRIDGE_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$BRIDGE_DIR/.bridge.pid"
LOG_FILE="$BRIDGE_DIR/bridge.log"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ---- 读取参数 ----
# 优先从 .env 读取，也支持环境变量覆盖
if [ -f "$BRIDGE_DIR/.env" ]; then
    # shellcheck disable=SC1091
    source "$BRIDGE_DIR/.env"
fi

BRIDGE_SERVER="${BRIDGE_SERVER:-}"
BRIDGE_TOKEN="${BRIDGE_TOKEN:-}"
BRIDGE_WORKING_DIR="${BRIDGE_WORKING_DIR:-$(pwd)}"
BRIDGE_LOG_LEVEL="${BRIDGE_LOG_LEVEL:-INFO}"

# ---- 辅助函数 ----

_check_config() {
    if [ -z "$BRIDGE_SERVER" ] || [ -z "$BRIDGE_TOKEN" ]; then
        echo -e "${RED}错误: 缺少必要配置${NC}"
        echo ""
        echo "请通过以下任一方式配置:"
        echo ""
        echo "  1) 创建 $BRIDGE_DIR/.env 文件:"
        echo "     BRIDGE_SERVER=<silk后端地址:端口>"
        echo "     BRIDGE_TOKEN=<你的Token>"
        echo "     # BRIDGE_WORKING_DIR=/path/to/workdir  (可选)"
        echo "     # BRIDGE_LOG_LEVEL=INFO                (可选)"
        echo ""
        echo "  2) 设置环境变量:"
        echo "     export BRIDGE_SERVER=localhost:8006"
        echo "     export BRIDGE_TOKEN=your-token"
        echo ""
        return 1
    fi
    return 0
}

_is_running() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            return 0
        else
            # PID 文件存在但进程已死，清理
            rm -f "$PID_FILE"
        fi
    fi
    return 1
}

_get_pid() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE"
    fi
}

# ---- 命令 ----

do_start() {
    if _is_running; then
        echo -e "${YELLOW}Bridge 已在运行中 (PID: $(_get_pid))${NC}"
        return 0
    fi

    _check_config || return 1

    echo -e "${BLUE}启动 CC Bridge...${NC}"
    echo -e "  Server:      ${GREEN}$BRIDGE_SERVER${NC}"
    echo -e "  Working dir: $BRIDGE_WORKING_DIR"
    echo -e "  Log level:   $BRIDGE_LOG_LEVEL"
    echo -e "  Log file:    $LOG_FILE"

    nohup python3 "$BRIDGE_DIR/bridge_agent.py" \
        --server "$BRIDGE_SERVER" \
        --token "$BRIDGE_TOKEN" \
        --working-dir "$BRIDGE_WORKING_DIR" \
        --log-level "$BRIDGE_LOG_LEVEL" \
        >> "$LOG_FILE" 2>&1 &

    local pid=$!
    echo "$pid" > "$PID_FILE"

    # 等待一小会确认进程存活
    sleep 1
    if kill -0 "$pid" 2>/dev/null; then
        echo -e "${GREEN}Bridge 已启动 (PID: $pid)${NC}"
    else
        echo -e "${RED}Bridge 启动失败，请查看日志: $LOG_FILE${NC}"
        rm -f "$PID_FILE"
        return 1
    fi
}

do_stop() {
    if ! _is_running; then
        echo -e "${YELLOW}Bridge 未在运行${NC}"
        return 0
    fi

    local pid
    pid=$(_get_pid)
    echo -e "${BLUE}停止 Bridge (PID: $pid)...${NC}"

    # 发送 SIGTERM，让 bridge_agent.py 优雅关闭
    kill "$pid" 2>/dev/null

    # 等待最多 10 秒
    local count=0
    while kill -0 "$pid" 2>/dev/null && [ $count -lt 10 ]; do
        sleep 1
        count=$((count + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        echo -e "${YELLOW}进程未响应 SIGTERM，强制终止...${NC}"
        kill -9 "$pid" 2>/dev/null
    fi

    rm -f "$PID_FILE"
    echo -e "${GREEN}Bridge 已停止${NC}"
}

do_restart() {
    do_stop
    sleep 1
    do_start
}

do_status() {
    if _is_running; then
        local pid
        pid=$(_get_pid)
        echo -e "${GREEN}Bridge 运行中 (PID: $pid)${NC}"
        if [ -n "$BRIDGE_SERVER" ]; then
            echo -e "  Server: $BRIDGE_SERVER"
        fi
    else
        echo -e "${YELLOW}Bridge 未运行${NC}"
    fi
}

do_logs() {
    if [ ! -f "$LOG_FILE" ]; then
        echo -e "${YELLOW}日志文件不存在: $LOG_FILE${NC}"
        return 1
    fi
    tail -f "$LOG_FILE"
}

# ---- 主入口 ----

case "${1:-}" in
    start)
        do_start
        ;;
    stop)
        do_stop
        ;;
    restart)
        do_restart
        ;;
    status|s)
        do_status
        ;;
    logs|l)
        do_logs
        ;;
    *)
        echo ""
        echo -e "${BLUE}CC Bridge 管理脚本${NC}"
        echo ""
        echo "用法: $0 <命令>"
        echo ""
        echo "命令:"
        echo "  start      后台启动 Bridge"
        echo "  stop       停止 Bridge"
        echo "  restart    重启 Bridge"
        echo "  status, s  查看运行状态"
        echo "  logs, l    查看日志 (tail -f)"
        echo ""
        echo "配置: 在 $BRIDGE_DIR/.env 中设置 BRIDGE_SERVER 和 BRIDGE_TOKEN"
        echo ""
        ;;
esac
