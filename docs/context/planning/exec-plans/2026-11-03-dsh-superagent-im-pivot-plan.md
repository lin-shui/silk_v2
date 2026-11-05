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
| 旧协作面（`backend/agents/`、`workflow/`、`ccconnect/`） | 方案 B：链路稳定后整面删除（已完成，见文末） |

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

### P0 最小链路（先跑通再说）——代码已完成（2026-11-03）

- silk 侧（分支 `feat/dsh-superagent-integration`，commit `568ed80`）：`routes/DshBotRoutes.kt`（token/me/join/pull/幂等 send）、`dsh/` 包（bot 身份 + token 仓库）、`dshbot_tokens` 表、`AgentRuntime.DSH_BOT_USER_ID` 白名单、`DshBotRoutesTest`（`./gradlew :backend:test` 268/0）。
- dsh 侧（`lin-shui/dsh-superagent-prototype` main，commit `b64a500`）：`src/sources/silk.ts` Source kind + `scripts/silk-cli.mjs`（SILK_URL/SILK_BOT_TOKEN 环境变量），`pnpm check` 1203/0；CLI 已对合同 stub 完成对接冒烟（pull/mention/游标/410 gone）。
- 剩余：真实 DSH 运行时部署联调（DeepSeek Harness + 模型密钥 + executor 仓库 checkout），群内 @DSH → Ticket → 回群消息全链路验证。接入步骤：silk `POST /api/dsh-bot/token` → `silk-cli join --code <邀请码>` → dsh 面板启用 Silk Source 并配置群。

### 部署进展（2026-11-03，ubuntu-silk-open 容器）

- 已装：tmux 3.4、gh 2.45（官方源不可达，低于文档建议的 2.48，功能路径未受影响）、`@deepseek-ai/dsh@0.2.1-rc.3`。
- DSH profile `superagent`（`~/.dsh/profiles/superagent`）：webserver 127.0.0.1:3082（DSH 拒绝 0.0.0.0），`dsh-superagent` stateDir `/home/silk/.dsh/state/superagent`，启用 `dsh-superagent-silk`，`llm-deepseek` 用 `DEEPSEEK_API_KEY`（key 复用容器内既有 Claude 配置，DSH 无需另配账号）。
- silk dev 实例：`/tmp/silk-dev`（独立库/历史/端口 36790），未触碰生产实例；bot token 已签发、以 GUEST 入群。**真实后端全链路已验证**：token → join → me → `/api/messages/send` 人类消息 → bot pull（`bot` 标记正确）→ 幂等 reply（sent → duplicate，历史仅一条）。
- DSH 设置已保存：`source:silk`（群 `3f7c2a1e-…`、listen all、poll 10s）、`policy.sources.silk.allowed=[u_alice]`、`executor`（directory `/home/silk/work/dsh`，prepare 用 `git worktree add` 从 `/home/silk/src/dsh-repo` 自举仓库出 worktree）。
- 接线：`/usr/local/bin/silk-cli` → 仓库 `scripts/silk-cli.mjs`；`/home/silk/dsh-superagent.sh` 包装启动脚本（注入 SILK_URL/SILK_BOT_TOKEN/DEEPSEEK_API_KEY）。
- **唯一剩余阻塞**：GitHub agent 账号未登录（`agentCliSignedOut`），需操作者在容器内 `~/.local/bin/gh-agent auth login`；其余均已就绪。

### 端到端验收通过（2026-11-03）

agent 账号为 `lin-shui-bot`（专用账号，对 `lin-shui/dsh-superagent-prototype` 有 push 权限）。真实链路已验证：

