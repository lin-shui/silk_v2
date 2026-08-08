# Phase 3：Workflow Room GitHub 集成 MVP

**文档类型**：执行计划  
**创建日期**：2026-08-06  
**最后更新**：2026-08-08
**状态**：实施中（Webhook + Polling 核心已实现；真实 GitHub 验收待完成）
**上位设计**：[Workflow Room 重新设计](2026-07-24-workflow-room-redesign.md)

## 0. 入场条件

- Phase 2.5 的代码、跨端合同和自动化验证已经完成；发布前仍应完成 Unified Room Navigation 的双账号、窄屏和 cc-connect 手工验收。
- Phase 2.5 手工验收未通过时，不得把 GitHub 事件作为正式发布能力打开；可以先并行开发 3A/3B 的纯后端合同和 Mock 测试。
- 开始真实绑定验收前，必须准备专用 GitHub 测试仓库、最小权限 fine-grained PAT、独立 `SILK_ENCRYPTION_KEY` 和可连接的 ACP Bridge。
- Polling 模式不要求公网 IP、域名、HTTPS 证书或入站连接，只要求 Silk 后端可出站访问 `https://api.github.com`。只有验收可选的 Webhook 模式时才需要公开、稳定的 HTTPS 后端地址。

## 1. 阶段目标

Phase 3 为 Workflow Room 的 Team Channel 增加一个可选开启的 GitHub 集成功能：

1. Workflow Room Owner 主动绑定一个 GitHub 仓库和 PAT；用户不需要选择接收模式。后端默认按 `AUTO` 解析：存在可用的公开 HTTPS Webhook URL 时沿用 Repository Webhook，否则自动使用简单轮询。
2. GitHub Issue、Pull Request 事件无论由 Webhook 还是 Polling 获取，均归一化为同一种 Team Channel 卡片和事件存储；Webhook 模式继续支持通用 `check_run`，Polling 模式不承诺通用 Check Runs 的等价覆盖。
3. 成员可从 Issue 卡片进入预填的工作区创建流程，确认本地目录后创建个人 Workspace，并在新工作区顶部看到仓库、Issue 标题和链接摘要；该摘要不触发 Agent。
4. Team Channel 的 `@Silk` 仍是唯一的平台 AI 触发入口；绑定开启时才向该 AI 注入近期 GitHub 事件上下文。

**默认行为必须保持不变**：没有成功绑定记录时，GitHub 集成视为关闭。关闭状态下不注册 Webhook、不启动仓库轮询、不调用 GitHub API、不显示 GitHub 事件卡片、不向 AI 注入 GitHub 上下文，现有 Team Channel 的聊天、成员、文件、工作区和 `@Silk` 行为不改变。

## 2. 当前基线与约束

- Room 复用现有 `Group`；只有 `RoomKind.WORKFLOW` 才能启用 GitHub，`CHAT` / `SILK_PRIVATE` 请求返回 `404` 或 `409`。
- Workflow Team Channel 已使用 `scope=TEAM`，可复用现有 `ChatServer.broadcast()`、历史回放和 `MessageType.CARD`。
- Workspace 创建当前要求 Room 成员身份、在线 ACP Bridge、受信任的本地目录和有效 Agent 类型。Issue 入口不能绕过这些执行权限门槛。
- Webhook 主链已经实现：绑定时 reconcile Hook，`issues/pull_request/check_run` 经 HMAC、delivery 去重后写入 Team `CARD`；Polling 主链也已实现，Issue/PR 通过仓库级 Issues API、游标、ETag 和快照 diff 归一化到同一 `CARD` 管道。
- `linkedGithubRef`、Issue-to-Workspace、GitHub 卡片、PR/失败 CI 摘要和 Team `@Silk` GitHub 上下文已经实现，Polling 必须复用这些能力，不能复制第二套产品链路。
- 当前 `RoomGitBinding` 和 `GitEventStore` 仍以 Webhook 为中心：Webhook URL/secret 是必填字符串，幂等键只有 delivery ID，没有 cursor、ETag、resource snapshot 或 polling 状态。
- 当前路由和 ChatServer 可能分别实例化 `GitEventStore`；实例内 `@Synchronized` 不能保护多个实例的同一个 JSON 文件。引入后台 Poller 前必须收敛为进程级共享 store/共享锁，否则轮询与 Webhook/上下文并发写可能覆盖数据。
- 当前卡片回复注册表是进程内状态，重启后会失效。因此 GitHub Issue 卡片按钮继续走可重放的 HTTP 流程，服务端再次校验 Room、绑定和 Issue 编号。
- 后端已有 Ktor CIO Client、JSON 文件存储、JWT 身份解析和 Workspace/Room 路由分层，优先沿用这些模式。

## 3. 范围

### 3.1 MVP 包含

