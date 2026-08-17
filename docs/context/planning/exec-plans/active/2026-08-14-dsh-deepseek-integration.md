# Silk 内置 AI 接入 DeepSeek Harness（dsh）执行计划

状态：P0/P1 完成，P2（沙箱 + 进程生命周期）完成，P3（Silk 受控 web_fetch）待做。

## 目标

Silk 内置 AI（`DirectModelAgent`）的模型调用层换成 DeepSeek 官方 harness（dsh），保留 Silk 的上下文/隔离/加载能力，采用最小安全组合，前端零改动。

## 已确认决策（用户拍板）

- 进程粒度：**每会话一个 dsh runtime 进程**（Landlock 根 = 会话 workspace）。
- web_fetch：runtime 内**默认关**（`tool-web {search: true, fetch: false}`），Silk 受控版放 P3。
- 适用范围：内置 Silk 对话默认走 DeepSeek；**不做 claude fallback**（dsh 不可用直接报错）；工作流不动。

## 架构

```
DirectModelAgent（Silk 编排层，保留）
  ├─ chat history 注入 / KB 同步 / 引用 / silk_kb_action   ← 不动
  └─ 模型调用层换成 DshSdkClient（Kotlin，NDJSON JSON-RPC over stdio）
       └─ 每会话 spawn 一个 dsh runtime（Landlock 根 = backend/chat_workspaces/<session>/）
            └─ 最小安全组合：llm-deepseek + tool-fs(只读) + sessions + compaction
               （无 bash / 无 terminal / 无 subagent / fs read-only / web_fetch 关）
```

要点：

- 不能用"形态 B（Silk 后端执行全部工具）"：SDK 协议无自定义工具注入通道（server→client 是 dead capability），runtime 工具集由 cordis.yml 组合死。
- runtime 自带 fs 工具对**读**不加围栏（fs-sandbox 只围栏写），跨会话读隔离必须靠 OS 级 Landlock，不能靠 harness 策略。
- session id 由 Silk 生成：普通聊天 `silk-<sha256(userId:groupId) 前 32 位>`（确定性）；工作流复用 `agentSessions["dsh"].sessionId` 槽（本轮不动工作流）。
- dsh `initialize(cwd)` 的 cwd 是进程级（所有 session 共享），配合"每会话一进程"使用：cwd = 该会话 workspace。

## P0 冒烟结果（2026-08-14，均已完成）

环境：源码构建 dsh（corepack pnpm 11.7.0，`pnpm install` + `pnpm run build:lib`）；Node 22.22.1。临时脚本在 `/tmp/silk-smoke/`（不入库）。

1. ✅ 官方 `keyless-smoke.e2e.ts` 4/4：SDK 协议全链路（initialize → session/prompt → 事件流 → turn/end → shutdown），mock 模型服务器，无需 API key。
2. ✅ 最小安全组合（`/tmp/silk-smoke/agent.cordis.yml`：无 bash/terminal/subagent，fs read-only）可启动并完成一轮：
   `step/start → user/message → session/title → request/header → request/context → assistant/chunk×N → assistant/message → step/end → turn/end → idle`。
3. ✅ JSONL 落盘：`<root>/<normalized-cwd>/<session-id>/session.jsonl`，header `id` 即客户端传入的 sessionId，`silk-<sha256>` 方案可直接用。
4. ✅ 真实 DeepSeek API（model `deepseek-v4-flash`，reasoningEffort high，默认 base URL）：
   回答正常（"2。"）、`turn/end reason=completed`、usage 1060 in / 3 out、JSONL 18 行含 assistant/message。
5. ✅ web search（`agent-search.cordis.yml` = 最小组合 + dsh-web + tool-web{search,fetch:false} + web-search-deepseek）：
   模型调用 `web_search` → `tool/call → web/deepseek-search-llm-request → tool/result`，最终回答带真实来源引用（IT之家/DoNews/36氪），`turn/end=completed`。
6. ❌ **跨进程重启恢复不支持**：同一 sessionId、同一 DSH_SESSION_ROOT，第二个进程上下文为空（没想起 phase 1 记的暗号），且 phase 2 事件未追加进原 JSONL。
   根因：SDK server 的 `createSession` 每次 `ctx.agents.create(...)` 新建 agent，不把已持久化 session log 灌回上下文。
   **对计划的影响**：Silk 的"加载"必须靠 Silk 自己把 chat history 喂进 prompt；dsh JSONL 只作引擎侧簿记，不依赖它做跨进程恢复。
