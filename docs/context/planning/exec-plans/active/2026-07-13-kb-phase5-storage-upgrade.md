# KB Phase 5: Storage Upgrade — 嵌入增强 + PostgreSQL 就绪

Status: 计划制定中
Date: 2026-07-13

## Goal

在现有 JSON store 的基础上，先引入**嵌入向量语义检索**提升搜索质量，再逐步过渡到 **PostgreSQL + pgvector** 作为主存储。

## Current Baseline

- KB 使用扁平 `kb_store.json`，全部 topic/entry 在内存中
- 搜索是纯关键词匹配：`extractSearchTerms` 分词 → `scoreContextCandidate` 加权排序
- 无嵌入、无向量、无全文索引
- 项目已有 Anthropic Client 连接（`AnthropicClient.kt`），可复用为嵌入来源
- 已有 Exposed ORM + SQLite（用于用户/群组/设置等），无 PostgreSQL
- Weaviate 已弃用但 docker-compose 仍在（`search/`）
- Memory 系统已具备去重合并、TTL 衰减、recency 加权等能力

## Proposed Plan

### Stage 1: 嵌入向量语义检索（纯内存，不改存储）

在 JSON store 不变的前提下，新增嵌入生成 + 内存向量索引，实现语义搜索。

| 步骤 | 内容 | 文件 |
|------|------|------|
| 1.1 | 嵌入生成器：调用 Anthropic Embeddings API 生成文本向量 | `kb/KnowledgeBaseEmbedding.kt`（新增）|
| 1.2 | 嵌入缓存文件 `kb_embeddings.json`：entryId → embedding 映射 | 侧边文件，主 store 不变 |
| 1.3 | 启动时加载嵌入到内存 `Map<String, FloatArray>` | `KnowledgeBaseManager` 增加嵌入字段 |
| 1.4 | 余弦相似度函数 + 批归一化 | `kb/KnowledgeBaseEmbedding.kt` |
| 1.5 | `searchEntriesForContext` 混合模式：关键词分 + 向量分(α, β)加权合并 | 修改 `KnowledgeBaseManager.kt` |
| 1.6 | `searchMemoryEntriesForContext` 同理，叠加 recency + 向量 | 修改 `KnowledgeBaseManager.kt` |
| 1.7 | 增量更新：entry create/update 时自动生成/刷新嵌入 | `KnowledgeBaseManager` create/update 中调用 |
| 1.8 | 启动时懒加载缺失嵌入 | `KnowledgeBaseManager` init 中遍历未嵌入条目 |

#### 嵌入模型选择

- 首选复用现有 Anthropic API Key，使用 `voyage-2` 或 Anthropic 新推出的嵌入模型（若可用）
- 备选：通过 `AIConfig` 配置嵌入模型名，可切换
- 嵌入维度：可变，代码中动态获取
- 缓存：entry 内容变化时重新生成，否则从 `kb_embeddings.json` 读取

### Stage 2: PostgreSQL + pgvector 主存储

当 JSON store 成为瓶颈时，迁移到 PostgreSQL。

| 步骤 | 内容 |
|------|------|
| 2.1 | 添加 `docker-compose.yml` 到项目根目录（PostgreSQL + pgvector） |
| 2.2 | 添加 PostgreSQL JDBC 驱动 + Exposed PostgreSQL 方言 |
| 2.3 | 定义 Exposed 表：`kb_topics`、`kb_entries`、`kb_embeddings`（带 pgvector 列） |
| 2.4 | 添加 `/api/admin/kb/migrate-to-pg` 迁移端点：JSON → PostgreSQL |
| 2.5 | `KnowledgeBaseManager` 增加 `PgKnowledgeBaseRepository` 实现 |
| 2.6 | 运行时通过配置 `silk.kb.store=json|postgres` 切换存储后端 |
| 2.7 | pgvector 原生 ANN 索引 + SQL 过滤实现混合检索 |

## Design

### 嵌入缓存格式

```kotlin
@Serializable
data class KbEmbeddingStore(
    val version: Int = 1,
    val model: String = "",
    val entries: Map<String, List<Float>> = emptyMap(),  // entryId → vector
    val updatedAt: Long = 0L,
)
```

