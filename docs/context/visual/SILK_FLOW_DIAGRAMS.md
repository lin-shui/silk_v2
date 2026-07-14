# Silk 系统流程与业务架构图

本文档使用 Mermaid 语法绘制 Silk 多端聊天系统的核心流程与业务架构图。

---

## 1. 系统业务架构总览

```mermaid
graph TB
    subgraph Frontend["前端层"]
        Web["🌐 WebApp<br/>Compose for Web"]
        Android["📱 AndroidApp<br/>Jetpack Compose"]
        Desktop["🖥️ DesktopApp<br/>Compose Desktop"]
        Harmony["📟 HarmonyApp<br/>ArkTS/ArkUI"]
        Shared["📦 frontend/shared<br/>消息模型/Ws客户端"]
    end

    subgraph Backend["后端层 - Ktor JVM"]
        App["Application.kt<br/>启动入口"]
        Routing["Routing.kt<br/>HTTP路由总入口"]
        WS["WebSocketConfig.kt<br/>ChatServer 消息主链"]
        AI["ai/DirectModelAgent.kt<br/>AI引擎 + Tool Calling"]
        Agent["agents/core/AgentRuntime.kt<br/>Agent框架 (ACP)"]
        DB["database/<br/>SQLite + Exposed"]
        
        subgraph Domain["业务领域模块"]
            Todo["todos/UserTodoStore.kt<br/>待办事项"]
            Workflow["workflow/WorkflowManager.kt<br/>工作流"]
            KB["kb/KnowledgeBaseManager.kt<br/>知识库"]
            Trust["trust/TrustedDirManager.kt<br/>目录信任"]
            Export["export/<br/>导出 (Obsidian/PDF)"]
            File["routes/FileRoutes.kt<br/>文件服务"]
            ASR["routes/AsrRoutes.kt<br/>语音识别"]
        end
        
        subgraph Storage["持久化存储"]
            SQLite["silk_database.db<br/>用户/群组/联系人/未读"]
            ChatHistory["chat_history/<br/>消息历史/上传文件"]
            WfStore["~/.silk-data/workflows/<br/>工作流存储"]
            KBStore["knowledge_base/<br/>知识库存储"]
        end
    end

    subgraph External["外部系统"]
        ClaudeCLI["Claude CLI"]
        CodexCLI["Codex CLI"]
        Cursor["Cursor IDE"]
        GeminiCLI["Gemini CLI"]
        
        subgraph Bridges["桥接适配器"]
            CCBridge["cc_bridge/acp_adapter.py<br/>ACP → Claude CLI"]
            CodexBridge["codex_bridge/codex_adapter.py<br/>ACP → Codex CLI"]
            FeishuBot["feishu_bot/main.py<br/>飞书网关"]
        end
        
        AudioWorker["Audio Duplex Worker<br/>(上游语音AI)"]
        AnthropicAPI["Anthropic Messages API"]
        ASRService["ASR 语音识别服务"]
    end

    Frontend -->|HTTP + WebSocket| Backend
    Shared --> Web
    Shared --> Android
    Shared --> Desktop
    Harmony -.->|独立实现| Backend
    
    Routing --> WS
    Routing --> File
    Routing --> ASR
    WS --> AI
    WS --> Agent
    Agent --> CCBridge
    Agent --> CodexBridge
    CCBridge --> ClaudeCLI
    CodexBridge --> CodexCLI
    
    Agent -.-> Cursor
    Agent -.-> GeminiCLI
    
    AI --> AnthropicAPI
    ASR --> ASRService
    
    FeishuBot -->|HTTP| Backend
    
    WS --> DB
    WS --> ChatHistory
    WS --> Domain
    
    Domain --> Storage
```

---

