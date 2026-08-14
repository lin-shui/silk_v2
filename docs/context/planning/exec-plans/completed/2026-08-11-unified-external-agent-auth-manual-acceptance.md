# 外部 Agent 统一认证 Phase 0–7 手工验收记录

## 文档状态

- 状态：验收完成（当前约定范围通过）
- 创建日期：2026-08-11
- 当前测试项：Phase 0–7 当前约定范围已完成；自动服务安装与 Windows Scheduled Task 按用户决定延期，跨 Windows 用户实机隔离因没有第二个本地账户跳过，当前开发环境不具备真实 TLS 入口
- 总计：53 项
- 进行中：0
- 已通过：49
- 失败：0
- 跳过：2
- 阻塞：2
- 尚未测试：0
- 主方案：[外部 Agent 统一接入与设备密钥认证方案](../2026-08-10-unified-external-agent-auth.md)

## 1. 范围

本记录用于验收 Phase 0–7 已实现的以下链路：

- `silk-agent` Host；
- 新设备 Ed25519 配对和 challenge-response；
- 已登记设备新增 Claude Code / Codex Agent；
- 单设备 WSS 多 Agent 复用；
- Host IPC v2 和受管 Adapter；
- Web `/device` 设备、Agent 与 Binding 管理；
- Room/Workspace Binding、双主体审批和服务端 ACL；
- 每轮执行权限信封与 Claude/Codex sandbox；
- 断线重连、撤销、备份、恢复和轮换；
- 用户级服务、Windows 安全、HTTPS/WSS 和签名发行包。

明确不包含：

- Phase 8 cc-connect 统一认证迁移；
- `/ccconnect-bridge` legacy Token 的替换；
- Cursor 的新 Host 直接接入。

## 2. 状态约定与更新流程

每项使用以下状态之一：

- `NOT_STARTED`：尚未测试；
- `IN_PROGRESS`：正在测试；
- `PASS`：实际结果完全符合预期；
- `FAIL`：发现可复现的不符合项；
- `BLOCKED`：环境、依赖或权限导致无法继续；
- `SKIPPED`：明确决定本轮不覆盖，并记录原因。

协作流程：

1. Codex 告知当前测试项的详细操作方法、风险和预期结果；
2. 测试人员完成该项并回复实际结果，不发送密码、Token、私钥或配对 secret；
3. Codex 更新本文件中的状态、日期、环境、证据摘要和问题记录；
4. 若为 `FAIL`，先记录复现与影响，再决定是否暂停后续测试修复；
5. 更新完成后进入下一项。

证据可以是截图文件名、脱敏日志片段、HTTP 状态码或简短现象说明。不要把真实凭据写入本文件。

## 3. 测试环境

由 `ENV-01` 填写：

| 字段 | 当前记录 |
| --- | --- |
| Silk 服务地址 | Web：`http://124.222.226.225:8005`；Backend：`http://124.222.226.225:8006` |
| 部署模式 | 公网 HTTP（仅用于当前受控测试；HTTPS/WSS 在 TLS 专项另行验收） |
| Backend 版本或 commit | 当前工作区 HEAD：`a9a4ffb5fb68` |
| Linux Agent 主机 OS/架构 | Linux `5.15.0-179-generic`，x86_64 |
| Windows Agent 主机 OS/架构 | Windows 11 Home China `10.0.26100`，64-bit |
| 浏览器及版本 | Windows Chrome；精确版本未登记，已确认可登录 Silk Web |
| Python 版本 | Linux `3.12.13`；Windows `3.13.12` |
| `silk-agent` 版本 | 外部 Linux PAIR-01/02 用 `0.4.1` 并复用 profile 升级到 `0.4.3`；Windows 基础配对用 `0.4.3` 通过，Windows 专项改用最终验收 `0.4.5` bundle，弹窗修复回归使用测试签名 `0.4.6` bundle，跨设备 cwd 修复回归使用测试签名 `0.4.7` bundle；Unicode prompt 修复回归使用测试签名 `0.4.8` bundle；原生 Claude/Codex 配置继承修复使用最终验收 `0.4.9` bundle；丢失旧 Claude session 自动恢复使用 `0.4.10` bundle |
| Claude Code CLI 版本 | Linux `2.1.209`；Windows `2.1.201` |
| Codex CLI 版本 | Linux `0.147.0`；Windows 未安装，本轮 Windows 只测试 Claude |
| 测试用户 A（Agent owner） | 已准备；只使用代号 A |
| 测试用户 B（目标管理员） | 已准备；只使用代号 B |
| 测试用户 C（普通/非成员） | 已准备；只使用代号 C |
| 临时 Agent profile | Linux 与 Windows 均已准备；不记录真实用户目录路径 |
| 临时工作目录 | Linux 与 Windows 均已准备；不记录真实用户目录路径 |
| Windows 实机 | 有 |

历史上的 `0.4.3` 是从未提交工作区构建的手工验收包；当前 `0.4.10` 是从本工作区生成的测试签名验收包，使用隔离临时密钥，不是生产发布签名包：

| 平台 | 临时下载地址 | SHA-256 |
| --- | --- | --- |
| Linux x86_64 | `http://124.222.226.225:8005/downloads/silk-agent_0.4.3_linux_amd64.tar.gz` | `521d547e840a656bbd41a39be71a9a8040e9e7ce87ad6a4cbac34edda461e5a8` |
| Windows x86_64（基础配对包） | `http://124.222.226.225:8005/downloads/silk-agent_0.4.3_windows_amd64.zip` | `a4603187370d86c11f384f77b25c5043b747ffe14f02650bd0576a82b592fee8` |
| Windows x86_64（最终验收包） | `http://124.222.226.225:8005/downloads/silk-agent_0.4.5_windows_amd64.tar.gz` | `08b35843e9567103d945489e33d52c15fae81f5cb1100dbe5db2968a79e20f23` |
| Windows x86_64（弹窗修复回归包） | `http://124.222.226.225:8005/downloads/silk-agent_0.4.6_windows_amd64.tar.gz` | `a5ad0bca970c4f2f0c922321faed2a1664d2b98cc94a68fd7d1fd6ff69993b3c` |
| Windows x86_64（跨设备 cwd 修复回归包） | `http://124.222.226.225:8005/downloads/silk-agent_0.4.7_windows_amd64.tar.gz` | `d5cf3b17b8c1bb62ae8875779e45be8be63acf51d63c9f62f798c67d062031f9` |
| Windows x86_64（Unicode prompt 修复回归包） | `http://124.222.226.225:8005/downloads/silk-agent_0.4.8_windows_amd64.tar.gz` | `51eaa309481b13f5182e375870a5ea4a23bc02416da2762510f5ee97ba144e25` |
| Windows x86_64（原生配置继承最终验收包） | `http://124.222.226.225:8005/downloads/silk-agent_0.4.9_windows_amd64.tar.gz` | `9b203b829034ed6d5a1dadf0d49d869856d661b652f2c2e3cea774edf740b76d` |
| Windows x86_64（旧会话恢复回归包） | `http://124.222.226.225:8005/downloads/silk-agent_0.4.10_windows_amd64.tar.gz` | `45a835ce1253df1b5df15cdaafb31fe60e131e61fe15986b8c5b4cf5520b0f02` |

Backend 已于 2026-08-12 14:19（Asia/Shanghai）部署修复版并完成 SQLite schema migration；部署前数据库备份保存在 `backend/silk_database.db.pre-pairing-0.4.1-20260812-141913`。本机和公网 Backend health、Web 首页及两个验收包下载均返回 HTTP 200。

`0.4.2` 修复 Windows 首次读取 profile 时把 POSIX `0700` 当作跨平台合同的问题，但管理员 PowerShell 创建 profile 时 owner 会成为 `BUILTIN\Administrators`，再次触发过严拒绝。`0.4.3` 只在当前进程确实 elevated 且 owner 恰为 Administrators 时把 owner 安全转交给当前用户；其他普通账号 owner 仍拒绝。Unix 继续强制目录 `0700`；Windows 把目录收紧为当前用户、SYSTEM、Administrators 的受保护可继承 DACL，fallback 私钥复用 Windows DACL 校验。自动验证和 Windows 管理员 PowerShell 实机配对回归均已通过。

2026-08-12 最终本机回归使用从当前工作区生成的测试签名 `0.4.5` 六平台发行包。2026-08-13 为 Windows 可见 Python 控制台修复生成 `0.4.6` 回归包；随后为 TEAM 跨设备 cwd 修复生成 `0.4.7` 六平台包；本次为 Unicode prompt 边界修复生成 `0.4.8` 六平台包；按设备原生配置优先原则生成 `0.4.9` 六平台包；2026-08-14 为丢失 Claude resume session 自动恢复生成 `0.4.10` 六平台包。签名密钥是隔离的临时验收密钥，不是生产发布密钥。`0.4.10` 验证包位于本机临时目录 `/home/ubuntu/.local/share/silk-agent-release-0.4.10-final-d2DLAF`，Windows amd64 已部署到当前 Web 下载目录；Windows 使用同一 `acceptance-windows-win03-20260813` profile 复用设备/Agent 身份，不需要重新配对。