- GitHub（仅 github.com）PAT 绑定，一个 Workflow Room 最多一个仓库。
- Webhook 与 Polling 双接收模式。默认 `AUTO`：配置了可用的公开 HTTPS Webhook URL 时使用现有 Webhook；没有配置时使用 Polling，不把公网入口作为绑定前提。
- Fine-grained PAT 优先。完整 Polling MVP 要求目标仓库 `Issues: read`、`Pull requests: read` 和 GitHub 强制的 Metadata 读取权限：仓库级 `/issues` 负责一次发现两类资源，`Pull requests: read` 只用于变化 PR 的详情补查。只有 Webhook 模式才要求 `Webhooks: write`。不得要求 Contents write、Administration 或全局账号权限。
- Webhook 事件：`issues`、`pull_request`、`check_run`。只处理产品需要的 action；忽略无关 action 并返回成功。
- Polling 事件：通过仓库级 `/repos/{owner}/{repo}/issues` 端点一次发现 Issue 与 PR 更新，使用 `since`、稳定查询参数、分页、认证条件请求、持久化游标和资源快照；首次绑定只建立 baseline，不回放历史卡片。
- Repository Webhook 的创建、幂等更新、解绑和失败补偿。
- `X-Hub-Signature-256` 原始字节 HMAC-SHA256 验签、`X-GitHub-Delivery` 去重、事件大小限制和异步处理。
- Issue、PR 事件的 Team Channel 卡片；PR 生成 AI 摘要。Webhook 模式继续提供 CI 卡片和失败 CI 摘要；AI 不可用时仍保留原始事件卡片。
- Issue → Workspace 预填创建流程：Issue 标题、编号、URL、正文、标签、有限评论上下文和关联引用进入首条 Workspace prompt；创建后写入 `linkedGithubRef`。
- Team Channel `@Silk` GitHub 上下文：仅当前 Room 的 active binding 生效，只注入最近且截断后的结构化事件摘要。
- Web、后端自动化测试、配置/运维文档，以及真实 GitHub Polling 手工验收；Webhook 模式保留已有自动化覆盖，并在具备公开 HTTPS 环境时做模式专项验收。

### 3.2 明确不包含

- GitLab、GitHub OAuth / GitHub App、多仓库绑定。
- `/pr-review`、自动创建/评论/合并 PR、Issue 状态回写、commit/push 里程碑自动推送。
- 全量 GitHub 历史回补、Webhook 投递管理后台、复杂事件规则编辑器。
- 在 Polling 模式逐 commit/ref 查询通用 Check Runs。GitHub 没有仓库级、带 `since` 的通用 Check Runs 列表端点，该能力继续由 Webhook 覆盖。
- GitHub Actions workflow runs 的低频仓库级轮询不作为 Polling 核心完成条件；可在 Issue/PR 轮询稳定后按 5 分钟周期和 `Actions: read` 权限单独评估，不能退化为逐 commit 查询。
- 没有 Bridge 或未授权目录时自动在服务器执行代码。
- 将 PAT 或 Webhook secret 下发到任何前端、消息内容、日志或 URL。

## 4. 产品行为与可选开关

### 4.1 开启与关闭

- Team Channel 头部或 Room 菜单增加 `GitHub 集成`入口。所有成员可看到当前状态；只有 Owner 可操作绑定。
- 初始状态为 `未开启`，只显示开启入口和能力说明，不创建空绑定记录。
- Owner 只提交仓库 URL 和 PAT，不提交公网地址或接收模式。后端验证仓库可访问后按服务端配置解析模式，并在脱敏绑定摘要中返回 `WEBHOOK` 或 `POLLING`。
- `WEBHOOK`：先验证 Hook 权限并创建/复用 Webhook；`POLLING`：先读取 Issue/PR 列表建立不发消息的 baseline。对应模式初始化成功后才写入 active binding，前端切换为 `已开启`。
- 任何验证、Hook 注册或 baseline 初始化失败都保持关闭，不保存明文或不可用的半成品 PAT；远端已创建的 Hook 必须尽力回滚。
- Owner 点击关闭/解绑时，后端先停止该 Room 的轮询任务；存在 Silk Hook 时尝试删除（删除失败需记录告警），随后清除 token/secret 加密数据、polling state、资源快照和事件上下文。既有 Team Channel 历史卡片不删除，但卡片按钮在绑定关闭后返回明确错误。
- MVP 不保留“暂停但保留 PAT”状态。再次开启需重新输入 PAT，避免关闭后继续持有仓库高权限凭据。

### 4.2 用户可见状态

- 普通使用者只需要仓库 URL 与 PAT；Polling 模式不需要额外部署服务、公网 IP、域名或 TLS 证书。
- 绑定摘要展示当前模式、仓库、状态、最后成功同步时间和最近事件时间；不得显示 PAT、Webhook secret、ETag 或原始限流 header。
- `POLLING` 正常时显示“定时同步”；PAT 失效、权限不足或连续失败时显示 `ERROR` 和可操作提示，不在 Team Channel 反复发送错误卡片。
- Polling 提醒延迟由轮询周期决定，默认目标为 0～2 分钟；服务停止期间不接收提醒，重启后从持久化游标补查。

### 4.3 权限

| 操作 | Owner/HOST | 其他 Room 成员 | 非成员 / 普通 Room |
| --- | --- | --- | --- |
| 查看绑定摘要（不含 secret/token） | ✅ | ✅ | 404 |
| 开启、换仓库、轮换 PAT | ✅ | 403 | 404 |
| 解绑 | ✅ | 403 | 404 |
| 接收 Webhook | 服务端 HMAC，不依赖 JWT；仅 Webhook 模式 | 服务端 HMAC，不依赖 JWT；仅 Webhook 模式 | 服务端 HMAC，不依赖 JWT |
| 执行 Polling | 后端调度器使用绑定 PAT | 无额外操作 | 不适用 |
| 从 Issue 创建自己的 Workspace | ✅ | ✅ | 404 |

Issue-to-Workspace 使用 Room Owner 保存的 PAT 拉取 Issue，但永远只把经过长度限制的 Issue 内容发送给当前成员自己的 Workspace；PAT 不暴露给成员。