## 2. 运行时主消息流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant FE as 前端 (任意端)
    participant WS as ChatServer<br/>WebSocketConfig.kt
    participant History as ChatHistoryManager
    participant Agent as AgentRuntime
    participant AI as DirectModelAgent
    participant Bridge as ACP Bridge<br/>(cc_bridge / codex_bridge)
    participant CLI as Claude CLI / Codex CLI

    User->>FE: 发送消息
    FE->>WS: WebSocket 消息
    
    WS->>WS: ① 消息去重
    WS->>History: ② 写入内存历史
    History->>History: ③ 持久化到 session.json
    WS->>WS: ④ 更新未读计数
    WS->>WS: ⑤ 广播到所有会话成员
    
    alt 有活跃 Agent
        WS->>Agent: ⑥ AgentRuntime.handleIfActive()
        Agent->>Agent: CommandRouter 解析命令
        
        alt ACP Agent 模式
            Agent->>Bridge: ACP session/prompt
            Bridge->>CLI: 执行 prompt
            CLI-->>Bridge: session/update 流式回传
            Bridge-->>Agent: 流式响应
            Agent-->>WS: blocks_state 推送
            WS-->>FE: 实时渲染 (thinking/text/tool_use)
        else 直接 AI 模式
            WS->>AI: ⑦ DirectModelAgent 响应
            AI->>AnthropicAPI: Messages API (SSE)
            AnthropicAPI-->>AI: 流式 content blocks
            AI-->>WS: blocks_state 回调
            WS-->>FE: 实时渲染
        end
    else 普通文本消息
        WS->>WS: ⑦ 异步触发 URL/PDF 处理
        WS-->>FE: 确认消息已接收
    end
    
    Note over WS,CLI: 消息处理完成后
    WS->>History: 最终持久化 (含 AI 回复)
    History-->>WS: 完成
```

---

## 3. 聊天/WebSocket 消息处理详细流程

```mermaid
flowchart LR
    subgraph Input["消息入口"]
        A1["用户文本消息"]
        A2["文件上传"]
        A3["URL/PDF 链接"]
        A4["语音消息"]
    end

    subgraph Processing["ChatServer.broadcast() 处理管线"]
        B1["① 去重检测"]
        B2["② 写入内存历史"]
        B3["③ ChatHistoryManager 持久化"]
        B4["④ 未读计数更新"]
        B5["⑤ 广播到所有 Session 成员"]
        B6["⑥ URL/PDF 异步处理<br/>WebPageDownloader"]
        B7["⑦ Agent 拦截判断"]
    end

    subgraph AgentRoute["Agent 路由"]
        C1{"AgentRuntime<br/>handleIfActive()"}
        C2["CommandRouter 解析"]
        C3["ACP Agent 模式<br/>(Claude Code / Codex)"]
        C4["直接 AI 模式<br/>DirectModelAgent"]
    end

    subgraph Output["消息输出"]
        D1["blocks_state 流式推送<br/>(thinking/text/tool_use)"]
        D2["最终消息落盘"]
        D3["前端实时渲染"]
    end

    A1 --> B1
    A2 --> B1
    A3 --> B1
    A4 --> ASR["ASR 转写"] --> B1
    
    B1 --> B2 --> B3 --> B4 --> B5 --> B6
    B6 --> B7
    
    B7 --> C1
    C1 -->|"活跃 Agent"| C2
    C1 -->|"无 Agent"| C4
    
    C2 -->|"/cc / @agent 命令"| C3
    C2 -->|"普通文本"| C4
    
    C3 --> D1
    C4 --> D1
    D1 --> D2 --> D3