本轮完整自动验证：Backend 370、Web 73、Shared 6、Android 15、Desktop 6、Direct Bridge Python 123 项均 0 failure/0 error；`go test -race ./...`、`go vet ./...`、Windows amd64/arm64 交叉编译、`silkLint`、Web production compile 和六平台测试签名发行包均通过。

## 4. 进度总览

| 分组 | 项数 | IN_PROGRESS | PASS | FAIL | BLOCKED | SKIPPED | NOT_STARTED |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 环境 | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| Web 与账号隔离 | 2 | 0 | 2 | 0 | 0 | 0 | 0 |
| 新设备配对 | 4 | 0 | 4 | 0 | 0 | 0 | 0 |
| 新增 Agent | 3 | 0 | 3 | 0 | 0 | 0 | 0 |
| Host 与日志 | 4 | 0 | 4 | 0 | 0 | 0 | 0 |
| ACP 交互与隔离 | 6 | 0 | 6 | 0 | 0 | 0 | 0 |
| Binding 与触发 | 6 | 0 | 6 | 0 | 0 | 0 | 0 |
| 执行权限 | 4 | 0 | 4 | 0 | 0 | 0 | 0 |
| 撤销 | 3 | 0 | 3 | 0 | 0 | 0 | 0 |
| 重启与网络 | 3 | 0 | 3 | 0 | 0 | 0 | 0 |
| 身份备份与轮换 | 4 | 0 | 4 | 0 | 0 | 0 | 0 |
| 用户级服务 | 2 | 0 | 1 | 0 | 0 | 1 | 0 |
| Windows 专项 | 3 | 0 | 2 | 0 | 0 | 1 | 0 |
| HTTPS/WSS | 3 | 0 | 1 | 0 | 2 | 0 | 0 |
| 发行包 | 2 | 0 | 2 | 0 | 0 | 0 | 0 |
| 高级安全 | 3 | 0 | 3 | 0 | 0 | 0 | 0 |
| **合计** | **53** | **0** | **49** | **0** | **2** | **2** | **0** |

## 5. 验收项目

### A. 环境

#### ENV-01 测试环境登记与隔离确认

- 优先级：核心
- 状态：`PASS`
- 操作：登记第 3 节环境；确认使用测试账号、临时 Agent profile 和临时工作目录；确认没有把生产身份、密钥或正式发行私钥放入测试范围。
- 预期：环境信息足以生成后续命令；测试数据与生产数据隔离；敏感信息未写入文档或聊天。
- 日期：2026-08-12
- 证据/结果：PASS。已确认一套 Silk 测试服务加两台独立 Agent 设备的拓扑：当前机器部署 Silk，另一台 Linux 云服务器和一台 Windows 笔记本分别测试 Agent 接入。Backend `8006` 运行且本机、Linux Agent、Windows Agent 的 `GET /health` 均返回 HTTP 200；Nginx 在 `8005` 提供 Web，`GET /` 与 `GET /device` 均返回 HTTP 200，Windows Chrome 已能登录。两台 Agent 均已准备隔离 profile/工作目录并确认 `silk-agent --version` 为 `0.4.0`。Linux 覆盖 Claude `2.1.209` 与 Codex `0.147.0`；Windows 覆盖原生 Claude `2.1.201` 和 Windows 平台安全/服务专项，Codex 未安装且明确不纳入 Windows 覆盖。测试用户 A/B/C 已准备。测试 bundle 仅用于本轮验收，不能作为正式签名发行包。

### B. Web 与账号隔离

#### WEB-01 `/device` 登录与基础加载

- 优先级：核心
- 状态：`PASS`
- 操作：分别在未登录和用户 A 已登录状态访问 `/device`，刷新页面并观察设备管理界面。
- 预期：未登录不能获得设备数据；登录后页面正常加载；刷新不会产生前端错误。
- 日期：2026-08-12
- 证据/结果：PASS。Windows Chrome 无痕访问 `/device` 时要求登录且未显示任何设备数据；用户 A 登录后“设备与 Agent”页面及批准、设备和 Agent 区域正常加载；普通刷新与强制刷新均保持在 `/device`，登录状态有效，无空白页、“加载设备数据失败”或其他错误提示。

#### WEB-02 设备 API 与跨账号隔离

- 优先级：核心
- 状态：`PASS`
- 操作：验证无 JWT 的设备 API 返回 401；用户 B/C 不能查看、批准、删除或修改用户 A 的设备与 Agent。
- 预期：所有跨账号访问均被拒绝，且不泄露目标对象详情。
- 日期：2026-08-13
- 证据/结果：PASS。匿名请求验证设备、Agent、Binding 三个管理 API 均为 HTTP 401；合同测试验证非 owner 对配对码得到统一 not-found，设备/Agent/Binding 查询和变更绑定当前 JWT 主体。真实 Web 中 A/B 已完成跨所有者 Binding；随后 C 账号登录 `/device`，三个管理 API 按 C 的 JWT 正常返回空集合，页面看不到 A/B 的任何设备、Agent 或 A 到 B 的 `222` Binding，也没有可批准、删除或修改的对象。

### C. 新设备配对

#### PAIR-01 创建配对与浏览器预览

- 优先级：核心
- 状态：`PASS`
- 操作：用隔离 profile 执行首次 `silk-agent connect claude-code --server <backend-origin> --account <A-loginName>`；Web 与 Backend 分 origin 且后端未配置 `BACKEND_WEB_APP_BASE_URL` 时另传 `--web <web-origin>`。直接打开 CLI 打印的 `/device#code=...` 链接；先在已登录 B 的浏览器标签页验证无法预览/批准并点击切换账号，再以 A 登录，检查自动预览。
- 预期：打印的验证页不再出现后端内网监听地址，且 fragment 已携带格式化短码；页面读取后清理地址栏中的 fragment，但当前标签页登录切换后仍能恢复流程。B 得到不泄露详情的错误且 pairing 保持待批准；A 无需再次复制短码即可看到设备名、平台、Agent 类型、能力和公钥指纹，且必须手动点击“批准连接”；新 Agent 尚未绑定 Room/Workspace。
- 日期：2026-08-13
- 证据/结果：PASS（Linux、Windows 及同页浏览器回归）。首次 Linux 配对、Windows 管理员 profile、账号锁定和 fragment 自动预览均通过；在已打开且无验证码的 `/device` 标签页中粘贴新的 `/device#code=...` URL 后，页面自动识别并预览一次性请求，仍要求手动批准。测试请求随后未批准/过期，未创建正式设备。

#### PAIR-02 批准、proof 与首次连接

- 优先级：核心
- 状态：`PASS`
- 操作：由用户 A 批准 PAIR-01，等待 CLI 完成 proof 和 Host 连接。
- 预期：设备与 Claude Agent 进入 ACTIVE；CLI 保存服务端返回 ID；`/device` 显示在线或最新连接状态。
- 日期：2026-08-12
- 证据/结果：PASS（Linux + Windows）。两个平台均由用户 A 批准后完成 proof、设备认证、单一 multiplexed WSS、Claude Adapter 启动和逻辑流打开。Linux 随后替换为 `0.4.3` bundle，在不重新配对的情况下复用同一个 `SILK_AGENT_HOME`、设备 ID、指纹和 Agent ID，`run` 使用原身份再次通过 WSS 认证。数据库只读核查确认 Linux `acceptance-linux-01` 与 Windows `acceptance-windows-01` 设备/Claude Agent 均为 `ACTIVE`，并已更新 `last_seen_at`。

#### PAIR-03 拒绝、错误代码与过期

- 优先级：核心
- 状态：`PASS`
- 操作：使用新的隔离 profile 分别测试拒绝、错误配对码和过期请求。
- 预期：均不能登记设备或建立连接；错误信息明确；不会留下 ACTIVE 设备或 Agent。
- 日期：2026-08-12
- 证据/结果：PASS。Linux 用户拒绝后 Host 得到终止状态且未登记 ACTIVE 对象；错误短码不能预览或批准；配对请求约 5 分钟后失效，等待中的 `silk-agent` 到达服务端返回的截止时间并以 `context deadline exceeded` 退出。经代码核对，该提示来自同一配对过期 deadline；进程退出后不能再提交设备私钥 proof，过期码也不能批准或创建设备。测试均使用独立 profile，没有影响既有 ACTIVE 设备。

#### PAIR-04 配对重放与本地凭据卫生

