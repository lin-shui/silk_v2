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

当前 detekt baseline 余量（2026-06-08，Slice 116 后）：

- `backend.xml`: 81
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
- `AIConfig.kt`、`AcpWebSocketTransport.kt`、`SilkAgent.kt` 与 `AnthropicClient.kt` 已移除 5 条 `UseCheckOrError` 和 1 条 `UseRequire` baseline；这些前置校验现统一改为 `check` / `checkNotNull` / `require`。
- `AiModels.kt`、`AIStepwiseAgent.kt`、`GroupTodoExtractionService.kt` 与 `AcpCapabilities.kt` 已额外移除 7 条 `ConstructorParameterNaming` baseline；消息模型、AI 请求体与 ACP `_silk` 扩展能力声明现统一改为 `camelCase + @SerialName(...)`，不改变现有协议字段。
- `SearchDrivenAgent.kt` 的两处 HTTP 非 200 响应抛错已从 `Exception(...)` 收紧为 `error(...)`；`TooGenericExceptionThrown` baseline 再减 1 条，不改变上层 fallback 行为。
- `TrustedDirManager.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；trusted dir store 读取现按反序列化 / I/O 分层回退，路径规范化失败也只在非法路径、权限或 I/O 失败时退回原始路径比较，不改变 trust 判定合同。
- `UserRepository.kt` 已移除 1 条 `TooGenericExceptionCaught` baseline；`createUser()` 现在只对 `ExposedSQLException` / `SQLException` 这类数据库写入失败回退 `null`，唯一键冲突分支也已补测试锚定，不改变注册失败合同。
- `Routing.kt` 已移除 1 条 `SwallowedException: ... CancellationException` baseline；Agent Bridge / group chat / audio duplex 的正常取消不再静默吞掉，日志也补到了取消与关闭失败路径。
- `FileRoutes.kt` 已移除 1 条 `UnusedPrivateProperty`、1 条 `ImplicitDefaultLocale` 与 1 条 `ForbiddenComment` baseline；文件路由不再保留未接线的删除索引 TODO，APK/HAP 版本号格式化也显式固定到 `Locale.ROOT`。
- `ChatHistoryManager.kt` 与 `ClaudeProcessClient.kt` 已移除 2 条 `UnusedPrivateProperty` baseline；孤立未接线局部值和私有属性已删除，不影响撤回链路或 Claude PTY 调用。
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
- `backend` 已无 `WildcardImport`、`UnusedPrivateMember`、`UnusedParameter`、`EmptyFunctionBlock`、`AsrRoutes.kt` 的 `SwallowedException`、`ChatHistoryBackupManager.kt` 的 `PrintStackTrace` / `SwallowedException`、`WeaviateClient.kt` 的 `PrintStackTrace`，以及 `WebSocketConfig.kt` 的 `ComplexCondition` / `PrintStackTrace` / `SwallowedException` baseline 和一条陈旧的 `TooGenericExceptionCaught(ex)` 残留；剩余主要是 `CyclomaticComplexMethod` 29、`TooGenericExceptionCaught` 19、`NestedBlockDepth` 11、`SwallowedException` 5、`LoopWithTooManyJumpStatements` 7、`LargeClass` 8。

## Current Status

- Slice 1-116 完成历史均已归档到 `docs/context/planning/exec-plans/completed/`。
- Android / Web / Desktop / Shared baseline 已清零；active plan 现在只保留 backend 的剩余 detekt 收敛。
- Android 侧既有 `JdkImageTransform` / `jlink` 环境阻塞仍未改变；这不影响 baseline 已清零这一事实。

## Next Slices

- Slice 117 候选：优先继续处理 `Routing.kt` 中单一路由族、可明确区分 parse / validation 的异常语义点，不直接碰整文件聚合的 `Exception` baseline。
- Slice 118 候选：如果继续 backend 复杂度，优先按单函数慢拆，不和异常语义 / import 收敛混在同一 slice。
- Slice 119 候选：如果 backend 异常语义继续推进，优先选单文件里的同类 catch / swallow 点做小批量收敛，不要横跨多个模块。
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
