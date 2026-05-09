#!/bin/bash

# ============================================================
# Silk Chat - @silk (Claude CLI) 部署脚本
# ============================================================
# 用法:
#   ./silk-chat.sh build           - 构建 WebApp 前端
#   ./silk-chat.sh build-backend   - 构建后端 shadowJar
#   ./silk-chat.sh build-apk       - 构建 Android APK
#   ./silk-chat.sh build-hap       - 构建鸿蒙 HAP
#   ./silk-chat.sh build-all       - 构建全部 (后端 + WebApp + APK + HAP)
#   ./silk-chat.sh start           - 启动服务 (后端 + 前端)
#   ./silk-chat.sh stop       - 停止服务
#   ./silk-chat.sh restart    - 重启
#   ./silk-chat.sh status     - 检查状态
#   ./silk-chat.sh logs       - 查看日志 (后端)
# ============================================================

SILK_DIR="$(cd "$(dirname "$0")" && pwd)"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# 加载 .env
if [ -f "$SILK_DIR/.env" ]; then
    TMP_ENV=$(mktemp)
    tr -d '\r' < "$SILK_DIR/.env" > "$TMP_ENV"
    set -a
    source "$TMP_ENV"
    set +a
    rm -f "$TMP_ENV"
fi

# Java Home
if [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
elif [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
else
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java) 2>/dev/null || echo "/usr/bin/java") 2>/dev/null) 2>/dev/null)
fi
export PATH=$JAVA_HOME/bin:$PATH

# Android SDK
if [ -d "/opt/homebrew/share/android-commandlinetools" ]; then
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
elif [ -d "/usr/lib/android-sdk" ]; then
    export ANDROID_HOME=/usr/lib/android-sdk
elif [ -d "/root/Android/Sdk" ]; then
    export ANDROID_HOME=/root/Android/Sdk
elif [ -d "/root/android-sdk" ]; then
    export ANDROID_HOME=/root/android-sdk
fi
if [ -n "$ANDROID_HOME" ]; then
    export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
fi

FRONTEND_HTTP_PORT=${FRONTEND_HTTP_PORT:-15004}
FRONTEND_PORT=${FRONTEND_INTERNAL_PORT:-${FRONTEND_PORT:-$FRONTEND_HTTP_PORT}}
BACKEND_PORT=${BACKEND_INTERNAL_PORT:-${BACKEND_HTTP_PORT:-15003}}
SILK_WORKFLOW_DIR=${SILK_WORKFLOW_DIR:-"$HOME/.silk-data/workflows"}
BACKEND_LOG="/tmp/silk_backend.log"
FRONTEND_LOG="/tmp/silk_frontend.log"
FRONTEND_STATIC_DIR="$SILK_DIR/frontend/webApp/build/dist/js/productionExecutable"
APK_OUTPUT_DIR="$SILK_DIR/backend/static"

print_header() {
    echo ""
    echo -e "${CYAN}════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}════════════════════════════════════════════${NC}"
}

check_port() {
    local port=$1
    if command -v lsof >/dev/null 2>&1; then
        lsof -i:"$port" -sTCP:LISTEN >/dev/null 2>&1
        return $?
    fi
    return 1
}

get_pid_on_port() {
    local port=$1
    lsof -ti:"$port" -sTCP:LISTEN 2>/dev/null
}

get_backend_url() {
    local host="${BACKEND_HOST:-localhost}"
    local port="${BACKEND_INTERNAL_PORT:-${BACKEND_HTTP_PORT:-15003}}"
    local scheme="${BACKEND_SCHEME:-http}"
    echo "${scheme}://${host}:${port}"
}

print_download_urls() {
    local base_url=$(get_backend_url)
    echo ""
    echo -e "${CYAN}════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  下载链接${NC}"
    echo -e "${CYAN}════════════════════════════════════════════${NC}"
    echo ""
    echo -e "  📱 APK:  ${GREEN}$base_url/silk.apk${NC}"
    echo -e "  📦 HAP:  ${GREEN}$base_url/silk.hap${NC}"
    echo ""
}

# ============================================================
# 构建后端
# ============================================================

