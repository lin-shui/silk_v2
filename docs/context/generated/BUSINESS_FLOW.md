# Silk 业务运营流程图

> 自动生成，反映当前架构与业务逻辑。

---

## 1. 系统架构总览

```mermaid
graph TB
    subgraph "前端层"
        WEB["🌐 WebApp<br/>Compose for Web"]
        AND["📱 Android App<br/>Jetpack Compose"]
        DESK["🖥 Desktop App<br/>Compose Desktop"]
        HARMONY["📱 HarmonyOS App<br/>ArkTS/ArkUI"]
        FEISHU["💬 飞书 Bot<br/>Python 网关"]
    end

    subgraph "通信层"
        WS["WebSocket<br/>/chat"]
        HTTP["HTTP REST API"]
        AUDIO_WS["WebSocket<br/>/ws/audio-duplex"]
    end

    subgraph "后端 Ktor JVM"
        ROUTING["Routing.kt<br/>HTTP 路由总入口"]
        CHAT_SERVER["ChatServer<br/>WebSocketConfig.kt"]
        AGENT_RUNTIME["AgentRuntime<br/>Agent 框架核心"]
        DIRECT_AI["DirectModelAgent<br/>Anthropic Messages API"]
        SEARCH["searchContext()<br/>Grep 搜索"]
        ASR["ASR Routes<br/>语音转文字"]
        ACP_REGISTRY["AcpRegistry<br/>ACP 连接管理"]
    end

    subgraph "Agent 执行层"
        CC_BRIDGE["cc_bridge/acp_adapter.py<br/>Claude Code CLI"]
        CODEX_BRIDGE["codex_bridge/codex_adapter.py<br/>Codex CLI"]
        CC_CONNECT["cc-connect 插件<br/>外部 AI 代理"]
    end

    subgraph "存储层"
        SQLITE["SQLite<br/>silk_database.db"]
        CHAT_HISTORY["chat_history/<br/>session.json"]
        WORKFLOW["~/.silk-data/workflows/<br/>workflow_store.json"]
        KB["knowledge_base/<br/>kb_store.json"]
        UPLOADS["chat_history/<session>/uploads/"]
    end

    subgraph "外部服务"
        ANTHROPIC["Anthropic Messages API"]
        AI_ASR["OpenAI-compatible ASR"]
        AUDIO_DUPLEX["Audio Duplex Worker"]
        WEAVIATE["Weaviate<br/>向量数据库（备选）"]
    end

    WEB --> WS
    WEB --> HTTP
    AND --> WS
    AND --> HTTP
    DESK --> WS
    DESK --> HTTP
    HARMONY --> WS
    HARMONY --> HTTP
    HARMONY --> AUDIO_WS
    FEISHU --> HTTP

    WS --> CHAT_SERVER
    WS --> AUDIO_WS
    HTTP --> ROUTING

    CHAT_SERVER --> AGENT_RUNTIME
    CHAT_SERVER --> DIRECT_AI
    DIRECT_AI --> ANTHROPIC
    DIRECT_AI --> SEARCH

    AGENT_RUNTIME --> ACP_REGISTRY
    ACP_REGISTRY --> CC_BRIDGE
    ACP_REGISTRY --> CODEX_BRIDGE
    ACP_REGISTRY --> CC_CONNECT

    CHAT_SERVER --> CHAT_HISTORY
    CHAT_SERVER --> SQLITE
    ROUTING --> WORKFLOW
    ROUTING --> KB
    ROUTING --> UPLOADS

    AUDIO_WS --> AUDIO_DUPLEX
    ASR --> AI_ASR
    SEARCH --> WEAVIATE
```

---

## 2. 聊天消息处理管道（核心业务流）

