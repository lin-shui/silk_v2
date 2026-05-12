# Known Drift

这些点容易误导 agent，遇到冲突时按本文件处理。

## Protected Human-Facing Files May Stay Stale

- 本轮按用户要求不修改 `README.md`、`.env.example`、`silk.sh`。
- 因此这些文件里的旧叙述不要自动当成最新事实；对 coding agent 来说，代码与 `docs/context/**` 的最近文档优先。

## Port Defaults Are Not Uniform

- `README.md` / `.env.example` 仍有 `8006` 后端端口叙述。
- 后端运行入口未配置时回落到 `8003`：
  - `Application.kt`
  - `silk.sh`
  - Web / Android / Desktop Gradle 后端地址 fallback
- Web 前端端口也不完全一致：
  - `silk.sh` 的 Python 静态服务器默认 `8005`
  - `frontend/webApp` / `frontend/androidApp` 生成 `FRONTEND_PORT` 的 fallback 仍是 `8004`
  - `frontend/webApp` dev server `runTask` 端口是 `8005`
- 实际运行和构建任务中优先读 `.env` / Gradle 属性；不要只抄 README 端口表。

## `silk.sh` HAP Comments Are Ahead Of Behavior

- `silk.sh` 顶部注释和 help 文案仍说 `deploy` / `build-all` 包含 Harmony HAP。
- 当前实际代码中 `build_all()` 只构建 WebApp + Android APK，`deploy()` 的 HAP 构建块被注释。
- Harmony HAP 需要显式运行 `./silk.sh build-hap`。

## README Understates Current Capabilities

- `README.md` 仍以 Web / Android / optional Desktop 为主，未完整描述 Harmony、Audio Duplex、HAP 下载路由等当前能力。
- `README.md` 里的 Workflow 存储路径仍写 `backend/workflows/workflow_store.json`，实际默认是 `~/.silk-data/workflows/workflow_store.json`。
- 若需要 coding context，优先看 `ARCHITECTURE.md` 与 `docs/context/**`。

## `Routing.kt` Still Owns Most HTTP Surface

- 虽然已经拆出 `routes/FileRoutes.kt`、`routes/AsrRoutes.kt`，但多数路由仍在 `Routing.kt`。
- 改 HTTP 行为时先搜 `Routing.kt`，不要假设所有路由都已模块化。

## Desktop Feature Parity Is Lower

- `frontend/desktopApp` 当前主要是登录 / 群组 / 聊天 / 设置。
- Workflow / Knowledge Base / Audio Duplex 的主壳当前在 Web、Android、Harmony，更不是 Desktop 的事实能力面。

## `search/` Is Legacy

- `search/` 目录是 Weaviate 时代的遗留物。Weaviate 已由 Claude 原生 `web_search` + 后端 grep `searchContext()` 替代。
- `search/README.md` 是 Weaviate 上游 README，不是本仓库的项目说明。
- 阅读 `search/` 时注意其内容已不反映当前架构。

## Generated / Runtime Paths May Be Dirty

- 当前工作树里常见未跟踪目录：`.silk-runtime/`、`backend/bin/`、`frontend/desktopApp/bin/`
- 这些通常是运行或构建副产物，不是架构入口。

## Todo Governance Lives Outside `docs/context/`

- Todo 规划的 canonical 文件仍是 `docs/todo-roadmap.md`
- 它是 human-maintained roadmap，不是 agent 自动维护的执行日志
- `docs/context/planning/TODO_ROADMAP.md` 只是 agent-facing wrapper

## Agent Framework (Plan E–E4 + F1 M1-M4 Done)

CC 模式已完全迁到通用 `AgentRuntime` 框架。旧 `ClaudeCodeManager` / `BridgeRegistry` / `StreamParser` / `bridge_agent.py` 已物理删除。**Plan F1 M1-M4 已落地**，Codex CLI 经 ACP 接入并完成多 agent UX 收尾：

- **入口面**：`WebSocketConfig.kt`、`ChatServer.broadcast()` 只调 `AgentRuntime.{handleIfActive, cancelIfActive, isAgentMessage}`
- **聊天执行**：`acp_adapter.py` 通过 `/agent-bridge` 端点接收 ACP 请求，复用 `Executor` 跑 Claude CLI，流式推 `session/update` 通知
- **文件系统操作走 ACP**：`/cc-fs/cd` → `_silk/set_cwd`；`/cc-fs/list` → `_silk/list_dir`
- **持久化**：`AgentRuntime.WorkflowPersistence` 接 `WorkflowManager`；prompt response 的 `meta.ccSessionId` 写入 `Workflow.agentSessions[agentType]`（per-agent）；`autoActivateForWorkflow` 用 seed 续会话
- **Multi-agent UX（F1 M4）**：`/codex <text>` inline 文本一步切换+提问；`@codex <text>` 跨 agent 路由不再静默；`Workflow.activeAgent` 持久化，`/use` 切换落盘，下次进工作流自动恢复；前端工作流标题显示 active agent badge
- **`/session <id>` 真正加载**：`AgentRuntime` 调 ACP `session/load`，cc_bridge 复用 `SessionManager.resume_session`，codex_bridge 新增 `codex_session_index.find_session_file`
- **Token 重生踢连接**：`AcpRegistry.disconnect(userId)` 关闭老 ACP 连接
- **TrustedDir bridgeId**：`resolveBridgeId(userId)` 从 `AcpRegistry.getRemoteIp` 拿，格式 `"ip:<remoteIp>"` 不变
- **`/cc-bridge` WebSocket 端点已删除**
- **无回退路径**：ACP 不可用时直接报"未连接"

排查 agent 行为时只看 `AgentRuntime` + 对应 ACP adapter（Claude Code: `cc_bridge/acp_adapter.py`；Codex: `codex_bridge/codex_adapter.py`）。
