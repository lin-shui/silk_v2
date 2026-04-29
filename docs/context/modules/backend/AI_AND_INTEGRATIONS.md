# AI And Integrations

## Mainline AI Path

- 当前主线 agent 是 `ai/DirectModelAgent.kt`
- `SilkAgent.kt` 仍保留旧接口与兼容逻辑，但新代码默认沿着 `DirectModelAgent` 看
- `AIConfig.kt` 统一读取：
  - OpenAI-compatible API
  - Weaviate
  - 外部搜索
  - ASR
  - 工具开关

## Tool Calling

- 工具暴露与权限控制由 `ToolPolicyManager.kt` 决定
- `DirectModelAgentToolPolicyTest` 锁定：
  - 禁用工具不暴露
  - 会话作用域拒绝
  - 路径拒绝与审计结果

## Search Stack

- `search/WeaviateClient.kt`:
  - 前景/背景搜索
  - 当前 session 与跨 session 搜索区分
  - 兼顾中文查询 + 英文文件名兜底
- `search/ExternalSearchService.kt`:
  - SerpAPI / Brave / Bing / DuckDuckGo 兜底
- `utils/WebPageDownloader.kt`:
  - URL 提取、HTML/PDF 下载、提取、落盘
  - `WebPageDownloaderSmokeTest` 覆盖本地 smoke

## Claude Code Mode

- 入口面已切到 `agents/core/AgentRuntime.kt`（`WebSocketConfig` 唯一调用点）
- `AgentRuntime` 在 ACP bridge 不可用时回退到旧 `claudecode/ClaudeCodeManager.kt` 执行
- `BridgeRegistry.kt` 管理旧桥 WebSocket；`agents/acp/AcpRegistry.kt` 管理新 ACP 桥
- 详见 `integrations/CLAUDE_CODE_AND_BRIDGES.md` 和 `KNOWN_DRIFT.md#Agent Framework In Transition`

## ASR

- `routes/AsrRoutes.kt` 代理 OpenAI-compatible ASR 服务
- 可选 ffmpeg 转码由 `AIConfig.ASR_TRANSCODE_TO_WAV` 和 `ASR_FFMPEG_PATH` 控制

## Change Checklist

- 改 tool schema / tool permission：更新后端测试
- 改 Weaviate 搜索过滤：确认 session/user 隔离不被破坏
- 改 CC 指令路由：同时查看 `cc_bridge/`
- 改 ASR 协议：同步看 Web/Android/Harmony 调用端
