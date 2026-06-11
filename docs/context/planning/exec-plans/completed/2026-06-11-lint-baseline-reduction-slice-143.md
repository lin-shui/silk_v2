# Lint Baseline Reduction Slice 143

这份归档保留 `lint-baseline-reduction` 的 Slice 143 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/search/WeaviateClient.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 将 `indexDocument(...)` 拆成文档清洗、`SilkContext` JSON 构造和请求结果记录 helper。
- 删除 `config/lint/detekt/backend.xml` 中 `WeaviateClient.kt$WeaviateClient$suspend fun indexDocument(...)` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- 保持 Weaviate 请求地址、字段名、JSON 转义、成功/失败日志和异常时返回 `false` 的既有语义不变。

## 验证

- `./gradlew silkLint`
- `./gradlew :backend:test`
- `git diff --check`

## 备注

- 这轮继续沿用“单文件、单规则、小步快跑”的 backend lint 收敛方式，没有顺手处理 `WeaviateClient.kt` 的 `TooGenericExceptionCaught` 或 `LargeClass`。
- `SEARCH_AND_AUX_SERVICES.md` 里对 Weaviate 现状的描述与当前代码仍有偏差，但这不是本轮 lint slice 新引入的事实变化；本轮未扩散到文档修订。
