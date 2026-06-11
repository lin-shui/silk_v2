package com.silk.backend.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.util.*

/**
 * JWT 用户标识，存储在 call.attributes 中供后续路由使用
 */
val JWT_USER_ID = AttributeKey<String>("jwt_user_id")

/**
 * 不需要认证的公开路径前缀
 */
private val publicPathPrefixes = listOf(
    "/auth/",       // 所有认证端点
    "/static",      // 静态文件
    "/health",      // 健康检查
    "/api-docs"     // API 文档
)

/**
 * 配置 JWT 认证
 *
 * 使用 Ktor 的 Authentication 插件，通过 bearer token 验证用户身份
 * 路由层通过 authenticate("jwt-auth") { } 包裹受保护的端点
 */
fun Application.configureJwtAuth() {
    install(Authentication) {
        bearer("jwt-auth") {
            realm = "silk"
            authenticate { credential ->
                val userId = JwtProvider.verifyAccessToken(credential.token)
                if (userId != null) {
                    UserIdPrincipal(userId)
                } else {
                    null
                }
            }
        }
    }
}

/**
 * 检查路径是否为公开端点（不需要认证）
 */
fun isPublicPath(path: String): Boolean {
    return publicPathPrefixes.any { path.startsWith(it) }
}
