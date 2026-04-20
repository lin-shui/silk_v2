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
- Backend 测试说明：`backend/src/test/kotlin/com/silk/backend/README_TESTS.md`

## Default Local Commands

- 只读状态：`./silk.sh status`
- Backend 快检：`./gradlew :backend:test`
- Web 快检：`./gradlew :frontend:webApp:nodeTest :frontend:webApp:compileProductionExecutableKotlinJs`
- Android 快检：`./gradlew :frontend:androidApp:testDebugUnitTest :frontend:androidApp:compileDebugKotlin`
- Desktop 快检：`./gradlew :frontend:desktopApp:test :frontend:desktopApp:compileKotlin`

## Build Outputs

- Web 生产构建最终被复制到 `backend/static/`
- Android APK 也会被复制/暴露到 `backend/static/`
- Backend `shadowJar` 产物位于 `backend/build/libs/*-all.jar`

## Configuration Notes

- `silk.sh` 会自动加载项目根 `.env`
- Web / Android / Desktop 的 build 脚本都会从 `.env` 注入后端地址
- 端口与公网/内网分离时，需要特别注意 `BACKEND_HTTP_PORT` 与 `BACKEND_INTERNAL_PORT`
