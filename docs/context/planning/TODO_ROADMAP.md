# Todo Roadmap

canonical: `../../todo-roadmap.md`

## Why This Wrapper Exists

- `docs/todo-roadmap.md` 已经是仓库内的既有 canonical 文件
- context architecture 需要把它纳入统一索引，但不应复制出第二份正文
- 该文件由人维护；agent 默认只消费，不自动改写

## Read This Before

- 改 Todo 抽取 / 合并 / 生命周期
- 改 Harmony Todo UI
- 改 `/api/user-todos*` 或工作日判定链路

## Agent Policy

- 默认不要直接改 `docs/todo-roadmap.md`
- 若需要沉淀本轮实现计划、候选方案、验证记录，写入 `exec-plans/active/`
- 只有当用户明确要求修改 roadmap 本身时，才编辑 canonical 文件