- 官方 bundled 单文件 exe 的发布形态：PyPI 上 `deepseek-harness-sdk` / `deepseek-harness-runtime-bin` 尚未发布（`pip index` 无匹配）；当前用源码 node 载体。

## 分阶段

- P1：Kotlin `DshSdkClient`（NDJSON JSON-RPC + 进程生命周期 + 事件→Silk blocks 映射）+ `DirectModelAgent` 接入（`SILK_AI_PROVIDER=dsh`，默认保持现状；无 claude fallback）。

### P1 落地内容（2026-08-14）

- 新增 `backend/.../ai/dsh/DshSdkClient.kt`：stdio NDJSON JSON-RPC（initialize / session/prompt / shutdown / 事件流），`parseDshEvent` 纯函数映射 text-delta / reasoning-delta / tool-call / tool-result / assistant-message / turn-end。
- `AIConfig` 新增 `SILK_AI_PROVIDER` / `DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` / `DSH_RUNTIME_CMD` / `DSH_RUNTIME_CWD` / `DSH_PROVIDER` / `DSH_MODEL` / `DSH_PROMPT_TIMEOUT_MS`。
- `DirectModelAgent` 新增 `runProvider()` 选择：`SILK_AI_PROVIDER=dsh` → `chatViaDsh()`（无 claude fallback，失败即报错）；默认路径不变。dsh 会话 id = `silk-<sha256(sessionId) 前 32>`；每会话一个 runtime，cwd=session workspace，JSONL 在 `workspace/.dsh_sessions/`。
- 单测：`DshSdkClientTest` 7 例（事件映射）；`DirectModelAgentCitationTest` 7 例通过；`:backend:detekt` 无新增问题。
- 文档：`.env.example`、`BOOTSTRAP.md`、`ARCHITECTURE.md`、`KNOWN_DRIFT.md` 已同步。

### P2 落地内容（2026-08-14）

- 新增 `backend/scripts/dsh_sandbox.py`：复用 `pty_chat.py` 的 Landlock 模式，`dsh_sandbox.py <workspace> <runtime_root> -- <argv...>`；workspace 全读写、runtime_root 与系统路径只读+执行、rlimit（无 RLIMIT_AS，避免 tsx WebAssembly 失败）、ABI=0 降级警告。
- `DshSdkClient`：沙箱启动器包装（`buildLaunchCommand`，脚本路径多候选解析）；超时/取消（STOP_GENERATE）→ 杀进程且可重启；单轮结束后 `DSH_IDLE_TIMEOUT_MS` 空闲自动关闭；`TSX_DISABLE_CACHE=1` 沙箱模式下避免写 /tmp。
- `AIConfig` 新增 `DSH_SANDBOX_SCRIPT` / `DSH_IDLE_TIMEOUT_MS`。
- 验证：`DshSdkClientIntegrationTest` 3 例（真实 runtime + 真实 key + 沙箱启动器）：流式回答、超时杀进程后可重启、空闲回收；单测 9 例；detekt 无新增问题。
- 已知限制（2026-08-17 修正）：初版 launcher 未设 no_new_privs，导致无 CAP_SYS_ADMIN 的进程 `restrict_self` 恒 EPERM，曾误判为容器 seccomp / 宿主 sysctl；补 `prctl(PR_SET_NO_NEW_PRIVS)` 后**本容器实测沙箱生效**（workspace 外写/读被拒、系统路径放行）。`/proc/sys/kernel/landlock` 在容器内被隐藏，launcher 走 syscall 探测；restrict_self 仍失败时打印原因并降级不沙箱。生产要求：Linux 6.7+、landlock LSM 启用、容器 seccomp 放行 landlock syscalls；非特权进程无需 CAP_SYS_ADMIN。
- P2：每会话 runtime + Landlock 沙箱固化（放行 runtime 自身可执行路径与依赖，用户文件仅 workspace 根）。
- P3（可选）：Silk 受控 web_fetch（SSRF 拒绝私网/保留段 + 大小上限 + 超时；补 harness 未做的私网防护）；等 SDK 支持 server→client 后考虑审批流。
- P4：单测 + 冒烟 + 文档同步（ARCHITECTURE / BOOTSTRAP / KNOWN_DRIFT：协议 0.0.1 无版本协商、无 cancel/approval）。

## 安全注意

- dsh 的 `web-fetch-http` provider 官方自认是 SSRF 原语（无私网防护），**不得**在 runtime 内启用 fetch。
- 真实 API key 仅经环境变量传入，不得入库、不得写入文档/脚本；冒烟用 key 若在聊天中明文出现过，建议轮换。
