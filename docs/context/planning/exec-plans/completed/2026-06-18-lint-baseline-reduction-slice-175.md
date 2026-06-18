# Slice 175

这份归档保留 `lint-baseline-reduction` 的 Slice 175 完成历史，记录本轮在 backend `UserTodoStore` 上继续收敛 detekt。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Summary

- 删除 `config/lint/detekt/backend.xml` 中 `UserTodoStore.kt$UserTodoStore` 对应的 1 条 `LargeClass` baseline。
- `UserTodoStore.kt` 只保留文件读写、生命周期 merge、部分字段更新和去重入口。
- 新增 `UserTodoStoreSupport.kt`，承接周期模板实例化、更新字段解析、logical-key 归一化和包含式标题合并 helper，保持 `/api/user-todos*` 存储结构与 Todo 去重/模板实例化合同不变。

## Validation

- `git diff --check`
- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`

## Notes

- 这轮继续避开已脏的 `WebSocketConfig.kt` / `Routing.kt`，优先处理未改动的 backend `LargeClass` 面，减少和现有工作区修改冲突。
- 新 helper 仍采用顶层函数拆分，没有再引入新的大对象，避免把 `LargeClass` 从 `UserTodoStore` 平移到别的类。
