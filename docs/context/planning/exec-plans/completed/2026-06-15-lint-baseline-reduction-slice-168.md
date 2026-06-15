# Lint Baseline Reduction Slice 168

这份归档保留 `lint-baseline-reduction` 的 Slice 168 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/AIStepwiseAgent.kt` 与 `backend/src/main/kotlin/com/silk/backend/Routing.kt` 上继续收敛 detekt baseline。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `AIStepwiseAgent.kt$AIStepwiseAgent$while` 对应的 1 条 `LoopWithTooManyJumpStatements` baseline；当前流式读取 loop 已无需 baseline。
- 删除 `AIStepwiseAgent.kt$AIStepwiseAgent$e: Exception` 对应的 1 条 `SwallowedException` baseline；该文件的命名异常吞没项已不再复现。
- 删除 `AIStepwiseAgent.kt$AIStepwiseAgent$e: Exception` 对应的 1 条 `TooGenericExceptionCaught` baseline；诊断步骤、PDF 生成、流式 HTTP、诊断历史读写与 quick response 的宽 catch 已按取消 / I/O / 中断 / 解析 / 状态失败分层收窄。
- 删除 `Routing.kt$e: Exception` 对应的 1 条 `SwallowedException` baseline；trusted-dir JSON 解析、mark-read、workday 和 user-todo update/delete 的 400 fallback 现在保留异常日志。
- `backend.xml` 余量从 Slice 167 后的 16 条降到 12 条，剩余为 `Routing.kt` 复杂度 1 条、backend `TooGenericExceptionCaught` 3 条与 `LargeClass` 8 条。

## 合同保持

- `AIStepwiseAgent` 的步骤失败仍记录到 `stepResults` 并继续后续步骤；协程取消改为明确透传。
- 流式诊断仍保留部分结果、格式化失败仍回退 fallback report、诊断历史读写失败仍只记录日志并返回既有 fallback。
- `Routing.kt` 相关接口仍返回原 HTTP 状态码和响应体；本轮只补充失败原因日志。

## 验证

- `./gradlew :backend:detekt --rerun-tasks --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`

## 备注

- 这轮没有处理 `LargeClass`，也没有尝试一次性替换 `Routing.kt` / `WebSocketConfig.kt` 的所有 broad catch。
