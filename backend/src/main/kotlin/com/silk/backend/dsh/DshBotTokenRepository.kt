package com.silk.backend.dsh

import com.silk.backend.database.DshBotTokens
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.LocalDateTime

data class DshBotTokenInfo(
    val userId: String,
    val label: String,
    val createdAt: String,
)

/**
 * dsh bot 访问 token 存储：同一时间至多一个有效 token，
 * regenerate 撤销旧 token 后生成新 token（旧 token 立即失效）。
 */
object DshBotTokenRepository {

    private val secureRandom = SecureRandom()

    /** 撤销全部旧 token 并生成新 token，返回明文 token（仅此一次返回）。 */
    fun regenerate(userId: String, label: String): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }

        transaction {
            DshBotTokens.deleteWhere { DshBotTokens.userId eq userId }
            DshBotTokens.insert {
                it[DshBotTokens.token] = token
                it[DshBotTokens.userId] = userId
                it[DshBotTokens.label] = label
                it[createdAt] = LocalDateTime.now()
            }
        }
        return token
    }

    /** 校验 token，有效时返回绑定的 bot userId。 */
    fun findUserIdByToken(token: String): String? {
        if (token.isBlank()) return null
        return transaction {
            DshBotTokens.selectAll()
                .singleOrNull()
                ?.takeIf { it[DshBotTokens.token] == token }
                ?.get(DshBotTokens.userId)
        }
    }

    /** 当前 token 元信息（不含 token 明文）。 */
    fun getTokenInfo(): DshBotTokenInfo? {
        return transaction {
            DshBotTokens.selectAll().singleOrNull()?.let {
                DshBotTokenInfo(
                    userId = it[DshBotTokens.userId],
                    label = it[DshBotTokens.label],
                    createdAt = it[DshBotTokens.createdAt].toString(),
                )
            }
        }
    }
}