### 嵌入生成接口

```kotlin
interface EmbeddingProvider {
    suspend fun generateEmbedding(text: String): List<Float>
    suspend fun generateEmbeddings(texts: List<String>): List<List<Float>>
}
```

- `AnthropicEmbeddingProvider` 通过 Anthropic Messages API 的 embeddings 端点
- 可扩展：`VoyageEmbeddingProvider`、`LocalEmbeddingProvider`（ONNX）

### 混合搜索评分

```
finalScore = α × keywordScore + β × vectorScore + γ × recencyScore
```

- α, β, γ 通过 `AIConfig` 或 context preference 可调
- 默认：α=0.4, β=0.4, γ=0.2（有记忆时 γ 增加）
- 关键词分：保持现有 `scoreContextCandidate` 逻辑不变
- 向量分：cosine(query_embedding, entry_embedding) 映射到 [0, 1]
- 降权/排除空间仍应用在最终排序前

### 存储后端切换

```kotlin
// KnowledgeBaseManager.kt
class KnowledgeBaseManager(
    private val baseDir: String = ...,
    private val storeBackend: StoreBackend = StoreBackend.JSON,
) {
    // 根据 storeBackend 选择：
    // - JSON: 现有逻辑
    // - POSTGRES: PgKnowledgeBaseRepository 实现
}
```

## Affected Code Surfaces

### Stage 1
- `backend/build.gradle.kts` — 无新增依赖（复用 Anthropic Client）
- `backend/src/main/kotlin/com/silk/backend/ai/AIConfig.kt` — 增加嵌入模型配置（可选）
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseEmbedding.kt` — 新增
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt` — 修改搜索方法 + 嵌入加载
- `backend/src/main/kotlin/com/silk/backend/models/KnowledgeBase.kt` — 无需改动（嵌入独立存储）
- `knowledge_base/kb_embeddings.json` — 自动生成的新文件
- `.gitignore` — 确认 `kb_embeddings.json` 是否跟踪

### Stage 2
- `backend/build.gradle.kts` — 增加 PostgreSQL JDBC 驱动 + Exposed 支持
- `docker-compose.yml` — 新增 PostgreSQL + pgvector 服务
- `backend/src/main/kotlin/com/silk/backend/database/DatabaseFactory.kt` — 增加 PostgreSQL 数据源
- `backend/src/main/kotlin/com/silk/backend/database/Tables.kt` — 增加 KB 表定义
- `backend/src/main/kotlin/com/silk/backend/kb/KnowledgeBaseManager.kt` — 增加 PG 存储后端
- `backend/src/main/kotlin/com/silk/backend/kb/PgKnowledgeBaseRepository.kt` — 新增
- `backend/src/main/kotlin/com/silk/backend/Routing.kt` — 增加迁移端点

## Risks

- Anthropic 嵌入 API 的可用性与成本：需要确认当前 API Key 是否有嵌入权限
- 嵌入维度变化：不同模型的嵌入维度不同，缓存格式需兼容
- 内存占用：~1000 条目 × 1024 dims × 4 bytes ≈ 4MB，可接受
- Stage 2 PostgreSQL 迁移需停机窗口或双写过渡
- 混合搜索的 α/β/γ 参数需要实验调优

## Verification

- `./gradlew :backend:test` — 核心测试
- `./gradlew :frontend:webApp:nodeTest` — 合同层测试
- 新增测试：
  - `kb/KnowledgeBaseEmbeddingTest.kt` — 嵌入生成/缓存/相似度
  - `kb/KnowledgeBaseHybridSearchTest.kt` — 混合搜索排序质量
  - `kb/KnowledgeBaseMigrationTest.kt` — JSON→PG 迁移正确性（Stage 2）

## Decision

**优先实现 Stage 1**（嵌入语义检索），Stage 2（PostgreSQL 迁移）作为独立后续阶段。理由：

1. JSON store 在当前规模（数百条目）下性能够用
2. 搜索质量的提升是当前最直接的瓶颈
3. 嵌入生成复用现有 Anthropic API，无需额外基础设施
4. Stage 2 的 pgvector 原生混合检索需要 Stage 1 的嵌入数据作为前提
