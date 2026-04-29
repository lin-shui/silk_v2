# Known Drift

这些点容易误导 agent，遇到冲突时按本文件处理。

## Port Defaults Are Not Uniform In Docs

- `README.md` 里有 8006/8005 的叙述。
- 代码与脚本未配置时常回落到 8003/8005：
  - `Application.kt`
  - `silk.sh`
  - `.env.example`
- 结论：实际任务中以 `.env` + `silk.sh` + 构建脚本为准，不要只抄 README 端口表。

## `Routing.kt` Still Owns Most HTTP Surface

- 虽然已经拆出 `routes/FileRoutes.kt`、`routes/AsrRoutes.kt`，但多数路由仍在 `Routing.kt`。
- 改 HTTP 行为时先搜 `Routing.kt`，不要假设所有路由都已模块化。

## Desktop Feature Parity Is Lower

- `frontend/desktopApp` 当前主要是登录 / 群组 / 聊天 / 设置。
- Workflow / Knowledge Base 的三 Tab 主壳当前在 Web、Android、Harmony，更不是 Desktop 的事实能力面。

## `search/README.md` Is Not Silk Guidance

- `search/README.md` 是 Weaviate 上游 README，不是本仓库的项目说明。
- 阅读 `search/` 时优先看脚本与 Silk 调用点，不要把该 README 当成项目约束。

## Generated / Runtime Paths May Be Dirty

- 当前工作树里常见未跟踪目录：`.silk-runtime/`、`backend/bin/`、`frontend/desktopApp/bin/`
- 这些通常是运行或构建副产物，不是架构入口。

## Todo Governance Lives Outside `docs/context/`

- Todo 规划的 canonical 文件仍是 `docs/todo-roadmap.md`
- 它是 human-maintained roadmap，不是 agent 自动维护的执行日志
- `docs/context/planning/TODO_ROADMAP.md` 只是 agent-facing wrapper

## Agent Framework In Transition (Plan D Done, Plan E Pending)

`develop_acp` 分支正在把 CC 模式从 `ClaudeCodeManager` 迁到通用 `AgentRuntime` 框架，目前是**双桥并存**的过渡态：

- **入口面已切换**：`WebSocketConfig.kt`、`ChatServer.broadcast()` 现在只调 `AgentRuntime.{handleIfActive, cancelIfActive, isAgentMessage}`，不再直接调 `ClaudeCodeManager`
- **执行面仍走旧桥**：`AgentRuntime.handlePrompt/handleCommand/cancelIfActive` 在 `AcpRegistry` 无客户端时**回退**到 `ClaudeCodeManager.handleIfActive`，由旧 `bridge_agent.py` + `/cc-bridge` 端点真正执行 Claude CLI
- **新 `/agent-bridge` 端点已就绪**：`acp_adapter.py` 能完成 ACP 握手，但还没接入 `Executor`，所以实际跑 CLI 仍依赖旧桥
- **状态层在 AgentRuntime**：`autoActivateForWorkflow` 同时写 `ClaudeCodeManager` 和 `AgentRuntime`；`AgentRuntime.GroupAgentContext.workingDir` 在激活时从 `ClaudeCodeManager.snapshotState()` 同步

排查 CC 行为时优先看 `AgentRuntime`（入口/路由/状态），实际执行细节看 `ClaudeCodeManager`/`bridge_agent.py`。`/cc-state`、`/cc-settings/bridge-status` 端点都查双注册表（`AcpRegistry` + `BridgeRegistry`）。

Plan E 完成后会移除旧桥；现阶段不要假设新旧路径已经合一。
