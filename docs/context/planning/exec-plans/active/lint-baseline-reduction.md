# Lint Baseline Reduction

## Goal

在已经接入 `./gradlew silkLint` 的基础上，继续把 detekt baseline 转化为源码修复。原则保持不变：baseline 只减不增，每一步都能独立验证、独立 review。

## Remaining Surfaces

- `config/lint/detekt/backend.xml`
- `backend/src/main/kotlin/com/silk/backend/`

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

当前 detekt baseline 余量（2026-06-11，Slice 148 后）：

- `backend.xml`: 38
- `frontend-androidApp.xml`: 0
- `frontend-webApp.xml`: 0
- `frontend-shared.xml`: 0
- `frontend-desktopApp.xml`: 0

当前关键分布：

- `frontend/androidApp` detekt baseline 已清空，并于 2026-06-05 再次通过 `:frontend:androidApp:detekt`、`:frontend:androidApp:compileDebugKotlin` 与 `silkLint` 复验。
- `frontend/webApp` detekt baseline 已清空；后续 Web 再出现 lint 只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/desktopApp` detekt baseline 已清空；后续 Desktop 再出现 lint 只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared` detekt baseline 已清空，并于 2026-06-05 通过 `:frontend:shared:detekt`、三端 consumer 编译与 `silkLint` 复验。
- `ExternalSearchService.kt` 已移除 1 条 `FunctionOnlyReturningConstant` 和 5 条 `MayBeConst` baseline；静态搜索 URL 现已收敛为 `const val`，未使用的 `isAvailable()` 已删除。
- `ExternalSearchService.kt` 的 9 条 `ConstructorParameterNaming` baseline 已清理；对外 JSON 字段改为 `@SerialName(...) + camelCase`，不改变 SerpAPI / DuckDuckGo / SearXNG 的响应反序列化合同。
- `ExternalSearchService.kt` 又移除 1 条 `CyclomaticComplexMethod` 与 1 条 `NestedBlockDepth` baseline；外部搜索优先级链现改为统一的 attempt helper 执行，继续保持 SearXNG -> SerpAPI -> Bing -> Wikipedia -> DuckDuckGo 的回退顺序、成功短路和日志语义不变。
- `ExternalSearchService.kt` 又移除 1 条 `TooGenericExceptionCaught` baseline；顶层搜索入口、单次 attempt 与各 provider 的失败回退现在统一经 `recoverSearchFailure(...)` 收口，继续透传 `CancellationException`，并保持 SerpAPI 失败后 fallback DuckDuckGo、各 provider 日志级别与失败返回结构不变。
- `AIConfig.kt`、`AcpWebSocketTransport.kt`、`SilkAgent.kt` 与 `AnthropicClient.kt` 已移除 5 条 `UseCheckOrError` 和 1 条 `UseRequire` baseline；这些前置校验现统一改为 `check` / `checkNotNull` / `require`。
- `AiModels.kt`、`AIStepwiseAgent.kt`、`GroupTodoExtractionService.kt` 与 `AcpCapabilities.kt` 已额外移除 7 条 `ConstructorParameterNaming` baseline；消息模型、AI 请求体与 ACP `_silk` 扩展能力声明现统一改为 `camelCase + @SerialName(...)`，不改变现有协议字段。
- `SearchDrivenAgent.kt` 的两处 HTTP 非 200 响应抛错已从 `Exception(...)` 收紧为 `error(...)`；`TooGenericExceptionThrown` baseline 再减 1 条，不改变上层 fallback 行为。
- `SearchDrivenAgent.kt` 又移除 1 条 `CyclomaticComplexMethod`、1 条 `NestedBlockDepth`、1 条 `SwallowedException` 与 1 条 `TooGenericExceptionCaught` baseline；三层搜索、意图分析、流式 SSE 解析和索引失败出口现统一改为 helper + `runCatching` 收口，并继续透传 `CancellationException`，不改变 fallback、日志和搜索结果合同。
- `AcpUpdateMapper.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；ACP `session/update` 映射现在按 message/thought/tool/plan/question helper 分发，保留 ask-user-question 卡片格式、streaming 累积和 stableId 合同不变。
- `UserTodoStore.kt` 已移除 1 条 `ComplexCondition` baseline；alarm/calendar 的结构化任务判定已收敛到 helper，继续保持“同类结构化日程按 logical key 去重、不同时间不误并”的既有合并合同。
- `GroupTodoExtractionService.kt` 已移除 1 条 `ComplexCondition` baseline；纪念日月日锚点现在经 helper 校验，继续保持“仅合法 month-day 生成 yearly template anchor，非法值回退 null”的既有合同。
- `GroupTodoExtractionService.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；粗粒度时间提取现拆成中文半点、中文整点、`HH:mm` 与阿拉伯数字点钟 helper，继续保持 PM 归一化、半点解析和非法时间回退 `null` 的既有合同不变。
- `TrustedDirManager.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；trusted dir store 读取现按反序列化 / I/O 分层回退，路径规范化失败也只在非法路径、权限或 I/O 失败时退回原始路径比较，不改变 trust 判定合同。
- `UserRepository.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；`createUser()` 现在只对 `ExposedSQLException` / `SQLException` 这类数据库写入失败回退 `null`，唯一键冲突分支也已补测试锚定，不改变注册失败合同。
- `Routing.kt` 已移除 1 条 `SwallowedException: ... CancellationException` baseline；Agent Bridge / group chat / audio duplex 的正常取消不再静默吞掉，日志也补到了取消与关闭失败路径。
- `FileRoutes.kt` 已移除 1 条 `UnusedPrivateProperty`、1 条 `ImplicitDefaultLocale` 与 1 条 `ForbiddenComment` baseline；文件路由不再保留未接线的删除索引 TODO，APK/HAP 版本号格式化也显式固定到 `Locale.ROOT`。
- `ChatHistoryManager.kt` 与 `ClaudeProcessClient.kt` 已移除 2 条 `UnusedPrivateProperty` baseline；孤立未接线局部值和私有属性已删除，不影响撤回链路或 Claude PTY 调用。
- `ChatHistoryManager.kt` 又移除 1 条 `TooGenericExceptionCaught` baseline；会话/历史加载、损坏文件备份和原子写入清理现在按 `SerializationException` / `IllegalArgumentException` / `IOException` / `SecurityException` 分层回退，继续保持“损坏先备份、拒绝覆盖既有历史、保存失败只记日志”的持久化合同不变。
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
- `AnthropicClient.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；Anthropic SSE 事件容错现在只对 `SerializationException`、`IllegalArgumentException` 与 `IllegalStateException` 这类解析失败做 debug 跳过，不再吞掉流式回调或其他运行时错误，继续保持坏事件 best-effort 跳过、正常 chunk 增量回调与 tool/citation 聚合合同不变。
- `AnthropicClient.kt` 又移除 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline；SSE 事件处理现按 start/delta/stop helper 分发，引用收集与 tool-use 收口也已下沉到专职 helper，继续保持 tool/citation 聚合、坏事件 best-effort 跳过和增量文本回调合同不变。
- `AnthropicClient.kt` 又移除 1 条 `CyclomaticComplexMethod` baseline；消息角色转换现按 user/assistant/tool helper 分发，tool_result 与 tool_use block 构造、参数 JSON 解析和 fallback user 映射都已拆出 helper，继续保持 Anthropic message payload 合同不变。
- `DirectModelAgent.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`normalizeCitedReferences(...)` 现已拆成提取 key、匹配已有元数据、补占位引用、正文重编号与 key 解析 helper，继续保持 citation / available 的去重顺序、重编号规则与占位引用合同不变。
- `WeaviateClient.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`indexDocument(...)` 现已拆成文档清洗、JSON 构造与结果记录 helper，继续保持 Weaviate 请求地址、`SilkContext` 字段、JSON 转义、成功/失败日志与 `false` 回退语义不变。
- `GroupTodoExtractionService.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`dedupeDrafts(...)` 现已拆成“是否参与去重 / logical key 生成 / 候选优先级比较” helper，继续保持模板优先、显式意图优先、`actionDetail` 更长优先与最大返回条数限制的既有合并合同不变。
- `GroupTodoExtractionService.kt` 又移除 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline；`extractRecurringTemplateDrafts(...)` 现已拆成“逐群收集 / 单行判定 / draft 构造 / 标题与时间格式化” helper，继续保持工作日习惯 / 纪念日识别、`matchedLines` 截断、`long_term_template` 合同和 `workday`/`yearly` repeat 语义不变。
- `GroupTodoExtractionService.kt` 又移除 1 条 `CyclomaticComplexMethod` 和 1 条 `NestedBlockDepth` baseline；`heuristicFromSlices(...)` 现已拆成“逐群收集 / 单行候选识别 / 单条 draft 构造 / alarm fallback 状态控制” helper，继续保持 checklist 提取、alarm 文本弱兜底、同一消息只接受一次非 checklist alarm fallback、标题截断与 `alarm`/`none` 判定语义不变。
- `GroupTodoExtractionService.kt` 又移除 1 条 `NestedBlockDepth` baseline；`buildTranscriptString(...)` 现已拆成“逐群追加 / 逐消息展开 / 单行标准化” helper，继续保持 transcript 头格式、逐行 `[sender]: content` 输出、空行跳过与 `MAX_TRANSCRIPT_CHARS` 截断语义不变。
- `AIStepwiseAgent.kt` 已移除 1 条 `CyclomaticComplexMethod` baseline；`generateFallbackReport(...)` 现已拆成“报告头 / 通用 section 拼接 / 仅成功步骤附正文” helper，继续保持章节标题、step key 映射、成功步骤输出顺序与失败步骤留空的既有 fallback 报告合同不变。
- `backend` 已无 `WildcardImport`、`UnusedPrivateMember`、`UnusedParameter`、`EmptyFunctionBlock`、`AcpUpdateMapper.kt` 的 `CyclomaticComplexMethod`、`AIStepwiseAgent.kt` 的 `generateFallbackReport(...)` 复杂度基线、`AnthropicClient.kt` 的 `TooGenericExceptionCaught` / `NestedBlockDepth` / `convertMessage(...)` 复杂度基线、`AsrRoutes.kt` 的 `SwallowedException`、`ChatHistoryBackupManager.kt` 的 `PrintStackTrace` / `SwallowedException`、`ChatHistoryManager.kt` 的 `TooGenericExceptionCaught`、`DirectModelAgent.kt` 的 `normalizeCitedReferences(...)` 复杂度基线、`ExternalSearchService.kt` 的 `TooGenericExceptionCaught`、`FileRoutes.kt` 的 `fileRoutes(...)` / `indexFileToWeaviate(...)` / `chunkText(...)` 复杂度基线、`GroupTodoExtractionService.kt` 的 `buildTranscriptString(...)` / `dedupeDrafts(...)` / `extractRecurringTemplateDrafts(...)` / `heuristicFromSlices(...)` / `extractRoughHourMinute(...)` 与 `ComplexCondition` 基线、`UserTodoStore.kt` 的 `ComplexCondition`、`SearchDrivenAgent.kt` 的 `SwallowedException` / `TooGenericExceptionCaught` / `NestedBlockDepth`、`ToolPolicyManager.kt` 的 `SwallowedException`、`UserTodoStore.kt` 的 `isTemplateDueToday(...)` / `normalizeActionDetailForKey(...)` / `extractTimeFromTitle(...)` / `mergeShortInstanceByState(...)` / `tryMergeByContainedNormTitle(...)` 复杂度基线、`WeaviateClient.kt` 的 `indexDocument(...)` 复杂度基线与 `PrintStackTrace`，以及 `WebSocketConfig.kt` 的 `ComplexCondition` / `PrintStackTrace` / `SwallowedException` baseline 和一条陈旧的 `TooGenericExceptionCaught(ex)` 残留；剩余主要是 `CyclomaticComplexMethod` 8、`TooGenericExceptionCaught` 13、`NestedBlockDepth` 1、`SwallowedException` 3、`LoopWithTooManyJumpStatements` 6、`LargeClass` 8。

