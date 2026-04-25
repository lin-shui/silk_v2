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
            SchemaUtils.create(Users, Groups, GroupMembers, Contacts, ContactRequests, UserSettingsTable)
            // 自动添加新列，保留已有行数据，避免升级后登录/群组/设置丢失。
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                Groups,
                GroupMembers,
                Contacts,
                ContactRequests,
                UserSettingsTable
            )
        }
        
        logger.info("✅ 数据库初始化完成: {}", databasePath)
    }
}
