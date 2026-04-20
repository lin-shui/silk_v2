# Quality Index

## Primary Files

- 快检范围： [CI_FAST_VALIDATION_SCOPE.md](CI_FAST_VALIDATION_SCOPE.md)
- 变更到验证映射： [TEST_MATRIX.md](TEST_MATRIX.md)
- CI workflow： `../../../.github/workflows/ci-fast-validation.yml`
- Backend 测试说明： `../../../backend/src/test/kotlin/com/silk/backend/README_TESTS.md`

## Default Principle

- 先跑与变更面直接相关的最小检查
- 尽量复用 CI 已存在的快检入口
- 改合同先补真实测试，不补字符串占位测试
