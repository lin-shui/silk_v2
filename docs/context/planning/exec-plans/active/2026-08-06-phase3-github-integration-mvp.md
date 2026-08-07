# Phase 3：Workflow Room GitHub 集成 MVP

**文档类型**：执行计划  
**创建日期**：2026-08-06  
**状态**：计划中  
**上位设计**：[Workflow Room 重新设计](2026-07-24-workflow-room-redesign.md)

## 0. 入场条件

- Phase 2.5 的代码、跨端合同和自动化验证已经完成；发布前仍应完成 Unified Room Navigation 的双账号、窄屏和 cc-connect 手工验收。
- Phase 2.5 手工验收未通过时，不得把 GitHub 事件作为正式发布能力打开；可以先并行开发 3A/3B 的纯后端合同和 Mock 测试。
- 开始真实绑定验收前，必须准备公开 HTTPS 后端地址、专用 GitHub 测试仓库、最小权限 fine-grained PAT、独立 `SILK_ENCRYPTION_KEY` 和可连接的 ACP Bridge。

## 1. 阶段目标

Phase 3 为 Workflow Room 的 Team Channel 增加一个可选开启的 GitHub 集成功能：

1. Workflow Room Owner 主动绑定一个 GitHub 仓库和 PAT 后，Silk 自动注册最小事件集的 Repository Webhook。
2. GitHub Issue、Pull Request、CI 检查事件以 Team Channel 卡片消息进入 Room，并按事件类型生成可读摘要。
3. 成员可从 Issue 卡片进入预填的工作区创建流程，确认本地目录后创建个人 Workspace，并在新工作区顶部看到仓库、Issue 标题和链接摘要；该摘要不触发 Agent。
4. Team Channel 的 `@Silk` 仍是唯一的平台 AI 触发入口；绑定开启时才向该 AI 注入近期 GitHub 事件上下文。

**默认行为必须保持不变**：没有成功绑定记录时，GitHub 集成视为关闭。关闭状态下不注册 Webhook、不调用 GitHub API、不显示 GitHub 事件卡片、不向 AI 注入 GitHub 上下文，现有 Team Channel 的聊天、成员、文件、工作区和 `@Silk` 行为不改变。

## 2. 当前基线与约束

- Room 复用现有 `Group`；只有 `RoomKind.WORKFLOW` 才能启用 GitHub，`CHAT` / `SILK_PRIVATE` 请求返回 `404` 或 `409`。
- Workflow Team Channel 已使用 `scope=TEAM`，可复用现有 `ChatServer.broadcast()`、历史回放和 `MessageType.CARD`。
- Workspace 创建当前要求 Room 成员身份、在线 ACP Bridge、受信任的本地目录和有效 Agent 类型。Issue 入口不能绕过这些执行权限门槛。
- Workspace 当前没有 `linkedGithubRef` 字段，需要以可选字段扩展 `PersonalWorkspace` 和 Web DTO；旧 `workspace_store.json` 必须兼容读取。
- 当前卡片回复注册表是进程内状态，重启后会失效。因此 GitHub Issue 卡片按钮必须走可重放的 HTTP 流程，服务端再次校验 Room、绑定和 Issue 编号。
- 后端已有 Ktor CIO Client、JSON 文件存储、JWT 身份解析和 Workspace/Room 路由分层，优先沿用这些模式。

## 3. 范围

### 3.1 MVP 包含

- GitHub（仅 github.com）PAT 绑定，一个 Workflow Room 最多一个仓库。
- Fine-grained PAT 优先；最小权限为目标仓库 `Webhooks: write`、`Issues: read` 和 GitHub 强制的 Metadata 读取权限。不得要求 Contents write、Administration 或全局账号权限。
- Webhook 事件：`issues`、`pull_request`、`check_run`。只处理产品需要的 action；忽略无关 action 并返回成功。
- Repository Webhook 的创建、幂等更新、解绑和失败补偿。
- `X-Hub-Signature-256` 原始字节 HMAC-SHA256 验签、`X-GitHub-Delivery` 去重、事件大小限制和异步处理。
- Issue、PR、CI 事件的 Team Channel 卡片；PR 与失败的 CI 生成 AI 摘要，AI 不可用时仍保留原始事件卡片。
- Issue → Workspace 预填创建流程：Issue 标题、编号、URL、正文、标签、有限评论上下文和关联引用进入首条 Workspace prompt；创建后写入 `linkedGithubRef`。
- Team Channel `@Silk` GitHub 上下文：仅当前 Room 的 active binding 生效，只注入最近且截断后的结构化事件摘要。
- Web、后端自动化测试、配置/运维文档和真实 GitHub Webhook 手工验收。

