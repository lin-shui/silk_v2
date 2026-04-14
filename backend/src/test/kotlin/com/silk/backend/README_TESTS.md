# Backend Tests

当前快检分成三类：

- `BackendHttpContractTest`：注册、登录、用户设置、群组创建/加入、消息撤回、Todo HTTP 合同。
- `BackendFileContractTest`：文件上传、重名处理、列表、下载、删除合同，以及 `processed_urls.txt` 本地路径分支。
- `BackendWebSocketContractTest`：群组 WebSocket 入群鉴权、历史回放、广播持久化、未读计数，以及本地 URL/PDF 入口成功分支、404/非支持内容类型、读超时、连接拒绝、损坏 PDF 等失败分支的下载去重、状态回显与落盘 smoke。
- `ai/DirectModelAgentToolPolicyTest`：AI 工具暴露面、会话作用域、路径拒绝与审计结果。
- `utils/WebPageDownloaderSmokeTest`：URL 提取去重、本地 HTML/PDF 下载提取与落盘 smoke，不依赖外网。
- `UserTodoStoreTest`：待办去重、重开、模板实例化等核心生命周期逻辑。
- `claudecode/StreamParserTest`：Claude Code Bridge 元信息格式化单测。

运行方式：

```bash
./gradlew :backend:test
```

新增测试时保持两点：

- 优先写能直接拦截回归的真实接口/逻辑测试，避免字符串占位测试。
- 使用 `TestWorkspace` 隔离 SQLite 与 `chat_history`，不要把测试产物写回仓库根目录。