```

---

## 4. Agent 框架与 ACP 协议流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant FE as 前端
    participant WS as WebSocket Server
    participant AR as AgentRuntime
    participant CR as CommandRouter
    participant GAC as GroupAgentContext
    participant ACP as AcpClient
    participant Adapter as ACP Adapter<br/>(acp_adapter.py)
    participant CLI as Claude CLI / Codex CLI

    Note over AR,CLI: ① Agent 激活流程
    
    User->>FE: /cc 激活 Claude Code
    FE->>WS: 发送 /cc 消息
    WS->>AR: handleIfActive()
    AR->>CR: route(/cc)
    CR->>AR: 激活 agent (ClaudeCode)
    AR->>GAC: 创建/恢复上下文
    GAC->>GAC: 从 Workflow 加载 seed<br/>(workingDir, sessionId)
    AR->>ACP: 检查连接状态
    
    alt ACP 已连接
        ACP-->>AR: 就绪
        AR-->>WS: 确认激活
        WS-->>FE: 显示 Agent 已激活
    else ACP 未连接
        ACP-->>AR: 未连接
        AR-->>WS: 返回"未连接"错误
        WS-->>FE: 提示用户启动 bridge
    end

    Note over AR,CLI: ② Prompt 执行流程
    
    User->>FE: 发送提问消息
    FE->>WS: 文本消息
    WS->>AR: handleIfActive()
    AR->>ACP: session/prompt (JSON-RPC)
    ACP->>Adapter: WebSocket 转发
    Adapter->>CLI: Executor 执行
    
    CLI-->>Adapter: 流式输出
    Adapter-->>ACP: session/update (流式)
    ACP-->>AR: 流式更新
    
    loop 每个 content block
        AR-->>WS: blocks_state 推送
        WS-->>FE: 实时渲染
        FE-->>User: 显示思考/工具/文本
    end
    
    CLI-->>Adapter: 执行完成
    Adapter-->>ACP: session/update(完成)
    ACP-->>AR: prompt 完成
    
    AR->>AR: WorkflowPersistence<br/>保存 sessionId
    AR-->>WS: 最终消息
    WS-->>FE: 完整回复
    FE-->>User: 显示最终结果

    Note over AR,CLI: ③ AskUserQuestion 流程
    
    CLI-->>Adapter: PreToolUse hook 触发
    Adapter->>PermissionServer: 权限请求
    PermissionServer-->>Adapter: 阻塞等待
    Adapter-->>ACP: session/update<br/>(ask_user_question)
    ACP-->>AR: 问题通知
    AR-->>WS: 广播问题消息
    WS-->>FE: 显示问题
    FE-->>User: 等待回答
    
    User->>FE: 输入回答
    FE->>WS: 回答消息
    WS->>AR: handlePrompt
    AR->>ACP: _silk/resolve_question
    ACP->>Adapter: resolve_question
    Adapter->>PermissionServer: resolve_request
    PermissionServer-->>Adapter: 返回答案
    Adapter->>CLI: 继续执行
    
    CLI-->>Adapter: 后续输出
    Adapter-->>ACP: session/update
    ACP-->>AR: 流式继续
```

---

## 5. cc-connect 集成流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant FE as 前端 (Web/Android/Harmony)
    participant BE as Silk 后端
    participant CC as cc-connect<br/>(独立进程)
    participant Plugin as silk.go 插件
    participant Agent as Claude Agent

    Note over User,Agent: ① 群组创建与 Token 生成
    
    User->>FE: 创建 cc-connect 群组
    FE->>BE: API 请求创建群组
    BE->>BE: 生成连接 Token
    BE-->>FE: 返回 Token
    FE-->>User: 展示 Token

    Note over User,Agent: ② cc-connect 连接
    
    User->>CC: 将 Token 写入 config.toml
    User->>CC: 启动 cc-connect
    CC->>BE: WebSocket 连接 /ccconnect-bridge
    BE->>Plugin: 注册连接
    Plugin-->>BE: StreamingCardPlatform 就绪
    BE-->>CC: 连接确认

    Note over User,Agent: ③ 消息路由
    
    User->>FE: 发送消息 (单人模式 or @cc/@claude 前缀)
    
    alt 单人模式 (群组仅 1 名成员)
        FE->>BE: 文本/图片消息
        BE->>BE: CcConnectRegistry 检查
        BE->>BE: 直接转发 (无需 @ 前缀)
    else 多人模式
        FE->>BE: 消息含 @cc/@claude 前缀
        BE->>BE: GroupRepository 检查角色
        alt HOST / OPERATOR
            BE->>BE: 转发到 cc-connect
        else GUEST
            BE-->>FE: 忽略 (不触发 agent)
        end
    end
    
    BE->>CC: 转发消息
    CC->>Plugin: 处理消息
    Plugin->>Agent: 执行 prompt

    Note over User,Agent: ④ 流式响应聚合
    
    loop Agent Turn
        Agent-->>Plugin: thinking / tool_use / text
        Plugin-->>CC: reply_stream (全量替换)
        CC-->>BE: 流式回复
        BE->>BE: 按 emoji 边界拆分<br/>(思考/工具/正文)
        BE-->>FE: blocks_state 推送
        FE-->>User: 实时气泡渲染
    end
    
    Agent-->>Plugin: 最终回复
    Plugin-->>CC: reply (持久化)
    CC-->>BE: 最终消息
    BE->>BE: 添加 CC_TURN / THINKING_END 标记
    BE-->>FE: 结构化 Markdown
    FE-->>User: 显示完整回复 (工具调用折叠)

    Note over User,Agent: ⑤ 图片流程
    
    User->>FE: 上传含 @cc 说明的图片
    FE->>BE: UserMessage.images 字段
    BE->>CC: 转发图片 (base64)
    CC->>Plugin: 处理图片
    Plugin->>Agent: 图片分析
    
    Agent-->>Plugin: 回复图片 (SVG/PNG)
    Plugin->>Plugin: 检测新增图片文件
    Plugin-->>CC: reply_images
    CC-->>BE: 图片消息
    BE-->>FE: Markdown data URI
    FE-->>User: 原生渲染图片