build_backend() {
    print_header "🏗️ 构建后端"

    echo ""
    echo -e "${BLUE}编译后端 + 打包 shadowJar...${NC}"
    cd "$SILK_DIR"
    ./gradlew :backend:shadowJar --no-build-cache 2>&1 | tail -5

    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ 后端构建失败${NC}"
        return 1
    fi

    local JAR_FILE=$(ls -t "$SILK_DIR/backend/build/libs/"*-all.jar 2>/dev/null | head -1)
    if [ -n "$JAR_FILE" ]; then
        local JAR_SIZE=$(du -h "$JAR_FILE" | cut -f1)
        echo -e "${GREEN}✅ 后端构建成功${NC}"
        echo -e "  产物: ${CYAN}$JAR_FILE${NC}"
        echo -e "  大小: ${CYAN}$JAR_SIZE${NC}"
    else
        echo -e "${YELLOW}⚠ 未找到 shadowJar 产物${NC}"
    fi
    echo ""
}

# ============================================================
# 构建 WebApp
# ============================================================

build() {
    print_header "🔨 构建 WebApp"

    echo ""
    echo -e "${BLUE}构建前端生产版本...${NC}"
    cd "$SILK_DIR"
    ./gradlew :frontend:webApp:compileProductionExecutableKotlinJs --no-build-cache 2>&1 | tail -5

    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ 构建失败${NC}"
        return 1
    fi

    echo ""
    echo -e "${BLUE}复制到 backend/static/...${NC}"
    local JS_FILE="$SILK_DIR/frontend/webApp/build/dist/js/productionExecutable/webApp.js"
    cp "$JS_FILE" "$SILK_DIR/backend/static/webApp.js"
    if [ $? -eq 0 ]; then
        local JS_SIZE=$(du -h "$JS_FILE" | cut -f1)
        echo -e "${GREEN}✅ WebApp 构建成功${NC}"
        echo -e "  产物: ${CYAN}$SILK_DIR/backend/static/webApp.js${NC}"
        echo -e "  大小: ${CYAN}$JS_SIZE${NC}"
    else
        echo -e "${RED}❌ 复制失败${NC}"
    fi
}

# ============================================================
# 构建 Android APK
# ============================================================

clean_gradle_kotlin_snapshots() {
    rm -rf "$SILK_DIR/backend/build/snapshot"
    rm -rf "$SILK_DIR/frontend/androidApp/build/snapshot"
}

build_apk() {
    print_header "📱 构建 Android APK"

    # ARM64: 用本地 ARM64 AAPT2 替换 Gradle 缓存的 x86-64 版本
    if [ "$(uname -m)" = "aarch64" ]; then
        AAPT2_OVERRIDE=""
        for build_tools_dir in "$ANDROID_HOME/build-tools"/*/; do
            if [ -x "${build_tools_dir}aapt2" ]; then
                local aapt2_arch=$(file "${build_tools_dir}aapt2" 2>/dev/null | grep -E 'aarch64|ARM aarch64' | head -1)
                if [ -n "$aapt2_arch" ]; then
                    AAPT2_OVERRIDE="-Pandroid.aapt2FromMavenOverride=${build_tools_dir}aapt2"
                    echo -e "  ${GREEN}✓ ARM64 AAPT2: ${build_tools_dir}aapt2${NC}"
                    break
                fi
            fi
        done
    fi

    # 后端地址注入 APK
    if [ -n "$BACKEND_BASE_URL" ]; then
        APK_BACKEND_URL="$BACKEND_BASE_URL"
    elif [ -n "$BACKEND_HOST" ]; then
        APK_BACKEND_URL="${BACKEND_SCHEME:-http}://${BACKEND_HOST}:${BACKEND_HTTP_PORT:-15003}"
    else
        APK_BACKEND_URL="${BACKEND_SCHEME:-http}://10.0.2.2:${BACKEND_HTTP_PORT:-15003}"
    fi
    echo -e "  后端地址: ${CYAN}$APK_BACKEND_URL${NC}"

    echo ""
    echo -e "${BLUE}构建 Android APK (Debug)...${NC}"
    cd "$SILK_DIR"
    clean_gradle_kotlin_snapshots
    ./gradlew -PBACKEND_BASE_URL="$APK_BACKEND_URL" $AAPT2_OVERRIDE :frontend:androidApp:assembleDebug

    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ APK 构建失败${NC}"
        return 1
    fi

    APK_FILE=$(ls -t "$SILK_DIR/frontend/androidApp/build/outputs/apk/debug/"*.apk 2>/dev/null | head -1)
    if [ -z "$APK_FILE" ]; then
        echo -e "${RED}❌ 未找到生成的 APK 文件${NC}"
        return 1
    fi

    APK_SIZE=$(du -h "$APK_FILE" | cut -f1)
    BUILD_CONFIG="$SILK_DIR/frontend/androidApp/build/generated/source/buildConfig/debug/com/silk/android/BuildConfig.java"
    APK_VERSION=$(grep "VERSION_NAME" "$BUILD_CONFIG" 2>/dev/null | sed 's/.*"\([^"]*\)".*/\1/' | head -1)
    APK_VERSION="${APK_VERSION:-$(date +"%Y.%m%d.%H%M")}"

    echo ""
    echo -e "${GREEN}✅ APK 构建成功${NC}"
    echo -e "  大小: ${CYAN}$APK_SIZE${NC}"
    echo -e "  版本: ${CYAN}$APK_VERSION${NC}"

    local APK_NAME="silk-${APK_VERSION}.apk"
    cp "$APK_FILE" "$APK_OUTPUT_DIR/$APK_NAME"
    ln -sf "$APK_OUTPUT_DIR/$APK_NAME" "$APK_OUTPUT_DIR/silk.apk"
    mkdir -p "$APK_OUTPUT_DIR/files"
    cp "$APK_FILE" "$APK_OUTPUT_DIR/files/androidApp-debug.apk"
    echo -e "  已复制到: ${CYAN}$APK_OUTPUT_DIR/$APK_NAME${NC}"
    echo ""
}

