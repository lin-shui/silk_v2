# Android Lint Baseline Closeout

## Scope

这份归档记录 2026-06-05 对 `frontend/androidApp` detekt baseline 清零状态的收尾复验，以及 active plan 的拆分。源码和 baseline 本轮未再新增变更；重点是确认 Android lint 已可从活跃待办中移除。

## Outcome Snapshot

- `config/lint/detekt/frontend-androidApp.xml` 仍为空，`<CurrentIssues/>` 为 0。
- `:frontend:androidApp:detekt` 与 `:frontend:androidApp:compileDebugKotlin` 复验通过。
- active plan 已改成只保留 backend / shared 的剩余 lint slice；Android lint 不再作为活跃待办。

## Completed Slice

1. Closeout: 复核 Android baseline 文件状态，确认 `frontend-androidApp.xml` 没有遗留 issue。
2. Closeout: 重新运行 Android detekt 与 Kotlin 编译，确认没有因为近期代码漂移重新引入 baseline 依赖。
3. Closeout: 精简 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)，把 Android 完成态从活跃计划中移除，仅保留未完成的 backend / shared 待办。

## Validation

- `./gradlew :frontend:androidApp:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :frontend:androidApp:compileDebugKotlin --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- Android baseline 的实际清零实现仍以 [2026-06-02-lint-baseline-reduction-slice-85.md](./2026-06-02-lint-baseline-reduction-slice-85.md) 为准；本文件只记录收尾复验和计划拆分。
- `:frontend:androidApp:testDebugUnitTest` 的既有 `JdkImageTransform/jlink` 环境阻塞没有在本轮处理，也不影响“Android detekt baseline 已清零”的结论。
