package com.silk.backend.auth

import com.silk.backend.EnvLoader
import com.silk.backend.database.User
import com.silk.backend.database.UserRepository
import com.silk.backend.database.WechatAccountRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 微信账号认证服务
 *
 * 处理微信 OAuth2.0 授权码登录流程：
 * 1. Android 端通过微信 SDK 获取授权 code
 * 2. 后端用 code 向微信服务器交换 access_token 和 openid
 * 3. 用 access_token 获取用户信息（昵称、头像）
 * 4. 查找/创建 Silk 用户并颁发 JWT Token
 *
 * 微信 OAuth 文档：https://developers.weixin.qq.com/doc/oplatform/Mobile_App/WeChat_Login/Development_Guide.html
 */
object WechatAuthService {
    private val logger = LoggerFactory.getLogger(WechatAuthService::class.java)

    data class AuthResult(
        val success: Boolean,
        val message: String = "",
        val user: User? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val isNewUser: Boolean = false
    )

    // 微信 API 端点
    private const val WECHAT_ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token"
    private const val WECHAT_USERINFO_URL = "https://api.weixin.qq.com/sns/userinfo"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 微信 OAuth 登录
     *
     * @param code 微信 SDK 授权后返回的临时授权码
     * @return AuthResult 认证结果
     */
    fun login(code: String): AuthResult {
        // 1. 获取配置
        val appId = EnvLoader.get("WECHAT_APP_ID")
            ?: return AuthResult(false, "微信登录未配置（缺少 WECHAT_APP_ID）")
        val appSecret = EnvLoader.get("WECHAT_APP_SECRET")
            ?: return AuthResult(false, "微信登录未配置（缺少 WECHAT_APP_SECRET）")

        // 2. 用 code 交换 access_token 和 openid
        val tokenResponse = exchangeCodeForToken(appId, appSecret, code)
            ?: return AuthResult(false, "微信授权码验证失败")

        logger.info("✅ 微信 code 交换成功: openId={}", tokenResponse.openid)

        // 3. 获取微信用户信息
        val wechatUser = getUserInfo(tokenResponse.accessToken, tokenResponse.openid)
            ?: return AuthResult(false, "获取微信用户信息失败")

        logger.info("✅ 获取微信用户信息成功: nickname={}", wechatUser.nickname)

        // 4. 登录或创建用户
        return loginOrCreateUser(wechatUser)
    }

    /**
     * 用授权 code 向微信服务器交换 access_token
     */
    private fun exchangeCodeForToken(
        appId: String,
        appSecret: String,
        code: String
    ): WechatTokenResponse? {
        return try {
            val url = "$WECHAT_ACCESS_TOKEN_URL?" +
                "appid=$appId&secret=$appSecret&code=$code&grant_type=authorization_code"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyStr = response.body?.string()
            if (bodyStr == null) {
                logger.error("❌ 微信 access_token 响应为空")
                return null
            }

            val tokenResponse = json.decodeFromString<WechatTokenResponse>(bodyStr)

            if (tokenResponse.errcode != null && tokenResponse.errcode != 0) {
                logger.error("❌ 微信 access_token 换取失败: errcode={}, errmsg={}",
                    tokenResponse.errcode, tokenResponse.errmsg)
                return null
            }

            tokenResponse
        } catch (e: Exception) {
            logger.error("❌ 微信 access_token 换取异常: {}", e.message)
            null
        }
    }

    /**
     * 用 access_token 获取微信用户信息
     */
    private fun getUserInfo(
        accessToken: String,
        openId: String
    ): WechatUserResponse? {
        return try {
            val url = "$WECHAT_USERINFO_URL?access_token=$accessToken&openid=$openId"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyStr = response.body?.string()
            if (bodyStr == null) {
                logger.error("❌ 微信用户信息响应为空")
                return null
            }

            val userResponse = json.decodeFromString<WechatUserResponse>(bodyStr)

            if (userResponse.errcode != null && userResponse.errcode != 0) {
                logger.error("❌ 微信用户信息获取失败: errcode={}, errmsg={}",
                    userResponse.errcode, userResponse.errmsg)
                return null
            }

            userResponse
        } catch (e: Exception) {
            logger.error("❌ 微信用户信息获取异常: {}", e.message)
            null
        }
    }

    /**
     * 核心逻辑：根据微信用户信息登录或创建 Silk 用户
     */
    private fun loginOrCreateUser(wechatUser: WechatUserResponse): AuthResult {
        val openId = wechatUser.openid
        val unionId = wechatUser.unionid

        // 1. 优先通过 unionId 查找绑定（同一开放平台下多个应用共享 unionId）
        var existingBinding: WechatAccountRepository.WechatAccount? = null
        if (!unionId.isNullOrBlank()) {
            existingBinding = WechatAccountRepository.findByUnionId(unionId)
        }

        // 2. 如果 unionId 没找到，通过 openId 查找
        if (existingBinding == null) {
            existingBinding = WechatAccountRepository.findByOpenId(openId)
        }

        if (existingBinding != null) {
            // 已有绑定 → 直接登录
            val userId = existingBinding.userId
            val silkUser = UserRepository.findUserById(userId)
            if (silkUser == null) {
                // 用户已被删除，清理绑定
                WechatAccountRepository.delete(openId)
                return AuthResult(false, "关联用户不存在")
            }

            logger.info("✅ 微信登录成功: {} (openId={})", silkUser.loginName, openId)

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

        // 3. 无绑定 → 创建新用户
        val loginName = "wechat_${openId.take(12)}"
        val fullName = wechatUser.nickname.ifBlank { "微信用户_${openId.take(6)}" }

        // 防止 loginName 冲突
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
        WechatAccountRepository.create(
            openId = openId,
            unionId = unionId,
            userId = silkUser.id,
            wechatName = wechatUser.nickname,
            wechatAvatar = wechatUser.headimgurl
        )

        logger.info("✅ 微信新用户创建成功: {} (openId={})", silkUser.loginName, openId)

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

    /**
     * 微信 access_token 响应
     */
    @Serializable
    data class WechatTokenResponse(
        val accessToken: String = "",
        val expiresIn: Int = 0,
        val refreshToken: String = "",
        val openid: String = "",
        val scope: String = "",
        val unionid: String? = null,
        val errcode: Int? = null,
        val errmsg: String? = null
    )

    /**
     * 微信用户信息响应
     */
    @Serializable
    data class WechatUserResponse(
        val openid: String = "",
        val nickname: String = "",
        val sex: Int = 0,
        val province: String = "",
        val city: String = "",
        val country: String = "",
        val headimgurl: String = "",
        val privilege: List<String>? = null,
        val unionid: String? = null,
        val errcode: Int? = null,
        val errmsg: String? = null
    )
}
