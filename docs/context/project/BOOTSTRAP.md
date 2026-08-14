# Bootstrap

## Runtime Prerequisites

- Java 17
- Python 3
- Go 1.22+（构建/测试独立 companion `silk-agent/`）
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
- CI 自动开启 auto-merge 工作流：`.github/workflows/auto-enable-ci-branch-merge.yml`（仅针对 base=`chore/ci-auto-merge` 的 PR；无权限启用 auto-merge 时记录 notice 并非阻塞退出）
- Lint 配置与 baseline：`config/lint/detekt.yml`、`config/lint/detekt/`
- Backend 测试说明：`backend/src/test/kotlin/com/silk/backend/README_TESTS.md`

## CI Script Smoke Notes

- `start` smoke 使用真实 backend 启动、本地 Weaviate readiness mock 和预置 Web 静态 fixture，验证 `/health`、Web `/`、Web `/device` SPA fallback 和 `stop` 清理。
- `silk.sh start/deploy` 通过 `scripts/silk_static_server.py` 提供 Web 静态产物；除普通文件外，`/device` 会返回 SPA `index.html`，用于外部 Agent 配对确认直达。
- `deploy` smoke 使用 Gradle/backend stub 和本地 Weaviate mock，验证端口清理、构建编排、产物复制和最终端口就绪。

## Default Local Commands

- 只读状态：`./silk.sh status`
- 仓库 lint：`./gradlew silkLint`
- lint baseline 刷新：`./gradlew silkLintBaseline`
- Backend 快检：`./gradlew :backend:test`
- Web 快检：`./gradlew :frontend:webApp:nodeTest :frontend:webApp:compileProductionExecutableKotlinJs`
- Android 快检：`./gradlew :frontend:androidApp:testDebugUnitTest :frontend:androidApp:compileDebugKotlin`
- Desktop 快检：`./gradlew :frontend:desktopApp:test :frontend:desktopApp:compileKotlin`
- silk-agent 快检：`cd silk-agent && go test ./... && go test -race ./... && go vet ./...`（交叉编译 Windows：`GOOS=windows GOARCH=amd64 go test -c`）；Host 会在随 binary 提供的 `adapters/silk-<agent-type>-adapter` 存在时纳管它，以单一设备 WSS 按 `agentInstanceId` 多路复用，已运行时可通过控制 IPC 热加载新批准的 Agent。`silk-agent/scripts/package-release.sh` 构建六平台 bundle、签名 manifest 并执行完整性复验；正式发布需要 Ed25519 私钥/公钥文件。
- Direct Bridge 快检：`python3 -m pytest bridge_common/tests cc_bridge/tests codex_bridge/tests -q`；Adapter 只使用 Python 标准库，另需设备用户已安装并完成认证的 Claude/Codex CLI。Host IPC v2 只转发 ACP，不向 Adapter 传递旧 Token、设备签名接口或后端连接；升级 Host 时必须同步替换 Adapter bundle。

## Build Outputs

- Web 生产构建最终被复制到 `backend/static/`
- Android APK 也会被复制/暴露到 `backend/static/`
- Harmony HAP 可通过 `./silk.sh build-hap` 复制/暴露到 `backend/static/`
- Backend `shadowJar` 产物位于 `backend/build/libs/*-all.jar`

## Configuration Notes

- `silk.sh` 会自动加载项目根 `.env`
- `silk.sh` 在 Android 相关命令前会按以下顺序解析 JDK / Android SDK：已有环境变量（含 `.env`）→ `local.properties` → 系统默认安装路径；若解析成功，会同步 `sdk.dir` / `org.gradle.java.home` 到 `local.properties`
- `silk.sh build` / `build-all` / `deploy` 若遇到 Kotlin/JS 的 `kotlinStoreYarnLock` 漂移，会自动执行 `kotlinUpgradeYarnLock` 刷新 `kotlin-js-store/yarn.lock` 后重试一次 Web production build
- Web / Android / Desktop 的 build 脚本都会从 `.env` 注入后端地址
- 未配置时端口默认并不完全一致：后端运行入口常回落到 `8003`，`silk.sh` 的 Web 前端静态服务器默认 `8005`，Web/Android `FRONTEND_PORT` 生成 fallback 仍是 `8004`；详见 `KNOWN_DRIFT.md`
- 端口与公网/内网分离时，需要特别注意 `BACKEND_HTTP_PORT` 与 `BACKEND_INTERNAL_PORT`
- 外部 Agent 新配对会预绑定 `silk-agent connect --account <loginName>` 指定的目标账号；其他登录账号即使拿到短码也只能得到与无效码相同的 404。签名中的 canonical `serverOrigin` 优先取 `BACKEND_BASE_URL`，未配置时取 Host 通过 `--server` 明确提交的连接 origin，不再回退到 Ktor 的内网监听地址；浏览器确认页优先取 `BACKEND_WEB_APP_BASE_URL`，其次取 Host `--web`，最后才与 `--server` 同源。Web/API 分端口时无法安全猜测端口，必须配置 Web origin 或传 `--web`。正式配对必须使用 HTTPS/WSS，直接 IP + HTTP 仅限受控开发环境。
- Backend SQLite 默认使用 `./silk_database.db`；测试或隔离运行可通过 JVM 参数 `-Dsilk.databasePath=...` 覆盖
- Workflow / TrustedDir 默认写到 `~/.silk-data/workflows`；`silk.sh` 会通过 `SILK_WORKFLOW_DIR` / `-Dsilk.workflowDir=...` 注入同一目录
- Workflow Room GitHub 集成使用同目录的 `git_integration_store.json`；开启前必须配置 Base64 32 字节 `SILK_ENCRYPTION_KEY`。`GITHUB_INGESTION_MODE` 默认 `AUTO`：只有显式配置可用的公开 HTTPS `GITHUB_WEBHOOK_BASE_URL` 时才注册 Webhook，否则随 Ktor 启动无需公网入口的 Polling；也可强制设为 `POLLING` 或 `WEBHOOK`。Polling 默认每 120 秒查询一次，可用 `GITHUB_POLL_INTERVAL_SECONDS` 调整（最小 60 秒），并根据 ETag、额度响应头和失败情况自动退避。同一 Workflow 存储目录由 `.git_polling.lock` 保证只有一个后端进程执行轮询。
- GitHub fine-grained PAT 在 Polling 模式需要目标仓库 `Issues: read`、`Pull requests: read` 和 Metadata 读取权限；Webhook 模式另外需要 `Webhooks: write`。Polling 覆盖 Issue/PR 提醒，不通用轮询 Check Runs；完整 CI `check_run` 卡片继续使用 Webhook。普通用户绑定时仍只提交仓库 URL 与 PAT。
