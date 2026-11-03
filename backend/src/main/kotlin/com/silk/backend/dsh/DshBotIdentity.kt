package com.silk.backend.dsh

import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.database.User
import com.silk.backend.database.UserRepository
import com.silk.backend.database.Users
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * dsh-superagent 集成 bot 的固定身份。
 *
 * userId 结尾满足前端 `_ai_agent` 约定（三端 UI 按 agent 渲染），
 * 后端侧由 AgentRuntime.isAgentUserId 白名单识别（不触发 Silk AI / ACP agent）。
 * bot 以 GUEST 角色入群：silk 内部不赋予触发权，谁能发起工作由 dsh 侧 Policy 决定。
 */
object DshBotIdentity {
    private val logger = LoggerFactory.getLogger(DshBotIdentity::class.java)

    val BOT_USER_ID: String = AgentRuntime.DSH_BOT_USER_ID
    const val BOT_LOGIN_NAME = "dsh"
    const val BOT_DISPLAY_NAME = "DSH"

    /** 幂等地创建 bot 用户（固定 userId，不可登录，仅作为消息发送者身份）。 */
    @Suppress("TooGenericExceptionCaught")
    fun ensureBotUser(): User? {
        UserRepository.findUserById(BOT_USER_ID)?.let { return it }
        return try {
            transaction {
                // 并发兜底：唯一索引冲突时说明另一线程已创建
                runCatching {
                    Users.insert {
                        it[id] = BOT_USER_ID
                        it[loginName] = BOT_LOGIN_NAME
                        it[fullName] = BOT_DISPLAY_NAME
                        it[phoneNumber] = "dsh-bot"
                        it[passwordHash] = "" // bot 不可通过登录接口认证
                    }
                }.onFailure { logger.debug("dsh bot 用户已由其他线程创建: {}", it.message) }
            }
            UserRepository.findUserById(BOT_USER_ID)
        } catch (e: Exception) {
            logger.error("❌ 创建 dsh bot 用户失败: {}", e.message)
            null
        }
    }
}
