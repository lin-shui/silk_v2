# Bootstrap

## Runtime Prerequisites

- Java 17
- Python 3
- Docker（本地跑 Weaviate 时）
- Android SDK（改 Android 时）
- DevEco / hvigor / hdc（改 Harmony 时）

## Build System Facts

- 根工程是 Gradle Kotlin DSL 多模块工程。
- 包含模块：
  - `:backend`
  - `:frontend:shared`
  - `:frontend:androidApp`
  - `:frontend:desktopApp`
  - `:frontend:webApp`
- Harmony、`cc_bridge/`、`feishu_bot/`、`search/` 不在 Gradle 主工程里。

## Operational Truth Files

- 环境变量模板：`.env.example`
- 统一运维脚本：`silk.sh`
- 快检工作流：`.github/workflows/ci-fast-validation.yml`
- 脚本 smoke 工作流：`.github/workflows/ci-script-smoke.yml`（`build` / `build-apk` / `build-all` / `deploy` 装配 smoke，以及 `start` / `stop` 运行态 smoke）
- CI 自动开启 auto-merge 工作流：`.github/workflows/auto-enable-ci-branch-merge.yml`（仅针对 base=`chore/ci-auto-merge` 的 PR）
- Lint 配置与 baseline：`config/lint/detekt.yml`、`config/lint/detekt/`
- Backend 测试说明：`backend/src/test/kotlin/com/silk/backend/README_TESTS.md`

## CI Script Smoke Notes

- `start` smoke 使用真实 backend 启动、本地 Weaviate readiness mock 和预置 Web 静态 fixture，验证 `/health`、前端静态服务和 `stop` 清理。
- `deploy` smoke 使用 Gradle/backend stub 和本地 Weaviate mock，验证端口清理、构建编排、产物复制和最终端口就绪。

## Default Local Commands

- 只读状态：`./silk.sh status`
- 仓库 lint：`./gradlew silkLint`
- lint baseline 刷新：`./gradlew silkLintBaseline`
- Backend 快检：`./gradlew :backend:test`
- Web 快检：`./gradlew :frontend:webApp:nodeTest :frontend:webApp:compileProductionExecutableKotlinJs`
- Android 快检：`./gradlew :frontend:androidApp:testDebugUnitTest :frontend:androidApp:compileDebugKotlin`
- Desktop 快检：`./gradlew :frontend:desktopApp:test :frontend:desktopApp:compileKotlin`

## Build Outputs

- Web 生产构建最终被复制到 `backend/static/`
- Android APK 也会被复制/暴露到 `backend/static/`
- Harmony HAP 可通过 `./silk.sh build-hap` 复制/暴露到 `backend/static/`
- Backend `shadowJar` 产物位于 `backend/build/libs/*-all.jar`

## Configuration Notes

- `silk.sh` 会自动加载项目根 `.env`
- Web / Android / Desktop 的 build 脚本都会从 `.env` 注入后端地址
- 未配置时端口默认并不完全一致：后端运行入口常回落到 `8003`，`silk.sh` 的 Web 前端静态服务器默认 `8005`，Web/Android `FRONTEND_PORT` 生成 fallback 仍是 `8004`；详见 `KNOWN_DRIFT.md`
- 端口与公网/内网分离时，需要特别注意 `BACKEND_HTTP_PORT` 与 `BACKEND_INTERNAL_PORT`
- Backend SQLite 默认使用 `./silk_database.db`；测试或隔离运行可通过 JVM 参数 `-Dsilk.databasePath=...` 覆盖
- Workflow / TrustedDir 默认写到 `~/.silk-data/workflows`；`silk.sh` 会通过 `SILK_WORKFLOW_DIR` / `-Dsilk.workflowDir=...` 注入同一目录