### 3.2 明确不包含

- GitLab、GitHub OAuth / GitHub App、多仓库绑定。
- `/pr-review`、自动创建/评论/合并 PR、Issue 状态回写、commit/push 里程碑自动推送。
- 全量 GitHub 历史回补、Webhook 投递管理后台、复杂事件规则编辑器。
- 没有 Bridge 或未授权目录时自动在服务器执行代码。
- 将 PAT 或 Webhook secret 下发到任何前端、消息内容、日志或 URL。

## 4. 产品行为与可选开关

### 4.1 开启与关闭

- Team Channel 头部或 Room 菜单增加 `GitHub 集成`入口。所有成员可看到当前状态；只有 Owner 可操作绑定。
- 初始状态为 `未开启`，只显示开启入口和能力说明，不创建空绑定记录。
- Owner 提交仓库 URL、PAT 后，后端先验证仓库可访问和 Webhook 权限，再创建/复用 Webhook；全部成功后才写入本地 active binding，前端切换为 `已开启`。
- 任何验证或注册失败都保持关闭，不保存明文或不可用的半成品 PAT；远端已创建的 Hook 必须尽力回滚。
- Owner 点击关闭/解绑时，后端删除 Silk 创建的 Hook（删除失败需记录告警并继续清除本地凭据），清除 token/secret 加密数据和事件上下文；既有 Team Channel 历史卡片不删除，但卡片按钮在绑定关闭后返回明确错误。
- MVP 不保留“暂停但保留 PAT”状态。再次开启需重新输入 PAT，避免关闭后继续持有仓库高权限凭据。

### 4.2 权限

| 操作 | Owner/HOST | 其他 Room 成员 | 非成员 / 普通 Room |
| --- | --- | --- | --- |
| 查看绑定摘要（不含 secret/token） | ✅ | ✅ | 404 |
| 开启、换仓库、轮换 PAT | ✅ | 403 | 404 |
| 解绑 | ✅ | 403 | 404 |
| 接收 Webhook | 服务端 HMAC，不依赖 JWT | 服务端 HMAC，不依赖 JWT | 服务端 HMAC，不依赖 JWT |
| 从 Issue 创建自己的 Workspace | ✅ | ✅ | 404 |

Issue-to-Workspace 使用 Room Owner 保存的 PAT 拉取 Issue，但永远只把经过长度限制的 Issue 内容发送给当前成员自己的 Workspace；PAT 不暴露给成员。

## 5. 后端设计

### 5.1 模块边界

建议新增 `backend/.../git/`：`GitHubClient`（REST、超时、错误映射、Hook reconcile）、`GitHubModels`、`GitHubRepositoryRef`、`GitHubWebhookVerifier`、`GitEventParser`、`GitEventStore`、`GitEventBroadcaster`、`GitContextBuilder`、`GitEncryption` 和 `GitRoutes`。路由在 `Routing.kt` 显式挂载；Webhook 不依赖 JWT，其他 Git 路由统一解析 JWT 后检查 Room 成员和角色。

### 5.2 本地持久化

新增 `git_integration_store.json`，位于 `SILK_WORKFLOW_DIR`（默认 `~/.silk-data/workflows`），使用临时文件 + 原子 rename、同步读改写，保持与 `WorkspaceManager` 一致。

存储需要包含以下三类数据：

- `RoomGitBinding`：`roomId/provider/owner/repo/hookId/webhookUrl/tokenEncrypted/webhookSecretEncrypted/createdBy/createdAt/updatedAt/lastDeliveryAt`。
- `GitEventRecord`：`deliveryId/roomId/event/action/repository/issueNumber/title/htmlUrl/summary/createdAt`。
- `processedDeliveryIds`：有 TTL（至少 30 天）和数量上限的 delivery 去重集合。

实现要求：binding 只存密文；API DTO 只返回 `enabled/provider/owner/repo/events/lastDeliveryAt/status`；事件只保存展示和 AI 所需的截断字段，不保存完整 Webhook body；旧文件不存在等价于关闭；读写和去重在同一临界区完成。

### 5.3 加密与配置