```mermaid
sequenceDiagram
    participant User as 用户
    participant FE as 前端 (Web/Android/Desktop/Harmony)
    participant WS as WebSocket 网关
    participant CS as ChatServer
    participant AG as AgentRuntime
    participant DM as DirectModelAgent
    participant HIST as ChatHistoryManager
    participant AD as ACP Adapter (CC/Codex)

    User->>FE: 发送消息
    FE->>WS: WebSocket connect(token/groupId)
    WS->>CS: 建立 session
    CS->>HIST: 回放历史消息
    HIST-->>FE: 推送历史

    FE->>WS: 发送 Message(text/files/images)
    WS->>CS: broadcast()

    CS->>CS: 1. 去重校验
    CS->>CS: 2. 写内存历史
    CS->>HIST: 3. 持久化消息
    CS->>CS: 4. 更新未读计数
    CS-->>FE: 5. 广播到群组所有 session

    CS->>CS: 6. 异步触发 URL/PDF 处理

    CS->>AG: 7. handleIfActive()

    alt Agent 已激活 (CC/Codex/cc-connect)
        AG->>AG: CommandRouter.route()
        alt 普通 prompt
            AG->>AD: ACP session/prompt
            AD->>AD: 执行 CLI
            AD-->>AG: stream session/update
            AG-->>FE: stream blocks_state
            FE->>FE: 实时渲染 thinking/text/tool_use
            AD-->>AG: prompt response (final)
            AG->>HIST: 持久化 agent 回复
        else AskUserQuestion
            AD->>AD: PreToolUse hook 拦截
            AD-->>AG: session/update(ask_user_question)
            AG-->>FE: 广播问题消息
            FE->>User: 显示提问与选项
            User->>FE: 回答
            FE->>WS: 发送回答
            WS->>AG: handleQuestionReply
            AG->>AD: ACP _silk/resolve_question
            AD->>AD: hook 返回 → CLI 继续
        end
    else Agent 未激活
        CS->>DM: DirectModelAgent 响应
        DM->>DM: 调用 Anthropic Messages API
        DM-->>CS: stream content blocks
        CS-->>FE: stream blocks_state
        FE->>FE: 实时渲染 AI 回复
    end
```

---

## 3. 前端 Tab 导航与用户操作流程

```mermaid
graph TB
    LOGIN["登录页<br/>JWT 认证"]

    subgraph "主界面 (NavRail 导航)"
        SILK_TAB["Silk Tab<br/>聊天"]
        WORKFLOW_TAB["Workflow Tab<br/>编程工作流"]
        KB_TAB["Knowledge Base Tab<br/>知识库"]
        AUDIO_TAB["Audio Duplex Tab<br/>语音对话"]
    end

    subgraph "Silk 聊天"
        GROUP_LIST["群组/联系人列表"]
        CHAT_ROOM["聊天室"]
        CHAT_INPUT["消息输入 (Text/File/Image/Mic)"]
        AI_REPLY["AI 实时回复<br/>Markdown + Thinking + ToolUse"]
    end

    subgraph "Workflow 工作流"
        WF_LIST["工作流列表<br/>+ 创建按钮"]
        WF_CREATE["创建工作流<br/>输入名称/描述/目录"]
        FOLDER_PICKER["目录选择器<br/>面包屑导航 + 信任验证"]
        WF_CHAT["工作流聊天<br/>CC 模式始终激活"]
        DIR_TRUST["目录信任管理<br/>per-user per-bridge"]
    end

    subgraph "Knowledge Base"
        KB_LIST["知识库文档列表"]
        KB_UPLOAD["上传文档"]
        KB_SEARCH["搜索知识库"]
    end

    subgraph "Audio Duplex"
        AD_START["开始语音对话"]
        AD_ACTIVE["实时语音传输"]
        AD_STOP["结束对话"]
    end

    LOGIN --> SILK_TAB
    LOGIN --> WORKFLOW_TAB
    LOGIN --> KB_TAB
    LOGIN --> AUDIO_TAB

    SILK_TAB --> GROUP_LIST
    GROUP_LIST --> CHAT_ROOM
    CHAT_ROOM --> CHAT_INPUT
    CHAT_INPUT --> AI_REPLY

    WORKFLOW_TAB --> WF_LIST
    WF_LIST --> WF_CREATE
    WF_CREATE --> FOLDER_PICKER
    FOLDER_PICKER --> DIR_TRUST
    DIR_TRUST --> WF_CHAT
    WF_CHAT --> AI_REPLY
    WF_LIST --> WF_CHAT

    KB_TAB --> KB_LIST
    KB_LIST --> KB_UPLOAD
    KB_LIST --> KB_SEARCH

    AUDIO_TAB --> AD_START
    AD_START --> AD_ACTIVE
    AD_ACTIVE --> AD_STOP
```

---

## 4. 多端消息合同与跨端流转

