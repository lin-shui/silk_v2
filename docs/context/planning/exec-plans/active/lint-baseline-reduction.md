# Lint Baseline Reduction

## Goal

在已经接入 `./gradlew silkLint` 的基础上，继续把 detekt baseline 转化为源码修复。原则保持不变：baseline 只减不增，每一步都能独立验证、独立 review。

## Remaining Surfaces

- 当前无剩余 detekt baseline；后续只处理新增 lint，不再维护历史豁免。

已完成并归档：

- Android baseline 清零：见 [2026-06-02-lint-baseline-reduction-slice-85.md](../completed/2026-06-02-lint-baseline-reduction-slice-85.md) 与 [2026-06-05-android-lint-baseline-closeout.md](../completed/2026-06-05-android-lint-baseline-closeout.md)
- Web baseline 清零：见各 slice 归档，active plan 不再保留 Web 待办
- Desktop baseline 清零：active plan 不再保留 Desktop 待办
- Shared baseline 清零：见 [2026-06-05-lint-baseline-reduction-slice-86.md](../completed/2026-06-05-lint-baseline-reduction-slice-86.md)

## Guardrails

- 每个 lint 收敛 slice 必须同时包含源码修复和对应 baseline 删除。
- 不默认运行并提交全量 `./gradlew silkLintBaseline` 覆盖结果。
- 不把 ktlint/格式化大重排混进 detekt 收敛。
- 不为过 lint 改变协议字段、外部 API payload 或跨端消息合同；如需改名先用 `@SerialName` 保合同。
- 异常处理类规则不能机械替换；必须先判断是否可恢复、是否要透传 coroutine cancellation、是否需要记录日志或返回用户可理解错误。

## Validation

每一步至少运行：

- `./gradlew silkLint`
- `git diff --check`

按改动面追加最窄验证：

- backend 改动：`./gradlew :backend:detekt`、`./gradlew :backend:test`
- shared 改动：按受影响 consumer 追加编译或测试

## Current Snapshot

当前 detekt baseline 余量（2026-06-22，Slice 179 后）：

- `backend.xml`: 0
- `frontend-androidApp.xml`: 0
- `frontend-webApp.xml`: 0
- `frontend-shared.xml`: 0
- `frontend-desktopApp.xml`: 0

当前关键分布：

