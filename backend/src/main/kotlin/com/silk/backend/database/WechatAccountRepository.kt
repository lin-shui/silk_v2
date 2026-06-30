package com.silk.backend.database

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 微信账号绑定 Repository
 *
 * 管理微信 openId/unionId 与 Silk 用户之间的绑定关系
 */
object WechatAccountRepository {

    data class WechatAccount(
        val openId: String,
        val unionId: String?,
        val userId: String,
        val wechatName: String?,
        val wechatAvatar: String?
    )

    fun findByOpenId(openId: String): WechatAccount? {
        return transaction {
            WechatAccounts.select { WechatAccounts.openId eq openId }
                .mapNotNull { it.toWechatAccount() }
                .singleOrNull()
        }
    }

    fun findByUnionId(unionId: String): WechatAccount? {
        return transaction {
            WechatAccounts.select { WechatAccounts.unionId eq unionId }
                .mapNotNull { it.toWechatAccount() }
                .singleOrNull()
        }
    }

    fun findByUserId(userId: String): WechatAccount? {
        return transaction {
            WechatAccounts.select { WechatAccounts.userId eq userId }
                .mapNotNull { it.toWechatAccount() }
                .singleOrNull()
        }
    }

    fun create(
        openId: String,
        unionId: String?,
        userId: String,
        wechatName: String?,
        wechatAvatar: String?
    ): WechatAccount {
        transaction {
            WechatAccounts.insert {
                it[WechatAccounts.openId] = openId
                it[WechatAccounts.unionId] = unionId
                it[WechatAccounts.userId] = userId
                it[WechatAccounts.wechatName] = wechatName
                it[WechatAccounts.wechatAvatar] = wechatAvatar
            }
        }
        return WechatAccount(openId, unionId, userId, wechatName, wechatAvatar)
    }

    fun delete(openId: String) {
        transaction {
            WechatAccounts.deleteWhere { WechatAccounts.openId.eq(openId) }
        }
    }

    private fun ResultRow.toWechatAccount() = WechatAccount(
        openId = this[WechatAccounts.openId],
        unionId = this[WechatAccounts.unionId],
        userId = this[WechatAccounts.userId],
        wechatName = this[WechatAccounts.wechatName],
        wechatAvatar = this[WechatAccounts.wechatAvatar]
    )
}
