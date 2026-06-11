package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silk.shared.i18n.getStrings
import com.silk.shared.models.Language
import com.silk.shared.models.UserSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.background
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flex
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.gap
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginLeft
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxWidth
import org.jetbrains.compose.web.css.minHeight
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.paddingLeft
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.textAlign
import org.jetbrains.compose.web.css.vh
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

@Composable
fun SettingsScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    val user = appState.currentUser ?: return

    var settings by remember { mutableStateOf<UserSettings?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // Local state for editing
    var selectedLanguage by remember { mutableStateOf<Language>(Language.CHINESE) }
    var defaultInstruction by remember { mutableStateOf("") }

    // CC settings state
    var ccBridgeToken by remember { mutableStateOf<String?>(null) }
    var ccBridgeConnected by remember { mutableStateOf(false) }
    var ccBridgeIp by remember { mutableStateOf<String?>(null) }
    var ccTokenVisible by remember { mutableStateOf(false) }
    var ccIsGenerating by remember { mutableStateOf(false) }
    var ccIsTesting by remember { mutableStateOf(false) }
    var ccTestResult by remember { mutableStateOf<String?>(null) }
    var ccTestGeneration by remember { mutableStateOf(0) }
    
    // Load settings on mount
    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            try {
                val response = ApiClient.getUserSettings(user.id)
                console.log("Settings response:", response)
                if (response.success && response.settings != null) {
                    val loadedSettings = response.settings!!
                    console.log("Loaded language:", loadedSettings.language)
                    settings = loadedSettings
                    selectedLanguage = loadedSettings.language
                    defaultInstruction = loadedSettings.defaultAgentInstruction
                } else {
                    // Use defaults
                    console.log("No settings found, using default CHINESE")
                    selectedLanguage = Language.CHINESE
                    defaultInstruction = "You are a helpful technical research assistant. "
                }
                // Load CC settings
                val ccResponse = ApiClient.getCcSettings(user.id)
                if (ccResponse.success) {
                    ccBridgeToken = ccResponse.ccBridgeToken
                    ccBridgeConnected = ccResponse.bridgeConnected
                    ccBridgeIp = ccResponse.bridgeIp
                }
            } catch (e: Exception) {
                console.error("加载设置失败:", e)
                // Use defaults on error
                selectedLanguage = Language.CHINESE
                defaultInstruction = "You are a helpful technical research assistant. "
            } finally {
                isLoading = false
            }
        }
    }
    
    // Get strings based on selected language
    val strings = getStrings(selectedLanguage)
    
    // Get English strings for the language dropdown (to show bilingual names)
    val englishStrings = getStrings(Language.ENGLISH)
    val chineseStrings = getStrings(Language.CHINESE)
    
    Div({
        style {
            minHeight(100.vh)
            background("linear-gradient(135deg, ${SilkColors.background} 0%, ${SilkColors.surfaceElevated} 100%)")
            padding(0.px)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
        }
    }) {
        // Header
        Div({
            style {
                background("linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                color(Color.white)
                padding(16.px, 24.px)
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.SpaceBetween)
                property("box-shadow", "0 2px 12px rgba(169, 137, 77, 0.2)")
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    gap(16.px)
                }
            }) {
                Button({
                    style {
                        backgroundColor(Color.transparent)
                        color(Color.white)
                        border { style(LineStyle.None) }
                        padding(8.px, 12.px)
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                    }
                    onClick { appState.navigateBack() }
                }) {
                    Text("← ${strings.backButton}")
                }
                
                Span({
                    style {
                        color(Color.white)
                        fontSize(20.px)
                        property("font-weight", "600")
                        property("letter-spacing", "1px")
                    }
                }) {
                    Text(strings.settingsTitle)
                }
            }
        }
        
        // Content
        Div({
            style {
                flex(1)
                padding(32.px)
                maxWidth(800.px)
                width(100.percent)
                property("margin", "0 auto")
            }
        }) {
            if (isLoading) {
                Div({
                    style {
                        textAlign("center")
                        padding(60.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text("加载中...")
                }
            } else {
                // Language selector
                Div({ style { marginBottom(32.px) } }) {
                    Label {
                        Span({
                            style {
                                display(DisplayStyle.Block)
                                marginBottom(12.px)
                                color(Color(SilkColors.textPrimary))
                                fontSize(14.px)
                                property("font-weight", "600")
                            }
                        }) {
                            Text(strings.languageLabel)
                        }
                    }
                    
                    Select({
                        id("language-select")
                        style {
                            width(100.percent)
                            padding(12.px)
                            fontSize(14.px)
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color(SilkColors.border))
                            }
                            borderRadius(8.px)
                            backgroundColor(Color(SilkColors.surfaceElevated))
                            property("box-sizing", "border-box")
                        }
                        attr("value", selectedLanguage.name)
                        onChange { event ->
                            val newValue = event.value ?: return@onChange
                            selectedLanguage = Language.valueOf(newValue)
                        }
                    }) {
                        Option(Language.ENGLISH.name) {
                            Text("${englishStrings.languageEnglish} - ${englishStrings.languageEnglishNative}")
                        }
                        Option(Language.CHINESE.name) {
                            Text("${englishStrings.languageChinese} - ${chineseStrings.languageChineseNative}")
                        }
                    }
                    
                    // Use LaunchedEffect to manually set the select value when selectedLanguage changes or after settings load
                    LaunchedEffect(selectedLanguage, isLoading) {
                        if (!isLoading) {
                            // Wait for DOM to be ready
                            kotlinx.coroutines.delay(50)
                            val select = document.getElementById("language-select")
                            if (select != null) {
                                select.asDynamic().value = selectedLanguage.name
                                console.log("Set select value to:", selectedLanguage.name)
                            }
                        }
                    }
                }
                
                // Default agent instruction
                Div({ style { marginBottom(32.px) } }) {
                    Label {
                        Span({
                            style {
                                display(DisplayStyle.Block)
                                marginBottom(12.px)
                                color(Color(SilkColors.textPrimary))
                                fontSize(14.px)
                                property("font-weight", "600")
                            }
                        }) {
                            Text(strings.defaultAgentInstructionLabel)
                        }
                    }

                    TextArea {
                        style {
                            width(100.percent)
                            padding(12.px)
                            fontSize(14.px)
                            minHeight(120.px)
                            property("border", "1px solid ${SilkColors.border}")
                            borderRadius(8.px)
                            backgroundColor(Color(SilkColors.surfaceElevated))
                            property("box-sizing", "border-box")
                            property("resize", "vertical")
                            fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
                        }
                        value(defaultInstruction)
                        onInput { event -> defaultInstruction = event.value }
                    }
                }

                // Claude Code settings section
                Div({
                    style {
                        marginBottom(32.px)
                        padding(20.px)
                        border {
                            width(1.px)
                            style(LineStyle.Solid)
                            color(Color(SilkColors.border))
                        }
                        borderRadius(12.px)
                        backgroundColor(Color(SilkColors.surfaceElevated))
                    }
                }) {
                    // Section title
                    Span({
                        style {
                            display(DisplayStyle.Block)
                            marginBottom(16.px)
                            color(Color(SilkColors.textPrimary))
                            fontSize(16.px)
                            property("font-weight", "600")
                        }
                    }) {
                        Text(strings.ccSettingsTitle)
                    }

                    if (ccBridgeToken == null) {
                        // No token generated yet
                        Div({ style { marginBottom(12.px) } }) {
                            Span({
                                style {
                                    color(Color(SilkColors.textSecondary))
                                    fontSize(13.px)
                                }
                            }) {
                                Text(strings.ccBridgeNotConfigured)
                            }
                        }
                        Button({
                            style {
                                padding(10.px, 20.px)
                                background("linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                                color(Color.white)
                                border { width(0.px) }
                                borderRadius(8.px)
                                property("cursor", if (ccIsGenerating) "not-allowed" else "pointer")
                                property("opacity", if (ccIsGenerating) "0.6" else "1")
                                fontSize(14.px)
                                property("font-weight", "500")
                            }
                            onClick {
                                if (!ccIsGenerating) {
                                    scope.launch {
                                        ccIsGenerating = true
                                        try {
                                            val resp = ApiClient.generateBridgeToken(user.id)
                                            if (resp.success) {
                                                ccBridgeToken = resp.ccBridgeToken
                                                ccBridgeConnected = resp.bridgeConnected
                                            }
                                        } finally {
                                            ccIsGenerating = false
                                        }
                                    }
                                }
                            }
                        }) {
                            Text(if (ccIsGenerating) "..." else strings.ccGenerateToken)
                        }
                    } else {
                        // Token exists — show token + status
                        // Bridge status indicator
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                gap(8.px)
                                marginBottom(if (ccBridgeConnected && ccBridgeIp != null) 8.px else 16.px)
                            }
                        }) {
                            // Status dot
                            Span({
                                style {
                                    width(10.px)
                                    height(10.px)
                                    borderRadius(50.percent)
                                    backgroundColor(if (ccBridgeConnected) Color(SilkColors.success) else Color(SilkColors.textLight))
                                    display(DisplayStyle.InlineBlock)
                                }
                            }) {}
                            Span({
                                style {
                                    fontSize(14.px)
                                    color(if (ccBridgeConnected) Color(SilkColors.success) else Color(SilkColors.textSecondary))
                                    property("font-weight", "500")
                                }
                            }) {
                                Text(if (ccBridgeConnected) strings.ccBridgeConnected else strings.ccBridgeDisconnected)
                            }
                        }

                        // Bridge IP (when connected)
                        if (ccBridgeConnected && ccBridgeIp != null) {
                            Div({
                                style {
                                    marginBottom(16.px)
                                    paddingLeft(18.px)
                                    fontSize(13.px)
                                    color(Color(SilkColors.textSecondary))
                                }
                            }) {
                                Span({ style { property("font-weight", "500") } }) {
                                    Text(strings.ccBridgeIpLabel)
                                }
                                Span({
                                    style {
                                        fontFamily("monospace")
                                        color(Color(SilkColors.textPrimary))
                                        marginLeft(4.px)
                                    }
                                }) {
                                    Text(ccBridgeIp!!)
                                }
                            }
                        }

                        // Refresh status button
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                gap(8.px)
                                marginBottom(16.px)
                            }
                        }) {
                            Button({
                                style {
                                    padding(6.px, 14.px)
                                    backgroundColor(Color(SilkColors.secondary))
                                    color(Color(SilkColors.textPrimary))
                                    border {
                                        width(1.px)
                                        style(LineStyle.Solid)
                                        color(Color(SilkColors.border))
                                    }
                                    borderRadius(6.px)
                                    property("cursor", if (ccIsTesting) "not-allowed" else "pointer")
                                    property("opacity", if (ccIsTesting) "0.6" else "1")
                                    fontSize(13.px)
                                }
                                onClick {
                                    if (!ccIsTesting) {
                                        scope.launch {
                                            ccIsTesting = true
                                            ccTestResult = null
                                            val gen = ++ccTestGeneration
                                            try {
                                                val resp = ApiClient.getBridgeStatus(user.id)
                                                ccBridgeConnected = resp.bridgeConnected
                                                ccBridgeIp = resp.bridgeIp
                                                ccTestResult = if (resp.bridgeConnected) strings.ccTestSuccess else strings.ccTestFailed
                                            } catch (e: Exception) {
                                                console.error("刷新 Bridge 状态失败:", e)
                                                ccTestResult = strings.ccTestFailed
                                            } finally {
                                                ccIsTesting = false
                                            }
                                            // 10 秒后自动清除结果（仅当没有更新的点击时）
                                            delay(10_000)
                                            if (ccTestGeneration == gen) {
                                                ccTestResult = null
                                            }
                                        }
                                    }
                                }
                            }) {
                                Text(if (ccIsTesting) strings.ccRefreshingStatus else strings.ccRefreshStatus)
                            }
                            // Result text
                            if (ccTestResult != null) {
                                Span({
                                    style {
                                        fontSize(13.px)
                                        property("font-weight", "500")
                                        color(if (ccTestResult == strings.ccTestSuccess) Color(SilkColors.success) else Color(SilkColors.error))
                                    }
                                }) {
                                    Text(ccTestResult!!)
                                }
                            }
                        }

                        // Token display
                        Div({ style { marginBottom(12.px) } }) {
                            Span({
                                style {
                                    display(DisplayStyle.Block)
                                    marginBottom(8.px)
                                    color(Color(SilkColors.textSecondary))
                                    fontSize(13.px)
                                }
                            }) {
                                Text(strings.ccBridgeTokenLabel)
                            }
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    gap(8.px)
                                }
                            }) {
                                // Token value (masked or visible)
                                Span({
                                    style {
                                        fontFamily("monospace")
                                        fontSize(13.px)
                                        padding(8.px, 12.px)
                                        backgroundColor(Color("#f5f5f5"))
                                        borderRadius(6.px)
                                        color(Color(SilkColors.textPrimary))
                                        property("cursor", "pointer")
                                        property("user-select", "all")
                                    }
                                    onClick { ccTokenVisible = !ccTokenVisible }
                                }) {
                                    Text(if (ccTokenVisible) (ccBridgeToken ?: "") else "••••••••••••••••")
                                }
                                // Copy button
                                Button({
                                    style {
                                        padding(6.px, 12.px)
                                        backgroundColor(Color(SilkColors.secondary))
                                        color(Color(SilkColors.textPrimary))
                                        border { width(0.px) }
                                        borderRadius(6.px)
                                        property("cursor", "pointer")
                                        fontSize(12.px)
                                    }
                                    onClick {
                                        ccBridgeToken?.let { token ->
                                            copyToClipboard(token)
                                            saveMessage = strings.ccTokenCopied
                                        }
                                    }
                                }) {
                                    Text(strings.ccCopyToken)
                                }
                            }
                        }

                        // Regenerate button
                        Div({ style { marginBottom(12.px) } }) {
                            Button({
                                style {
                                    padding(8.px, 16.px)
                                    backgroundColor(Color("#FFF3E0"))
                                    color(Color("#E65100"))
                                    border {
                                        width(1.px)
                                        style(LineStyle.Solid)
                                        color(Color("#FFB74D"))
                                    }
                                    borderRadius(6.px)
                                    property("cursor", if (ccIsGenerating) "not-allowed" else "pointer")
                                    property("opacity", if (ccIsGenerating) "0.6" else "1")
                                    fontSize(13.px)
                                }
                                onClick {
                                    if (!ccIsGenerating) {
                                        val confirmed = kotlinx.browser.window.confirm(strings.ccRegenerateConfirm)
                                        if (confirmed) {
                                            scope.launch {
                                                ccIsGenerating = true
                                                try {
                                                    val resp = ApiClient.generateBridgeToken(user.id)
                                                    if (resp.success) {
                                                        ccBridgeToken = resp.ccBridgeToken
                                                        ccBridgeConnected = resp.bridgeConnected
                                                        ccTokenVisible = true
                                                    }
                                                } finally {
                                                    ccIsGenerating = false
                                                }
                                            }
                                        }
                                    }
                                }
                            }) {
                                Text(if (ccIsGenerating) "..." else strings.ccRegenerateToken)
                            }
                        }

                        // Help text
                        Div({
                            style {
                                padding(12.px)
                                backgroundColor(Color("#F5F5F5"))
                                borderRadius(8.px)
                                fontSize(12.px)
                                color(Color(SilkColors.textSecondary))
                                fontFamily("monospace")
                                property("white-space", "pre-wrap")
                            }
                        }) {
                            Text(strings.ccBridgeHelp)
                        }
                    }
                }
                
                // Save message
                if (saveMessage != null) {
                    Div({
                        style {
                            padding(12.px, 16.px)
                            marginBottom(24.px)
                            borderRadius(8.px)
                            backgroundColor(
                                if (saveMessage?.contains("成功") == true || saveMessage?.contains("success") == true)
                                    Color("#E8F5E9")
                                else
                                    Color("#FFEBEE")
                            )
                            color(
                                if (saveMessage?.contains("成功") == true || saveMessage?.contains("success") == true)
                                    Color("#2E7D32")
                                else
                                    Color("#C62828")
                            )
                            fontSize(14.px)
                        }
                    }) {
                        Text(saveMessage ?: "")
                    }
                }
                
                // Buttons
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        gap(12.px)
                        justifyContent(JustifyContent.FlexEnd)
                    }
                }) {
                    Button({
                        style {
                            padding(12.px, 24.px)
                            backgroundColor(Color(SilkColors.secondary))
                            color(Color(SilkColors.textPrimary))
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", "pointer")
                            fontSize(14.px)
                            property("font-weight", "500")
                        }
                        onClick { appState.navigateBack() }
                    }) {
                        Text(strings.cancelButton)
                    }
                    
                    Button({
                        style {
                            padding(12.px, 24.px)
                            background("linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", if (isSaving) "not-allowed" else "pointer")
                            property("opacity", if (isSaving) "0.6" else "1")
                            fontSize(14.px)
                            property("font-weight", "600")
                        }
                        onClick {
                            if (!isSaving) {
                                scope.launch {
                                    isSaving = true
                                    saveMessage = null
                                    try {
                                        val response = ApiClient.updateUserSettings(
                                            userId = user.id,
                                            language = selectedLanguage,
                                            defaultAgentInstruction = defaultInstruction
                                        )
                                        if (response.success && response.settings != null) {
                                            val savedSettings = response.settings!!
                                            settings = savedSettings
                                            selectedLanguage = savedSettings.language
                                            defaultInstruction = savedSettings.defaultAgentInstruction
                                            saveMessage = strings.settingsSaved
                                        } else {
                                            saveMessage = strings.settingsSaveError
                                        }
                                    } catch (e: Exception) {
                                        console.error("保存设置失败:", e)
                                        saveMessage = strings.settingsSaveError
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        }
                    }) {
                        Text(if (isSaving) "保存中..." else strings.saveButton)
                    }
                }

                // 分隔线
                Div({
                    style {
                        height(1.px)
                        backgroundColor(Color(SilkColors.divider))
                        marginTop(48.px)
                        marginBottom(32.px)
                    }
                })

                // 注销账号
                var showDeleteConfirm by remember { mutableStateOf(false) }
                var isDeleting by remember { mutableStateOf(false) }
                var deleteMessage by remember { mutableStateOf<String?>(null) }

                Div({
                    style {
                        padding(24.px)
                        border(1.px, LineStyle.Solid, Color("#F5D0D0"))
                        borderRadius(12.px)
                        backgroundColor(Color("#FFF8F8"))
                    }
                }) {
                    Span({
                        style {
                            fontSize(16.px)
                            fontWeight("bold")
                            color(Color(SilkColors.error))
                            display(DisplayStyle.Block)
                            marginBottom(8.px)
                        }
                    }) {
                        Text("危险区域")
                    }

                    Div({
                        style {
                            fontSize(13.px)
                            color(Color(SilkColors.textSecondary))
                            marginBottom(16.px)
                            property("line-height", "1.6")
                        }
                    }) {
                        Text("注销账号将删除您的所有数据（包括群组、联系人、聊天记录等），且无法恢复。该操作不可撤销。")
                    }

                    if (!showDeleteConfirm) {
                        Button({
                            style {
                                padding(10.px, 20.px)
                                backgroundColor(Color(SilkColors.error))
                                color(Color.white)
                                border { width(0.px) }
                                borderRadius(8.px)
                                property("cursor", "pointer")
                                fontSize(13.px)
                                property("font-weight", "500")
                            }
                            onClick {
                                showDeleteConfirm = true
                                deleteMessage = null
                            }
                        }) {
                            Text("注销账号")
                        }
                    } else {
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                flexDirection(FlexDirection.Column)
                                gap(12.px)
                            }
                        }) {
                            Div({
                                style {
                                    fontSize(14.px)
                                    color(Color(SilkColors.error))
                                    fontWeight("bold")
                                }
                            }) {
                                Text("确认注销？此操作不可撤销！")
                            }

                            // 操作按钮
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    gap(12.px)
                                }
                            }) {
                                Button({
                                    style {
                                        padding(10.px, 20.px)
                                        backgroundColor(Color("#999"))
                                        color(Color.white)
                                        border { width(0.px) }
                                        borderRadius(8.px)
                                        property("cursor", "pointer")
                                        fontSize(13.px)
                                        property("font-weight", "500")
                                    }
                                    onClick {
                                        showDeleteConfirm = false
                                        deleteMessage = null
                                    }
                                }) {
                                    Text("取消")
                                }

                                Button({
                                    style {
                                        padding(10.px, 20.px)
                                        backgroundColor(Color(SilkColors.error))
                                        color(Color.white)
                                        border { width(0.px) }
                                        borderRadius(8.px)
                                        property("cursor", if (isDeleting) "not-allowed" else "pointer")
                                        property("opacity", if (isDeleting) "0.6" else "1")
                                        fontSize(13.px)
                                        property("font-weight", "500")
                                    }
                                    onClick {
                                        if (!isDeleting) {
                                            isDeleting = true
                                            deleteMessage = null
                                            appState.deleteAccount { success, msg ->
                                                deleteMessage = msg
                                                if (!success) {
                                                    isDeleting = false
                                                    showDeleteConfirm = false
                                                }
                                            }
                                        }
                                    }
                                }) {
                                    Text(if (isDeleting) "注销中..." else "确认注销")
                                }
                            }

                            if (deleteMessage != null) {
                                Div({
                                    style {
                                        fontSize(13.px)
                                        color(Color(if (deleteMessage == "账号已注销") SilkColors.success else SilkColors.error))
                                        marginTop(8.px)
                                    }
                                }) {
                                    Text(deleteMessage!!)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