- `frontend/androidApp` detekt baseline 已清空，并于 2026-06-05 再次通过 `:frontend:androidApp:detekt`、`:frontend:androidApp:compileDebugKotlin` 与 `silkLint` 复验。
- `frontend/webApp` detekt baseline 已清空；后续 Web 再出现 lint 只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/desktopApp` detekt baseline 已清空；后续 Desktop 再出现 lint 只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared` detekt baseline 已清空，并于 2026-06-05 通过 `:frontend:shared:detekt`、三端 consumer 编译与 `silkLint` 复验。
- `AIStepwiseAgent.kt` 已移除 1 条 `LargeClass` baseline；主类现在只保留诊断主流程、AI 调用入口、医生增量诊断编排与对外数据模型，步骤失败收口、聊天上下文/离线结果、诊断历史持久化、fallback 报告拼装与 streaming/quick-response helper 已拆到 `AIStepwise*Support.kt` 顶层文件，继续保持诊断步骤顺序、PDF 生成入口、诊断历史文件结构与流式增量回调语义不变。
- `AgentRuntime.kt` 已移除 1 条 `LargeClass` baseline；`AgentRuntime` 现只保留对外门面、workflow/状态快照数据模型与 filesystem proxy API，状态/persistence、命令编排与 ACP question/permission/plan-review 交互分别拆到 `AgentRuntimeStateSupport.kt`、`AgentRuntimeCommandSupport.kt` 与 `AgentRuntimeAcpSupport.kt`，继续保持 `/use` / `@agent` / `/session` / `/compact` / AskUserQuestion / permission card / plan review / `_silk` bridge 调用路径与工作流持久化合同不变。
- `PDFReportGenerator.kt` 已移除 1 条 `LargeClass` baseline；PDF 报告主类现只保留文件名/文档生命周期编排，文本清洗与字体/表格 helper、聊天历史抽取以及章节渲染拆到独立顶层 helper 文件，继续保持 PDF 文件名规则、报告章节顺序、诊断摘要提取与下载 URL 合同不变。
- `WeaviateClient.kt` 已移除 1 条 `LargeClass` baseline；Weaviate 客户端现只保留公开搜索/索引编排、HTTP 客户端和失败恢复，文本清洗、GraphQL query 构造/解析、搜索结果映射和索引 JSON helper 已拆到 `WeaviateClientSupport.kt`，继续保持 Weaviate REST/GraphQL 路径、字段名和 false/empty-result 回退语义不变。
- `UserTodoStore.kt` 已移除 1 条 `LargeClass` baseline；待办存储主对象现只保留文件读写、生命周期 merge、部分字段更新和去重入口，周期模板实例化、更新字段解析与逻辑去重/包含式标题合并 helper 已拆到 `UserTodoStoreSupport.kt`，继续保持 `/api/user-todos*` 存储结构、模板实例化、logical-key dedupe 与包含式标题合并合同不变。
- `GroupTodoExtractionService.kt` 已移除 1 条 `LargeClass` baseline；待办抽取主对象现只保留刷新编排、diagnostics 写回和对外入口，transcript 构造、启发式/周期模板抽取、LLM prompt/调用、JSON 解析和去冗 helper 已拆到 `GroupTodoExtractionSupport.kt`，继续保持 `/api/user-todos*` 抽取写回、周期模板、logical-key 去重与失败回退语义不变。
- `KnowledgeBaseReferenceResolver.kt` 的新增命名阻塞已清掉；知识库引用 helper 现落到 `KnowledgeBasePromptContext.kt`，继续保留 `resolveKnowledgeBasePromptContext(...)` / `buildKnowledgeBasePath(...)` 对外符号与 KB 引用 prompt 合同不变。
- `ExternalSearchService.kt` 已移除 1 条 `FunctionOnlyReturningConstant` 和 5 条 `MayBeConst` baseline；静态搜索 URL 现已收敛为 `const val`，未使用的 `isAvailable()` 已删除。
- `ExternalSearchService.kt` 的 9 条 `ConstructorParameterNaming` baseline 已清理；对外 JSON 字段改为 `@SerialName(...) + camelCase`，不改变 SerpAPI / DuckDuckGo / SearXNG 的响应反序列化合同。
- `ExternalSearchService.kt` 又移除 1 条 `CyclomaticComplexMethod` 与 1 条 `NestedBlockDepth` baseline；外部搜索优先级链现改为统一的 attempt helper 执行，继续保持 SearXNG -> SerpAPI -> Bing -> Wikipedia -> DuckDuckGo 的回退顺序、成功短路和日志语义不变。
- `ExternalSearchService.kt` 又移除 1 条 `TooGenericExceptionCaught` baseline；顶层搜索入口、单次 attempt 与各 provider 的失败回退现在统一经 `recoverSearchFailure(...)` 收口，继续透传 `CancellationException`，并保持 SerpAPI 失败后 fallback DuckDuckGo、各 provider 日志级别与失败返回结构不变。
- `AIConfig.kt`、`AcpWebSocketTransport.kt`、`SilkAgent.kt` 与 `AnthropicClient.kt` 已移除 5 条 `UseCheckOrError` 和 1 条 `UseRequire` baseline；这些前置校验现统一改为 `check` / `checkNotNull` / `require`。
- `AiModels.kt`、`AIStepwiseAgent.kt`、`GroupTodoExtractionService.kt` 与 `AcpCapabilities.kt` 已额外移除 7 条 `ConstructorParameterNaming` baseline；消息模型、AI 请求体与 ACP `_silk` 扩展能力声明现统一改为 `camelCase + @SerialName(...)`，不改变现有协议字段。
- `SearchDrivenAgent.kt` 的两处 HTTP 非 200 响应抛错已从 `Exception(...)` 收紧为 `error(...)`；`TooGenericExceptionThrown` baseline 再减 1 条，不改变上层 fallback 行为。
- `SearchDrivenAgent.kt` 又移除 1 条 `CyclomaticComplexMethod`、1 条 `NestedBlockDepth`、1 条 `SwallowedException` 与 1 条 `TooGenericExceptionCaught` baseline；三层搜索、意图分析、流式 SSE 解析和索引失败出口现统一改为 helper + `runCatching` 收口，并继续透传 `CancellationException`，不改变 fallback、日志和搜索结果合同。
- `AcpUpdateMapper.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；ACP `session/update` 映射现在按 message/thought/tool/plan/question helper 分发，保留 ask-user-question 卡片格式、streaming 累积和 stableId 合同不变。
- `GroupTodoExtractionService.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`refreshTodosForUser(...)` 现已拆成“准备刷新上下文 / 选择 primary drafts / 应用抽取结果 / 记录 diagnostics” helper，继续保持“无 API key / 无群组 / 无群消息文件时的 skip 语义、LLM 与启发式二选一、forced recurring 叠加、replace-undone 写回、compact + logical-key dedupe 收尾”的既有刷新合同不变。
- `UserTodoStore.kt` 已移除 1 条 `ComplexCondition` baseline；alarm/calendar 的结构化任务判定已收敛到 helper，继续保持“同类结构化日程按 logical key 去重、不同时间不误并”的既有合并合同。
- `GroupTodoExtractionService.kt` 已移除 1 条 `ComplexCondition` baseline；纪念日月日锚点现在经 helper 校验，继续保持“仅合法 month-day 生成 yearly template anchor，非法值回退 null”的既有合同。
- `GroupTodoExtractionService.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；粗粒度时间提取现拆成中文半点、中文整点、`HH:mm` 与阿拉伯数字点钟 helper，继续保持 PM 归一化、半点解析和非法时间回退 `null` 的既有合同不变。
- `TrustedDirManager.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；trusted dir store 读取现按反序列化 / I/O 分层回退，路径规范化失败也只在非法路径、权限或 I/O 失败时退回原始路径比较，不改变 trust 判定合同。
- `UserRepository.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；`createUser()` 现在只对 `ExposedSQLException` / `SQLException` 这类数据库写入失败回退 `null`，唯一键冲突分支也已补测试锚定，不改变注册失败合同。
- `Routing.kt` 已移除 1 条 `SwallowedException: ... CancellationException` baseline；Agent Bridge / group chat / audio duplex 的正常取消不再静默吞掉，日志也补到了取消与关闭失败路径。
- `FileRoutes.kt` 已移除 1 条 `UnusedPrivateProperty`、1 条 `ImplicitDefaultLocale` 与 1 条 `ForbiddenComment` baseline；文件路由不再保留未接线的删除索引 TODO，APK/HAP 版本号格式化也显式固定到 `Locale.ROOT`。
- `ChatHistoryManager.kt` 与 `ClaudeProcessClient.kt` 已移除 2 条 `UnusedPrivateProperty` baseline；孤立未接线局部值和私有属性已删除，不影响撤回链路或 Claude PTY 调用。
- `ChatHistoryManager.kt` 又移除 1 条 `TooGenericExceptionCaught` baseline；会话/历史加载、损坏文件备份和原子写入清理现在按 `SerializationException` / `IllegalArgumentException` / `IOException` / `SecurityException` 分层回退，继续保持“损坏先备份、拒绝覆盖既有历史、保存失败只记日志”的持久化合同不变。
- `ChatHistoryManager.kt` 已移除 1 条 `LoopWithTooManyJumpStatements` baseline；`findAgentRepliesAfterMessage(...)` 现在按“是否继续扫描 / 是否仍在 5 分钟连续窗口” helper 收敛，继续保持“只识别目标用户消息后连续出现的 agent 回复，遇到其他用户插话或超时即停止”的既有撤回链路合同不变。
- `GroupTodoExtractionService.kt` 已移除 1 条 `UseCheckOrError` baseline；group todo 的 LLM HTTP 非 200 失败出口现改为 `error(...)`，保持原有 `IllegalStateException` 失败语义。
- `AnthropicClient.kt` 与 `AIStepwiseAgent.kt` 已移除 3 条 `TooGenericExceptionThrown` baseline；AI HTTP 非 200 失败出口统一改为 `error(...)`，不改变既有错误文案和上层 fallback。
- `AIStepwiseAgent.kt` 与 `PDFReportGenerator.kt` 又移除 1 条 `UnusedPrivateProperty` 和 1 条 `TooGenericExceptionThrown` baseline；主源码 backend 已不再保留 `TooGenericExceptionThrown`。
- `UserSettingsRepository.kt` 与 `WorkflowManager.kt` 已移除 3 条异常语义 baseline；用户语言枚举回退现在只捕获 `IllegalArgumentException` 并保留 warning 日志，workflow store 读取也按 decode / 内容非法 / I/O 分层处理，不改变默认回退到空 store 的行为。
- `EnvLoader.kt` 与 `KnowledgeBaseManager.kt` 已移除 2 条 `TooGenericExceptionCaught` baseline；`.env` 读取现在只按 I/O / 权限失败回退，KB store 加载也按 decode / 内容非法 / I/O 分层处理，不改变“失败即回退空配置/空 store”的既有行为。
- `AcpClient.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；ACP 请求发送、receive loop、单条消息分发和 permission request 处理不再依赖 `catch (e: Exception)`，统一改为 `runCatching` + cancellation 透传，保持 malformed JSON / handler 失败不拖垮整条连接的既有容错语义。
- `UserTodoRefreshAsyncManager.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；后台待办刷新改为 `runCatching` + 结构化日志，正常 cancellation 不再被误记成失败，业务异常仍会回写到 `lastError` 供前端轮询状态。
- `SilkAgent.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；消息索引到 Weaviate 的失败分支改为 `runCatching`，正常 cancellation 不再被吞掉，其余异常仍按原语义记录日志并返回 `false`。
- `ContactRepository.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；联系人写库失败现在只对 `ExposedSQLException` / `SQLException` 回退 `false/null`，重复联系人插入的既有失败合同已补 `ContactRepositoryTest` 锚定。
- `GroupRepository.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；群组创建/加人/退群/删群等写库失败现在只对 `ExposedSQLException` / `SQLException` / `SecurityException` 回退，Weaviate 群组同步改为 `runCatching` 并继续透传 `CancellationException`，重复成员插入失败合同已补 `GroupRepositoryTest` 锚定。
- `ChatHistoryBackupManager.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；群组历史备份、单条撤回备份、备份恢复与备份清理现在按 `SerializationException` / `IllegalArgumentException` / `IOException` / `SecurityException` 分层回退，损坏备份元数据的“跳过列表、恢复返回 false”合同已补 `ChatHistoryBackupManagerTest` 锚定。
- `AsrRoutes.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；ASR 路由请求解析现在按 `SerializationException` / `ConnectException` / `IOException` / `SecurityException` 分层回退，ffmpeg 转码和临时文件清理也不再依赖 broad catch，坏 base64 请求返回 400 的既有合同已补 `AsrRoutesTest` 锚定。
- `UserTodoStore.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；Todo JSON 读取失败现在按 `SerializationException` / `IllegalArgumentException` / `IOException` / `SecurityException` 分层回退空列表，损坏 payload 的既有容错合同已补 `UserTodoStoreTest` 锚定。
- `ClaudeProcessClient.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；Claude CLI PTY 调用失败改为 `runCatching` 收口，仍保持“取消透传、其余异常强制销毁进程后继续抛出”的既有行为。
- `Application.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；后端启动阶段的 Weaviate 群组同步、历史批量索引和单会话索引失败现统一改为 `runCatching` 收口，继续保持“记录 warning 并跳过失败项”的既有启动容错语义，不改变游标、索引路径或启动入口。
- `Application.kt` 又移除 1 条 `CyclomaticComplexMethod`、1 条 `NestedBlockDepth` 与 1 条 `LoopWithTooManyJumpStatements` baseline；历史聊天批量索引主循环已拆成单职责 helper，继续保持会话过滤、游标推进、50 条分批索引与启动期 best-effort 日志语义不变。
- `ToolPolicyManager.kt` 已移除 1 条 `TooGenericExceptionCaught` 和 1 条 `SwallowedException` baseline；工具策略配置加载现在按 `SerializationException` / `IOException` / `SecurityException` 分层回退，非法权限枚举只回退为 `DISABLED`，路径规范化失败也只在 `canonicalPath` 相关 I/O / 权限失败时退回原字符串比较，不改变沙箱路径与安全命令的既有判定合同。
- `ToolPolicyManager.kt` 又移除 2 条 `NestedBlockDepth` baseline；配置文件加载与路径白名单/黑名单校验已拆成 helper，但仍保持“配置缺失回退默认策略、黑名单优先于白名单、非法权限枚举回退 `DISABLED`”的既有合同。
- `CommandRouter.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；agent 路由总入口现按 `/use`、trigger command、`@agent` 与 slash command 分拆 helper，继续保持 alias、插队、无 agent 透传和 `/session` `/cd` 等命令解析语义不变。
- `FileRoutes.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；文件上传、异步索引与 PDF 文本提取不再依赖 `catch (e: Exception)`，改为 `runCatching` + cancellation 透传，保持上传失败返回 500、索引失败只回写状态消息、PDF 提取失败回退 `null` 的既有合同。
- `FileRoutes.kt` 又移除 1 条 `NestedBlockDepth` baseline；长文本分块逻辑现把 chunk 边界探测拆到 helper，继续保持大文档按窗口回退、优先句边界切分和 overlap 续接的既有索引切块语义不变。
- `FileRoutes.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；`fileRoutes()` 现已收敛为纯路由挂载入口，上传、下载、版本查询、文件列表与删除均拆到独立 `register*Route()` helper，保持 `/api/files/*` 路径、返回体和异步索引流程不变。
- `FileRoutes.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；`indexFileToWeaviate(...)` 现已拆成 Weaviate 就绪检查、内容提取、内容清洗、分块准备、单块索引与关键词构造 helper，保持 PDF/text/binary 分流、分块大小、关键词上限与成功块计数语义不变。
- `UserTodoStore.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；模板实例是否在今天触发的判断现拆成 active window / yearly anchor / monthly anchor helper，继续保持工作日、年周期、月周期与 activeFrom/activeTo 窗口判定合同不变。
- `UserTodoStore.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；action detail 归一化现拆成 exact time / full datetime / relative datetime helper，继续保持闹钟/日程 logical key 的时间标准化与 fallback `normKey(...)` 合同不变。
- `UserTodoStore.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；标题里的时间提取现拆成中文半点、中文整点、阿拉伯数字点钟与 `HH:mm` helper，继续保持 PM 归一化、半点解析和非法时间回退 `null` 的既有合同不变。
- `UserTodoStore.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；短期任务状态 merge 现拆成“查找已有项 / active merge / reopen 判定 / reopen copy” helper，继续保持 `active` 直接合并、`done`/`deferred` 按证据时间重开、`cancelled` 需显式意图才重开的既有合同不变。
- `UserTodoStore.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；包含式标题合并现拆成“结构化日程同 key 检查 / 标题包含检查 / 合并字段选择” helper，继续保持模板/实例不互并、`alarm/calendar` 仅同 logical key 合并、普通标题按归一化包含关系合并的既有合同不变。
- `UserTodoStore.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`updateItem(...)` 现已拆成 lifecycle/done/closedAt、提醒 ID 与可选字符串字段解析 helper，继续保持 `cancelled`/`deferred` 关闭态在 `done=true` 时不误改、blank 输入清空可选字段、`clearReminderId` 优先于显式 reminderId，以及 `closedAt<=0` 回退 `null` 的既有更新合同不变。
- `UserTodoStore.kt` 已移除 1 条 `LoopWithTooManyJumpStatements` baseline；包含式标题逐轮合并、模板实例化与抽取结果入库现在都已收敛到 helper 判定，不再依赖多分支 `continue`/`break` 控制流，继续保持模板/实例不互并、结构化日程仅同 logical key 合并、较短标题优先和较长 `actionDetail` 优先，以及“当天已实例化模板不重复生成、重复 logical key 与非法标题跳过”的既有合同不变。
- `AnthropicClient.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；Anthropic SSE 事件容错现在只对 `SerializationException`、`IllegalArgumentException` 与 `IllegalStateException` 这类解析失败做 debug 跳过，不再吞掉流式回调或其他运行时错误，继续保持坏事件 best-effort 跳过、正常 chunk 增量回调与 tool/citation 聚合合同不变。
- `AnthropicClient.kt` 又移除 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline；SSE 事件处理现按 start/delta/stop helper 分发，引用收集与 tool-use 收口也已下沉到专职 helper，继续保持 tool/citation 聚合、坏事件 best-effort 跳过和增量文本回调合同不变。
- `AnthropicClient.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；消息角色转换现按 user/assistant/tool helper 分发，tool_result 与 tool_use block 构造、参数 JSON 解析和 fallback user 映射都已拆出 helper，继续保持 Anthropic message payload 合同不变。
- `DirectModelAgent.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`normalizeCitedReferences(...)` 现已拆成提取 key、匹配已有元数据、补占位引用、正文重编号与 key 解析 helper，继续保持 citation / available 的去重顺序、重编号规则与占位引用合同不变。
- `DirectModelAgent.kt` 已移除 1 条 `LoopWithTooManyJumpStatements` baseline；缺失引用的 placeholder 生成现在统一经 `createPlaceholderReferences(...)` helper 收口，不再依赖 `for + continue`，继续保持真实引用优先重编号、缺失引用补占位以及 `citation` / `available` 分桶索引语义不变。
- `WeaviateClient.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`indexDocument(...)` 现已拆成文档清洗、JSON 构造与结果记录 helper，继续保持 Weaviate 请求地址、`SilkContext` 字段、JSON 转义、成功/失败日志与 `false` 回退语义不变。
- `WeaviateClient.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；ready/meta 健康检查、session 查询/注册、文档索引、删除与 BM25 搜索失败现在统一经 `recoverWeaviateFailure(...)` helper 收口，继续透传 `CancellationException`，并保持“连接/查询失败只记日志并回退 false/empty result、批量删除 best-effort 继续后续 messageId”的既有容错合同不变。
- `AgentRuntime.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`handleCommand(...)` 现已拆成 exit/new/status/queue/help/compact/session 系列 helper 与统一消息构造 helper，继续保持 slash command 文案、ACP `compact` / `listLocalSessions` / `sessionLoad` 调用路径、工作流会话清空持久化与 adapter fallback 行为不变。
- `GroupTodoExtractionService.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`dedupeDrafts(...)` 现已拆成“是否参与去重 / logical key 生成 / 候选优先级比较” helper，继续保持模板优先、显式意图优先、`actionDetail` 更长优先与最大返回条数限制的既有合并合同不变。
- `GroupTodoExtractionService.kt` 又移除 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline；`extractRecurringTemplateDrafts(...)` 现已拆成“逐群收集 / 单行判定 / draft 构造 / 标题与时间格式化” helper，继续保持工作日习惯 / 纪念日识别、`matchedLines` 截断、`long_term_template` 合同和 `workday`/`yearly` repeat 语义不变。
- `GroupTodoExtractionService.kt` 又移除 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline；`heuristicFromSlices(...)` 现已拆成“逐群收集 / 单行候选识别 / 单条 draft 构造 / alarm fallback 状态控制” helper，继续保持 checklist 提取、alarm 文本弱兜底、同一消息只接受一次非 checklist alarm fallback、标题截断与 `alarm`/`none` 判定语义不变。
- `GroupTodoExtractionService.kt` 又移除 1 条 `NestedBlockDepth` baseline；`buildTranscriptString(...)` 现已拆成“逐群追加 / 逐消息展开 / 单行标准化” helper，继续保持 transcript 头格式、逐行 `[sender]: content` 输出、空行跳过与 `MAX_TRANSCRIPT_CHARS` 截断语义不变。
- `GroupTodoExtractionService.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；`parseCompactTodoJson(...)` 现已拆成单条输出解析、可选字段提取与 `done` 状态回填 helper，继续保持“只接受输入中已有 id、未知/重复 id 跳过、title 长度校验、原始 createdAt/executedAt/reminderId 透传”的去冗写回合同不变。
- `AcpClientTest.kt` 已移除 1 条 `TooGenericExceptionThrown` baseline；receive-loop 存活性测试里的 handler 失败现在改抛 `IllegalStateException("boom")`，继续保持“单个 handler 崩溃不拖垮后续 RPC”的既有测试语义不变。
- `GroupTodoExtractionService.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；LLM 抽取/去冗入口不再依赖 `catch (e: Exception)`，改为 `callLlmOrNull(...)` 对 cancellation 透传、对 `InterruptedException` / `IOException` / `SerializationException` / `IllegalStateException` 等失败分层回退，JSON 解析也只对 decode/shape 异常降级，继续保持“抽取失败回退启发式、去冗失败走本地 logical-key 去重”的既有容错合同不变。
- `PDFReportGenerator.kt` 已移除 1 条 `UnusedPrivateProperty` baseline；未接线的 `chineseFontPath` 懒加载探测已删除，实际生效的字体路径仍由 `createChineseFont()` 的内置 CJK 字体分支承担，不改变 PDF 生成路径或字体回退合同。
- `DirectModelAgent.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；`chat(...)` 与 Claude CLI fallback 入口改为 `runCatching` 收口，继续透传 `CancellationException`，并保持 Claude CLI 失败后优先回退 Anthropic API、前端错误回写、回复持久化与 citation 后处理合同不变。
- `AIStepwiseAgent.kt` 已移除 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline；`generateQuickResponse(...)` 现已拆成请求构造、SSE 行消费、增量触发与尾包 flush helper，继续保持 `/chat/completions` streaming、`reasoning` 字段读取、按累计换行数推送增量以及仅在 `[DONE]` 后回调完成消息的既有 quick-response 合同不变。
- `AIStepwiseAgent.kt` 又移除 1 条 `CyclomaticComplexMethod` 和 1 条 `ThrowsCount` baseline；`callAIApiStreaming(...)` 现已收敛为主流程编排，HTTP 请求构造、状态校验、SSE 行读取与 chunk 消费都已下沉到 helper，继续保持非 200 失败、超时返回已收集文本和逐 chunk 回调前端的既有诊断流式合同不变。
- `AIStepwiseAgent.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`generateFallbackReport(...)` 现已拆成“报告头 / 通用 section 拼接 / 仅成功步骤附正文” helper，继续保持章节标题、step key 映射、成功步骤输出顺序与失败步骤留空的既有 fallback 报告合同不变。
- `AIStepwiseAgent.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`processDoctorDiagnosisUpdate(...)` 现已拆成“历史消息过滤 / 新消息上下文 / 旧诊断摘要 / 增量流式回写 / stepResults 与 PDF 消息构造” helper，继续保持“只用上次诊断后的新消息生成更新、每 3 行或 2 秒推一次增量、最终整合医生医嘱 + 历史诊断 + AI 更新诊断生成 PDF”的既有更新诊断合同不变。
- `AIStepwiseAgent.kt` 已移除 1 条 `LoopWithTooManyJumpStatements`、1 条 `SwallowedException` 与 1 条 `TooGenericExceptionCaught` baseline；流式读取 loop-jump / swallowed baseline 已确认过期，诊断执行、PDF 生成、流式请求、诊断历史读写与 quick response 的宽 catch 已收窄为取消透传、I/O/中断/解析/状态失败分层回退，继续保持步骤失败记录、部分流式结果保留、格式化失败 fallback、历史读写失败只记日志以及 quick response 失败回调文案不变。
- `PDFReportGenerator.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；生成失败后的文档关闭清理现改为 `runCatching` 收口，继续保持“主异常优先向上抛出、关闭失败只记 warning、不覆盖原始 PDF 生成错误”的既有收尾语义不变。
- `PDFReportGenerator.kt` 又移除 1 条 `TooGenericExceptionCaught` 和 1 条 `SwallowedException` baseline；字体加载、字符间距设置、消息摘要提取与主流程收尾现统一改成 `runCatching`/helper 收口，继续保持“字体失败回退 Helvetica、字符间距失败仅退默认值、摘要提取失败回退空串或提示文案、PDF 主异常优先向上抛出”的既有容错合同不变。
- `GroupTodoExtractionService.kt` 已移除 1 条 `LoopWithTooManyJumpStatements` baseline；历史加载、群切片收集、transcript 行展开、启发式/周期模板候选收集和 draft 去重不再依赖多跳转 `for + continue` 控制流，继续保持聊天历史查找顺序、候选过滤、每消息 alarm fallback、周期模板 matched lines 与 dedupe 优先级合同不变。
- `AgentRuntime.kt` 已移除 1 条 `LoopWithTooManyJumpStatements` baseline；bridge 断线时的 per-context session 清理现在按用户过滤并委托 `handleDisconnectedSession(...)`，pending question 清理也统一到 helper，继续保持 handler cleanup、CARD reply unregister、运行中任务 cancel 与 `acpSessionId` 置空语义不变。
- `Routing.kt` 已额外移除 1 条 `SwallowedException: ... Exception` baseline；trusted-dir JSON 解析、mark-read、workday 和 user-todo update/delete 的 400 回退现在保留异常日志，继续保持原 HTTP 状态码与响应体不变。
- `AgentRuntime.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；workflow 持久化、seed 加载、ACP command/prompt、question/permission/plan review 与目录操作失败分支已改为 `runAgentCatching` / `Result` 收口，继续透传 cancellation 并保持原用户可见文案。
- `WebSocketConfig.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；历史/URL 缓存、成员查询、卡片回复、Weaviate best-effort 索引、URL 下载、AI job、历史召回、医生诊断和撤回/删除索引分支已改为 `runChatCatching` / `Result` 收口，文件内不再保留 broad-catch suppress。
- `Routing.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；用户设置、Bridge 文件系统、trusted-dir、认证/群组/联系人、todo、消息、Agent Bridge、聊天 WebSocket 与 Audio Duplex 代理的宽 catch 改为 `runCatching` + cancellation 语义收口，继续保持原 HTTP 状态码、响应体和 best-effort 日志行为。
- `Routing.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`configureRouting()` 现在只负责 AgentRuntime persistence wiring 和 73 个 route registration helper 的挂载顺序，5 个拆分后仍偏复杂的 Bridge FS、CC settings、workflow 创建与 chat WebSocket handler 已继续抽到小 helper，保持原路径、状态码、响应体、广播文案和 WebSocket 行为不变。
- `BackendWebSocketContractTest.kt` 已移除 1 条 `LargeClass` baseline；聊天回放/鉴权合同与 URL 导入合同现已拆成独立 test class，仍保持历史回放、实时广播、URL 下载状态推送与失败回退验证覆盖不变。
- `backend` 已无 `WildcardImport`、`UnusedPrivateMember`、`UnusedParameter`、`EmptyFunctionBlock`、`AcpUpdateMapper.kt` 的 `CyclomaticComplexMethod`、`AgentRuntime.kt` 的 `handleCommand(...)` 复杂度基线、loop-jump / broad-catch / `LargeClass` 基线、`AIStepwiseAgent.kt` 的 `generateFallbackReport(...)` / `generateQuickResponse(...)` / `callAIApiStreaming(...)` / `processDoctorDiagnosisUpdate(...)` 复杂度基线与 `callAIApiStreaming(...)` 的 `ThrowsCount`、文件级 `TooGenericExceptionCaught` / `SwallowedException` / loop-jump 基线与 `LargeClass`、`AnthropicClient.kt` 的 `TooGenericExceptionCaught` / `NestedBlockDepth` / `convertMessage(...)` 复杂度基线、`AsrRoutes.kt` 的 `SwallowedException`、`ChatHistoryBackupManager.kt` 的 `PrintStackTrace` / `SwallowedException`、`ChatHistoryManager.kt` 的 `TooGenericExceptionCaught` 与 agent-reply scan loop 基线、`DirectModelAgent.kt` 的 `normalizeCitedReferences(...)` 复杂度基线、引用占位 loop 基线与 broad-catch 基线、`ExternalSearchService.kt` 的 `TooGenericExceptionCaught`、`FileRoutes.kt` 的 `fileRoutes(...)` / `indexFileToWeaviate(...)` / `chunkText(...)` 复杂度基线、`GroupTodoExtractionService.kt` 的 `buildTranscriptString(...)` / `dedupeDrafts(...)` / `extractRecurringTemplateDrafts(...)` / `heuristicFromSlices(...)` / `extractRoughHourMinute(...)`、loop-jump / `ComplexCondition` / `LargeClass` 基线、`UserTodoStore.kt` 的 `ComplexCondition`、`updateItem(...)` 复杂度基线与包含式标题合并 loop 基线、`PDFReportGenerator.kt` 的 `closeEx: Exception` broad-catch 基线、文件级 `e: Exception` broad-catch / swallow 基线与 `LargeClass`、`SearchDrivenAgent.kt` 的 `SwallowedException` / `TooGenericExceptionCaught` / `NestedBlockDepth`、`ToolPolicyManager.kt` 的 `SwallowedException`、`UserTodoStore.kt` 的 `isTemplateDueToday(...)` / `normalizeActionDetailForKey(...)` / `extractTimeFromTitle(...)` / `mergeShortInstanceByState(...)` / `tryMergeByContainedNormTitle(...)` 复杂度基线、`WeaviateClient.kt` 的 `indexDocument(...)` 复杂度基线与 `PrintStackTrace` / broad-catch 基线，以及 `Routing.kt` / `WebSocketConfig.kt` 的宽异常 baseline；剩余为 `LargeClass` 1（`WebSocketConfig.kt$ChatServer`）。
- `backend` 已无 `WildcardImport`、`UnusedPrivateMember`、`UnusedParameter`、`EmptyFunctionBlock`、`AcpUpdateMapper.kt` 的 `CyclomaticComplexMethod`、`AgentRuntime.kt` 的 `handleCommand(...)` 复杂度基线、loop-jump / broad-catch / `LargeClass` 基线、`AIStepwiseAgent.kt` 的 `generateFallbackReport(...)` / `generateQuickResponse(...)` / `callAIApiStreaming(...)` / `processDoctorDiagnosisUpdate(...)` 复杂度基线与 `callAIApiStreaming(...)` 的 `ThrowsCount`、文件级 `TooGenericExceptionCaught` / `SwallowedException` / loop-jump 基线与 `LargeClass`、`AnthropicClient.kt` 的 `TooGenericExceptionCaught` / `NestedBlockDepth` / `convertMessage(...)` 复杂度基线、`AsrRoutes.kt` 的 `SwallowedException`、`ChatHistoryBackupManager.kt` 的 `PrintStackTrace` / `SwallowedException`、`ChatHistoryManager.kt` 的 `TooGenericExceptionCaught` 与 agent-reply scan loop 基线、`DirectModelAgent.kt` 的 `normalizeCitedReferences(...)` 复杂度基线、引用占位 loop 基线与 broad-catch 基线、`ExternalSearchService.kt` 的 `TooGenericExceptionCaught`、`FileRoutes.kt` 的 `fileRoutes(...)` / `indexFileToWeaviate(...)` / `chunkText(...)` 复杂度基线、`GroupTodoExtractionService.kt` 的 `buildTranscriptString(...)` / `dedupeDrafts(...)` / `extractRecurringTemplateDrafts(...)` / `heuristicFromSlices(...)` / `extractRoughHourMinute(...)`、loop-jump / `ComplexCondition` / `LargeClass` 基线、`UserTodoStore.kt` 的 `ComplexCondition`、`updateItem(...)` 复杂度基线与包含式标题合并 loop 基线、`PDFReportGenerator.kt` 的 `closeEx: Exception` broad-catch 基线、文件级 `e: Exception` broad-catch / swallow 基线与 `LargeClass`、`SearchDrivenAgent.kt` 的 `SwallowedException` / `TooGenericExceptionCaught` / `NestedBlockDepth`、`ToolPolicyManager.kt` 的 `SwallowedException`、`UserTodoStore.kt` 的 `isTemplateDueToday(...)` / `normalizeActionDetailForKey(...)` / `extractTimeFromTitle(...)` / `mergeShortInstanceByState(...)` / `tryMergeByContainedNormTitle(...)` 复杂度基线、`WeaviateClient.kt` 的 `indexDocument(...)` 复杂度基线与 `PrintStackTrace` / broad-catch 基线，以及 `Routing.kt` / `WebSocketConfig.kt` 的宽异常 baseline；backend detekt baseline 已清零。

