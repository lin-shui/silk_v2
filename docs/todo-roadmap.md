# Silk 待办规划（长期建设）

## 1. 背景与原则

待办能力是 Silk 的核心闭环模块，需要按“长期演进”治理，而不是零散需求堆叠。后续待办相关改动统一以本文件为准，先规划、后实现、再回写。

核心原则：
- 分层明确：`长期模板` 负责规则沉淀，`短期实例` 负责执行闭环。
- 数据幂等：同一逻辑事项不重复生成、重复执行可安全覆盖。
- 可恢复：系统提醒被外部删除后，应用可自动回补为可重设状态。
- 可观测：接口、页面、诊断链路都可定位“为什么没显示/没执行”。
- 渐进交付：每个里程碑都有可验收的最小成果。

## 2. 现状基线（截至当前）

已落地能力（以当前分支代码为基线）：
- 后端支持用户跨群待办提取、刷新、异步刷新状态与诊断接口。
- 后端待办模型支持 `taskKind`、`repeatRule`、`lifecycleState` 等字段。
- 鸿蒙端已有独立待办页（分短期/长期分区）与执行按钮逻辑。
- 鸿蒙端已具备待办刷新、恢复、编辑、删除等基本能力。
- 前端 API 对待办返回体已做字段归一化与 `null` 安全兜底。

## 3. 里程碑

### M1 稳定性（当前优先）
- 目标：确保“看得到、点得动、状态一致”。
- 结果：
  - 列表渲染稳定，无空白/不刷新问题。
  - 同一用户下，待办列表与诊断计数一致。
  - 请求失败可回退并给出可读错误。

### M2 闭环一致性
- 目标：编辑/勾选/删除/执行全链路一致，前后端状态不漂移。
- 结果：
  - 执行与回写策略统一（本地 optimistic + 服务端回流）。
  - 模板与实例去重、回流、暂停/启用规则统一。

### M3 体验与治理
- 目标：让用户“可理解、可预期、可自助排障”。
- 结果：
  - 页面展示刷新状态、失败原因、重试入口。
  - 关键操作有最小交互锁，避免并发误操作。

### M4 回归自动化
- 目标：核心待办链路可重复验证，减少回归风险。
- 结果：
  - 建立端到端回归清单与固定验证脚本。
  - PR/提测附带待办链路验证结果。

## 4. 需求池（Backlog）

状态定义：`pending` / `in_progress` / `done` / `blocked`

| ID | 状态 | 价值 | 范围 | 验收标准 | 风险/依赖 |
|---|---|---|---|---|---|
| TODO-M1-001 | pending | 自动生成稳定 | 后端抽取 + 存储合并 | 同源事项不重复、刷新后数量稳定 | 依赖抽取质量与去重键 |
| TODO-M1-002 | pending | 列表显示可靠 | 鸿蒙待办页渲染 | 诊断有数据时列表可见且分区正确 | 依赖 taskKind/repeatRule 完整性 |
| TODO-M1-003 | pending | 网络健壮 | ApiClient 待办接口 | loopback/模拟器差异可自动回退 | 依赖设备网络环境 |
| TODO-M1-004 | done | Obsidian 联动一阶段 | 群聊导出 Markdown（本地桥接）；Web 经 ApiClient 拉取 JSON 后本地下载；Weaviate 原生启动兜底 | 成员可导出非空 `.md`；非成员被拒；无代理时浏览器访问端口与后端监听一致 | 无反向代理时 `BACKEND_INTERNAL_PORT` 应与 `BACKEND_HTTP_PORT` 一致或省略前者 |
| TODO-M2-001 | pending | 编辑闭环一致 | 前后端 update/delete/toggle | 编辑后 UI 与服务端状态一致，无幽灵项 | 依赖 patch 字段约束 |
| TODO-M2-002 | pending | 执行幂等可恢复 | Alarm/Calendar + 回补逻辑 | 重复执行不脏写，删除系统提醒后可恢复 | 依赖系统能力返回 |
| TODO-M2-003 | done | 长期模板可执行 | 鸿蒙长期模板运行按钮 + 执行回写 | 长期模板（如工作日7点起床）可直接运行并创建系统闹钟，执行后模板仍保持 active | 依赖时间解析与系统闹钟能力 |
| TODO-M2-004 | done | 工作日语义正确 | 鸿蒙执行前工作日校验 | `repeatRule=workday` 模板在周末不创建闹钟并提示原因 | 依赖本地日期判定（后续可接法定节假日） |
| TODO-M2-005 | done | 执行可撤回 | 待办执行后手动撤回入口 + 状态回滚 | 执行后可一键撤回并清理系统提醒，且可再次执行 | 依赖 reminderId 与系统能力幂等 |
| TODO-M2-006 | done | 法定工作日准确判定 | 前后端工作日查询与执行前校验 | 工作日模板按中国法定节假日/调休判断，不再仅周一到周五 | 依赖 HolidayCalendarCn 与 API 可用性 |
| TODO-M3-001 | pending | 反馈友好 | 待办页状态提示 | 刷新、失败、恢复场景都有可读提示 | 依赖错误归类 |
| TODO-M4-001 | pending | 降低回归成本 | 端到端验证脚本/清单 | 每次发布可跑固定待办主链验证 | 依赖测试环境可用性 |
| TODO-FE-001 | done | 可控的 @Silk 回复 | 多端聊天输入区停止按钮；共享 `ChatClient` 发送 `STOP_GENERATE`；后端取消活跃生成协程；`DirectModelAgent` 协作取消 | Web/Android/Desktop/鸿蒙在 Silk 生成中可点「停止」；后端任务被取消；客户端可保留已输出片段并抑制残余流式帧 | 依赖 WebSocket 与 Kotlin 协程取消语义 |
| TODO-NAV-001 | done | 全局三栏导航 + 移动端底栏 | Web：NavRail + Tab；Android/Harmony：手机布局为底部 Tab（聊天页隐藏底栏），知识库单栏递进、工作流全宽单列 | Web 左侧栏三 Tab；Android/Harmony 底栏三 Tab 且聊天不挡输入；KB 可主题→条目→编辑；工作流列表全宽 | 鸿蒙主壳暂不含原侧栏「设置」快捷入口，需从群列表等入口进入设置 |
| TODO-WF-001 | done | 工作流占位结构 | 后端 Workflow CRUD + 三端工作流列表/创建/删除 UI | 可创建、列出、删除工作流名称；详情区显示"功能开发中" | 编排逻辑留白，由其他开发者补充 |
| TODO-KB-001 | done | 知识库核心 | 后端 KBTopic/KBEntry CRUD + 三端知识库主题/条目列表 + Markdown 编辑器 | 可创建主题和条目，编辑并保存 Markdown 内容 | 初期 JSON 文件存储，后续可迁移 DB |
| TODO-KB-002 | done | 知识库 Obsidian 自动归类导出 | 后端 KBObsidianExporter + Web 端 ObsidianVaultManager 写入 | Web 端可导出到 Obsidian vault 目录 `Silk/Knowledge/{project}/{topic}/` | File System Access API 仅 Chrome/Edge 支持 |