# ============================================================
# 构建鸿蒙 HAP
# ============================================================

build_hap() {
    print_header "📱 构建鸿蒙 HAP"

    local HARMONY_DIR="$SILK_DIR/frontend/harmonyApp"

    # DevEco Studio 根目录
    if [ -z "$DEVECO_HOME" ]; then
        if [ -d "/Applications/DevEco-Studio.app" ]; then
            DEVECO_HOME="/Applications/DevEco-Studio.app/Contents"
        elif [ -d "$HOME/DevEco-Studio" ]; then
            DEVECO_HOME="$HOME/DevEco-Studio"
        elif [ -d "/opt/DevEco-Studio" ]; then
            DEVECO_HOME="/opt/DevEco-Studio"
        fi
    fi
    if [ -d "$DEVECO_HOME/Contents/tools/hvigor/bin" ]; then
        DEVECO_HOME="$DEVECO_HOME/Contents"
    fi

    local OHPM_CMD=""
    local HVIGORW_CMD=""
    local DEVECO_SDK_HOME=""

    if [ -n "$DEVECO_HOME" ] && [ -d "$DEVECO_HOME/tools/ohpm/bin" ]; then
        OHPM_CMD="$DEVECO_HOME/tools/ohpm/bin/ohpm"
        HVIGORW_CMD="$DEVECO_HOME/tools/hvigor/bin/hvigorw"
        DEVECO_SDK_HOME="$DEVECO_HOME/sdk"
    fi
    if [ -z "$HVIGORW_CMD" ] || [ ! -x "$HVIGORW_CMD" ]; then
        if [ -f "$HARMONY_DIR/hvigorw" ]; then
            HVIGORW_CMD="$HARMONY_DIR/hvigorw"
        fi
    fi

    if [ -z "$HVIGORW_CMD" ] || [ ! -x "$HVIGORW_CMD" ]; then
        echo -e "${YELLOW}⚠ 未找到 DevEco 自带 hvigorw${NC}"
        echo -e "  请安装 DevEco Studio，或设置 ${CYAN}DEVECO_HOME${NC}"
        echo ""
        return 1
    fi

    # 后端地址注入 HAP
    if [ -n "$BACKEND_BASE_URL" ]; then
        HAP_BACKEND_URL="$BACKEND_BASE_URL"
    elif [ -n "$BACKEND_HOST" ]; then
        HAP_BACKEND_URL="${BACKEND_SCHEME:-http}://${BACKEND_HOST}:${BACKEND_HTTP_PORT:-15003}"
    else
        HAP_BACKEND_URL="${BACKEND_SCHEME:-http}://localhost:${BACKEND_HTTP_PORT:-15003}"
    fi
    echo -e "  后端地址: ${CYAN}$HAP_BACKEND_URL${NC}"

    # hdc
    local HDC_CMD="${HDC:-}"
    if [ -z "$HDC_CMD" ] || [ ! -x "$HDC_CMD" ]; then
        for cand in \
            "${DEVECO_HOME}/sdk/default/openharmony/toolchains/hdc" \
            "/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc"; do
            if [ -n "$cand" ] && [ -x "$cand" ]; then
                HDC_CMD="$cand"
                break
            fi
        done
    fi

    cd "$HARMONY_DIR"

    run_hvigor() {
        (
            if [ -n "$DEVECO_HOME" ] && [ -d "$DEVECO_HOME/jbr" ]; then
                export JAVA_HOME="$DEVECO_HOME/jbr"
                export PATH="$DEVECO_HOME/jbr/bin:$PATH"
            fi
            if [ -n "$DEVECO_SDK_HOME" ] && [ -d "$DEVECO_SDK_HOME" ]; then
                export DEVECO_SDK_HOME="$DEVECO_SDK_HOME"
            fi
            "$HVIGORW_CMD" "$@"
        )
    }

    local HVIGOR_IDE_FLAGS="-p product=default --analyze=normal --parallel --incremental --daemon"

    # 1. ohpm install
    if [ -n "$OHPM_CMD" ] && [ -x "$OHPM_CMD" ]; then
        echo ""
        echo -e "${BLUE}[1/4] ohpm install...${NC}"
        "$OHPM_CMD" install 2>&1 || {
            echo -e "${RED}❌ ohpm install 失败${NC}"; return 1
        }
    else
        echo -e "${YELLOW}⚠ 跳过 ohpm install（未找到 DevEco ohpm）${NC}"
    fi

    # 2. hvigor sync
    echo ""
    echo -e "${BLUE}[2/4] hvigor --sync...${NC}"
    run_hvigor --sync $HVIGOR_IDE_FLAGS 2>&1 || {
        echo -e "${RED}❌ sync 失败${NC}"; return 1
    }

    # 3. assembleHap
    echo ""
    echo -e "${BLUE}[3/4] assembleHap...${NC}"
    run_hvigor assembleHap --mode module -p module=entry@default $HVIGOR_IDE_FLAGS 2>&1
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ HAP 构建失败${NC}"
        return 1
    fi

    local HAP_FILE=""
    for cand in \
        "$HARMONY_DIR/entry/build/default/outputs/default/entry-default-signed.hap" \
        "$HARMONY_DIR/entry/build/default/outputs/default/app/entry-default.hap" \
        "$HARMONY_DIR/entry/build/default/outputs/default/entry-default-unsigned.hap"; do
        if [ -f "$cand" ]; then
            HAP_FILE="$cand"
            break
        fi
    done
    if [ -z "$HAP_FILE" ]; then
        HAP_FILE=$(find "$HARMONY_DIR/entry/build" -name "*.hap" -type f 2>/dev/null | head -1)
    fi
    if [ -z "$HAP_FILE" ]; then
        echo -e "${YELLOW}⚠ 未找到 .hap 输出${NC}"
        return 1
    fi

    local HAP_SIZE=$(du -h "$HAP_FILE" | cut -f1)
    echo ""
    echo -e "${GREEN}✅ HAP 构建成功${NC}"
    echo -e "  大小: ${CYAN}$HAP_SIZE${NC}"
    echo -e "  路径: ${CYAN}$HAP_FILE${NC}"

    if [[ "$(basename "$HAP_FILE")" == *unsigned* ]]; then
        echo ""
        echo -e "${YELLOW}⚠ 当前产物为未签名 HAP，真机安装会失败${NC}"
        echo -e "  请在 DevEco Studio 中配置签名后重新构建。"
    fi

    # 复制到 backend/static/
    echo ""
    echo -e "${BLUE}[4/4] 复制到 backend/static/...${NC}"
    mkdir -p "$APK_OUTPUT_DIR"
    cp "$HAP_FILE" "$APK_OUTPUT_DIR/"
    ln -sf "$APK_OUTPUT_DIR/$(basename "$HAP_FILE")" "$APK_OUTPUT_DIR/silk.hap"
    echo -e "${GREEN}✅ 已复制${NC}"

    # hdc install（可跳过）
    if [ -z "${SILK_SKIP_HARMONY_RUN:-}" ] && [ -n "$HDC_CMD" ] && [ -x "$HDC_CMD" ]; then
        local HDC_TARGET="${SILK_HDC_TARGET:-127.0.0.1:5555}"
        echo ""
        echo -e "${BLUE}安装到鸿蒙设备 ($HDC_TARGET)...${NC}"
        if "$HDC_CMD" -t "$HDC_TARGET" install -r "$HAP_FILE" 2>&1; then
            echo -e "${GREEN}✅ 安装成功${NC}"
            if [ "${SILK_HARMONY_NO_START:-}" != "1" ]; then
                "$HDC_CMD" -t "$HDC_TARGET" shell aa start -a EntryAbility -b com.silk.harmony 2>&1 && \
                echo -e "${GREEN}✅ 已启动${NC}"
            fi
        else
            echo -e "${YELLOW}⚠ hdc install 失败，请手动安装${NC}"
        fi
    fi
    echo ""
}

