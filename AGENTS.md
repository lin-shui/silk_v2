# AGENTS

## Startup Contract

- 先只读本文件；不要默认全仓扫描。
- 第二步读 [ARCHITECTURE.md](ARCHITECTURE.md)。
- 第三步按任务打开 [docs/context/TASK_ROUTER.md](docs/context/TASK_ROUTER.md) 指向的最小文档集。

## Source Of Truth

1. 代码与测试
2. `silk.sh`、`.env.example`、`.github/workflows/ci-fast-validation.yml`
3. `docs/context/**`
4. `README.md`

## Hard Rules

- 忽略生成物与运行时噪音，除非任务明确要求处理它们：`backend/bin/`、`frontend/desktopApp/bin/`、`build/`、`backend/build/`、`.gradle/`、`.silk-runtime/`、`kotlin-js-store/`。
- 后端改动先定位入口面：`backend/src/main/kotlin/com/silk/backend/Application.kt`、`Routing.kt`、`WebSocketConfig.kt`、`routes/*`。
- 跨端消息/文件 payload 改动先定位共享合同面：`frontend/shared/src/commonMain/kotlin/com/silk/shared/models/`、`frontend/shared/src/commonMain/kotlin/com/silk/shared/ChatClient.kt`，再看三端 `FileContractsTest`。
- `docs/todo-roadmap.md` 是 human-maintained canonical planning。Todo 相关改动先读它；除非用户明确要求改 roadmap，否则不要自动维护它。agent 的实施计划与临时建议写到 `docs/context/planning/exec-plans/`。
- 代码、脚本、CI、运行入口、消息合同、存储路径、验证命令等事实发生变化时，同轮检查是否会让 `ARCHITECTURE.md`、`docs/context/**` 或 `README.md` 过期；会过期就同步更新最近的文档，不需要更新则在最终回复说明已检查。
- 先跑最窄验证，再跑更重验证；验证映射见 [docs/context/quality/TEST_MATRIX.md](docs/context/quality/TEST_MATRIX.md)。

## Fast Routing

- 仓库地图： [docs/context/generated/REPO_MAP.md](docs/context/generated/REPO_MAP.md)
- 任务路由： [docs/context/TASK_ROUTER.md](docs/context/TASK_ROUTER.md)
- 项目启动/运行面： [docs/context/project/BOOTSTRAP.md](docs/context/project/BOOTSTRAP.md)
- 已知文档偏差： [docs/context/project/KNOWN_DRIFT.md](docs/context/project/KNOWN_DRIFT.md)
