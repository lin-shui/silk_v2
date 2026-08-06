# Unified Room Navigation 与显式 RoomKind 开发计划

- **文档类型**：执行计划
- **创建日期**：2026-08-04
- **当前状态**：代码与自动化验证完成，待发布环境手工验收
- **建议顺序**：作为 Workflow Room Phase 2.5，在 Phase 3 GitHub 集成前完成
- **关联目标**：[Workflow Room 重新设计](2026-07-24-workflow-room-redesign.md)

## 一、结论摘要

Silk 会话与 Workflow 不再作为两个并列产品入口。前端统一为一个“会话”入口，所有可见会话均以 Room 为一级对象：

- Silk 专属对话固定在会话列表顶部。
- 普通群组与 cc-connect 群组统一归入“聊天群组”。
- Workflow Room 在界面中称为“工作群组”，是普通 Room 的增强类型。
- 工作群组选中后，在 Room 下展示 Team Channel 与 Personal Workspace 二级消息流。
- cc-connect 暂不拥有独立一级分类；连接的 Agent 类型与在线状态只作为聊天群组的附加标识。

Room 类型不能再通过群名是否以 `wf_` 或 `[Silk]` 开头判断。新增显式 `RoomKind`：

```kotlin
@Serializable
enum class RoomKind {
    CHAT,
    WORKFLOW,
    SILK_PRIVATE,
}
```

不采用 `isWorkflow: Boolean`，因为 Silk 专属对话已经具有独立行为，枚举也能避免未来继续堆叠互斥布尔字段。cc-connect 的 `RoomKind` 为 `CHAT`；它是聊天 Room 的集成能力，不是新的 Room 基类。

## 二、当前问题

### 2.1 类型依赖名称约定

当前工作群组由 `/api/workflows` 创建关联 Group，Group 名称为 `wf_<name>_<timestamp>`。Web、Android、Harmony 和部分后端逻辑通过 `name.startsWith("wf_")` 隐藏或标记这些 Group。

主要问题：

- 重命名和展示名称不能成为稳定的类型合同。
- 新客户端必须知道隐含的字符串约定。
- Workflow 元数据、Group 和前端筛选各自维护判断逻辑。
- 名称迁移或用户输入可能造成误分类。
- Silk 专属对话仍以 `[Silk]` 前缀判断，也存在同类问题。

### 2.2 前端存在三套 Room 列表

- `GroupListScene.kt`：登录后的群组列表页。
- `Main.kt` / `ChatScene`：进入 Silk 聊天后的快捷切换侧栏。
- `WorkflowScene.kt`：独立的 Workflow Room 列表。

三者分别处理加载、分组、选择、折叠、宽度、创建和管理操作。Workflow Room 内又有一套 Team Channel / Workspace 导航，导致顶层产品入口和二级消息流分散在不同区域。

### 2.3 Room 与本地 Workspace 生命周期耦合

当前创建 Workflow 会同时：

1. 创建 Group。
2. 创建 Workflow 元数据。
3. 创建默认 PersonalWorkspace。
4. 校验 Bridge、信任目录并执行 `cdSync`。

这使团队 Room 无法在没有本地 Bridge 的情况下先建立，也把 Room 的生命周期绑定到创建者当前设备。目标设计应允许先创建工作群组和 Team Channel，再由每个成员分别创建自己的工作区。

## 三、范围

### 3.1 本计划包含

- Group 持久化显式 `RoomKind`，并同步 API 合同。
- 现有 Room 的幂等迁移与旧客户端兼容。
- 后端所有 Workflow/Silk Private 类型判断改为显式属性。
- 新建统一 Room 发现接口与创建入口。
- Web 移除独立 Workflow 一级导航，建立统一会话侧栏。
- 工作群组选中后展示 Team Channel / Workspace 二级树。
- 工作群组创建与 PersonalWorkspace 创建解耦。
- cc-connect 保持聊天群组归类，保留现有消息路由和连接状态。
- Android、Desktop、Harmony 同步解析 `roomKind`；完整统一 UI 未实现前，按显式类型保持现有可见范围。

### 3.2 本计划不包含

- 修改 Silk AI / `DirectModelAgent` 核心能力。
- 修改 TEAM / WORKSPACE 消息 ACL。
- GitHub/GitLab 集成。
- 将 cc-connect 定义为独立产品类型。
- 普通聊天群组与工作群组之间的转换。
- 一次性删除所有旧 Workflow API 或旧 JSON 数据。
- Android/Harmony 的完整多工作区统一界面；它们先完成合同兼容，完整体验仍按总体 Phase 4 推进。

