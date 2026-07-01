# AI And Integrations

## Mainline AI Path

- 当前主线 agent 是 `ai/DirectModelAgent.kt`
- `SilkAgent.kt` 仍保留旧接口与兼容逻辑，但新代码默认沿着 `DirectModelAgent` 看
- `DirectModelAgent.processInput(...)` 关键参数：
  - `availableReferences`：本轮可引用的 KB 条目列表（含 `spaceId`/`spaceLabel`/`origin`/`reason`），经 `registerReference` 写入 `MessageReference`
  - `additionalContext: String?`：由 `resolveKnowledgeBasePromptContext` 生成的 KB prompt 文本块，追加到系统提示词末尾
  - `callback`：流式回调（thinking/text/blocks_state/tool 相关事件）
- `AIConfig.kt` 统一读取：
  - Anthropic Claude API（Messages API）
  - ASR
  - 工具开关

## Anthropic Client

- `ai/AnthropicClient.kt` 封装与 Anthropic Messages API 的通信：
  - SSE 流式解析（content_block_start/delta/stop + message_delta，含 thinking_delta）
  - 结构化 content block 追踪：流式过程中维护 `streamingBlocks` 映射（thinking/text/tool_use），通过 `blocks_state` 回调每步推送到前端实时渲染
  - 内部 Message ↔ Anthropic 格式双向转换
  - 工具定义转换（custom tools → {name, description, input_schema}，web_search → 原生 `web_search_20260209`）
  - tool_use 收集与残缺 JSON 修复

## Tool Calling

- 工具暴露与权限控制由 `ToolPolicyManager.kt` 决定
- `backend/src/main/resources/tool_policy.json` 是默认工具权限配置
- `DirectModelAgentToolPolicyTest` 锁定：
  - 禁用工具不暴露
  - 会话作用域拒绝
  - 路径拒绝与审计结果
- `DirectModelAgentAutoCliTest` 锁定 AutoCLI 沙箱、白名单与可选集成执行
- `DirectModelAgentCitationTest` 锁定 citation / available 引用标记、搜索证据格式化与引用重编号
- `WebSocketConfig.kt` 会把聊天 / 工作流消息中的 `[[kb:entryId|标题]]` 解析成当前轮可用资料，并要求 `DirectModelAgent` 以 `[available:N]` 标注引用

## Search Stack

- Claude 原生 `web_search` 工具（由 AnthropicClient 转换为 `web_search_20260209` 类型）：
  - 替代旧 SerpAPI / Brave / Bing / SearXNG 外部搜索
  - 模型自动触发，无需后端编排
- `searchContext()` 后端 grep 搜索（替代 Weaviate）：
  - 基于 `DirectModelAgent.accessibleSessionIds` 限制搜索范围
  - 搜索 `_text.txt`（PDF 提取文本）和 `session.json`（聊天消息）
  - 结果截断至 30000 字符，路径层级限制防逃逸
- `writeOtherGroupsHistories()` 跨群上下文注入：
  - 仅在 Silk 专属对话中触发（`accessibleSessionIds.size > 1`）
  - 遍历用户所有群组，读取最近 50 条 TEXT 消息
  - 写入 `workspaceDir/other_groups/chat_history_<群名>.md`
  - AI 可通过 Grep/Read 工具跨群搜索，提示词中明确告知跨群访问权限
- `utils/WebPageDownloader.kt`：
  - URL 提取、HTML/PDF 下载、提取、落盘
  - `WebPageDownloaderSmokeTest` 覆盖本地 smoke

## Agent Framework / ACP

- 入口面：`agents/core/AgentRuntime.kt`（`WebSocketConfig` 唯一调用点）
- Agent 描述符：`agents/adapters/claudecode/ClaudeCodeDescriptor.kt`、`agents/adapters/codex/CodexDescriptor.kt`
- 执行面：`cc_bridge/acp_adapter.py` 与 `codex_bridge/codex_adapter.py`（外部进程）通过 ACP 协议连接 `/agent-bridge` 端点；`agents/acp/AcpRegistry.kt` 管理连接
- ACP 不可用时直接报"未连接"，无 fallback
- 详见 `integrations/CLAUDE_CODE_AND_BRIDGES.md`

## ASR

- `routes/AsrRoutes.kt` 代理 OpenAI-compatible ASR 服务
- 可选 ffmpeg 转码由 `AIConfig.ASR_TRANSCODE_TO_WAV` 和 `ASR_FFMPEG_PATH` 控制

## Audio Duplex

- `Routing.kt` 的 `/ws/audio-duplex?sessionId=...` 是代理入口
- 上游地址来自 `AIConfig.AUDIO_DUPLEX_URL`，默认 `http://localhost:22700`
- Web / Android / Harmony 均有 Audio Duplex 页面；Desktop 当前无主壳承载

## Change Checklist

- 改 tool schema / tool permission：更新后端测试
- 改 AnthropicClient 格式转换：同步验证 convertMessage / convertTool 双向兼容
- 改 grep searchContext：确认 accessibleSessionIds 隔离不被破坏
- 改 agent 指令路由：看 `agents/core/CommandRouter.kt` + 对应 adapter；外部 adapter：同时查看 `cc_bridge/`、`codex_bridge/`
- 改 ASR 协议：同步看 Web/Android/Harmony 调用端
- 改 Audio Duplex 代理协议：同步看 Web/Android/Harmony Audio Duplex 调用端
