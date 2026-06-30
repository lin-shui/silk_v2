package com.silk.backend.database

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * 用户设置数据访问层
 */
object UserSettingsRepository {

    private val secureRandom = SecureRandom()
    private val logger = LoggerFactory.getLogger(UserSettingsRepository::class.java)

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

    /**
     * 生成 Bridge Token
     * 用 SecureRandom 生成 32 字符 hex token 并存储
     */
    fun generateBridgeToken(userId: String): String {
        val token = generateHexToken()

        transaction {
            val existing = UserSettingsTable.select { UserSettingsTable.userId eq userId }
                .singleOrNull()

            if (existing == null) {
                UserSettingsTable.insert {
                    it[UserSettingsTable.userId] = userId
                    it[ccBridgeToken] = token
                    it[updatedAt] = LocalDateTime.now()
                }
            } else {
                UserSettingsTable.update({ UserSettingsTable.userId eq userId }) {
                    it[ccBridgeToken] = token
                    it[updatedAt] = LocalDateTime.now()
                }
            }
        }

        return token
    }

    /**
     * 获取 Bridge Token
     */
    fun getBridgeToken(userId: String): String? {
        return transaction {
            UserSettingsTable.select { UserSettingsTable.userId eq userId }
                .singleOrNull()
                ?.get(UserSettingsTable.ccBridgeToken)
        }
    }

    /**
     * 通过 token 反向查找 userId，用于 Bridge 认证
     */
    fun findUserIdByBridgeToken(token: String): String? {
        return transaction {
            UserSettingsTable.select { UserSettingsTable.ccBridgeToken eq token }
                .singleOrNull()
                ?.get(UserSettingsTable.userId)
        }
    }

    /**
     * 将数据库行转换为UserSettings对象
     */
    private fun rowToUserSettings(row: ResultRow): UserSettings {
        val languageStr = row[UserSettingsTable.language]
        val language = try {
            Language.valueOf(languageStr)
        } catch (e: IllegalArgumentException) {
            logger.warn("Unknown user language '{}', fallback to CHINESE", languageStr, e)
            Language.CHINESE // 默认值
        }

        return UserSettings(
            language = language,
            defaultAgentInstruction = row[UserSettingsTable.defaultAgentInstruction],
            ccBridgeToken = row[UserSettingsTable.ccBridgeToken],
        )
    }

    private fun generateHexToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