## 5. 执行中（当前会话）

约束：
- 同时只允许 1 个 `in_progress` 事项。
- 新需求若不在需求池，先补一条再开始实现。

当前执行项：
- （空）

## 6. 实施流程（必须遵守）

1. 接到待办相关需求后，先定位到本文件：
   - 判断是否命中既有 ID；
   - 若未命中，先新增需求池条目（含验收标准）。
2. 将目标项状态改为 `in_progress`，再进入代码改动。
3. 完成实现与验证后，状态改为 `done`，记录结果与残留风险。
4. 提交说明（commit / PR）必须引用至少一个待办规划 ID。

## 7. 变更日志

### 2026-03-30（初始化）
- 创建本规划文件，确立待办长期建设里程碑与执行治理流程。
- 后续所有待办开发需遵循“先规划后实施”。

### 2026-03-30（TODO-M2-003 完成）
- 需求：长期模板（如“工作日七点起床”）也要支持直接执行并落系统闹钟。
- 实施：
  - 放开长期模板的 `todoCanExecute` 限制（TodoPage + ChatPage）。
  - 执行回写区分长期/短期：长期模板执行后保持 `lifecycleState=active`、`done=false`，仅更新 `executedAt/reminderId`。
  - 短期实例保持原有执行后 `done=true` 的闭环语义。
- 结果：长期模板可显示“运行/重设”并创建闹钟，不会因执行被误置为“已暂停”。

### 2026-03-30（TODO-M2-004 完成）
- 问题：工作日模板在休息日也会创建闹钟，未体现 `workday` 语义。
- 实施：
  - 在 `TodoPage` 与 `ChatPage` 的执行入口新增工作日模板判定与“今日是否工作日”校验。
  - 对 `repeatRule=workday`（或标题含“工作日/上班日”）的长期模板，在周六/周日拦截执行并提示原因。
- 结果：休息日不会为工作日模板创建闹钟；工作日可正常执行。

### 2026-04-13（TODO-M1-004 完成）
- 需求：Silk 群聊记录可导出为 Obsidian 友好 Markdown（本地桥接架构第一阶段）。
- 实施：
  - 后端：`ChatObsidianExporter`、群成员校验、`GET /groups/{groupId}?export=obsidian_markdown` 返回 JSON（`buildJsonObject`，避免 `Map<String, Any>` 序列化 500）。
  - Web：`ApiClient.exportGroupMarkdown` + 聊天头 `📝` 按钮，成功后在浏览器侧 Blob 下载。
  - `silk.sh`：Weaviate 优先启动 `search/bin/weaviate` 原生进程；`start-native.sh` 修正二进制路径与从 `.env` 读端口。
