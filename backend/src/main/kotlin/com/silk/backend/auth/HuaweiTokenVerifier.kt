package com.silk.backend.auth

import com.silk.backend.database.HuaweiUserInfo
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 华为 ID Token 验证器
 *
 * 验证华为 Account Kit 签发的 ID Token（JWT 格式）
 * 从华为 JWKS 端点获取公钥并验证签名，提取用户信息
 *
 * 提供两种验证方式：
 * 1. 通过 code 换取 access_token 后调 userinfo 端点（Web OAuth 流程）
 * 2. 直接验证 ID Token JWT 签名（Harmony/Android 原生流程）
 */
object HuaweiTokenVerifier {
    private val logger = LoggerFactory.getLogger(HuaweiTokenVerifier::class.java)

    private const val HUAWEI_JWKS_URL = "https://oauth-login.cloud.huawei.com/oauth2/v3/jwks"
    private const val HUAWEI_TOKEN_URL = "https://oauth-login.cloud.huawei.com/oauth2/v3/token"
    private const val HUAWEI_USERINFO_URL = "https://oauth-login.cloud.huawei.com/oauth2/v3/userinfo"

    private var cachedJwkSet: Map<String, PublicKey>? = null
    private var cacheTimestamp: Long = 0
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Web OAuth 流程：authorization code → access_token → userinfo
     */
    fun exchangeCodeForUserInfo(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): HuaweiUserInfo? {
        return try {
            // 1. 交换 code → access_token
            val tokenForm = buildString {
                append("grant_type=authorization_code")
                append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
                append("&client_secret=").append(URLEncoder.encode(clientSecret, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
            }

            val tokenRequest = Request.Builder()
                .url(HUAWEI_TOKEN_URL)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(tokenForm.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            val tokenBodyStr = httpClient.newCall(tokenRequest).execute().body?.string() ?: return null
            val tokenObj = json.parseToJsonElement(tokenBodyStr).jsonObject
            val accessToken = tokenObj["access_token"]?.jsonPrimitive?.content ?: return null

            // 2. 调 userinfo
            val userInfoRequest = Request.Builder()
                .url(HUAWEI_USERINFO_URL)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            val userInfoBody = httpClient.newCall(userInfoRequest).execute().body?.string() ?: return null
            val userInfo = json.parseToJsonElement(userInfoBody).jsonObject
            val openId = userInfo["sub"]?.jsonPrimitive?.content
                ?: userInfo["openID"]?.jsonPrimitive?.content
                ?: userInfo["open_id"]?.jsonPrimitive?.content
                ?: return null

            HuaweiUserInfo(
                openId = openId,
                name = userInfo["name"]?.jsonPrimitive?.content ?: "",
                avatar = userInfo["picture"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            logger.error("❌ 华为 OAuth code 交换失败: {}", e.message)
            null
        }
    }

    /**
     * 直接验证 ID Token JWT 签名（Harmony/Android 原生端）
     */
    fun verifyIdToken(idToken: String): HuaweiUserInfo? {
        return try {
            val parts = idToken.split(".")
            if (parts.size != 3) {
                logger.warn("⚠️ ID Token 格式错误")
                return null
            }
            val headerJson = String(Base64.getUrlDecoder().decode(parts[0]))
            val header = json.parseToJsonElement(headerJson).jsonObject
            val kid = header["kid"]?.jsonPrimitive?.content ?: return null

            val publicKey = getPublicKey(kid) ?: return null

            val claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(idToken)
                .payload

            val openId = claims.subject ?: return null
            val name = claims.get("name", String::class.java) ?: ""
            val avatar = claims.get("picture", String::class.java) ?: ""

            HuaweiUserInfo(openId = openId, name = name, avatar = avatar)
        } catch (e: JwtException) {
            logger.warn("⚠️ 华为 ID Token 验证失败: {}", e.message)
            null
        } catch (e: Exception) {
            logger.warn("⚠️ 华为 ID Token 处理异常: {}", e.message)
            null
        }
    }

    private fun getPublicKey(kid: String): PublicKey? {
        if (cachedJwkSet != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cachedJwkSet!![kid] ?: run { refreshJwkSet(); cachedJwkSet?.get(kid) }
        }
        refreshJwkSet()
        return cachedJwkSet?.get(kid)
    }

    private fun refreshJwkSet() {
        try {
            val body = httpClient.newCall(Request.Builder().url(HUAWEI_JWKS_URL).build()).execute().body?.string() ?: return
            val root = json.parseToJsonElement(body).jsonObject
            val keys = root["keys"]?.jsonArray ?: return

            val parsed = mutableMapOf<String, PublicKey>()
            for (elem in keys) {
                val keyObj = elem.jsonObject
                val kid = keyObj["kid"]?.jsonPrimitive?.content ?: continue
                if (keyObj["kty"]?.jsonPrimitive?.content != "RSA") continue
                val n = keyObj["n"]?.jsonPrimitive?.content ?: continue
                val e = keyObj["e"]?.jsonPrimitive?.content ?: continue
                try {
                    val modulus = BigInteger(1, Base64.getUrlDecoder().decode(n))
                    val exponent = BigInteger(1, Base64.getUrlDecoder().decode(e))
                    parsed[kid] = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
                } catch (ex: Exception) {
                    logger.debug("⚠️ 跳过 RSA key {}: {}", kid, ex.message)
                }
            }
            cachedJwkSet = parsed
            cacheTimestamp = System.currentTimeMillis()
            logger.info("✅ 成功拉取华为 JWKS，共 {} 个公钥", parsed.size)
        } catch (e: Exception) {
            logger.error("❌ 拉取华为 JWKS 失败: {}", e.message)
        }
    }
}
