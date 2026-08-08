# Planning Index

## Canonical Governance Files

- Todo 路线图： [TODO_ROADMAP.md](TODO_ROADMAP.md)

## Active Execution Plans

- [Unified Room Navigation 与显式 RoomKind](exec-plans/active/2026-08-04-unified-room-navigation.md)（代码与自动化验证完成，待发布环境手工验收）
- [Workflow Room 重新设计](exec-plans/active/2026-07-24-workflow-room-redesign.md)（Phase 1、1.5、2 已完成；Phase 2.5 待手工验收）
- [Phase 3：Workflow Room GitHub 集成 MVP](exec-plans/active/2026-08-06-phase3-github-integration-mvp.md)（实施中；Webhook + Polling 核心已实现，真实 GitHub 验收待完成；默认关闭，Owner 主动绑定后启用）
- [KB Memory Layer](exec-plans/active/2026-07-07-kb-memory-layer.md)（Phase 1-4 ✅，Phase 5 ✅ → 已归档 `exec-plans/completed/`）
- [Lint baseline reduction](exec-plans/active/lint-baseline-reduction.md)

## Completed Execution Plans

- [KB AI Operations](exec-plans/completed/2026-07-06-kb-ai-operations.md)
- [KB Context And Sharing](exec-plans/completed/kb-context-sharing-plan.md)
- [KB Copilot: Streaming + Inline Diff Edit + UX Polish](exec-plans/completed/2026-07-10-kb-copilot-streaming-inline-edit.md)
- [KB Copilot: 三状态流程改造](exec-plans/completed/2026-07-13-kb-copilot-three-state-redesign.md)
- [KB Candidate Merge: Diff Preview](exec-plans/completed/2026-07-13-kb-candidate-merge-diff-preview.md)
- [KB Phase 5: Storage Upgrade](exec-plans/completed/2026-07-13-kb-phase5-storage-upgrade.md)（Stage 1 嵌入语义检索 ✅，Stage 2 PostgreSQL ✅，2026-07-14 验证通过）

## Usage

- `docs/todo-roadmap.md` 是 human-maintained canonical roadmap
- agent 默认只读 roadmap，不自动维护它；若要提出实施建议或分解计划，写到 `exec-plans/active/`
- 大任务执行计划：可放入 `exec-plans/active/`，完成后归档到 `exec-plans/completed/`