## 5. 后端设计

### 5.1 模块边界

现有 `backend/.../git/` 已包含 `GitHubClient`、`GitHubModels`、`GitHubRepositoryRef`、`GitHubWebhookVerifier`、`GitEventParser`、`GitEventStore`、`GitEventBroadcaster`、`GitContextBuilder`、`GitEncryption` 和 `GitRoutes`。Polling 在同一模块内新增 `GitIngestionModeResolver`、`GitPollingScheduler`、`GitPollingService` 和 polling response/snapshot models，不建立外部 worker 或第二个服务。调度器随 Ktor Application 生命周期启动和取消；Webhook 不依赖 JWT，其他 Git 路由继续统一解析 JWT 后检查 Room 成员和角色。

### 5.2 本地持久化

新增 `git_integration_store.json`，位于 `SILK_WORKFLOW_DIR`（默认 `~/.silk-data/workflows`），使用临时文件 + 原子 rename、同步读改写，保持与 `WorkspaceManager` 一致。

存储需要包含以下数据：

- `RoomGitBinding`：增加 `ingestionMode`；`hookId/webhookUrl/webhookSecretEncrypted` 改为 Webhook 模式才有值，并增加脱敏同步状态引用。已有非空 Hook 字段必须兼容读取。
- `GitPollingState`：`roomId/cursor/etagByRequest/lastPollAt/lastSuccessfulPollAt/consecutiveFailures/nextPollAt/rateLimitRemaining/rateLimitResetAt/baselineCompleted`。
- `GitResourceSnapshot`：保存 Issue/PR 的稳定标识和事件判断所需的最小字段，例如 `resourceId/number/kind/state/draft/merged/title/labels/commentCount/headSha/updatedAt`；不得保存完整正文或评论。
- `GitEventRecord`：在现有字段基础上增加 `source`（`WEBHOOK`/`POLLING`）、跨来源 `dedupeKey` 和可恢复的投递状态；Polling 使用 `poll_<hash(dedupeKey)>` 作为稳定 delivery/message ID。已有记录缺字段时按 `WEBHOOK + 已投递` 兼容读取，避免升级后重放历史。
- `processedDeliveryIds`：有 TTL（至少 30 天）和数量上限的 delivery 去重集合。

实现要求：binding 只存密文；API DTO 只返回 `enabled/provider/owner/repo/events/ingestionMode/lastDeliveryAt/lastSuccessfulPollAt/status`；事件只保存展示和 AI 所需的截断字段，不保存完整 Webhook body 或 GitHub 响应；旧文件不存在等价于关闭；游标、资源快照、事件去重和待投递事件必须在同一临界区提交。后台发送成功后标记已投递，进程重启先排空 pending，避免“游标已推进但卡片未落地”的丢提醒窗口。

### 5.3 加密与配置

- 新增必需环境变量 `SILK_ENCRYPTION_KEY`：Base64 编码的 32 字节密钥，示例生成命令为 `openssl rand -base64 32`。
- 使用 AES-GCM、随机 12 字节 nonce，密文带版本号（如 `v1:<base64(nonce+ciphertext+tag)>`），不得复用 nonce。
- 未配置或长度不正确时，开启绑定直接返回配置错误，绝不明文落盘；已有 binding 无法解密时状态为 `ERROR`，Webhook 和 Polling 均不处理并记录告警。
- 新增可选 `GITHUB_INGESTION_MODE=AUTO|WEBHOOK|POLLING`，默认 `AUTO`。`AUTO` 只有在显式配置的 `GITHUB_WEBHOOK_BASE_URL` 可解析为公开 HTTPS 地址时选择 `WEBHOOK`，未配置时必须选择 `POLLING`；`WEBHOOK` 强制要求公开 HTTPS；`POLLING` 即使存在 URL 也不注册 Hook。
- `GITHUB_WEBHOOK_BASE_URL` 继续用于拼接 `/api/git/webhook/{roomId}`。现有 `BACKEND_BASE_URL` 回退只在显式强制 `WEBHOOK` 时兼容，不能让通用后端地址在 `AUTO` 下静默切换模式。在 `AUTO` 下若显式 URL 非 HTTPS、指向本机/LAN 或格式非法，应记录明确配置告警并回退 Polling；强制 `WEBHOOK` 时仍返回配置错误。
- 新增可选 `GITHUB_POLL_INTERVAL_SECONDS`，默认 `120`、最小 `60`。该值是目标周期而非固定突发时间，实际调度应加入 10%～20% jitter，并受限流退避覆盖。
- GitHub Client 集中设置 `Accept: application/vnd.github+json`、版本 header、Bearer PAT、连接/请求超时和有限重试；token、secret、Authorization header 永不写日志。

### 5.4 GitHub Client 与绑定事务

