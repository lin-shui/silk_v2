# CC Bridge Agent

CC Bridge 是一个 Python 服务，通过 WebSocket 连接 Silk 后端，在本地执行 Claude CLI 命令并将结果回传给 Silk。

## 架构

```
用户 (浏览器) ──→ Silk 后端 ──WebSocket──→ CC Bridge (bridge_agent.py) ──→ Claude CLI
```

Silk 后端将用户的 CC 模式消息转发给 Bridge，Bridge 调用本地的 Claude CLI 执行，并将流式输出实时回传。

## 适用场景

- 后端运行在容器/VM 中，没有安装 Claude CLI
- 希望 Claude CLI 在能直接访问代码仓库的机器上运行
- 需要将后端部署与 Claude 执行环境分离

## 前置条件

- Python 3.8+
- Claude CLI 已安装且可通过 `claude` 命令调用
- Silk 后端已启动

## 快速开始

### 1. 安装依赖（建议在仓库外建 venv）

不在 `silk_harmony` 里创建 `.venv`，例如在用户目录单独建环境并安装依赖：

```bash
python3 -m venv ~/venvs/silk-cc-bridge
~/venvs/silk-cc-bridge/bin/pip install -r /path/to/silk_harmony/cc_bridge/requirements.txt
```

之后在 `cc_bridge/.env` 里设置 `BRIDGE_PYTHON` 指向该解释器（见下一步）。若坚持用系统 Python，可省略 `BRIDGE_PYTHON`，并在当前 shell 对 `python3` 执行 `pip install -r requirements.txt`。

### 2. 生成 Bridge Token

在 Silk Web UI 中：**设置** → **Claude Code** → **生成 Token**，复制生成的 Token。

### 3. 配置

创建 `cc_bridge/.env` 文件：

```bash
BRIDGE_SERVER=<silk后端地址>:8006
# 若 Web 入口是 HTTPS，请写完整 URL（会自动用 WSS 连 /cc-bridge），例如：
# BRIDGE_SERVER=https://ai-silk.duckdns.org:36796
BRIDGE_TOKEN=<你的Token>
BRIDGE_PYTHON=/path/to/your-venv/bin/python3   # 推荐：仓库外 venv 的 python3
# BRIDGE_WORKING_DIR=/path/to/workdir  # 可选，默认为当前目录
# BRIDGE_LOG_LEVEL=INFO                # 可选：DEBUG/INFO/WARNING/ERROR
```

`BRIDGE_SERVER` 带 `https://` 或 `wss://` 前缀时，桥接使用 **WSS**；`http://`、`ws://` 或仅 `host:port` 时使用 **WS**。

若 WSS 使用**自签名证书**，Python 会报 `CERTIFICATE_VERIFY_FAILED`。在 `cc_bridge/.env` 中增加一行（仅内网/自建可信环境）：

```bash
BRIDGE_TLS_INSECURE=1
```

然后 `./bridge.sh restart`。长期方案是在反向代理上使用 Let’s Encrypt 等受信任证书，再删掉该选项。

### 4. 启动

**后台运行（推荐）：**

```bash
./bridge.sh start
```

**前台运行（调试用）：**

```bash
/path/to/your-venv/bin/python bridge_agent.py --server <silk后端地址>:8006 --token <你的Token>
```

### 5. 验证

在 Silk 设置页点击 **刷新状态**，确认显示：
- 绿色圆点 + "已连接"
- Bridge IP 显示 Bridge 所在机器的 IP 地址

然后在任意聊天中使用 `/cc` 进入 CC 模式即可。

## bridge.sh 管理脚本

`bridge.sh` 用于后台管理 Bridge 进程，从 `cc_bridge/.env` 读取配置。

| 命令 | 说明 |
|------|------|
| `./bridge.sh start` | 后台启动 Bridge |
| `./bridge.sh stop` | 停止 Bridge（优雅关闭，10秒超时后强制终止） |
| `./bridge.sh restart` | 重启 Bridge |
| `./bridge.sh status` | 查看运行状态 |
| `./bridge.sh logs` | 查看日志（tail -f） |

日志输出到 `cc_bridge/bridge.log`，PID 记录在 `cc_bridge/.bridge.pid`。

## bridge_agent.py 命令行参数

直接运行 `bridge_agent.py` 时使用以下参数（使用 `bridge.sh` 时通过 `.env` 配置）：

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `--server` | 是 | - | Silk 后端地址，如 `localhost:8006` |
| `--token` | 是 | - | Bridge 认证 Token（在 Silk 设置页生成） |
| `--working-dir` | 否 | 当前目录 | Claude CLI 的默认工作目录 |
| `--log-level` | 否 | `INFO` | 日志级别：`DEBUG`、`INFO`、`WARNING`、`ERROR` |

## 文件说明

| 文件 | 职责 |
|------|------|
| `bridge.sh` | 管理脚本：后台启动/停止/重启/状态/日志 |
| `bridge_agent.py` | 入口：WebSocket 客户端、消息路由、Token 认证 |
| `executor.py` | Claude CLI 子进程管理、stream-json 输出解析 |
| `session_manager.py` | 会话持久化，保存至 `~/.silk/cc_sessions.json` |
| `requirements.txt` | Python 依赖（`websockets>=12.0`） |
| `.env` | 配置文件（需自行创建，不提交到 Git） |

## 断线重连

Bridge 内置自动重连机制。WebSocket 连接断开后会自动尝试重连。如果 Token 失效（后端返回 `invalid token`），需要在 Silk 设置页重新生成 Token 并重启 Bridge。

## 停止

- 后台运行时：`./bridge.sh stop`
- 前台运行时：按 `Ctrl+C`

两种方式都会优雅关闭，自动清理正在运行的 Claude CLI 子进程。
