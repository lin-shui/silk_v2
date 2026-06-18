# Slice 174

这份归档保留 `lint-baseline-reduction` 的 Slice 174 完成历史，记录本轮在 `backend` 的 `WeaviateClient` 上继续收敛 detekt。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Summary

- 删除 `config/lint/detekt/backend.xml` 中 `WeaviateClient.kt$WeaviateClient` 对应的 1 条 `LargeClass` baseline。
- `WeaviateClient.kt` 只保留 Weaviate 请求编排、公开搜索/索引 API 和失败恢复入口。
- 新增 `WeaviateClientSupport.kt`，承接文本清洗、GraphQL query 构造/解析、搜索结果映射和索引 JSON helper，保持 Weaviate REST/GraphQL 路径、字段名和回退语义不变。

## Validation

- `git diff --check`
- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`

## Notes

- 这轮沿用 `PDFReportGenerator` 的做法，优先把纯 helper 外提到顶层文件，不在 `WeaviateClient` 内再引入新的大对象，避免把 `LargeClass` 从一个类挪到另一个类。
- 首次跑 `:backend:test` 时暴露了新 helper 文件缺少 `io.ktor.http.isSuccess` 导入；已在同轮补齐并复跑通过，没有留下新的编译噪音。