- 结果：导出文件含完整 frontmatter 与消息正文；无 Docker 时 Weaviate 可正常拉起。
- 残留：若部署无 nginx 且同时配置内外端口不一致，会出现「旧进程占公网端口」类问题，需在 `.env` 对齐或只配 `BACKEND_HTTP_PORT`。

### 2026-03-30（TODO-M2-005 / TODO-M2-006 完成）
- 需求：
  - 每一个执行都要求可撤回；
  - 工作日模板需要按法定节假日/调休口径判定。
- 实施：
  - 后端新增工作日查询接口 `GET /api/calendar/workday/{yyyy-MM-dd}`，复用 `HolidayCalendarCn.isWorkday`。
  - 前端 `ApiClient` 新增 `isCalendarWorkday(date)`，并在查询失败时提示后按周规则兜底。
  - `TodoPage` 与 `ChatPage` 的执行前校验改为调用法定工作日接口（仅针对 `workday` 模板）。
  - `TodoTaskCardRow` 与 `ChatPage` 待办行增加“撤回执行”入口；撤回会尝试取消系统提醒并回写待办状态（`done=false`, `clearReminderId=true`, `lifecycleState=active`）。
- 结果：
  - 执行后的待办可手动撤回并再次执行；
  - 工作日模板不再仅按周一到周五粗判，已接入后端法定口径。

### 2026-04-15（TODO-NAV-001 / TODO-WF-001 / TODO-KB-001 / TODO-KB-002 完成）
- 需求：Silk 产品重构为三栏导航布局（Silk / 工作流 / 知识库）。
- 实施：
  - 后端：新增 Workflow CRUD（`WorkflowManager`）、KnowledgeBase CRUD（`KnowledgeBaseManager`）、`KBObsidianExporter`，路由挂载到 `/api/workflows` 和 `/api/kb/*`。
  - Web（Compose for Web）：`AppState` 新增 `NavTab` 枚举 + `currentTab`；`SilkApp` 重构为 `SilkNavRail` + `SilkTabContent`；新增 `WorkflowScene` 和 `KnowledgeBaseScene`（三列：主题/条目/Markdown 编辑器 + Obsidian 导出）。
  - Android（Jetpack Compose）：`AppState` 新增 `NavTab` + `currentTab`；`SilkApp` 重构为 Material 3 `NavigationRail` + Content；新增 `WorkflowScreen` 和 `KnowledgeBaseScreen`。
  - HarmonyOS（ArkUI）：`Router` 新增 `Main` PageName；新增 `MainPage`（Row: NavRail Column + Content）；新增 `WorkflowPage` 和 `KnowledgeBasePage`；`ApiClient` 新增 raw HTTP helpers 和 Workflow/KB API。
- 结果：三端登录后展示左侧导航栏，可切换 Silk（原有功能）、工作流（列表占位）、知识库（主题-条目-编辑器完整链路）。
- 残留：工作流编排逻辑留白；窄屏降级为底部导航待后续迭代。

### 2026-04-15（TODO-NAV-001 移动端壳层）
- 需求：Android APK 与鸿蒙主界面与手机习惯一致：底栏主导航，避免侧栏占宽；知识库与工作流在窄屏下可用。
- 实施：
  - Android：`Scaffold` + `NavigationBar`（三 Tab）；`CHAT_ROOM` 隐藏底栏；移除与底栏冲突的调试浮层；`KnowledgeBaseScreen` 单栈（主题→条目→编辑器）；`WorkflowScreen` 全宽单列 + FAB。
  - Harmony：`MainPage` 底部 `Tabs`；`KnowledgeBasePage` / `WorkflowPage` 与 Android 对齐的窄屏信息架构。
- 结果：`fork` 提交 `df4d961`；未纳入 `backend/static/entry-default-unsigned.hap`（本地构建产物，可按 `silk.sh build-hap` 重生成）。
- 残留：鸿蒙 `MainPage` 需另设「设置」入口（若产品要求从主壳直达）。

### 2026-04-15（TODO-FE-001 完成）
- 需求：`@Silk` 对话在流式生成过程中可停止，后端同步中止生成。
- 实施：
  - 后端：`MessageType.STOP_GENERATE`、`ChatServer` 跟踪 `activeAiJob` 并取消；`DirectModelAgent` 增加 `ensureActive()` 与取消传播。
  - 共享：`MessageType.STOP_GENERATE`、`ChatClient` 的 `isGenerating`、`stopGeneration()` 与停止后 transient 抑制。
  - Web/Android/Desktop：生成中显示停止按钮并调用 `stopGeneration()`；鸿蒙沿用 `ChatStore`/`ChatPage`。
  - i18n：Web 使用 `strings.stopButton`（中/英）。
- 结果：多端编译通过；停止路径与后端已有处理对齐。
- 验证：本地 `./gradlew` 编译 `webApp`/`androidApp`/`desktopApp`/`backend` 通过（设备端流式行为建议再手测）。
