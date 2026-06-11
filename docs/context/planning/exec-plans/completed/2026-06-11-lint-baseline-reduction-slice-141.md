# Lint Baseline Reduction Slice 141

这份归档保留 `lint-baseline-reduction` 的 Slice 141 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/routes/FileRoutes.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 将 `indexFileToWeaviate(...)` 拆成可索引性检查、文件内容提取、内容清洗、分块准备、单块索引与关键词构造等 helper。
- 删除 `config/lint/detekt/backend.xml` 中 `FileRoutes.kt$private suspend fun indexFileToWeaviate(...)` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- 保持 Weaviate 就绪检查、PDF/text/binary 分流、分块大小、关键词上限、成功块计数与日志语义不变。

## 验证

- `./gradlew silkLint`
- `./gradlew :backend:test --tests com.silk.backend.BackendFileContractTest`
- `git diff --check`

## 备注

- 这轮继续沿用同文件、单规则、小步收敛的节奏，没有改 `/api/files/*` 路径或响应体。
- `FileRoutes.kt` 的复杂度基线现已从路由注册层下沉到更值得继续处理的业务模块；后续可转向 `Routing.kt` 的单一路由族，或换到其它 backend 单函数点继续收敛。
