package com.silk.backend

import java.io.File

/**
 * 统一管理运行时使用的数据库和文件目录。
 *
 * 优先级：
 * 1. JVM system properties（便于测试和 CI）
 * 2. .env / process env
 * 3. 项目默认相对路径
 */
object AppPaths {
    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun configuredDir(
        propertyKey: String,
        envKey: String,
        defaultDirName: String
    ): File {
        val explicitPath = firstNonBlank(
            System.getProperty(propertyKey),
            EnvLoader.get(envKey),
            System.getenv(envKey)
        )

        val file = if (explicitPath != null) {
            File(explicitPath)
        } else {
            File(storageRootDir(), defaultDirName)
        }

        return file.absoluteFile.normalize()
    }

    fun storageRootDir(): File {
        val explicitRoot = firstNonBlank(
            System.getProperty("silk.storage.root"),
            EnvLoader.get("SILK_STORAGE_ROOT"),
            System.getenv("SILK_STORAGE_ROOT")
        )
        return File(explicitRoot ?: ".").absoluteFile.normalize()
    }

    fun databaseUrl(): String {
        return firstNonBlank(
            System.getProperty("silk.db.url"),
            EnvLoader.get("SILK_DB_URL"),
            System.getenv("SILK_DB_URL")
        ) ?: "jdbc:sqlite:${File(storageRootDir(), "silk_database.db").absolutePath}"
    }

    fun chatHistoryDir(): File = configuredDir(
        propertyKey = "silk.chat.dir",
        envKey = "SILK_CHAT_DIR",
        defaultDirName = "chat_history"
    )

    fun chatHistoryBackupDir(): File = configuredDir(
        propertyKey = "silk.chat.backup.dir",
        envKey = "SILK_CHAT_BACKUP_DIR",
        defaultDirName = "chat_history_backup"
    )

    fun staticDir(): File = configuredDir(
        propertyKey = "silk.static.dir",
        envKey = "SILK_STATIC_DIR",
        defaultDirName = "static"
    )
}
