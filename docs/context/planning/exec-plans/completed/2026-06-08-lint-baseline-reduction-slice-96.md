# Lint Baseline Reduction Slice 96

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 96 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/search/ExternalSearchService.kt` 上继续收敛 detekt 的 `ConstructorParameterNaming` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 126 条降到 117 条。
- `ExternalSearchService.kt` 中 SerpAPI / DuckDuckGo / SearXNG 的 9 个 snake_case 或首字母大写响应字段都已改成 `camelCase` Kotlin 属性。
- 外部 JSON 合同未变：所有改名字段都通过 `@SerialName(...)` 维持原始响应字段名。
- 本轮没有改变搜索优先级、回退顺序、超时配置或结果提取逻辑。

## Completed Slice

1. Slice 96: 清理 `SerpAPIResponse` 的 1 条 `ConstructorParameterNaming` baseline。
2. Slice 96: 清理 `DuckDuckGoResponse` / `DuckDuckGoTopic` 的 7 条 `ConstructorParameterNaming` baseline。
3. Slice 96: 清理 `SearXNGResponse` 的 1 条 `ConstructorParameterNaming` baseline。
4. Slice 96: 同步更新 `ExternalSearchService.kt` 内部字段引用，保持反序列化和结果映射行为不变。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这一刀继续沿用“单文件、单规则、小批量”的 backend baseline 收敛方式，没有回到 `Routing.kt` 的聚合 broad-catch。
- 后续若继续处理 `ConstructorParameterNaming`，优先仍走 `@SerialName(...)` 保合同；若切回异常语义，按 active plan 里的 `Routing.kt` 单函数慢拆。
