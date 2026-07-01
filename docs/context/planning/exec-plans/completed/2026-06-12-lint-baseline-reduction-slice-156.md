# Lint Baseline Reduction Slice 156

这份归档保留 `lint-baseline-reduction` 的 Slice 156 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/todos/UserTodoStore.kt` 上继续收敛 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## 本轮完成

- 删除 `config/lint/detekt/backend.xml` 中 `UserTodoStore.kt$fun updateItem(...)` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- `updateItem(...)` 现在只负责更新主流程编排；lifecycle/done/closedAt、提醒 ID 和可选字符串字段解析都已下沉到 helper。
- 保持 Todo 更新合同不变：`cancelled`/`deferred` 在 `done=true` 时仍保持关闭态、blank 输入仍清空 `actionType`/`actionDetail`/`repeat*`/`templateId`/`dateBucket`，`clearReminderId` 仍优先于显式 reminderId，`closedAt<=0` 仍回退 `null`。
- 新增 `UserTodoStoreTest` 窄测试，锚定取消态更新和可选字段清空语义，避免后续再为压复杂度误改 Todo 持久化行为。

## 验证

- `./gradlew :backend:test --tests com.silk.backend.todos.UserTodoStoreTest`
- `./gradlew :backend:detekt`
- `./gradlew silkLint`
- `git diff --check`

## 备注

- 这轮继续留在 Todo 面单函数、小步收敛，没有把 `refreshTodosForUser(...)`、文件级 broad-catch 或 `LargeClass` 条目一并混入。
