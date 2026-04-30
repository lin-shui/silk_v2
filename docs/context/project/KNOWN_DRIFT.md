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

## Agent Framework In Transition (Plan E + E2 Done, E3-E4 Pending)

`develop_acp` 分支把 CC 模式从 `ClaudeCodeManager` 迁到通用 `AgentRuntime` 框架。**Plan E + E2 已完成**，ACP 已能覆盖所有 CC 功能（不只聊天）：

- **入口面**：`WebSocketConfig.kt`、`ChatServer.broadcast()` 只调 `AgentRuntime.{handleIfActive, cancelIfActive, isAgentMessage}`
- **聊天执行**：`acp_adapter.py`（新默认桥）通过 `/agent-bridge` 端点接收 ACP 请求，复用 `Executor` 跑 Claude CLI，流式推 `session/update` 通知
- **文件系统操作走 ACP（E2 新增）**：
  - `/cc-fs/cd` → `AgentRuntime.cdSync()` → `_silk/set_cwd` 扩展
  - `/cc-fs/list` → `AgentRuntime.listDirectory()` → `_silk/list_dir` 扩展
  - 创建工作流（`POST /api/workflows`）的 cdSync 也走 ACP
- **持久化（E2 新增）**：`AgentRuntime.WorkflowPersistence` 接口接 `WorkflowManager`；`session/prompt` response 通过 `meta.ccSessionId` 把 Claude CLI session id 报回，写入 `Workflow.sessionId`；重启后 `autoActivateForWorkflow` 从 seed 拿 ccSessionId，下次 `session/new` 带过去让 adapter resume
- **Token 重生踢连接（E2 新增）**：`AcpRegistry.disconnect(userId)` 关闭老 ACP 连接；`/cc-settings/generate-token` 同时清理两路注册表
- **TrustedDir bridgeId（E2 新增）**：抽出 `resolveBridgeId(userId)` helper，ACP 优先 + 旧桥兜底，格式 `"ip:<remoteIp>"` 不变以兼容已有 trust 记录
- **回退路径仍在**：`AgentRuntime.handlePrompt` 在 ACP 不可用时回退到 `ClaudeCodeManager`；`/cc-fs/list` 在 ACP 不可用时也回退；`BRIDGE_MODE=legacy` 可启动旧桥 `bridge_agent.py`
- **旧代码未删**：`ClaudeCodeManager` / `BridgeRegistry` / `/cc-bridge` 端点仍在代码中（E3/E4 清理）；`Routing.kt#configureRouting` 双 wiring 持久化

排查 CC 行为时优先看 `AgentRuntime`（入口/路由/状态）+ `acp_adapter.py`（执行）。旧 `ClaudeCodeManager` / `bridge_agent.py` 仅作回退参考。

Plan E3: 清除所有旧 API 调用点（让旧 object 无人引用）；E4: 删除旧代码。