```

---

## 6. cc-connect 消息聚合与渲染细节

```mermaid
flowchart TB
    subgraph AgentSide["Agent 端 (cc-connect)"]
        A1["Agent 开始 response"]
        A2["思考阶段"]
        A3["工具调用阶段"]
        A4["正文回复阶段"]
        A5["最终回复"]
    end

    subgraph Plugin["silk.go 插件处理"]
        P1["StreamingCardPlatform 接口<br/>聚合整个 turn"]
        P2["reply_stream (incremental: false)<br/>全量替换"]
        P3["reply (最终持久化)"]
        P4["检测图片文件<br/>→ reply_images"]
    end

    subgraph Backend["Silk 后端处理"]
        B1["接收 reply_stream"]
        B2["按 emoji 前缀拆分<br/>思考🧠 / 工具🔧 / 正文"]
        B3["构造结构化 Markdown<br/><!--CC_TURN--> / <!--THINKING_END-->"]
        B4["广播 blocks_state"]
        B5["最终消息落盘<br/>插入 <!--TOOLS_END-->"]
    end

    subgraph Frontend["前端渲染"]
        F1["临时气泡显示"]
        F2["识别 CC_TURN 标记"]
        F3["剥离标记<br/>Markdown 渲染"]
        F4["工具调用折叠为 &lt;details&gt;"]
        F5["思考区折叠渲染"]
        F6["最终展示"]
    end

    A1 --> A2 --> A3 --> A4 --> A5
    A2 --> P1
    A3 --> P1
    A4 --> P1
    A5 --> P3
    
    P1 --> P2 --> B1
    
    B1 --> B2 --> B3 --> B4
    B3 --> B5
    
    B4 --> F1
    F1 --> F2 --> F3 --> F4 --> F5 --> F6
    
    P3 --> B5
    
    P4 -->|reply_images| B5
