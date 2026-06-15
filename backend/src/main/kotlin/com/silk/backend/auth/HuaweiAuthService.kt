package com.silk.backend.auth

import com.silk.backend.EnvLoader
import com.silk.backend.database.*
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 华为账号认证服务
 *
 * 处理华为账号的登录逻辑（创建/关联用户、颁发 Token）
 */
object HuaweiAuthService {
    private val logger = LoggerFactory.getLogger(HuaweiAuthService::class.java)

    data class AuthResult(
        val success: Boolean,
        val message: String = "",
        val user: User? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val isNewUser: Boolean = false
    )

    /**
     * Web OAuth 登录（Authorization Code 模式）
     * 前端跳转华为 OAuth → 回调 → 前端发 code 到后端 → 后端交换 token → 登录/注册
     */
    fun webLogin(code: String, redirectUri: String): AuthResult {
        val clientId = EnvLoader.get("HUAWEI_OAUTH_CLIENT_ID") ?: return AuthResult(false, "华为 OAuth 未配置（缺少 CLIENT_ID）")
        val clientSecret = EnvLoader.get("HUAWEI_OAUTH_CLIENT_SECRET") ?: return AuthResult(false, "华为 OAuth 未配置（缺少 CLIENT_SECRET）")

        // 1. 交换 code 获取用户信息
        val huaweiUser = HuaweiTokenVerifier.exchangeCodeForUserInfo(
            code = code,
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = redirectUri
        ) ?: return AuthResult(false, "华为账号验证失败")

        return loginOrCreateUser(huaweiUser)
    }

    /**
     * Harmony/Android 原生登录（ID Token 模式）
     * 客户端通过 Account Kit 获取 ID Token → 发送到后端 → 验证签名 → 登录/注册
     */
    fun nativeLogin(idToken: String): AuthResult {
        val huaweiUser = HuaweiTokenVerifier.verifyIdToken(idToken)
            ?: return AuthResult(false, "华为 ID Token 验证失败")

        return loginOrCreateUser(huaweiUser)
    }

    /**
     * 将华为账号绑定到已有用户（老账号迁移）
     * 老用户登录后，调用此方法将华为 openId 绑定到其 userId
     */
    fun bindToUser(code: String, redirectUri: String, userId: String): AuthResult {
        val clientId = EnvLoader.get("HUAWEI_OAUTH_CLIENT_ID")
            ?: return AuthResult(false, "华为 OAuth 未配置（缺少 CLIENT_ID）")
        val clientSecret = EnvLoader.get("HUAWEI_OAUTH_CLIENT_SECRET")
            ?: return AuthResult(false, "华为 OAuth 未配置（缺少 CLIENT_SECRET）")

        // 1. 交换 code 获取华为用户信息
        val huaweiUser = HuaweiTokenVerifier.exchangeCodeForUserInfo(
            code = code,
            clientId = clientId,
            clientSecret = clientSecret,
            redirectUri = redirectUri
        ) ?: return AuthResult(false, "华为账号验证失败")

        // 2. 检查该华为账号是否已被其他用户绑定
        val existingBinding = HuaweiAccountRepository.findByOpenId(huaweiUser.openId)
        if (existingBinding != null) {
            if (existingBinding.userId == userId) {
                return AuthResult(true, "该华为账号已绑定到当前用户")
            }
            return AuthResult(false, "该华为账号已被其他用户绑定")
        }

        // 3. 检查当前用户是否已绑定其他华为账号
        val userBinding = HuaweiAccountRepository.findByUserId(userId)
        if (userBinding != null) {
            return AuthResult(false, "当前用户已绑定华为账号，请先解绑")
        }

        // 4. 创建绑定
        val silkUser = UserRepository.findUserById(userId)
            ?: return AuthResult(false, "用户不存在")

        HuaweiAccountRepository.create(
            openId = huaweiUser.openId,
            userId = userId,
            huaweiName = huaweiUser.name,
            huaweiAvatar = huaweiUser.avatar
        )
        logger.info("✅ 华为账号绑定成功: userId={}, openId={}", userId, huaweiUser.openId)

        return AuthResult(
            success = true,
            message = "华为账号绑定成功",
            user = silkUser
        )
    }

    /**
     * 核心逻辑：根据华为用户信息登录或创建 Silk 用户
     */
    private fun loginOrCreateUser(huaweiUser: HuaweiUserInfo): AuthResult {
        // 1. 查找是否已有绑定
        val existingBinding = HuaweiAccountRepository.findByOpenId(huaweiUser.openId)

        if (existingBinding != null) {
            // 已有绑定 → 直接登录
            val userId = existingBinding.userId
            val silkUser = UserRepository.findUserById(userId)
            if (silkUser == null) {
                // 用户已被删除，清理绑定
                HuaweiAccountRepository.delete(huaweiUser.openId)
                return AuthResult(false, "关联用户不存在")
            }
            logger.info("✅ 华为账号登录成功: {} (openId={})", silkUser.loginName, huaweiUser.openId)

            // 生成 Token
            val (accessToken, refreshToken) = JwtProvider.generateTokenPair(userId)
            return AuthResult(
                success = true,
                message = "登录成功",
                user = silkUser,
                accessToken = accessToken,
                refreshToken = refreshToken
            )
        }

        // 无绑定 → 创建新用户
        val loginName = "huawei_${huaweiUser.openId.take(12)}"
        val fullName = huaweiUser.name.ifBlank { "华为用户_${huaweiUser.openId.take(6)}" }

        val existingUser = UserRepository.findUserByLoginName(loginName)
        val finalLoginName = if (existingUser != null) {
            "${loginName}_${UUID.randomUUID().toString().take(6)}"
        } else {
            loginName
        }

        val silkUser = UserRepository.createUser(
            loginName = finalLoginName,
            fullName = fullName,
            phoneNumber = "",
            passwordHash = ""
        ) ?: return AuthResult(false, "创建用户失败")

        // 创建绑定关系
        HuaweiAccountRepository.create(
            openId = huaweiUser.openId,
            userId = silkUser.id,
            huaweiName = huaweiUser.name,
            huaweiAvatar = huaweiUser.avatar
        )

        logger.info("✅ 华为账号新用户创建成功: {} (openId={})", silkUser.loginName, huaweiUser.openId)

        // 生成 Token — 标记为新用户，前端需要弹出昵称设置
        val (accessToken, refreshToken) = JwtProvider.generateTokenPair(silkUser.id)
        return AuthResult(
            success = true,
            message = "登录成功",
            user = silkUser,
            accessToken = accessToken,
            refreshToken = refreshToken,
            isNewUser = true
        )
    }
}
