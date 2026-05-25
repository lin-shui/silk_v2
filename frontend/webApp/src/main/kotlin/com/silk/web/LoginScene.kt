package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
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
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Style
import org.jetbrains.compose.web.dom.Text

private const val DEFAULT_AUTH_ERROR_PREFIX = "操作失败: "

@Composable
private fun AuthField(
    label: String,
    type: InputType<*>,
    value: String,
    placeholder: String,
    isLoading: Boolean,
    onValueChange: (String) -> Unit,
) {
    Div({ style { marginBottom(20.px) } }) {
        Label {
            Span({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    property("letter-spacing", "0.5px")
                }
            }) {
                Text(label)
            }
        }
        Input(type) {
            this.value(value)
            onInput { onValueChange(it.value?.toString().orEmpty()) }
            style {
                width(100.percent)
                padding(14.px)
                fontSize(14.px)
                marginTop(8.px)
                border {
                    width(1.px)
                    style(LineStyle.Solid)
                    color(Color(SilkColors.border))
                }
                borderRadius(8.px)
                property("box-sizing", "border-box")
                property("background", SilkColors.surface)
                property("color", SilkColors.textPrimary)
                property("transition", "all 0.2s ease")
                fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
            }
            if (!isLoading) {
                attr("placeholder", placeholder)
            }
        }
    }
}

@Composable
private fun RegistrationFields(
    fullName: String,
    phoneNumber: String,
    isLoading: Boolean,
    onFullNameChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
) {
    AuthField(
        label = "姓名",
        type = InputType.Text,
        value = fullName,
        placeholder = "请输入姓名",
        isLoading = isLoading,
        onValueChange = onFullNameChange,
    )
    AuthField(
        label = "手机号",
        type = InputType.Text,
        value = phoneNumber,
        placeholder = "请输入手机号",
        isLoading = isLoading,
        onValueChange = onPhoneNumberChange,
    )
}

@Composable
private fun AuthErrorMessage(message: String) {
    Div({
        style {
            color(Color(SilkColors.error))
            fontSize(13.px)
            marginBottom(20.px)
            textAlign("center")
            padding(12.px)
            backgroundColor(Color("#FDF5F5"))
            borderRadius(8.px)
            property("border", "1px solid ${SilkColors.error}")
        }
    }) {
        Text(message)
    }
}

private suspend fun submitAuth(
    appState: WebAppState,
    isLogin: Boolean,
    loginName: String,
    password: String,
    fullName: String,
    phoneNumber: String,
    setErrorMessage: (String) -> Unit,
) {
    recoverSuspendNonCancellation(
        block = {
            val response = if (isLogin) {
                ApiClient.login(loginName, password)
            } else {
                ApiClient.register(loginName, fullName, phoneNumber, password)
            }

            if (response.success && response.user != null) {
                console.log("${if (isLogin) "登录" else "注册"}成功:", response.user.fullName)
                appState.setUser(response.user)
            } else {
                setErrorMessage(response.message)
            }
        },
        recover = { error ->
            setErrorMessage(DEFAULT_AUTH_ERROR_PREFIX + error.message)
        },
    )
}

private fun authSubmitButtonText(isLoading: Boolean, isLogin: Boolean): String {
    return when {
        isLoading -> "处理中..."
        isLogin -> "登 录"
        else -> "注 册"
    }
}

@Composable
fun LoginScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    
    // 检查是否是意外到达登录页（用户没有明确退出登录）
    // 如果是，自动恢复到群组列表页面
    LaunchedEffect(Unit) {
        console.log("🔍 [LoginScene] 检查是否需要恢复会话...")
        val restored = appState.checkAndRestoreSession()
        if (restored) {
            console.log("✅ [LoginScene] 会话已恢复，跳转到群组列表")
        }
    }
    
    var isLogin by remember { mutableStateOf(true) }
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
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
            }
        }) {
            // Logo
            Div({
                style {
                    textAlign("center")
                    marginBottom(12.px)
                }
            }) {
                Span({
                    style {
                        fontSize(42.px)
                        property("font-weight", "700")
                        color(Color(SilkColors.primary))
                        property("letter-spacing", "6px")
                        fontFamily("'Cormorant Garamond'", "Georgia", "serif")
                        property("text-transform", "uppercase")
                    }
                }) {
                    Text("Silk")
                }
            }
            
            // 副标题
            Div({
                style {
                    textAlign("center")
                    marginBottom(36.px)
                    fontSize(13.px)
                    color(Color(SilkColors.textLight))
                    property("letter-spacing", "3px")
                    property("font-style", "italic")
                }
            }) {
                Text("smooth & simple")
            }
            
            // 标题
            H2({
                style {
                    textAlign("center")
                    color(Color(SilkColors.textPrimary))
                    marginBottom(32.px)
                    fontSize(22.px)
                    property("font-weight", "600")
                    property("letter-spacing", "1px")
                }
            }) {
                Text(if (isLogin) "欢迎回来" else "创建账号")
            }

            AuthField(
                label = "登录名",
                type = InputType.Text,
                value = loginName,
                placeholder = "请输入登录名",
                isLoading = isLoading,
                onValueChange = { loginName = it },
            )
            AuthField(
                label = "密码",
                type = InputType.Password,
                value = password,
                placeholder = "请输入密码",
                isLoading = isLoading,
                onValueChange = { password = it },
            )

            if (!isLogin) {
                RegistrationFields(
                    fullName = fullName,
                    phoneNumber = phoneNumber,
                    isLoading = isLoading,
                    onFullNameChange = { fullName = it },
                    onPhoneNumberChange = { phoneNumber = it },
                )
            }

            if (errorMessage.isNotEmpty()) {
                AuthErrorMessage(errorMessage)
            }

            Button({
                style {
                    width(100.percent)
                    padding(14.px)
                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    fontSize(15.px)
                    property("font-weight", "600")
                    property("letter-spacing", "2px")
                    property("cursor", if (isLoading) "not-allowed" else "pointer")
                    property("opacity", if (isLoading) "0.7" else "1")
                    property("box-shadow", "0 4px 16px rgba(169, 137, 77, 0.3)")
                    property("transition", "all 0.2s ease")
                    fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                }
                onClick {
                    if (!isLoading) {
                        scope.launch {
                            isLoading = true
                            errorMessage = ""
                            submitAuth(
                                appState = appState,
                                isLogin = isLogin,
                                loginName = loginName,
                                password = password,
                                fullName = fullName,
                                phoneNumber = phoneNumber,
                                setErrorMessage = { errorMessage = it },
                            )
                            isLoading = false
                        }
                    }
                }
            }) {
                Text(authSubmitButtonText(isLoading, isLogin))
            }

            Div({
                style {
                    textAlign("center")
                    marginTop(24.px)
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    property("cursor", "pointer")
                    property("letter-spacing", "0.5px")
                    property("transition", "color 0.2s ease")
                }
                onClick {
                    if (!isLoading) {
                        isLogin = !isLogin
                        errorMessage = ""
                    }
                }
            }) {
                Text(if (isLogin) "没有账号？点击注册" else "已有账号？点击登录")
            }
        }
    }
}
