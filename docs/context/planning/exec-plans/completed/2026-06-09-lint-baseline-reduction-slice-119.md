# Lint Baseline Reduction Slice 119

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 119 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/routes/FileRoutes.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 78 条降到 77 条。
- `FileRoutes.kt` 不再使用 `catch (e: Exception)`；上传主链、异步索引与 PDF 文本提取统一改成 `runCatching` 收口。
- coroutine cancellation 现在在文件上传后的异步索引链路显式透传，不再被 broad catch 误吞；其余失败仍保持原有日志和状态消息语义。

## Completed Slice

1. Slice 119: 清理 `FileRoutes.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 119: 保持上传失败返回 500、索引失败只回写状态消息、PDF 提取失败回退 `null` 的既有合同。
3. Slice 119: 把 PDF 文本提取改成 `PDDocument.load(...).use { ... }`，避免异常路径遗漏关闭文档句柄。

## Validation

- `./gradlew :backend:detekt`
- `./gradlew :backend:test --tests com.silk.backend.BackendFileContractTest`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这一轮继续沿用“小文件、单规则、单职责”的 backend lint 收敛方式，没有把 `Routing.kt` 或 `WebSocketConfig.kt` 这类聚合面混进来。
- `FileRoutes.kt` 的 broad catch 是文件级聚合签名，所以本轮必须一次性清掉同文件里的所有 `catch (e: Exception)`，不能只修局部。
