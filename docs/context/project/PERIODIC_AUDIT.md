# Periodic Audit

本文件记录需要周期性清查的全仓项目，以及最近一次可作为增量检查起点的基准时间和 commit。

用途：

- 下一次清查时，从上次基准之后的 GitHub commits 开始看，而不是默认全仓重扫。
- 让周期性清查项目有固定 ID、范围、更新规则和结果记录。
- 记录项目级治理状态；不要把一次性执行日志或临时计划写到这里。

## Current Registry

| Audit ID | 清查项目 | 范围 | 建议频率 | 上次清查基准时间 | 上次基准 commit | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- |
| `docs-code-consistency` | 文档与代码库内容一致性 | `ARCHITECTURE.md`、`README.md`、`docs/context/**`、`silk.sh`、`.env.example`、`.github/workflows/ci-fast-validation.yml`，以及被变更触达的代码入口面 | 重要功能合入后或发布前 | `2026-05-06 10:48:05 +0800` | `33bd5a0` | 首次全量直查已完成；已同步 context 文档，`README.md` / `.env.example` / `silk.sh` / 默认端口改动已按用户要求撤回，相关偏差记录到 `KNOWN_DRIFT.md` |

## Audit Records

| 时间 | Audit ID | 基准 commit | 结果 | 后续动作 |
| --- | --- | --- | --- | --- |
| `2026-05-06 10:12:44 +0800` | `docs-code-consistency` | `33bd5a0` | 建立定期清查记录与路由；未做全仓一致性结论 | 下一次清查从该时间或 commit 之后的变更开始 |
| `2026-05-06 10:48:05 +0800` | `docs-code-consistency` | `33bd5a0` | 首次按当前代码库直接全量对照；已修正 `docs/context/**` 与 `ARCHITECTURE.md` 的事实，`README.md` / `.env.example` / `silk.sh` / 默认端口改动已撤回并以已知偏差保留 | 下一次清查按本基准之后的增量 commit 执行 |

## How To Audit `docs-code-consistency`

1. 先读 `AGENTS.md`、`ARCHITECTURE.md`、`docs/context/TASK_ROUTER.md` 和本文件。
2. 以上次 `上次清查基准时间` 或 `上次基准 commit` 为起点拉取 GitHub 增量变更。将 `<target-branch>` 替换为本次要清查的远端分支：

   ```bash
   git fetch origin
   git log --since='2026-05-06 10:48:05 +0800' --name-status origin/<target-branch>
   git log 33bd5a0..origin/<target-branch> --name-status
   ```

3. 按变更路径打开最小文档集，不默认全仓扫描；忽略 `backend/bin/`、`frontend/desktopApp/bin/`、`build/`、`backend/build/`、`.gradle/`、`.silk-runtime/`、`kotlin-js-store/` 等生成物与运行时噪音。
4. 对照代码、脚本、CI 和运行入口事实，检查下列文档是否需要同步：
   - `ARCHITECTURE.md`
   - `README.md`
   - `docs/context/**`
   - `docs/todo-roadmap.md`，仅在用户明确要求改 roadmap 时修改
5. 若发现文档事实已经过期且本轮修正，直接更新对应最近文档。
6. 若发现偏差但本轮不适合修正，记录到 `docs/context/project/KNOWN_DRIFT.md`。
7. 清查完成后，更新 `Current Registry` 中该项目的时间、commit、状态，并在 `Audit Records` 追加一行结果。

完成标准：

- 增量 commits 中触达的代码、脚本、CI、运行入口和消息合同事实，已经映射到最近的文档或明确判定无需更新。
- 未修正的偏差已经写入 `docs/context/project/KNOWN_DRIFT.md`。
- 本文件已经更新新的清查时间、基准 commit、结果和后续动作。

## Adding New Audit Items

新增周期性清查项目时：

1. 在 `Current Registry` 添加一行，给出稳定的 `Audit ID`。
2. 写清楚范围，范围应是路径或可验证事实，不写笼统目标。
3. 写清楚建议频率和初始基准时间。
4. 若新项目需要特殊读取路径，在 `docs/context/TASK_ROUTER.md` 增加对应路由。
5. 若新项目会改变 context 维护契约，在 `docs/context/INDEX.md` 同步说明。

## Closeout Rules

- 时间统一使用本地带时区格式：`YYYY-MM-DD HH:MM:SS +0800`。
- commit 记录短 hash 即可；跨远端协作时，优先使用 commit range，比单纯按时间过滤更稳定。
- 只把周期性清查状态写入本文件；具体修复计划写入 `docs/context/planning/exec-plans/`。
- 文档清查本身通常只需要 `git diff --check`；如果清查带出代码、脚本、CI 或合同变更，按 `docs/context/quality/TEST_MATRIX.md` 选择最窄验证。
