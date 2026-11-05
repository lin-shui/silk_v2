# Known Drift

这些点容易误导 agent，遇到冲突时按本文件处理。

## Port Defaults Are Not Uniform

- `README.md` / `.env.example` 仍有 `8006` 后端端口叙述。
- 后端运行入口未配置时回落到 `8003`：
  - `Application.kt`
  - `silk.sh`
  - Web / Android / Desktop Gradle 后端地址 fallback
- Web 前端端口也不完全一致：
  - `silk.sh` 的 Python 静态服务器默认 `8005`
  - `frontend/webApp` / `frontend/androidApp` 生成 `FRONTEND_PORT` 的 fallback 已修复为优先读 `FRONTEND_HTTP_PORT`（见 `build.gradle.kts`），无配置时仍回 `8004`
  - `frontend/webApp` dev server `runTask` 端口是 `8005`
- 实际运行和构建任务中优先读 `.env` / Gradle 属性；不要只抄 README 端口表。

## `silk.sh` HAP Comments Are Ahead Of Behavior

- `silk.sh` 顶部注释和 help 文案仍说 `deploy` / `build-all` 包含 Harmony HAP。
- 当前实际代码中 `build_all()` 只构建 WebApp + Android APK，`deploy()` 的 HAP 构建块被注释。
- Harmony HAP 需要显式运行 `./silk.sh build-hap`。

## README Understates Current Capabilities

- `README.md` 仍以 Web / Android / optional Desktop 为主，未完整描述 Harmony、Audio Duplex、HAP 下载路由等当前能力。
- 若需要 coding context，优先看 `ARCHITECTURE.md` 与 `docs/context/**`。

## `Routing.kt` Still Owns Most HTTP Surface

- 虽然已经拆出 `routes/FileRoutes.kt`、`routes/AsrRoutes.kt`，但多数路由仍在 `Routing.kt`。
- 改 HTTP 行为时先搜 `Routing.kt`，不要假设所有路由都已模块化。

## Desktop Feature Parity Is Lower

- `frontend/desktopApp` 当前主要是登录 / 群组 / 聊天 / 设置。
- Knowledge Base / Audio Duplex 的主壳当前在 Web、Android、Harmony，不是 Desktop 的事实能力面。

## `search/` Is Legacy

- `search/` 目录是 Weaviate 时代的遗留物。Weaviate 已由 Claude 原生 `web_search` + 后端 grep `searchContext()` 替代。
- `search/README.md` 是 Weaviate 上游 README，不是本仓库的项目说明。
- 阅读 `search/` 时注意其内容已不反映当前架构。

## Generated / Runtime Paths May Be Dirty

- 当前工作树里常见未跟踪目录：`.silk-runtime/`、`backend/bin/`、`frontend/desktopApp/bin/`
- 这些通常是运行或构建副产物，不是架构入口。

## Todo Governance Lives Outside `docs/context/`

- Todo 规划的 canonical 文件仍是 `docs/todo-roadmap.md`
- 它是 human-maintained roadmap，不是 agent 自动维护的执行日志
- `docs/context/planning/TODO_ROADMAP.md` 只是 agent-facing wrapper
