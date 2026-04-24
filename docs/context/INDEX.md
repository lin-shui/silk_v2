# Context Index

本目录给 coding agent 使用，不是面向人的教程。

## Read Order

1. `AGENTS.md`
2. `ARCHITECTURE.md`
3. `TASK_ROUTER.md`
4. 任务对应的最小子文档

## Layers

- `generated/`: 仓库地图与检索入口
- `project/`: 构建、运行、存储、已知偏差
- `modules/`: backend / frontend 深挖
- `integrations/`: Claude Code、Weaviate、飞书等辅助子系统
- `quality/`: 测试面、快检、验证映射
- `planning/`: Todo 治理与执行计划入口
- `../skills/`: 仓库内 agent workflow skill；按 `TASK_ROUTER.md` 指向按需加载

## Maintenance Contract

完成代码、脚本、CI 或运行行为变更后，做一次 context closeout：

- 若变更改变入口文件、模块边界、运行/存储事实、跨端 payload、验证命令或 CI 范围，同步更新最近的 `docs/context/**` 文档。
- 若变更改变 agent 的读文档路径，更新 `TASK_ROUTER.md`；若改变顶层架构事实，更新 `ARCHITECTURE.md`。
- 若变更影响面向人的启动、安装或能力说明，检查 `README.md` 是否也需要同步。
- 若发现文档与代码暂时不一致且本轮不适合修正，记录到 `project/KNOWN_DRIFT.md`。
- 不把执行日志或临时计划写进模块文档；这类内容放到 `planning/exec-plans/`。
- `docs/todo-roadmap.md` 仍由人维护，除非用户明确要求，不自动改 roadmap 正文。
