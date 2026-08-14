package com.silk.backend.database

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * 用户设置数据访问层
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
object UserSettingsRepository {

    private val secureRandom = SecureRandom()

    /**
     * 获取用户设置
     * 如果用户没有设置，返回默认设置
     */
    fun getUserSettings(userId: String): UserSettings {
        return transaction {
            UserSettingsTable.select { UserSettingsTable.userId eq userId }
                .mapNotNull { rowToUserSettings(it) }
                .singleOrNull()
                ?: UserSettings() // 返回默认设置
        }
    }

    /**
     * 更新用户设置
     * 如果设置不存在，则创建；如果存在，则更新
     */
    fun updateUserSettings(
        userId: String,
        language: Language,
        defaultAgentInstruction: String
    ): UserSettings {
        return transaction {
            val existing = UserSettingsTable.select { UserSettingsTable.userId eq userId }
                .singleOrNull()

            if (existing == null) {
                // 创建新设置
                UserSettingsTable.insert {
                    it[UserSettingsTable.userId] = userId
                    it[UserSettingsTable.language] = language.name
                    it[UserSettingsTable.defaultAgentInstruction] = defaultAgentInstruction
                    it[UserSettingsTable.updatedAt] = LocalDateTime.now()
                }
            } else {
                // 更新现有设置
                UserSettingsTable.update({ UserSettingsTable.userId eq userId }) {
                    it[UserSettingsTable.language] = language.name
                    it[UserSettingsTable.defaultAgentInstruction] = defaultAgentInstruction
                    it[UserSettingsTable.updatedAt] = LocalDateTime.now()
                }
            }

            getUserSettings(userId)
        }
    }

    /**
     * 获取或创建 App HTTP 认证 token（用于 KB 等 API bearer 认证）
     */
    fun getOrCreateAppAuthToken(userId: String): String {
        getAppAuthToken(userId)?.let { return it }
        return issueAppAuthToken(userId)
    }

    fun getAppAuthToken(userId: String): String? {
        return transaction {
            UserSettingsTable.select { UserSettingsTable.userId eq userId }
                .singleOrNull()
                ?.get(UserSettingsTable.appAuthToken)
        }
    }

    fun issueAppAuthToken(userId: String): String {
        val token = generateHexToken()
        transaction {
            val existing = UserSettingsTable.select { UserSettingsTable.userId eq userId }
                .singleOrNull()

            if (existing == null) {
                UserSettingsTable.insert {
                    it[UserSettingsTable.userId] = userId
                    it[appAuthToken] = token
                    it[updatedAt] = LocalDateTime.now()
                }
            } else {
                UserSettingsTable.update({ UserSettingsTable.userId eq userId }) {
                    it[appAuthToken] = token
                    it[updatedAt] = LocalDateTime.now()
                }
            }
        }
        return token
    }

    fun findUserIdByAppAuthToken(token: String): String? {
        return transaction {
            UserSettingsTable.select { UserSettingsTable.appAuthToken eq token }
                .singleOrNull()
                ?.get(UserSettingsTable.userId)
        }
    }

    private fun generateHexToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 将数据库行转换为UserSettings对象
     */
    private fun rowToUserSettings(row: ResultRow): UserSettings {
        val languageStr = row[UserSettingsTable.language]
        val language = try {
            Language.valueOf(languageStr)
        } catch (e: Exception) {
            Language.CHINESE // 默认值
        }

        return UserSettings(
            language = language,
            defaultAgentInstruction = row[UserSettingsTable.defaultAgentInstruction],
            appAuthToken = row[UserSettingsTable.appAuthToken],
        )
    }
}