1. 规范化 `https://github.com/{owner}/{repo}`，拒绝非 GitHub host、额外 path、query、fragment、空段和错误后缀，避免 SSRF。
2. 使用 PAT `GET /repos/{owner}/{repo}` 验证仓库存在和 token 可读。
3. 通过 `GitIngestionModeResolver` 解析本次 effective mode；客户端请求体不增加 mode、公网 URL 或 interval 字段。
4. `WEBHOOK`：列出现有 hooks，按精确 callback URL 查找 Silk Hook，避免重复创建；找到后更新事件、secret、active 状态，否则创建一个 Hook。config 固定 `content_type=json`、SSL 校验开启、secret 为高熵随机值；订阅 `issues`、`pull_request`、`check_run`。
5. `POLLING`：不调用 hooks API，不生成 Webhook secret；读取仓库 Issue/PR 分页建立资源 baseline 并保存初始 cursor，不把已有资源广播为新事件。
6. 远端验证和模式初始化成功后原子写本地 binding；本地写失败时尽力删除本次新建 Hook。换仓库时先完成新 binding 初始化与本地切换，再停止旧 poll 或删除旧 Hook；旧资源清理失败需有可观测告警。
7. 解绑时先从调度器移除 binding，再删除记录对应 Hook（若有，404 视为已删除）；无论远端结果如何都清除本地密文、polling state、资源快照和事件上下文。
8. `AUTO` 的环境配置在重启后发生变化时允许模式迁移：切到 Webhook 必须先完成 Hook reconcile、再停止 Polling；切到 Polling 必须先完成 baseline/catch-up、再尽力删除旧 Hook。迁移窗口由跨来源 `dedupeKey` 防止重复卡片；任一步失败时继续使用原有效模式并暴露 `ERROR`/告警，不能形成无接收窗口。

### 5.5 Webhook 接收合同

`POST /api/git/webhook/{roomId}` 的要求：

- 使用 `call.receive<ByteArray>()` 读取原始 body，先验签后 JSON decode；不接受 query/body 中的 secret，也不依赖 JWT。
- 必须检查 `X-Hub-Signature-256: sha256=<hex>`，使用 HMAC-SHA256 + constant-time compare；缺失/不匹配返回 `401` 或 `403`，不暴露 Room 是否存在。
- 读取 `X-GitHub-Event`、`X-GitHub-Delivery`、可选 `X-GitHub-Hook-ID`；hook ID 存在时必须匹配 active binding。
- 限制 body 大小，检查 event/action；解析失败或不支持 action 不进入广播；重复 delivery 返回 `202`/`204`。
- 验签、绑定查找、delivery 去重和事件入队在快速路径完成，返回 `202 Accepted`；卡片写入、AI 摘要和外部 Issue 拉取在后台执行，确保 GitHub 10 秒响应窗口内完成。
- 事件只广播到绑定 Room 的 `TEAM` scope，不能创建 `WORKSPACE` 消息或触发编码 Agent。Room 被删除/解绑后旧 delivery 不得恢复或创建 Room。

### 5.6 事件映射与 AI 摘要

| GitHub 事件 | 来源 | MVP action | Team Channel | AI 摘要 |
| --- | --- | --- | --- | --- |
| `issues` | Webhook / Polling | `opened`、`closed`、`reopened`；Polling 另有 `updated` | Issue 卡片，标题/编号/作者/标签/状态/链接，提供“开始开发” | 不强制，原始卡片即可 |
| `pull_request` | Webhook / Polling | `opened`、`closed`、`reopened`、`ready_for_review`；Polling 另有 `updated` | PR 卡片，标题/编号/作者/分支/状态/链接 | 异步生成简短变更摘要，失败保留原始卡片 |
| `check_run` | Webhook | `completed` | CI 卡片，名称/commit/结论/链接 | 仅 failure/timed_out/cancelled 尝试分析，成功只发状态 |

Polling 不直接拥有 GitHub Webhook 的 `action`，必须通过本次资源与持久化 snapshot 比较生成归一化 action：新资源为 `opened`，`open → closed` 为 `closed`，`closed → open` 为 `reopened`，PR 的 draft 变化可生成 `ready_for_review`，标题/标签/评论数/head SHA 等有意义字段变化生成 `updated`。只变化 `updated_at` 而稳定字段相同不得重复发卡；需要完整 PR 状态或分支信息时，仅对本轮发生变化的 PR 补调详情端点。

事件卡片使用已有 `MessageType.CARD` 和 `TEAM` scope，发送者固定为系统身份（如 `github_bot`），持久化到 Room 历史。Issue 按钮动作携带不含 secret 的 `roomId + issueNumber`，前端点击时调用受保护 API；服务端必须再次从 active binding 拉取 Issue，不能信任卡片里的正文或 URL。

AI 摘要复用现有 `DirectModelAgent` 能力，但通过独立 Git 摘要服务和专用 session/context 调用，不伪造用户 `@Silk` 消息，不污染 Team Channel 用户对话历史。摘要 prompt 只包含允许字段和长度限制后的文本；超时、未配置、限流或异常不阻塞原始事件落地。

### 5.7 Team Channel GitHub 上下文

`GitContextBuilder` 为当前 Workflow Room 生成最近 N 条事件的短文本（建议最多 20 条、总字符预算固定），注入现有 `DirectModelAgent.processInput(..., additionalContext=...)` 链路：

- 仅 active binding 存在时启用；无 binding 时完全不读 GitEventStore。
- 只向当前 Room 的 `@Silk` 请求注入；普通 Team 消息仍不触发 AI。
- 只包含 event/action/title/status/link/summary，不包含 PAT、secret、完整 webhook body 或未授权 Workspace 内容。
- 事件读取失败时降级为无 GitHub 上下文，不阻塞普通 AI 对话。

### 5.8 Polling 查询、游标与幂等

