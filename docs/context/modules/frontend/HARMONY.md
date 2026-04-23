# Harmony

## Entry Surface

- `frontend/harmonyApp/entry/src/main/ets/pages/`
- `frontend/harmonyApp/entry/src/main/ets/api/`
- `frontend/harmonyApp/entry/src/main/ets/components/`
- `frontend/harmonyApp/entry/src/main/ets/stores/`

## Current Shape

- 独立 ArkTS / ArkUI 应用
- 不复用 `frontend/shared`
- 页面包含：
  - `MainPage.ets`
  - `ChatPage.ets`
  - `WorkflowPage.ets`
  - `KnowledgeBasePage.ets`
  - `TodoPage.ets`
  - `SettingsPage.ets`

## Tooling Facts

- 构建入口：`frontend/harmonyApp/hvigorfile.ts`
- 依赖文件：`oh-package.json5`, `entry/oh-package.json5`
- 仓库已有 Cursor 规则要求改 Harmony 代码后执行 sync + assembleHap + install
- 签名与 HAP 安装注意事项见 `frontend/harmonyApp/README.md`

## Watch Points

- 改 Todo 时，Harmony 是真实主承载端之一
- 改网络协议时，同时看 `api/ApiClient.ets` 与 `api/WebSocketClient.ets`
- 改 Markdown / KaTeX 渲染时，看 `components/MarkdownWeb.ets`, `MarkdownLite.ets`, `MathKatexWeb.ets`
