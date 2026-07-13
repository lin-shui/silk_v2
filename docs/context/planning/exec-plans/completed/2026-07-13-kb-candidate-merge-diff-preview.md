# KB Candidate Merge: Diff Preview + Review

Status: 已完成
Date: 2026-07-13

## Goal

升级候选条目合并体验，让用户在合并前能看到"目标原内容 vs 合并后内容"的差异对比，而不是盲目追加。

## Current State

- `MergeKnowledgeEntryDialog` 仅提供目标文档下拉选择 + 确认按钮，无内容对比
- 合并策略为简单追加 `---\n## 合并自：title\n\ncontent`
- `DiffReviewPane` 和 `DiffChunk` 模型已存在于 Copilot 内联编辑，但未用于合并流程
- Diff 计算（LCS 行级 diff）当前仅后端有，前端合并预览需要前端侧 diff 算法

## Design

### 1. 前端纯客户端 Diff 算法

在 `KnowledgeBaseScene.kt` 中新增 `computeLineDiff(original: String, modified: String): List<DiffChunk>` 函数，实现基于 LCS（Longest Common Subsequence）的行级 diff，复用现有的 `DiffChunk` 数据类。

### 2. MergeKnowledgeEntryDialog 升级

改为两步交互流：

**Step 1 — 选择目标**（当前流程不变）：
- 下拉选择目标文档 + topic

**Step 2 — 预览差异**（新增）：
- 用户选择目标后，自动计算原内容 vs 合并后内容的行级 diff
- 在对话框中展示带着色 diff 的预览区域（新增/删除/未更改/已修改）
- 合并内容计算公式不变（追加 `---\n## 合并自：`）
- 预览区域顶部显示变更摘要（"新增 X 行，删除 Y 行"）
- 底部保留"确认合并"和"返回选择"按钮

### 3. 多候选批量合并预览

- 批量合并时同样计算 diff（targetOriginal vs batchMerged）
- 在待合并列表区域显示变更摘要

## Affected Surfaces

| 文件 | 改动 |
| --- | --- |
| `frontend/webApp/.../KnowledgeBaseScene.kt` | 新增 `computeLineDiff`、`MergeDiffPreview`；升级 `MergeKnowledgeEntryDialog` |
| `frontend/webApp/.../KnowledgeBaseScene.kt` | 批量合并入口增加预览状态管理 |
| `frontend/webApp/.../KnowledgeBaseSceneLogicTest.kt` | 新增 `computeLineDiff` 测试 |

## Risks

- Diff 算法在超大文档上可能较慢（O(n*m)），但合并场景通常为中等规模文档，可接受
- 预览 diff 只在提交前展示，不持久化，不影响现有数据模型

## Verification

- `./gradlew :frontend:webApp:nodeTest`
- `./gradlew :frontend:webApp:compileProductionExecutableKotlinJs`