```

---

## 7. 业务领域模块关系图

```mermaid
graph TB
    subgraph Core["核心服务层"]
        Auth["用户认证<br/>(auth/)"]
        Group["群组与成员<br/>(database/GroupRepository)"]
        Contact["通讯录与好友<br/>(database/)"]
        Unread["未读计数<br/>(database/)"]
        Settings["用户设置<br/>(database/)"]
    end

    subgraph Business["业务领域层"]
        Todo["待办事项<br/>todos/UserTodoStore"]
        WF["工作流<br/>workflow/WorkflowManager"]
        KB["知识库<br/>kb/KnowledgeBaseManager"]
        TrustDir["目录信任<br/>trust/TrustedDirManager"]
    end

    subgraph AILayer["AI 智能层"]
        DM["DirectModelAgent<br/>AI 主引擎"]
        AC["AnthropicClient<br/>Messages API 通信"]
        TP["ToolPolicyManager<br/>工具权限控制"]
        Search["searchContext()<br/>grep 搜索"]
        WPD["WebPageDownloader<br/>URL/PDF 提取"]
    end

    subgraph AgentLayer["Agent 框架层"]
        AR["AgentRuntime<br/>统一入口"]
        CR["CommandRouter<br/>命令解析"]
        GAC["GroupAgentContext<br/>用户/群组上下文"]
        AS["AgentSession<br/>会话状态管理"]
        ACPReg["AcpRegistry<br/>ACP 连接注册"]
        ACPCli["AcpClient<br/>ACP 协议客户端"]
        
        subgraph Adapters["适配器描述符"]
            CCDesc["ClaudeCodeDescriptor"]
            CodexDesc["CodexDescriptor"]
        end
    end

    subgraph ExternalLayer["外部集成层"]
        CCAdapter["cc_bridge/acp_adapter.py<br/>ACP→Claude CLI"]
        CodexAdapter["codex_bridge/codex_adapter.py<br/>ACP→Codex CLI"]
        Feishu["feishu_bot<br/>飞书网关"]
        CCConnect["cc-connect<br/>外部 AI 代理集成"]
    end

    subgraph Storage["持久化存储"]
        SQLite["SQLite<br/>结构化数据"]
        FS["文件系统<br/>JSON + 文件"]
    end

    %% 领域依赖关系
    Todo --> FS
    WF --> FS
    KB --> FS
    TrustDir --> FS
    Auth --> SQLite
    Group --> SQLite
    Contact --> SQLite
    Unread --> SQLite
    Settings --> SQLite

    %% AI 依赖
    DM --> AC
    DM --> TP
    DM --> Search
    DM --> WPD
    AC -->|HTTP SSE| Anthropic["Anthropic API"]

    %% Agent 框架依赖
    AR --> CR
    AR --> GAC
    AR --> AS
    AR --> ACPReg
    ACPReg --> ACPCli
    AR --> WF
    AR --> CCDesc
    AR --> CodexDesc

    %% 外部连接
    ACPCli -->|WebSocket| CCAdapter
    ACPCli -->|WebSocket| CodexAdapter
    CCAdapter --> ClaudeCLI["Claude CLI"]
    CodexAdapter --> CodexCLI["Codex CLI"]
    Feishu -->|HTTP| Core
    CCConnect -->|WebSocket| Core
```

---

## 8. Audio Duplex 音频双工流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant FE as 前端 (Web/Android/Harmony)
    participant BE as Silk 后端
    participant Worker as Audio Duplex Worker
    participant AI as 语音 AI 服务

    User->>FE: 打开 Audio Duplex 页面
    FE->>BE: WebSocket 连接 /ws/audio-duplex?sessionId=...
    BE->>BE: 读取 AIConfig.AUDIO_DUPLEX_URL
    BE-->>FE: 连接确认
    
    loop 实时对话
        User->>FE: 说话 (麦克风输入)
        FE->>BE: WebSocket 音频数据帧
        
        BE->>Worker: 代理转发音频流
        Worker->>AI: 语音识别 + 语义理解
        
        AI-->>Worker: 语义响应 + 语音合成
        Worker-->>BE: 代理转发响应
        
        BE-->>FE: WebSocket 音频/文本帧
        FE-->>User: 播放语音 + 显示文本
    end
    
    User->>FE: 关闭页面
    FE->>BE: WebSocket 断开
    BE->>Worker: 代理断开
```

---

## 9. 文件上传/下载与 URL 摄入流程

