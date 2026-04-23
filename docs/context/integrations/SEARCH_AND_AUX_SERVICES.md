# Search And Aux Services

## Search Directory

`search/` 不是主应用代码，而是 Weaviate 相关脚本集合：

- `schema.py`
- `indexer.py`
- `reindex_files.py`
- `start.sh`
- `start-native.sh`
- `docker-compose*.yaml`

## Important Fact

- `search/README.md` 是 Weaviate 上游 README，不是 Silk 的项目文档
- 对 Silk 而言，真正有价值的是脚本、schema 与后端调用点

## Backend Touchpoints

- `backend/search/WeaviateClient.kt`
- `backend/search/ExternalSearchService.kt`
- `routes/FileRoutes.kt` 的文件索引
- `WebSocketConfig.kt` 的消息索引与 URL 下载入口

## Runtime Modes

- 本地 Docker / native Weaviate 都可能被 `silk.sh` 使用
- 如果未配置 Weaviate，后端部分路径会跳过索引，不应把外部索引可用性硬绑进基础快检
