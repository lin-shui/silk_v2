package com.silk.backend.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * 刷新令牌 Repository
 */
object RefreshTokenRepository {

    data class RefreshTokenRow(
        val jti: String,
        val userId: String,
        val expiresAt: LocalDateTime,
        val revoked: Boolean
    )

    /**
     * 查找有效的刷新令牌
     */
    fun findValid(jti: String): RefreshTokenRow? {
        return transaction {
            RefreshTokensTable.select {
                (RefreshTokensTable.jti eq jti) and
                (RefreshTokensTable.revoked eq false) and
                (RefreshTokensTable.expiresAt greaterEq LocalDateTime.now())
            }
                .mapNotNull { it.toRefreshToken() }
                .singleOrNull()
        }
    }

    /**
     * 创建刷新令牌
     */
    fun create(jti: String, userId: String, expiresAt: LocalDateTime) {
        transaction {
            RefreshTokensTable.insert {
                it[RefreshTokensTable.jti] = jti
                it[RefreshTokensTable.userId] = userId
                it[RefreshTokensTable.expiresAt] = expiresAt
            }
        }
    }

    /**
     * 撤销指定的刷新令牌
     */
    fun revoke(jti: String) {
        transaction {
            RefreshTokensTable.update({ RefreshTokensTable.jti eq jti }) {
                it[revoked] = true
            }
        }
    }

    /**
     * 撤销用户的所有刷新令牌（全设备登出）
     */
    fun revokeAllForUser(userId: String) {
        transaction {
            RefreshTokensTable.update({ RefreshTokensTable.userId eq userId }) {
                it[revoked] = true
            }
        }
    }

    private fun ResultRow.toRefreshToken() = RefreshTokenRow(
        jti = this[RefreshTokensTable.jti],
        userId = this[RefreshTokensTable.userId],
        expiresAt = this[RefreshTokensTable.expiresAt],
        revoked = this[RefreshTokensTable.revoked]
    )
}