- 新增必需环境变量 `SILK_ENCRYPTION_KEY`：Base64 编码的 32 字节密钥，示例生成命令为 `openssl rand -base64 32`。
- 使用 AES-GCM、随机 12 字节 nonce，密文带版本号（如 `v1:<base64(nonce+ciphertext+tag)>`），不得复用 nonce。
- 未配置或长度不正确时，开启绑定直接返回配置错误，绝不明文落盘；已有 binding 无法解密时状态为 `ERROR`，Webhook 不处理并记录告警。
- 新增 `GITHUB_WEBHOOK_BASE_URL`，用于拼接 `/api/git/webhook/{roomId}`；可回退到 `BACKEND_BASE_URL`，但生产绑定必须是公开、稳定的 HTTPS 地址。仅本地 fixture 测试允许不注册远端 Hook。
- GitHub Client 集中设置 `Accept: application/vnd.github+json`、版本 header、Bearer PAT、连接/请求超时和有限重试；token、secret、Authorization header 永不写日志。

### 5.4 GitHub Client 与绑定事务

1. 规范化 `https://github.com/{owner}/{repo}`，拒绝非 GitHub host、额外 path、query、fragment、空段和错误后缀，避免 SSRF。
2. 使用 PAT `GET /repos/{owner}/{repo}` 验证仓库存在和 token 可读。
3. 列出现有 hooks，按精确 callback URL 查找 Silk Hook，避免重复创建；找到后更新事件、secret、active 状态，否则创建一个 Hook。config 固定 `content_type=json`、SSL 校验开启、secret 为高熵随机值。
4. 订阅最小事件集 `issues`、`pull_request`、`check_run`。MVP 不订阅 `push`，避免每次 commit 噪音进入 Team Channel。
5. 远端成功后原子写本地 binding；本地写失败时尽力删除新 Hook。换仓库时先完成新 Hook 与本地记录，再删除旧 Hook；旧 Hook 清理失败需有可观测告警。
6. 解绑时删除记录对应 Hook，404 视为已删除；无论远端结果如何都清除本地密文和事件上下文。

### 5.5 Webhook 接收合同

`POST /api/git/webhook/{roomId}` 的要求：

- 使用 `call.receive<ByteArray>()` 读取原始 body，先验签后 JSON decode；不接受 query/body 中的 secret，也不依赖 JWT。
- 必须检查 `X-Hub-Signature-256: sha256=<hex>`，使用 HMAC-SHA256 + constant-time compare；缺失/不匹配返回 `401` 或 `403`，不暴露 Room 是否存在。
- 读取 `X-GitHub-Event`、`X-GitHub-Delivery`、可选 `X-GitHub-Hook-ID`；hook ID 存在时必须匹配 active binding。
- 限制 body 大小，检查 event/action；解析失败或不支持 action 不进入广播；重复 delivery 返回 `202`/`204`。
- 验签、绑定查找、delivery 去重和事件入队在快速路径完成，返回 `202 Accepted`；卡片写入、AI 摘要和外部 Issue 拉取在后台执行，确保 GitHub 10 秒响应窗口内完成。
- 事件只广播到绑定 Room 的 `TEAM` scope，不能创建 `WORKSPACE` 消息或触发编码 Agent。Room 被删除/解绑后旧 delivery 不得恢复或创建 Room。

### 5.6 事件映射与 AI 摘要

| GitHub 事件 | MVP action | Team Channel | AI 摘要 |
| --- | --- | --- | --- |
| `issues` | `opened`、`closed`、`reopened` | Issue 卡片，标题/编号/作者/标签/状态/链接，提供“开始开发” | 不强制，原始卡片即可 |
| `pull_request` | `opened`、`closed`、`reopened`、`ready_for_review` | PR 卡片，标题/编号/作者/分支/状态/链接 | 异步生成简短变更摘要，失败保留原始卡片 |
| `check_run` | `completed` | CI 卡片，名称/commit/结论/链接 | 仅 failure/timed_out/cancelled 尝试分析，成功只发状态 |

事件卡片使用已有 `MessageType.CARD` 和 `TEAM` scope，发送者固定为系统身份（如 `github_bot`），持久化到 Room 历史。Issue 按钮动作携带不含 secret 的 `roomId + issueNumber`，前端点击时调用受保护 API；服务端必须再次从 active binding 拉取 Issue，不能信任卡片里的正文或 URL。

AI 摘要复用现有 `DirectModelAgent` 能力，但通过独立 Git 摘要服务和专用 session/context 调用，不伪造用户 `@Silk` 消息，不污染 Team Channel 用户对话历史。摘要 prompt 只包含允许字段和长度限制后的文本；超时、未配置、限流或异常不阻塞原始事件落地。

### 5.7 Team Channel GitHub 上下文

`GitContextBuilder` 为当前 Workflow Room 生成最近 N 条事件的短文本（建议最多 20 条、总字符预算固定），注入现有 `DirectModelAgent.processInput(..., additionalContext=...)` 链路：

