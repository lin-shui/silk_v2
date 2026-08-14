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

    /** Main application database. SQLite is the local default; clustered mode requires PostgreSQL. */
    private var mainDatabase: Database? = null
    /** KB PostgreSQL 数据库（仅在 storeBackend=postgres 时初始化）。 */
    private var kbPostgresDatabase: Database? = null

    fun init() {
        initMainDatabase()
        RoomKindMigration.run()
        RoomLastMessageMigration.run()
        initKbPostgresIfEnabled()
    }

    /**
     * 获取 KB PostgreSQL 数据库连接。
     * 调用方应确保 [initKbPostgresIfEnabled] 已执行。
     */
    fun getKbPostgresDatabase(): Database? = kbPostgresDatabase

    /** Get the main application database connection. */
    fun getMainDatabase(): Database? = mainDatabase

    private fun initMainDatabase() {
        val configuredUrl = configuredValue("SILK_DATABASE_URL", "silk.databaseUrl")
        val databasePath = System.getProperty("silk.databasePath")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "./silk_database.db"
        val jdbcUrl = configuredUrl ?: "jdbc:sqlite:$databasePath"
        val clustered = configuredValue("SILK_CLUSTERED", "silk.clustered")?.toBooleanStrictOrNull() ?: false
        if (clustered && !jdbcUrl.startsWith("jdbc:postgresql:")) {
            error("SILK_CLUSTERED=true requires a shared PostgreSQL SILK_DATABASE_URL")
        }
        val database = if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            Database.connect(
                url = jdbcUrl,
                driver = "org.postgresql.Driver",
                user = configuredValue("SILK_DATABASE_USER", "silk.databaseUser").orEmpty(),
                password = configuredValue("SILK_DATABASE_PASSWORD", "silk.databasePassword").orEmpty(),
            )
        } else {
            require(jdbcUrl.startsWith("jdbc:sqlite:")) { "SILK_DATABASE_URL must use jdbc:sqlite or jdbc:postgresql" }
            val sqliteLocation = jdbcUrl.removePrefix("jdbc:sqlite:")
            if (sqliteLocation != ":memory:" && !sqliteLocation.startsWith("file:")) {
                File(sqliteLocation).absoluteFile.parentFile?.mkdirs()
            }
            Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")
        }
        mainDatabase = database
        
        transaction(database) {
            // 创建所有表
            SchemaUtils.create(
                Users, Groups, GroupMembers, Contacts, ContactRequests,
                UserSettingsTable, CcConnectTokens, HuaweiAccounts, WechatAccounts,
                RefreshTokensTable, AgentDevices, AgentInstances, AgentBindings, AgentBindingAuditEvents,
                AgentPairingRequests, AgentConnectionChallenges, AgentSecurityEvents,
            )
            SchemaUtils.createMissingTablesAndColumns(
                Users, Groups, GroupMembers, Contacts, ContactRequests,
                UserSettingsTable, CcConnectTokens, HuaweiAccounts, WechatAccounts,
                RefreshTokensTable, AgentDevices, AgentInstances, AgentBindings, AgentBindingAuditEvents,
                AgentPairingRequests, AgentConnectionChallenges, AgentSecurityEvents,
            )
            retireDirectBridgeTokens()
            migrateAgentAuthenticationOrigins()
            migrateAgentBindingApprovals()
        }
        
        logger.info(
            "✅ Main database initialized: backend={}, clustered={}",
            if (jdbcUrl.startsWith("jdbc:postgresql:")) "postgresql" else "sqlite",
            clustered,
        )
    }

    private fun configuredValue(environmentName: String, propertyName: String): String? =
        System.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv(environmentName)?.trim()?.takeIf { it.isNotEmpty() }
            ?: com.silk.backend.EnvLoader.get(environmentName)

    /** Preserve the origin accepted during enrollment for pre-column device records. */
    private fun org.jetbrains.exposed.sql.Transaction.migrateAgentAuthenticationOrigins() {
        exec(
            """
            UPDATE agent_devices
            SET authentication_origin = (
                SELECT agent_pairing_requests.server_origin
                FROM agent_pairing_requests
                WHERE agent_pairing_requests.device_id = agent_devices.id
                  AND agent_pairing_requests.state = 'CONSUMED'
                  AND agent_pairing_requests.request_kind = 'DEVICE_ENROLLMENT'
                ORDER BY agent_pairing_requests.consumed_at DESC
                LIMIT 1
            )
            WHERE authentication_origin IS NULL
            """.trimIndent()
        )
    }

    /** Existing bindings were created only when one user controlled both approval sides. */
    private fun org.jetbrains.exposed.sql.Transaction.migrateAgentBindingApprovals() {
        exec(
            """
            UPDATE agent_bindings
            SET owner_id = (
                SELECT agent_instances.user_id
                FROM agent_instances
                WHERE agent_instances.id = agent_bindings.agent_instance_id
            )
            WHERE owner_id IS NULL
            """.trimIndent()
        )
        exec(
            """
            UPDATE agent_bindings
            SET agent_owner_approved_by = COALESCE(agent_owner_approved_by, owner_id),
                agent_owner_approved_at = COALESCE(agent_owner_approved_at, created_at),
                target_approved_by = COALESCE(target_approved_by, created_by),
                target_approved_at = COALESCE(target_approved_at, created_at),
                updated_at = COALESCE(updated_at, created_at)
            WHERE status = 'ACTIVE'
            """.trimIndent()
        )
    }

    /** The column remains for schema compatibility, but retired bearer credentials are destroyed. */
    private fun org.jetbrains.exposed.sql.Transaction.retireDirectBridgeTokens() {
        exec("UPDATE user_settings SET cc_bridge_token = NULL WHERE cc_bridge_token IS NOT NULL")
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
