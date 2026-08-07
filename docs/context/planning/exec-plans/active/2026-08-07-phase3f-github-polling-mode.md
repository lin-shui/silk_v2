# Phase 3F：GitHub 轮询模式（免公网部署）

**文档类型**：执行计划
**创建日期**：2026-08-07
**状态**：计划中
**上位计划**：[Phase 3 GitHub 集成 MVP](2026-08-06-phase3-github-integration-mvp.md)

## 1. 背景与目标

Phase 3 的 GitHub 集成依赖 Webhook，要求部署方提供公开 HTTPS 地址（`GITHUB_WEBHOOK_BASE_URL`）。自部署用户通常在 NAT/防火墙后，需要额外搭建 ngrok / cloudflared 隧道，这是当前部署链路上最重的一步。

Phase 3F 增加**轮询模式**作为默认工作方式：Silk 主动调用 GitHub API 拉取事件，不需要任何公网入口。

### 1.1 模式选择规则

| 部署配置 | 生效模式 |
| --- | --- |
| 未配置 `GITHUB_WEBHOOK_BASE_URL` | **POLLING**（默认） |
| 已配置且为可用的公开 HTTPS 地址 | **WEBHOOK**（保留现有行为） |

模式在**绑定时确定并持久化到 binding**，不在每次事件处理时重新判断，避免重启后静默切换导致事件重复或丢失。

### 1.2 目标

1. 没有公网地址的部署可以完整使用 Issue / PR 事件卡片和 Issue-to-Workspace，零额外运维。
2. 轮询消耗对 GitHub API 配额影响可忽略（依赖条件请求）。
3. 现有 Webhook 模式行为、存储结构、已有绑定完全不受影响。

## 2. 配额与条件请求（设计依据）

- 认证用户 REST API 主限额为 **5,000 请求/小时**，计费主体是 PAT 所属账号（同账号多 token 共享）。
- 条件请求返回 `304 Not Modified` 时**不计入主限额**（官方要求：必须带正确的 `Authorization` header）。
- 因此实际消耗与仓库活跃度成正比，与轮询频率基本无关。60 秒间隔、单 Room 单端点的空闲仓库接近零消耗。
- 次级限额（并发/突发）无响应头可查，必须**串行**请求并在请求间插入小延迟。