```mermaid
flowchart TB
    subgraph Upload["文件上传"]
        U1["用户选择文件"]
        U2["FileRoutes.kt<br/>POST /api/files/upload"]
        U3["保存到 chat_history/&lt;session&gt;/uploads/"]
        U4["生成文件消息卡片"]
        U5["PDF 文本提取 → _text.txt<br/>(供 AI grep 搜索)"]
    end

    subgraph URL["URL/PDF 摄入"]
        R1["消息含 URL 链接"]
        R2["WebPageDownloader<br/>异步处理"]
        R3["HTML 内容提取"]
        R4["PDF 下载与文本提取"]
        R5["持久化到 uploads/"]
        R6["生成文件消息"]
        R7["processed_urls.txt<br/>去重缓存"]
    end

    subgraph Download["文件下载"]
        D1["前端点击下载"]
        D2["FileRoutes.kt<br/>GET /api/files/download/{sessionId}/{fileId}"]
        D3["读取 uploads/ 目录"]
        D4["APK / HAP 版本查询"]
        D5["后端静态服务<br/>backend/static/"]
    end

    subgraph Search["AI 搜索"]
        S1["DirectModelAgent<br/>searchContext()"]
        S2["grep 搜索 _text.txt"]
        S3["grep 搜索 session.json"]
        S4["结果截断 30000 字符"]
        S5["accessibleSessionIds 隔离"]
    end

    U1 --> U2 --> U3 --> U4
    U3 --> U5
    
    R1 --> R2 --> R3 --> R5 --> R6
    R2 --> R4 --> R5
    R5 --> R7
    
    D1 --> D2 --> D3 --> D4
    D3 --> D5
    
    U5 --> S1 --> S2 --> S4
    R6 --> S1 --> S3 --> S4
    S2 --> S5
    S3 --> S5
```

---

## 10. 多端前端架构图

```mermaid
graph TB
    subgraph Shared["frontend/shared · 共享合同层"]
        Msg["models/Message.kt<br/>消息模型"]
        CC["models/UserSettings.kt<br/>CC 状态模型"]
        Audio["models/AudioDuplexModels.kt<br/>音频双工模型"]
        Client["ChatClient.kt<br/>WebSocket 客户端"]
    end

    subgraph Web["frontend/webApp · Compose for Web"]
        W_Routes["WebSocket 连接管理"]
        W_Chat["聊天界面"]
        W_Todo["待办事项"]
        W_KB["知识库"]
        W_Workflow["工作流"]
        W_Audio["Audio Duplex"]
        W_CC["cc-connect 集成界面"]
    end

    subgraph Android["frontend/androidApp · Jetpack Compose"]
        A_Tab["四 Tab 导航"]
        A_Chat["聊天 Tab"]
        A_Contact["通讯录 Tab"]
        A_KB["知识库 Tab"]
        A_Settings["设置 Tab"]
        A_Audio["Audio Duplex"]
        A_CC["cc-connect 集成"]
    end

    subgraph Desktop["frontend/desktopApp · Compose Desktop"]
        D_Chat["聊天界面"]
    end

    subgraph Harmony["frontend/harmonyApp · ArkTS/ArkUI (独立)"]
        H_Chat["聊天"]
        H_Todo["待办事项"]
        H_Workflow["工作流"]
        H_KB["知识库"]
        H_Audio["Audio Duplex"]
    end

    Shared --> Web
    Shared --> Android
    Shared --> Desktop
    Harmony -.->|不复用 shared| Backend["Silk 后端"]
    Web --> Backend
    Android --> Backend
    Desktop --> Backend
```

---

## 11. 持久化存储与数据流

