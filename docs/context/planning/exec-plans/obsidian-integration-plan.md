# Obsidian 集成方案

## 需求

1. **Silk → Obsidian 同步**：Silk 上的聊天记录、知识库条目可以同步到 Obsidian vault 中
2. **Obsidian → Silk 专属对话**：在 Silk 专属对话（cc-connect 群组）中，可以看到 Obsidian vault 里的内容

---

## 现状分析

### Silk 已有的 Obsidian 能力

| 模块 | 功能 | 路径 |
|------|------|------|
| `ChatObsidianExporter` | 聊天记录 → Obsidian Markdown（YAML frontmatter + 时间线格式） | `backend/src/.../export/ChatObsidianExporter.kt` |
| `KBObsidianExporter` | 知识库条目 → Obsidian Markdown（含 topic/entry_id/tags 元数据） | `backend/src/.../kb/KBObsidianExporter.kt` |
| HTTP API 端点 | `GET /groups/{groupId}?export=obsidian_markdown`、`GET /api/groups/{groupId}/export/markdown`、`GET /download/obsidian/{groupId}`、`GET /api/kb/entries/{entryId}?export=obsidian` | `Routing.kt` |
| Knowledge Base | 完整的条目 CRUD + `[[kb:...]]` 内联引用 + AI 上下文注入 | `kb/KnowledgeBaseManager.kt` |
| cc-connect | 专属对话，可让 AI agent 直接访问文件系统 | `ccconnect/` |

### 缺失的部分

- 没有 Obsidian 插件拉取 Silk 数据
- 没有双向同步机制（Obsidian 修改 → Silk KB）
- 专属对话只能通过 cc-connect AI agent 的 `_silk/list_dir` + `_silk/read` 间接访问文件，缺少结构化的 vault 浏览

---

## 整体架构

```
┌─────────────────────────────┐      ┌──────────────────────────────┐
│        Obsidian Vault       │      │       Silk Backend           │
│                             │      │                              │
│  Silk Chat Exports/         │      │  ┌────────────────────────┐  │
│  Silk KB Mirror/            │◄────►│  │ Obsidian Plugin API    │  │
│  .obsidian/plugins/         │      │  │ (新增端点)              │  │
│    silk-sync/               │      │  └────────────────────────┘  │
│                             │      │  ┌────────────────────────┐  │
│  (用户日常编辑笔记)          │──────►│  │ KB Ingest API         │  │
│                             │      │  │ (推送 Obsidian 笔记)   │  │
└─────────────────────────────┘      │  └────────────────────────┘  │
                                      │  ┌────────────────────────┐  │
                                      │  │ cc-connect + File      │  │
                                      │  │ Access for AI Agents   │  │
                                      │  └────────────────────────┘  │
                                      └──────────────────────────────┘
```

---

## 方案详解

### 方案 A：Obsidian 插件 `silk-sync`（实现双向同步）

一个标准的 Obsidian 插件，通过 Silk HTTP API 实现双向数据流动。

#### 需要 SilK 后端新增的 API

```
# --- 拉取类（供插件主动查询）---
GET  /api/obsidian/groups                    # 列出用户有权限的群组（含最新消息时间戳）
GET  /api/obsidian/groups/{groupId}/export   # 导出群聊为 Markdown（已有，但需标准化）
GET  /api/obsidian/kb/topics                 # 列出 KB 主题
GET  /api/obsidian/kb/entries?topicId=xxx    # 列出某主题下的条目（已有类似）
GET  /api/obsidian/kb/export/{entryId}       # 导出单条 KB 条目为 Markdown（已有）
GET  /api/obsidian/changes                   # 获取最近变更的增量（用于增量同步）
     ?since=<timestamp>
     &scope=all|chat|kb

# --- 推送类（供插件将 Obsidian 笔记推回 Silk）---
POST /api/obsidian/notes/ingest              # 导入一条 Obsidian 笔记到 Silk KB
     { title, content, vaultPath, tags, modifiedAt }
     
POST /api/obsidian/notes/batch-ingest        # 批量导入
     { notes: [...] }
     
POST /api/obsidian/notes/sync-status         # 标记哪些笔记已在 Obsidian 中被删除
     { deletedVaultPaths: [...] }
```

#### Obsidian 插件功能

1. **设置页**
   - Silk 服务器 URL（默认 `http://localhost:8080`）
   - API Token（从 Silk 用户设置生成）
   - 同步开关（自动/手动）
   - 同步范围：聊天记录 / KB 条目 / 两者
   - 同步间隔（分钟）

2. **拉取同步（Silk → Obsidian）**
   - 定时轮询 `/api/obsidian/changes` 获取增量
   - 聊天记录写入 `Silk/Chats/<群组名>/<日期>.md`
   - KB 条目写入 `Silk/Knowledge/<项目>/<主题>/<标题>.md`
   - 插件侧维护 `silk-sync.json` 记录上次同步时间戳和文件映射

3. **推送同步（Obsidian → Silk）**
   - 插件监控 `Silk/Notes/` 目录下的文件变更
   - 新增/修改的文件自动推送到 `POST /api/obsidian/notes/ingest`
   - Silk 后端将内容存入 Knowledge Base（`sourceType=OBSIDIAN`）

4. **命令面板**
   - "Silk: 立即同步所有"
   - "Silk: 推送当前笔记到 Silk"
   - "Silk: 从 Silk 拉取最新"

#### 文件结构（在 Obsidian vault 中）

