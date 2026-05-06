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

## Agent Framework In Transition (Plan E + E2 + E3 Done, E4 Pending)

`develop_acp` 分支把 CC 模式从 `ClaudeCodeManager` 迁到通用 `AgentRuntime` 框架。**Plan E + E2 + E3 已完成**，所有业务代码完全走 ACP，不再引用旧 API：

- **入口面**：`WebSocketConfig.kt`、`ChatServer.broadcast()` 只调 `AgentRuntime.{handleIfActive, cancelIfActive, isAgentMessage}`
- **聊天执行**：`acp_adapter.py` 通过 `/agent-bridge` 端点接收 ACP 请求，复用 `Executor` 跑 Claude CLI，流式推 `session/update` 通知
- **文件系统操作走 ACP**：`/cc-fs/cd` → `_silk/set_cwd`；`/cc-fs/list` → `_silk/list_dir`
- **持久化**：`AgentRuntime.WorkflowPersistence` 接 `WorkflowManager`；prompt response 的 `meta.ccSessionId` 写入 `Workflow.sessionId`；`autoActivateForWorkflow` 用 seed 续会话
- **Token 重生踢连接**：`AcpRegistry.disconnect(userId)` 关闭老 ACP 连接
- **TrustedDir bridgeId**：`resolveBridgeId(userId)` 从 `AcpRegistry.getRemoteIp` 拿，格式 `"ip:<remoteIp>"` 不变
- **`/cc-bridge` WebSocket 端点已删除**
- **无回退路径**：ACP 不可用时直接报"未连接"，不再走 ClaudeCodeManager
- **旧代码孤岛**：`backend/claudecode/ClaudeCodeManager.kt` 和 `BridgeRegistry.kt` 仍在文件系统中但无人引用（E4 物理删除）

排查 CC 行为时只看 `AgentRuntime` + `acp_adapter.py`，旧 `ClaudeCodeManager` / `bridge_agent.py` 不再有效。

Plan E4: 物理删除 `claudecode/` 包 + `bridge_agent.py`，`bridge.sh` 移除 `BRIDGE_MODE=legacy` 选项。

## Multi-Agent Routing UX Defects (Codex M1 暴露)

接入 Codex（M1）时发现两个 ACP 抽象层（Plan E）就埋下、但单 agent 时代未被触发的 UX 缺陷。两个都不是 Codex 接入引入的，但只有多 agent 在线时才暴露。

### 1. Trigger 命令不支持 inline 文本

- 现象：`/codex 你是谁`、`/cc 你是谁` 这类**命令带文本**的写法**不会触发切换**
- 病灶：`backend/.../agents/core/CommandRouter.kt:55-58` 用 `==` 严格匹配 trigger
  ```kotlin
  if (trimmed.lowercase() == d.triggerCommand.lowercase()) {
      return RouteResult.TriggerAgent(d.agentType)
  }
  ```
  `/codex 你是谁` 因为不严格等于 `/codex` 不命中第 3 条，落到第 7 条"普通 prompt"，发给 `ctx.currentAgentType`
- 现行 workaround：先 `/use codex` 再发 prompt；或用 `@codex <text>`（但 `@` 也有问题，见下）
- 修复方向：`CommandRouter` 第 3 条改为 `startsWith(trigger + " ")` 也命中，把 trigger + 剩余文本当一次性 prompt

### 2. `@xxx <text>` 当前 agent ≠ 目标时静默不回复（多 agent 必现）

- 现象：在 Silk 普通会话（`ctx.currentAgentType == null`）发 `@codex 你是谁` **完全没回复**；在工作流（auto-activated `claude-code`）发 `@codex 你是谁` 报"Claude Code 未连接"
- 病灶：`backend/.../agents/core/AgentRuntime.kt`
  - `handleAtAgent` (第 322 行) 调 `handlePrompt(ctx, route.text, ...)` **不传 agentType**
  - `handlePrompt` (第 519 行) `val agentType = ctx.currentAgentType ?: return` 只读 ctx，无视 `@` 的目标 agent
  - `?: return` 是静默返回，连错误消息都不发
- 巧合掩盖：当 `ctx.currentAgentType == @ 的目标 agent` 时表现正常，所以 Claude Code 单 agent 时代从未暴露
- 现行 workaround：先 `/use codex` 再发普通 prompt
- 修复方向：`handlePrompt` 加 `overrideAgentType: String? = null` 参数，`handleAtAgent` 第 322 行传过去；ctx.currentAgentType 不被污染

### 3. 工作流默认 agent 仍硬编码为 claude-code

- 现象：进入新建/已有工作流时，`ctx.currentAgentType` 被自动设为 `"claude-code"`，无论 codex 是否已连接
- 病灶：`Routing.kt:2419` 调 `AgentRuntime.autoActivateForWorkflow(userId, "group_$groupId", "claude-code")`，agent 字面量硬编码
- 影响：Codex bridge 单独连着时，进工作流默认仍是 CC 模式；用户必须显式 `/use codex` 切换
- 修复方向：把 `autoActivateForWorkflow` 默认改为读 workflow 持久化字段（新增 `Workflow.activeAgentType`）；或后端首选已连接 agent

### 处置

三处都不修，统一留到 Codex M4 收尾后做一次"多 agent UX 修复" PR。M2/M3 期间只新增能力，不改这三处行为。