- `GitPollingScheduler` 是后端进程内单例调度器，随 Ktor 生命周期启动/关闭，按 binding 而不是按请求创建 timer。相同 PAT 的请求进入同一串行队列，仓库之间加入 jitter，避免重启时集中突发。
- Issue/PR 使用 `GET /repos/{owner}/{repo}/issues?state=all&sort=updated&direction=asc&since={cursor-with-overlap}&per_page=100`；响应带 `pull_request` 键的项目按 PR 处理。查询参数在 cursor 未变化时保持完全一致。
- 首次绑定记录 baseline start time，读取完整分页的资源快照但不发历史卡片，再用 overlap 做一次 catch-up；baseline 不得简单把“轮询完成时间”当作游标，否则会漏掉请求期间发生的更新。超过分页预算的旧资源将来首次变化时归一化为 `updated`，不能误报为刚刚 `opened`。
- 每轮从持久化 cursor 向前重叠至少 60 秒，按 repository + resource kind + id/number + action + updatedAt 生成确定性 `dedupeKey`。同秒事件使用资源 ID/number 作为稳定次序；重复窗口、重启补查以及 Webhook/Polling 模式迁移均不得重复广播。
- 使用 `per_page=100` 并遵循 GitHub `Link` header。单轮设置合理页数预算；超过预算时从最后已完整处理的位置继续，不得直接把 cursor 推到当前时间。只有事件记录、snapshot 和 cursor 原子提交成功后才能确认该批次。
- 对每个完整 request URL 保存 `ETag`。下一次同 URL 发送带 PAT 的 `If-None-Match`：`304 Not Modified` 时不修改 cursor/snapshot；`200` 时处理响应并保存新 ETag。不能每轮无条件把 `since` 改成当前时间，否则会破坏条件请求命中并产生漏事件风险。
- Polling 生成的 `GitEventRecord` 先以 pending 状态进入现有 store，再由 `GitEventBroadcaster → MessageType.CARD/TEAM → GitEventSummaryService/GitContextBuilder` 链路发送并确认；不另建消息类型，也不直接触发 Workspace Agent。

### 5.9 额度、退避与故障恢复

- 认证条件请求只有在 GitHub 返回 `304` 时才不计入 primary rate limit；普通空数组 `200` 仍计一次。因此必须实际保存/发送 ETag，不能把“没有业务事件”等同于“没有额度消耗”。
- `304` 仍然是一次 HTTP 请求，仍需遵守 secondary rate limit 和 GitHub 的轮询最佳实践；它降低的是 primary 配额消耗，不代表可以无限提高频率。
- 每次响应读取 `X-RateLimit-Limit/Remaining/Reset`，优先使用响应 header 而不是额外请求 `/rate_limit`。默认两分钟周期下，未命中 `304` 的理论上限为每仓库每小时 30 次；实际安静仓库应主要返回 `304`。
- `remaining` 低于 20% 时自动把该 token 的周期延长到 5～10 分钟；为 `0` 时暂停到 `reset`。遇到 `Retry-After`、`403` 或 `429` 按 header 与指数退避处理，禁止紧密重试。
- 网络错误、GitHub `5xx` 和超时不推进 cursor；短暂失败保留 `ACTIVE` 并退避，连续失败达到阈值后标记 `ERROR`、暂停常规频率并周期性探测恢复。PAT 被撤销、过期或权限不足时停止事件查询并在绑定状态中提示 Owner，不能向 Team Channel 刷错误消息。
- 服务重启从持久化 `nextPollAt/cursor/snapshot` 恢复；停机期间的更新由 `since + overlap` 补查。多实例部署在引入共享租约前不允许每个实例都启动 Poller；MVP 需通过单实例约束或文件锁保证同一 store 只有一个 active scheduler。

## 6. API 合同

### 6.1 Binding

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/rooms/{roomId}/git/binding` | 成员读取脱敏摘要；未开启返回 `enabled=false` |
| `POST` | `/api/rooms/{roomId}/git/binding` | Owner 开启或换绑；body 为 provider、repositoryUrl、token |
| `DELETE` | `/api/rooms/{roomId}/git/binding` | Owner 解绑并关闭集成 |

`POST` 请求合同保持不变，客户端不需要传 `ingestionMode`。`GET/POST` 响应增加 `ingestionMode`、`lastSuccessfulPollAt` 和不含敏感 header 的同步状态；旧客户端忽略新增字段仍可工作。

错误码至少区分：`WORKFLOW_ROOM_REQUIRED`、`NOT_ROOM_OWNER`、`INVALID_REPOSITORY_URL`、`GITHUB_UNAUTHORIZED`、`GITHUB_FORBIDDEN`、`WEBHOOK_URL_REQUIRED`（仅强制 Webhook）、`ENCRYPTION_NOT_CONFIGURED`、`WEBHOOK_REGISTRATION_FAILED`、`POLLING_INITIALIZATION_FAILED`、`GITHUB_RATE_LIMITED`、`BINDING_NOT_FOUND`。

### 6.2 Issue → Workspace

建议使用 Room 嵌套路由：`POST /api/rooms/{roomId}/git/issue-to-workspace`。请求字段为 `issueNumber/name?/workingDir/agentType/visibility`。

服务端必须检查成员和 active binding，用绑定 PAT 拉取 Issue 基本信息；Issue 编号、仓库和 URL 全部由服务端确定；复用目录信任、Bridge 在线和 Agent 类型检查；创建 Workspace，名称默认 `#<number>: <title>`，写入 `linkedGithubRef`；返回 Workspace DTO 和仓库、Issue 标题、链接组成的 `issueSummary`。 

