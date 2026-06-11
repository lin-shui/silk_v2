# Lint Baseline Reduction Slice 140

这份归档保留 `lint-baseline-reduction` 的 Slice 140 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/routes/FileRoutes.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 将 `fileRoutes()` 收敛为纯路由挂载入口，按上传、下载、版本查询、文件列表与删除拆成独立 `register*Route()` helper。
- 删除 `config/lint/detekt/backend.xml` 中 `FileRoutes.kt$fun Route.fileRoutes()` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- 保持 `/api/files/*` 的路径、请求参数、响应体和异步索引流程不变，没有修改文件合同。

## 验证

- `./gradlew silkLint`
- `./gradlew :backend:test --tests com.silk.backend.BackendFileContractTest`
- `git diff --check`

## 备注

- 这轮选择 `FileRoutes.kt`，是因为它仍属于 backend 路由面，但比直接拆 `Routing.kt` 的整文件聚合复杂度更可控，适合继续按“小文件、单规则、合同不变”的方式推进。
- `FileRoutes.kt` 仍保留 `indexFileToWeaviate(...)` 的复杂度基线；后续若继续在同文件推进，优先单拆该索引 helper，不要把路由注册和索引逻辑混做一刀。