- 仅 active binding 存在时启用；无 binding 时完全不读 GitEventStore。
- 只向当前 Room 的 `@Silk` 请求注入；普通 Team 消息仍不触发 AI。
- 只包含 event/action/title/status/link/summary，不包含 PAT、secret、完整 webhook body 或未授权 Workspace 内容。
- 事件读取失败时降级为无 GitHub 上下文，不阻塞普通 AI 对话。

## 6. API 合同

### 6.1 Binding

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/rooms/{roomId}/git/binding` | 成员读取脱敏摘要；未开启返回 `enabled=false` |
| `POST` | `/api/rooms/{roomId}/git/binding` | Owner 开启或换绑；body 为 provider、repositoryUrl、token |
| `DELETE` | `/api/rooms/{roomId}/git/binding` | Owner 解绑并关闭集成 |

错误码至少区分：`WORKFLOW_ROOM_REQUIRED`、`NOT_ROOM_OWNER`、`INVALID_REPOSITORY_URL`、`GITHUB_UNAUTHORIZED`、`GITHUB_FORBIDDEN`、`WEBHOOK_URL_REQUIRED`、`ENCRYPTION_NOT_CONFIGURED`、`WEBHOOK_REGISTRATION_FAILED`、`BINDING_NOT_FOUND`。

### 6.2 Issue → Workspace

建议使用 Room 嵌套路由：`POST /api/rooms/{roomId}/git/issue-to-workspace`。请求字段为 `issueNumber/name?/workingDir/agentType/visibility`。

服务端必须检查成员和 active binding，用绑定 PAT 拉取 Issue 基本信息；Issue 编号、仓库和 URL 全部由服务端确定；复用目录信任、Bridge 在线和 Agent 类型检查；创建 Workspace，名称默认 `#<number>: <title>`，写入 `linkedGithubRef`；返回 Workspace DTO 和仓库、Issue 标题、链接组成的 `issueSummary`。 

为保持“一键进入”的体验，Web 点击按钮后打开现有 Workspace 创建对话框，预填名称；用户选择/确认本地目录后提交 API，创建成功后切换到新 Workspace 并发送一条 `SYSTEM` 摘要消息，不启动 Workspace Agent。若创建成功但摘要消息失败，保留 Workspace 并允许重试。

## 7. Web UI 计划

- 在现有 `ConversationRoomHeaderActions` 中增加 GitHub 状态/设置入口，不改变已有文件、导出、邀请、成员按钮顺序和图标。
- 非 Owner 显示 `未开启` 或仓库摘要，不显示 PAT 输入和管理操作；Owner 可开启、换绑、解绑。
- 未开启时不渲染 GitHub 空面板、专属发送工具或占位消息；只有绑定成功后显示仓库状态、最近 delivery 和事件卡片。
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

### Stage 3E：收尾与验收

- [x] 按测试矩阵运行后端、Web、共享合同和 lint/compile 最小集。
- [ ] 使用 GitHub 测试仓库和真实 HTTPS endpoint 做 Issue/PR/check_run、redelivery、invalid signature、解绑回归。
- [x] 清理调试日志、临时 fixture、明文 token、冗余 JSON 解析和未使用 UI 状态（代码扫描无明文凭据或调试 println，视为完成）。
- [ ] 上位设计 Phase 3 只有在所有自动化和手工验收项完成后才标记完成；未完成项记录到本计划或 `KNOWN_DRIFT.md`。

## 9. 自动化验证

### 9.1 Backend

使用 `./gradlew :backend:test`，新增或扩展：

- `GitHubRepositoryRefTest`：规范 URL、拒绝非 GitHub/额外 path/空 owner-repo。
- `GitEncryptionTest`：AES-GCM round trip、nonce 唯一、错误 key/损坏密文 fail-closed。
- `GitHubWebhookVerifierTest`：原始 body、有效/无效/缺失签名和输入格式。
- `GitEventParserTest`：三类事件、支持/忽略 action、缺字段、过长字段截断。
- `GitEventStoreTest`：原子保存、旧文件兼容、delivery 去重和 TTL/数量清理。
- `GitHubClientTest`：MockEngine 检查 API URL、headers、最小事件集、状态码映射、无重复 Hook 创建和回滚。
- `GitWebhookRouteContractTest`：无 JWT 但必须 HMAC、401/202/204、TEAM scope、重复 delivery 不重复发消息、关闭 binding 不接收。
- `GitBindingRouteContractTest`：Workflow/Owner/member/non-member/普通 Room 权限、PAT 不出响应和日志、关闭清密文。
- `GitContextBuilderTest`：仅 active binding 注入、字符预算、无 token/secret 泄露、解绑为空。
- `IssueToWorkspaceRouteContractTest`：成员门禁、Issue 拉取、目录信任/Bridge/Agent 门禁、`linkedGithubRef` 持久化、服务端不信任客户端 URL/body。