为保持“一键进入”的体验，Web 点击按钮后打开现有 Workspace 创建对话框，预填名称；用户选择/确认本地目录后提交 API，创建成功后切换到新 Workspace 并发送一条 `SYSTEM` 摘要消息，不启动 Workspace Agent。若创建成功但摘要消息失败，保留 Workspace 并允许重试。

## 7. Web UI 计划

- 在现有 `ConversationRoomHeaderActions` 中增加 GitHub 状态/设置入口，不改变已有文件、导出、邀请、成员按钮顺序和图标。
- 非 Owner 显示 `未开启` 或仓库摘要，不显示 PAT 输入和管理操作；Owner 可开启、换绑、解绑。
- 绑定表单继续只要求仓库 URL 与 PAT，不增加 Webhook URL、轮询周期或模式选择。未开启时不渲染 GitHub 空面板、专属发送工具或占位消息；只有绑定成功后显示仓库、当前模式、最近 delivery/同步时间和事件卡片。
- Polling 状态使用安静的状态文本或 tooltip 展示“定时同步”“上次同步”“同步异常”；不为每次无更新轮询生成消息、toast 或 loading 闪动。
- 复用 `CardMessageRenderer` 的视觉几何，但增加 provider/event badge、状态色、标题、链接和 action 文案。
- Issue “开始开发”只负责打开预填 Workspace 创建流程，不直接绕过本地目录确认。
- 覆盖历史卡片、重复点击、绑定关闭、Issue 无权限/已删除、Bridge 离线、目录未信任和 API 失败；前端按钮隐藏只是体验优化，后端必须最终裁决。
- `WorkspaceDto` 增加可选 `linkedGithubRef`；工作区沿用现有 Team/Workspace Tab、目录信任和 Agent 控件。归档/删除 Workspace 不回写 GitHub；解绑不清除已有关联字段。

## 8. 分批实施顺序

### Stage 3A：合同、存储与安全基座

- [x] 定义 Git DTO、JSON store、旧文件兼容和原子写策略。
- [x] 实现 AES-GCM、`SILK_ENCRYPTION_KEY`、密钥缺失 fail-closed 和日志脱敏。
- [x] 实现 GitHub URL 规范化、HMAC 验签、delivery 去重、body/action 限制。
- [x] 为纯逻辑补单元测试，不接真实 GitHub。

### Stage 3B：GitHub Client 与可选绑定

- [x] 实现 Ktor CIO GitHub Client、版本/Accept/Auth headers、超时和有限重试。
- [x] 实现 repository 验证、Hook reconcile、换绑补偿和解绑清理。
- [x] 增加 binding GET/POST/DELETE 路由，完成 Workflow/Owner/成员鉴权。
- [x] Web 增加默认关闭、开启、成功、失败、解绑和绑定状态读取。
- [x] 更新 `.env.example`、`BOOTSTRAP.md` 和相关 backend/frontend context，补充密钥、公网 HTTPS、PAT 最小权限和 Webhook 入口。

### Stage 3C：Webhook、事件卡片与摘要

- [x] 增加公开 Webhook 路由，完成原始 body 验签、hook ID 校验、快速 2xx、异步处理和 delivery 幂等。
- [x] 实现三类事件 parser、动作过滤、事件记录和 Team Channel `CARD` 持久化/广播。
- [x] 实现 PR/失败 CI 异步 AI 摘要；无 AI 配置或调用失败时保留原始卡片。
- [x] Web 复用事件卡片渲染，接入 GitHub 链接跳转和 Issue action 分流；历史回放沿用既有 CARD 消息链路。

### Stage 3D：GitHub 上下文与 Issue 开发入口

- [x] 实现 `GitContextBuilder`，接入 Team Channel `@Silk` prompt，确保关闭/解绑无上下文。
- [x] 扩展 Workspace `linkedGithubRef`，实现 Issue 基本信息拉取。
- [x] 增加 Issue-to-Workspace API，复用目录信任、Bridge 和 Workspace CRUD 门禁。
- [x] Web Issue 卡片接入 Workspace 创建流程，支持目录信任确认并在创建后发送不触发 Agent 的 `SYSTEM` 摘要消息。
- [x] 覆盖重启后历史卡片、绑定关闭后按钮、成员权限和重复点击。
  - `GitWebhookRouteContractTest`：新增 inactive binding 拒绝、hook ID 不匹配拒绝、onEvent TEAM scope 验证。
  - `GitBindingRouteContractTest`：Workflow/CHAT/Owner/member/outsider 全权限矩阵、PAT 不出响应、DELETE 清密文（新文件，`package com.silk.backend`）。
  - `IssueToWorkspaceRouteContractTest`：member gate、binding gate、agent/bridge gate、非法 issueNumber/workingDir 校验（新文件，`package com.silk.backend`）。

### Stage 3E：现有 Webhook 路径收尾

- [x] 按测试矩阵运行后端、Web、共享合同和 lint/compile 最小集。
- [ ] 在具备公开 HTTPS 环境时，使用 GitHub 测试仓库做 Issue/PR/check_run、redelivery、invalid signature、解绑回归；这是 Webhook 模式专项验收，不再阻塞 Polling 模式作为默认无公网方案发布。
- [x] 清理调试日志、临时 fixture、明文 token、冗余 JSON 解析和未使用 UI 状态（代码扫描无明文凭据或调试 println，视为完成）。

### Stage 3F：Polling fallback 与双模式

