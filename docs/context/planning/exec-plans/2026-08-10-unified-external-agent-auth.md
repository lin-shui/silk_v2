# 外部 Agent 统一接入与设备密钥认证方案

## 文档状态

- 状态：Phase 0–7 已实现并完成仓库级验证（含 AgentBinding 双主体审批、服务端路由 ACL、每轮执行权限信封、Web 管理、Host IPC v2、单设备 WSS 多路复用、撤销广播、生产传输门禁、系统凭据存储、备份/轮换和签名发行包）；Phase 8 cc-connect 独立迁移仍未开始。
- 目标读者：负责后续开发的 Agent、后端/前端/Connector 开发者和评审者。
- 设计范围：Claude Code、Codex、Cursor 等通过 ACP bridge、cc-connect 或后续 Connector 接入 Silk 的统一身份、设备认证、Agent 注册、Room 使用和撤销。
- 分阶段范围：第一阶段优先完成统一 Host、设备认证和直接 Bridge（主要是 ACP bridge）；cc-connect 明确延后，不作为第一阶段交付阻塞项。
- 本文不是现有实现的说明，而是“目标方案 + 当前实现事实 + 迁移约束”的开发依据。除非明确标记为“现状”，后续实现应以本文的目标方案为准。

相关上下文：

- [Claude Code And Bridges](../../integrations/CLAUDE_CODE_AND_BRIDGES.md)
- [Architecture](../../../../ARCHITECTURE.md)
- [Task Router](../../TASK_ROUTER.md)
- [cc-connect Silk 分支 feat/platform-silk](https://github.com/lin-shui/cc-connect/tree/feat/platform-silk)

### 0.1 当前实现基线（2026-08-11）

本节记录已经落入代码的事实。它是后续开发 Agent 判断“哪些可以复用、哪些仍需实现”的依据，不代表完整目标已经交付。

已实现：

- `backend/src/main/kotlin/com/silk/backend/agents/auth/` 固化了 v1 设备认证合同、Ed25519 公钥验证、配对状态数据、短时 challenge 和连接注册表。
- SQLite 已创建 `agent_devices`、`agent_instances`、`agent_bindings`、`agent_binding_audit_events`、`agent_pairing_requests` 五张表；`DatabaseFactory` 和测试隔离数据库都会创建/补齐这些表。
- `POST /api/agent-pairings` 创建 5 分钟配对事务。请求提交目标账号 loginName、Host 实际连接 origin、可选 Web origin、raw 32-byte Ed25519 公钥（无 padding 的 base64url）和 Agent 元数据；后端在出码前把 loginName 解析成不可变 userId 并预绑定，其他账号对该短码统一得到 not-found。返回 canonical `serverOrigin`、一次性 `userCode`、带 `#code=` fragment 的验证页地址、设备轮询 secret 和 `ws(s)` Agent 地址。fragment 不会随 HTTP 请求发送到服务端，Web 读取后清理地址栏。轮询 secret 只在响应中明文出现一次，数据库只保存 SHA-256 摘要。
- JWT 保护的 `POST /api/agent-pairings/preview` 和 `POST /api/agent-pairings/approve` 复用 Silk 现有登录体系。Web 可从验证 URL fragment 自动填码并 preview，但审批 API 仍在 body 中携带短码；打开链接不会自动批准，必须先展示请求详情并由用户明确确认。首次设备审批成功后进入 `DEVICE_PROOF_PENDING`，proof 有效期最多 90 秒；已有设备新增 Agent 则在设备签名请求通过后等待同一页面审批，批准后直接进入 `CONSUMED`。
- `GET /api/agent-pairings/{pairingId}/status` 使用 `X-Silk-Device-Poll-Secret`，`POST /api/agent-pairings/{pairingId}/proof` 使用同一短时 secret + Ed25519 签名完成设备和首个 Agent 登记。proof 成功后 pairing 进入 `CONSUMED`，设备和 Agent 进入 `ACTIVE`。
- `GET/DELETE /api/agent-devices`、`GET/DELETE /api/agent-instances` 和 `GET/POST/PUT/DELETE /api/agent-bindings` 已提供用户级查询、创建、更新和软撤销底座，`POST /api/agent-bindings/{bindingId}/approval` 提供批准/拒绝。跨所有者 Binding 可由 Agent owner 或目标管理者发起，必须由 Agent owner 与 Room HOST/OPERATOR（或 Workspace owner）双方批准才进入 ACTIVE；配置更新会按当前发起者权限重置另一侧审批，拒绝/撤销后的同范围请求会重新打开审批 revision，历史状态保留在不可变 audit event 中。服务端授权器把设备签名连接钉到精确 `agentInstanceId`，Workspace prompt、Room TEAM、目录读取/切换和 Source Control RPC 均检查 ACTIVE Binding、所需权限与声明能力。删除 Agent 只撤销该 Agent 和其绑定；删除设备撤销该设备下全部 Agent 和绑定，并关闭当前 `/agent-connect` 会话。
- `wss://<server>/agent-connect`（开发 HTTP 时为 `ws://`）已实现 `hello → challenge → authenticate → authenticated` 握手；challenge 有 30 秒有效期、一次性消费和 120 秒客户端时钟窗口。声明 `HOST_MULTIPLEXED_V1` 的 Host 在一次认证后以 `agent_open / agent_rpc / agent_close` v1 envelope 承载 ACP，每帧固定携带 `agentInstanceId`；未声明该模式的旧健康连接仍只接受 heartbeat。
- 第一阶段新配对只接受 `ACP` + `claude-code` / `codex`。`cc-connect` 会明确返回 `CC_CONNECT_DEFERRED`；`/agent-bridge` 只支持设备签名 challenge 并拒绝旧 query Token，`/ccconnect-bridge` 仍是 legacy Token。
- `silk-agent/` 已提供 Go Host 命令：`connect`、`run`、`start`、`status`、`stop`、`logs`、`service install/uninstall/status` 和 `identity backup/restore/rotate/migrate-keychain`；设备私钥优先使用 macOS Keychain、Windows DPAPI、Linux Secret Service，缺失时回退到 secure-file（Unix 0600/目录 0700；Windows 当前用户 owner + 受保护 DACL）。配对轮询/proof、origin 规范化、单一设备 WSS challenge/heartbeat/reconnect、Unix 0600/Windows 当前用户控制通道，以及受控 Adapter ProcessSupervisor 已完成。`logs --follow` 按 offset 增量输出并处理截断/替换；Windows Scheduled Task 显式持久化解析后的 `--config-dir`，profile 目录会收紧到当前用户/SYSTEM/Administrators 并让子文件继承；若提升进程创建目录导致 owner 为 Administrators，则只在当前进程确实 elevated 时转交给当前用户，其他账号 owner 拒绝。备份口令与发行私钥文件按 owner/DACL fail-closed 校验。已登记设备再次执行 `connect` 会发送覆盖 origin、一次性 requestId、Agent 元数据和能力集合的设备签名请求，审批后通过控制 socket 热加载新 Agent。Host IPC v2 以一次性 nonce 和固定 `agentInstanceId` 绑定子进程，再用 `adapter/acp` / `host/forwardAcp` 双向转发；Adapter 不再获得设备签名接口或建立 Silk WSS。Host 会把服务端撤销错误视为该身份的终止状态，全部启用身份被撤销时停止而非永久重连；Adapter 启动前校验 wrapper、入口脚本及全部随包 Python 模块的 owner/写权限，屏蔽 raw CLI I/O 日志并禁止 Python bytecode 写回发行目录。当前 `service` 为当前用户级 systemd user unit、macOS LaunchAgent 或 Windows Scheduled Task，不需要 root。
- 当前每个设备的每种 Agent 类型只允许一个 `ACTIVE` 实例，因此一个 Host 可并存 Claude Code 与 Codex，但尚不支持同类型多实例；重复登记会返回 `AGENT_ALREADY_ENROLLED`。若 Phase 6 需要同类型多实例，必须同时调整本地配置索引、控制命令和后端唯一性约束，不能只扩展 WSS envelope。
- 当前后端 ACP 运行时索引仍按 `(userId, agentType)` 只保留一个活动连接；同一账号在多个设备登记同类型 Agent 时，后建立的连接会替换前一个。Binding 数据和授权检查仍按精确 `agentInstanceId` 保存，但 Workspace 选择同类型设备需要后续引入显式 `deviceId/bridgeId` 路由。
- `cc_bridge/acp_adapter.py` 与 `codex_bridge/codex_adapter.py` 支持 `--silk-host-stdio`：不接收 Token、不请求设备签名、不连接后端，只通过 Host IPC v2 处理 ACP；直接运行 Adapter 和旧 `/agent-bridge` query Token 均明确拒绝，避免旧客户端静默降级。
- 设备签名连接的每次 ACP `session/prompt` 都要求 Agent 声明 `EXECUTION_POLICY_V1` 并携带版本化 `_silk.executionPolicy`。服务端仅从精确 ACTIVE Binding 与连接声明能力的交集生成 `READ_FILE` / `WRITE_FILE` / `RUN_COMMAND`；缺少版本能力的旧登记 Agent 拒绝 prompt，受管连接缺失信封、异常或未知信封均由 Adapter 按空权限处理。Claude Code 与 Codex 默认继承设备用户的原生认证和 CLI 配置（包括 hooks、MCP、插件和规则）；Silk 仅对核心本地文件/命令工具增加 Binding 权限上限：Claude 使用显式 deny、工作区路径检查和完整权限下的原生 Bash sandbox，Codex 使用 `read-only/workspace-write` sandbox 和 shell feature gate，受管路径不会主动开启 legacy dangerous bypass。
- Web `/device` 页面复用现有 JWT 登录，支持区分新设备/新增 Agent 的配对预览、批准/拒绝、设备与 Agent 查询/实时连接状态/撤销，以及 Room/Workspace AgentBinding 增删改和双主体审批；`silk.sh` 静态服务与 Nginx 回退后的 Ktor 固定路由都支持 `/device` SPA 直达。
- 已有 `AgentAuthProtocolTest`、`AgentAuthRouteContractTest` 和 `AgentBindingApprovalRouteContractTest` 覆盖 canonical 签名、错误/篡改 payload、origin 绑定、challenge 过期/重放、JWT 审批、proof 重放、已登记设备新增 Agent、Binding 双向发起/双审批/重新审批与撤销、双 Agent 连接隔离、WSS 认证和两级撤销。

Phase 7 已完成的仓库级实现：

- `DeploymentSecurity` 在生产模式强制 HTTPS origin、无凭据 URL 和显式 CORS allowlist；Host 生产连接强制 HTTPS/WSS，开发 HTTP/WS 需要显式开关。
- challenge 和安全事件使用共享主数据库；`AgentRevocationWatcher` 按持久化事件序列关闭每个节点的本地 Agent/设备连接。Binding 审批和同设备同类型新增 Agent 均使用事务内行锁处理跨节点竞态。
- `silk-agent` 提供系统凭据存储、加密身份备份、恢复、轮换和密钥迁移；发行脚本构建六个平台 bundle，并用 Ed25519 签名 manifest 校验版本、SHA-256 哈希和未签名文件，同时规范化文件/目录权限并拒绝 group/other 可写输出。
- Adapter 权限下推覆盖直接 `cc_bridge` / `codex_bridge` 的设备签名 prompt；受管连接缺失或异常信封按空权限处理。Claude Bash 只有完整读/写/命令授权才开放；Codex 写入要求读+写、shell 要求读+命令，因此不完整组合会降级而不会扩权。

仍需真实部署环境验证的事项：TLS 终止代理、各平台 Keychain/DPAPI/Secret Service 的实际桌面会话，以及发布系统中注入正式签名密钥后的安装升级验收。跨公网/NAT 与无浏览器审批已在当前 HTTP 受控环境通过，但生产 WSS 代理仍需单独验收。配对尝试限速目前是节点本地内存状态（已有周期清理和 10,000 桶硬上限）；若公网多节点部署需要全局限速，应在 ingress 或共享限流组件补齐。过期配对事务在新配对创建时清理 7 天前记录，安全审计事件继续保留。它们不应被本地单元测试冒充为已完成的生产部署。

本切片的验证命令：`./gradlew :backend:test`、`./gradlew :frontend:webApp:nodeTest :frontend:webApp:compileProductionExecutableKotlinJs`、`cd silk-agent && go test ./... && go test -race ./... && go vet ./...`、Direct Bridge Python/语法检查与 `./gradlew silkLint`。这些检查已接入 CI fast validation；后续修改消息/RPC 合同时必须继续扩展相应合同测试。

---

## 1. 背景与要解决的问题

Silk 目前有两种历史上独立演进的外部 Agent 接入方式：

1. ACP bridge：
   - Claude Code、Codex 等外部 adapter 连接后端的 /agent-bridge。
   - 改造前主要使用用户级 ccBridgeToken；Phase 7 已销毁并拒绝该凭据。
   - 同一个用户 Token 可能被多个 Agent 类型复用。
   - Token 通常通过 URL query 参数传给 bridge。
   - 重新生成 Token 会断开该用户的 ACP 连接。

2. cc-connect：
   - 通过 /ccconnect-bridge 连接。
   - 当前 Token 按群组生成并保存，Connector 通过 config.toml 配置。
   - 后端按照 Token 找到 group。
   - 连接注册表主要按 groupId 管理。

这两套模型不同，主要是两个模块由不同开发者实现且早期没有统一设计，并不存在必须保留“用户级 Token”和“群组级 Token”的产品原因。

当前方式给用户带来的问题：

- 用户需要从 Silk 界面生成 Token。
- 用户需要复制 Token 到 Connector 配置文件。
- Token 可能出现在命令行、URL、配置文件或日志中。
- 用户需要理解 Token 与用户、群组、Agent 之间的关系。
- ACP 与 cc-connect 的接入和撤销语义不一致。
- Token 是一个长期 bearer credential，泄漏后可被复制使用。

目标是把 ACP bridge、cc-connect 及后续 Connector 收束为同一类产品对象：

> 用户在某个本地设备上接入了一个 Agent，并可将这个 Agent 明确绑定到一个或多个 Room/Workspace。

用户只感知账号登录、设备和 Agent 对象，不生成、不复制、不管理长期连接 Token。

---

## 2. 已确定的核心决策

### 2.1 产品层统一为 Agent Connector

ACP 和 cc-connect 在产品层不再作为两类对象暴露。它们只是内部传输适配器：

~~~text
统一 AgentInstance / AgentBinding / 认证生命周期
                  ↓
        ACP Adapter / cc-connect Adapter
~~~

对用户而言，Claude Code、Codex、Cursor 等都是“接入 Silk 的 Agent”。用户不应该先理解“ACP bridge”还是“cc-connect”。

内部仍可暂时保留两套 wire protocol 和适配器，不要求第一版把 ACP JSON-RPC 与 cc-connect 消息帧强行改成完全相同的格式。统一重点是：

- 身份模型；
- 连接生命周期；
- 认证流程；
- Agent 注册和撤销；
- Room/Workspace 绑定；
- 后端统一路由和能力描述。

### 2.2 用户体验不包含长期 Token

用户不需要看到以下内容：

- 连接 Token；
- Token 生成按钮；
- Token 复制操作；
- Token 粘贴到 config.toml；
- Token 轮换操作。

协议内部仍可以使用短时、一次性、仅用于配对事务的随机值；这不属于用户需要管理的长期认证凭据。

### 2.3 使用非对称密钥证明设备身份

每个本地 Silk 设备配置生成一对 Ed25519 密钥：

- 私钥：只保存在设备本地，优先放系统 Keychain/Credential Manager；不能上传 Silk。
- 公钥：可以在临时配对事务中短暂保存；只有用户批准并完成设备私钥证明后，才作为 DeviceEnrollment 的长期公钥登记到 Silk 后端。
- 后端使用公钥验证设备连接时的签名挑战。

设备 ID 不是安全凭据。设备 ID 可以是公钥指纹或随机 UUID，但单独提供设备 ID 不能通过认证。真正的安全依据是设备持有对应私钥。

这与 SSH 的关系：

| SSH | Silk |
| --- | --- |
| 客户端私钥 | 本地设备私钥 |
| 服务端 authorized_keys 中的公钥 | Silk 后端保存的设备公钥 |
| 首次写入 authorized key | 用户在浏览器确认设备配对 |
| 删除 authorized key | 撤销设备公钥 |
| SSH 登录 | WSS challenge-response 后建立 Agent 会话 |

Silk 与 SSH 的重要区别是：信任的首次建立依赖 Silk 账号认证和用户确认，而不是服务器管理员手工编辑文件。

### 2.4 信任根、授权范围和使用范围分层

推荐的层级是：

~~~text
Silk User
  └ DeviceEnrollment
      ├ AgentInstance: Claude Code
      ├ AgentInstance: Codex
      └ AgentInstance: Cursor（可由 cc-connect 承载）
          └ AgentBinding -> ROOM 或 WORKSPACE
~~~

四个概念含义不同：

- User：Silk 账号所有者。
- DeviceEnrollment：一个本地 Silk 身份配置与某个用户之间的公钥信任关系。
- AgentInstance：该设备上的一个可独立管理的 Agent 安装/运行实例。
- AgentBinding：Agent 被授权使用某个 Room 或 Workspace 的关系。

对应的授权边界：

- 设备认证：证明“来自哪个已信任本地设备配置”。
- Agent 授权：证明“该设备上的哪个 Agent 被用户批准”。
- Room/Workspace 绑定：决定“该 Agent 可以看到和作用于哪些消息或工作空间”。

设备认证不能自动授予全部 Agent，也不能自动授予全部 Room。

### 2.5 用户只面对一个 silk-agent 入口

目标架构采用统一的本地 Silk Agent Host，用户不再分别运行 cc_bridge、codex_bridge、Cursor bridge 或 cc-connect。

统一入口负责：

- 首次设备配对；
- 新增 Agent 确认；
- 本地 Agent 检测；
- Adapter 和子进程启动、停止、重启；
- WSS 连接、重连和心跳；
- 状态、日志和诊断；
- 设备私钥和 AgentInstance 本地映射。

Host 可以检测本地已安装的 Agent，但检测结果只用于展示和 preflight；不得因为检测到 claude、codex 或 cursor 可执行文件就自动创建 AgentInstance 或发起绑定。

用户界面可以同时支持交互菜单和明确命令：

~~~text
silk-agent connect codex --server https://silk.example.com --account <login-name>
silk-agent connect claude-code --server https://silk.example.com --account <login-name>
silk-agent status
silk-agent stop codex
silk-agent logs codex
~~~

Agent 特有代码仍然保留为内部 Adapter。统一入口不代表取消 Agent 之间必要的协议差异。

第一阶段的可用 Agent 只包含已经能够直接由 Silk Host 纳管的 Bridge。cc-connect 虽然最终也应通过同一个入口接入，但在其专用 Adapter 完成前，不应阻塞设备认证、Host 和直接 Bridge 的交付。

---

## 3. “设备”的定义

安全模型中的设备不应等同于物理硬件、MAC 地址或机器序列号。硬件信息可能不存在、不稳定或可伪造。

第一版建议将设备定义为：

> 操作系统用户 + Silk 本地配置空间 + 一个 Silk 账户所对应的本地密钥身份。

因此：

- 同一台电脑的不同操作系统用户是不同设备。
- 同一操作系统用户的不同 Silk 账户配置应使用不同设备密钥。
- 删除本地 Silk 配置、丢失私钥、重装系统后，默认视为新设备。
- 不要依赖 MAC、硬盘序列号、主机名等信息进行认证。
- 设备展示名（如 dev-server-01）只用于用户识别，不能参与安全判断。

第一版建议限制一个本地设备配置只绑定一个 Silk 用户账号。若未来需要同一进程服务多个账号，应显式设计多租户隔离，不要默认复用同一私钥。

### 3.1 公钥指纹和 deviceId

可以使用以下方式之一：

- 服务端随机生成 deviceId，同时保存公钥；
- 使用 SHA-256(publicKey) 的截断或完整结果作为稳定指纹；
- deviceId 使用随机 UUID，另存 publicKeyFingerprint 供用户核对。

推荐第二种或第三种。无论采用哪种，服务端验证时必须根据数据库中的公钥验证签名，不能只比较 deviceId。

---

## 4. 用户可见对象与生命周期

### 4.1 DeviceEnrollment

建议字段：

~~~text
deviceId
userId
publicKey
keyAlgorithm = Ed25519
publicKeyFingerprint
displayName
status = ACTIVE | REVOKED
createdAt
lastSeenAt
lastSeenIp（仅用于安全审计，可按隐私策略保留）
clientPlatform
metadata（有限、非敏感）
revokedAt
~~~

后端必须保存公钥，不能保存私钥。设备删除后，公钥记录应立即变为 REVOKED 或被安全删除，并使所有现有连接失效。

### 4.2 AgentInstance

建议字段：

~~~text
agentInstanceId
userId
deviceId
agentType                 // claude_code, codex, cursor, ...
transportAdapter          // ACP, CC_CONNECT, ...
displayName
connectorVersion
reportedCapabilities
status = PENDING | ACTIVE | SUSPENDED | REVOKED
createdAt
lastSeenAt
revokedAt
~~~

agentType 表示用户理解的 Agent 类型；transportAdapter 是内部实现细节。两者不能混为一个字段。

外部 Connector 声明自己是 Codex 或 Claude Code，并不代表 Silk 能验证它确实是官方程序。除非未来引入软件签名或发行版证明，UI 应避免把自报类型展示成强安全保证。

同一设备上运行 Claude Code、Codex、Cursor 时，应该产生三个独立的 AgentInstance：

~~~text
Device A
  ├ AgentInstance Claude Code
  ├ AgentInstance Codex
  └ AgentInstance Cursor
~~~

它们可以共享设备信任根，但必须拥有独立的 Agent ID、状态、绑定和撤销操作。

### 4.3 AgentBinding

Agent 初次接入时不自动绑定 Room。用户在 Silk 中选择 Agent，并显式将它加入 Room 或 Workspace，类似添加群成员。

建议字段：

~~~text
bindingId
agentInstanceId
targetType = ROOM | WORKSPACE
targetId
messageScope = TEAM | WORKSPACE
triggerPolicy = ALL | MENTION | EVENT
permissions
status = ACTIVE | DISABLED | REVOKED
createdBy
createdAt
revokedAt
~~~

建议的最小权限枚举：

~~~text
READ_MESSAGE
SEND_MESSAGE
READ_FILE
WRITE_FILE
RUN_COMMAND
READ_WORKSPACE
WRITE_WORKSPACE
~~~

### 4.4 ConnectionSession

连接会话是短期运行时状态，不应当作为用户管理的长期 Token：

~~~text
connectionId
agentInstanceId
authenticatedAt
lastHeartbeatAt
transportAdapter
protocolVersion
~~~

重启或断线后通过私钥重新完成 challenge，不依赖永久 bearer Token。

---

## 5. 首次配对流程

### 5.1 为什么不让 Connector 直接收集账号密码

Connector 可能由不同项目、不同语言和不同开发者提供。若要求脚本读取账号密码，用户完整凭据会进入 Connector 进程，带来以下风险：

- 恶意或被篡改的 Connector 可以读取密码；
- 命令行参数、终端历史、调试日志可能泄露密码；
- 无法自然支持华为、微信、MFA、SSO 等非密码登录；
- 账号密码的生命周期被错误地交给 Agent 工具管理。

正确的凭据边界是：

~~~text
用户账号密码 / OAuth 登录
        ↓
Silk 官方登录页面
        ↓
Silk 后端

Connector 只接收配对结果，并使用本地私钥证明设备持有权。
~~~

### 5.2 有浏览器的设备

推荐交互：

~~~text
silk-agent connect codex --server https://silk.example.com --account <login-name>
  → 本地生成或读取设备密钥
  → 创建配对请求
  → 自动打开 Silk 配对网页
  → 用户登录（已登录则复用已有会话）
  → 页面展示设备、Agent、请求权限
  → 用户确认
  → silk-agent Host 获得配对成功结果
  → Host 进行设备签名认证
~~~

浏览器不一定要求用户每次输入密码。用户已经登录 Silk 时，只需要点击确认。

页面至少展示：

- Silk 账号；
- 设备名称；
- 设备公钥指纹的可读形式；
- Agent 类型和 Connector 版本；
- 请求的能力和权限；
- 配对请求有效期；
- 最近连接地址（如适合展示）。

### 5.3 无浏览器的设备

典型场景：

- 远程 Linux 服务器；
- 通过 SSH 使用的开发机；
- Docker 或 CI 环境；
- 没有图形界面的主机。

流程仍然是同一个配对协议，只改变网页打开方式：

~~~text
silk-agent Host 创建配对请求
  → 终端输出在 fragment 中携带短码的一次性网址
  → 用户在手机或另一台电脑打开 Silk 页面
  → 用户登录并确认
  → Host 通过轮询或出站 WSS 得到结果
  → Host 使用本地私钥完成签名
~~~

示例：

~~~text
请在任意浏览器打开：
https://silk.example.com/device#code=ABCD-EFGH

有效期：5 分钟
~~~

这个短码只用于在浏览器中定位一个临时配对请求，不是用户长期保存的连接 Token。它必须：

- 短时有效（建议 5 分钟左右，可配置）；
- 一次性使用；
- 服务端限速；
- 与设备侧持有的高熵 pairing handle 绑定；
- 不能单独绕过登录和用户确认；
- 只出现在不会发往服务端的 URL fragment 中，并由页面读取后立即清理地址栏；不出现在普通 access log 中。

短码的作用类似 GitHub CLI 的 device authorization code：它解决“终端和浏览器不是同一台机器”的关联问题。

### 5.4 推荐的服务端配对状态机

~~~text
CREATED
  → USER_PENDING
  → USER_APPROVED
  → DEVICE_PROOF_PENDING
  → ENROLLED
  → CONSUMED

异常状态：
EXPIRED
REJECTED
CANCELLED
FAILED
~~~

建议行为：

- CREATED：已收到设备公钥和 Agent 元数据，尚未有用户。
- USER_PENDING：等待用户在网页登录和确认。
- USER_APPROVED：用户批准了 userId、deviceId 和 Agent 请求。
- DEVICE_PROOF_PENDING：等待设备用私钥完成签名。
- ENROLLED：设备公钥和 AgentInstance 已正式登记。
- CONSUMED：配对事务已使用，不能重复消费。
- 任意超时、拒绝或取消都不能继续认证。

### 5.5 配对时到底向后端提交什么

初始请求可以类似：

~~~json
{
  "protocolVersion": 1,
  "keyAlgorithm": "Ed25519",
  "publicKey": "<base64url>",
  "accountLoginName": "<login-name>",
  "connectionOrigin": "https://silk.example.com",
  "verificationOrigin": "https://silk.example.com",
  "deviceName": "dev-server-01",
  "platform": "linux",
  "agentType": "codex",
  "agentDisplayName": "Codex",
  "connectorType": "ACP",
  "connectorVersion": "1.0.0",
  "capabilities": [
    "PROMPT",
    "STREAM",
    "CANCEL"
  ]
}
~~~

后端返回的信息应包括：

~~~json
{
  "pairingId": "<opaque-id>",
  "devicePollSecret": "<high-entropy-short-lived-secret>",
  "verificationUri": "https://silk.example.com/device#code=ABCD-EFGH",
  "userCode": "ABCD-EFGH",
  "expiresAt": "..."
}
~~~

devicePollSecret 仅供当前设备查询配对结果，服务端只保存其哈希值。userCode 供浏览器从 fragment 自动填入或由用户手工输入，熵较低且只用于定位请求。二者都不应成为后续长期连接凭据。

deviceId 的生成方式必须在协议中固定：

- 如果使用公钥指纹，设备可以在首次请求前自行计算并提交；
- 如果使用服务端随机 UUID，服务端必须在配对审批结果中返回 deviceId，Host 持久化后再用于后续 hello；
- 无论哪种方式，deviceId 必须绑定到本次 pairing publicKey，不能由用户手工输入或仅凭名称认领。

---

## 6. 后续连接认证协议

### 6.1 建立 WSS

生产环境必须使用 TLS：

~~~text
https://silk.example.com
wss://silk.example.com/agent-connect
~~~

当前直接通过 http://<server-ip>:8005 访问时，理论上可以生成 HTTP 配对网址，但只适合开发测试。正式环境需要 HTTPS/WSS，以免账号密码、配对码和连接数据被监听或篡改。

### 6.2 Challenge-response

推荐握手：

~~~text
Silk Agent Host → Server:
  hello(deviceId, agentInstanceId, protocolVersion)

Server → Silk Agent Host:
  challenge(challengeId, nonce, expiresAt)

Silk Agent Host → Server:
  authenticate(
    challengeId,
    deviceId,
    agentInstanceId,
    timestamp,
    signature
  )

Server → Silk Agent Host:
  authenticated(connectionId, capabilities, serverTime)
~~~

签名必须覆盖确定性的、不可歧义的 payload。建议包含：

~~~text
silk-device-auth/v1
serverOrigin
protocolVersion
challengeId
nonce
deviceId
agentInstanceId
timestamp
~~~

编码格式必须固定，例如 UTF-8、固定字段顺序、明确换行规则或 canonical JSON，避免不同语言 Connector 产生不同签名。

服务端必须：

- challenge 使用密码学安全随机数；
- 具有短过期时间；
- 只能成功消费一次；
- 检查 deviceId 与数据库公钥的对应关系；
- 检查 agentInstanceId 属于该设备和用户；
- 检查签名；
- 防止旧时间戳、重复 nonce 和连接重放；
- 允许明确的客户端时钟偏差窗口，并以 serverTime 或服务端校验为准；
- 在认证完成前禁止读取 Room、发送消息或调用 Agent RPC。

WSS/TLS 负责：

- 验证客户端连接的是正确的 Silk 服务端；
- 加密网络传输。

设备签名负责：

- 验证客户端确实持有已登记的私钥。

两者不能互相替代。

### 6.3 断线和重连

- 已登记 Agent 重启或普通断线：自动重新建立 WSS 并进行 challenge。
- 不要求用户重新输入密码。
- 不要求用户重新复制 Token。
- 如果设备被撤销、Agent 被撤销或绑定被禁用，challenge 必须失败。
- 服务端撤销后应主动关闭现有连接，同时在下一次重连时再次拒绝。

---

## 7. 新增 Agent 的处理

设备级信任不能自动覆盖设备上的所有 Agent，否则任意本地程序都可以声明自己是新 Agent。

建议规则：

1. 第一个 Agent：
   - 创建设备密钥；
   - 用户在 Silk 页面登录并确认设备和 Agent；
   - 服务端登记 DeviceEnrollment 和 AgentInstance。

2. 已认证设备新增 Agent：
   - 已认证 Host 发送带私钥签名的“新增 Agent 请求”；当前 Phase 5 使用 `POST /api/agent-pairings/agents`，Phase 6 再迁入统一设备 WSS；
   - 服务端识别该设备对应的用户；
   - 用户不需要再次输入账号密码；
   - Silk 页面仍显示并要求确认新增的 Agent；
   - 确认后创建独立 AgentInstance。

新增 Agent 请求必须包含设备签名、requested agent metadata 和一次性 requestId。服务端不得因为请求来自 ACTIVE 设备就自动创建 AgentInstance。

当前请求签名覆盖服务端 origin、协议版本、一次性 requestId、deviceId、Agent 类型/展示名、transport、connectorVersion、排序后的 capabilities 和时间戳。后端只接受 120 秒时钟窗口内的 ACTIVE 设备签名，并持久化 requestId 防止重放；请求预先绑定设备 owner，其他账号不能用短码认领。批准前不会创建 AgentInstance，拒绝不会改变已有设备或 Agent。

3. 已批准 Agent 重启：
   - 直接 challenge-response；
   - 不要求再次确认。

4. 不要因为同一个设备已经认证，就默认批准 Claude Code、Codex、Cursor 全部接入。

如果产品最终决定“设备一旦认证，设备上的所有 Agent 都自动接入”，必须明确接受其风险，并在 UI 中清楚提示。这不是本方案的推荐默认值。

---

## 8. DeviceSigner 与 Silk Agent Host

### 8.1 目标版本：Silk Agent Host 持有 DeviceSigner

抽象出统一接口，不让业务代码直接依赖某种密钥保存方式：

~~~text
DeviceSigner
  - ensureKey()
  - getPublicKey()
  - getDeviceId()
  - sign(payload)
  - deleteOrRotateKey()
~~~

实现优先级：

1. 系统 Keychain/Credential Manager：
   - macOS Keychain；
   - Windows Credential Manager/DPAPI；
   - Linux Secret Service 或受保护文件。

2. 跨平台不可用时，使用本地受保护文件作为 fallback：
   - 文件权限 0600；
   - 创建目录时使用 0700；
   - 进程设置 umask 077；
   - 原子写入；
   - 不放入 URL、环境变量、命令行参数或日志；
   - 不复制到云盘、仓库或普通备份；
   - 读取失败时不要自动生成新密钥覆盖旧文件。

目标架构中的 Connector Adapter 不直接读取私钥，而由统一的 Silk Agent Host 调用 DeviceSigner：

~~~text
silk-agent CLI
      ↓
Silk Agent Host
  ├─ DeviceSigner（KeychainSigner / SecureFileSigner）
  ├─ PairingManager
  ├─ SilkConnection
  ├─ AgentRegistry
  └─ ProcessSupervisor
       ├─ ClaudeCodeAdapter
       ├─ CodexAdapter
       ├─ CursorAdapter
       └─ CcConnectAdapter
~~~

用户只需要安装和启动一个 silk-agent。现有 cc_bridge、codex_bridge 和 cc-connect 进程由 Host 作为子进程或 Adapter 管理，桥接脚本不再是用户需要理解的产品入口。

Host 与 Adapter 之间建议使用 stdin/stdout JSON-RPC 或受保护的 Unix socket/named pipe：

~~~text
Silk Agent Host
  ├──启动──> ClaudeCodeAdapter 子进程
  ├──启动──> CodexAdapter 子进程
  ├──启动──> CursorAdapter 子进程
  └──启动──> CcConnectAdapter 子进程
~~~

Adapter 只负责 Agent 特有的启动、事件解析和命令转换；不能获得用户账号密码或设备私钥。

Host 的默认部署约束：

- 以当前操作系统用户运行，不默认使用 root 或系统级高权限；
- 设备密钥、Host 配置和日志按 OS 用户隔离；
- Host 启动的 Adapter 使用明确的子进程句柄或受保护 IPC；
- 每个 Adapter 启动时由 Host 分配一次性本地握手材料，并绑定到一个 AgentInstance；
- Adapter 不能在 IPC 中自由指定其他 agentInstanceId；
- Host 必须校验 IPC 对端的 OS peer credential（可用时）和启动握手；
- Host 关闭或撤销 Agent 时关闭对应子进程和 IPC 通道。
- Host 自身的安装包和更新也应使用版本、哈希和发布签名校验；不能只校验 cc-connect companion binary。

推荐将 Host 作为独立的跨平台 companion project（例如仓库内新的 silk-agent 目录或独立 companion 仓库），不把它做成 Ktor 后端的一部分。默认实现语言可优先评估 Go，以便生成单一跨平台二进制并复用成熟的进程、WebSocket、Ed25519 和系统凭据库；如果实现 Agent 决定采用其他语言，必须保持相同的 DeviceSigner、IPC 和发布制品合同。

### 8.2 采用 Host 模式的收益与代价

当前新增的统一启动需求使 Host 模式成为目标架构，而不只是可选的签名代理。

收益：

- 用户只安装和启动一个 silk-agent；
- 统一完成设备配对、Agent 新增确认和账号关联；
- 统一管理 Claude Code、Codex、Cursor、cc-connect 的启动、停止、重启和状态；
- 统一处理 WSS 连接、心跳、重连、错误和日志；
- 私钥只由 Host 持有，Adapter 不接触私钥；
- AgentInstance、能力和本地进程之间可以建立明确映射；
- 可以让多个 Agent 共用一个设备级连接并在服务端按 agentInstanceId 多路复用；
- 将来可以增加每 Agent 子密钥、OS 用户隔离和企业策略。

代价：

- 增加安装、启动、升级和跨平台 IPC 复杂度；
- Host 停止会导致所有 Agent 无法认证；
- 需要处理守护进程生命周期和版本兼容；
- IPC 本身必须做权限控制；
- 如果同一操作系统用户下的任意进程都能调用 socket，Host 并不能自动提供强隔离；
- 真正的进程级隔离仍可能需要不同 OS 用户、容器或每 Agent 独立密钥。

因此目标版本建议包含：

~~~text
silk-agent connect codex --server https://silk.example.com --account <login-name>
silk-agent connect claude-code --server https://silk.example.com --account <login-name>
silk-agent status
silk-agent stop codex
silk-agent logs codex
silk-agent service install
~~~

同时保留无常驻服务的前台模式：

~~~text
silk-agent run
~~~

适用于远程服务器、SSH 和调试环境。

### 8.3 Host 内部 Adapter 与进程管理

统一 Host 不等于把所有 Agent 的执行协议重写成一份代码。应定义内部 AgentAdapter 接口：

~~~text
AgentAdapter
  - detect()
  - validate()
  - start()
  - stop()
  - prompt()
  - cancel()
  - resolveQuestion()
  - resolvePermission()
  - capabilities()
  - health()
~~~

公共逻辑放入 Host：

- DeviceSigner、配对和 challenge；
- Silk WSS 连接；
- AgentInstance 注册；
- 重连、心跳和连接状态；
- Adapter 子进程启动与停止；
- 日志、诊断和版本信息；
- AgentBinding 权限前置检查。

Agent-specific 逻辑保留在 Adapter：

- Claude Code 的 stream-json 和权限事件；
- Codex 的 JSONL、session 和事件格式；
- Cursor 的本地插件或 CLI 接口；
- cc-connect 的项目、平台和 Agent 转换。

每个 Adapter 应独立拥有：

- 健康状态；
- 请求队列；
- 超时和 cancel；
- 崩溃重启退避；
- 日志文件或日志流；
- 最大重启次数和失败状态。

某个 Agent 崩溃时，不能导致其他 Agent 全部退出。Host 自身退出时应明确显示“设备 Host 离线”，而不是把所有 Agent 错误显示成单独的认证失败。

### 8.4 cc-connect 的后续集成策略（第一阶段明确排除）

用户提供的 [cc-connect Silk 分支](https://github.com/lin-shui/cc-connect/tree/feat/platform-silk) 是 cc-connect 原始仓库的 fork，并在 platform/silk 下增加了 Silk platform。该实现当前仍要求 server 和 token 配置，建立 WebSocket 后把 token 放入 query 参数，发送包含 project、agent_type、cwd 的 hello，并从 hello_ack 获取 group_id；因此它目前是“旧 Token + 群组连接”适配器，不能直接作为新的 DeviceSignature Host。

cc-connect 的改造暂时不进入第一阶段。第一阶段只为它保留稳定的 Adapter 扩展点和旧连接兼容，不要求开发 Agent 同时完成 cc-connect 的进程、配置和消息模型收束。

在直接 Bridge 和 Host 协议稳定后，再把该分支作为 Host 的 CcConnectAdapter 参考实现和子进程，不应要求用户手工 clone、修改、配置或单独启动。建议：

1. Host 项目维护 cc-connect 的明确版本、构建产物或受控依赖；
2. Host 为 cc-connect 生成临时运行配置，不把长期 Silk Token 暴露给用户；
3. CcConnectAdapter 负责把 cc-connect 的 project/agent 映射为 AgentInstance；
4. Host 负责设备配对和新的 Silk 认证；
5. Adapter 与 Host 通过本地 stdin/stdout JSON-RPC 或受保护 IPC 通信；
6. 不把 cc-connect 的全部代码复制到 Silk Host 中，避免产生长期分叉；
7. 中长期应把 Silk Adapter 所需的 Host/IPC 接口上游化，或维护一个明确版本化的适配包。

“由 Host 管理 cc-connect”不等于运行时执行 git clone，也不等于把整个 cc-connect 源码复制到 Host 目录中。推荐的交付方式是：

1. 开发和 CI 通过固定 commit/tag 引用该分支；
2. 在 Silk 发布流水线中为支持的平台构建受控的 cc-connect companion binary；
3. Host 安装包可以直接捆绑 companion binary，或者首次启用时从 Silk 官方制品源下载；
4. 下载时必须校验版本、SHA-256 和发布签名；
5. Host 记录实际运行的 cc-connect 版本，并支持兼容性检查和受控升级；
6. 禁止自动使用 GitHub 分支最新 HEAD，避免上游变化导致不可重复构建；
7. 打包和再分发前必须确认 cc-connect 及 fork 改动的许可证、NOTICE 和再分发条件。

如果前期为了开发方便需要把源码放入 Silk 工作区，可以选择固定 commit 的 git submodule、独立 vendor 构建目录或 CI checkout；这只是源码依赖管理方式，不能成为最终用户的安装步骤。

cc-connect 支持多个 Agent 和 project，因此后续接入时必须明确映射规则：

- 用户界面展示底层实际 Agent，如 Claude Code、Codex、Cursor；
- 每个被批准的 Agent/project 组合生成稳定的 AgentInstance；
- cc-connect 本身只作为 Connector/运行时，不作为用户看到的 Agent 类型；
- 删除一个 AgentInstance 不应删除同一 cc-connect 进程管理的其他 Agent；
- cc-connect 当前具备单进程多 project 能力，目标实现应让一个受 Host 管理的 companion process 承载多个 AgentInstance；
- Host 与 cc-connect 的本地 IPC 必须携带 projectId/agentInstanceId，不能继续只依赖 groupId；
- 若某个 Agent 需要不同 OS 用户、独立崩溃域或不兼容配置，可以为该 Agent 启动独立 cc-connect 子进程。

### 8.5 WSS 多路复用与渐进迁移

目标形态是 Host 持有设备级 WSS，并在消息 envelope 中携带 agentInstanceId：

~~~text
Silk Agent Host
       │ 一条设备级 WSS
       ▼
Silk Backend
  ├ logical stream: agentInstanceId=codex-1
  ├ logical stream: agentInstanceId=claude-1
  └ logical stream: agentInstanceId=cursor-1
~~~

服务端仍必须分别检查每个 AgentInstance 的状态、能力、Binding 和权限。设备只认证一次不能绕过 Agent 级授权。

Host 的多路复用实现必须满足：

- Host 维护 HostConnection 到 AgentInstance 的显式映射；
- 每个逻辑 Agent stream 有独立的队列、取消、超时和背压；
- Adapter 进程只能写入 Host 为它分配的 agentInstanceId；
- AgentInstance 撤销或 Binding 删除时，只关闭对应逻辑 stream；
- 设备撤销或 Host WSS 断开时，关闭该设备的全部逻辑 stream；
- 一个 Agent 的大消息或卡住请求不能无限占用其他 Agent 的连接资源。

对于第一阶段直接 Bridge，采用三步迁移：

1. Bridge-first MVP：silk-agent 作为统一启动器和进程监督器；每个直接 Bridge 可以暂时保持独立的逻辑后端连接，但新设备使用 DeviceSignature，已有安装可短期标记为 LEGACY_TOKEN；
2. Host central auth：设备密钥、配对和认证由 Host 统一处理；Adapter 通过受控本地接口请求 Host 连接，不读取私钥或长期 Silk 凭据；
3. 统一 WSS：Host 维护单一设备级 WSS，Adapter 只处理本地 Agent 执行，Host 在服务端按 agentInstanceId 多路复用。

迁移期间可以保留 /agent-bridge 和 /ccconnect-bridge 路径，但它们应共享新的认证服务，不能继续产生新的长期 Token。

cc-connect 不纳入上述第一阶段迁移链路。待直接 Bridge 完成后，再单独设计 cc-connect 的本地 IPC、project 映射、配置生成和 companion binary 交付。

### 8.6 Host 模式的安全边界

Host 是设备私钥的唯一调用者；如果 Host 进程或其所在 OS 用户被完全攻陷，攻击者仍可能冒充该设备发起签名。第一版应明确：

- 设备是本地 OS 用户级信任边界；
- AgentInstance 仍由服务端独立管理；
- 新 Agent 需要用户批准；
- Agent 到 Room 的访问由独立 AgentBinding 控制；
- Adapter 子进程默认不持有设备私钥；
- Host 与 Adapter 的 IPC 必须限制为 Host 启动的受控进程；
- 这不能防御 Host 所在 OS 用户已经完全被攻陷的情况；
- 若未来需要更强隔离，应使用 peer credential、每 Agent 子密钥、独立 OS 用户或容器。

---

## 9. Room、Workspace 与消息权限

Agent 接入 Silk 后，初始状态是不绑定任何 Room 的。连接成功不代表 Agent 自动看到用户全部消息。

### 9.1 绑定规则

- 用户在 Agent 列表中选择 Agent；
- 选择加入的 Room 或 Workspace；
- 明确选择消息范围和触发策略；
- Room 管理权限和 Agent 所有者权限都满足后，创建 AgentBinding。

建议初始策略：

- 普通 Room：可绑定 TEAM 消息；
- Workflow Room：明确区分 TEAM 与 WORKSPACE；
- Workspace：仅绑定到明确的 Workspace，不扩大到同一 Room 的其他 Workspace；
- SILK_PRIVATE：不得因为 Room 级绑定而泄露给 Agent；
- 触发策略支持 ALL、MENTION、EVENT，默认优先 MENTION 或明确配置。

### 9.2 所有权

第一版建议 AgentInstance 归属于创建它的 Silk 用户。将 Agent 加入 Room 时：

- Room 管理者需要有添加外部 Agent 的权限；
- Agent 所有者需要同意共享；
- 不能仅凭 Room 成员身份取得 Agent 管理权；
- Agent 所有者删除 Agent 时，所有绑定都失效；
- Room 管理者删除绑定时，Agent 本身仍保留在所有者的 Agent 列表中。

当前实现以 `PENDING` 保存未完成的跨所有者请求，并分别记录 Agent owner 与目标管理者的批准人和批准时间；任一侧拒绝都会撤销请求，双方均批准才进入 `ACTIVE`。更新目标、Agent、触发策略或权限时不会沿用另一侧旧批准。

---

## 10. 撤销、删除、丢失和轮换

### 10.1 删除 AgentBinding

只取消 Agent 与某个 Room/Workspace 的使用关系：

- Agent 仍处于可接入状态；
- 其他绑定不受影响；
- 当前 Room 的消息不再转发给该 Agent；
- 如果连接仍存在，应立即停止该目标上的新请求。

### 10.2 删除 AgentInstance

只删除一个 Agent：

- 关闭该 Agent 的连接；
- Host 收到撤销事件后应停止或隔离该 Agent 对应的 Adapter 子进程；
- 删除或撤销该 Agent 的全部 AgentBinding；
- 同设备上的其他 Agent 不受影响；
- 设备本身仍然是可信设备。

### 10.3 删除 DeviceEnrollment

删除设备是更大范围的操作：

- 撤销设备公钥；
- 关闭该设备上所有 AgentInstance 的连接；
- Host 收到设备撤销事件后应关闭该设备配置下的全部 Adapter 子进程；
- 撤销这些 Agent 的全部绑定；
- 设备本地旧私钥即使仍存在，也不能再次认证；
- 之后从该本地配置发起连接必须重新配对。

### 10.4 私钥丢失或怀疑泄漏

提供“轮换设备密钥”操作：

1. 用户在 Silk 中确认轮换；
2. silk-agent Host 生成新密钥；
3. 新公钥通过重新配对或已认证设备的签名请求提交；
4. 新公钥激活后，旧公钥立即撤销；
5. 现有连接重新认证或被关闭。

不能在读取旧私钥失败时静默生成新密钥并覆盖旧文件，否则会造成不可解释的设备分裂。

---

## 11. 后端数据模型建议

表名可以根据项目既有命名调整，以下是逻辑模型，不要求直接照搬名称。

### 11.1 agent_devices

~~~text
id / deviceId                 primary key
userId                        indexed
publicKey                     unique or indexed
keyAlgorithm
fingerprint
displayName
status                        ACTIVE / REVOKED
platform
createdAt
lastSeenAt
lastSeenIp
revokedAt
~~~

约束：

- publicKey 必须唯一，或至少不能在同一用户下重复；
- REVOKED 设备不能通过 challenge；
- 删除用户时级联撤销设备及其 Agent。

### 11.2 agent_instances

~~~text
id / agentInstanceId          primary key
userId                        indexed
deviceId                      foreign key
agentType
transportAdapter
displayName
connectorVersion
capabilitiesJson
status                        PENDING / ACTIVE / SUSPENDED / REVOKED
createdAt
lastSeenAt
revokedAt
~~~

约束：

- userId 必须与 agent_devices.userId 一致；
- 一个 AgentInstance 只能属于一个 DeviceEnrollment；
- AgentInstance 的撤销不撤销 DeviceEnrollment；
- 同一个安装重启时应复用稳定的 AgentInstance ID，而不是每次生成新对象。

### 11.3 agent_bindings

~~~text
id / bindingId                primary key
agentInstanceId               indexed
targetType                    ROOM / WORKSPACE
targetId                      indexed
messageScope
triggerPolicy
permissionsJson
status
createdBy
ownerId
agentOwnerApprovedBy / agentOwnerApprovedAt
targetApprovedBy / targetApprovedAt
createdAt
updatedAt
revokedBy
revokedAt
~~~

需要唯一性约束，避免同一个 Agent 对同一个目标重复创建相同绑定；如果需要不同权限配置，则把配置纳入业务判断。

`agent_binding_audit_events` 以 `bindingId + actorId + eventAction + snapshotJson + createdAt` 保存创建、更新、审批、拒绝、撤销和重新打开前后的不可变快照。当前 SQLite 唯一约束要求同范围重新申请复用稳定 `bindingId`，因此不能只覆盖主表审计字段而不写事件快照。

### 11.4 agent_pairing_requests

~~~text
pairingId
publicKey
publicKeyFingerprint
requestedDeviceName
requestedAgentMetadataJson
userCodeHash
devicePollSecretHash
state
createdAt
expiresAt
approvedBy
approvedAt
consumedAt
~~~

安全要求：

- userCode 和 devicePollSecret 不以明文长期存储；
- 配对请求自动过期；
- 已消费请求不能重复批准；
- 审批后仍需设备私钥证明；
- 不在普通访问日志中记录完整 code、secret、私钥或密码。

### 11.5 运行时连接表

连接注册表可以继续以内存为主，但必须以稳定的 agentInstanceId 为主键，并能够根据：

~~~text
userId + deviceId + agentInstanceId + transportAdapter
~~~

定位和关闭连接。多实例部署时，撤销事件需要通过共享数据库、事件总线或其他一致性机制广播到所有后端节点。

---

## 12. 建议的 HTTP/WebSocket 接口边界

以下是当前 Phase 0–2 已固定的实际路径；后续 Host/前端扩展不得改变签名字段和 secret 传递位置。

### 12.1 创建配对请求

~~~text
POST /api/agent-pairings
认证：无用户 JWT；使用新生成的临时设备事务材料
~~~

请求包括公钥和 Agent 元数据，返回：

- pairingId；
- verificationUri；
- userCode；
- 设备轮询用的临时 secret；
- 过期时间。

初次请求必须携带目标 loginName，服务端在生成短码前解析并保存 `intendedOwnerId`；preview、approve、reject 都只允许该 userId，其他账号统一按无效码返回，避免泄露该码绑定关系。服务端返回 canonical `serverOrigin`、verificationUri 和 Agent WSS 地址。canonical origin 优先由 `BACKEND_BASE_URL` 控制；未配置时使用 Host 从 `--server` 提交的规范化连接 origin，不能回退到 Ktor 看到的内网监听地址。验证页优先使用 `BACKEND_WEB_APP_BASE_URL`，其次使用 Host `--web`，最后才与连接 origin 同源；Web/API 分端口时不能猜测端口。Host 分别持久化用户实际连接的 `serverOrigin` 与服务端用于签名绑定的 `authenticationOrigin`，并拒绝后续 challenge 更换该认证 origin，避免反向代理或内网别名导致签名 origin 不一致。Server URL 可以是公网域名、内网域名、VPN 地址或开发阶段的 IP；URL 中不得包含账号密码、长期 Token 或私钥。

该接口必须限速，避免被用于无限制生成配对请求。

### 12.2 浏览器查看和确认

~~~text
GET  /device                         // Web SPA 已实现；无登录态先进入现有登录流程
POST /api/agent-pairings/preview     // 当前已实现；JWT；body.userCode
POST /api/agent-pairings/approve     // 当前已实现；JWT；body.userCode + approve
认证：Silk 普通登录会话/JWT
~~~

审批 API 使用 body 传递 `userCode`，不使用 query 或 `/.../{userCode}`，避免短码进入 HTTP request target/access log。verification URI 只允许把短码放在浏览器 `#code=` fragment；Web 读取并保存在当前标签页后立即清理地址栏。浏览器页面必须先展示设备名、公钥指纹、Agent 类型、Connector 版本、能力和剩余有效期，再由用户明确调用 approve；不得因打开链接或已有登录态自动批准，用户拒绝时发送 `approve=false`。

审批页面必须校验：

- 用户当前登录账号；
- 配对码；
- 配对请求是否过期；
- 设备名称、指纹、Agent 类型和能力；
- 是否允许该用户新增设备或 Agent。

账号登录应复用 Silk 现有认证体系，不能另造一套“连接密码”。当前账号可能通过本地账号密码、华为 OAuth、微信 OAuth 等方式登录，因此连接流程不能假设用户一定有密码。

已登记设备新增 Agent 使用：

~~~text
POST /api/agent-pairings/agents       // 无 JWT；body 携带设备签名，不携带长期 Token
POST /api/agent-pairings/preview      // JWT；仅设备 owner 可预览
POST /api/agent-pairings/approve      // JWT；批准后创建独立 AgentInstance
GET  /api/agent-pairings/{pairingId}/status
~~~

该流程复用短码与 `X-Silk-Device-Poll-Secret`，但不重复创建设备，也不需要第二次 pairing proof；设备签名已经证明请求来自现有信任根。当前它是统一 WSS 前的渐进实现，Phase 6 只迁移承载通道，不改变签名字段或浏览器审批要求。

### 12.3 设备获取配对结果

~~~text
GET /api/agent-pairings/{pairingId}/status
认证：devicePollSecret（短时、仅本次配对）
~~~

当前实现通过 `X-Silk-Device-Poll-Secret` 请求头传递 secret；不允许把它放在 query、路径、环境变量或日志中。审批完成后 status 返回 proof challenge；设备调用：

~~~text
POST /api/agent-pairings/{pairingId}/proof
认证：同一 X-Silk-Device-Poll-Secret + body.signature
~~~

也可以使用配对期间保持的出站 WSS 通道接收状态变化。无论采用轮询还是 WSS，都不能把该临时 secret 当作后续长期凭据。

### 12.4 Agent 连接

当前统一 Host 认证与业务入口：

~~~text
wss://<silk-server>/agent-connect
~~~

握手顺序固定为 `hello → challenge → authenticate → authenticated`。新 Host 在 hello 声明 `connectionMode=HOST_MULTIPLEXED_V1`，认证后使用 `agent_open / agent_rpc / agent_close` v1 envelope；`agent_rpc.payload` 是 ACP JSON-RPC object，所有逻辑 envelope 都固定携带 `agentInstanceId`。每个逻辑流使用独立有界队列、ACP client 和关闭回调；Agent 撤销只关闭对应流，设备撤销或物理 WSS 断开才关闭全部流。未声明新模式的旧 Host 健康连接仍只接受 heartbeat。

迁移期间可以保留：

- /agent-bridge
- /ccconnect-bridge

但它们应共享认证服务和 AgentInstance/DeviceEnrollment 模型。旧路径不应继续创建新的长期 Token。

### 12.5 AgentBinding 管理（当前最小版本）

~~~text
GET    /api/agent-bindings
POST   /api/agent-bindings
PUT    /api/agent-bindings/{bindingId}
POST   /api/agent-bindings/{bindingId}/approval
DELETE /api/agent-bindings/{bindingId}
认证：Silk JWT；Agent owner 或目标管理者可发起，双方批准后才 ACTIVE
~~~

`POST` 和 `PUT` 的 body 固定包含 `agentInstanceId`、`targetType`、`targetId`、`messageScope`、`triggerPolicy` 和 `permissions`；approval body 为 `approve`。Agent owner 可向自己已加入的普通 Room 或可见共享 Workspace 发起请求，目标侧批准者为 Room HOST/OPERATOR 或 Workspace owner；目标管理者也可反向发起并等待 Agent owner 批准。新 Agent 不会自动生成绑定；Binding 删除只是撤销目标使用关系，不撤销 Agent 或设备。`AgentBindingAuthorizationService` 使用 `AcpRegistry` 当前连接携带的精确 `agentInstanceId`，而不是仅按 user/type 查找任意 Binding；Workspace prompt 要求消息读写权限与 `PROMPT` 能力，Room TEAM 另执行 `ALL / MENTION / EVENT` 触发策略，带 Workspace 目标的 `cc-fs` 和 Source Control RPC 分别要求文件/工作区权限及对应声明能力。首次创建 Workspace 时的目录选择 `_silk/list_dir` 和初始化 `_silk/set_cwd` 仍是已登录 owner（后者另有 TrustedDir）保护的 bootstrap 例外，否则会形成“必须先绑定已存在 Workspace、但创建 Workspace 又必须先调用 Agent”的循环；这些调用不携带 Room/Workspace 消息或服务端文件数据，创建完成后的目标消息与 RPC 均受 Binding 约束。未绑定 Agent 仍不能通过 `/agent-connect` 执行业务 RPC。

---

## 13. 能力模型与统一操作

后端对 Agent 暴露统一的逻辑能力：

- prompt；
- 流式输出；
- cancel；
- question response；
- permission response；
- session resume（可选）；
- 文件和工作目录操作（按能力和权限可选）；
- 图片输入/输出（可选）；
- 模型、模式切换（可选）。

AgentInstance 在握手或注册时声明能力，但服务端必须做白名单和权限交集：

~~~text
有效能力 = Connector 声明能力
        ∩ Silk 允许的 Agent 类型能力
        ∩ AgentBinding 权限
        ∩ 当前 Room/Workspace ACL
~~~

不能因为 Connector 自报了 READ_WORKSPACE 就自动授予该能力。

AgentInstance 即使完成设备认证，也可以处于“已连接但未绑定”状态。此时服务端允许健康检查和 Agent 管理操作，但任何 Room 消息、prompt、文件或 Workspace RPC 都必须要求匹配的 ACTIVE AgentBinding；否则返回明确的 AGENT_NOT_BOUND 或 PERMISSION_DENIED 错误。

ACP 和 cc-connect 可以在 Adapter 内部保留不同命令和帧格式，统一层负责：

- 找到 AgentInstance；
- 检查连接状态；
- 检查绑定和权限；
- 转换统一操作；
- 归一化错误和取消语义；
- 处理 Agent 生命周期。

---

## 14. 安全要求和威胁模型

### 14.1 必须满足的要求

- 生产环境使用 HTTPS/WSS；
- 私钥永不上传；
- 用户账号密码不进入 Connector；
- challenge 使用强随机 nonce；
- challenge 短时、一次性、不可重放；
- 认证前不允许访问消息、Room、文件和 Agent RPC；
- 所有用户输入和配对码都限速；
- 日志脱敏；
- 撤销后立即关闭连接；
- 设备、Agent、Binding 的权限分别校验；
- 服务端验证 userId、deviceId、agentInstanceId 的所有权链；
- 每种协议版本和签名格式有明确版本号。

### 14.2 不能错误宣称的安全能力

设备私钥能证明“拥有该本地密钥”，不能证明：

- 设备硬件没有被攻陷；
- 当前进程是官方 Claude Code/Codex/Cursor；
- 同一 OS 用户下的其他进程无法调用签名能力；
- 本地工作目录没有泄露；
- Agent 具备真实厂商身份。

这些需要更高层的本地隔离、软件签名、容器或企业设备管理来解决，不应在第一版认证设计中假装已经解决。

### 14.3 配对页面防误配

页面应显示设备和 Agent 的可读信息以及公钥指纹。用户确认时应明确看到：

~~~text
账号：alice@example.com
设备：dev-server-01
指纹：AB12 CD34 ...
Agent：Codex
Connector：ACP
请求能力：PROMPT、STREAM、CANCEL
有效期：剩余 04:32
~~~

不能只显示“是否允许连接”，否则用户无法发现错误设备或恶意 Agent。

---

## 15. 当前直接 IP + HTTP 部署的约束

目前有部署通过类似以下地址访问 Silk：

~~~text
http://<server-ip>:8005/
~~~

该方式可以用于开发阶段验证配对流程，且不要求公网域名。正式环境不建议直接在 HTTP 上登录和配对，原因是账号密码、配对网址、短码和 WebSocket 数据都可能被监听或篡改。

推荐生产部署：

~~~text
浏览器 / Agent
       ↓ HTTPS / WSS
Nginx 或 Caddy
       ↓ HTTP / WS
Ktor Silk（127.0.0.1:8005）
~~~

不一定需要公网 IP：

- 公司内网可使用内网 DNS；
- VPN 可使用 VPN 地址；
- 本机测试可使用 localhost。

必须满足的是浏览器和 Agent 都能访问同一个 Silk 服务入口，并且正式场景下浏览器能验证服务端 TLS 证书。域名通常比直接使用 IP 更适合证书、OAuth 回调和长期维护。

---

## 16. 现有 Token 的迁移方案

不能直接删除现有 Token 逻辑，否则旧版 cc_bridge、codex_bridge 和 cc-connect 会同时中断。

### 16.1 迁移原则

- 新版本优先采用 DeviceEnrollment + AgentInstance。
- 旧 Token 在迁移期内只作为兼容认证方式。
- 新用户不再看到 Token 生成和复制入口。
- 现有 Token 不应自动变成已认证设备，因为 Token 不能证明 Connector 持有设备私钥。
- 用户需要从现有 Connector 发起一次新配对，生成设备密钥并完成确认。
- 第一阶段不改造 cc-connect 时，已有 cc-connect 用户继续走 LEGACY_TOKEN；Silk UI 应将其标记为旧版接入，并明确提示后续升级，不得把 Phase 1 误报为 cc-connect 已完成新认证迁移。
- 第一阶段默认不在新的 silk-agent Agent 列表中展示 cc-connect；若为了兼容必须创建旧 Token，应放在明确标注的 legacy/管理员兼容入口，不得与新设备认证入口混用。

### 16.2 迁移期连接标识

旧 Token 连接可以标记为：

~~~text
authenticationMode = LEGACY_TOKEN
~~~

新连接标记为：

~~~text
authenticationMode = DEVICE_SIGNATURE
~~~

UI 可以提示用户“该 Agent 使用旧版认证，请完成设备配对升级”，但不强制在第一步立即中断。

### 16.3 旧 Token API

当前涉及的旧能力（例如用户级 ACP Token、cc-connect 群组 Token）应按以下顺序处理：

1. 保留各适配器的验证端点，保证迁移期旧客户端可用；
2. 直接 Bridge（ACP/Claude/Codex）的新安装停止创建长期 Token；
3. 新增配对入口和设备签名认证；
4. 提供按适配器区分的迁移提示；
5. 直接 Bridge 完成迁移后，关闭其 Token 创建并在通知窗口后下线验证；
6. cc-connect 因第一阶段延后，继续保留 LEGACY_TOKEN 验证直到 Phase 8 完成；
7. Phase 8 的 cc-connect DeviceSignature 迁移完成后，再单独关闭 cc-connect Token 创建和验证。

旧 Token 的重新生成不应成为新认证方案的设备密钥轮换接口，两者语义必须分开。

---

## 17. 推荐实施顺序

本节记录开发 Agent 的拆分和收束顺序。Phase 0–7 已完成仓库级实现与验证；仅 cc-connect 统一认证迁移按 Phase 8 后续推进。

### 17.1 代码落点与职责映射

后续开发应先按以下职责定位代码，不要把认证逻辑继续分散到各个 Bridge：

| 职责 | 主要代码面 | 目标改动 |
| --- | --- | --- |
| Ktor 启动和路由 | backend/src/main/kotlin/com/silk/backend/Application.kt、Routing.kt、WebSocketConfig.kt、routes/* | 配对 API、设备/Agent/Binding API、统一 Agent WebSocket 入口和认证拦截 |
| 持久化 | backend/src/main/kotlin/com/silk/backend/database、models、auth | DeviceEnrollment、AgentInstance、AgentBinding、PairingRequest 和撤销状态 |
| Agent 框架 | backend/src/main/kotlin/com/silk/backend/agents/core、agents/acp、ccconnect | 统一注册、能力、路由、连接生命周期；ACP/cc-connect 只作为适配器 |
| 现有直接 Bridge | cc_bridge/acp_adapter.py、cc_bridge/bridge.sh、codex_bridge/codex_adapter.py、codex_bridge/bridge.sh | Host 子进程协议、DeviceSignature 迁移、旧 Token 兼容 |
| Silk Agent Host | 新增独立 companion project 或明确的仓库内 silk-agent 目录 | CLI、DeviceSigner、配对、WSS、Adapter 管理、日志和服务安装 |
| Web 管理界面 | frontend/webApp；共享请求/模型时检查 frontend/shared | 设备列表、Agent 列表、配对审批、撤销和 Room/Workspace Binding |
| cc-connect | 外部仓库 lin-shui/cc-connect 的固定 commit | Phase 8 单独适配；第一阶段不修改其核心运行时 |

后端认证和授权应由 Silk 服务端统一完成。Adapter 不应各自实现用户账号登录、长期 Token 生成或 Room ACL。

### Phase 0：合同和威胁模型（已完成）

- [x] 固化对象名和状态机（数据库状态包含 CREATED/USER_PENDING/USER_APPROVED/DEVICE_PROOF_PENDING/ENROLLED/CONSUMED 等；当前实现把审批/登记的瞬时状态收束在事务内）；
- [x] 固化 Ed25519 raw 公钥、无 padding base64url、`SHA256:<base64url>` fingerprint 格式；
- [x] 固化 challenge 签名 canonical payload：UTF-8、固定换行字段、时间单位 epoch milliseconds、无尾部换行；
- [x] 固化 5 分钟配对、最多 90 秒 proof、30 秒连接 challenge、120 秒时钟窗口、一次性 userCode/poll secret 生命周期；
- [x] 固化 AgentBinding 的 scope、trigger 和 permission，并接入当前服务端路由；
- [x] 固化 Host 与 Adapter 的本地 IPC 协议；
- [x] 固化 Host 多 Agent WSS envelope 和 agentInstanceId 路由；
- [x] 明确 API 错误码与协议版本（当前后端使用 protocolVersion=1）；

### Phase 1：后端设备和 Agent 数据模型（当前服务端路由 ACL 已完成）

- [x] 新增 DeviceEnrollment、AgentInstance、AgentBinding、PairingRequest 持久化；
- [x] 增加公钥唯一性、所有权和状态约束；
- [x] 增加设备/Agent/Binding 查询和设备/Agent 撤销服务；
- [x] 连接注册表按 AgentInstance 管理，并在撤销时关闭 `/agent-connect` 会话；
- [x] 增加 Binding 创建/撤销 API，并对 Room HOST/OPERATOR、Workspace owner 做最小目标校验；
- [x] 增加 Binding update API 和 Web 编辑入口；
- [x] 增加 Agent owner 与目标管理者双主体审批、审计字段和 Web 批准/拒绝入口；
- [x] 为设备签名 Agent 的 Workspace prompt 接入 ACTIVE Binding + `READ_MESSAGE`/`SEND_MESSAGE` 门禁；
- [x] 完成 Room TEAM、目录/Source Control Workspace RPC 的消息 ACL，并按精确 AgentInstance 校验能力交集；
- [x] 为设备签名 prompt 下发版本化执行权限信封，队列内每条消息保持各自授权快照；
- 多实例撤销广播已由持久化安全事件序列和各节点 watcher 实现。

### Phase 2：后端配对和 challenge-response（已完成）

- [x] 创建配对请求；
- [x] 提供 JWT preview/approve API，并由浏览器 `/device` 页面消费；
- [x] 设备轮询获取审批结果并提交 proof，Host 完成出站 WSS 连接；
- [x] `/agent-connect` challenge-response、heartbeat 与 Host ACP 多路复用；
- [x] 重放防护、过期、限速和日志脱敏；
- [x] 认证前不接受业务 RPC；
- [x] 将 challenge/revocation 状态扩展到多节点一致性（共享 PostgreSQL 主库、持久化 challenge、撤销事件序列与节点 watcher）。

### Phase 3：silk-agent CLI 与 Host 基础（已完成）

- [x] 确定 Host 实现语言和仓库内独立 companion 交付方式（Go module `silk-agent/`）；
- [x] 实现 silk-agent `connect/status/start/stop/logs/run`，其中 `logs --follow` 仅输出新增内容并识别日志截断/替换；
- [x] 实现前台模式和后台 Host 模式（PID、0600 log、Unix 控制 socket）；
- [x] Host 持有 DeviceSigner；
- [x] 实现系统 Keychain/Credential Manager（macOS Keychain、Windows DPAPI、Linux Secret Service；不可用时回退 secure-file）；
- [x] 实现 secure-file fallback（Unix 文件 0600/目录 0700；Windows 当前用户 owner + 受保护可继承 DACL；原子写入、缺失时不覆盖旧身份）；
- [x] 实现本地 AgentRegistry 和稳定 AgentInstance ID（服务端配对返回 ID 后持久化）；
- [x] 实现认证连接的健康检查和重连退避；
- [x] 实现 Host 自身受保护的本地控制 IPC；
- [x] 不把配对 secret/私钥/长期 Token 放入 URL、环境变量或日志；
- [x] 实现 Adapter 子进程 ProcessSupervisor、受控 stdin/stdout JSON-RPC IPC（nonce + agentInstanceId 绑定、health/shutdown、崩溃退避）；
- [x] 已登记设备可签名请求新增 Agent，运行中的 Host 通过受保护控制 IPC 热加载批准后的 Agent；
- [x] Host 当前用户级服务安装和平台守护进程集成（Linux systemd user、macOS LaunchAgent、Windows Scheduled Task；显式固定实际 profile 路径）；

### Phase 4：直接 Bridge 纳管（已完成）

- [x] 先把 cc_bridge、codex_bridge wrapper 作为 Host 管理的子进程；
- [x] 为现有 Bridge 增加 `--silk-host-stdio` Host IPC Adapter；
- [x] 让 Claude Code、Codex 等直接 Bridge 完成设备签名认证；
- [x] 将 `READ_FILE` / `WRITE_FILE` / `RUN_COMMAND` 下推为 Claude 核心本地工具 deny/Bash sandbox 与 Codex 文件 sandbox/shell gate，同时保留设备用户原生 CLI 配置；以 `EXECUTION_POLICY_V1` 阻止旧 Adapter 静默绕过，未知或异常信封 fail-closed；
- [x] 验证多个直接 Bridge 可以在同一 Host 下并存；
- [x] 验证一个 Bridge 崩溃/背压不会关闭其他 Agent 逻辑流；
- [x] 保留 cc-connect 旧 Token 路径，仅作为兼容能力，不纳入新 Host 交付；
- [x] 验证单个 Adapter 崩溃不影响其他 Agent。

### Phase 5：前端设备和 Agent 管理（已完成）

- [x] 设备列表；
- [x] Agent 列表；
- [x] 配对审批页；
- [x] 已登记设备新增 Agent 的签名审批；
- [x] Agent/设备删除；
- [x] 公钥指纹、最近连接和连接状态；
- [x] Room/Workspace AgentBinding 配置、细粒度权限选择、待审批状态及批准/拒绝操作。

### Phase 6：统一 Host WSS 与协议收束（已完成）

- [x] Host 维护单一设备级 WSS；
- [x] 每个逻辑消息 envelope 携带 agentInstanceId；
- [x] Adapter 不持有设备私钥、签名接口、长期 Silk 凭据或后端 WSS；
- [x] 后端按 AgentInstance 创建独立 ACP transport、队列和关闭回调；
- [x] 保留 `/agent-bridge` 旧 Host 设备签名兼容，并明确拒绝旧 query Token；
- [x] 添加 Host IPC v1 → v2 同 bundle 升级提示；
- [x] 验证一条 Host WSS 下 Claude Code 与 Codex 并存、Host 替换及跨流隔离；Cursor 仍仅通过 cc-connect 接入，留到 Phase 8。

### Phase 7：安全加固和收尾（已完成仓库级验收）

- [x] HTTPS/WSS 生产传输门禁与 TLS WebSocket/反向代理合同测试；真实公网 ingress 仍需部署验收；
- [x] 撤销实时生效验证（本地连接注册表 + 持久化安全事件 watcher）；
- [x] 多节点一致性（共享 PostgreSQL challenge、撤销事件序列和事务行锁）；
- [x] Binding/设备/Agent 安全审计事件与不可变快照；
- [x] companion binary 版本、SHA-256 manifest、Ed25519 签名、完整性校验和六平台发行脚本；
- [x] Host/Adapter IPC 权限与伪造边界（Unix ownership/parent checks、0600 socket、Windows 当前用户 pipe ACL、nonce + agentInstanceId 绑定）；
- [x] 直接 Bridge 的旧 Token 下线；cc-connect legacy Token 保留到 Phase 8；
- [x] 加密备份、恢复、设备密钥轮换和 Keychain/secure-file 迁移流程。

### Phase 8：cc-connect 独立接入（后续阶段）

该阶段在第一阶段直接 Bridge 和 Host 协议稳定、测试通过后单独立项，不应反向拖延前面的认证交付。

- 固定 cc-connect feat/platform-silk 的 commit 或发布版本；
- 确认 cc-connect 许可证、NOTICE 和再分发条件；
- 设计 cc-connect 与 Host 的本地 IPC 或 Host 专用 platform；
- 去除 cc-connect 对用户可见 Silk Token 的依赖；
- Host 自动生成 cc-connect 运行时配置；
- 在 CI 中构建和签名各平台 cc-connect companion binary；
- 定义 project、agent_type 与 AgentInstance 的映射；
- 决定单 Host 多 project 还是按 AgentInstance 隔离子进程；
- 验证多 Agent、会话、权限、文件和图片能力；
- 在确认兼容性后，再将 cc-connect 纳入统一 silk-agent connect 命令。

---

## 18. 验收标准

### 18.1 正常流程

- 用户只通过 silk-agent 管理外部 Agent；
- 用户不直接运行 cc_bridge、codex_bridge 或 cc-connect；
- 新设备生成 Ed25519 密钥；
- 私钥不离开本地；
- 设备私钥只由 Host 的 DeviceSigner 使用，Adapter 子进程不可读取；
- 浏览器登录方式可以是账号密码或 OAuth；
- Connector 从不读取用户密码；
- 用户能看到设备、Agent、能力和有效期；
- 用户确认后设备完成签名认证；
- 新 Agent 初始不绑定 Room；
- 用户可以把 Agent 加入指定 Room/Workspace；
- 同一设备可以独立运行多个 Agent；
- 每个 Agent 都有独立的状态和 ID；
- Host 可以启动、停止、查看状态和查看各 Agent 日志；
- 第一阶段直接 Bridge 的 Adapter 不需要用户手工启动或配置。

### 18.2 断线与重启

- 已批准 Agent 重启后自动认证；
- 不重复要求账号密码；
- 不需要复制 Token；
- 断线重连重新完成 challenge；
- session/Agent 状态按设计恢复或明确报失效。

### 18.3 撤销

- 删除 Binding 不影响 Agent；
- 删除 Agent 不影响同设备其他 Agent；
- 删除 Device 关闭设备上的全部 Agent；
- 删除设备后旧私钥不能再次认证；
- 撤销现有连接立即生效；
- 密钥轮换后旧公钥失效。

### 18.4 安全测试

至少覆盖：

- 错误公钥签名；
- 错误 deviceId；
- 错误 agentInstanceId；
- challenge 过期；
- challenge 重复使用；
- nonce 重放；
- 修改签名 payload；
- pairing userCode 暴力尝试和限速；
- pairing 请求过期；
- 拒绝后的设备尝试；
- 被撤销设备重连；
- 被删除 Agent 继续发送消息；
- Agent 访问未绑定 Room；
- Agent 访问其他 Workspace 或 PRIVATE 内容；
- Host 管理连接缺失 `_silk` 信封、版本错误或字段畸形时按空权限处理；直接 Bridge 不再存在可省略信封的 Token 模式；
- 未授予写入/命令权限时，受管 CLI 参数不得包含 dangerous bypass，且对应工具/shell 被关闭；
- 未授权本地进程连接 Host IPC；
- Adapter 冒用其他 agentInstanceId；
- 一个 Adapter 崩溃、卡死或持续重启时其他 Agent 保持可用；
- 日志和 URL 不包含密码、私钥和长期 Token。

### 18.5 部署测试

- HTTPS/WSS 正常工作；
- 内网域名或 VPN 地址可用；
- 当前 IP + HTTP 配置仅作为开发测试路径；
- 无浏览器设备可以通过另一台设备完成配对；
- 浏览器与 Agent 不在同一台机器但都能访问 Silk 时流程可用；
- NAT 下不要求 Silk 服务端主动连接 Agent；
- 前台 silk-agent run 可在 SSH 服务器工作；
- 后台服务可以随操作系统用户登录恢复。

### 18.6 cc-connect 后续验收

本节不属于第一阶段验收，只有在 Phase 8 开始后才执行：

- 用户无需 clone、修改或单独启动 cc-connect；
- cc-connect companion binary 的版本、哈希和签名可验证；
- cc-connect 不要求用户接触长期 Silk Token；
- cc-connect 的 project/agent_type 能稳定映射到 AgentInstance；
- 一个 cc-connect 进程管理多个 project 时，各 AgentInstance 的消息、权限和撤销相互隔离；
- cc-connect 子进程崩溃或重启不会破坏 Host 和其他直接 Bridge；
- cc-connect 支持的 session、question、permission、文件、图片能力均经过逐项适配；
- 删除 cc-connect 管理的一个 AgentInstance 不影响同进程的其他 AgentInstance。

---

## 19. 暂留的实现决策

以下事项不影响总体架构，但后续 Host/前端/业务授权开发前需要在代码设计评审中确定。已经落地的协议项不再作为可变选项：

已确定：raw 32-byte Ed25519 公钥使用无 padding base64url；fingerprint 使用 `SHA256:<base64url>`；deviceId 和 agentInstanceId 使用服务端 UUID；canonical payload 使用 UTF-8 固定换行且时间为 epoch milliseconds；`/agent-connect` 使用 `HOST_MULTIPLEXED_V1` + `agent_open / agent_rpc / agent_close` v1 envelope；Host 与 Adapter 使用 stdin/stdout JSON-RPC IPC v2；新增 Agent 使用设备签名请求 + Web 确认，当前仍由签名 HTTP 请求承载。

1. silk-agent Host 的实现语言、Keychain 库和跨平台打包方式。
2. secure-file fallback 是否只使用文件权限，还是再增加本地加密。
3. Agent 是否允许跨用户共享；第一版建议只允许所有者管理。
4. 是否在第一版提供公钥指纹复制/导出功能；建议只显示，不要求用户操作。
5. 多后端实例的撤销事件使用数据库轮询、Redis、WebSocket 广播还是其他机制。
6. 旧 Token 兼容期和最终下线版本。
7. AgentBinding 的默认 messageScope、triggerPolicy 和权限集合。
8. 是否允许同一 AgentInstance 同时绑定多个 Room，以及并发消息如何路由。
9. cc-connect 依赖采用 submodule、CI checkout 还是独立制品仓库。
10. cc-connect 固定的 commit/tag、许可证、NOTICE 和再分发条件。
11. companion binary 是随 silk-agent 安装包捆绑，还是首次启用时按平台下载。
12. cc-connect 多 project 是映射为一个进程多个 AgentInstance，还是一个 AgentInstance 一个子进程。

这些事项只能细化实现，不应重新引入用户管理长期 Token、Connector 收集账号密码或设备认证自动覆盖所有 Agent。

---

## 20. 最终原则

后续实现应始终遵循以下原则：

1. 用户账号认证由 Silk 官方登录页面完成，Connector 不接触账号密码。
2. 后端维护用户与设备公钥的绑定，不维护设备私钥。
3. deviceId 是标识，不是认证秘密；必须用私钥签名证明持有权。
4. 设备信任、Agent 授权和 Room 使用权限分层。
5. 同一设备上的多个 Agent 是独立的用户对象，可单独添加和删除。
6. Agent 初次接入不自动进入任何 Room。
7. 删除 AgentBinding、删除 Agent、删除设备具有不同影响范围。
8. 浏览器配对和无浏览器配对是同一协议的两种 UX，不是两套身份体系。
9. 一次性网址/短码只用于短期配对，不替代设备私钥，也不成为长期 Token。
10. 用户只通过 silk-agent 管理外部 Agent；目标架构采用 Silk Agent Host 并由 Host 持有 DeviceSigner。
11. ACP 和 cc-connect 在产品和认证层统一，在传输适配层允许渐进迁移。
12. 生产环境必须使用 HTTPS/WSS；直接 IP + HTTP 仅限受控开发测试。
13. 任何“自动信任设备上所有 Agent”的方案都必须显式评估并承担其安全后果，默认不采用。
14. 用户不需要 clone、修改或单独启动 cc-connect；Host 管理固定、可验证的 cc-connect companion binary。
15. 不在用户运行时执行 git clone，也不自动追踪外部分支最新 HEAD。