## Current Status

- Slice 1-139 完成历史均已归档到 `docs/context/planning/exec-plans/completed/`。
- Android / Web / Desktop / Shared baseline 已清零；active plan 现在只保留 backend 的剩余 detekt 收敛。
- Android 侧既有 `JdkImageTransform` / `jlink` 环境阻塞仍未改变；这不影响 baseline 已清零这一事实。

## Next Slices

- Slice 149 候选：优先继续处理 `GroupTodoExtractionService.kt` 的 `parseCompactTodoJson(...)`，保持 Todo 面同文件、单函数推进，不碰 refresh 主流程。
- Slice 150 候选：如果转向 backend 异常语义，优先处理 `Routing.kt` 中单一路由族、可明确区分 parse / validation 的 catch 点，不直接碰整文件聚合的 `Exception` baseline。
- Slice 151 候选：如果换文件收复杂度，优先找 `Routing.kt` 之外的单职责 backend 文件，不把 `LargeClass` / `LoopWithTooManyJumpStatements` 这类重构型条目混进异常语义 slice。
- 如果某一步发现需要新增 baseline，先停下来判断是否应关规则、补测试或拆小 PR，不要直接把新增项写进 baseline。

## Handoff Notes

- `frontend/androidApp` baseline 已清空；后续 Android 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/webApp` baseline 已清空；后续 Web 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/desktopApp` baseline 已清空；后续 Desktop 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared` baseline 已清空；后续 shared 再出现 lint，只接受“新增问题直接修源码”，不要回填 baseline。
- `frontend/shared/src/iosMain` 当前不在根 detekt source set 中；本计划按当前 lint 覆盖面推进，不把未启用 iOS 源码混进每一步。
- 如果回到 backend，优先选择单文件、单函数、单职责的收敛面，不要再按大范围机械清理切片。
- `Routing.kt` 的 `TooGenericExceptionCaught: ... Exception` 与 `SwallowedException: ... Exception` 现已确认是文件级聚合签名；后续要继续拆异常语义，先按单一路由族收窄，再删 baseline。
- `WebSocketConfig.kt` 当前 broad-catch baseline 仍以文件级签名聚合；后续要拆异常语义时，先选边界最清晰的一组 catch，不要一次性移除整文件同签名 baseline。
