# Lint Baseline Reduction

## Goal

在已经接入 `./gradlew silkLint` 的基础上，继续把 detekt baseline 转化为源码修复。原则保持不变：baseline 只减不增，每一步都能独立验证、独立 review。

## Affected Surfaces

- `config/lint/detekt/*.xml`
- `frontend/androidApp/src/main/kotlin/com/silk/android/`
- 后续候选：`backend/src/main/kotlin/com/silk/backend/`、`frontend/webApp/src/main/kotlin/com/silk/web/`

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

当前 detekt baseline 余量（2026-05-25，Slice 31 后）：

- `backend.xml`: 186
- `frontend-androidApp.xml`: 57
- `frontend-webApp.xml`: 15
- `frontend-shared.xml`: 7
- `frontend-desktopApp.xml`: 0

当前关键分布：

- `frontend/androidApp` 已无 `WildcardImport` baseline，剩余主要是 `CyclomaticComplexMethod` 22、`TooGenericExceptionCaught` 11、`SwallowedException` 7。
- `frontend/webApp` 已无 `WildcardImport`、`UnusedParameter`、`EmptyCatchBlock`、`UseCheckOrError`、`LoopWithTooManyJumpStatements` 与 `ComplexCondition` baseline；剩余主要是 `CyclomaticComplexMethod` 13，以及 `ApiClient.kt` 上 2 条签名级异常语义 baseline。
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
- Android 侧已知 `jlink` / `JdkImageTransform` 阻塞保持不变；当前 active plan 继续优先选择不依赖该链路的窄 slice。

## Next Slices

- Slice 32 候选：继续留在 `frontend/webApp`，优先挑 `GroupListScene.kt` 里单独的对话框级复杂度项（先看 `JoinGroupDialog(...)` 或 `GroupMembersListDialog(...)`），暂时继续避开 `Main.kt` / `WorkflowScene.kt` / `GroupListScene(...)` 主场景大函数。
- Slice 33 候选：回到 Android，优先处理 `ApiClient.kt`、`AudioDuplexScreen.kt`、`ChatScreen.kt` 的异常语义规则（`TooGenericExceptionCaught` / `SwallowedException`），但继续避开会把整文件泛 catch 一次性摊开的重构。
- 如果继续留在 backend，下一轮不要再按“大范围机械清理”切；优先选单文件的 `UnusedPrivateMember` / `UnusedParameter` 或明确异常语义问题。
- 复杂度规则继续按单文件慢拆，不和异常语义 / import 收敛混在同一 slice。

## Handoff Notes

- `frontend/desktopApp` 已清空 detekt baseline；如果后续 desktop 再出现 lint，只接受“新增问题直接修源码”，不要再回填 baseline。
- `frontend/shared/src/iosMain` 当前不在根 detekt source set 中；本计划按当前 lint 覆盖面推进，不把未启用 iOS 源码混进每一步。
- `frontend/webApp/src/main/kotlin/com/silk/web/ApiClient.kt` 的 `TooGenericExceptionCaught` / `SwallowedException` 当前是签名级 baseline；除非准备成组改整文件的泛 catch，否则不要再尝试按单方法直接删这两条。
- `frontend/webApp` 里的 transient/system-message 复杂条件已抽成共享 helper；后续同类判断优先复用 `shouldRenderInlineTransientMessage(...)` 与 `isWorkflowAgentLifecycleMessage(...)`，避免把条件重新写散。
- `ContactsScene.kt` 已经把顶部栏、加载态、待处理请求区、联系人区拆成独立 composable；后续改联系人页时优先在这些 section helper 上扩展，不要把条件再塞回 `ContactsScene(...)`。
- `SettingsScene.kt` 已经把顶部栏、语言/默认指令区、CC Bridge 区块、保存提示和底部按钮拆成独立 helper；后续继续加设置项时优先往这些 section 扩展，不要把分支重新堆回主 composable。
- `KnowledgeBaseScene.kt` 已经把主题栏、条目栏、编辑器与创建弹窗拆成独立 helper；后续改知识库页时优先复用这些 section 和文件级动作 helper，不要再把保存/导出/创建分支塞回主 composable。
- `AudioDuplexScene.kt` 已经把 transcript pane、空态、状态文案和拨号按钮拆成独立 helper；后续改音频双工页时优先沿着这些 helper 扩展，不要回退成单函数堆逻辑。
- 如果某一步发现需要新增 baseline，先停下来判断是否应关规则、补测试或拆小 PR，不要直接把新增项写进 baseline。
