# Lint Baseline Reduction Slice 121

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 121 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/ai/ToolPolicyManager.kt` 上继续收敛 detekt 的复杂度基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 74 条降到 72 条。
- `ToolPolicyManager.kt` 的配置文件加载与路径访问判定已拆成 helper，不再把配置读取、策略 merge、黑名单校验和白名单校验都堆在两个深层函数里。
- 既有权限合同保持不变：配置文件缺失仍回退默认策略，非法权限枚举仍回退 `DISABLED`，路径检查仍是黑名单优先于白名单。

## Completed Slice

1. Slice 121: 清理 `ToolPolicyManager.kt` 的 1 条 `NestedBlockDepth` baseline（`loadConfigFromFile`）。
2. Slice 121: 清理 `ToolPolicyManager.kt` 的 1 条 `NestedBlockDepth` baseline（`validateFilePath`）。
3. Slice 121: 复用现有 `ToolPolicyManagerTest` 继续锚定沙箱路径与安全命令判定，不新增合同变化。

## Validation

- `./gradlew :backend:test --tests com.silk.backend.ai.ToolPolicyManagerTest`
- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`
- `git diff --check`

## Notes

- 这轮继续沿用“单文件、单职责、小步快跑”的 backend lint 收敛方式，没有把 `Routing.kt` 或 `WebSocketConfig.kt` 这类文件级聚合面混进来。
- 由于 `ToolPolicyManager.kt` 在 Slice 118 已做过异常语义收敛，这一轮只做结构拆分，不改权限策略、返回文案或测试期望。