```
Silk/
├── Chats/
│   ├── 项目讨论/
│   │   ├── 2026-07-10.md
│   │   └── 2026-07-09.md
│   └── 日常闲聊/
│       └── 2026-07-10.md
├── Knowledge/
│   ├── silk/
│   │   ├── Architecture/
│   │   │   └── 系统设计.md
│   │   └── API/
│   │       └── WebSocket 协议.md
│   └── Personal/
│       └── diary/
│           └── 随笔.md
└── Notes/              ← 用户在此编辑，自动同步回 Silk KB
    └── 我的想法.md
```

---

### 方案 B：专属对话中读取 Obsidian 内容（两条路径）

#### 路径 B1：通过 cc-connect AI agent 直接读取 vault

cc-connect 的 AI agent（Claude Code / Cursor 等）已经在工作目录中有文件系统访问权限。

**做法**：在专属对话的工作流中，将 Obsidian vault 目录加入 `trusted_dirs`，AI agent 就能通过 `_silk/list_dir` 和 `_silk/read` 访问 `.md` 文件。

**用户操作**：
```
在 Silk UI 中创建 cc-connect 群组
→ 设置 workingDir 为 Obsidian vault 路径
→ AI agent 可以 `ls`、`cat` vault 中的 .md 文件
→ 用户提问："参考我的 Obsidian 笔记中的架构设计，帮我..."
```

**优点**：零开发，即可用
**缺点**：AI agent 看到的是原始 .md 文件，缺少结构化元数据；不适合大量笔记检索

#### 路径 B2：Obsidian 笔记 → Silk KB → AI 上下文注入（推荐）

将 Obsidian 笔记推送到 Silk Knowledge Base，专属对话中的 AI 就能通过 [[kb:...]] 引用或自动上下文检索看到它们。

**流程**：
```
用户在 Obsidian 编辑笔记
  → silk-sync 插件推送笔记到 POST /api/obsidian/notes/ingest
  → Silk 后端存入 KB（sourceType=OBSIDIAN, status=PUBLISHED）
  → 专属对话中：
    a) 用户发消息 [[kb:笔记标题]] → KB 内容自动注入 AI prompt
    b) 或 AI 自动检索相关 KB 条目作为上下文
```

**优点**：
- AI 可以精准引用结构化 KB 条目
- 支持跨笔记的语义检索
- 可结合 `pinnedEntryIds` / `excludedEntryIds` 精细控制

**缺点**：需要实现 Obsidian 插件推送逻辑

#### 路径 B3：混合模式（最强方案）

结合 B1 + B2：
- 日常通过 **B2（KB）** 让 AI 了解 Obsidian 中的知识
- 需要 AI 直接操作文件时，用 **B1（cc-connect）** 让 AI 读写 vault
- 或者通过 cc-connect 的 `_silk/read` 直接读取特定 .md 文件内容，通过 CARD 消息返回给用户

---

## 推荐实施路线

### Phase 1：后端新增 Obsidian API（1-2 天）

1. 新增 `routes/ObsidianRoutes.kt`
   - `GET  /api/obsidian/groups` — 用户有权限的群组列表
   - `GET  /api/obsidian/changes?since=<timestamp>` — 增量变更查询
   - `POST /api/obsidian/notes/ingest` — 导入一条笔记到 KB（新增 `KBSourceType.OBSIDIAN`）
   - `POST /api/obsidian/notes/batch-ingest` — 批量导入

2. 新增 KB source type
   - 在 `KBEntrySource` 增加 `OBSIDIAN` 类型
   - 含 `vaultPath` 字段（记录来源 vault 路径，用于去重和反向映射）

3. 身份认证
   - 复用现有 `Authorization: Bearer` JWT 鉴权
   - 或为 Obsidian 插件生成专用 API Token

### Phase 2：Obsidian 插件开发（2-3 天）

1. 插件脚手架
   - `manifest.json`、`main.ts`、`settings.ts`
   - 配置 Silk 服务器地址 + API Token

2. 拉取同步
   - 定时轮询 `/api/obsidian/changes`
   - 写入 vault 的 `Silk/` 目录
   - 维护本地同步状态 JSON

3. 推送同步
   - 监听 `Silk/Notes/` 目录（via `MetadataCache` + `Vault.on('modify')`）
   - 检测到变化推送到 `POST /api/obsidian/notes/ingest`

4. UI
   - Ribbon icon + 命令面板
   - 状态栏显示上次同步时间

### Phase 3：专属对话集成（1 天）

1. 群组 KB 上下文增强
   - 如果用户已导入 Obsidian 笔记到 KB，专属对话中自动检索相关笔记
   - 支持 `[[obsidian:笔记标题]]` 快捷引用语法（内部映射到 `[[kb:...]]`）

2. cc-connect 目录配置
   - 在创建 cc-connect 工作流时，可选择 Obsidian vault 为工作目录
   - 或在 UI 中添加"关联 Obsidian vault"按钮

---

## 不需要改的东西

- **已有** `ChatObsidianExporter` / `KBObsidianExporter` — 格式成熟，直接复用
- **已有** KB 的 CRUD + 鉴权 + AI 上下文注入 — 推送的笔记直接进入 KB 管线
- **已有** cc-connect 文件访问 — B1 路径无需开发

## 验证方式

```
# 后端测试
./gradlew :backend:test

# 手动测试
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/obsidian/changes?since=0
curl -X POST -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"title":"测试笔记","content":"# Hello","vaultPath":"Silk/Notes/test.md","tags":["test"]}' \
  http://localhost:8080/api/obsidian/notes/ingest
```
