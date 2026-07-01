package com.silk.backend.database

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

    fun init() {
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
        
        transaction(database) {
            // 创建所有表
            SchemaUtils.create(Users, Groups, GroupMembers, Contacts, ContactRequests, UserSettingsTable, CcConnectTokens, HuaweiAccounts, WechatAccounts, RefreshTokensTable)
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                Groups,
                GroupMembers,
                Contacts,
                ContactRequests,
                UserSettingsTable,
                CcConnectTokens,
                HuaweiAccounts,
                WechatAccounts,
                RefreshTokensTable
            )
        }
        
        logger.info("✅ 数据库初始化完成: {}", databasePath)
    }
}
