# CI Fast Validation Scope

## 目标

这个 CI 用于 `push`、`pull_request`、`merge_group`、`workflow_dispatch` 的快速拦截。

只放三类检查：

- GitHub-hosted runner 上稳定可跑的检查
- 能在较短时间内发现高概率回归的检查
- 对“新提交不要明显破坏已有功能”有直接价值的检查

不把它做成全量发布流水线；慢、重、依赖专用环境的项放到后续专用 CI。

## 当前基线（2026-04-20）

工作流文件：`.github/workflows/ci-fast-validation.yml`

### 已覆盖

工程与构建：

- [x] Gradle 根工程可配置
- [x] `:backend:test`
- [x] `:backend:shadowJar`
- [x] `:frontend:webApp:compileProductionExecutableKotlinJs`
- [x] `:frontend:desktopApp:compileKotlin`
- [x] `:frontend:androidApp:compileDebugKotlin`
- [x] `bash -n silk.sh`
- [x] `./silk.sh status` smoke

backend 真实快检：

- [x] 注册 / 登录 / 用户校验 HTTP 合同
- [x] 用户设置读取 / 更新 HTTP 合同
- [x] 群组创建 / 入群 / 成员列表 HTTP 合同
- [x] 文件上传 / 重名处理 / 列表 / 下载 / 删除 HTTP 合同
- [x] 上传文件 `FILE` 消息 payload 广播与历史回放合同
- [x] `FILE` 消息特殊字符文件名 JSON 转义与 URL 编码合同
- [x] URL 提取去重与非网页资源过滤
- [x] 本地 HTML 下载提取与落盘 smoke
- [x] 本地 PDF 下载提取与落盘 smoke
- [x] WebSocket 入群鉴权
- [x] WebSocket 最近 50 条历史回放
- [x] WebSocket 文本广播与持久化
- [x] WebSocket 本地 URL/PDF 检测、去重、状态回显与落盘 smoke
- [x] WebSocket URL 下载失败分支（404 / 非支持内容类型）状态回显与“不落盘”约束
- [x] WebSocket URL 下载异常分支（读超时 / 连接拒绝 / 损坏 PDF）状态回显与“不落盘”约束
- [x] WebSocket URL 下载产物文件消息广播与历史回放
- [x] 未读计数与 `mark-read` 链路
- [x] AI 工具暴露面过滤（禁用工具不暴露给模型）
- [x] AI 工具会话作用域拒绝（空作用域 / 非当前会话）
- [x] AI 工具路径拒绝与审计结果一致
- [x] 消息撤回 HTTP 合同（发送者权限、普通消息撤回、`@silk` 连带回复删除）
- [x] 用户 Todo 列表 / 更新 / 删除 HTTP 合同
- [x] Todo 生命周期：done 重开、cancelled 重开门槛、逻辑去重、月度模板实例化
- [x] Claude Code Bridge 元信息格式化单测

### 本次补齐的点

