# Lint Baseline Reduction Slice 69

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 69 完成历史，记录本轮继续在 `backend` 的 `WebPageDownloader.kt` 上做的孤立未使用签名清理。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `backend.xml` 从 165 条降到 163 条。
- `backend/src/main/kotlin/com/silk/backend/utils/WebPageDownloader.kt` 清掉了最后 1 条 `UnusedParameter` baseline，并顺手删除了同文件 1 条 `UnusedPrivateProperty` baseline。
- backend baseline 里的 `UnusedParameter` 已清零；后续 backend lint 可以回到复杂度或异常语义面，不再留有孤立签名噪音。
- 本轮没有改 URL 提取、下载策略、文件保存格式或 WebSocket 文件消息合同。

## Completed Slice

1. Slice 69: 删除 `WebPageDownloader.generateFileName(...)` 上未参与文件名生成的 `url` 参数，并同步收紧 HTML/PDF 调用点，保持文件名生成规则不变。
2. Slice 69: 删除 `WebPageDownloader.kt` 中未被任何分支使用的 `WEB_PAGE_EXTENSIONS` 常量，并从 `backend.xml` 移除对应 2 条 detekt baseline。

## Validation

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮说明“单文件孤立项”适合连同同文件死常量一起收掉，只要保持行为面不变，就能在不扩散风险的情况下继续压 baseline。