- 优先级：高级
- 状态：`PASS`
- 操作：检查重复批准/proof 行为、本地 profile 权限，以及 CLI、URL、日志和进程环境中是否出现私钥、长期 Token 或 poll secret。
- 预期：重放被拒绝；私钥不离开 Host；日志和 URL 不包含敏感凭据；Unix profile/文件权限符合 0700/0600。
- 日期：2026-08-12
- 证据/结果：PASS。Backend 合同确认 proof 重放 HTTP 409 且不重复创建对象；Go 合同确认 poll secret 只走专用 header、HTTP redirect 不转发 secret、origin 篡改 proof 被拒绝。本机 profile/`config.json`/`device_key`/`host.log`/PID/socket 分别为 0700/0600，敏感标记扫描无命中；Host 环境屏蔽 Silk/Bridge credential、raw CLI I/O 开关和 Python loader 变量。发行 bundle 实际启动后未生成 `__pycache__`。

### D. 已登记设备新增 Agent

#### ADD-01 创建 Codex 新增请求

- 优先级：核心
- 状态：`PASS`
- 操作：在已有 Claude 设备上执行 `silk-agent connect codex`，检查 `/device` 审批类型。
- 预期：显示“新增 Agent”而不是新设备；审批前 Codex 不可用；设备 ID 不变。
- 日期：2026-08-13
- 证据/结果：PASS（合同 + 本机 0.4.5 实测）。在已有 Claude 设备上执行 `connect codex`，Web 显示新增 Agent 审批并由锁定账号批准；审批前 Codex 不在本地 profile，批准后得到独立 Agent ID `34789d1a-88a5-4258-bfa8-46baca5446e3`。设备 ID 始终为 `0d193b0a-8a7c-488f-9f47-b5c375992470`，没有创建第二个设备。

#### ADD-02 批准、热加载与双 Agent 状态

- 优先级：核心
- 状态：`PASS`
- 操作：批准 Codex 新增请求，观察运行中 Host 与 `/device`。
- 预期：Codex 被热加载；Claude 和 Codex 同时 ACTIVE；各自有独立 `agentInstanceId`；只有一个设备对象。
- 日期：2026-08-13
- 证据/结果：PASS（合同 + 本机 0.4.5 实测）。批准返回后运行中 Host 直接报告 `Agent codex loaded by the running Host`，无需重启。Claude `8348c549-36a1-42b9-ac06-c562cedbb58c` 与 Codex `34789d1a-88a5-4258-bfa8-46baca5446e3` 同时为 `connected=true adapter=healthy restarts=0`；进程检查显示一个 Host、两个受管 Adapter，socket 检查显示 Host 到 Backend 只有一条已建立连接。

#### ADD-03 重复同类型 Agent 拒绝

- 优先级：核心
- 状态：`PASS`
- 操作：在同一设备再次尝试登记已经 ACTIVE 的 Agent 类型。
- 预期：返回明确的重复登记错误，不创建第二个同类型 ACTIVE 实例，也不影响既有 Agent。
- 日期：2026-08-12
- 证据/结果：PASS（事务/HTTP 合同）。两个并发待审批的同类型请求中首个成功，第二个批准返回 HTTP 409 `AGENT_ALREADY_ENROLLED`；已消费请求重放也返回冲突，最终只有一个同类型 ACTIVE Agent。

### E. Host 与日志

#### HOST-01 `status` 内容与运行状态

- 优先级：核心
- 状态：`PASS`
- 操作：执行 `silk-agent status`，对照 `/device`。
- 预期：服务地址、设备、指纹、密钥后端、Agent ID、启用状态、连接和 Adapter 状态正确。
- 日期：2026-08-12
- 证据/结果：PASS（本机 0.4.5 实测）。显示 server、device、稳定 ID/指纹、`secure-file`、Claude ID/enable 状态；运行态为 `connected=true adapter=healthy restarts=0`，与 Backend 认证日志一致。

#### HOST-02 前后台启动和整机停止

- 优先级：核心
- 状态：`PASS`
- 操作：测试前台 `run`、后台 `start`、重复启动和 `stop host`。
- 预期：前后台模式均可用；重复启动被拒绝；整机停止清理运行状态；不丢本地身份。
- 日期：2026-08-12
- 证据/结果：PASS（本机实测）。前台 `run`、后台 `start`、`stop host`、重复启动拒绝和多轮停止/恢复均通过；最终升级到 0.4.5 后保持原设备 ID、指纹和 Agent ID。

#### HOST-03 单 Agent 停止与隔离

- 优先级：核心
- 状态：`PASS`
- 操作：停止 Claude，验证 Codex，再重新启用 Claude；随后反向测试。
- 预期：只影响目标 Agent；另一 Agent 的连接、会话和响应不受影响。
- 日期：2026-08-13
- 证据/结果：PASS（合同 + 本机 0.4.5 双 Agent 实测）。停止 Claude 后，其 Adapter 单独退出且状态变为 disabled/disconnected，Codex 继续 `connected=true adapter=healthy`；重新热加载 Claude 后两者均恢复健康。反向停止 Codex 时 Claude 保持健康，重新热加载 Codex 后两者再次健康。全过程 Host PID、设备 ID、指纹和两个 Agent ID 均不变，Adapter restart 计数均为 0。

#### HOST-04 普通日志与增量 follow

- 优先级：核心
- 状态：`PASS`
- 操作：测试 `silk-agent logs`、`logs --follow`、追加日志、Host 重启，以及可控条件下的日志截断/替换。
- 预期：普通日志最多显示最近约 200 行；follow 的已有内容只出现一次；新增内容不重复；截断/替换后从新文件开头继续；没有敏感凭据。
- 日期：2026-08-12
- 证据/结果：PASS（本机实测 + Go 合同）。普通日志不超过最近约 200 行；follow 对追加、truncate、replace 三个 marker 各输出一次；配置/日志敏感标记扫描无命中。修复了 Python shutdown 的 signal wakeup fd 噪音，并禁用 raw CLI I/O 日志；120 项 Python 回归通过。

### F. ACP 交互与隔离

#### ACP-01 Claude 基础 prompt 与流式响应

- 优先级：核心
- 状态：`PASS`
- 操作：在已授权临时 Workspace 中向 Claude 发送简单问题和多轮问题。
- 预期：流式响应、thinking/tool/text 和最终消息正常；不会一直停在运行状态。
- 日期：2026-08-13
- 证据/结果：PASS。用户在 `test` Team Channel 通过真实 Web 发送 Claude mention，Claude 成功返回；本机 Host 日志确认 session/new、session/prompt、CLI 进程正常结束和 `stopReason=end_turn`。ACP initialize、流式 text/thinking/tool 和取消合同也通过。

#### ACP-02 Codex 基础 prompt 与流式响应

- 优先级：核心
- 状态：`PASS`
- 操作：在同一临时 Workspace 中向 Codex 发送简单问题和多轮问题。
- 预期：响应和状态正常；使用独立 Codex 会话；不串入 Claude 流。
- 日期：2026-08-13
- 证据/结果：PASS。本机运行中的受管 Codex executor 使用真实 provider 配置和 `ExecutionPolicy()` 发送 `请只回复：CODEX-ACP-OK`，约 15 秒收到精确 `CODEX-ACP-OK`，并正常产生 `thread_started`、`agent_message`、`turn_completed`；修复后用户又在真实 Web `test` Team Channel 发起 Codex mention，页面正常收到回复并退出运行状态。Host 始终保持 `connected=true adapter=healthy`。Codex JSONL 解析、dispatcher、session、工具/流式映射与 sandbox 共 120 项 Python 集合通过。

#### ACP-03 显式路由与跨流隔离

- 优先级：核心
- 状态：`PASS`
- 操作：测试 `/codex`、`@claude`、`@codex` 和 Agent 切换；同时观察两路状态。
- 预期：消息进入正确 Agent；request/response/session 不跨 `agentInstanceId`；一个流繁忙不阻塞另一个流。
- 日期：2026-08-12
- 证据/结果：PASS（合同）。Backend 验证精确 `agentInstanceId` 双逻辑流、旧 Host 替换与不同 group 更新隔离；Go race 合同验证一个 Agent queue 阻塞不阻塞另一 stream；命令/mention 路由单元测试通过。

#### ACP-04 取消正在执行的任务