- 删除了几组只校验字符串/JSON 形状的占位测试，改为真实后端合同测试。
- 把消息撤回从 JSON 形状占位校验改成真实路由合同测试，并修正了测试环境的 `chat_history` 隔离。
- 测试现在使用临时 SQLite 和临时 `chat_history` 目录，避免把测试产物写回仓库根目录。
- 文件路由改为尊重 `silk.chatHistoryDir`，并在未配置 Weaviate 时直接跳过索引，避免快检被外部配置缺失拖垮。
- 新增文件路由合同测试，覆盖上传、重名处理、列表、下载、删除，以及 `processed_urls.txt` 本地路径分支。
- 新增上传文件 `FILE` 消息合同测试，锁定 `fileName` / `fileSize` / `downloadUrl` payload、实时广播和后加入成员历史回放。
- 新增 `FILE` 消息特殊字符文件名合同测试，覆盖空格、`#`、`?`、引号场景，锁定 JSON 转义、URL 编码、实时广播、历史回放和文件列表 `downloadUrl` 输出。
- 新增 `WebPageDownloader` 本地 smoke，使用进程内 HTTP server 覆盖 URL 提取、HTML/PDF 下载提取与落盘，不依赖外网和外部服务。
- 新增 WebSocket 合同测试，覆盖群成员鉴权、最近 50 条历史回放、实时广播持久化和未读计数主链路。
- 新增 WebSocket URL 入口 smoke，覆盖“聊天消息带本地 HTML/PDF 链接 -> 下载提取 -> `processed_urls.txt` 去重 -> 状态消息回显 -> 文件落盘”主链路。
- 新增 WebSocket URL 失败分支 smoke，覆盖本地 404 / 非支持内容类型时的状态消息，以及失败后不写 `processed_urls.txt` / 不产生落盘文件。
- 给 `WebPageDownloader` 增加测试可控的连接 / 读取超时参数，并新增 WebSocket URL 异常分支 smoke，覆盖读超时、连接拒绝、损坏 PDF 时的状态消息和“不落盘”约束。
- 把 URL 下载成功后的产物接成真实 `FILE` 消息，新增 WebSocket 合同测试覆盖实时文件消息广播、历史入库、后加入成员回放，以及重复 URL 不重复广播文件消息。
- 新增 AI 工具权限测试，锁定禁用工具暴露面、会话作用域拒绝，以及路径拒绝时的审计结果。
- Claude Code 已切到 Bridge Agent 架构后，移除了失效的旧 session store / stream parser JVM 单测，保留当前后端仍承担的元信息格式化测试。
- 把 `silk.sh` 的基础语法校验和只读 `status` smoke 接进了快检。
- 把 `:backend:shadowJar` 和产物 artifact 上传接进快检，补上交付装配层拦截。
- Android 文件夹浏览改为按 JSON 解析文件列表并直接使用后端返回的 `downloadUrl`，避免正则解析和手拼 URL 在特殊字符文件名下失真。

### 明确未覆盖

- [ ] WebSocket AI/Claude Code 触发链路、断线重连恢复、异常网络行为
- [ ] AI 工具完整端到端 tool-calling（真实模型响应、外部搜索、Weaviate）
- [ ] 外部真实站点 URL/PDF 抓取、异常响应处理，以及 Weaviate 索引链路
- [ ] Harmony HAP 构建
- [ ] `silk.sh build/start/deploy` 级别的完整脚本 smoke

## 运行备注

- Android job 会显式安装 SDK 并生成 `local.properties`，避免 runner 环境差异导致评估期直接失败。
- Gradle wrapper 在 GitHub-hosted runner 上会临时改回 `services.gradle.org`，避免镜像可达性造成非业务失败。
- 目前 `:backend:test` 已经是有意义的快检入口；后续新增 backend 能力时，优先往这里补真实测试，不要再加占位测试。
- 文件相关快检默认不依赖外部 Weaviate；未配置时只验证本地路径和 HTTP 合同，不把外部索引可用性混进基础快检。
- `WebPageDownloader` smoke 在测试里显式关闭 Playwright，固定走本地 HTTP 路径，避免浏览器运行时差异把基础快检变成环境问题。
- WebSocket URL 入口 smoke 同样固定走本地 HTTP 路径，并使用进程内 HTTP server 提供 HTML/PDF，避免外网和浏览器环境波动影响快检稳定性。
- WebSocket URL 失败分支也走同一套本地 fixture，确保失败行为校验的是业务分支，不是外部网络抖动。
- 下载超时在测试里通过系统属性显式缩短，避免为了验证超时分支把 CI 挂成几十秒。

## 下一步建议

1. 给 Android/Web 文件列表和文件卡片各补一层轻量解析测试，避免特殊字符文件名回归只在运行时暴露。
2. AI 端到端另起一层可选 smoke，专门验证模型 tool-calling 回路，不阻塞基础快检。
3. Harmony HAP 另起独立 workflow，放到自托管或预置 DevEco/hvigor 环境的 runner。
4. `silk.sh build/start/deploy` 另起脚本级 smoke，验证交付脚本而不是只验只读 `status`。