```mermaid
graph LR
    subgraph "合同定义层 frontend/shared"
        MESSAGE["Message 模型<br/>MessageType / MessageCategory"]
        CLIENT["ChatClient<br/>WebSocket 状态机"]
        API["ApiResponses<br/>HTTP 响应 DTO"]
        SETTINGS["UserSettings<br/>CC DTO / 目录协议"]
        AUDIO_MODELS["AudioDuplexModels<br/>语音会话状态"]
    end

    subgraph "前端消费端"
        WEB_UI["WebApp<br/>Compose for Web"]
        ANDROID_UI["Android App<br/>Jetpack Compose"]
        DESKTOP_UI["Desktop App<br/>Compose Desktop"]
    end

    subgraph "独立端"
        HARMONY_UI["HarmonyOS App<br/>ArkTS/ArkUI<br/>手动同步合同"]
    end

    subgraph "后端生产端"
        BACKEND_WS["WebSocketConfig.kt<br/>消息生产"]
        BACKEND_ROUTES["Routing.kt<br/>HTTP 响应"]
    end

    MESSAGE --> WEB_UI
    MESSAGE --> ANDROID_UI
    MESSAGE --> DESKTOP_UI
    MESSAGE --> BACKEND_WS

    CLIENT --> WEB_UI
    CLIENT --> ANDROID_UI
    CLIENT --> DESKTOP_UI

    API --> BACKEND_ROUTES
    API --> WEB_UI
    API --> ANDROID_UI
    API --> DESKTOP_UI

    SETTINGS --> BACKEND_ROUTES
    SETTINGS --> WEB_UI

    AUDIO_MODELS --> WEB_UI
    AUDIO_MODELS --> ANDROID_UI
    AUDIO_MODELS --> HARMONY_UI

    HARMONY_NOTE["⚠️ Harmony 不复用 shared 模块，需手工保持协议同步"]
    HARMONY_UI -.- HARMONY_NOTE
```

---

## 5. Workflow + Agent 完整执行流

```mermaid
sequenceDiagram
    participant User as 用户
    participant FE as 前端
    participant API as HTTP API
    participant WS as WebSocket
    participant RT as AgentRuntime
    participant WM as WorkflowManager
    participant ACP as ACP Adapter
    participant CLI as Claude Code / Codex CLI

    %% 创建工作流
    User->>FE: 打开 Workflow Tab
    FE->>FE: 显示工作流列表
    User->>FE: 点击"创建"
    FE->>API: POST /api/workflows
    API->>API: 验证目录信任状态
    alt 目录未信任
        API-->>FE: 400 DIRECTORY_NOT_TRUSTED
        FE->>User: 跳转信任管理
        User->>FE: 添加信任
        FE->>API: POST /users/{id}/trusted-dirs
    end
    API->>WM: 创建工作流 + 聊天群组
    WM-->>API: workflowId
    API-->>FE: 创建成功

    %% 进入工作流聊天
    FE->>WS: 连接群组 WebSocket
    WS->>RT: autoActivateForWorkflow()
    RT->>WM: 读 workflow.activeAgent
    RT->>WM: 读 agentSessions[agentType]
    RT-->>WS: CC 模式激活（无需 /cc）
    WS-->>FE: 确认 CC 模式

    %% 发送编程任务
    User->>FE: 输入编程任务
    FE->>WS: 发送消息
    WS->>RT: handleIfActive()
    RT->>ACP: ACP session/prompt
    ACP->>CLI: 执行 prompt
    CLI-->>ACP: stream 回复
    ACP-->>RT: stream session/update
    RT-->>WS: stream blocks_state
    WS-->>FE: 实时渲染
    FE->>User: 实时看到 Thinking → Tool Use → 结果

    CLI-->>ACP: prompt response (meta.cliSessionId)
    ACP-->>RT: 最终回复
    RT->>WM: 持久化 sessionId/activeAgent
    RT-->>WS: 完整回复广播
```

---

## 6. 部署与运维流程

```mermaid
graph TB
    subgraph "开发阶段"
        DEV["本地开发<br/>macOS / Linux"]
        BUILD["构建产物"]
    end

    subgraph "构建流程"
        WEB_BUILD["./silk.sh build<br/>WebApp Kotlin/JS 编译<br/>→ backend/static/"]
        APK_BUILD["./silk.sh build-apk<br/>Android APK 编译<br/>→ backend/static/"]
        HAP_BUILD["./silk.sh build-hap<br/>Harmony HAP 编译<br/>→ backend/static/"]
    end

    subgraph "部署启动"
        DEPLOY["./silk.sh deploy<br/>一键部署"]
        START["./silk.sh start<br/>增量启动"]
        STOP["./silk.sh stop<br/>停止服务"]
        STATUS["./silk.sh status<br/>查看状态"]
    end

    subgraph "运行时服务"
        BACKEND["Ktor Backend<br/>:8006"]
        WEB_SERVER["Web 静态服务器<br/>:8005"]
        WEAVIATE["Weaviate Docker<br/>:8008 / :50051"]
        CC_ADAPTER["cc_bridge<br/>ACP Adapter"]
        CODEX_ADAPTER["codex_bridge<br/>ACP Adapter"]
    end

    subgraph "日志与监控"
        LOGS["./silk.sh logs<br/>tail 日志"]
        HEALTH["/health 端点<br/>健康检查"]
    end

    DEV --> WEB_BUILD
    DEV --> APK_BUILD
    DEV --> HAP_BUILD

    WEB_BUILD --> DEPLOY
    APK_BUILD --> DEPLOY
    HAP_BUILD --> DEPLOY

    DEPLOY --> BACKEND
    DEPLOY --> WEB_SERVER
    DEPLOY --> WEAVIATE
    START --> BACKEND
    START --> WEB_SERVER
    START --> WEAVIATE

    BACKEND --> LOGS
    WEB_SERVER --> LOGS

    BACKEND --> CC_ADAPTER
    BACKEND --> CODEX_ADAPTER

    STOP --> BACKEND
    STOP --> WEB_SERVER
    STOP --> WEAVIATE

    STATUS --> BACKEND
    STATUS --> WEB_SERVER
    STATUS --> WEAVIATE
    HEALTH --> STATUS
```