- 优先级：核心
- 状态：`PASS`
- 操作：分别启动较长 Claude/Codex 任务并取消。
- 预期：目标任务停止；UI 退出运行状态；后续 prompt 仍可执行；另一 Agent 不受影响。
- 日期：2026-08-13
- 证据/结果：PASS。首次真实 Web 测试发现并修复 TEAM 停止路由缺口；修复后用户再次启动长 Claude 任务并点击停止，页面恢复正常并成功返回 `CLAUDE-CANCEL-RECOVERED`。Backend 日志记录收到 `STOP_GENERATE` 并取消 1 个 TEAM 外部 Agent；Host 日志对精确 ACP session `16f1ef17-c165-4b45-a27a-92bf58a02e44` 记录 `session/cancel ... killed=True`，实际 Claude 子进程以 `Exit code=-9` 退出并返回 `stopReason=cancelled`。后续恢复 prompt 在新进程中正常完成 `stopReason=end_turn`，停止后无残留旧 Claude 进程。取消时 Backend 的 `JobCancellationException` ERROR 是协程取消路径日志，不代表任务仍在执行。

#### ACP-05 会话新建、恢复与 Host 重启

- 优先级：核心
- 状态：`PASS`
- 操作：分别创建会话、产生上下文、重启 Host 后恢复，并验证找不到旧会话时的错误。
- 预期：可恢复会话保持上下文；不可恢复时明确报错并允许 `/new`；Claude/Codex 会话互相独立。
- 日期：2026-08-13
- 证据/结果：PASS。用户先在真实 Web Team Channel 分别让 Claude/Codex 记住独立标记；本机随后正常停止旧 Host PID `530923` 及两个 Adapter，确认 `host.pid` 清理，再从同一 profile 启动新 Host PID `913671`。设备 ID、两个 Agent ID 和指纹保持不变，无需重新配对，两个逻辑流均恢复为 `connected=true adapter=healthy`。重启后 Claude 创建新 ACP session `c2682650-bc73-4b74-a77f-5804a740fae3`，以旧 CLI session `9a1d1d92-072d-4f12-b359-066c401f2d9a` 执行 `--resume`；Codex 创建新 ACP session `c74ea915-deb8-461f-a002-95327efed262`，恢复旧 thread `019ff925-8db3-7ee0-beeb-d9b68042b36a`。两者均在页面精确返回各自重启前标记，未串流。缺失/畸形 session、明确错误和 `/new` 重置路径由 Claude/Codex session index/lifecycle 自动化合同覆盖。

#### ACP-06 Claude 问题卡片、权限卡片与 Adapter 故障隔离

- 优先级：高级
- 状态：`PASS`
- 操作：触发 AskUserQuestion 和权限请求，分别批准/拒绝；随后可控终止一个 Adapter 并观察 Supervisor。
- 预期：卡片交互能继续或拒绝任务；Adapter 失败被重启或明确标为失败；另一 Agent 始终可用。
- 日期：2026-08-12
- 证据/结果：PASS（代码合同）。AskUserQuestion 多问题卡片、permission request/reply 映射、Host IPC nonce/instance binding、health/shutdown、故障有界重启与跨 Agent 隔离测试通过。真实卡片视觉/点击体验并入第 7 节可选 UI 体验复核，不作为安全合同阻塞项。

### G. Binding、触发与目标 ACL

#### BIND-01 未绑定默认拒绝

- 优先级：核心
- 状态：`PASS`
- 操作：使用没有 Binding 的新 Agent，从目标 Room/Workspace 尝试触发和读取。
- 预期：不执行 prompt，不读取目标数据，不获得隐式默认权限。
- 日期：2026-08-12
- 证据/结果：PASS（服务端合同）。新 Agent 的 Binding 列表为空；授权服务对不匹配的精确 `agentInstanceId` 返回 `AGENT_NOT_BOUND`，未绑定 Agent 不能读目标数据或触发 prompt。

#### BIND-02 同所有者 Room/Workspace Binding

- 优先级：核心
- 状态：`PASS`
- 操作：用户 A 把自己的 Agent 分别绑定到自己管理的 Room 和 Workspace。
- 预期：scope、trigger、permissions 正确保存；审批状态符合单所有者规则；ACTIVE 后只作用于目标范围。
- 日期：2026-08-13
- 证据/结果：PASS。用户在 Web 为同一所有者的 Claude/Codex 分别创建到 Room `test` 的 Binding；数据库核对两条均为 `ACTIVE`，`TEAM / MENTION`、`READ_MESSAGE + SEND_MESSAGE` 和双方审批字段正确。随后用户用 A 账号为 Claude 创建到 Workspace `team / Issue #7` 的 Binding，数据库核对为 `ACTIVE`，`WORKSPACE / MENTION`、`READ_MESSAGE + SEND_MESSAGE` 和双方审批字段正确；Room 与 Workspace Binding 彼此独立，现有 Room Binding 未被修改。

#### BIND-03 跨所有者双主体审批

- 优先级：核心
- 状态：`PASS`
- 操作：将用户 A 的 Agent 绑定到用户 B 管理的目标，分别由两侧批准。
- 预期：单侧批准保持 PENDING；Agent owner 与目标管理员都批准后才 ACTIVE；双方均可发起流程。
- 日期：2026-08-13
- 证据/结果：PASS。用户在真实 Web 中先把 A 加入 B 管理的 Room `222`，A 为自己的 Claude Agent 创建 Room Binding 后页面保持 `PENDING`，B 在自己的 `/device` 页面批准后 A 刷新看到 `ACTIVE`。数据库核对 binding `0230f063-1d36-4b41-9254-1e2fea1a8359` 的 Agent owner approval 为 A、target approval 为 B，权限为 `READ_MESSAGE + SEND_MESSAGE`，范围为 `TEAM / MENTION`；不可变审计事件依次记录 A 的 `CREATED` 与 B 的 `APPROVED`。A 发起/B 批准、B 发起/A 批准及拒绝重开等其余方向由 HTTP/DB 合同覆盖。

#### BIND-04 拒绝、重新申请和配置更新

- 优先级：核心
- 状态：`PASS`
- 操作：测试任一方拒绝、拒绝后重开、ACTIVE/PENDING 配置更新。
- 预期：拒绝不激活；重开产生新 revision；配置变化重置另一方审批；旧快照保留在审计中。
- 日期：2026-08-12
- 证据/结果：PASS（HTTP/事务合同）。任一方拒绝进入 REVOKED；重新申请复用稳定 bindingId 并写 `REOPENED/PREVIOUS_REVISION`；更新重置目标方批准，授权立即失效直至重新批准。

#### BIND-05 角色权限、撤销与审计

- 优先级：核心
- 状态：`PASS`
- 操作：由用户 C 尝试审批/修改/撤销，再由合法 owner/manager 撤销并查看历史。
- 预期：无权用户全部被拒绝；合法撤销立即生效；Binding 审计记录主体、动作和不可变配置快照。
- 日期：2026-08-12
- 证据/结果：PASS（路由/授权合同）。审批能力按 Agent owner 与 Room HOST/OPERATOR/Workspace owner 计算；合法目标管理员可撤销，撤销立即使 ACL 失败；审计含 REJECTED、PREVIOUS_REVISION、REOPENED 和不可变快照。无权 JWT 路径由合同覆盖。

#### BIND-06 ALL/MENTION/EVENT 与目标隔离

- 优先级：核心
- 状态：`PASS`
- 操作：逐一测试 ALL、MENTION、EVENT；提及 Claude/Codex；尝试访问其他 Room、Workspace 和 PRIVATE 内容。
- 预期：只按配置触发精确 Agent；跨 Agent mention 不误触发；其他目标和 PRIVATE 内容被拒绝或不暴露。
- 日期：2026-08-12
- 证据/结果：PASS（当前实现边界）。MENTION 只接收精确 bound Agent mention；ALL 忽略 `@Silk` 与其他 Agent mention；EVENT 明确不由普通聊天 TEXT 触发，当前没有定义外部业务事件入口。Room 主链跳过 SILK_PRIVATE，授权按精确 Room/Workspace target 与 instance 检查，跨目标失败。若产品要让 GitHub 等事件触发外部 Agent，需要另立功能合同，不能把当前预留枚举当作已接入事件源。

### H. 执行权限

所有本组测试只使用临时工作目录和无破坏性命令，如读取测试文件、创建临时文件、`pwd`、`echo`。

#### POL-01 仅 READ_FILE

- 优先级：核心
- 状态：`PASS`
- 操作：只授予读取，测试工作区内读取、写入、shell 和工作区外读取。
- 预期：只允许工作区内读取；写入、shell 和越界读取失败。
- 日期：2026-08-12
- 证据/结果：PASS（策略/CLI 参数合同）。权限信封解析只授予字面 true，workspace 内 read 开启；write、shell 和越界路径 fail-closed。服务端还要求 Binding permission 与 Agent capability 同时满足。

#### POL-02 READ_FILE + WRITE_FILE

