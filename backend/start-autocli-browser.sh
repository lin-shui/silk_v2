#!/bin/bash
# Start Xvfb + Chrome with AutoCLI extension for browser-mode commands.
# Usage: ./start-autocli-browser.sh [start|stop|status]

DISPLAY_NUM=99
XVFB_PID_FILE="/tmp/xvfb-autocli.pid"
CHROME_PID_FILE="/tmp/chrome-autocli.pid"
CHROME_DATA_DIR="/tmp/autocli-chrome-profile"
EXTENSION_DIR="/opt/autocli-chrome-extension"

start() {
    if [ -f "$XVFB_PID_FILE" ] && kill -0 "$(cat "$XVFB_PID_FILE")" 2>/dev/null; then
        echo "Xvfb already running (pid $(cat "$XVFB_PID_FILE"))"
    else
        Xvfb ":$DISPLAY_NUM" -screen 0 1920x1080x24 -ac &
        echo $! > "$XVFB_PID_FILE"
        sleep 1
        echo "Xvfb started on :$DISPLAY_NUM (pid $(cat "$XVFB_PID_FILE"))"
    fi

    export DISPLAY=":$DISPLAY_NUM"

    if [ -f "$CHROME_PID_FILE" ] && kill -0 "$(cat "$CHROME_PID_FILE")" 2>/dev/null; then
        echo "Chrome already running (pid $(cat "$CHROME_PID_FILE"))"
    else
        mkdir -p "$CHROME_DATA_DIR"
        google-chrome \
            --no-sandbox \
            --disable-gpu \
            --disable-dev-shm-usage \
            --user-data-dir="$CHROME_DATA_DIR" \
            --load-extension="$EXTENSION_DIR" \
            --remote-debugging-port=9222 \
            --no-first-run \
            --disable-default-apps \
            --disable-extensions-except="$EXTENSION_DIR" \
            about:blank &
        echo $! > "$CHROME_PID_FILE"
        sleep 2
        echo "Chrome started with AutoCLI extension (pid $(cat "$CHROME_PID_FILE"))"
    fi

    echo "AutoCLI browser mode ready. Use 'autocli doctor' to verify."
}

stop() {
    if [ -f "$CHROME_PID_FILE" ]; then
        kill "$(cat "$CHROME_PID_FILE")" 2>/dev/null
        rm -f "$CHROME_PID_FILE"
        echo "Chrome stopped"
    fi
    if [ -f "$XVFB_PID_FILE" ]; then
        kill "$(cat "$XVFB_PID_FILE")" 2>/dev/null
        rm -f "$XVFB_PID_FILE"
        echo "Xvfb stopped"
    fi
}

status() {
    if [ -f "$XVFB_PID_FILE" ] && kill -0 "$(cat "$XVFB_PID_FILE")" 2>/dev/null; then
        echo "Xvfb: running (pid $(cat "$XVFB_PID_FILE"))"
    else
        echo "Xvfb: stopped"
    fi
    if [ -f "$CHROME_PID_FILE" ] && kill -0 "$(cat "$CHROME_PID_FILE")" 2>/dev/null; then
        echo "Chrome: running (pid $(cat "$CHROME_PID_FILE"))"
    else
        echo "Chrome: stopped"
    fi
}

case "${1:-start}" in
    start)  start  ;;
    stop)   stop   ;;
    status) status ;;
    *)      echo "Usage: $0 {start|stop|status}" ;;
esac