## 四、目标信息架构

### 4.1 顶层导航

登录后的一级导航调整为：

```text
会话
知识库
音频双工
```

移除独立“工作流”一级入口。内部代码可将 `NavTab.SILK` 改为 `NavTab.CONVERSATIONS`，删除 `NavTab.WORKFLOW`。

### 4.2 统一会话侧栏

默认结构：

```text
会话                         搜索  新建

Silk AI                                      2

全部 | 工作群组 | 聊天群组

Project Alpha                   工作         4
产品讨论群                       群聊         1
Claude Automation               Claude      在线
```

规则：

- Silk AI 固定在顶部，不参与工作/聊天筛选。
- cc-connect 进入“聊天群组”，通过 Claude/Cursor/Codex 等附加标签和连接状态识别。
- 默认列表不再永久显示邀请码；邀请码进入 Room 菜单或成员面板。
- Room 行统一显示选中态、类型、未读数和管理菜单。所有成员可邀请；Owner 可重命名/删除，非 Owner 可退出；Silk 专属会话不提供管理菜单。
- 非 Owner 的工作群组可在次要信息中显示 Owner，Owner 信息不取代最近活动信息。
- 第一版可复用当前 Room 级未读数；消息预览、精确的 per-stream 未读和 ACL-safe 最近活动索引作为后续增强，不阻塞结构合并。

### 4.3 工作群组二级树

桌面端仅展开当前选中的工作群组：

```text
Project Alpha                   工作
  Team Channel
  我的工作区
    feature/login               RUNNING
  Co-pilot
    jhshen / api-refactor       WAITING
  成员工作区
    jhshen (2)
  历史与归档
```

规则：

- Room 与 Workspace 不在同一级平铺。
- Team Channel 始终是第一个子消息流。
- “我的工作区”直接列出活动工作区。
- Co-pilot 保留快捷分组；工作区仍可同时出现在 Owner 分组中。
- 其他成员工作区继续按 Owner 稳定分组。
- 历史共享与已归档默认折叠，避免挤占高频导航。
- 点击工作群组首次进入 Team Channel；之后恢复该用户在该 Room 最后选择且仍有权限的消息流，否则回退 Team Channel。
- 窄屏不保留常驻树，继续使用单一 Team/Workspace 选择器。

## 五、数据与合同设计

### 5.1 RoomKind 的唯一权威来源

在 `Groups` 表新增：

```kotlin
val roomKind = varchar("room_kind", 32).default(RoomKind.CHAT.name)
```

后端 `Group` 与 Web/Android/Desktop 的 Group DTO 增加：

```kotlin
val roomKind: RoomKind = RoomKind.CHAT
```

建议把 `RoomKind` 放在 `frontend/shared/src/commonMain/.../models/`，由 backend、Web、Android、Desktop 共享；Harmony 使用相同字符串值手工同步。

兼容原则：

- 新服务端给旧客户端返回新增字段，旧客户端通过 `ignoreUnknownKeys` 忽略。
- 新客户端连接旧服务端时，缺少字段默认按 `CHAT` 解析。
- 在所有客户端完成显式类型筛选前，不立即批量重命名旧 `wf_` Group。

### 5.2 cc-connect 的归类

- cc-connect Group 持久化为 `RoomKind.CHAT`。
- `CreateGroupRequest.type = "ccconnect"` 暂作为兼容创建参数保留。
- cc-connect token、Agent 类型、连接状态继续来自现有 cc-connect 存储/注册表。
- 新的 Room summary 可提供可选 `integration` 摘要，但筛选时仍属于聊天群组。
- 本计划不改变 `/ccconnect-bridge`、角色路由或触发规则。

### 5.3 Group.name 成为 Room 展示名

目标状态下，`Group.name` 是 Room 的统一展示名称，不再编码 Room 类型。

迁移期间：

- `Workflow.name` 仍保留，供旧 API 和回滚使用。
- 新 Room summary 优先使用 `Group.name`，迁移未完成时回退 `Workflow.name`。
- Workflow 重命名在兼容期双写 Group 与 Workflow 元数据。
- 新工作群组不再生成 `wf_<name>_<timestamp>`，而是使用现有群组名称唯一化规则。
- 所有客户端切换到 `roomKind` 后，再把旧 Workflow Group 名称迁移为对应 `Workflow.name`；名称冲突使用现有 `(1)/(2)` 规则处理。