1. Alice 在 dev silk 群发 `@DSH 请在 README 的 Documentation 列表里加一行…写完开个 PR`。
2. silk Source 10s 轮询收到 Trigger（`addressed: mention`）→ Eval `accept`（explicit）→ 建工单 → 先回群确认「好的，会…」。
3. Executor 在 `/home/silk/work/dsh/<ticket>` worktree 干活，以 `lin-shui-bot` 身份推送分支 `agent/readme-doc-source-line-<id>` 并开 PR #1（README +1 行）。
4. 终报落回群：「已提交 [PR #1](…)，可以审阅了；合并需要你来点」。

**踩坑记录（重要）**：不要在工单仓库里设 `credential.helper`——dsh 的 `verifyDirectory` 会拒绝 repo-local 的 `credential.*` / `http.*extraheader`（防止 agent 读到仓库自带凭据）。dsh 已在 stateDir 生成 `worker-gitconfig`，把 `credential.https://github.com.helper` 指向部署的 `gh-agent`，并把 `git@github.com:` 重写为 HTTPS；克隆只需保留干净的 remote（HTTPS 即可）。

**v1 已知缺口**：
- silk 工单不跟随 PR 生命周期（GitHub Source 才把 PR 绑定到 Thread），PR 合并后工单不会自动结束；需要在面板手工停止或后续实现 `bindThread`。
- dev 实例的数据在 `/tmp/silk-dev`（重启即失），DSH 服务需用 `/home/silk/dsh-superagent.sh` 启动以注入 silk/模型凭据。
- DSH 的 `github` Source 在跑（60s 轮询），会对自举仓库的 GitHub 通知产生额外工单；如需只留 silk，可在 `source:github` 关闭（但部署仍要求 gh 账号可用）。

### 分支与 PR 状态（2026-11-03，已全部合入）

- silk：`feat/dsh-superagent-integration` → **PR #154 已合并**（squash 到 develop，`37877a4`）；CI 修复 **PR #155 已合并**（`8da8331`）。
- dsh：`feat/silk-source` → **PR #2 已合并**（`37c7167`）。
- 合并后 develop 的 `Backend + Web + Desktop`、`Android Debug Kotlin Compile` 两个检查均通过。
- 环境注意：容器内 gh 2.45 的 `gh pr edit` 会因 Projects classic GraphQL 弃用而失败，改 body 用 REST `PATCH /repos/.../pulls/<n>`；`gh pr create` 正常。
- 已知 flaky（已修复）：`BackendFileContractTest > file upload broadcasts stable file payload to live chat and history replay` 的偶发失败来自 `ChatHistoryManager` 读-改-写未加 per-session 锁（上传预览消息与 FILE 消息并发写历史互相覆盖），PR #157 已用 `withSessionLock` 串行化。
- 当前 develop 汇总：#154（bot HTTP 面）、#155（CI 恢复）、#156（本状态文档）、#157（历史写竞态）均已合并；dsh 侧 #2 已合并。
- 执行环境：`executor.prepare` 已改为先 `git fetch --prune` 再 `worktree add`，新工单从最新 `origin/main` 开分支。

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
- **lint 基线（2026-11-03 已清理）**：origin/develop 上原有的 45 条存量 detekt 报点（backend `ai/ClaudeProcessClient.kt`、`Routing.kt` poll 端点、frontend/shared `PlatformWebSocket.js.kt`）已由 PR #155 清零，`silkLint` 恢复全绿；同时把两处 `android-actions/setup-android` 升到 `v4`（runner 镜像已移除 `tools` 包）。

## 方案 B：删除旧协作面（2026-11，已完成）

### Stage 1a：后端 cc-connect（PR #159 已合并）

- 删除 `backend/.../ccconnect/`（Registry / TokenRepository / Protocol）
- 删除 `/ccconnect-bridge` WebSocket、cc-connect token 管理 API、按角色/前缀转发、按钮答案拦截、cc-connect 图片转发、`ccconnect_tokens` 表与建群发 token 逻辑
- `WebSocketConfig.broadcast` 不再有 cc-connect 分支：消息只可能走 Vision 或 Silk AI
- 破坏性：生产曾生成过 cc-connect token 的群（`silk-spark-main`、`silk-open-cc`、`test_cc_public`、`test_cc`）连接失效；`silk-spark-main` 已改用 dsh，不受影响

### Stage 1b + 2 + 3：ACP agents、bridges、workflow（本 PR）

- 后端：删除 `agents/`（ACP 协议层、`AgentRuntime`、`CommandRouter`、`AgentSession`、描述符）、`workflow/WorkflowManager`、`trust/TrustedDirManager`、`routes/AgentChangesRoutes`、`models/Workflow`；`Routing.kt` 删除 `/agent-bridge`、`/api/agents`、`/api/workflows*`、`cc-settings` / `cc-state` / `cc-fs` / `trusted-dirs`；`WebSocketConfig` 删除 ACP 拦截与 AgentRuntime 取消分支；新增 `AgentIdentity`（仅用于 AI 消息身份判定）；`UserSettings.cc_bridge_token` 列与相关仓库方法删除。KB 的 `KBSourceType.WORKFLOW` 仅保留为历史存量兼容值。
- 外部进程：删除 `cc_bridge/`、`codex_bridge/`、`cursor_bridge/`。
- 前端：Web/Android 删除 Workflow 页面与导航 Tab、Agent 设置、Source Control、cc-connect 状态/Token/`@cc`/operator 管理；Harmony 删除 `WorkflowPage` / `CcWorkflowPanel` 与对应 API；`frontend/shared` 删除 `CcSettingsResponse` / `CcStateResponse` / `DirListingResponse` / `GitChanges*` / `ccMetadataJson`。
- 脚本与配置：`silk.sh` / `silk-chat.sh` / `backend/build.gradle.kts` 删除 `SILK_WORKFLOW_DIR` / `-Dsilk.workflowDir` 注入；`.env.example` 与 CI script smoke 同步。
- 文档：删除 `integrations/CLAUDE_CODE_AND_BRIDGES.md`；同步 `ARCHITECTURE.md`、`TASK_ROUTER.md`、`REPO_MAP.md`、`TEST_MATRIX.md`、后端/前端模块文档、`README.md`、`README_TESTS.md`。
- 破坏性：ACP bridge、workflow、目录信任、代码审查 API 与全部三端 UI 消失；历史 KB 条目中的 `sourceType=WORKFLOW` / `workflowId` 仍可读取和展示。
