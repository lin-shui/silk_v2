# Silk Todo Roadmap

## Purpose

本文件是 Todo 领域的 human-maintained roadmap。

它只保留 team 明确确认的信息，用来给人和 agent 提供稳定上下文。

不用它来做：

- agent 自动生成的实现记录
- commit archaeology
- 每轮开发后的流水账
- 没有明确产品确认的推断性 backlog

## Maintenance Rules

- 由 team 手工维护。
- agent 默认只读；只有用户明确要求时才修改本文件。
- 实施计划、候选方案、验证记录写到 `docs/context/planning/exec-plans/`。
- 保持简洁，优先写结论，不写实现过程。
- 不能确认的内容先留空，不要根据代码猜测补全。

## Scope

这里追踪：

- Todo 领域的产品边界
- 当前 active backlog
- 暂缓但未关闭的事项

这里不追踪：

- 通用聊天能力
- Workflow / Knowledge Base / ASR / Bridge 等其他域
- 详细实现方案、测试过程、发布记录

## Current Baseline

只写 team 已确认、且会影响后续判断的现状。
没有需要强调的结论时，保持为空。

## Active Backlog

只保留当前真的在跟的事项；每条尽量短。

| ID | Priority | Status | Topic | Desired Outcome |
| --- | --- | --- | --- | --- |

## Parking Lot

记录明确讨论过、但当前不做的事项，避免和 active backlog 混在一起。

| Topic | Why Parked | Revisit Trigger |
| --- | --- | --- |

## Notes

- 改 Todo 逻辑前先对齐本文件。
- 若本文件与代码/测试不一致，以代码与测试为准，再由人决定是否更新本文件。
