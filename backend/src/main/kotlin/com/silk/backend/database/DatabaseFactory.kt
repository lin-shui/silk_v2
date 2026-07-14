package com.silk.backend.database

import com.silk.backend.ai.AIConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 数据库工厂：初始化数据库连接和创建表
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    /** 主 SQLite 数据库（用户、群组、设置等）。 */
    private var mainDatabase: Database? = null
    /** KB PostgreSQL 数据库（仅在 storeBackend=postgres 时初始化）。 */
    private var kbPostgresDatabase: Database? = null

    fun init() {
        initSqlite()
        initKbPostgresIfEnabled()
    }

    /**
     * 获取 KB PostgreSQL 数据库连接。
     * 调用方应确保 [initKbPostgresIfEnabled] 已执行。
     */
    fun getKbPostgresDatabase(): Database? = kbPostgresDatabase

    /**
     * 获取主 SQLite 数据库连接。
     */
    fun getMainDatabase(): Database? = mainDatabase

    private fun initSqlite() {
        val databasePath = System.getProperty("silk.databasePath")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "./silk_database.db"
        File(databasePath).parentFile?.mkdirs()

        // 使用 SQLite 数据库
        val database = Database.connect(
            url = "jdbc:sqlite:$databasePath",
            driver = "org.sqlite.JDBC"
        )
        mainDatabase = database
        
        transaction(database) {
            // 创建所有表
            SchemaUtils.create(
                Users, Groups, GroupMembers, Contacts, ContactRequests,
                UserSettingsTable, CcConnectTokens, HuaweiAccounts, WechatAccounts,
                RefreshTokensTable
            )
            SchemaUtils.createMissingTablesAndColumns(
                Users, Groups, GroupMembers, Contacts, ContactRequests,
                UserSettingsTable, CcConnectTokens, HuaweiAccounts, WechatAccounts,
                RefreshTokensTable
            )
        }
        
        logger.info("✅ SQLite 数据库初始化完成: {}", databasePath)
    }

    /**
     * 如果配置了 silk.kb.store=postgres，初始化 PostgreSQL 连接并创建 KB 表。
     * 否则跳过。
     */
    private fun initKbPostgresIfEnabled() {
        if (AIConfig.KB_STORE_BACKEND != "postgres") {
            logger.info("⏭ KB 存储后端为 JSON，跳过 PostgreSQL 初始化")
            return
        }

        val host = AIConfig.PG_HOST
        val port = AIConfig.PG_PORT
        val db = AIConfig.PG_DATABASE
        val user = AIConfig.PG_USER
        val password = AIConfig.PG_PASSWORD

        if (password.isBlank()) {
            logger.warn("⚠ PG_PASSWORD 未配置，跳过 PostgreSQL 初始化")
            return
        }

        try {
            val database = Database.connect(
                url = "jdbc:postgresql://$host:$port/$db",
                driver = "org.postgresql.Driver",
                user = user,
                password = password,
            )
            kbPostgresDatabase = database

            transaction(database) {
                // 启用 pgvector 扩展（每个连接需要）
                exec("CREATE EXTENSION IF NOT EXISTS vector")

                // 创建 KB 表
                SchemaUtils.create(KbTopicsTable, KbEntriesTable)
                SchemaUtils.createMissingTablesAndColumns(KbTopicsTable, KbEntriesTable)

                // 创建 kb_embeddings 表（使用 pgvector 的 vector 类型）
                // Exposed 不支持直接定义 vector 列，用原生 SQL
                exec("""
                    CREATE TABLE IF NOT EXISTS kb_embeddings (
                        entry_id VARCHAR(128) PRIMARY KEY REFERENCES kb_entries(id),
                        embedding vector(1024),
                        model VARCHAR(128) NOT NULL DEFAULT '',
                        updated_at TIMESTAMP NOT NULL DEFAULT NOW()
                    )
                """.trimIndent())

                // 创建 pgvector IVFFlat 索引（加速 ANN 搜索）
                exec("""
                    CREATE INDEX IF NOT EXISTS idx_kb_embeddings_ivfflat
                    ON kb_embeddings
                    USING ivfflat (embedding vector_cosine_ops)
                    WITH (lists = 100)
                """.trimIndent())

                logger.info("✅ KB PostgreSQL 表创建完成 (host={}, db={})", host, db)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("❌ KB PostgreSQL 初始化失败: {}", e.message)
            // 不阻止应用启动——PG 不可用时降级回 JSON store
            kbPostgresDatabase = null
        }
    }
}
