package com.silk.backend.ccconnect

import com.silk.backend.database.CcConnectTokens
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.LocalDateTime

data class CcConnectTokenInfo(
    val token: String,
    val groupId: String,
    val label: String,
    val createdAt: String,
)

object CcConnectTokenRepository {

    private val secureRandom = SecureRandom()

    fun generateToken(groupId: String, label: String): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }

        transaction {
            CcConnectTokens.insert {
                it[CcConnectTokens.token] = token
                it[CcConnectTokens.groupId] = groupId
                it[CcConnectTokens.label] = label
                it[CcConnectTokens.createdAt] = LocalDateTime.now()
            }
        }
        return token
    }

    fun findGroupIdByToken(token: String): String? {
        return transaction {
            CcConnectTokens.select { CcConnectTokens.token eq token }
                .singleOrNull()
                ?.get(CcConnectTokens.groupId)
        }
    }

    fun revokeToken(token: String) {
        transaction {
            CcConnectTokens.deleteWhere { CcConnectTokens.token eq token }
        }
    }

    fun revokeAllForGroup(groupId: String) {
        transaction {
            CcConnectTokens.deleteWhere { CcConnectTokens.groupId eq groupId }
        }
    }

    fun getTokenForGroup(groupId: String): CcConnectTokenInfo? {
        return transaction {
            CcConnectTokens.select { CcConnectTokens.groupId eq groupId }
                .singleOrNull()
                ?.let {
                    CcConnectTokenInfo(
                        token = it[CcConnectTokens.token],
                        groupId = it[CcConnectTokens.groupId],
                        label = it[CcConnectTokens.label],
                        createdAt = it[CcConnectTokens.createdAt].toString(),
                    )
                }
        }
    }

    fun regenerateToken(groupId: String, label: String): String {
        revokeAllForGroup(groupId)
        return generateToken(groupId, label)
    }
}