- 优先级：核心
- 状态：`PASS`
- 操作：增加写入，测试临时文件创建/修改、工作区外写入和 shell。
- 预期：只允许工作区内读写；越界写入和未授权 shell 失败。
- 日期：2026-08-12
- 证据/结果：PASS（策略合同）。Claude 文件工具与 Codex `workspace-write` 仅限 canonical workspace root；未授予 RUN_COMMAND 时 shell/unified exec 被关闭；越界路径被拒绝。

#### POL-03 READ_FILE + RUN_COMMAND

- 优先级：核心
- 状态：`PASS`
- 操作：测试安全命令和写入尝试；分别观察 Claude 与 Codex 行为。
- 预期：Codex shell 在只读 sandbox 中按授权工作且不能写；Claude 没有完整读/写/命令组合时不开放 Bash。
- 日期：2026-08-12
- 证据/结果：PASS（策略合同）。Codex 在 read-only sandbox 开命令但无写权限；Claude Bash 要求完整 read+write+command 三项，部分授权不会开放 Bash；受管参数不含 dangerous bypass。

#### POL-04 完整权限、越界与异常信封

- 优先级：高级
- 状态：`PASS`
- 操作：授予读/写/命令，测试工作区内操作、工作区外操作；在可控测试客户端中尝试缺失、错误版本或畸形 `_silk.executionPolicy`。
- 预期：完整授权仍受 sandbox 限制；越界失败；异常信封按空权限；不会加入 dangerous bypass 参数。
- 日期：2026-08-12
- 证据/结果：PASS（策略合同）。完整授权选择硬 sandbox 而非 bypass；缺失/畸形/错误字段信封按空权限；Host 注册要求 `EXECUTION_POLICY_V1`，旧 Adapter 得到 `CAPABILITY_DENIED`。路径归一化与 workspace containment 测试通过。

### I. 撤销

#### REVOKE-01 删除 Binding

- 优先级：核心
- 状态：`PASS`
- 操作：在 Agent 在线时删除 ACTIVE Binding，然后再次访问原目标。
- 预期：目标权限立即失效；Agent 和其他 Binding 保持可用。
- 日期：2026-08-12
- 证据/结果：PASS（HTTP/ACL 合同）。删除 ACTIVE Binding 后 `hasActiveAgentBinding` 立即为 false，Agent/设备状态不受影响；合同覆盖配置更新后的权限快照。

#### REVOKE-02 删除单个 Agent

- 优先级：核心
- 状态：`PASS`
- 操作：删除 Claude 或 Codex，观察当前连接、Binding、重连和同设备另一 Agent。
- 预期：目标 Agent 立即断开且不能重连；其 Binding 失效；另一 Agent 不受影响。
- 日期：2026-08-12
- 证据/结果：PASS（Backend 合同）。删除单 Agent 关闭其连接并返回 violated policy/agent revoked，Binding 失效；同设备另一 Agent 保持 ACTIVE/connected。旧实例重新连接得到 `DEVICE_OR_AGENT_REVOKED`。

#### REVOKE-03 删除设备

- 优先级：核心
- 状态：`PASS`
- 操作：删除设备并重启本地 Host，观察全部 Agent 和旧私钥认证。
- 预期：设备下全部 Agent 立即断开；相关 Binding 失效；旧设备私钥无法再次认证；其他设备不受影响。
- 日期：2026-08-12
- 证据/结果：PASS（Backend 合同）。设备删除关闭设备下所有逻辑流，全部 Agent 进入 revoked/断开，旧私钥认证被拒绝；安全事件含 DEVICE_REVOKED。当前本机未对正在使用的设备执行破坏性删除。

### J. 重启与网络

#### NET-01 Host 重启与自动认证

- 优先级：核心
- 状态：`PASS`
- 操作：停止并重新启动 Host。
- 预期：不需要账号密码、配对码或 Token；使用新 challenge 自动认证；Agent 恢复状态明确。
- 日期：2026-08-12
- 证据/结果：PASS（本机实测）。多轮 stop/start、从旧 0.4.4/0.4.5 切换到最终 0.4.5 均不需要账号、配对码或 Token，稳定设备/Agent ID 保留，WSS challenge 重新认证并恢复 healthy。

#### NET-02 Backend 重启与临时断网

- 优先级：核心
- 状态：`PASS`
- 操作：分别短暂停止 Backend 和断开 Agent 主机网络，再恢复。
- 预期：Host 退避重连；恢复后重新 challenge；不复用旧 challenge；不会产生重复逻辑流。
- 日期：2026-08-12
- 证据/结果：PASS（本机实测）。Backend 短暂停止期间 Host 按 1/2/4/.../30 秒退避，恢复后重新 challenge，Claude logical stream 恢复；无重复 ACTIVE 设备或 Agent。

#### NET-03 跨机器、NAT 与无浏览器 Host

- 优先级：部署
- 状态：`PASS`
- 操作：让浏览器、Silk 和 Agent 位于不同机器或网络；用另一设备批准无浏览器 Host。
- 预期：只需 Host 主动出站访问 Silk；NAT 下服务端无需回连；跨设备审批正常。
- 日期：2026-08-12
- 证据/结果：PASS（用户外部 Linux/Windows 实机）。Silk 后端、本地/远端浏览器和 Linux/Windows Agent 分机运行；`--no-browser` 打印 URL，由另一设备批准；Host 只需主动出站 WSS，NAT 不要求服务端回连。TLS 代理路径另见 TLS-02/03。

### K. 身份备份、恢复与轮换

本组使用可丢弃的测试设备；执行轮换前确认已有可恢复备份。

#### ID-01 加密备份与 passphrase 边界

- 优先级：核心
- 状态：`PASS`
- 操作：使用受保护 passphrase 文件备份；测试缺失、过短和命令行传递限制。
- 预期：只接受受保护文件；passphrase 至少 12 字符；备份为新文件；内容不含明文私钥。
- 日期：2026-08-12
- 证据/结果：PASS（本机自动实测）。受保护 600 passphrase 文件可创建加密 backup；短口令/宽权限输入拒绝；备份为新文件且不含明文私钥。命令行不接受 passphrase 参数。

#### ID-02 错误口令和篡改备份

- 优先级：核心
- 状态：`PASS`
- 操作：用错误口令恢复，并复制后修改备份字节再恢复。
- 预期：两者均通过认证加密校验失败，不写入部分身份或配置。
- 日期：2026-08-12
- 证据/结果：PASS（自动实测）。错误 passphrase 和修改 backup 字节均在认证加密校验阶段失败，不写入部分 profile。

#### ID-03 全新 profile 恢复与覆盖保护

- 优先级：核心
- 状态：`PASS`
- 操作：恢复到新的临时 profile，再尝试恢复到已有身份的 profile。
- 预期：新 profile 恢复后可以认证；已有身份不会被覆盖；恢复失败不破坏原状态。
- 日期：2026-08-12
- 证据/结果：PASS（自动实测）。新 profile 恢复后 config/key/host.log 权限为 600、目录 700；已有身份恢复被拒绝且原 key hash 不变。

#### ID-04 服务端撤销后的设备密钥轮换

- 优先级：核心
- 状态：`PASS`
- 操作：先测试未确认撤销时轮换，再撤销旧设备、确认并轮换，最后重新配对。
- 预期：未确认时拒绝；旧公钥失效；新身份需要新配对；失败时保留可恢复备份。
- 日期：2026-08-13
- 证据/结果：PASS。使用隔离临时设备 `acceptance-id04-rotatable-20260813` 完成真实回归。未带 `--confirm-device-revoked` 时轮换被拒绝，旧私钥/config hash 不变；用户 A 撤销设备后，Backend DELETE 返回 200，旧 Host 收到 `DEVICE_OR_AGENT_REVOKED` 并终止，不再重连。带确认参数轮换先写入 `0600` 加密备份，再删除旧 `device_key` 并清空 config。使用同一 profile 重新配对后得到新设备 `6af0ae0c-8a77-4168-b502-f265561087c4`、新 Agent `59b05657-2317-4a2f-9a22-7f8c94320461`、新指纹 `SHA256:pr7pJeSAYeh25jYtWRJ6V2Qtf7DC8TazED1K_8W7DW8`；数据库核对旧设备 `ec2c3f32-acde-45de-8c89-d78d729c17b4`/Agent `139f9f1b-8a31-40f8-8daf-2ef0668e7071` 为 `REVOKED`，新身份为 `ACTIVE`。主验收 Host 随后恢复 Claude/Codex healthy。临时明文口令文件已删除；加密备份保留在临时 profile 供本轮恢复边界复核。

### L. 用户级服务

#### SVC-01 安装、状态与登录恢复

