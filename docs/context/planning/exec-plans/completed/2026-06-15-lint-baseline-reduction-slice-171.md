# Lint Baseline Reduction Slice 171

这份归档保留 `lint-baseline-reduction` 的 Slice 171 完成历史，记录本轮在 `backend/src/main/kotlin/com/silk/backend/Routing.kt` 上清理 detekt 的 `CyclomaticComplexMethod` 基线。活跃待办留在 [active/lint-baseline-reduction.md](../active/lint-baseline-reduction.md)。

## Result

- `config/lint/detekt/backend.xml` 从 9 条降到 8 条。
- 删除 `Routing.kt$fun Application.configureRouting()` 对应的 1 条 `CyclomaticComplexMethod` baseline。
- `configureRouting()` 现在只保留 AgentRuntime persistence wiring 与 73 个 `Route.register...()` helper 的挂载顺序。
- 拆分后仍偏复杂的 Bridge FS list / cd、CC settings update、workflow create 与 chat WebSocket handler 继续下沉到小 helper，避免新增复杂度 baseline。

## Behavior Notes

- 保持原 HTTP 路径、状态码、响应体、广播文案和 WebSocket 行为不变。
- Bridge dir listing 的 JSON 到 `DirListingResponse` 转换抽到纯 helper。
- `/users/{userId}/cc-fs/cd` 的成功响应、目录切换系统消息广播和状态响应抽到 helper。
- `/users/{userId}/cc-settings/update` 的 agent / permission mode 应用、系统消息广播和最终状态响应抽到 helper。
- `/api/workflows` 创建请求解析与错误响应构造抽到 helper，继续保持 silk_chat 与 bridge workflow 分支语义不变。
- `/chat` 的 workflow agent 自动激活、pending question 回放和 incoming frame 解析抽到 helper。

## Validation

- `./gradlew :backend:detekt --no-daemon --warning-mode none --console=plain --rerun-tasks`
- `./gradlew :backend:test --no-daemon --warning-mode none --console=plain`
- `./gradlew silkLint --no-daemon --warning-mode none --console=plain`

## Follow-up

- backend detekt baseline 只剩 8 条 `LargeClass`，属于结构性收敛；后续应按模块边界拆分，并为每个切片补对应验证。