### 9.2 Web

使用 `./gradlew :frontend:webApp:nodeTest :frontend:webApp:compileProductionExecutableKotlinJs`，覆盖默认关闭/Owner 管理/成员只读、binding 表单 loading 与错误、GitHub 卡片历史回放、Issue 预填创建、可恢复失败，以及关闭后普通 Team Channel 回归。

### 9.3 跨端与共享合同

本阶段尽量不修改 shared `Message` 字段；事件卡片继续使用已有 `MessageType.CARD` JSON，以降低三端合同风险。若必须新增 shared 字段或 enum，必须同步 Android/Desktop/Harmony 的未知类型处理，并按 `TEST_MATRIX.md` 补跑 backend、Web、Android、Desktop 与 shared desktopTest。

## 10. 手工验收清单

1. 新建 Workflow Room，不开启 GitHub：Team Channel 与现有版本一致；普通消息不产生 GitHub 网络请求或卡片；`@Silk` 仍可用。
2. Owner 用最小权限 PAT 开启绑定：无效 URL、无权 PAT、缺少 `SILK_ENCRYPTION_KEY`、无公网 HTTPS 均有明确错误且不会留下本地/远端半成品。
3. 开启成功后仓库只有一个 Silk Hook，事件集合正确；PAT/secret 不出现在 UI、历史、浏览器响应、日志和配置回显。
4. 真实投递 Issue、PR、check_run：每个 Team Channel 卡片只出现一次，正文和链接正确，PR/失败 CI 摘要异步出现或明确降级。
5. 重放同一 delivery、篡改 body、缺失签名、错误 hook ID：重放不重复，其他请求拒绝且不泄露 binding。
6. 成员从 Issue 卡片点击“开始开发”：看到预填标题，确认受信任目录后创建自己的 Workspace；`linkedGithubRef` 正确，新工作区顶部出现仓库、Issue 标题和链接摘要，且不产生 Agent 回复，不能进入他人的私密历史。
7. 没有 Bridge、目录未信任、Issue 已删除或 PAT 失效时，入口可恢复、错误可理解，原始事件不消失。
8. Owner 解绑后：远端 Hook 删除或产生可见告警；新 delivery 不进入 Room；历史卡片仍可回放；`@Silk` 不再获得 GitHub 上下文；已有 Workspace 关联字段保留。
9. 服务重启后：绑定摘要、事件卡片和 Workspace 关联可恢复；delivery 去重不重复消费；密钥缺失时 fail-closed。
10. 两个成员同时查看 Team Channel 并从同一 Issue 创建 Workspace 时，目录、Agent session、消息 scope 互不混淆。

## 11. 完成定义

Phase 3 只有在以下条件全部满足后才能在上位设计中标记完成：

- 关闭状态与现有 Team Channel 完全兼容，没有隐式 GitHub 请求或 UI 噪音。
- binding、Webhook、加密、权限、去重、事件卡片、摘要降级和 Issue-to-Workspace 自动化测试通过。
- 真实 GitHub HTTPS Webhook 的 Issue/PR/CI、重放、错误签名、解绑和重启场景通过。
- Issue 入口没有绕过本地目录、Bridge、Agent 权限门槛；PAT/secret 没有进入客户端或日志。
- `git diff --check`、受影响的 `silkLint`、后端/Web 编译与测试通过，且没有调试代码、临时凭据和未使用配置。
- `.env.example`、`BOOTSTRAP.md`、backend/frontend context 与本计划的 API/存储事实一致。

## 12. 官方参考

- [GitHub REST API endpoints for repository webhooks](https://docs.github.com/en/rest/repos/webhooks)：Hook 创建/更新/删除、`Webhooks: write` 和 JSON/secret/SSL 配置。
- [Validating webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)：原始 body、`X-Hub-Signature-256` 和 SHA-256 HMAC 验签。
- [Best practices for using webhooks](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)：最小事件集、HTTPS、secret、10 秒响应和 `X-GitHub-Delivery` 去重。
- [REST API endpoints for issues](https://docs.github.com/en/rest/issues/issues)：Issue 内容、labels、comments 和 PR/Issue 返回体差异。