Workflow 的 `id` 仍可使用 `wf_` 前缀。需要消除的是“通过 Group.name 前缀判断类型”，不是 Workflow ID 格式。

### 5.4 统一 RoomSummary

新增 JWT 保护的发现接口：

```text
GET /api/rooms/visible
```

建议合同：

```kotlin
@Serializable
data class RoomSummaryDto(
    val roomId: String,
    val roomKind: RoomKind,
    val name: String,
    val ownerId: String,
    val ownerDisplayName: String,
    val role: String,
    val workflowId: String? = null,
    val unreadCount: Int = 0,
    val createdAt: String,
    val createdAtEpochMs: Long = 0,
    val updatedAt: Long = 0,
    val lastMessageAt: Long = 0,
    val integration: RoomIntegrationSummaryDto? = null,
)
```

接口规则：

- 只返回调用者为成员的 Room。
- `workflowId` 只用于兼容 Workflow 专项 API；导航主键统一使用 `roomId = groupId`。
- cc-connect `integration` 可包含 Agent 类型和在线状态，但 `roomKind` 仍为 `CHAT`。
- 列表活动时间优先使用 `lastMessageAt`；无消息 Room 使用 `createdAtEpochMs`，因此新建 Room 位于 Silk 专属会话之后、其他旧 Room 之前。
- 当前 Room 的持久化消息通过现有 WebSocket 在 Web 本地立即更新排序；15 秒列表轮询继续负责其他 Room、跨设备活动和未读状态兜底，不额外增加请求频率。
- Room 列表不返回 Workspace 私有目录、session、权限模式等运行时信息。
- 第一版 `updatedAt` 可使用已有安全元数据；禁止为了列表排序向 Observer 暴露 PRIVATE Workspace 活动。后续若实现最近活动索引，应以 TEAM 活动或调用者可见消息为依据。

旧接口 `/groups/*`、`GET /api/workflows/visible` 与 `/api/workflows/by-group/*` 在兼容期继续保留。

### 5.5 统一创建入口

新增：

```text
POST /api/rooms
```

请求至少包含：

```kotlin
data class CreateRoomRequest(
    val name: String,
    val roomKind: RoomKind,
)
```

约束：

- 普通用户只允许创建 `CHAT` 或 `WORKFLOW`。
- `SILK_PRIVATE` 只能由 `/api/silk-private-chat` 系统入口创建。
- 创建 `CHAT` 只创建 Group 和群主成员关系。
- 创建 `WORKFLOW` 只创建 Group、Workflow 入口元数据和 Team Channel，不要求 Bridge，不创建默认 Workspace。
- PersonalWorkspace 继续通过 `POST /api/rooms/{roomId}/workspaces` 显式创建；此时才校验 Bridge、信任目录、Agent 和权限模式。
- 旧 `/api/workflows` 在兼容期维持原子创建默认 Workspace 的旧语义，供尚未迁移的客户端使用；Web 切换到新接口后再评估弃用时间。

RoomKind 初期不可变。普通群组升级为工作群组可以未来设计为单向显式操作；工作群组降级涉及工作区、历史和集成清理，不在本计划中隐式实现。

## 六、迁移与安全策略

### 6.1 分阶段迁移顺序

必须按以下顺序发布，不能先删除名称前缀：

1. 数据库添加 `room_kind`，所有 DTO 以默认值兼容。
2. 幂等回填现有 RoomKind，但保留旧 Group 名称。
3. 后端运行时改用 `roomKind`，兼容期对类型/元数据不一致执行 fail-closed 合并判断。
4. Web、Android、Desktop、Harmony 改用 `roomKind` 筛选；未实现统一 UI 的端继续排除 `WORKFLOW`，但不再检查名称。
5. Web 上线统一 Room 导航和新创建接口。
6. 所有受支持客户端完成迁移后，清理旧 `wf_` Group 展示名和名称判断。
7. 最后删除旧前端列表与无用兼容分支；旧 API 的删除另行决策。

### 6.2 幂等回填规则

回填优先级：

1. `workflow_store.json` 中引用的 `groupId` 标记为 `WORKFLOW`。
2. `workspace_store.json` 中已有 Workspace 的 `roomId` 可作为 WORKFLOW 的交叉校验和修复来源。
3. 同时满足“用户 + Silk Agent 两名成员”且使用旧保留命名的 Room 标记为 `SILK_PRIVATE`。
4. 其他 Room 保持 `CHAT`，包括 cc-connect。

