# Lint Baseline Reduction

## Goal

在已经接入 `./gradlew silkLint` 的基础上，继续把 detekt baseline 转化为源码修复。原则保持不变：baseline 只减不增，每一步都能独立验证、独立 review。

## Affected Surfaces

- `config/lint/detekt/*.xml`
- `frontend/androidApp/src/main/kotlin/com/silk/android/`
- `frontend/webApp/src/main/kotlin/com/silk/web/`
- 后续候选：`backend/src/main/kotlin/com/silk/backend/`

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

- `frontend/androidApp`：`./gradlew :frontend:androidApp:detekt`
- Android Kotlin 变更：`./gradlew :frontend:androidApp:compileDebugKotlin`
- Android 单测：`./gradlew :frontend:androidApp:testDebugUnitTest`
- backend 改动：`./gradlew :backend:test`
- web 改动：`./gradlew :frontend:webApp:nodeTest`

## Current Snapshot

当前 detekt baseline 余量（2026-05-28，Slice 44 后）：

- `backend.xml`: 186
- `frontend-androidApp.xml`: 53
- `frontend-webApp.xml`: 0
- `frontend-shared.xml`: 7
- `frontend-desktopApp.xml`: 0

当前关键分布：

- `frontend/androidApp` 已无 `WildcardImport` 与 `InstanceOfCheckForException` baseline，剩余主要是 `CyclomaticComplexMethod` 22、`TooGenericExceptionCaught` 10、`SwallowedException` 5。
- `frontend/webApp` 已清空 detekt baseline；`ApiClient.kt` 的异常恢复已统一收敛到 `recoverApiCall(...)` helper。
- `backend` 已无 `WildcardImport` baseline；剩余主要是 `CyclomaticComplexMethod` 34、`TooGenericExceptionCaught` 33、`ConstructorParameterNaming` 16、`NestedBlockDepth` 14、`UnusedPrivateMember` 13。

## Current Status

- Slice 1-16 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slices-1-16.md](../completed/2026-05-21-lint-baseline-reduction-slices-1-16.md)。
- Slice 17-18 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slices-17-18.md](../completed/2026-05-21-lint-baseline-reduction-slices-17-18.md)。
- Slice 19 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slice-19.md](../completed/2026-05-21-lint-baseline-reduction-slice-19.md)。
- Slice 20 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slice-20.md](../completed/2026-05-21-lint-baseline-reduction-slice-20.md)。
- Slice 21 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slice-21.md](../completed/2026-05-21-lint-baseline-reduction-slice-21.md)。
- Slice 22 的完成历史已归档到 [2026-05-21-lint-baseline-reduction-slice-22.md](../completed/2026-05-21-lint-baseline-reduction-slice-22.md)。
- Slice 23 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-23.md](../completed/2026-05-25-lint-baseline-reduction-slice-23.md)。
- Slice 24 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-24.md](../completed/2026-05-25-lint-baseline-reduction-slice-24.md)。
- Slice 25 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-25.md](../completed/2026-05-25-lint-baseline-reduction-slice-25.md)。
- Slice 26 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-26.md](../completed/2026-05-25-lint-baseline-reduction-slice-26.md)。
- Slice 27 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-27.md](../completed/2026-05-25-lint-baseline-reduction-slice-27.md)。
- Slice 28 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-28.md](../completed/2026-05-25-lint-baseline-reduction-slice-28.md)。
- Slice 29 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-29.md](../completed/2026-05-25-lint-baseline-reduction-slice-29.md)。
- Slice 30 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-30.md](../completed/2026-05-25-lint-baseline-reduction-slice-30.md)。
- Slice 31 的完成历史已归档到 [2026-05-25-lint-baseline-reduction-slice-31.md](../completed/2026-05-25-lint-baseline-reduction-slice-31.md)。
- Slice 32 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-32.md](../completed/2026-05-26-lint-baseline-reduction-slice-32.md)。
- Slice 33 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-33.md](../completed/2026-05-26-lint-baseline-reduction-slice-33.md)。
- Slice 34 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-34.md](../completed/2026-05-26-lint-baseline-reduction-slice-34.md)。
- Slice 35 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-35.md](../completed/2026-05-26-lint-baseline-reduction-slice-35.md)。
- Slice 36 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-36.md](../completed/2026-05-26-lint-baseline-reduction-slice-36.md)。
- Slice 37 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-37.md](../completed/2026-05-26-lint-baseline-reduction-slice-37.md)。
- Slice 38 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-38.md](../completed/2026-05-26-lint-baseline-reduction-slice-38.md)。
- Slice 39 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-39.md](../completed/2026-05-26-lint-baseline-reduction-slice-39.md)。
- Slice 40 的完成历史已归档到 [2026-05-26-lint-baseline-reduction-slice-40.md](../completed/2026-05-26-lint-baseline-reduction-slice-40.md)。
- Slice 41 的完成历史已归档到 [2026-05-28-lint-baseline-reduction-slice-41.md](../completed/2026-05-28-lint-baseline-reduction-slice-41.md)。
- Slice 42 的完成历史已归档到 [2026-05-28-lint-baseline-reduction-slice-42.md](../completed/2026-05-28-lint-baseline-reduction-slice-42.md)。
- Slice 43 的完成历史已归档到 [2026-05-28-lint-baseline-reduction-slice-43.md](../completed/2026-05-28-lint-baseline-reduction-slice-43.md)。
- Slice 44 的完成历史已归档到 [2026-05-28-lint-baseline-reduction-slice-44.md](../completed/2026-05-28-lint-baseline-reduction-slice-44.md)。
- Android 侧已知 `jlink` / `JdkImageTransform` 阻塞保持不变；当前 active plan 继续优先选择不依赖该链路的窄 slice。