```mermaid
flowchart TB
    subgraph DataSources["数据源"]
        UMsg["用户消息<br/>(WebSocket)"]
        UFile["用户文件<br/>(HTTP Upload)"]
        UTodo["待办操作<br/>(HTTP API)"]
        UWf["工作流操作<br/>(HTTP API)"]
        UKB["知识库操作<br/>(HTTP API)"]
        UAuth["认证请求<br/>(HTTP API)"]
    end

    subgraph Process["处理层"]
        WS["ChatServer<br/>消息主链"]
        FR["FileRoutes<br/>文件路由"]
        TodoAPI["Todo API"]
        WfAPI["Workflow API"]
        KbAPI["KB API"]
        AuthAPI["Auth API"]
    end

    subgraph Store["存储层"]
        SQLite["silk_database.db<br/>· 用户/群组/联系人<br/>· 未读计数<br/>· 用户设置"]
        
        subgraph FileSys["文件系统"]
            Hist["chat_history/&lt;session&gt;/<br/>session.json"]
            Uploads["chat_history/&lt;session&gt;/uploads/"]
            TodoJson["chat_history/user_todos/<br/>*.json"]
            WfJson["~/.silk-data/workflows/<br/>workflow_store.json"]
            TrustJson["~/.silk-data/workflows/<br/>trusted_dirs.json"]
            KBJson["knowledge_base/<br/>kb_store.json"]
            Static["backend/static/<br/>APK/HAP/Web"]
        end
    end

    subgraph Override["路径覆盖机制"]
        O1["-Dsilk.databasePath=..."]
        O2["-Dsilk.chatHistoryDir=..."]
        O3["-Dsilk.userTodoBaseDir=..."]
        O4["SILK_WORKFLOW_DIR / -Dsilk.workflowDir=..."]
        O5["-Dsilk.kbDir=..."]
    end

    UMsg --> WS
    UFile --> FR
    UTodo --> TodoAPI
    UWf --> WfAPI
    UKB --> KbAPI
    UAuth --> AuthAPI

    WS --> SQLite
    WS --> Hist
    WS --> Uploads
    FR --> Uploads
    
    TodoAPI --> TodoJson
    WfAPI --> WfJson
    KbAPI --> KBJson
    AuthAPI --> SQLite

    O1 -.-> SQLite
    O2 -.-> Hist
    O3 -.-> TodoJson
    O4 -.-> WfJson
    O4 -.-> TrustJson
    O5 -.-> KBJson
```

---

## 12. 飞书网关集成流程

```mermaid
sequenceDiagram
    participant User as 飞书用户
    participant Feishu as 飞书平台
    participant Bot as feishu_bot<br/>(main.py)
    participant Silk as Silk 后端
    participant AI as AI 引擎

    User->>Feishu: 发送消息给机器人
    Feishu->>Bot: Webhook / 事件回调
    
    Bot->>Bot: feishu_handler.py 解析消息
    Bot->>Bot: user_binding.py 查询绑定
    
    alt 未绑定
        Bot-->>Feishu: 提示用户绑定账号
        Feishu-->>User: "请先绑定 Silk 账号"
    else 已绑定
        Bot->>Silk: silk_client.py HTTP 请求
        Silk->>AI: 处理消息请求
        AI-->>Silk: 生成回复
        Silk-->>Bot: 返回回复内容
        Bot->>Bot: streaming.py 流式处理
        Bot-->>Feishu: 逐段发送回复
        Feishu-->>User: 显示回复
    end
```

---

## 索引

| 图号 | 名称 | 说明 |
|------|------|------|
| 1 | 系统业务架构总览 | 全栈组件关系图，涵盖前端、后端、外部系统 |
| 2 | 运行时主消息流程 | 用户消息从发送到回复的完整时序 |
| 3 | 聊天/WebSocket 消息处理详细流程 | ChatServer.broadcast() 处理管线 |
| 4 | Agent 框架与 ACP 协议流程 | Agent 激活、Prompt 执行、AskUserQuestion 完整流程 |
| 5 | cc-connect 集成流程 | 外部 AI 代理的群组集成全链路 |
| 6 | cc-connect 消息聚合与渲染细节 | 流式消息聚合、拆分、前端渲染 |
| 7 | 业务领域模块关系图 | 核心服务、业务领域、AI、Agent、外部集成依赖 |
| 8 | Audio Duplex 音频双工流程 | 实时语音对话代理 |
| 9 | 文件上传/下载与 URL 摄入流程 | 文件、链接处理与 AI 搜索 |
| 10 | 多端前端架构图 | 四端前端代码组织与共享合同 |
| 11 | 持久化存储与数据流 | 数据源、处理层、存储与覆盖机制 |
| 12 | 飞书网关集成流程 | 飞书到 Silk 的网关消息流 |