`wf_` 名称只能作为迁移诊断信号，不能单独决定 RoomKind。发现“名称像 Workflow，但没有 Workflow/Workspace 元数据”的孤儿 Group 时记录结构化警告，保持 `CHAT` 或进入人工修复清单，避免把用户普通群组误分类。

迁移每次启动可安全重复执行，不删除 `workflow_store.json` 或 `workspace_store.json`。

### 6.3 fail-closed 过渡

Workflow Room 当前有更严格的 JWT、成员和 Workspace ACL。兼容期如果出现以下任一条件，应按 Workflow Room 保护：

- `Group.roomKind == WORKFLOW`
- `WorkflowManager.getWorkflowByGroupId(groupId) != null`
- `WorkspaceManager` 存在属于该 Room 的 Workspace

同时记录不一致日志。迁移稳定并有测试覆盖后，`Group.roomKind` 成为唯一分类权威，Workflow/Workspace 元数据仅承载专项数据。

## 七、实施步骤

### Stage 0：锁定现有行为与迁移样本

- [x] 为 Group JSON 合同增加 `roomKind` 默认值兼容测试。
- [x] 覆盖普通群组、cc-connect、Silk 专属对话、Workflow Room 四类样本。
- [x] 覆盖旧数据库新增列和旧 Workflow/Workspace store 回填。
- [x] 覆盖 RoomKind 与 Workflow 元数据不一致时仍执行严格鉴权。
- [x] 以第 9.2 节记录 Web Room/Workflow 导航的关键手工回归基线。

### Stage 1：显式 RoomKind 基础

- [x] 在共享合同中增加 `RoomKind`。
- [x] `Groups` 表和 backend `Group` 增加 `roomKind`。
- [x] `GroupRepository.createGroup()` 接受默认 `CHAT` 的显式类型参数。
- [x] 收敛 Group 数据行到 DTO 的重复映射，避免遗漏 `roomKind`。
- [x] 新增幂等 RoomKind 回填器，并在数据库初始化后、路由服务前执行。
- [x] `/api/silk-private-chat` 创建和查找改用 `SILK_PRIVATE`。
- [x] Workflow 创建 Group 时写入 `WORKFLOW`。
- [x] cc-connect 创建保持 `CHAT`。

### Stage 2：替换运行时字符串判断

- [x] WebSocket 的 Silk 专属会话触发、跨群访问和 Todo 刷新改用 `SILK_PRIVATE`。
- [x] Workflow WebSocket JWT/成员门禁改用显式 RoomKind，并保留过渡期 fail-closed 检查。
- [x] Workspace route 的 `isWorkflowRoom`、PDF 导出限制、成员管理和轮询路径改用 RoomKind。
- [x] 邀请/添加成员对 Silk 专属对话的限制改用 `SILK_PRIVATE`。
- [x] `UserWorkspaceManager` 的展示类型和名称清理改用 RoomKind/Workflow 元数据。
- [x] KB 群组选择、消息转发目标和跨端群组筛选停止检查 `wf_`。

### Stage 3：统一发现与创建 API

- [x] 实现 `GET /api/rooms/visible` 与 `RoomSummaryDto`。
- [x] 聚合当前用户角色、Room 级未读和可选 cc-connect 摘要，避免 Web 对每个群组逐个探测类型。
- [x] 实现 `POST /api/rooms`，限制调用者可创建的 RoomKind。
- [x] 新工作群组创建不要求 Bridge，也不创建默认 Workspace。
- [x] 统一 Room 重命名，使 Group.name 为主，Workflow.name 在兼容期双写。
- [x] 保留并标记旧 Workflow API 为兼容入口，禁止在同一前端流程同时调用新旧创建接口。

### Stage 4：Web 统一会话壳层

- [x] 将一级导航收敛为 `CONVERSATIONS / KNOWLEDGE_BASE / AUDIO_DUPLEX`。
- [x] 新建统一 Conversation 壳层、`RoomListPanel` 与 Room detail host。
- [x] 合并 `GroupListScene`、`ChatScene` 侧栏和 `WorkflowScene` 外层 Room 列表的数据与交互。
- [x] 普通聊天主体抽为 `ChatRoomView`；Workflow 主体抽为 `WorkflowRoomView`。
- [x] `RoomNavigationTarget` 以 roomId 为主键，替代 Chat/Workflow 两套导航目标。
- [x] 统一搜索、筛选、选中态和未读；侧栏 `+` 统一承载创建群组/邀请码加入，Room 行菜单按角色承载邀请、重命名、退出和删除。
- [x] Silk AI 固定；cc-connect 放入聊天群组筛选并保留连接标识。
- [x] 创建弹窗使用“聊天群组 / 工作群组”分段选择；cc-connect 作为聊天群组的集成选项保留。