- [x] 扩展可向后兼容的 binding/store DTO：`ingestionMode`、nullable Webhook 字段、polling state、resource snapshot、跨来源 dedupe key 和同步状态。
- [x] 将路由、ChatServer 与 Poller 收敛到同一个进程级 `GitEventStore`/共享锁，并加入 pending event 恢复，避免多实例文件写覆盖或推进游标后丢卡片。
- [x] 实现 `AUTO|WEBHOOK|POLLING` 模式解析；默认 `AUTO` 在没有可用 Webhook URL 时选择 Polling，绑定请求仍只有仓库 URL 与 PAT。
- [x] 扩展 `GitHubClient`：Issue/PR repository polling、分页、ETag/`If-None-Match`、rate-limit headers，以及仅对变化 PR 的详情查询。
- [x] 实现首次 baseline、`since + overlap` 游标、snapshot diff、归一化 action、原子提交和重启补查；确认首次绑定不发送历史卡片。
- [x] 实现随 Ktor 生命周期运行的串行调度器、jitter、per-token 预算、限流/错误退避、单实例保护和解绑取消。
- [x] 复用现有事件卡片、PR 摘要、Issue-to-Workspace 与 `@Silk` GitHub 上下文链路；Polling 不新增第二套展示或上下文模型。
- [x] Web 显示当前接收模式、最后成功同步与可恢复错误；不向用户暴露调度参数、ETag 或 secret。
- [x] 更新 `.env.example`、`BOOTSTRAP.md`、backend/frontend context，明确无 Webhook URL 的默认行为、PAT 分模式最小权限和 CI 能力差异。

### Stage 3G：双模式收尾与验收

- [x] 完成 Polling、模式解析、迁移和共享事件链路自动化测试，并按测试矩阵运行后端、Web、lint/compile 最小集。
- [ ] 使用真实 GitHub 仓库在没有公网入口的部署上验证 baseline、Issue/PR 更新、`304`、额度 header、重启补查、PAT 失效和解绑。
- [ ] 在可用环境验证 Polling → Webhook → Polling 模式迁移不漏事件、不重复发卡；若没有公网 HTTPS，记录 Webhook 专项手工验收待办但不得阻塞 Polling 路径验收。
- [ ] 上位设计 Phase 3 只有在 Polling 核心完成条件、自动化验证和无公网真实仓库验收通过后才标记完成；未完成的 Webhook 环境专项项留在本计划或 `KNOWN_DRIFT.md`。

## 9. 自动化验证

### 9.1 Backend

使用 `./gradlew :backend:test`，新增或扩展：

- `GitHubRepositoryRefTest`：规范 URL、拒绝非 GitHub/额外 path/空 owner-repo。
- `GitEncryptionTest`：AES-GCM round trip、nonce 唯一、错误 key/损坏密文 fail-closed。
- `GitHubWebhookVerifierTest`：原始 body、有效/无效/缺失签名和输入格式。
- `GitEventParserTest`：三类事件、支持/忽略 action、缺字段、过长字段截断。
- `GitEventStoreTest`：原子保存、旧文件兼容、delivery 去重和 TTL/数量清理。
- `GitHubClientTest`：MockEngine 检查 API URL、headers、最小事件集、状态码映射、无重复 Hook 创建和回滚。
- `GitIngestionModeResolverTest`：`AUTO` 有/无显式可用 HTTPS URL、非法 URL 回退、强制 Webhook 缺 URL、强制 Polling，以及仅强制 Webhook 使用 `BACKEND_BASE_URL` 的兼容行为。
- `GitHubClientTest`：`since/state/sort/direction/per_page`、PR 识别、Link 分页、ETag/`If-None-Match`、`200/304/401/403/429/5xx` 和 rate-limit headers。
- `GitPollingServiceTest`：首次 baseline 无历史卡片、Issue/PR snapshot diff、重叠窗口去重、同秒事件、只对变化 PR 补详情、批次失败不推进游标、重启 catch-up。
- `GitPollingSchedulerTest`：Ktor 启停、解绑取消、串行+jitter、per-token 退避、reset/retry-after、连续失败 `ERROR` 与恢复，以及单实例保护。
- `GitWebhookRouteContractTest`：无 JWT 但必须 HMAC、401/202/204、TEAM scope、重复 delivery 不重复发消息、关闭 binding 不接收。
- `GitBindingRouteContractTest`：Workflow/Owner/member/non-member/普通 Room 权限、请求合同不增加 mode、无 URL 默认 Polling、PAT 不出响应和日志、关闭清密文/polling state。
- `GitEventStoreTest` + `GitPollingServiceTest`：Polling 重叠、Webhook redelivery 和模式迁移中的同一资源变更最多生成一张卡片。
- `GitContextBuilderTest`：仅 active binding 注入、字符预算、无 token/secret 泄露、解绑为空。
- `IssueToWorkspaceRouteContractTest`：成员门禁、Issue 拉取、目录信任/Bridge/Agent 门禁、`linkedGithubRef` 持久化、服务端不信任客户端 URL/body。

### 9.2 Web

使用 `./gradlew :frontend:webApp:nodeTest :frontend:webApp:compileProductionExecutableKotlinJs`，覆盖默认关闭/Owner 管理/成员只读、binding 表单只提交仓库+PAT、模式和同步状态展示、loading 与错误、GitHub 卡片历史回放、Issue 预填创建、可恢复失败，以及关闭后普通 Team Channel 回归。

### 9.3 跨端与共享合同

