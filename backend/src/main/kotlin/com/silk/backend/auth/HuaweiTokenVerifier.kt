package com.silk.backend.auth

import com.silk.backend.database.HuaweiUserInfo
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import kotlinx.serialization.json.JsonObject
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
@Suppress("TooGenericExceptionCaught")
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
     * Web OAuth 流程：authorization code → access_token + id_token → 解码 id_token 获取用户信息
     *
     * 华为 OAuth 的 token 端点返回的 id_token (JWT) 中直接包含用户信息，
     * 不需要额外调 userinfo 端点（该端点已废弃/404）。
     */
    fun exchangeCodeForUserInfo(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): HuaweiUserInfo? {
        return try {
            // 1. 交换 code → access_token & id_token
            val tokenObj = requestHuaweiTokenObject(code, clientId, clientSecret, redirectUri)
                ?: return null

            // 2. 优先从 id_token (JWT) 中解码用户信息 —— 更可靠，不依赖 userinfo 端点
            // 一旦拿到 id_token 即硬停（成功或失败都不再尝试 userinfo 降级），与原逻辑一致。
            val idToken = tokenObj["id_token"]?.jsonPrimitive?.content
            if (idToken != null) {
                return decodeUserInfoFromIdToken(idToken)
            }

            // 3. 降级：从 access_token 调 userinfo 端点
            logger.warn("⚠️ 未获取到 id_token，尝试调 userinfo 端点")
            val accessToken = tokenObj["access_token"]?.jsonPrimitive?.content ?: return null
            fetchUserInfoFromUserinfoEndpoint(accessToken)
        } catch (e: Exception) {
            logger.error("❌ 华为 OAuth code 交换失败: {} (redirectUri={})", e.message, redirectUri)
            null
        }
    }

    /**
     * 第 1 步：用 authorization code 交换 token，返回解析后的 token JSON 对象。
     * 空响应/解析失败/华为返回错误时返回 null（与原内联早返回等价）。
     */
    private fun requestHuaweiTokenObject(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String,
    ): JsonObject? {
        logger.info("📤 请求华为 token 端点, redirectUri={}, code前8字={}", redirectUri, code.take(8))
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

        val response = httpClient.newCall(tokenRequest).execute()
        val tokenBodyStr = response.body?.string()
        if (tokenBodyStr == null) {
            logger.error("❌ 华为 token 端点返回空响应, HTTP status=${response.code}, redirectUri={}", redirectUri)
            return null
        }
        logger.info("📥 华为 token 端点响应 (HTTP ${response.code}, 前300字): {}", tokenBodyStr.take(300))
        val tokenObj = try {
            json.parseToJsonElement(tokenBodyStr).jsonObject
        } catch (e: Exception) {
            logger.error("❌ 华为 token 响应 JSON 解析失败: {} | 原始响应(前500字): {}", e.message, tokenBodyStr.take(500))
            return null
        }
        // 检查华为是否返回了错误
        if (tokenObj.containsKey("error") || tokenObj.containsKey("sub_error")) {
            val errCode = tokenObj["error"]?.jsonPrimitive?.content ?: "?"
            val errDesc = tokenObj["error_description"]?.jsonPrimitive?.content ?: "?"
            val subErr = tokenObj["sub_error"]?.jsonPrimitive?.content ?: "?"
            logger.error("❌ 华为 token 交换失败: error=$errCode, description=$errDesc, sub_error=$subErr, redirectUri={}", redirectUri)
            return null
        }
        return tokenObj
    }

    /**
     * 第 2 步：从 id_token 解码用户信息。先尝试 JWKS 签名验证，失败再直接解码 payload；
     * 都失败返回 null。调用方在 id_token 存在时硬停于此结果，不再降级。
     */
    private fun decodeUserInfoFromIdToken(idToken: String): HuaweiUserInfo? {
        logger.info("🔑 从 id_token 解码用户信息")
        // 先尝试签名验证（需要 JWKS 端点可用）
        verifyIdToken(idToken)?.let { return it }
        // JWKS 端点不可用时，直接解码 JWT payload（token 来自于可信的华为 token 端点）
        logger.info("⚠️ JWKS 验证失败，尝试直接解码 id_token payload")
        decodeIdTokenPayload(idToken)?.let { return it }
        logger.error("❌ id_token 解码也失败")
        return null
    }

    /**
     * 第 3 步（降级）：用 access_token 调 userinfo 端点解析用户信息。
     */
    private fun fetchUserInfoFromUserinfoEndpoint(accessToken: String): HuaweiUserInfo? {
        val userInfoRequest = Request.Builder()
            .url(HUAWEI_USERINFO_URL)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        val userInfoResponse = httpClient.newCall(userInfoRequest).execute()
        val userInfoBody = userInfoResponse.body?.string()
        if (userInfoBody == null) {
            logger.error("❌ 华为 userinfo 端点返回空响应, HTTP status=${userInfoResponse.code}")
            return null
        }
        if (!userInfoBody.trimStart().startsWith("{")) {
            logger.error("❌ 华为 userinfo 端点返回非 JSON 内容, HTTP ${userInfoResponse.code}, 响应(前500字): {}", userInfoBody.take(500))
            return null
        }
        val userInfo = json.parseToJsonElement(userInfoBody).jsonObject
        val openId = userInfo["sub"]?.jsonPrimitive?.content
            ?: userInfo["openID"]?.jsonPrimitive?.content
            ?: userInfo["open_id"]?.jsonPrimitive?.content
            ?: return null

        return HuaweiUserInfo(
            openId = openId,
            name = userInfo["name"]?.jsonPrimitive?.content ?: "",
            avatar = userInfo["picture"]?.jsonPrimitive?.content ?: ""
        )
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
            logger.warn("⚠️ 华为 ID Token 签名验证失败: {}", e.message)
            null
        } catch (e: Exception) {
            logger.warn("⚠️ 华为 ID Token 处理异常: {}", e.message)
            null
        }
    }

    /**
     * 不验证签名，仅解码 id_token 的 payload（用于 Web OAuth 流程）
     *
     * 在 Web OAuth 中，id_token 直接从华为 token 端点获取（使用有效 client_secret），
     * 通信是 HTTPS 服务器到服务器，因此 JWT 本身是可信的，不需要额外验证签名。
     * 当华为 JWKS 端点不可用时使用此方法作为降级。
     */
    private fun decodeIdTokenPayload(idToken: String): HuaweiUserInfo? {
        return try {
            val parts = idToken.split(".")
            if (parts.size != 3) {
                logger.warn("⚠️ ID Token 格式错误")
                return null
            }
            // 解码 payload（第二部分）
            val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]))
            val payload = json.parseToJsonElement(payloadJson).jsonObject
            val openId = payload["sub"]?.jsonPrimitive?.content ?: return null
            val name = payload["name"]?.jsonPrimitive?.content ?: ""
            val avatar = payload["picture"]?.jsonPrimitive?.content ?: ""
            logger.info("✅ id_token 解码成功: openId={}, name={}", openId.take(12), name)
            HuaweiUserInfo(openId = openId, name = name, avatar = avatar)
        } catch (e: Exception) {
            logger.error("❌ id_token payload 解码失败: {}", e.message)
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

            val parsed = keys.mapNotNull { parseRsaJwk(it.jsonObject) }.toMap().toMutableMap()
            cachedJwkSet = parsed
            cacheTimestamp = System.currentTimeMillis()
            logger.info("✅ 成功拉取华为 JWKS，共 {} 个公钥", parsed.size)
        } catch (e: Exception) {
            logger.error("❌ 拉取华为 JWKS 失败: {}", e.message)
        }
    }

    /**
     * 解析单个 JWK 为 (kid, PublicKey)，无法解析（缺字段/非RSA/异常）时返回 null。
     * 与原循环体逐项 continue/try-catch 跳过的语义等价。
     */
    private fun parseRsaJwk(keyObj: JsonObject): Pair<String, PublicKey>? {
        val kid = keyObj["kid"]?.jsonPrimitive?.content ?: return null
        if (keyObj["kty"]?.jsonPrimitive?.content != "RSA") return null
        val n = keyObj["n"]?.jsonPrimitive?.content ?: return null
        val e = keyObj["e"]?.jsonPrimitive?.content ?: return null
        return try {
            val modulus = BigInteger(1, Base64.getUrlDecoder().decode(n))
            val exponent = BigInteger(1, Base64.getUrlDecoder().decode(e))
            kid to KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        } catch (ex: Exception) {
            logger.debug("⚠️ 跳过 RSA key {}: {}", kid, ex.message)
            null
        }
    }
}
