package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.textAlign
import org.jetbrains.compose.web.css.vh
import org.jetbrains.compose.web.css.vw
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Style
import org.jetbrains.compose.web.dom.Text

/**
 * 华为 OAuth 登录页面
 * 使用华为账号登录（无密码方案）
 */
@Composable
fun LoginScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    
    // 检查是否已有 JWT 会话（未主动登出）
    LaunchedEffect(Unit) {
        console.log("🔍 [LoginScene] 检查是否需要恢复会话...")
        val restored = appState.checkAndRestoreSession()
        if (restored) {
            console.log("✅ [LoginScene] 会话已恢复，跳转到群组列表")
        }
    }
    
    var errorMessage by remember { mutableStateOf(appState.loginError.also { appState.loginError = "" }) }
    var isLoading by remember { mutableStateOf(false) }
    
    // 设置全局样式，去掉浏览器滚动条
    Style {
        """
        html, body {
            height: 100%;
            margin: 0;
            padding: 0;
            overflow: hidden;
        }
        #root {
            height: 100%;
        }
        """.trimIndent()
    }
    
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            height(100.vh)
            width(100.vw)
            property("background", SilkColors.backgroundGradient)
            property("overflow", "auto")
        }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                padding(48.px, 40.px)
                borderRadius(16.px)
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.15)")
                width(420.px)
                maxWidth(90.vw)
                property("border", "1px solid ${SilkColors.border}")
                textAlign("center")
            }
        }) {
            // Logo
            Span({
                style {
                    fontSize(42.px)
                    property("font-weight", "700")
                    color(Color(SilkColors.primary))
                    property("letter-spacing", "6px")
                    fontFamily("'Cormorant Garamond'", "Georgia", "serif")
                    property("text-transform", "uppercase")
                    display(DisplayStyle.Block)
                    marginBottom(8.px)
                }
            }) {
                Text("Silk")
            }
            
            // 副标题
            Div({
                style {
                    marginBottom(48.px)
                    fontSize(13.px)
                    color(Color(SilkColors.textLight))
                    property("letter-spacing", "3px")
                    property("font-style", "italic")
                }
            }) {
                Text("smooth & simple")
            }
            
            // 错误提示
            if (errorMessage.isNotEmpty()) {
                Div({
                    style {
                        color(Color(SilkColors.error))
                        fontSize(13.px)
                        marginBottom(20.px)
                        padding(12.px)
                        backgroundColor(Color("#FDF5F5"))
                        borderRadius(8.px)
                        property("border", "1px solid ${SilkColors.error}")
                    }
                }) {
                    Text(errorMessage)
                }
            }
            
            // 微信登录暂时不可用（需微信开放平台企业认证），启用后可取消注释
            // Button(...) { Text("使用微信登录") }

            // 华为账号登录按钮
            Button({
                style {
                    width(100.percent)
                    padding(16.px)
                    property("background", "#CF0A2C")  // 华为红
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    fontSize(16.px)
                    property("font-weight", "500")
                    property("cursor", if (isLoading) "not-allowed" else "pointer")
                    property("opacity", if (isLoading) "0.7" else "1")
                    property("transition", "all 0.2s ease")
                    fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                    marginBottom(12.px)
                }
                onClick {
                    if (!isLoading) {
                        isLoading = true
                        errorMessage = ""
                        // 先检查 clientId 是否配置
                        val clientId = BuildConfig.HUAWEI_OAUTH_CLIENT_ID
                        if (clientId.isBlank()) {
                            console.error("❌ HUAWEI_OAUTH_CLIENT_ID 未配置")
                            errorMessage = "华为登录暂未配置，请联系管理员"
                            isLoading = false
                            return@onClick
                        }
                        startHuaweiOAuth()
                    }
                }
            }) {
                Text(if (isLoading) "跳转中..." else "使用华为帐号登录")
            }
            
            // 说明文字
            Div({
                style {
                    fontSize(12.px)
                    color(Color(SilkColors.textLight))
                    property("line-height", "1.6")
                    marginTop(4.px)
                }
            }) {
                Text("点击即表示同意使用对应平台帐号进行身份认证")
            }
        }
    }
}

/**
 * 启动微信 OAuth 授权流程
 * 重定向到微信扫码登录页面，用户使用微信扫码授权后回调到当前应用
 */
private fun startWeChatOAuth() {
    val appId = BuildConfig.WECHAT_APP_ID

    // 生成 state 用于 CSRF 防护
    val state = generateRandomState()
    try {
        kotlinx.browser.sessionStorage.setItem("wechat_oauth_state", state)
    } catch (_: Exception) {}

    // 当前页面 URL 作为 redirect_uri（OAuth 回调时重新加载页面）
    val redirectUri = window.location.href.split("?")[0].split("#")[0]

    // 构造微信 OAuth 授权 URL（微信开放平台扫码登录）
    val authUrl = buildString {
        append("https://open.weixin.qq.com/connect/qrconnect")
        append("?appid=").append(encodeURIComponent(appId))
        append("&redirect_uri=").append(encodeURIComponent(redirectUri))
        append("&response_type=code")
        append("&scope=snsapi_login")
        append("&state=").append(state)
        append("#wechat_redirect")
    }

    console.log("🔗 跳转到微信 OAuth: ", authUrl)
    window.location.href = authUrl
}

