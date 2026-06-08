# Lint Baseline Reduction Slice 107

## Scope

这份归档保留 `lint-baseline-reduction` 的 Slice 107 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/database/UserRepository.kt` 上继续收敛 detekt 的 `TooGenericExceptionCaught` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Outcome Snapshot

- `config/lint/detekt/backend.xml` 从 91 条降到 90 条。
- `UserRepository.createUser()` 不再用 `catch (e: Exception)` 吞掉所有失败；现只对 `ExposedSQLException` / `SQLException` 这类数据库写入失败回退 `null`。
- 新增 repository 级测试，锁定唯一键冲突时 `createUser()` 继续返回 `null`，不改变上层注册失败语义。

## Completed Slice

1. Slice 107: 清理 `UserRepository.kt` 的 1 条 `TooGenericExceptionCaught` baseline。
2. Slice 107: 保持 `createUser()` 在数据库唯一键冲突等写入失败场景下回退 `null`，不改变 `AuthService` / HTTP 注册路径的对外合同。
3. Slice 107: 新增 `UserRepositoryTest`，直接覆盖重复 `loginName` / `phoneNumber` 的失败分支，避免后续再把 repository 创建失败路径打回 broad catch。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`
- `git diff --check`

## Notes

- 这轮继续沿用“小文件、单规则、补行为测试”的 backend lint 收敛策略，没有扩散到 `ContactRepository` / `GroupRepository` 这类同目录但多 catch 聚合面。
- `UserRepository.kt` 已没有 detekt broad-catch baseline；若后续回到用户/认证存储面，优先继续处理相邻 repository 的单点异常语义，而不是回填基线。
