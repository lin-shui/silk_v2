# Slice 176

这份归档保留 `lint-baseline-reduction` 的 Slice 176 完成历史，记录本轮在 backend `GroupTodoExtractionService` 上继续收敛 detekt。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Summary

- 删除 `config/lint/detekt/backend.xml` 中 `GroupTodoExtractionService.kt$GroupTodoExtractionService` 对应的 1 条 `LargeClass` baseline。
- `GroupTodoExtractionService.kt` 只保留刷新编排、diagnostics 写回和对外入口。
- 新增 `GroupTodoExtractionSupport.kt`，承接 transcript 构造、启发式/周期模板抽取、LLM prompt/调用、JSON 解析和去冗 helper，保持 `/api/user-todos*` 的抽取写回、周期模板、logical-key 去重与失败回退语义不变。

## Validation

- `git diff --check`
- `./gradlew :backend:detekt`
- `./gradlew :backend:test`
- `./gradlew silkLint`

## Notes

- 这轮继续避开已脏的 `WebSocketConfig.kt` / `Routing.kt`，优先处理未改动的 backend `LargeClass` 面，减少与现有工作区修改冲突。
- 新 helper 仍采用顶层函数拆分，没有再引入新的大对象，避免把 `LargeClass` 从 `GroupTodoExtractionService` 平移到别的类。