## Next Slices

- Slice 45 候选：继续留在 Android，优先处理 `AudioDuplexScreen.kt` 的 `TooGenericExceptionCaught` / `SwallowedException`，因为它同时还挂着 `LoopWithTooManyJumpStatements`，适合先只切异常恢复面、不要把循环复杂度一起摊开。
- Slice 46 候选：如果回到 backend，下一轮不要再按“大范围机械清理”切；优先选单文件的 `UnusedPrivateMember` / `UnusedParameter` 或明确异常语义问题。
- `frontend/webApp` baseline 已清空；后续 web 再出现 lint 时只接受“新增问题直接修源码”，不要回填 baseline。
- 复杂度规则继续按单文件慢拆，不和异常语义 / import 收敛混在同一 slice。

## Handoff Notes

- `frontend/desktopApp` 已清空 detekt baseline；如果后续 desktop 再出现 lint，只接受“新增问题直接修源码”，不要再回填 baseline。
- `frontend/shared/src/iosMain` 当前不在根 detekt source set 中；本计划按当前 lint 覆盖面推进，不把未启用 iOS 源码混进每一步。
- `frontend/webApp/src/main/kotlin/com/silk/web/ApiClient.kt` 已抽出 `recoverApiCall(...)`；后续新增 web API 方法优先复用它，保持 cancellation 透传和 fallback 语义一致，不要再回到整文件 `catch (Exception)`。
- `frontend/androidApp/src/main/kotlin/com/silk/android/ApiClient.kt` 已抽出同名 `recoverApiCall(...)`；后续新增 Android API 方法优先复用它，保持 `Dispatchers.IO`、cancellation 透传和 fallback 语义一致，不要再回到整文件 `catch (Exception)`。
- `frontend/webApp` 里的 transient/system-message 复杂条件已抽成共享 helper；后续同类判断优先复用 `shouldRenderInlineTransientMessage(...)` 与 `isWorkflowAgentLifecycleMessage(...)`，避免把条件重新写散。
- `ContactsScene.kt` 已经把顶部栏、加载态、待处理请求区、联系人区拆成独立 composable；后续改联系人页时优先在这些 section helper 上扩展，不要把条件再塞回 `ContactsScene(...)`。
- `SettingsScene.kt` 已经把顶部栏、语言/默认指令区、CC Bridge 区块、保存提示和底部按钮拆成独立 helper；后续继续加设置项时优先往这些 section 扩展，不要把分支重新堆回主 composable。
- `KnowledgeBaseScene.kt` 已经把主题栏、条目栏、编辑器与创建弹窗拆成独立 helper；后续改知识库页时优先复用这些 section 和文件级动作 helper，不要再把保存/导出/创建分支塞回主 composable。
- `AudioDuplexScene.kt` 已经把 transcript pane、空态、状态文案和拨号按钮拆成独立 helper；后续改音频双工页时优先沿着这些 helper 扩展，不要回退成单函数堆逻辑。
- `Main.kt` 的 `ChatScene(...)` 已经把 missing-context、sidebar header/content/card、unread badge，以及 sidebar 刷新/未读轮询 effect 拆成独立 helper；后续改聊天页外围群组 sidebar 时优先沿这些 helper 扩展，不要把分支重新塞回 `ChatScene(...)`。
- `Main.kt` 的 `ChatAppWithGroup(...)` 已经把 language/session effects、顶部 header、选择模式工具栏、常规操作工具栏、连接状态条，以及转发/成员邀请/上传输入等 overlays 拆成独立 helper；后续改聊天页 host 层时优先沿这些 helper 扩展，不要把 toolbar、dialog 和 upload 分支重新塞回主 composable。
- `Main.kt` 的 `MembersDialog(...)` 已经把 overlay、surface、状态体和单成员行拆成独立 helper，并把成员交互状态抽成 `MembersDialogMemberState`；后续改成员弹窗时优先沿这些 helper 扩展，不要把成员判定和 icon/status 分支重新塞回主 dialog。
- `Main.kt` 的 `MessageItem(...)` 已经收敛成 render-mode dispatch，普通文本、文件消息、系统提示、卡片回复摘要，以及选择态卡片壳层/下载动作都已拆成独立 helper；后续改消息项时优先沿这些 helper 扩展，不要把分支重新塞回主 dispatch。
- `GroupListScene.kt` 已经把顶层 scene 编排拆成 effect、header、content、overlay 与小型 suspend helper，且 create/join dialog 壳层、输入区、错误区、按钮区，以及成员弹窗内容区/成员 display helper 都已独立；后续继续改群组页时优先沿这些 helper 扩展，不要把条件重新塞回顶层 scene 或对话框主 composable。
- `WorkflowScene.kt` 的 `FolderPickerDialog(...)` 已经把 header、breadcrumbs、列表区和 footer 拆成独立 helper；后续继续改目录选择器时优先沿这些 helper 扩展，不要把 UI 分支重新塞回主 dialog。
- `WorkflowScene.kt` 的 `WorkflowChatPanel(...)` 已经把 header、消息区、badge/dropdown、输入区和目录/信任弹窗入口拆成独立 helper；后续继续改工作流聊天面板时优先在这些 helper 上扩展，不要把 agent 切换、目录切换和发送逻辑重新堆回主 composable。
- `WorkflowScene.kt` 的 `WorkflowScene(appState: WebAppState)` 已经把左侧列表、右侧主面板、创建流程和管理弹窗编排拆成独立 helper；后续继续改工作流 scene 时优先在这些 host/helper 上扩展，不要把 create/trust/menu/rename/delete 分支重新塞回顶层 scene。
- 如果某一步发现需要新增 baseline，先停下来判断是否应关规则、补测试或拆小 PR，不要直接把新增项写进 baseline。
