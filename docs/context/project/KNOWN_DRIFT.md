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

## Agent Framework (Plan E–E4 + F1 M1-M4 Done)

CC 模式已完全迁到通用 `AgentRuntime` 框架。旧 `ClaudeCodeManager` / `BridgeRegistry` / `StreamParser` / `bridge_agent.py` 已物理删除。**Plan F1 M1-M4 已落地**，Codex CLI 经 ACP 接入并完成多 agent UX 收尾：

- **入口面**：`WebSocketConfig.kt`、`ChatServer.broadcast()` 只调 `AgentRuntime.{handleIfActive, cancelIfActive, isAgentMessage}`
- **聊天执行**：`acp_adapter.py` 通过 `/agent-bridge` 端点接收 ACP 请求，复用 `Executor` 跑 Claude CLI，流式推 `session/update` 通知
- **文件系统操作走 ACP**：`/cc-fs/cd` → `_silk/set_cwd`；`/cc-fs/list` → `_silk/list_dir`
- **持久化**：`AgentRuntime.WorkflowPersistence` 接 `WorkflowManager`；prompt response 的 `meta.cliSessionId` 写入 `Workflow.agentSessions[agentType]`（per-agent）；`autoActivateForWorkflow` 用 seed 续会话
- **Multi-agent UX（F1 M4）**：`/codex <text>` inline 文本一步切换+提问；`@codex <text>` 跨 agent 路由不再静默；`Workflow.activeAgent` 持久化，`/use` 切换落盘，下次进工作流自动恢复；前端工作流标题显示 active agent badge
- **`/session <id>` 真正加载**：`AgentRuntime` 调 ACP `session/load`，cc_bridge 复用 `SessionManager.resume_session`，codex_bridge 新增 `codex_session_index.find_session_file`
- **Token 重生踢连接**：`AcpRegistry.disconnect(userId)` 关闭老 ACP 连接
- **TrustedDir bridgeId**：`resolveBridgeId(userId)` 从 `AcpRegistry.getRemoteIp` 拿，格式 `"ip:<remoteIp>"` 不变
- **`/cc-bridge` WebSocket 端点已删除**
- **无回退路径**：ACP 不可用时直接报"未连接"

排查 CC 行为时只看 `AgentRuntime` + `acp_adapter.py`。
