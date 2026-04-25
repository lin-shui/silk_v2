# Web

## Entry Surface

- `frontend/webApp/src/main/kotlin/com/silk/web/Main.kt`
- `AppState.kt`
- `ApiClient.kt`
- `GroupListScene.kt`
- `KnowledgeBaseScene.kt`
- `WorkflowScene.kt`
- `SettingsScene.kt`

## Current Shape

- Compose for Web
- 登录后是左侧 `NavRail` + 右侧内容区
- 主 Tab：
  - Silk
  - Workflow
  - Knowledge Base

## Build-Time Facts

- 从 `.env` 读取后端端口并生成 `BuildConfig.kt`
- 生产构建最终供后端静态分发
- JS 轻量测试跑 `nodeTest`，不依赖浏览器自动化

## Watch Points

- 改文件消息/下载逻辑时，优先看 `FileContracts.kt` / `FileContractsTest.kt`
- 改布局壳层时，确认 `AppState.kt` 与 `Main.kt` 的 scene/tab 状态流
- 工作流面板（`WorkflowScene.kt`）含 Folder Picker：
  - header 显示 agent 名（取自 `Message.userName`）和当前工作目录
  - "更改" 链接 / 创建工作流的"选择…" 按钮 → `FolderPickerDialog`（面包屑 + `..` + 子目录 + 手动输入）
  - 切目录走 HTTP `cdCcDir`（不发聊天 `/cd` 气泡）；FolderPicker 内部用 `loadJob` 取消旧请求避免 stale 覆盖
  - 共用 `ModalOverlay` composable；后端 `DirListingResponse.separator` 字段决定路径拼接，前端不猜 Unix vs Windows