### Stage 5：工作群组二级导航

- [x] 桌面端在当前工作群组下展开 Team Channel 与 Workspace 树。
- [x] 复用现有 Owner/Co-pilot/Observer、历史共享和归档分组逻辑。
- [x] 折叠状态按 Room/分组稳定保存，不用当前选中值推导。
- [x] 保存每个工作群组最后选中的消息流，并在权限撤销后回退 Team Channel。
- [x] 窄屏继续使用去重后的单一选择器，不强塞常驻嵌套侧栏。
- [x] 桌面树稳定后删除重复的横向 Workspace 导航；迁移期间只能保留一个主导航入口，避免双选中状态。

### Stage 6：跨端兼容与遗留清理

- [x] Web/Android/Desktop Group DTO 使用共享 RoomKind。
- [x] Harmony Group 合同同步 `roomKind` 字符串值。
- [x] Android/Harmony 完整统一 UI 前，用 `roomKind` 保持现有 Workflow 可见策略。
- [x] 所有端移除 `name.startsWith("wf_")` 和运行时 `[Silk]` 类型判断。
- [x] 迁移旧 Workflow Group 展示名，保留 Workflow ID 前缀不变。
- [x] 删除 Web 三套 Room 列表中的废弃实现和各自独立的宽度/折叠状态。
- [x] 更新 `ARCHITECTURE.md`、backend/frontend context 和 API 文档。

## 八、主要受影响代码面

### 后端

- `backend/src/main/kotlin/com/silk/backend/database/Tables.kt`
- `backend/src/main/kotlin/com/silk/backend/database/Models.kt`
- `backend/src/main/kotlin/com/silk/backend/database/GroupRepository.kt`
- `backend/src/main/kotlin/com/silk/backend/database/DatabaseFactory.kt`
- `backend/src/main/kotlin/com/silk/backend/auth/GroupService.kt`
- `backend/src/main/kotlin/com/silk/backend/Routing.kt`
- `backend/src/main/kotlin/com/silk/backend/WebSocketConfig.kt`
- `backend/src/main/kotlin/com/silk/backend/routes/WorkflowRoomRoutes.kt`
- `backend/src/main/kotlin/com/silk/backend/routes/WorkspaceRoutes.kt`
- `backend/src/main/kotlin/com/silk/backend/workflow/WorkflowManager.kt`
- `backend/src/main/kotlin/com/silk/backend/workspace/WorkspaceManager.kt`
- `backend/src/main/kotlin/com/silk/backend/UserWorkspaceManager.kt`

### 共享合同与前端

- `frontend/shared/src/commonMain/kotlin/com/silk/shared/models/`
- `frontend/webApp/src/main/kotlin/com/silk/web/AppState.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/ConversationScene.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/WorkflowScene.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/ApiClient.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/KnowledgeBaseCaptureDialog.kt`
- `frontend/webApp/src/main/kotlin/com/silk/web/KnowledgeBaseScene.kt`
- `frontend/androidApp/src/main/kotlin/com/silk/android/`
- `frontend/desktopApp/src/main/kotlin/com/silk/desktop/`
- `frontend/harmonyApp/entry/src/main/ets/`

## 九、验证计划

### 9.1 自动化验证

按风险从窄到宽执行：

```bash
./gradlew :backend:test
./gradlew :frontend:webApp:nodeTest
./gradlew :frontend:webApp:compileProductionExecutableKotlinJs
./gradlew :frontend:shared:desktopTest
./gradlew :frontend:androidApp:testDebugUnitTest
./gradlew :frontend:androidApp:compileDebugKotlin
./gradlew :frontend:desktopApp:test
./gradlew :frontend:desktopApp:compileKotlin
./gradlew silkLint
git diff --check
```

新增或加强的测试重点：

