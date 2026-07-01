package com.silk.backend.auth

import com.silk.backend.database.AuthResponse
import com.silk.backend.database.LoginRequest
import com.silk.backend.database.RegisterRequest
import com.silk.backend.database.UserRepository
import com.silk.backend.database.UserSettingsRepository
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory

/**
 * 用户认证服务
 */
object AuthService {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * 注册新用户
     */
    /**
     * 注册已关闭：新用户请使用华为帐号登录
     */
    fun register(@Suppress("UnusedParameter") request: RegisterRequest): AuthResponse {
        return AuthResponse(false, "注册已关闭，请使用华为帐号登录")
    }
    
    /**
     * 用户登录 - 支持 loginName、手机号、全名登录
     */
    fun login(request: LoginRequest): AuthResponse {
        logger.debug("🔐 [Login] 尝试登录: {}", request.loginName)
        
        // 验证输入
        if (request.loginName.isBlank()) {
            logger.debug("🔐 [Login] 失败: 登录名为空")
            return AuthResponse(false, "登录名不能为空")
        }
        if (request.password.isBlank()) {
            logger.debug("🔐 [Login] 失败: 密码为空")
            return AuthResponse(false, "密码不能为空")
        }
        
        // 查找用户 - 支持多种登录方式
        var user = UserRepository.findUserByLoginName(request.loginName)
        if (user == null) {
            logger.debug("🔐 [Login] loginName 未找到，尝试手机号...")
            user = UserRepository.findUserByPhoneNumber(request.loginName)
        }
        if (user == null) {
            logger.debug("🔐 [Login] 手机号未找到，尝试全名...")
            user = UserRepository.findUserByFullName(request.loginName)
        }
        if (user == null) {
            logger.debug("🔐 [Login] 失败: 用户不存在 - {}", request.loginName)
            return AuthResponse(false, "用户名或密码错误")
        }
        logger.debug("🔐 [Login] 找到用户: {} (loginName: {})", user.id, user.loginName)
        
        // 验证密码 - 使用找到的用户的 loginName
        val passwordHash = UserRepository.getUserPasswordHash(user.loginName)
        logger.debug("🔐 [Login] 密码哈希: {}...", passwordHash?.take(20))
        if (passwordHash == null) {
            logger.debug("🔐 [Login] 失败: 无法获取密码哈希")
            return AuthResponse(false, "用户名或密码错误")
        }
        
        val passwordMatch = BCrypt.checkpw(request.password, passwordHash)
        logger.debug("🔐 [Login] 密码验证结果: {}", passwordMatch)
        if (!passwordMatch) {
            return AuthResponse(false, "用户名或密码错误")
        }
        
        logger.debug("🔐 [Login] 成功: {}", user.loginName)
        // 生成 JWT Token，供绑定 API 等需要鉴权的端点使用
        val (accessToken, refreshToken) = JwtProvider.generateTokenPair(user.id)
        // 生成或复用 App HTTP 认证 token，供 KB 等 API bearer 认证使用
        val appAuthToken = UserSettingsRepository.getOrCreateAppAuthToken(user.id)
        return AuthResponse(true, "登录成功", user, accessToken, refreshToken, appAuthToken)
    }
    
    /**
     * 验证用户是否存在
     */
    fun validateUser(userId: String): Boolean {
        return UserRepository.findUserById(userId) != null
    }
}

