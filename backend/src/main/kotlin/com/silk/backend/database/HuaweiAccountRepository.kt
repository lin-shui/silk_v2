package com.silk.backend.database

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 华为账号绑定 Repository
 */
object HuaweiAccountRepository {

    data class HuaweiAccount(
        val openId: String,
        val userId: String,
        val huaweiName: String?,
        val huaweiAvatar: String?
    )

    fun findByOpenId(openId: String): HuaweiAccount? {
        return transaction {
            HuaweiAccounts.select { HuaweiAccounts.openId eq openId }
                .mapNotNull { it.toHuaweiAccount() }
                .singleOrNull()
        }
    }

    fun findByUserId(userId: String): HuaweiAccount? {
        return transaction {
            HuaweiAccounts.select { HuaweiAccounts.userId eq userId }
                .mapNotNull { it.toHuaweiAccount() }
                .singleOrNull()
        }
    }

    fun create(openId: String, userId: String, huaweiName: String?, huaweiAvatar: String?): HuaweiAccount {
        transaction {
            HuaweiAccounts.insert {
                it[HuaweiAccounts.openId] = openId
                it[HuaweiAccounts.userId] = userId
                it[HuaweiAccounts.huaweiName] = huaweiName
                it[HuaweiAccounts.huaweiAvatar] = huaweiAvatar
            }
        }
        return HuaweiAccount(openId, userId, huaweiName, huaweiAvatar)
    }

    fun delete(openId: String) {
        transaction {
            val col = HuaweiAccounts.openId
            HuaweiAccounts.deleteWhere { col.eq(openId) }
        }
    }

    private fun ResultRow.toHuaweiAccount() = HuaweiAccount(
        openId = this[HuaweiAccounts.openId],
        userId = this[HuaweiAccounts.userId],
        huaweiName = this[HuaweiAccounts.huaweiName],
        huaweiAvatar = this[HuaweiAccounts.huaweiAvatar]
    )
}

