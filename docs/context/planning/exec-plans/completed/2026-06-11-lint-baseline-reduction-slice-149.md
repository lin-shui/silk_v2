# Lint Baseline Reduction Slice 149

这份归档保留 `lint-baseline-reduction` 的 Slice 149 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/GroupTodoExtractionService.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Completed Work

- 删除 `config/lint/detekt/backend.xml` 中 `GroupTodoExtractionService.kt$private fun parseCompactTodoJson(...)` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- `parseCompactTodoJson(...)` 改为只负责遍历输出数组；单条 todo 解析、可选文本字段提取、`actionType` 规范化与 `done` 回填都下沉到 helper。
- 保持去冗写回合同不变：只接受输入中已有 id，未知/重复 id 继续跳过，空标题或超长标题继续丢弃，原始 `createdAt` / `executedAt` / `reminderId` 继续透传。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`

## Notes

- 这一轮继续沿用“同文件、单函数、单职责”的 Todo lint 收敛方式，没有把 `refreshTodosForUser(...)` 或文件级 broad catch 一起混进来。
