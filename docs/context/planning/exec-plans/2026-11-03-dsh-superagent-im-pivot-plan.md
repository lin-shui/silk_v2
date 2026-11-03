# DSH-Superagent 驱动多人协作 · Silk 回归 IM（2026-11-03）

## 背景与分工决策

多人协作（谁有权发起、要不要接活、执行、回报）由 `dsh-superagent-prototype`（位于 `/volume1/docker/silk/dsh-superagent-prototype`，executor 仓库指向 `https://github.com/lin-shui/dsh-superagent-prototype`）承担。Silk 回归 IM 本体，仅以"集成机器人"身份接入 dsh，作为其第三个 Source（现有：飞书、GitHub）。

工作分支：`feat/dsh-superagent-integration`（基于 `origin/develop` @ 8ed25a1 创建）。

## 已拍板的决策

| 决策点 | 结论 |
| --- | --- |
| Thread 映射 | v1 一个群 = 一个 Thread（Eval 按群串行） |
| Executor 仓库 | `lin-shui/dsh-superagent-prototype` |
| Silk AI（DirectModelAgent） | 保留，单人群需要 |
| 与飞书的关系 | 集成不经飞书；silk 不与 feishu 扯上关系（`feishu_bot/` 后续去留另议） |
| 旧协作面（`backend/agents/`、`workflow/`、`ccconnect/`） | 冻结不加新能力，链路稳定后再决定删除 |

## 概念映射（silk → dsh）

| dsh | silk |
| --- | --- |
| Trigger | 一条群消息（id = messageId，timestamp 游标） |
| Thread | 一个群 |
| Actor | silk userId；Policy allowed 名单同步群内 HOST/OPERATOR（dsh `policy.sync`） |
| addressed: mention | 消息 @ 机器人账号（userId 以 `_ai_agent` 结尾的普通用户） |
| Channel.send | 以机器人身份发消息回群（幂等） |

dsh 的 Source 走 CLI 子进程模式（飞书 `lark-cli`、GitHub `gh`），需提供薄 `silk-cli` 包装 silk HTTP API。

## 阶段计划

### P0 最小链路（先跑通再说）

silk 侧（改动刻意最小，守住"就做 IM"）：
1. 梳理 `GET /api/messages/poll/{groupId}?since=`（`Routing.kt`）作为 pull 基础；补 bot token 鉴权（token 存储可复用 `ccconnect/CcConnectTokenRepository` 模式）。
2. 新增"以机器人身份幂等发消息"HTTP 端点（幂等 key 语义对齐 dsh `message.id`）。

dsh 侧：
3. 新增 `src/sources/silk.ts` Source kind（照 `docs/008-cookbook.md` + `src/sources/feishu.ts` 模式）+ 薄 `silk-cli`。
4. 验收路径：一个群 + @bot → Eval 开 Ticket → Executor 在 `lin-shui/dsh-superagent-prototype` 干活 → 回复落回群。

### P1 冻结

silk 的 `backend/agents/`、`workflow/`、`ccconnect/` 停止演进，仅修 bug。

### P2 清理（链路稳定后）

再议：旧协作面删除范围、`feishu_bot/` 去留、master/develop 合并节奏。

## 风险与注意

- `/api/messages/poll` 当前未见鉴权（需确认），bot token 方案要在 P0 一并定。（已由 `/api/dsh-bot/*` 独立面解决，不动旧端点。）
- 群 = Thread 意味着单群内评估串行，多活群之间互不影响；若未来需要更细粒度再引入引用链。
- dsh 原型面向"一个 GitHub 仓库"，executor 仓库已定为 dsh-superagent-prototype 自身（自举）。
- 本分支不动 `docs/todo-roadmap.md`（human-maintained）。
- **lint 基线（2026-11-03 确认）**：origin/develop HEAD 上 `./gradlew silkLint` 本身就是红的（backend `ai/ClaudeProcessClient.kt`、`Routing.kt:2616` 与 frontend/shared `PlatformWebSocket.js.kt` 共 45 条存量 detekt 报点，来自最近一次 develop 提交）。本分支改动零新增 detekt 报点；存量修复不在本分支范围，另行处理。
