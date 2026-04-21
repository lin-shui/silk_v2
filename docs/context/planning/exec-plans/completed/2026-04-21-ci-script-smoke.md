# 2026-04-21 CI Script Smoke

## 目标

按质量文档里的“后续专用 CI”方向，新增独立脚本级 smoke workflow，覆盖 `silk.sh build` 和 `silk.sh build-apk`，不把更重的装配检查塞进 `ci-fast-validation.yml`。

## 受影响代码面

- `.github/workflows/ci-script-smoke.yml`
- `docs/context/quality/CI_SCRIPT_SMOKE_SCOPE.md`
- `docs/context/quality/INDEX.md`
- `docs/context/quality/CI_FAST_VALIDATION_SCOPE.md`
- `docs/context/project/BOOTSTRAP.md`

## 风险

- Android runner 上 `silk.sh build-apk` 依赖 SDK 路径；workflow 需要显式安装 SDK 并写 `local.properties`。
- `backend/static/` 仓库内已有大量静态文件与历史 APK，artifact 上传必须只挑本次 smoke 关心的产物。
- 脚本 smoke 比快检更重，应独立 workflow，避免拉长基础 PR 反馈时间。

## 验证

- `git diff --check`
- Ruby `YAML.load_file` 解析新增和修改后的 workflow 文件

## 当前状态

- 已完成：新增独立脚本 smoke workflow，并把质量文档更新到“快检 + 脚本 smoke”双层基线。