# ============================================================
# 构建全部
# ============================================================

build_all() {
    print_header "📦 构建全部"

    build_backend || return 1
    echo ""
    build || return 1
    echo ""
    build_apk || return 1
    echo ""
    build_hap || return 1

    echo ""
    echo -e "${GREEN}✅ 全部构建完成${NC}"
    echo ""
    echo -e "${CYAN}════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  访问地址${NC}"
    echo -e "${CYAN}════════════════════════════════════════════${NC}"
    echo ""
    echo -e "  前端页面: ${GREEN}http://localhost:$FRONTEND_PORT${NC}"
    echo ""
    print_download_urls
}

# ============================================================
# 启动
# ============================================================

start() {
    print_header "🚀 启动服务"

    # ── 后端 ──
    if check_port $BACKEND_PORT; then
        local PID=$(get_pid_on_port $BACKEND_PORT)
        echo -e "  ${YELLOW}端口 $BACKEND_PORT 已被占用 (PID: $PID)${NC}"
        echo -n "  是否终止? [y/N]: "
        read -t 5 answer
        if [ "$answer" == "y" ] || [ "$answer" == "Y" ]; then
            kill -TERM "$PID" 2>/dev/null
            sleep 1
            kill -9 "$PID" 2>/dev/null
            sleep 1
            echo -e "  ${GREEN}端口已释放${NC}"
        else
            echo -e "  ${RED}已取消${NC}"
            return 1
        fi
    fi

    echo ""
    echo -e "${BLUE}启动后端 (端口 $BACKEND_PORT)...${NC}"
    mkdir -p "$SILK_WORKFLOW_DIR"
    rm -f "$BACKEND_LOG"
    nohup env JAVA_TOOL_OPTIONS="-Dsilk.workflowDir=$SILK_WORKFLOW_DIR" \
        ./gradlew :backend:run \
        > "$BACKEND_LOG" 2>&1 &
    echo -e "  ${GREEN}后端启动命令已执行${NC}"
    echo -e "  日志: ${CYAN}$BACKEND_LOG${NC}"

    echo ""
    echo -e "${YELLOW}等待后端就绪...${NC}"
    for i in {1..24}; do
        sleep 5
        if check_port $BACKEND_PORT; then
            local HOST="${BACKEND_HOST:-localhost}"
            echo -e "  ${GREEN}✅ 后端已就绪${NC}"
            break
        fi
        echo -n "."
    done
    if ! check_port $BACKEND_PORT; then
        echo ""
        echo -e "${YELLOW}⚠ 后端启动超时，请查看日志: tail -f $BACKEND_LOG${NC}"
        return 1
    fi

    # ── 前端 ──
    echo ""
    echo -e "${BLUE}启动前端静态服务器 (端口 $FRONTEND_PORT)...${NC}"
    if check_port $FRONTEND_PORT; then
        local FPID=$(get_pid_on_port $FRONTEND_PORT)
        echo -e "  ${YELLOW}前端端口 $FRONTEND_PORT 已被占用 (PID: $FPID)${NC}"
        echo -n "  是否终止? [y/N]: "
        read -t 5 answer
        if [ "$answer" == "y" ] || [ "$answer" == "Y" ]; then
            kill -9 "$FPID" 2>/dev/null
            sleep 1
            echo -e "  ${GREEN}端口已释放${NC}"
        else
            echo -e "  ${YELLOW}跳过前端启动${NC}"
            return 1
        fi
    fi

    local STATIC_DIR="$FRONTEND_STATIC_DIR"
    if [ ! -f "$STATIC_DIR/webApp.js" ]; then
        echo -e "  ${YELLOW}⚠ 前端文件不存在，请先运行: ./silk-chat.sh build${NC}"
        return 1
    fi
    rm -f "$FRONTEND_LOG"
    cd "$STATIC_DIR"
    nohup python3 -m http.server $FRONTEND_PORT --bind 0.0.0.0 > "$FRONTEND_LOG" 2>&1 &
    sleep 2
    echo -e "  ${GREEN}✅ 前端已就绪${NC}"
    echo -e "  日志: ${CYAN}$FRONTEND_LOG${NC}"

    echo ""
    echo -e "${GREEN}════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  所有服务已就绪${NC}"
    echo -e "${GREEN}════════════════════════════════════════════${NC}"
    echo ""
    echo -e "  前端页面: ${GREEN}http://localhost:$FRONTEND_PORT${NC}"
    echo -e "  后端:     ${GREEN}${BACKEND_SCHEME:-http}://$HOST:$BACKEND_PORT${NC}"
    echo ""
    print_download_urls
}