本阶段尽量不修改 shared `Message` 字段；事件卡片继续使用已有 `MessageType.CARD` JSON，以降低三端合同风险。若必须新增 shared 字段或 enum，必须同步 Android/Desktop/Harmony 的未知类型处理，并按 `TEST_MATRIX.md` 补跑 backend、Web、Android、Desktop 与 shared desktopTest。

## 10. 手工验收清单

1. 新建 Workflow Room，不开启 GitHub：Team Channel 与现有版本一致；普通消息不产生 Hook 注册、Polling 请求或卡片；`@Silk` 仍可用。
2. 不配置 `GITHUB_WEBHOOK_BASE_URL`，Owner 只用仓库 URL + 最小只读 PAT（`Issues: read`、`Pull requests: read`）开启绑定：成功进入 `POLLING`，不要求公网 IP/HTTPS，不调用 hooks API，不留下明文 PAT。
3. 首次 Polling baseline 不发送仓库历史卡片；仓库保持不变时后续同 URL 请求携带 `If-None-Match` 并获得 `304`，`X-RateLimit-Remaining` 不因该 `304` 减少。
4. 新建、关闭、重开和更新 Issue/PR：每个有意义的变化只出现一张 Team 卡片，正文/链接/action 正确；PR 摘要异步出现或明确降级，Issue “开始开发”可用。
5. Polling 请求超时、GitHub `5xx`、`403/429`、PAT 过期和权限不足：不推进游标、不刷错误卡片，按 header/退避暂停并在绑定状态显示可理解错误；恢复后补查不丢事件。
6. Polling 服务重启、重叠窗口重复返回和同秒多事件：绑定摘要、snapshot、cursor、事件卡片和 Workspace 关联可恢复，不重复广播也不跳过更新。
7. 配置有效公开 HTTPS Webhook URL 后绑定或迁移：沿用现有 `WEBHOOK` 模式，仓库只有一个 Silk Hook，事件集合正确；PAT/secret 不出现在 UI、历史、浏览器响应、日志和配置回显。
8. Webhook 模式真实投递 Issue、PR、check_run，并验证 redelivery、篡改 body、缺失签名和错误 hook ID：合法事件只出现一次，非法请求拒绝且不泄露 binding。没有公网验收环境时将此项保留为模式专项待办。
9. 成员从 Issue 卡片点击“开始开发”：看到预填标题，确认受信任目录后创建自己的 Workspace；`linkedGithubRef` 正确，新工作区顶部出现仓库、Issue 标题和链接摘要，且不产生 Agent 回复，不能进入他人的私密历史。
10. Owner 解绑后：Poller 停止、远端 Hook 删除或产生可见告警、polling state/密文/上下文清除；历史卡片仍可回放，`@Silk` 不再获得 GitHub 上下文，已有 Workspace 关联字段保留。
11. 两个成员同时查看 Team Channel 并从同一 Issue 创建 Workspace 时，目录、Agent session、消息 scope 互不混淆。

## 11. 完成定义

Phase 3 只有在以下条件全部满足后才能在上位设计中标记完成：

- 关闭状态与现有 Team Channel 完全兼容，没有隐式 GitHub 请求或 UI 噪音。
- binding、模式解析、Polling、Webhook、加密、权限、跨来源去重、事件卡片、摘要降级和 Issue-to-Workspace 自动化测试通过。
- 在没有 Webhook URL 和公网入口的真实部署中，仅提供 PAT + 仓库即可完成绑定；Issue/PR 的 baseline、更新提醒、`304`、限流退避、解绑和重启补查通过。
- Webhook 代码和自动化合同继续通过；真实 HTTPS 的 Issue/PR/CI、重放和错误签名是 Webhook 模式发布前的专项门禁，不是 Polling 默认路径的部署前提。
- Issue 入口没有绕过本地目录、Bridge、Agent 权限门槛；PAT/secret 没有进入客户端或日志。
- `git diff --check`、受影响的 `silkLint`、后端/Web 编译与测试通过，且没有调试代码、临时凭据和未使用配置。
- `.env.example`、`BOOTSTRAP.md`、backend/frontend context 与本计划的双模式、API、存储和运行事实一致。

## 12. 官方参考

- [GitHub REST API endpoints for repository webhooks](https://docs.github.com/en/rest/repos/webhooks)：Hook 创建/更新/删除、`Webhooks: write` 和 JSON/secret/SSL 配置。
- [Validating webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)：原始 body、`X-Hub-Signature-256` 和 SHA-256 HMAC 验签。
- [Best practices for using webhooks](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)：最小事件集、HTTPS、secret、10 秒响应和 `X-GitHub-Delivery` 去重。
- [List repository issues](https://docs.github.com/en/rest/issues/issues#list-repository-issues)：仓库级 Issue/PR 混合列表、`since`、`sort`、`direction` 与分页。
- [Best practices for using the REST API](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)：Polling、认证条件请求、ETag/`304`、串行请求和错误退避。
- [Rate limits for the REST API](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)：primary/secondary limits、`X-RateLimit-*`、`Retry-After` 和 `403/429` 处理。
- [List workflow runs for a repository](https://docs.github.com/en/rest/actions/workflow-runs#list-workflow-runs-for-a-repository)：GitHub Actions 仓库级低频轮询的可选评估入口。
- [List check runs for a Git reference](https://docs.github.com/en/rest/checks/runs#list-check-runs-for-a-git-reference)：通用 Check Runs 需要指定 ref，解释 Polling MVP 不逐 commit 查询的边界。
