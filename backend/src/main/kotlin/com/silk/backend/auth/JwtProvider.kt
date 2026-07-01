package com.silk.backend.auth

import com.silk.backend.EnvLoader
import com.silk.backend.database.RefreshTokenRepository
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.*
import javax.crypto.SecretKey

/**
 * JWT Token 提供者
 * 负责签发、验证 Access Token 和 Refresh Token
 */
object JwtProvider {
    private val logger = LoggerFactory.getLogger(JwtProvider::class.java)

    private val secretKey: SecretKey by lazy {
        val secret = EnvLoader.get("SILK_JWT_SECRET")
        if (!secret.isNullOrBlank()) {
            logger.info("🔑 使用环境变量 SILK_JWT_SECRET 作为 JWT 密钥")
            Keys.hmacShaKeyFor(secret.toByteArray())
        } else {
            logger.warn("⚠️ SILK_JWT_SECRET 未设置，自动生成临时密钥（重启后旧 Token 失效）")
            Jwts.SIG.HS256.key().build()
        }
    }

    private const val ACCESS_TOKEN_TTL_MINUTES = 1440L // 24小时
    private const val REFRESH_TOKEN_TTL_DAYS = 30L

    /**
     * 生成 Access Token（JWT，1小时有效期）
     */
    fun generateAccessToken(userId: String): String {
        val now = Date()
        val expiration = Date(now.time + ACCESS_TOKEN_TTL_MINUTES * 60 * 1000)

        return Jwts.builder()
            .subject(userId)
            .issuedAt(now)
            .expiration(expiration)
            .claim("type", "access")
            .signWith(secretKey)
            .compact()
    }

    /**
     * 生成 Refresh Token（随机 UUID 字符串，30天有效期）
     * 同时写入 refresh_tokens 表
     */
    fun generateRefreshToken(userId: String): String {
        val jti = UUID.randomUUID().toString().replace("-", "")
        val expiresAt = LocalDateTime.now().plusDays(REFRESH_TOKEN_TTL_DAYS)
        RefreshTokenRepository.create(jti, userId, expiresAt)
        return jti
    }

    /**
     * 验证 Access Token，返回 userId 或 null
     */
    fun verifyAccessToken(token: String): String? {
        return try {
            val claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .payload

            val type = claims.get("type", String::class.java)
            if (type != "access") {
                logger.warn("⚠️ Token 类型错误: {}", type)
                return null
            }

            claims.subject
        } catch (e: JwtException) {
            logger.warn("⚠️ JWT 验证失败: {}", e.message)
            null
        } catch (e: IllegalArgumentException) {
            logger.warn("⚠️ JWT 格式错误: {}", e.message)
            null
        }
    }

    /**
     * 验证 Refresh Token，返回 userId 或 null
     */
    fun verifyRefreshToken(refreshToken: String): String? {
        val row = RefreshTokenRepository.findValid(refreshToken) ?: return null
        return row.userId
    }

    /**
     * 刷新 Access Token：用 Refresh Token 换取新的 Access Token
     * 返回新的 access token，或 null（refresh token 无效）
     */
    fun refreshAccessToken(refreshToken: String): String? {
        val userId = verifyRefreshToken(refreshToken) ?: return null
        return generateAccessToken(userId)
    }

    /**
     * 同时生成 Access Token 和 Refresh Token
     */
    fun generateTokenPair(userId: String): Pair<String, String> {
        val accessToken = generateAccessToken(userId)
        val refreshToken = generateRefreshToken(userId)
        return Pair(accessToken, refreshToken)
    }

    /**
     * 登出：撤销指定 Refresh Token
     */
    fun revokeRefreshToken(refreshToken: String) {
        RefreshTokenRepository.revoke(refreshToken)
    }
}
