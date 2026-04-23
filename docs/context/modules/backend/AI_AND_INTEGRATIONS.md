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

- `claudecode/ClaudeCodeManager.kt` 维护 per-user-per-group CC 状态
- `BridgeRegistry.kt` 管理 backend 与外部 bridge 的 WebSocket
- 聊天消息在 `ChatServer.broadcast()` 中先被 CC 模式拦截，再决定是否进入 Silk AI 主链

## ASR

- `routes/AsrRoutes.kt` 代理 OpenAI-compatible ASR 服务
- 可选 ffmpeg 转码由 `AIConfig.ASR_TRANSCODE_TO_WAV` 和 `ASR_FFMPEG_PATH` 控制

## Change Checklist

- 改 tool schema / tool permission：更新后端测试
- 改 Weaviate 搜索过滤：确认 session/user 隔离不被破坏
- 改 CC 指令路由：同时查看 `cc_bridge/`
- 改 ASR 协议：同步看 Web/Android/Harmony 调用端