## Current Status

- Slice 1-179 完成历史均已归档到 `docs/context/planning/exec-plans/completed/`。
- Slice 179 已归档；Android / Web / Desktop / Shared / backend baseline 已全部清零。
- Android 侧既有 `JdkImageTransform` / `jlink` 环境阻塞仍未改变；这不影响 baseline 已清零这一事实。

## Next Slices

- 当前没有剩余 slice。后续若出现新的 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- 如果某一步发现需要新增 baseline，先停下来判断是否应关规则、补测试或拆小 PR，不要直接把新增项写进 baseline。

## Handoff Notes

- `frontend/androidApp` baseline 已清空；后续 Android 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/webApp` baseline 已清空；后续 Web 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/desktopApp` baseline 已清空；后续 Desktop 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared` baseline 已清空；后续 shared 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared/src/iosMain` 当前不在根 detekt source set 中；本计划按当前 lint 覆盖面推进，不把未启用 iOS 源码混进每一步。
- 如果回到 backend，直接修新增 lint；不要重新积累 backend baseline。
- `Routing.kt` 的 `SwallowedException: ... Exception`、`TooGenericExceptionCaught: ... Exception` 与 `configureRouting()` 复杂度 baseline 已清理；后续 Routing 再出现 lint 只接受按路由/handler 边界修源码，不回填 baseline。
- `WebSocketConfig.kt` broad-catch baseline 已清理；后续 WebSocket 再出现异常语义 lint，直接按失败边界修源码，不回填 baseline。
