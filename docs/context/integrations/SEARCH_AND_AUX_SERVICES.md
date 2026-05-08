# Search And Aux Services (Legacy)

## Search Directory

`search/` 不是主应用代码，而是 Weaviate 相关脚本集合（**已弃用**，由 Claude 原生 `web_search` + 后端 grep 替代）：

- `schema.py`
- `indexer.py`
- `reindex_files.py`
- `start.sh`
- `start-native.sh`
- `docker-compose*.yaml`

## Important Fact

- `search/README.md` 是 Weaviate 上游 README，不是 Silk 的项目文档
- Weaviate 已由以下方案替代，`search/` 目录仅为历史遗留：
  1. **网络搜索**: Claude 原生 `web_search` 工具（`AnthropicClient` 转换为 `web_search_20260209` 类型）
  2. **本地检索**: 后端 `searchContext()` 通过 grep 搜索 `_text.txt` 和 `session.json`，受 `accessibleSessionIds` 隔离

## Backend Touchpoints (Legacy)

- ~~`backend/search/WeaviateClient.kt`~~ → 已移除，由 DirectModelAgent.searchContext() 替代
- ~~`backend/search/ExternalSearchService.kt`~~ → 已移除，由 Anthropic web_search 工具替代
- `routes/FileRoutes.kt` 的文件保存逻辑（保留，文件不再索引到 Weaviate）
- `WebSocketConfig.kt` 的消息持久化与 URL 下载入口（保留，Weaviate 索引步骤已移除）

## Runtime Modes

- Weaviate 不再需要运行；`silk.sh` 中相关启动逻辑已标记为遗留
- 后端不再依赖任何外部搜索服务（无需 Docker、无需 API Key）
