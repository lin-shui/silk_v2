package com.silk.backend.database

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 用户数据访问层
 */
@Suppress("TooGenericExceptionCaught")
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
                WechatAccounts.deleteWhere { WechatAccounts.userId eq userId }

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

    /** 根据名称、登录名或电话号码搜索用户。 */
    fun searchUsersByName(query: String, limit: Int = 10): List<User> {
        if (normalizeUserSearchText(query).isBlank()) return emptyList()
        return transaction {
            rankUsersForSearch(
                users = Users.selectAll().mapNotNull { rowToUser(it) },
                query = query,
                limit = limit,
            )
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

internal fun rankUsersForSearch(
    users: List<User>,
    query: String,
    limit: Int = 10,
): List<User> {
    val normalizedQuery = normalizeUserSearchText(query)
    if (normalizedQuery.isBlank()) return emptyList()
    return users.mapNotNull { user ->
        val score = listOf(user.loginName, user.fullName, user.phoneNumber)
            .mapIndexedNotNull { index, field ->
                userSearchFieldScore(
                    field = normalizeUserSearchText(field),
                    query = normalizedQuery,
                    allowFuzzy = index < 2,
                )?.plus(index * 2)
            }
            .minOrNull()
            ?: return@mapNotNull null
        user to score
    }.sortedWith(
        compareBy<Pair<User, Int>> { it.second }
            .thenBy { it.first.fullName.lowercase() }
            .thenBy { it.first.loginName.lowercase() }
    ).take(limit.coerceAtLeast(0)).map { it.first }
}

private fun normalizeUserSearchText(value: String): String = value
    .trim()
    .lowercase()
    .filter(Char::isLetterOrDigit)

private fun userSearchFieldScore(field: String, query: String, allowFuzzy: Boolean): Int? {
    if (field.isBlank()) return null
    if (field == query) return 0
    if (field.startsWith(query)) return 10 + (field.length - query.length).coerceAtMost(20)
    val containsAt = field.indexOf(query)
    if (containsAt >= 0) return 40 + containsAt
    if (!allowFuzzy || query.all(Char::isDigit)) return null
    subsequenceGapScore(field, query)?.let { return 80 + it }
    if (query.length < 2) return null
    val maxDistance = when {
        query.length >= 5 -> 2
        else -> 1
    }
    val distance = levenshteinDistance(field, query, maxDistance)
    return distance?.let { 140 + it * 10 + kotlin.math.abs(field.length - query.length) }
}

private fun subsequenceGapScore(field: String, query: String): Int? {
    var fieldIndex = 0
    var firstMatch = -1
    var lastMatch = -1
    for (character in query) {
        val matchIndex = field.indexOf(character, fieldIndex)
        if (matchIndex < 0) return null
        if (firstMatch < 0) firstMatch = matchIndex
        lastMatch = matchIndex
        fieldIndex = matchIndex + 1
    }
    return firstMatch + (lastMatch - firstMatch + 1 - query.length)
}

private fun levenshteinDistance(left: String, right: String, maxDistance: Int): Int? {
    if (kotlin.math.abs(left.length - right.length) > maxDistance) return null
    var previous = IntArray(right.length + 1) { it }
    for (leftIndex in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        var rowMinimum = current[0]
        for (rightIndex in right.indices) {
            val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + substitutionCost,
            )
            rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
        }
        if (rowMinimum > maxDistance) return null
        previous = current
    }
    return previous[right.length].takeIf { it <= maxDistance }
}
