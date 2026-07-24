# Workflow Room 重新设计

**文档类型**：产品设计文档 + 技术实现参考  
**创建日期**：2026-07-24  
**状态**：设计完成，待实现  
**作者**：产品 + 工程对齐讨论产出

---

> **阅读指引**：第 1-8 节为产品设计文档，聚焦"做什么"和"为什么"；第 9-10 节为技术实现参考，聚焦"怎么做"，供代码开发直接使用。

---

## 目录

1. [设计背景与目标](#一设计背景与目标)
2. [核心概念体系](#二核心概念体系)
3. [用户场景](#三用户场景)
4. [角色与权限模型](#四角色与权限模型)
5. [个人工作区设计](#五个人工作区设计)
6. [Team Channel 设计](#六team-channel-设计)
7. [GitHub 集成设计](#七github-集成设计)
8. [设计决策记录 ADR](#八设计决策记录-adr)
9. [技术实现参考](#九技术实现参考)
10. [实现分阶段计划](#十实现分阶段计划)

---

## 一、设计背景与目标

### 1.1 当前问题

当前 Silk 存在两个并列的会话入口：**Silk 会话**（多人聊天 + AI）和**工作流**（个人工作空间 + AI 智能体）。两者定位模糊、边界不清：

- 用户需要维护两套心智模型，认知负担高
- 工作流被实现为独立入口，而非聊天会话的增强，造成割裂感
- 工作流仅支持单用户单设备，无法支撑真实的多人团队开发协作场景
- 两者底层数据结构不统一，限制了功能扩展

### 1.2 设计目标

1. **统一心智模型**：所有会话空间的基础概念统一为 Room
2. **工作流作为增强**：工作流是 Room 的派生场景，而非独立产品
3. **支持多人多设备协作**：每个开发者带自己的设备和 AI 智能体进入共享 Room
4. **隔离与共享并存**：个人开发上下文相互隔离，团队信息选择性共享
5. **GitHub 作为协作桥梁**：打通代码仓库，实现个人开发到团队协作的完整闭环

---

## 二、核心概念体系

### 2.1 Room（基类）

**定义**：Room 是多个"个体"进行信息交换的空间。

- **个体**可以是：人类用户、AI 智能体
- **职责**：消息路由、历史记录维护、成员管理
- **所有会话**（聊天、工作流）都是 Room 的实例

### 2.2 Workflow Room（工作场景增强）

**定义**：Workflow Room 是 Room 的子类，在基础聊天能力之上叠加"代码开发工作"场景所需的能力。

与普通 Room 的核心差异：

| 能力 | 普通 Room | Workflow Room |
|------|----------|---------------|
| 消息聊天 | ✅ | ✅ |
| AI 对话（平台 AI）| ✅ | ✅（Team Channel 层）|
| AI 执行代码操作 | ❌ | ✅（个人工作区层）|
| 文件系统访问 | ❌ | ✅（需信任目录授权）|
| GitHub 集成 | ❌ | ✅ |
| 多工作区并行 | ❌ | ✅ |

**关系**：`Workflow Room = Room + 代码工作场景绑定`，是增强模式，不是独立产品。

### 2.3 Workflow Room 的双层结构

Workflow Room 内部由两个层次构成：

```
Workflow Room
  ├── 📢 Team Channel（共享层）
  │     ├── 面向所有成员
  │     ├── GitHub 事件 + AI 摘要
  │     ├── 成员间人工讨论
  │     └── Silk 平台 AI（DirectModelAgent，注入 GitHub 上下文）
  │
  └── 👤 个人工作区 × N（执行层，每位成员各自持有）
        ├── 绑定个人设备（ACP bridge）
        ├── 绑定个人 AI 智能体（CC / Codex）
        └── 可设为共享（团队可见）或私密（仅本人可见）
```

---

## 三、用户场景

### 3.1 单人工作场景

**描述**：一名开发者在 Workflow Room 里独自用 AI 智能体开发代码。

**行为**：
- Team Channel 自动进入"个人模式"，轻量展示（主要显示 GitHub 事件）
- 开发者拥有一个或多个个人工作区，各自绑定本地目录和 AI 会话
- AI 智能体（CC/Codex）在工作区内执行代码、修改文件、运行调试
- UI 不强制展示 Team Channel 层次感，体验接近当前工作流

**与当前行为的差异**：底层模型统一为 Workflow Room，但单人时 UI 退化简洁，不暴露多人层级。

### 3.2 多人团队协作场景

**描述**：多名开发者同时在同一个 Workflow Room 里并行开发同一代码仓库的不同功能。

**典型流程**：
1. 团队成员各自连接自己的本地设备（启动各自的 ACP bridge）
2. 每人在 Room 内创建自己的工作区，绑定本地目录（对应自己的 git branch）
3. 各自在工作区里与自己的 AI 智能体交互，互不干扰
4. GitHub 事件（PR 开启、CI 状态、Issue 变动）自动出现在 Team Channel
5. Team Channel 的 AI 自动生成事件摘要，团队在此讨论、决策、分配任务
6. 一个人的工作区完成任务 → 提交 PR → 自动推送里程碑摘要到 Team Channel
7. 其他成员在 Team Channel 看到 PR，各自在自己工作区里让 AI 协助 review

**并行开发时的隔离保证**：
- Alice 和 Bob 的 AI 智能体各自运行在各自的机器上，执行环境完全隔离
- 消息路由按 `(userId, workspaceId)` 隔离，不会跨工作区混入
- Team Channel 只显示里程碑级别的摘要，不显示工作过程的细节流

### 3.3 跨工作区上下文同步场景

**描述**：Bob 的模块依赖 Alice 正在修改的代码，Bob 需要了解 Alice 的最新进展。

**行为（基于工作区可见性）**：
- Alice 的工作区设为"共享"
- Bob 可直接切换到 Alice 的工作区 Tab 旁观
- Bob 也可以问自己的 AI："Alice 最近在改什么？有没有影响到我的 auth 模块？"
- Bob 的 AI 读取 Alice 共享工作区的消息历史，分析后给出上下文感知的回答
- 这是 Silk 独有的 **AI 中介上下文同步**能力

### 3.4 GitHub Issue 驱动开发场景

**描述**：从一个 GitHub Issue 直接发起开发工作区，零摩擦进入 AI 辅助开发。

**流程**：
1. GitHub Issue 出现在 Team Channel
2. 开发者点击 Issue 卡片上的"开始开发"按钮
3. 系统自动创建一个新个人工作区，预填：工作区名称（`#42: fix login bug`）、初始 prompt（包含 Issue 完整内容）
4. AI 智能体自动启动，带有完整 Issue 上下文，直接进入开发节奏

---

## 四、角色与权限模型

### 4.1 三种角色

| 角色 | 对自身工作区 | 对他人共享工作区 | 对 Team Channel |
|------|------------|----------------|----------------|
| **Owner** | 读 + 指挥 AI | 只读（旁观）| 读 + 写 |
| **Co-pilot** | — | 读 + 指挥该工作区的 AI（需 Owner 授权）| 读 + 写 |
| **Observer** | — | 只读（旁观）| 读 + 写 |

- **Owner**：工作区的创建者，是该工作区绑定的本地设备的所有者
- **Co-pilot**：由 Owner 针对**特定工作区**显式授权，Owner 可随时撤销；授权时 UI 需明确提示"你正在授权 X 可以操作你设备上的代码"
- **Observer**：Room 内其他所有成员的默认角色，对他人共享工作区只读

### 4.2 权限边界说明

- **指挥 AI 智能体 = 对该设备的代码执行权限**：授权 Co-pilot 等同于给予对方在你机器上执行代码的能力，这是高权限操作
- **私密工作区对所有人不可见**：Observer 无法旁观，Co-pilot 也不能访问私密工作区（Co-pilot 授权是针对特定共享工作区的）
- **Team Channel 对所有成员对称开放**：任何人都可以读写 Team Channel，无特殊限制

### 4.3 第四角色（Guest）

暂不实现。Guest 的定位是"不可见 AI 交互、只看 Owner 手动分享内容的外部观察者"，适用于客户可见性、跨团队协作等场景。根据需求情况在后续版本加入。

---

## 五、个人工作区设计

### 5.1 工作区定义

**个人工作区（Personal Workspace）**是一个有状态的 AI 编码会话，包含：

| 属性 | 说明 |
|------|------|
| `workspaceId` | 唯一 ID |
| `name` | 用户定义的名称（推荐对应 branch 或 Issue 编号）|
| `ownerId` | 工作区所有者（userId）|
| `workingDir` | 绑定的本地目录（执行环境根目录）|
| `agentType` | 使用的 AI 智能体类型（claude-code / codex）|
| `cliSessionId` | AI 会话 ID，用于跨对话续接 |
| `visibility` | `SHARED`（团队可见）或 `PRIVATE`（仅本人）|
| `linkedGithubRef` | 关联的 GitHub branch 或 Issue URL（可选）|

### 5.2 可见性模型

工作区采用**二值可见性**：

- **SHARED（共享）**：在 Team Channel 的成员工作区列表中可见，其他 Observer 可旁观，其他成员的 AI 可读取该工作区消息历史作为上下文
- **PRIVATE（私密）**：仅 Owner 可见，不出现在团队视图中

**关键区分**：私密控制的是**过程可见性**，不是**结果可见性**：
- 私密工作区里完成的 commit、PR 依然会通过 GitHub 推送到 Team Channel
- Owner 可以随时手动 `/share` 私密工作区的某条 AI 输出到 Team Channel

可见性可随时切换（`SHARED ↔ PRIVATE`）。

### 5.3 数量与命名

- **每人每个 Workflow Room 可拥有任意数量的工作区**，无上限
- 支持同时拥有多个共享工作区（不限制为仅一个共享）
- 推荐命名规范：
  - 对应 GitHub Issue：`#42: fix login timeout`
  - 对应 git branch：`feature/auth-refactor`
  - 自由命名：`支付模块重构`

### 5.4 团队视图中的展示

当多人有多个工作区时，团队 Tab 按人分组，避免平铺混乱：

```
Team Channel | Alice (3) ▾ | Bob (1) ▾ | Carol (2) ▾
```

展开 Alice 的分组后显示她的3个共享工作区。有 AI 活动时在角标显示状态（🟢 活跃 / 🟡 等待 / ⚪ 空闲）。

### 5.5 AI 跨工作区上下文读取

当 Bob 的 AI 需要了解 Alice 工作区的进展时，Bob 可以：

1. **手动旁观**：切换到 Alice 的工作区 Tab，直接查看消息历史
2. **AI 代为查询**：在自己工作区问 AI "Alice 最近改了什么？"——AI 读取 Alice 共享工作区的消息历史，给出综合回答
3. **自动冲突检测（未来扩展）**：AI 检测到两人工作区存在同一文件修改时，主动提醒

**前提条件**：Alice 的工作区必须是 `SHARED` 可见性。私密工作区不参与跨工作区上下文读取。

---

## 六、Team Channel 设计

### 6.1 定位

Team Channel 是 Workflow Room 的**团队协调层**，专注于：
- 团队成员之间的人工讨论
- GitHub 事件的汇聚与 AI 摘要
- 开发里程碑的自动通报
- Silk 平台 AI 提供的团队级信息查询

Team Channel **不是**个人 AI 编码的交互场所——那是个人工作区的职责。

### 6.2 AI 能力

Team Channel 中的 AI 是 Silk 平台的 **DirectModelAgent**，与普通 Room 中的 AI 类型相同，但被注入了额外上下文：

- 该 Workflow Room 关联的 GitHub 仓库的近期事件（PR、Issue、CI 状态）
- 各共享工作区的里程碑摘要（非全量消息）
- Room 成员信息

**能做**：回答团队进展问题、汇总 PR/Issue 内容、分析 CI 失败原因  
**不能做**：访问文件系统、执行代码（这是个人工作区 AI 的职责）

### 6.3 内容来源与推送规则

| 内容类型 | 来源 | 推送到 Team Channel？ | 说明 |
|---------|------|---------------------|------|
| GitHub PR 开启/合并/关闭 | Webhook | ✅ 自动推 + AI 摘要 | 包含标题、作者、关联 Issue |
| GitHub Issue 创建/关闭 | Webhook | ✅ 自动推 | 包含标题、标签 |
| GitHub CI 状态（通过/失败）| Webhook | ✅ 自动推 | 失败时 AI 自动分析原因 |
| 个人工作区：commit 提交 | ACP 事件 | ✅ 自动推摘要 | 简短里程碑通报 |
| 个人工作区：AI 读文件/跑测试 | ACP 事件 | ❌ 不推 | 过程细节，避免噪音 |
| 用户手动 `/share` | 用户操作 | ✅ 推 | 用户主动判断值得共享 |
| 成员间人工对话 | 用户操作 | ✅（本身就在此层）| — |

**核心原则：推送粒度是"里程碑"而非"操作日志"。**

### 6.4 单人使用时的行为

当 Workflow Room 中只有一名成员连接了设备时：

- Team Channel **始终存在**，不隐藏（行为一致性，避免多人加入后 UI 突变）
- 进入"个人模式"：仅显示 GitHub 事件通知，AI 可正常对话
- 无成员工作区 Tab（仅有一人，无需分组）
- 第二个成员连接后自然激活多人协作功能，无需特殊操作

---

## 七、GitHub 集成设计

### 7.1 仓库绑定

每个 Workflow Room 可绑定一个 GitHub 或 GitLab 仓库：

- **认证方式**：优先支持 Personal Access Token（PAT），OAuth 在后续版本实现
- **绑定粒度**：Room 级别（一个 Room 对应一个仓库）
- **绑定操作**：在 Room 设置中填入仓库地址和 PAT，系统自动注册 Webhook

### 7.2 Webhook 事件处理

后端新增 `POST /api/git/webhook/{roomId}` 端点：
1. 验证 GitHub HMAC-SHA256 签名
2. 解析事件类型（push、pull_request、issues、check_run 等）
3. 格式化为 Silk 消息，AI 生成事件摘要
4. 广播到该 Room 的 Team Channel（scope = TEAM）

### 7.3 从 Issue 一键开启工作区

Team Channel 中的 Issue 卡片提供"开始开发"入口：

1. 用户点击 Issue 卡片上的"开始开发"按钮
2. 系统调用 `POST /api/git/issue-to-workspace`
3. 后端拉取 Issue 完整内容（标题、描述、标签、关联 PR、讨论历史）
4. 自动创建新个人工作区，预填：
   - 名称：`#42: fix login timeout`
   - 初始 prompt：包含 Issue 完整上下文
   - `linkedGithubRef`：Issue URL
5. AI 智能体以完整 Issue 上下文启动，无需用户手动复制粘贴

### 7.4 双向联动（后续版本）

- 工作区内 AI 创建 PR → 自动推送 PR 卡片到 Team Channel
- `/pr-review #123` 命令 → AI 拉取 PR diff，在工作区进行代码审查
- AI 完成 Issue 实现 → 可选自动在 GitHub Issue 下发评论

### 7.5 支持平台路线图

- **Phase 1**：GitHub + PAT 认证
- **Phase 2**：GitLab 适配（API 结构相似，边际成本低）
- **Phase 3**：GitHub OAuth 替换 PAT

---

## 八、设计决策记录 ADR

### ADR-1：工作流是 Room 的子类，而非独立产品

**决策**：所有会话统一为 Room 基类，Workflow Room 是派生子类。  
**理由**：消除两套心智模型；工作流本质是"聊天 + 代码执行"，继承比并列更准确。  
**权衡**：现有"工作流"入口和数据结构需要迁移重构，有一定开发成本。

### ADR-2：执行环境由用户本地设备提供

**决策**：AI 代码执行在用户本地机器上进行（ACP bridge），Silk 后端不提供执行环境。  
**理由**：用户代码、工具链、运行时都在本地；数据不离开用户机器；Silk 容器无法替代真实本地环境。  
**权衡**：用户须保持 ACP bridge 在线；暂不支持云端执行（未来可扩展）。

### ADR-3：指挥 AI 等同于设备代码执行权限

**决策**：将"指挥 AI 智能体"视为高权限操作，等同于对该设备代码目录的执行权限。  
**理由**：能执行代码的 AI 可通过代码间接做任何文件操作，沙箱无法根本消除。  
**影响**：Co-pilot 授权必须有明确的用户同意流程和 UI 风险提示。

### ADR-4：Co-pilot 授权粒度为单个工作区

**决策**：Co-pilot 针对特定工作区授权，而非 Room 级或用户级。  
**理由**：开发者可能信任某人访问 feature-A 工作区，但不信任其访问 payment 工作区；细粒度降低过度授权风险。

### ADR-5：工作区可见性采用二值模型（SHARED / PRIVATE）

**决策**：工作区只有共享和私密两种状态，不支持按成员设置可见性。  
**理由**：同一代码仓的团队成员通常可以共享代码工作内容；按人设置权限矩阵复杂度高于价值。  
**未来扩展**：如有按人授权旁观的需求，可在此基础上加入白名单机制。

### ADR-6：每人每 Room 工作区数量不设上限

**决策**：一个用户在同一 Workflow Room 中可拥有任意数量的工作区。  
**理由**：同时开发多个独立功能（对应不同 branch/Issue）是真实需求；强制单工作区降低工具实用性。  
**UI 处理**：团队视图按人分组展示，不平铺。

### ADR-7：Team Channel 的 AI 使用 DirectModelAgent

**决策**：Team Channel 的 AI 与普通 Room 一致，均为 DirectModelAgent，不引入新 AI 类型。  
**理由**：与"Workflow Room 是普通 Room 增强"的原则一致；扩展成本低（注入 GitHub 上下文即可）。  
**差异**：Team Channel 的 DirectModelAgent 实例被注入 GitHub 事件上下文，这是与普通 Room 的唯一配置差异。

---

## 九、技术实现参考

### 9.1 现有架构评估

#### 可复用的有利基础

| 组件 | 现状 | 对新设计的价值 |
|------|------|--------------|
| `AgentRuntime` | 按 `(userId, groupId)` 隔离 | 天然支持同 Room 内多用户独立 agent |
| `AcpRegistry` | 按 `userId` 注册 ACP 连接 | 多用户各自连接设备的基础已就绪 |
| `DirectModelAgent` | 可配置 context 注入 | 可扩展注入 GitHub 上下文 |
| `ChatServer` | 消息广播基础设施 | 扩展支持 scope 过滤广播 |
| ACP `session/load` | 按 cliSessionId 续接会话 | 多工作区切换的技术基础 |

#### 需要改造的部分

| 组件 | 当前问题 | 需要的改动 |
|------|---------|-----------|
| `WorkflowManager` | 按 `groupId` 存储，无用户维度 | 重构为 `WorkspaceManager`，按 `workspaceId` 存储 |
| `Message` 数据类 | 无 scope / workspaceId 字段 | 新增 `scope: MessageScope`、`workspaceId: String?` |
| `ChatServer` 广播逻辑 | 全量广播给 Room 所有人 | 按 scope 路由（TEAM → 全员；WORKSPACE → 工作区成员）|
| 前端消息渲染 | 单一扁平消息流 | 增加 Tab 分组视图 |

### 9.2 新增数据模型

#### PersonalWorkspace

```kotlin
@Serializable
data class PersonalWorkspace(
    val workspaceId: String,
    val roomId: String,
    val ownerId: String,
    val name: String,
    val workingDir: String,
    val agentType: AgentType,
    val cliSessionId: String? = null,
    val visibility: WorkspaceVisibility = WorkspaceVisibility.PRIVATE,
    val linkedGithubRef: String? = null,
    val copilots: List<String> = emptyList(), // 被授权的 userId
    val createdAt: Long = System.currentTimeMillis()
)

enum class WorkspaceVisibility { SHARED, PRIVATE }
```

#### Message 扩展

```kotlin
// 在共享层 Message.kt 新增
enum class MessageScope { TEAM, WORKSPACE }

// Message 新增字段（有默认值，向后兼容）
val scope: MessageScope = MessageScope.TEAM,
val workspaceId: String? = null
```

#### RoomGitBinding

```kotlin
@Serializable
data class RoomGitBinding(
    val roomId: String,
    val provider: GitProvider,
    val owner: String,
    val repo: String,
    val tokenEncrypted: String,  // AES 加密存储
    val webhookSecret: String,
    val createdAt: Long
)

enum class GitProvider { GITHUB, GITLAB }
```

### 9.3 广播路由扩展

```kotlin
// ChatServer.broadcast() 扩展逻辑（伪代码）
fun broadcast(message: Message) {
    val targets = when (message.scope) {
        MessageScope.TEAM -> getAllRoomSessions()
        MessageScope.WORKSPACE -> {
            val ws = workspaceManager.getWorkspace(message.workspaceId!!)
            val eligible = setOf(ws.ownerId) + ws.copilots + getObservers(ws)
            getRoomSessions().filter { it.userId in eligible }
        }
    }
    targets.forEach { it.send(message) }
}
```

### 9.4 新增后端模块结构

```
backend/src/main/kotlin/com/silk/backend/
  ├── workspace/
  │   ├── PersonalWorkspace.kt
  │   ├── WorkspaceManager.kt       # CRUD + 可见性 + Co-pilot 管理
  │   └── WorkspaceStore.kt         # JSON 文件持久化
  └── git/
      ├── GitHubClient.kt           # GitHub REST API
      ├── GitLabClient.kt           # GitLab REST API（同接口）
      ├── GitWebhookHandler.kt      # Webhook 验签 + 事件解析
      ├── GitEventBroadcaster.kt    # Git 事件 → Team Channel 消息
      ├── GitContextBuilder.kt      # Issue/PR → workspace 初始 prompt
      └── RoomGitBindingStore.kt
```

### 9.5 新增 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rooms/{roomId}/workspaces` | 创建工作区 |
| GET | `/api/rooms/{roomId}/workspaces` | 列出（按可见性过滤）|
| PATCH | `/api/rooms/{roomId}/workspaces/{wsId}` | 更新名称/可见性 |
| DELETE | `/api/rooms/{roomId}/workspaces/{wsId}` | 删除 |
| POST | `/api/rooms/{roomId}/workspaces/{wsId}/copilots` | 授权 Co-pilot |
| DELETE | `/api/rooms/{roomId}/workspaces/{wsId}/copilots/{userId}` | 撤销 Co-pilot |
| POST | `/api/rooms/{roomId}/git/binding` | 绑定 GitHub 仓库 |
| DELETE | `/api/rooms/{roomId}/git/binding` | 解绑 |
| POST | `/api/git/webhook/{roomId}` | Webhook 接收（HMAC 鉴权）|
| POST | `/api/git/issue-to-workspace` | Issue → 新建工作区 |

### 9.6 数据迁移

现有 `workflow_store.json` 中的 Workflow 记录需迁移到 `workspace_store.json`：

- `Workflow.groupId` → `PersonalWorkspace.roomId`
- `Workflow.workingDir` → `PersonalWorkspace.workingDir`
- `Workflow.agentSessions[agentType].cliSessionId` → `PersonalWorkspace.cliSessionId`
- `Workflow.activeAgent` → `PersonalWorkspace.agentType`
- 新增字段使用默认值（`visibility = PRIVATE`，`name = "default"`）

迁移在服务启动时自动执行，完成后删除旧文件。

---

## 十、实现分阶段计划

### Phase 1：模型统一 + 单人工作区重构

**目标**：底层数据模型迁移，用户可见行为不变。

- [ ] `PersonalWorkspace` 数据类 + `WorkspaceManager`
- [ ] `Message` 共享模型新增 `scope` / `workspaceId`（同步三端 FileContractsTest）
- [ ] `WorkflowManager` → `WorkspaceManager` 数据迁移（自动）
- [ ] `ChatServer` 广播支持 scope 过滤（TEAM/WORKSPACE）
- [ ] Workspace API 端点（CRUD）
- [ ] 前端 Web：Team Channel / 工作区 Tab 基础框架（单人时 Tab 不显示）
- [ ] 验证：单人工作流场景行为不变

### Phase 2：多用户多工作区支持

**目标**：同一 Room 内多用户各自连设备、独立工作区并行运行。

- [ ] 验证多用户并发 ACP 连接在同一 Room 的隔离性（`AgentRuntime` + `AcpRegistry`）
- [ ] Workspace 可见性（SHARED/PRIVATE）+ 广播路由按 scope 生效
- [ ] Co-pilot 授权 API + UI（授权弹窗含风险提示文案）
- [ ] 前端 Web：Team Channel + 成员工作区 Tab（按人分组，活跃状态角标）
- [ ] 验证：多用户并行，消息不跨工作区混入

### Phase 3：GitHub 集成 MVP

**目标**：GitHub 事件自动推送到 Team Channel，支持 Issue 一键开启工作区。

- [ ] `GitHubClient` + 仓库绑定 API（PAT）
- [ ] Webhook 接收端点（HMAC 验证 + 事件解析）
- [ ] GitHub 事件 → Team Channel + AI 摘要
- [ ] `GitContextBuilder`：Issue 内容拉取与 prompt 构建
- [ ] Issue 卡片 UI + "开始开发"按钮 → 自动创建工作区
- [ ] 验证：PR/Issue/CI 事件正确出现；Issue→工作区 prompt 完整

### Phase 4：体验完善

- [ ] AI 跨工作区上下文读取（Bob 的 AI 读 Alice 的共享工作区历史）
- [ ] 工作区里程碑自动推送 Team Channel（commit/PR 创建事件）
- [ ] GitLab 适配
- [ ] Android / Harmony 前端适配
- [ ] Guest 角色（第四角色，按需）
- [ ] GitHub OAuth 替换 PAT

---

*文档维护说明：设计决策变更请同步更新本文档对应 ADR 节；代码实现细节（具体函数签名、表结构）在实现过程中按实际情况补充，不要求设计阶段预先完备。*