- 优先级：平台
- 状态：`SKIPPED`
- 操作：测试 `service install/status`，注销并登录当前 OS 用户。
- 预期：未登记设备时拒绝安装；正常安装无需 root；登录后自动恢复 Host；读取正确 profile。
- 日期：2026-08-13（Linux 当前用户真实登录恢复）
- 证据/结果：本轮按用户决定延期自动服务功能。服务生命周期部分已通过：enabled user service 在全部 SSH 会话终止后自动恢复，原 Device/Agent ID 保留；Backend 恢复后原 Host 自行重新认证，无需重新配对。真实 prompt 随后发现 service 不读取交互式 shell 的 `ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN`，Claude 返回 `Not logged in · Please run /login`。后续需设计显式、受保护的 CLI 环境配置；当前不删除 `service install`，但不作为推荐运行方式，已卸载 service 并恢复普通 `silk-agent start`。

#### SVC-02 卸载与身份保留

- 优先级：平台
- 状态：`PASS`
- 操作：执行 `service uninstall`，再次登录并手动运行 Host。
- 预期：不再自动启动；本地配置和设备身份未删除；手动运行仍可认证。
- 日期：2026-08-12
- 证据/结果：PASS（Linux 当前用户实测）。uninstall 后 service inactive/unit 删除，config/key 保留且 hash 不变；手动 start 仍可认证。

### M. Windows 专项

没有 Windows 实机时，本组标记 `BLOCKED` 或经确认后 `SKIPPED`，不能记为 PASS。

#### WIN-01 默认/自定义 profile Scheduled Task

- 优先级：平台
- 状态：`SKIPPED`
- 操作：分别使用默认和包含空格的自定义 `SILK_AGENT_HOME` 安装服务，注销后登录。
- 预期：Scheduled Task 固定实际 `--config-dir`；两种 profile 均恢复正确设备；不回落到默认目录。
- 日期：2026-08-12（模板/交叉编译通过）
- 证据/结果：Windows action quoting、包含空格/尾反斜杠的显式 `--config-dir` 合同和 amd64/arm64 交叉编译通过。因自动服务环境配置与 Linux `SVC-01` 同属待完善能力，用户决定本轮延期真实 Scheduled Task 登录恢复；Windows 继续使用直接 `run/start`。

#### WIN-02 profile、passphrase 与发行私钥 owner/DACL

- 优先级：安全
- 状态：`PASS`
- 操作：从默认继承权限创建 profile，确认 Host 自动收紧目录并使新文件继承；再测试当前用户私有文件、非当前用户 owner、Authenticated Users/其他普通用户具有允许型访问的 passphrase/发行私钥文件。
- 预期：profile 目录仅允许当前用户、SYSTEM、Administrators 且禁止继续继承宽泛 ACL；私密输入只接受当前用户 owner 且允许主体限于当前用户、SYSTEM、Administrators 或 owner 占位的文件，其余 fail-closed。
- 日期：2026-08-12
- 证据/结果：PASS（用户 Windows 配对 + Windows runner 合同）。0.4.3 管理员 PowerShell 实机已验证 Administrators owner 安全转交、受保护 profile DACL、DPAPI/Adapter 启动。Windows 专项测试覆盖 broad inherited ACL 收紧、其他 owner/Authenticated Users 写入拒绝、公共只读允许，以及 Adapter 入口与全部随包 Python 模块 owner/DACL；CI Windows job 已纳入这些测试。

#### WIN-03 DPAPI、Named Pipe、日志与后台 Adapter 窗口

- 优先级：安全
- 状态：`PASS`（跨用户实机子项 `SKIPPED`）
- 操作：使用 `0.4.10` 启动同一 profile，确认受管 Adapter 不弹出 Python 控制台；重启后加载 DPAPI 身份；从其他用户尝试 Host Named Pipe；测试 `logs --follow`。
- 预期：后台启动不创建可见 Python 窗口；当前用户身份可恢复；其他用户不能控制 Host；日志只输出新增内容。
- 日期：2026-08-14（`0.4.10` Windows amd64 实机回归中）
- 证据/结果：0.4.5 实机已完成 DPAPI 身份创建、设备认证和 Adapter 启动，但发现 `start` 会显示 Python 控制台，关闭后 Supervisor 会按设计重启 Adapter 导致窗口再次出现。0.4.6 增加 `CREATE_NO_WINDOW` 后，Windows 重启并复用同一 profile，设备 ID、指纹和 Claude Agent ID 均保持不变，`windows-dpapi` 成功恢复，运行态为 `connected=true adapter=healthy restarts=0`，且不再出现 Python 窗口。2026-08-14 使用 0.4.10 实测 `logs --follow`：先输出既有日志一次，随后只追加新 session/prompt/complete 活动；Claude 正常完成 turn，日志仅记录 `prompt_len`，未出现完整 prompt、API Key、私钥或配对 secret。测试机没有第二个 Windows 本地账户，用户决定跳过跨用户实机尝试；代码复核确认 Named Pipe 使用受保护 DACL `D:P(A;;GA;;;<current-user-SID>)`，profile owner/DACL 拒绝合同已有 Windows 自动测试覆盖，因此本项其余验收通过并按约定收口。

### N. HTTPS/WSS 与部署门禁

#### TLS-01 HTTP 开发门禁

- 优先级：核心
- 状态：`PASS`
- 操作：用 HTTP origin 分别不带和带 `--allow-insecure-http` 连接受控开发环境。
- 预期：默认拒绝；显式开关后才允许；生产模式不能借该开关降级。
- 日期：2026-08-12
- 证据/结果：PASS（合同 + 实机）。HTTP 默认拒绝，显式 `--allow-insecure-http` 才能连接当前受控环境；保存的 profile 明确记录 insecure development 许可。生产 deployment 合同要求 HTTPS origin 和显式 CORS allowlist。

#### TLS-02 生产 HTTPS/WSS、origin 和 CORS

- 优先级：部署
- 状态：`BLOCKED`
- 操作：验证 HTTPS/WSS；测试带凭据、query、fragment 的 origin；测试缺失或不匹配的 CORS allowlist。
- 预期：合法 HTTPS/WSS 工作；非法 origin 和 CORS 配置 fail-fast/fail-closed。
- 日期：2026-08-13（本地合同 + 部署入口核查）
- 证据/结果：Backend `DeploymentSecurityTest` 与 `AgentPairingOriginTest` 窄测通过；Host 既有 TLS/origin 合同已通过，但本次复跑受本机 Go 1.24.5 与标准库版本冲突阻断，未改动代码。当前 nginx 仅监听 HTTP `8005`，Backend 监听 HTTP `8006`，无 `443` 监听、可信域名或证书，无法进行真实 HTTPS/WSS 配对、重连和 CORS 验收。需后续准备正式 HTTPS ingress 后重开本项。

#### TLS-03 反向代理 canonical origin 与公网路径

- 优先级：部署
- 状态：`BLOCKED`
- 操作：在 TLS 终止代理、内网/VPN 或公网入口完成配对和重连，核对连接 origin 与认证 canonical origin。
- 预期：代理别名不破坏签名；WSS 升级正常；跨公网/NAT 主链稳定。
- 日期：2026-08-13
- 证据/结果：本轮 BLOCKED（真实 TLS proxy 未部署）。连接 origin 与 authentication origin 分离、alias 签名合同以及公网 HTTP/NAT 主链已通过；服务器没有 443/证书/域名，生产 WSS upgrade 与 canonical proxy alias 必须在 TLS-02 环境一并验收。

### O. 签名发行包

#### REL-01 正常六平台发行包验证

- 优先级：发布
- 状态：`PASS`
- 操作：使用正式或测试签名流水线生成/下载发行物，验证 manifest、签名、版本、哈希和 bundle 内容。
- 预期：六个目标包均受签名 manifest 覆盖；Host、Adapter wrapper、Bridge 源与依赖文件完整；正常验证通过。
- 日期：2026-08-12
- 证据/结果：PASS（测试签名）。0.4.5 Linux/macOS/Windows × amd64/arm64 六包、manifest 与 signature 正常 verify；包内 Host/wrapper/Bridge/requirements 完整，测试源码已剔除；目录 755、可执行 755、源/archives/manifest/signature 644，group/other 可写输出被脚本拒绝。使用临时测试 key，不代表生产发布密钥注入已验收。

#### REL-02 篡改、未签名文件和公钥替换拒绝

- 优先级：安全
- 状态：`PASS`
- 操作：在副本中分别修改 artifact、manifest、signature，增加未签名文件/目录，并尝试给生产 binary 覆盖信任公钥。
- 预期：所有篡改或额外文件均验证失败；生产 artifact 不接受运行时公钥替换。
- 日期：2026-08-12
- 证据/结果：PASS（最终 0.4.5 artifact 实测）。修改 artifact、manifest、signature、加入 unsigned file 均失败；embedded production trust 拒绝 `--public-key-file` override。实际运行发行包不写 `__pycache__`，随包 Python source 也受启动前 write-trust 校验。

### P. 高级安全