- RoomKind 数据库默认值和序列化默认值。
- 旧 Workflow/Workspace/Silk Private 数据的幂等回填。
- cc-connect 为 CHAT，但仍生成 token 并保持现有转发行为。
- Workflow Room 即使元数据暂时不一致也不能降级为匿名普通 Room。
- 新工作群组无 Bridge 可创建；创建 Workspace 时仍严格校验 Bridge 和 TrustedDir。
- `/api/rooms/visible` 只能返回调用者所属 Room，不泄露 Workspace runtime 元数据。
- Team Channel 与 Silk 专属对话继续复用同一 DirectModelAgent，触发规则不变；普通聊天与 Team Channel composer 共用 `@Silk` 快捷按钮，Workspace 不显示该按钮。
- Android/Desktop 对新增字段兼容；Harmony 类型值一致。

### 9.2 手工验收

1. 现有普通群组、旧 `wf_` 工作群组、Silk AI 和 cc-connect 升级后全部可见且分类正确。
2. 会话一级入口中不再出现独立“工作流”标签。
3. `全部 / 工作群组 / 聊天群组` 筛选正确；cc-connect 只出现在聊天群组。
4. 新建聊天群组后直接进入普通聊天界面。
5. Bridge 离线时仍可新建工作群组并进入 Team Channel。
6. 新建工作区时才要求 Bridge、目录信任、Agent 和权限模式。
7. 工作群组桌面树中的 Team、我的工作区、Co-pilot、成员工作区、历史和归档均可切换。
8. 切换 Room 后恢复最后合法消息流；权限撤销后安全回退 Team Channel。
9. Silk 专属对话继续自动回复；普通/工作群组只有前置 `@Silk` 才回复。
10. cc-connect 连接、触发、在线状态和 token 管理无回归。
11. 双账号验证 Workflow 成员、PRIVATE/SHARED、Observer/Co-pilot ACL 无回归。
12. 窄屏下 Room 选择与单一 Workspace 选择器不重叠、不截断。

## 十、风险与缓解

### 10.1 旧客户端把无前缀工作群组当普通群聊

**缓解**：先添加 RoomKind 并升级所有客户端筛选，再停止创建/迁移 `wf_` 名称；旧 API 和旧名称保留兼容窗口。

### 10.2 RoomKind 与 Workflow JSON 元数据不一致导致权限降级

**缓解**：过渡期采用 fail-closed 合并判断；迁移器输出不一致日志；增加 WebSocket 和 Workspace route 合同测试。

### 10.3 统一列表一次重写过大

**缓解**：先完成显式类型和统一 API，再迁移 Web 壳层；聊天主体和 Workflow 主体先保持现有实现，只移除各自外层列表。

### 10.4 工作区树造成侧栏过长

**缓解**：只展开当前工作群组；成员、历史和归档默认折叠；窄屏保持单一选择器。

### 10.5 最近活动暴露 PRIVATE Workspace 元数据

**缓解**：第一版只使用现有安全 Room 元数据和 Room 级未读；未来活动索引必须按 TEAM 或调用者可见消息计算，不以私密工作区全局时间戳排序。

## 十一、完成标准

- [x] Group/Room 具有持久化、跨端兼容的显式 RoomKind。
- [x] 后端和前端不再通过 Group.name 的 `wf_` / `[Silk]` 前缀决定运行时类型。
- [x] cc-connect 明确归属于 CHAT，不出现独立一级分类。
- [x] Web 只有一个会话一级入口和一套运行中的 Room 列表实现。
- [x] 普通群组与工作群组使用各自内部视图，但共享统一 Room 导航和创建入口。
- [x] 工作群组可在无 Bridge 状态创建，Workspace 创建仍保持严格设备权限边界。
- [x] 现有 Workflow/Workspace/聊天历史和成员关系无数据丢失。
- [x] 自动化验证通过。
- [ ] 双账号核心手工验收通过。
- [x] `ARCHITECTURE.md`、相关 `docs/context/**` 与实际合同同步。

## 十二、实施状态（2026-08-06）

代码实现已完成，旧 Web Room/Workflow 外层壳层已清理。Room 行管理菜单已补齐：统一 Room API 使用 JWT 身份约束 Owner/成员动作，工作群组退出会收回 Workspace 共享权限，删除会同步清理 Workflow/Workspace、cc-connect 与在线会话状态。

自动化验证包含 `backend:test`、Web `nodeTest`/production compile、shared desktop commonTest、Android unit test/compile、Desktop test/compile、`silkLint` 和 `git diff --check`。真实 Bridge/双账号浏览器环境下的第 9.2 节手工回归仍需在发布验收环境执行。
