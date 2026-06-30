# Android

## Entry Surface

- `frontend/androidApp/src/main/kotlin/com/silk/android/MainActivity.kt`
- `AppState.kt`
- `ApiClient.kt`
- `ChatScreen.kt`
- `GroupListScreen.kt`
- `WorkflowScreen.kt`
- `WorkflowChatScreen.kt`
- `WorkflowDialogs.kt`
- `CardMessageRenderer.kt`
- `KnowledgeBaseScreen.kt`
- `AudioDuplexScreen.kt`
- `SettingsScreen.kt`

## Current Shape

- Jetpack Compose + Material 3
- 登录后主壳是底部四 Tab
- 聊天页 / 工作流会话页隐藏底栏
- 工作流会话页（`WorkflowChatScreen.kt`）复用 `com.silk.shared.ChatClient`，通过 `wf.groupId` 走 `/chat` WebSocket
- 工作流会话页支持 `MessageType.CARD` 交互卡片渲染与 `CARD_REPLY` 回复发送
- 包含版本检查 / APK 下载 / 文件处理 / ASR / Audio Duplex

## Build-Time Facts

- `build.gradle.kts` 会按构建时间生成 `versionCode` / `versionName`
- `BuildConfig.BACKEND_BASE_URL` 从 `.env` 或 Gradle 属性注入
- CI 只跑：
  - `testDebugUnitTest`
  - `compileDebugKotlin`

## Watch Points

- 改文件 payload 时看 `FileContracts.kt` / `FileContractsTest.kt`
- 改导航壳层时看 `AppState.kt` 与 `MainActivity.kt`
- 改后端地址逻辑时同时检查 `.env.example`
- 改 Audio Duplex 时看 `AudioDuplexScreen.kt` 与后端 `/ws/audio-duplex`
- 改工作流目录信任 / Bridge 交互流程时看 `WorkflowDialogs.kt`（`FolderPickerDialog` / `TrustConfirmDialog`）和 `WorkflowChatScreen.kt`，与 Web 端 `WorkflowScene.kt` 行为对齐
- Android Knowledge Base 不再只是纯标题列表：
  - topic 列表顶部支持“个人 + 我所在群组”空间切换，创建主题会落到当前选中空间
  - topic / entry 卡片会显示空间、只读/可编辑、条目状态与来源 badge
  - entry 列表支持 `全部 / 候选 / 已发布 / 已归档` 状态筛选；候选/已发布/已归档条目可在编辑页直接做发布、归档、重新发布
  - entry 页顶部支持“会议入库”，通过统一 `POST /api/kb/captures` 契约把会议纪要存成 `MEETING` 来源的 `candidate` 或 `published` 条目
  - topic 无写权限时，创建条目与保存 Markdown 会禁用，并在编辑页显式提示只读
  - 条目编辑页会展开最小 provenance 明细：来源群组、workflowId、消息 id 摘要、置信度、创建人/更新人
