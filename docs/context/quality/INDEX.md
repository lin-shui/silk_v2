# Quality Index

## Primary Files

- 快检范围： [CI_FAST_VALIDATION_SCOPE.md](CI_FAST_VALIDATION_SCOPE.md)
- 脚本 smoke 范围： [CI_SCRIPT_SMOKE_SCOPE.md](CI_SCRIPT_SMOKE_SCOPE.md)
- 变更到验证映射： [TEST_MATRIX.md](TEST_MATRIX.md)
- CI workflow： `../../../.github/workflows/ci-fast-validation.yml`
- 脚本 smoke workflow： `../../../.github/workflows/ci-script-smoke.yml`
- CI auto-merge workflow： `../../../.github/workflows/auto-enable-ci-branch-merge.yml`
- Backend 测试说明： `../../../backend/src/test/kotlin/com/silk/backend/README_TESTS.md`
- Lint 配置： `../../../config/lint/detekt.yml`、`../../../config/lint/detekt/`

## Default Principle

- 先跑与变更面直接相关的最小检查
- 尽量复用 CI 已存在的快检入口
- 改合同先补真实测试，不补字符串占位测试
- Kotlin 源码静态分析 / shell 入口 lint 使用 `./gradlew silkLint`；源码静态分析历史问题通过 detekt baseline 固化，新增问题应直接修复或有理由后再更新 baseline
