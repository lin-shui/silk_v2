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
- 改元服务/购物跳转时，同时看 `common/MetaServiceLauncher.ets`、`common/MetaServiceConfig.ets`、`common/ShoppingLauncher.ets`

## 元服务（Meta-Service / Atomic Service）

Harmony 端支持通过 `startAbility` 调用鸿蒙元服务，集成在 Todo 执行流程中。

### 支持的服务

| 服务 | ID | 类型 | 参数 | 浏览器回退 |
|------|----|------|------|-----------|
| 京东购物 | `jd_shopping` | search | keywords | 是 |
| 淘宝 | `taobao_shopping` | search | keywords | 是 |
| 美团 | `meituan_shopping` | search | keywords | 是 |
| 拼多多 | `pdd_shopping` | search | keywords | 是 |
| T3出行 | `t3_ride` | parameter | pickup, destination | 否 |

### 架构

- `MetaServiceConfig.ets` — 元服务注册中心（bundleName 候选、URI scheme 模板、参数定义、触发关键词）
- `MetaServiceLauncher.ets` — 统一启动引擎，三层降级：原生元服务 → URI scheme → 浏览器（仅搜索型）
- `ShoppingLauncher.ets` — 兼容旧接口，委派到 MetaServiceLauncher
- `ShoppingIntentParser.ets` — 文本意图解析（购物 + 出行）

### 启动流程

```
actionType === 'shopping' → ShoppingIntentParser.parse()
  ├── ride_hailing → MetaServiceLauncher.launch(t3_ride)
  └── shopping    → ShoppingLauncher.launchShopping() → MetaServiceLauncher.launch()

actionType === 'meta_service' → executeMetaService()
  └── actionDetail JSON { service, params } → MetaServiceLauncher.launch()
```

### Bundle 名验证

当前 bundleName 为最佳猜测，需要在鸿蒙 NEXT 真机上用 `hdc shell aa dump -l` 确认后更新 `MetaServiceConfig.ets`。