参考：
- [Rate limits for the REST API](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
- [Best practices for using the REST API](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)

## 3. 范围

### 3.1 包含

- `GitIntegrationMode` 枚举（`WEBHOOK` / `POLLING`）持久化到 `RoomGitBinding`。
- 绑定流程按配置分支：POLLING 模式不注册 Webhook、不生成 webhook secret。
- 轮询调度器：定时、串行、按 binding 隔离失败。
- `GET /repos/{owner}/{repo}/issues` 拉取 Issue 与 PR（该端点同时返回两者）。
- ETag 条件请求 + `since` 游标双重收敛。
- 轮询事件去重（复用现有 `processedDeliveryIds` 机制）。
- 复用现有 `GitEventParser` → `GitEventRecord` → `GitEventCardBuilder` → Team Channel CARD 链路。
- Web binding 面板显示当前生效模式。
- 后端自动化测试与文档同步。

### 3.2 不包含

- **`check_run` 轮询**（原因见 §3.3）。
- Webhook 模式与 POLLING 模式同时对同一 Room 生效。
- 轮询历史回补（首次绑定只建立游标基线，不拉取历史事件）。
- 轮询间隔的按 Room 级配置（MVP 只有全局配置）。

### 3.3 为什么 `check_run` 不做轮询

Issue/PR 能低成本轮询的前提是仓库级列表端点 + `since` 时间过滤：

```
GET /repos/{owner}/{repo}/issues?since={ts}    ← 一个请求拿到「此后所有变化」
```

check-runs 两个条件都不满足。官方只有两个列表端点，且都带作用域：

```
GET /repos/{owner}/{repo}/commits/{ref}/check-runs      ← 必须给 commit/branch/tag
GET /repos/{owner}/{repo}/check-suites/{id}/check-runs  ← 必须给 suite id
```

没有仓库级 `GET /repos/{owner}/{repo}/check-runs`，且**所有 check-runs 端点都不支持 `since`**（仅有 `filter=latest|all`，那是排序而非时间边界）。因此「拿到最近变化的 CI 结果」没有对应的单次请求，必须先确定要查哪些 ref 再逐个查，一轮从 1 个请求变成 1+N 个，且 N 没有时间上界收敛。

**能力差异（需在 UI 与文档说明）**：POLLING 模式下没有 CI 事件卡片，也没有失败 CI 的分析摘要。

**PR 的 AI 摘要不受影响**——`GitEventSummaryService.shouldSummarize()` 对 `event == "pull_request"` 无条件返回 true，与 check_run 分支独立，PR 事件在轮询模式下正常获取。

### 3.4 延后评估：PR head CI 轮询（本阶段不做）

存在一条成本可控的部分方案，记录备查，等 3F 主链路上线并实际使用后再判断是否需要：

```
1. GET /repos/{o}/{r}/pulls?sort=updated&direction=desc&per_page=30   → PR 对象带 head.sha
2. 对 updated_at > pollCursor 的 PR，逐个带 ETag 查 /commits/{head.sha}/check-runs
```

- 成本：1 + 最近更新的 PR 数；稳定期多为 304。5 个活跃 PR、60 秒间隔约占配额 7%。
- 代价：需换用 `/pulls` 端点（`/issues` 返回的 PR 子对象没有 `head.sha`），而 `/pulls` 不支持 `since`，要靠 `sort=updated` + 客户端比对游标收敛；需按 SHA 分别存 ETag；只覆盖 PR 的 CI，push 到默认分支触发的 CI 仍拿不到。

## 4. 已知精度差异（必须接受的取舍）

Webhook 推送携带明确的 `action`（`opened` / `closed` / `reopened`），轮询只能看到资源的**当前状态**，无法看到状态跃迁。推断规则：

| 观察到的状态 | 推断 action |
| --- | --- |
| `created_at == updated_at` 且 `state == open` | `opened` |
| `state == closed` | `closed` |
| 其余（标题/正文/标签变更等） | `updated` |

`reopened` 在轮询模式下基本无法可靠区分，会落到 `closed` 或 `updated`。`updated` 是 POLLING 模式新增的 action 值，需要加入 `GitEventParser` 的支持集合，并在卡片标题上正确渲染。

## 5. 后端设计

### 5.1 数据模型扩展

`RoomGitBinding` 新增字段（全部可选，旧 `git_integration_store.json` 直接兼容）：

```
mode: GitIntegrationMode = WEBHOOK   // 旧记录默认 WEBHOOK，保持既有行为
pollCursor: Long? = null             // 已处理的 updated_at 水位（epoch ms）
issuesEtag: String? = null           // /issues 端点的 ETag
lastPolledAt: Long? = null           // 最近一次轮询完成时间，用于 UI 展示
```

`webhookSecretEncrypted` 在 POLLING 模式下不再有意义，但**保留为非空字段并写入空占位密文**，避免改动现有序列化契约与 Webhook 路径的解密逻辑。

`GitBindingDto` 新增 `mode` 与 `lastPolledAt`，供 Web 展示。

### 5.2 绑定流程分支

改 `GitRoutes.kt` 的 POST binding：

1. 读 `GitConfig.webhookBaseUrl`，用现有 `isUsableCallbackBase()` 判断可用性。
2. **可用** → 走现状：注册/复用 Hook，`mode = WEBHOOK`。
3. **不可用或未配置** → 不再返回 `WEBHOOK_URL_REQUIRED`；跳过 Hook 注册，`mode = POLLING`，`hookId = null`，并用一次 `GET /repos/{owner}/{repo}` 校验 PAT 可读（这步现状已有，两个模式共用）。
4. POLLING 绑定成功后立即建立游标基线：`pollCursor = now()`，不回补历史。

解绑逻辑：POLLING 模式 `hookId` 为空，现有 `if (key != null && binding.hookId != null)` 条件天然跳过远端删除，只清本地记录，无需改动。

### 5.3 轮询调度器

新增 `backend/.../git/GitPollingScheduler.kt`：

- 单个后台协程循环，间隔取 `GitConfig.pollIntervalSeconds`。
- 每轮：读取所有 `mode == POLLING && status == ACTIVE` 的 binding，**串行**处理，binding 之间插入固定小延迟（抵御次级限额）。
- 单个 binding 失败（网络、401、解密失败）只记录告警并跳过，不影响其他 binding，不中断循环。
- PAT 失效（401/403）时把 binding 置为 `ERROR` 状态并停止轮询该 binding，与 Webhook 模式的失效表现一致。
- 生命周期：随 Ktor 应用启动，在 `ApplicationStopping` 时取消。Room 被删除时由现有 `onRoomDeleted` 清掉 binding，下一轮自然不再轮询。
- 未配置 `SILK_ENCRYPTION_KEY` 时不启动（fail-closed，与绑定路径一致）。

### 5.4 单次轮询流程

```
1. 解密 PAT
2. GET /repos/{owner}/{repo}/issues
     ?since={pollCursor}&state=all&sort=updated&direction=desc&per_page=50
   If-None-Match: {issuesEtag}
3. 304 → 更新 lastPolledAt，结束（不计配额）
4. 200 → 存新 ETag；逐条解析：
     - 有 pull_request 键 → event = "pull_request"
     - 否则 → event = "issues"
     - 按 §4 规则推断 action
     - 生成合成 deliveryId（见 5.5）
     - recordDelivery() 去重后广播 CARD
5. pollCursor = 本批最大 updated_at
```

`per_page=50` 是容量上限保护：极端活跃仓库单轮可能超过 50 条，超出部分靠 `pollCursor` 在下一轮补上（因为游标只推进到已处理的最大水位）。MVP 不做分页翻页。

### 5.5 去重键设计

Webhook 用 `X-GitHub-Delivery` 去重。轮询没有这个 ID，合成一个稳定键：

```
poll:{event}:{number}:{updated_at_epoch_ms}
```

同一资源在同一 `updated_at` 上重复出现 → 键相同 → 复用现有 `processedDeliveryIds` 集合去重。资源被再次更新 → `updated_at` 变化 → 键变化 → 作为新事件广播。这样无需新增去重存储结构，也天然继承现有的 TTL 与数量上限清理。

### 5.6 GitHubClient 扩展

现有 `request()` 不支持自定义请求头，需要：

- `request()` 增加可选 header 参数（用于 `If-None-Match`）。
- 增加返回体与响应头的组合返回类型，以便读取 `ETag` 和区分 `304`。
- **`304` 不能走现有的非 2xx 抛异常路径**——现在 `request()` 对 `status !in 200..299` 一律抛 `GitHubApiException`，必须把 `304` 作为正常结果返回。这是本阶段唯一需要改动现有 client 控制流的地方，改动时不能影响 Hook 相关调用。
- 新增 `listIssuesAndPulls(ref, token, since, etag)`。

### 5.7 配置

`.env.example` 新增：

```
# 轮询间隔秒数，默认 60；下限 20（防触发 GitHub 次级限额）
# GITHUB_POLL_INTERVAL_SECONDS=60
```

`GitConfig` 增加 `pollIntervalSeconds`，读取并对下限做钳制。

## 6. Web UI

- Binding 面板显示当前模式：`实时推送（Webhook）` 或 `定时同步（约 N 秒）`。
- POLLING 模式下补一行说明：CI 检查事件不可用，需要配置 `GITHUB_WEBHOOK_BASE_URL` 才能启用。
- 已连接区块把 `最近接收` 的时间来源改为 `lastDeliveryAt ?: lastPolledAt`。
- 事件卡片、Issue「开始开发」按钮、Issue-to-Workspace 流程**完全不变**——两个模式产出的都是同样的 `GitEventRecord` 和 CARD 消息。

## 7. 分批实施顺序

### Stage 3F-A：模型与配置

- [x] 新增 `GitIntegrationMode`，扩展 `RoomGitBinding`（`mode`/`pollCursor`/`issuesEtag`/`lastPolledAt`）与 `GitBindingDto`（`mode`/`lastPolledAt`/`pollIntervalSeconds`），`toDto()` 按模式返回不同 events 列表；旧文件 `ignoreUnknownKeys` 默认 WEBHOOK。
- [x] `GitConfig` 增加 `pollIntervalSeconds`（默认60，下限 20），`.env.example` 新增两个变量说明并注明模式选择规则。
- [x] `GitEventStore` 增加 `listPollableBindings()` 和 `updatePollState()`（游标只前进）。

### Stage 3F-B：Client 与解析

- [x] `GitHubClient` 新增独立 `conditionalGet()` 路径（`ConditionalGetResult` sealed interface、`GitHubIssueListItem`、`listIssuesAndPulls()`）；`request()` 一行未动，Hook 调用无回归。
- [x] `GitEventParser` 新增 `parseIssueList()`、`shouldBroadcast()`、`pollDeliveryId()`、`inferAction()`（`opened`/`reopened`/`merged`/`closed`/`updated` 五种推断规则）。
- [x] `GitEventCardBuilder` 新增 merged PR 绿色模板与中文 action 标题映射。
- [x] `GitPollingParserTest`：所有推断规则、PR/issue 区分、shouldBroadcast、pollDeliveryId 稳定性、parseIssueList 容错（14 个用例，全部通过）。

### Stage 3F-C：绑定分支与调度器

- [x] `GitRoutes.kt` POST binding：按 `isUsableCallbackBase()` 分支，POLLING 时跳过 Hook 注册，写 `mode=POLLING`，`pollCursor = now()`。
- [x] `GitPollingScheduler`：串行轮询、per-binding 失败隔离、PAT 401/403 置 ERROR、SILK_ENCRYPTION_KEY 缺失 fail-closed、生命周期绑定 `ApplicationStopping`。
- [x] `GitBindingRouteContractTest` 扩展：验证无 `GITHUB_WEBHOOK_BASE_URL` 时不再返回 `412 WEBHOOK_URL_REQUIRED`（polling 分支已生效）。

### Stage 3F-D：Web 与收尾

- [x] `ApiClient.kt`：`GitBindingSummary` 增加 `mode`/`lastPolledAt`/`pollIntervalSeconds`，新增 `transportModeLabel()`/`isPollingMode()`/`lastActivityAt()` 三个 helper。
- [x] `GitHubIntegrationDialogs.kt`：已连接区块展示同步方式标签；POLLING 模式显示 CI 不可用的橙色提示条；「最近同步」时间合并 `lastDeliveryAt ∥ lastPolledAt`。
- [x] `:backend:test` + `:frontend:webApp:compileProductionExecutableKotlinJs` + `silkLint` 全部通过，无回归。
- [ ] 同步 `BOOTSTRAP.md` 与 backend context：说明两种模式、默认行为、CI 事件差异。
- [ ] 手工验收（见 §9）。

## 8. 自动化验证

### 8.1 Backend

`./gradlew :backend:test`

- `GitPollingSchedulerTest`：串行调用、失败隔离、PAT 失效置 ERROR、未配密钥不启动。
- `GitEventStoreTest`（扩展）：新字段旧文件兼容、游标与 ETag 持久化。
- `GitHubClientTest`（扩展）：`If-None-Match` 透传、`304` 不抛异常、ETag 读取、Hook 调用未被回归影响。
- `GitEventParserTest`（扩展）：API 列表形状解析、issues/PR 区分、三种 action 推断、`updated` 支持。
- `GitBindingRouteContractTest`（扩展）：无 `GITHUB_WEBHOOK_BASE_URL` 时绑定成功且 `mode=POLLING`、`hookId` 为空、解绑不触发远端删除。

### 8.2 Web

`./gradlew :frontend:webApp:nodeTest :frontend:webApp:compileProductionExecutableKotlinJs`

覆盖两种模式的面板文案、`lastPolledAt` 回退展示、POLLING 模式 CI 差异提示。

### 8.3 共享合同

不改 shared `Message` 字段，事件卡片继续用 `MessageType.CARD`，三端无需同步改动。

## 9. 手工验收清单

1. 不配 `GITHUB_WEBHOOK_BASE_URL` 启动 → 绑定成功，GitHub 仓库 Settings → Webhooks **没有**新增 hook。
2. 新建 Issue → 一个轮询周期内 Team Channel 出现卡片，内容与链接正确。
3. 关闭该 Issue → 出现 `closed` 卡片，不重复推送 `opened`。
4. 连续多个轮询周期无仓库变更 → 无重复卡片；观察日志确认走的是 304 路径。
5. Issue 卡片「开始开发」→ Issue-to-Workspace 流程与 Webhook 模式表现一致。
6. 服务重启 → 模式、游标、已处理事件均可恢复，不重复消费。
7. 把 PAT 改为失效值 → binding 转 `ERROR`，停止轮询，UI 有可理解的错误。
8. 配上 `GITHUB_WEBHOOK_BASE_URL` 后重新绑定 → 转为 WEBHOOK 模式，注册了 hook，实时推送正常，轮询不再对该 Room 生效。
9. 两个 Room 分别绑不同仓库（同一 PAT）→ 事件互不混淆，配额消耗符合预期。
10. 用 `GET /rate_limit` 观察一小时消耗，确认与仓库活跃度相关而非与轮询次数相关。

## 10. 完成定义

- 未配公网地址的部署可完整使用 Issue/PR 卡片与 Issue-to-Workspace。
- 已有 Webhook 绑定与 store 文件行为不变，`mode` 缺失默认 `WEBHOOK`。
- 轮询正确使用条件请求，空闲仓库不消耗主限额。
- 去重在重启与重复轮询下均不产生重复卡片。
- POLLING 模式的 CI 事件能力差异已在 UI 与文档中明确说明。
- 后端/Web 测试与受影响的 `silkLint` 通过，`.env.example`、`BOOTSTRAP.md`、backend context 与实现一致。
