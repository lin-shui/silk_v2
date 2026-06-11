package com.silk.web

import androidx.compose.runtime.Composable
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
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
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
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Style
import org.jetbrains.compose.web.dom.Text

/**
 * 昵称设置页面
 * 首次华为登录后显示，让用户设置/确认显示昵称
 */
@Composable
fun NicknameSetupScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()

    val defaultName = appState.currentUser?.fullName ?: ""
    var nickname by remember { mutableStateOf(defaultName) }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

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
            // 标题
            Span({
                style {
                    fontSize(28.px)
                    property("font-weight", "600")
                    color(Color(SilkColors.primary))
                    display(DisplayStyle.Block)
                    marginBottom(8.px)
                    fontFamily("'Cormorant Garamond'", "Georgia", "serif")
                }
            }) {
                Text("设置昵称")
            }

            // 说明文字
            Div({
                style {
                    marginBottom(32.px)
                    fontSize(13.px)
                    color(Color(SilkColors.textLight))
                    property("line-height", "1.6")
                }
            }) {
                Text("请设置您的显示昵称，好友将看到此名称")
            }

            // 昵称输入框
            Div({
                style {
                    marginBottom(16.px)
                    textAlign("left")
                }
            }) {
                Div({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textLight))
                        marginBottom(6.px)
                    }
                }) {
                    Text("昵称")
                }

                Input(InputType.Text) {
                    style {
                        width(100.percent)
                        padding(12.px, 14.px)
                        fontSize(15.px)
                        color(Color(SilkColors.textPrimary))
                        backgroundColor(Color(SilkColors.surface))
                        border(1.px, LineStyle.Solid, Color(if (errorMessage.isNotEmpty()) SilkColors.error else SilkColors.border))
                        borderRadius(8.px)
                        property("outline", "none")
                        property("box-sizing", "border-box")
                        property("transition", "border-color 0.2s ease")
                    }
                    value(nickname)
                    onInput { e ->
                        nickname = e.value ?: ""
                        if (errorMessage.isNotEmpty()) errorMessage = ""
                    }
                }
            }

            // 错误提示
            if (errorMessage.isNotEmpty()) {
                Div({
                    style {
                        color(Color(SilkColors.error))
                        fontSize(13.px)
                        marginBottom(16.px)
                        padding(10.px)
                        backgroundColor(Color("#FDF5F5"))
                        borderRadius(8.px)
                        property("border", "1px solid ${SilkColors.error}")
                        textAlign("center")
                    }
                }) {
                    Text(errorMessage)
                }
            }

            // 保存按钮
            Button({
                style {
                    width(100.percent)
                    padding(14.px)
                    property("background", SilkColors.primary)
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    fontSize(16.px)
                    property("font-weight", "500")
                    property("cursor", if (isLoading) "not-allowed" else "pointer")
                    property("opacity", if (isLoading) "0.7" else "1")
                    property("transition", "all 0.2s ease")
                    fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                }
                onClick {
                    if (isLoading) return@onClick
                    val trimmed = nickname.trim()
                    if (trimmed.isBlank()) {
                        errorMessage = "昵称不能为空"
                        return@onClick
                    }
                    if (trimmed.length > 50) {
                        errorMessage = "昵称不能超过50个字符"
                        return@onClick
                    }

                    isLoading = true
                    errorMessage = ""
                    val userId = appState.currentUser?.id ?: return@onClick

                    scope.launch {
                        val result = ApiClient.updateUserProfile(userId, trimmed)
                        if (result.success) {
                            appState.navigateTo(Scene.GROUP_LIST)
                        } else {
                            errorMessage = result.message.ifBlank { "保存失败，请重试" }
                            isLoading = false
                        }
                    }
                }
            }) {
                Text(if (isLoading) "保存中..." else "保存")
            }

            // 跳过链接
            Div({
                style {
                    marginTop(16.px)
                    fontSize(13.px)
                    color(Color(SilkColors.textLight))
                    property("cursor", "pointer")
                    property("text-decoration", "underline")
                }
                onClick {
                    appState.navigateTo(Scene.GROUP_LIST)
                }
            }) {
                Text("稍后设置")
            }
        }
    }
}