#### SEC-01 错误签名、ID、challenge 和 nonce 重放

- 优先级：高级
- 状态：`PASS`
- 操作：使用受控测试客户端构造错误公钥签名、错误 deviceId/agentInstanceId、过期/重复 challenge、修改 payload 和 nonce 重放。
- 预期：全部被拒绝；失败 challenge 不可再次使用；认证前不能发送业务 RPC。
- 日期：2026-08-12
- 证据/结果：PASS（协议/HTTP/WSS 合同）。错误签名、变更 displayName/payload、错误 ID、origin mutation、过期/重复 challenge、proof 重放与认证前业务帧均拒绝；challenge 跨节点只消费一次。

#### SEC-02 配对限速和跨账号审批攻击

- 优先级：高级
- 状态：`PASS`
- 操作：在不影响共享环境的受控条件下尝试连续错误代码、跨账号 preview/approve 和过期请求。
- 预期：触发节点级限速；跨账号和过期请求被拒绝；响应不泄露配对对象详情。
- 日期：2026-08-12
- 证据/结果：PASS（用户实测 + 自动合同）。B 无法预览/批准锁定到 A 的短码，错误/过期码不泄露详情；限速窗口、来源+登录用户隔离、过期 reset、攻击 key 周期清理和 10,000 桶硬上限测试通过。多节点全局限速仍须 ingress/shared limiter，这是明确部署边界。

#### SEC-03 Host IPC 冒用、Adapter 冒用与故障隔离

- 优先级：高级
- 状态：`PASS`
- 操作：从未授权本地主体连接控制 IPC；让测试 Adapter 使用错误 nonce/agentInstanceId；模拟卡死、持续崩溃或队列压力。
- 预期：未授权连接和冒用被拒绝；Supervisor 有界退避；一个 Adapter 的故障/背压不关闭其他 Agent 或设备 WSS。
- 日期：2026-08-12
- 证据/结果：PASS（Go race/平台合同）。Unix 0600 socket/Windows current-user pipe、错误 nonce/instance、symlink/不可信 owner/可写 wrapper 或 Python module 均拒绝；Supervisor 5 次快速崩溃后失败，队列有界，一个 Adapter/stream 的背压不关闭另一 stream/WSS。

## 6. 问题记录

### ISSUE-001 verification URL 错误回退到后端内网地址

- 关联测试项：`PAIR-01`
- 严重级别：中
- 环境：Linux Agent，经公网连接未配置 public origin 的 Backend
- 实际结果：CLI 打印 `http://10.0.0.2:8006/device`，远端浏览器不可达。
- 预期结果：未配置 canonical origin 时沿用 Host 明确传入的 `--server`；Web 分 origin 时使用 `--web`。
- 处理状态：已关闭。`0.4.1` Linux 实机回归通过。

### ISSUE-002 初次配对短码未预绑定目标账号

- 关联测试项：`PAIR-01`、`SEC-02`
- 严重级别：高
- 环境：Linux Agent，浏览器误登录用户 B
- 实际结果：B 可以批准原计划给 A 的配对请求。
- 预期结果：出码前按 `--account` 把 pairing 绑定到目标 userId；其他账号不能预览或批准且响应不泄露请求详情。
- 处理状态：已关闭。自动合同测试和 `0.4.1` Linux 实机回归通过；针对恶意跨账号请求的完整攻击验收仍由 `SEC-02` 覆盖。

### ISSUE-003 Windows profile 被 POSIX 0700 检查误拒绝

- 关联测试项：Windows `PAIR-01/02` 回归、`WIN-02`
- 严重级别：阻塞
- 环境：Windows 11 x86_64，`silk-agent 0.4.1`
- 实际结果：`connect` 在创建 pairing 前退出：`host config directory permissions are too broad; expected 0700`。
- 预期结果：Windows 使用 owner/DACL 语义保护 profile，不检查 POSIX mode bit。
- 处理状态：已关闭。`0.4.2` 已按平台拆分 Unix mode 与 Windows DACL；管理员 PowerShell 实测又发现 profile owner 为 `BUILTIN\Administrators`，而非当前账号。`0.4.3` 仅在 elevated + Administrators owner 的组合下转交 owner 给当前用户，然后写入受保护可继承 DACL；其他用户 owner 仍拒绝。自动验证及 Windows 管理员 PowerShell 实机配对、设备认证、WSS 和 Adapter 启动回归均通过。

### ISSUE-004 已打开 `/device` 标签页粘贴配对链接不触发

- 关联测试项：`PAIR-01`、`PAIR-03`
- 严重级别：中
- 环境：Windows Chrome；已打开 `/device` 且输入框为空时，把新的 `http://<web-origin>/device#code=...` 粘贴到地址栏并回车。
- 实际结果：页面地址变化，但短码没有填入，也没有触发预览。
- 预期结果：同页 fragment 导航被消费，短码自动填入并触发已有预览流程；仍需用户明确点击“批准连接”。
- 处理状态：已关闭。WebApp 已注册 `hashchange` 监听，在 `/device` 路由消费有效短码、清理 fragment 并复用现有自动 preview；生产 bundle 已部署到当前 Web `8005`，版本标记 `20260812172241`。2026-08-13 用户在已打开的同一 `/device` 标签页完成真实回归，自动填码/预览正常。

### ISSUE-005 认证限速桶与历史配对记录无界增长

- 关联测试项：`SEC-02`、长期运行稳定性
- 严重级别：中
- 实际结果：原节点内存 limiter 没有 stale-key cleanup/总桶上限，配对事务也没有 retention 清理。
- 处理状态：已关闭。限速桶按窗口清理并设 10,000 硬上限；配对码按来源 + 登录主体隔离。新配对会清理过期超过 7 天的事务，安全审计事件保留。新增 4 项 Backend 测试并纳入 367 项完整回归。

### ISSUE-006 被撤销 Host 永久重连与 pairing redirect secret 边界

- 关联测试项：`REVOKE-02/03`、`NET-02`、`SEC-01`
- 严重级别：高
- 实际结果：服务端明确返回 `DEVICE_OR_AGENT_REVOKED` 后 Host 仍永久退避；pairing HTTP client 默认跟随 redirect，理论上可能把 poll secret 转发到非预期地址。
- 处理状态：已关闭。撤销成为该认证身份的 terminal 状态；全部启用身份均撤销时 Host 清理后退出并提示重新配对，多 Agent 可尝试存活身份。HTTP client 禁止自动 redirect。Go race 合同通过。

### ISSUE-007 Adapter 可信源码与日志/发行目录卫生不足

- 关联测试项：`PAIR-04`、`HOST-04`、`REL-01/02`、`SEC-03`、`WIN-02`
- 严重级别：高
- 实际结果：原先主要校验启动 wrapper/入口脚本，未覆盖所有被 import 的随包 Python 模块；Python 会生成 `__pycache__`；Claude raw I/O 调试开关和 vendor stderr/非 JSON 日志可能记录 prompt 内容；发行文件 mode 受调用方 umask 影响。
- 处理状态：已关闭。Unix/Windows 均校验 wrapper/入口和所有随包 `.py` 的 owner/write trust，发行包剔除测试源码，Host 禁止 bytecode 写回并屏蔽 raw I/O 开关，vendor 日志仅保留长度/状态元数据；发行脚本固定 022 umask、规范化 755/644 且拒绝 group/other 可写输出。最终 0.4.5 六包及负向篡改测试通过。

### ISSUE-008 受管 Codex 忽略自定义 provider 连接配置

- 关联测试项：`ACP-02`
- 严重级别：高
- 实际结果：受管 Codex 为隔离权限而使用 `--ignore-user-config`，同时丢弃了本机自定义 model provider/base URL，进程连接错误默认端点并长期停在 `turn.started`。
- 预期结果：设备用户的 Codex 原生认证和配置可用，同时 Silk 仍对核心命令执行施加 Binding-derived sandbox/shell 上限。
- 处理状态：已关闭。最初用严格 TOML 白名单恢复 provider 连接字段并完成真实 `CODEX-ACP-OK` 测试；随后根据设备原生体验优先的产品决定，受管 Codex 改为直接继承 `CODEX_HOME`、`config.toml`、`AGENTS.md`、rules、hooks、MCP、插件及模型/provider 配置。Silk 继续用显式 CLI 参数覆盖 sandbox、approval 和 shell feature gate，且不在受管路径启用 dangerous bypass。Claude Code 同步采用设备原生配置加核心本地文件/命令权限上限。

### ISSUE-009 Team Channel 停止按钮未取消 bound Agent

