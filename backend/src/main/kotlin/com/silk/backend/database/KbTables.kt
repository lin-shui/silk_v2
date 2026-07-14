package com.silk.backend.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * KB Topics 表（PostgreSQL 存储后端用）。
 * 对应 [com.silk.backend.models.KBTopic]。
 *
 * 注意：JSON 存储后端的 topic 字段为 `purpose` 和 `spaceType` 等枚举，
 * 此处用 VARCHAR 存储字符串，代码层转换。
 */
object KbTopicsTable : Table("kb_topics") {
    val id = varchar("id", 128).uniqueIndex()
    val name = varchar("name", 512)
    val project = varchar("project", 256).default("")
    val ownerId = varchar("owner_id", 128)
    /** "GENERAL" 或 "MEMORY" */
    val purpose = varchar("purpose", 32).default("GENERAL")
    /** "PERSONAL" 或 "TEAM" */
    val spaceType = varchar("space_type", 32).default("PERSONAL")
    val groupId = varchar("group_id", 128).nullable().default(null)
    /** JSON 序列化的 [com.silk.backend.models.KBAccessPolicy] */
    val accessPolicyJson = text("access_policy_json").default("""{"readUserIds":[],"writeUserIds":[],"manageUserIds":[],"writeLocked":false,"teamMembersCanWrite":true}""")
    val createdBy = varchar("created_by", 128).default("")
    val updatedBy = varchar("updated_by", 128).default("")
    val createdAt = datetime("created_at").default(LocalDateTime.now())
    val updatedAt = datetime("updated_at").default(LocalDateTime.now())

    override val primaryKey = PrimaryKey(id)
}

/**
 * KB Entries 表（PostgreSQL 存储后端用）。
 * 对应 [com.silk.backend.models.KBEntry]。
 */
object KbEntriesTable : Table("kb_entries") {
    val id = varchar("id", 128).uniqueIndex()
    val topicId = varchar("topic_id", 128).references(KbTopicsTable.id)
    val title = varchar("title", 1024)
    val content = text("content").default("")
    /** JSON 序列化的 tags: List<String> */
    val tagsJson = text("tags_json").default("[]")
    val ownerId = varchar("owner_id", 128)
    /** "CANDIDATE" | "PUBLISHED" | "ARCHIVED" | "DELETED" */
    val status = varchar("status", 32).default("PUBLISHED")
    /** JSON 序列化的 [com.silk.backend.models.KBEntrySource] */
    val sourceJson = text("source_json")
    /** JSON 序列化的 [com.silk.backend.models.KBMemoryMetadata]?，可为 null */
    val memoryJson = text("memory_json").nullable().default(null)
    val createdBy = varchar("created_by", 128).default("")
    val updatedBy = varchar("updated_by", 128).default("")
    val createdAt = datetime("created_at").default(LocalDateTime.now())
    val updatedAt = datetime("updated_at").default(LocalDateTime.now())

    override val primaryKey = PrimaryKey(id)
}

/**
 * KB Embeddings 表（PostgreSQL 存储后端用）。
 * 使用 pgvector 的 vector 类型存储嵌入向量。
 * 对应嵌入缓存中的 entryId → List<Float>。
 *
 * 注意：首次建表前需确保 pgvector 扩展已安装（见 docker-compose-pg.yml init 脚本）。
 */
object KbEmbeddingsTable : Table("kb_embeddings") {
    val entryId = varchar("entry_id", 128).references(KbEntriesTable.id).uniqueIndex()
    /**
     * pgvector 向量列。维度由嵌入模型决定（如 voyage-3 为 1024 维）。
     * 建表时用动态维度（例如 vector(1024)），代码中通过 SQL 执行。
     * 此处 Exposed 层定义为 TEXT 占位，实际 DDL 由 init 脚本或 migrate 端点处理。
     *
     * pgvector 的 vector 类型 Exposed 原生不支持，这里用 custom SQL 创建。
     * 具体见 [PgKnowledgeBaseRepository] 的 init 方法。
     */
    /** pgvector 向量列，用 text 占位（实际 DDL 由 DatabaseFactory 的 init 脚本用原生 SQL 创建）。 */
    val embedding = text("embedding").nullable()
    val model = varchar("model", 128).default("")
    val updatedAt = datetime("updated_at").default(LocalDateTime.now())

    override val primaryKey = PrimaryKey(entryId)
}
