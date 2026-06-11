package com.silk.backend.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 用户数据访问层
 */
object UserRepository {
    private val logger = LoggerFactory.getLogger(UserRepository::class.java)
    
    /**
     * 创建新用户
     */
    fun createUser(
        loginName: String,
        fullName: String,
        phoneNumber: String,
        passwordHash: String
    ): User? {
        return try {
            transaction {
                val userId = UUID.randomUUID().toString()
                
                Users.insert {
                    it[id] = userId
                    it[Users.loginName] = loginName
                    it[Users.fullName] = fullName
                    it[Users.phoneNumber] = phoneNumber
                    it[Users.passwordHash] = passwordHash
                }
                
                findUserById(userId)
            }
        } catch (e: Exception) {
            logger.error("❌ 创建用户失败: {}", e.message)
            null
        }
    }
    
    /**
     * 更新用户显示昵称
     */
    fun updateFullName(userId: String, newFullName: String): User? {
        return try {
            transaction {
                Users.update({ Users.id eq userId }) {
                    it[fullName] = newFullName
                }
                findUserById(userId)
            }
        } catch (e: Exception) {
            logger.error("❌ 更新用户昵称失败: {}", e.message)
            null
        }
    }

    /**
     * 根据ID查找用户
     */
    fun findUserById(userId: String): User? {
        return transaction {
            Users.select { Users.id eq userId }
                .mapNotNull { rowToUser(it) }
                .singleOrNull()
        }
    }
    
    /**
     * 根据登录名查找用户
     */
    fun findUserByLoginName(loginName: String): User? {
        return transaction {
            Users.select { Users.loginName eq loginName }
                .mapNotNull { rowToUser(it) }
                .singleOrNull()
        }
    }
    
    /**
     * 根据手机号查找用户
     */
    fun findUserByPhoneNumber(phoneNumber: String): User? {
        return transaction {
            Users.select { Users.phoneNumber eq phoneNumber }
                .mapNotNull { rowToUser(it) }
                .singleOrNull()
        }
    }
    
    /**
     * 根据全名查找用户
     */
    fun findUserByFullName(fullName: String): User? {
        return transaction {
            Users.select { Users.fullName eq fullName }
                .mapNotNull { rowToUser(it) }
                .singleOrNull()
        }
    }
    
    /**
     * 获取用户的密码哈希（用于验证）
     */
    fun getUserPasswordHash(loginName: String): String? {
        return transaction {
            Users.select { Users.loginName eq loginName }
                .map { it[Users.passwordHash] }
                .singleOrNull()
        }
    }
    
    /**
     * 检查登录名是否已存在
     */
    fun loginNameExists(loginName: String): Boolean {
        return transaction {
            Users.select { Users.loginName eq loginName }
                .count() > 0
        }
    }
    
    /**
     * 检查手机号是否已存在
     */
    fun phoneNumberExists(phoneNumber: String): Boolean {
        return transaction {
            Users.select { Users.phoneNumber eq phoneNumber }
                .count() > 0
        }
    }
    
    /**
     * 删除用户及其所有关联数据（注销账号）
     */
    fun deleteUser(userId: String): Boolean {
        return try {
            transaction {
                // 按外键依赖顺序删除

                // 1. 删除华为账号绑定
                HuaweiAccounts.deleteWhere { HuaweiAccounts.userId eq userId }

                // 2. 删除群组成员关系
                GroupMembers.deleteWhere { GroupMembers.userId eq userId }

                // 3. 删除联系人关系（双向）
                Contacts.deleteWhere { Contacts.userId eq userId }
                Contacts.deleteWhere { Contacts.contactId eq userId }

                // 4. 删除联系人请求（双向）
                ContactRequests.deleteWhere { ContactRequests.fromUserId eq userId }
                ContactRequests.deleteWhere { ContactRequests.toUserId eq userId }

                // 5. 删除用户设置
                UserSettingsTable.deleteWhere { UserSettingsTable.userId eq userId }

                // 6. 撤销所有 Refresh Token
                RefreshTokensTable.deleteWhere { RefreshTokensTable.userId eq userId }

                // 7. 删除用户拥有的群组（级联删除相关数据）
                val ownedGroups = Groups.select { Groups.hostId eq userId }.map { it[Groups.id] }
                for (groupId in ownedGroups) {
                    // 删除群组成员
                    GroupMembers.deleteWhere { GroupMembers.groupId eq groupId }
                    // 删除 cc-connect token
                    CcConnectTokens.deleteWhere { CcConnectTokens.groupId eq groupId }
                }
                Groups.deleteWhere { Groups.hostId eq userId }

                // 8. 最后删除用户本身
                Users.deleteWhere { Users.id eq userId }

                logger.info("✅ 用户注销成功: userId={}", userId)
                true
            }
        } catch (e: Exception) {
            logger.error("❌ 用户注销失败: {}", e.message)
            false
        }
    }

    /**
     * 将数据库行转换为User对象
     */
    private fun rowToUser(row: ResultRow): User {
        return User(
            id = row[Users.id],
            loginName = row[Users.loginName],
            fullName = row[Users.fullName],
            phoneNumber = row[Users.phoneNumber],
            createdAt = row[Users.createdAt].toString()
        )
    }
}