- 关联测试项：`ACP-04`
- 严重级别：高
- 实际结果：用户在 `test` Team Channel 停止正在运行的 Claude 后，按钮短暂恢复又回到生成状态，任务继续执行；Host 日志没有 `session/cancel`。
- 预期结果：TEAM 停止请求取消该 Room 中正在运行的 Claude/Codex ACP session，UI 退出生成状态，另一 Room 不受影响，随后可继续发送 prompt。
- 处理状态：已关闭。`ChatServer.handleStopGeneration` 现在调用 `AgentRuntime.cancelBoundTeamAgents(roomId)`，按 `room:<roomId>` context 取消所有运行中的 bound Agent，并继续清理内置 AI 和客户端状态。新增真实 Room/Binding/ACP transport 合同，与 AgentRuntime 取消测试合计 9 项通过；真实 UI 复测确认 Backend、Host 和 Claude 子进程均已停止，随后 prompt 可正常恢复；Backend 健康检查 200，Host 两个 Agent 保持 healthy。

### ISSUE-010 Windows `start` 显示受管 Adapter Python 控制台

- 关联测试项：`WIN-03`、`SEC-03`
- 严重级别：中
- 环境：Windows 11，`silk-agent 0.4.5`，直接 `start` 且 Claude Adapter 已启用
- 实际结果：Adapter 的 Python 控制台窗口可见；关闭窗口后 Supervisor 按设计重启 Adapter，窗口再次出现，容易被误认为 Host 异常。
- 预期结果：后台 Host/Adapter 运行不弹出可见控制台；Adapter 崩溃仍由 Supervisor 记录并按退避策略处理。
- 处理状态：已关闭。修复已进入 `0.4.6`：Windows Adapter 子进程保留 `CREATE_NEW_PROCESS_GROUP` 并增加 `CREATE_NO_WINDOW`；Windows-only 单元合同、普通/race/vet 和 amd64/arm64 交叉编译通过。Windows 实机升级后确认不再出现 Python 窗口，且原 DPAPI 身份保持不变，Adapter 为 `connected=true adapter=healthy restarts=0`。

### ISSUE-011 TEAM Binding 切换到 Windows Agent 后沿用 Linux cwd

- 关联测试项：`WIN-03`、`BIND-03`、`ACP-01`
- 严重级别：高
- 环境：同账号从 Linux Claude Binding 切换到 Windows Claude；Team Channel 发送纯消息 prompt
- 实际结果：Windows Claude 启动失败并返回 `[WinError 267] 目录名称无效`；TEAM Room 上下文把 Backend/Linux 默认工作目录作为 `session/new.cwd` 发送给 Windows Adapter。
- 预期结果：纯消息 TEAM 不依赖服务端或另一设备的文件路径；目标 Agent 使用自己设备上的安全默认目录。具有文件/命令权限的 Workspace 若目标目录不存在则应明确拒绝，不能静默换目录。
- 处理状态：已关闭。修复进入 Backend 与 `silk-agent 0.4.7`：Backend 对 TEAM 使用空 cwd；Claude/Codex Adapter 对无本地文件权限的纯消息 prompt 安全回退到本机默认目录，带 Workspace 权限的无效目录 fail-closed，`_silk/set_cwd` 也拒绝不存在的目录。Windows 实机升级后不再出现 `[WinError 267]`，后续 prompt 已进入 Claude 执行链。

### ISSUE-012 Windows/Claude ACP prompt 孤立 Unicode surrogate 导致 UTF-8 编码失败

- 关联测试项：`WIN-03`、`ACP-01`
- 严重级别：高
- 环境：Windows `silk-agent 0.4.7`，Team Channel 发送包含中文的普通 prompt
- 实际结果：Adapter 报 `发送 prompt 失败: 'utf-8' codec can't encode character '\udca4' ... surrogates not allowed`，Claude 子进程没有收到 prompt。
- 根因：JSON 的 escaped UTF-16 surrogate 可以被 Python `json.loads` 保留为孤立 surrogate；直接 `ensure_ascii=False` 后编码 UTF-8 会抛出异常。该输入不是正常中文本身的问题，而是消息链路中的 malformed Unicode code unit。
- 预期结果：正常 Unicode 保持不变；合法 surrogate pair 还原为一个 Unicode scalar；孤立 surrogate 在 ACP/CLI UTF-8 边界替换为 U+FFFD，prompt 继续执行，不因异常输入崩溃。
- 处理状态：已关闭，修复进入 `silk-agent 0.4.8`。新增 `bridge_common.unicode_safety`，Host IPC 入站/出站、Claude stdin JSON 和 Codex stdin 均采用递归/文本安全归一化；Windows 实机升级后不再出现 surrogate/UTF-8 编码错误，prompt 成功进入 Claude 认证与会话阶段。

### ISSUE-013 受管 Claude/Codex 与设备原生认证配置不一致

- 关联测试项：`ACP-02`、`WIN-03`
- 实际结果：为防止配置绕过而使用隔离配置时，Claude 无法读取设备 `settings.json` 中的认证来源，Codex 无法使用设备自定义 provider，表现为 Claude `403` 或 Codex 连接默认端点。
- 预期结果：Silk 上的受管 Agent 与用户在同一设备直接运行 CLI 使用同一认证、模型、provider、hooks、MCP、插件和规则；Binding 只增加必要的核心文件/命令权限上限，不改变设备的其他原生配置。
- 处理状态：已关闭，修复进入 `silk-agent 0.4.9`。Claude 移除 `--setting-sources user`/严格 MCP/工具白名单等过度隔离参数，恢复原生配置加载，仅保留 `--settings` deny、manual permission mode 和必要 sandbox/`--disallowedTools` 上限；Codex 移除 `--ignore-user-config`/`--ignore-rules`，恢复 `CODEX_HOME`、配置、规则、hooks、MCP 与插件，仅保留显式 sandbox、approval、shell gate。真实本机 Codex `CODEX-NATIVE-CONFIG-OK` 通过；Windows Claude 在后续 0.4.10 回归中已使用设备原生配置成功回复消息。

### ISSUE-014 Team `@agent /new` 被当作普通 prompt，旧 Claude session 无法恢复

- 关联测试项：`ACP-01`、`WIN-03`
- 实际结果：设备切换或本机历史清理后，Backend 仍持有旧 Claude session ID；Claude 返回 `No conversation found with session ID`。用户在 Team Channel 输入 `@cc /new` 时，Binding 去除 mention 后把 `/new` 直接作为普通 prompt 发给 Claude，因此再次触发同一错误。
- 预期结果：Team 的定向 `/new` 由 Silk 重置对应绑定 Agent 会话；若用户直接发送普通 prompt，Claude Adapter 对明确 missing-session 错误自动创建新 session 并只重试一次，认证、403、timeout 等其他错误不应被掩盖。
- 处理状态：已关闭。Backend Team 路由识别 `/new` 并在本地清理会话；`silk-agent 0.4.10` 的 Claude Adapter 增加窄范围自动恢复。Backend 370、Direct Bridge 123、Go test/race/vet 和 0.4.10 六平台测试签名包通过；2026-08-14 Backend 重启后，用户在 Windows Team Channel 实测 `@cc /new` 及后续 Claude 消息均正常，不再出现旧 session 错误。

## 7. 验收结束后的延期项目

Phase 0–7 当前约定范围已经完成，没有仍需测试人员立即操作的项目。以下能力以后具备相应环境或重新纳入范围时再验收：

1. **生产部署专项（TLS-02/03）**：当前服务器没有 443、可信域名和证书，已记录为 `BLOCKED`；以后准备 HTTPS/WSS reverse proxy 后再重开本组。
2. **用户级自动服务（SVC-01/WIN-01）**：设备原生 CLI 凭据环境的服务化配置尚未设计完成，按用户决定记录为 `SKIPPED`；当前推荐直接使用 `silk-agent start/run`。
3. **Phase 8 cc-connect**：仍不属于本轮范围，后续单独设计和验收。

## 8. 最终签署

只有满足以下条件才可将本文件状态改为“通过”：

- 所有核心项均为 `PASS`；
- 所有 `FAIL` 已修复并回归为 `PASS`，或有明确接受记录；
- `BLOCKED`/`SKIPPED` 项已说明环境边界和发布影响；
- Windows、TLS/WSS、跨公网和正式签名等无法由本地自动化替代的项目有真实环境证据；
- cc-connect 仍明确留在 Phase 8，不被误报为本次已验收。

签署结论（2026-08-14）：Phase 0–7 当前约定范围验收完成，49 项通过、0 项失败、0 项进行中、2 项因环境阻塞、2 项按决定跳过；所有验收中发现的功能问题均已修复并完成对应自动或实机回归。上述 `BLOCKED`/`SKIPPED` 边界不影响当前 HTTP 受控测试环境下采用直接 `silk-agent start/run` 的功能结论，但不能替代未来生产 HTTPS/WSS、正式服务化和 Phase 8 cc-connect 的专项验收。