---

## 7. 数据持久化全景

```mermaid
graph TB
    subgraph "运行时数据库"
        DB_SQLITE["silk_database.db<br/>SQLite (Exposed ORM)"]
        DB_AUTH["用户认证<br/>auth/*"]
        DB_GROUPS["群组/联系人<br/>groups/* / contacts/*"]
        DB_UNREAD["未读计数<br/>unread/*"]
    end

    subgraph "文件存储"
        FS_CHAT["chat_history/{session}/<br/>session.json + uploads/"]
        FS_WORKFLOW["~/.silk-data/workflows/<br/>workflow_store.json"]
        FS_TRUST["~/.silk-data/workflows/<br/>trusted_dirs.json"]
        FS_KB["knowledge_base/<br/>kb_store.json"]
        FS_TODO["chat_history/user_todos/<br/>{user}.json"]
        FS_URL_CACHE["processed_urls.txt<br/>URL 去重缓存"]
    end

    subgraph "持久化触发时机"
        TRIGGER_CHAT["每条消息收发<br/>ChatServer.broadcast()"]
        TRIGGER_WF["工作流创建/切换 agent/<br/>改目录/prompt 响应"]
        TRIGGER_TODO["Todo 抽取/刷新"]
        TRIGGER_KB["知识库文档管理"]
        TRIGGER_URL["URL/PDF 下载提取"]
    end

    TRIGGER_CHAT --> FS_CHAT
    TRIGGER_CHAT --> DB_SQLITE
    TRIGGER_WF --> FS_WORKFLOW
    TRIGGER_WF --> FS_TRUST
    TRIGGER_TODO --> FS_TODO
    TRIGGER_KB --> FS_KB
    TRIGGER_URL --> FS_URL_CACHE
```

---

## 8. 消息类型与内容块渲染

```mermaid
graph LR
    subgraph "消息分类"
        TEXT["Text<br/>普通文本"]
        IMAGE["Image<br/>图片文件"]
        FILE["File<br/>文件附件"]
        SYSTEM["System<br/>系统消息"]
        AI_MSG["AI<br/>AI 回复"]
        AGENT_MSG["Agent<br/>Agent 回复"]
        TRANSIENT["Transient<br/>临时增量"]
    end

    subgraph "Content Block 类型"
        CB_THINKING["🤔 ThinkingBlock<br/>折叠渲染"]
        CB_TEXT["📝 Text/Markdown<br/>MarkdownContent"]
        CB_TOOL["🔧 ToolCallBlock<br/>工具调用详情"]
    end

    subgraph "cc-connect 增强"
        CC_TURN["<!--CC_TURN--><br/>分阶段标记"]
        THINK_END["<!--THINKING_END--><br/>思考结束标记"]
        TOOLS_END["<!--TOOLS_END--><br/>工具调用折叠"]
    end

    AI_MSG --> CB_THINKING
    AI_MSG --> CB_TEXT
    AI_MSG --> CB_TOOL
    AGENT_MSG --> CC_TURN
    AGENT_MSG --> THINK_END
    AGENT_MSG --> TOOLS_END
```

---

## 图例说明

| 颜色 | 含义 |
|------|------|
| 🟦 蓝色框 | 前端用户界面 |
| 🟩 绿色框 | 后端服务/组件 |
| 🟧 橙色框 | 外部 Agent/Adapter 进程 |
| 🟪 紫色框 | 数据存储层 |
| ⬜ 灰色框 | 外部 API/服务 |
| ➡️ 实线箭头 | 主要数据流 |
| - - ➡️ 虚线箭头 | 次要/备选数据流 |