/**
 * 启动华为 OAuth 授权流程
 * 重定向到华为登录页面，用户授权后回调到当前应用
 */
private fun startHuaweiOAuth() {
    val clientId = BuildConfig.HUAWEI_OAUTH_CLIENT_ID
    
    // 生成 state 用于 CSRF 防护
    val state = generateRandomState()
    try {
        kotlinx.browser.sessionStorage.setItem("huawei_oauth_state", state)
    } catch (_: Exception) {}
    
    // 当前页面 URL 作为 redirect_uri（OAuth 回调时重新加载页面）
    val redirectUri = window.location.href.split("?")[0].split("#")[0]
    
    // 构造华为 OAuth 授权 URL
    val authUrl = buildString {
        append("https://oauth-login.cloud.huawei.com/oauth2/v3/authorize")
        append("?client_id=").append(encodeURIComponent(clientId))
        append("&response_type=code")
        append("&redirect_uri=").append(encodeURIComponent(redirectUri))
        append("&scope=openid+profile")
        append("&state=").append(state)
    }
    
    console.log("🔗 跳转到华为 OAuth: ", authUrl)
    window.location.href = authUrl
}

/**
 * 对字符串进行 URL 编码
 */
private fun encodeURIComponent(s: String): String {
    return js("encodeURIComponent")(s).unsafeCast<String>()
}

/**
 * 生成随机 state 字符串（用于 CSRF 防护）
 */
private fun generateRandomState(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..32).map { chars[kotlin.random.Random.nextInt(chars.length)] }.joinToString("")
}

/**
 * 处理 OAuth 回调（同时支持微信和华为）
 * 检测 URL 中是否有 code 参数，区分是微信还是华为的回调
 * 在主页面渲染前调用
 */
suspend fun handleOAuthCallback(appState: WebAppState): Boolean {
    val params = window.location.search
    if (params.isBlank()) return false
    
    val urlParams = params.drop(1).split("&").mapNotNull { param ->
        val parts = param.split("=", limit = 2)
        if (parts.size == 2) parts[0] to decodeURIComponent(parts[1])
        else null
    }.toMap()
    
    val code = urlParams["code"] ?: return false
    val state = urlParams["state"] ?: ""
    
    // 判断 OAuth 来源：优先检查微信 state，再检查华为 state
    val isWeChat = try {
        val savedState = kotlinx.browser.sessionStorage.getItem("wechat_oauth_state")
        savedState != null
    } catch (_: Exception) { false }

    val oauthType = if (isWeChat) "wechat" else "huawei"
    val stateKey = "${oauthType}_oauth_state"

    // 验证 state（CSRF 防护）
    val savedState = try {
        kotlinx.browser.sessionStorage.getItem(stateKey)
    } catch (_: Exception) { null }
    
    if (savedState != null && state != savedState) {
        console.error("❌ OAuth state 不匹配，可能的 CSRF 攻击")
        return false
    }
    
    // 清除 state
    try {
        kotlinx.browser.sessionStorage.removeItem(stateKey)
    } catch (_: Exception) {}
    
    val redirectUri = window.location.href.split("?")[0].split("#")[0]
    
    console.log("🔑 收到 $oauthType OAuth code，正在登录...")
    
    if (isWeChat) {
        val result = ApiClient.wechatLogin(code)
        if (result.success && result.user != null && result.accessToken != null && result.refreshToken != null) {
            console.log("✅ 微信登录成功:", result.user.fullName, "isNewUser=", result.isNewUser)
            window.history.replaceState(null, "", redirectUri)
            appState.setSession(result.user, result.accessToken!!, result.refreshToken!!, result.isNewUser)
            return true
        } else {
            console.error("❌ 微信登录失败:", result.message)
            return false
        }
    } else {
        val result = ApiClient.huaweiWebLogin(code, redirectUri)
        if (result.success && result.user != null && result.accessToken != null && result.refreshToken != null) {
            console.log("✅ 华为登录成功:", result.user.fullName, "isNewUser=", result.isNewUser)
            window.history.replaceState(null, "", redirectUri)
            appState.setSession(result.user, result.accessToken!!, result.refreshToken!!, result.isNewUser)
            return true
        } else {
            console.error("❌ 华为登录失败:", result.message)
            appState.loginError = "华为登录失败: ${result.message}"
            return false
        }
    }
}

private fun decodeURIComponent(s: String): String {
    return try {
        js("decodeURIComponent")(s).unsafeCast<String>()
    } catch (_: Exception) { s }
}