# ============================================================
# 停止
# ============================================================

stop() {
    print_header "🛑 停止服务"

    echo -e "${BLUE}[1/2] 停止前端...${NC}"
    if check_port $FRONTEND_PORT; then
        local FPID=$(get_pid_on_port $FRONTEND_PORT)
        kill -9 "$FPID" 2>/dev/null
        echo -e "  ${GREEN}前端已停止 (PID: $FPID)${NC}"
    else
        echo -e "  ${YELLOW}前端未运行${NC}"
    fi

    echo ""
    echo -e "${BLUE}[2/2] 停止后端...${NC}"
    if check_port $BACKEND_PORT; then
        local PID=$(get_pid_on_port $BACKEND_PORT)
        kill -TERM "$PID" 2>/dev/null
        sleep 2
        kill -9 "$PID" 2>/dev/null
        echo -e "  ${GREEN}后端已停止 (PID: $PID)${NC}"
    else
        echo -e "  ${YELLOW}后端未运行${NC}"
    fi
    echo ""
}

# ============================================================
# 状态
# ============================================================

status() {
    print_header "🔍 状态"

    echo -e "${BLUE}前端 (端口 $FRONTEND_PORT)${NC}"
    if check_port $FRONTEND_PORT; then
        local FPID=$(get_pid_on_port $FRONTEND_PORT)
        echo -e "  ${GREEN}● 运行中 (PID: $FPID)${NC}"
    else
        echo -e "  ${RED}○ 已停止${NC}"
    fi

    echo ""
    echo -e "${BLUE}后端 (端口 $BACKEND_PORT)${NC}"
    if check_port $BACKEND_PORT; then
        local PID=$(get_pid_on_port $BACKEND_PORT)
        echo -e "  ${GREEN}● 运行中 (PID: $PID)${NC}"
        local HEALTH=$(curl -s http://localhost:$BACKEND_PORT/health 2>/dev/null)
        if [ -n "$HEALTH" ]; then
            echo -e "  健康检查: ${GREEN}✓ OK${NC}"
        fi
    else
        echo -e "  ${RED}○ 已停止${NC}"
    fi
    echo ""
}

# ============================================================
# 日志
# ============================================================

logs() {
    if [ -f "$BACKEND_LOG" ]; then
        tail -f "$BACKEND_LOG"
    else
        echo -e "${YELLOW}日志文件不存在: $BACKEND_LOG${NC}"
    fi
}

# ============================================================
# 主入口
# ============================================================

case "$1" in
    build|b)
        build
        ;;
    build-backend|bb)
        build_backend
        ;;
    build-apk|ba)
        build_apk
        ;;
    build-hap|bh)
        build_hap
        ;;
    build-all|ball)
        build_all
        ;;
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart|r)
        stop
        sleep 1
        start
        ;;
    status|s)
        status
        ;;
    logs|l)
        logs
        ;;
    *)
        echo ""
        echo -e "${CYAN}Silk Chat 部署脚本 (Claude CLI)${NC}"
        echo ""
        echo "用法: $0 <命令>"
        echo ""
        echo "  build, b         构建 WebApp 前端"
        echo "  build-backend, bb 构建后端 shadowJar"
        echo "  build-apk, ba    构建 Android APK"
        echo "  build-hap, bh    构建鸿蒙 HAP"
        echo "  build-all, ball  构建全部 (后端 + WebApp + APK + HAP)"
        echo "  start            启动服务 (后端 $BACKEND_PORT + 前端 $FRONTEND_PORT)"
        echo "  stop             停止服务"
        echo "  restart, r       重启"
        echo "  status, s        检查服务状态"
        echo "  logs, l          查看后端日志"
        echo ""
        ;;
esac
