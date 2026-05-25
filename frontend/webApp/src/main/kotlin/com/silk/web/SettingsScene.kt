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
import kotlinx.coroutines.CoroutineScope
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
import org.jetbrains.compose.web.css.gap
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginLeft
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

private const val LANGUAGE_SELECT_ID = "language-select"
private const val DEFAULT_AGENT_INSTRUCTION = "You are a helpful technical research assistant. "

private fun applyDefaultSettings(
    onLanguageChange: (Language) -> Unit,
    onDefaultInstructionChange: (String) -> Unit,
) {
    onLanguageChange(Language.CHINESE)
    onDefaultInstructionChange(DEFAULT_AGENT_INSTRUCTION)
}

private suspend fun loadSettingsSceneData(
    userId: String,
    onLanguageChange: (Language) -> Unit,
    onDefaultInstructionChange: (String) -> Unit,
    onCcSettingsLoaded: (token: String?, connected: Boolean, ip: String?) -> Unit,
) {
    recoverSuspendNonCancellation(
        block = {
            val response = ApiClient.getUserSettings(userId)
            console.log("Settings response:", response)
            if (response.success && response.settings != null) {
                val loadedSettings = response.settings!!
                console.log("Loaded language:", loadedSettings.language)
                onLanguageChange(loadedSettings.language)
                onDefaultInstructionChange(loadedSettings.defaultAgentInstruction)
            } else {
                console.log("No settings found, using default CHINESE")
                applyDefaultSettings(onLanguageChange, onDefaultInstructionChange)
            }

            val ccResponse = ApiClient.getCcSettings(userId)
            if (ccResponse.success) {
                onCcSettingsLoaded(ccResponse.ccBridgeToken, ccResponse.bridgeConnected, ccResponse.bridgeIp)
            }
        },
        recover = { error ->
            console.error("加载设置失败:", error)
            applyDefaultSettings(onLanguageChange, onDefaultInstructionChange)
        },
    )
}

private fun isSuccessSaveMessage(message: String): Boolean {
    return message.contains("成功") || message.contains("success")
}

@Composable
private fun SettingsHeader(strings: com.silk.shared.i18n.Strings, onBack: () -> Unit) {
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
                onClick { onBack() }
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
}

@Composable
private fun SettingsLoadingState() {
    Div({
        style {
            textAlign("center")
            padding(60.px)
            color(Color(SilkColors.textSecondary))
        }
    }) {
        Text("加载中...")
    }
}

@Composable
private fun SettingsFieldLabel(label: String) {
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
            Text(label)
        }
    }
}

@Composable
private fun LanguageSection(
    selectedLanguage: Language,
    isLoading: Boolean,
    strings: com.silk.shared.i18n.Strings,
    englishStrings: com.silk.shared.i18n.Strings,
    chineseStrings: com.silk.shared.i18n.Strings,
    onLanguageChange: (Language) -> Unit,
) {
    Div({ style { marginBottom(32.px) } }) {
        SettingsFieldLabel(strings.languageLabel)

        Select({
            id(LANGUAGE_SELECT_ID)
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
                onLanguageChange(Language.valueOf(newValue))
            }
        }) {
            Option(Language.ENGLISH.name) {
                Text("${englishStrings.languageEnglish} - ${englishStrings.languageEnglishNative}")
            }
            Option(Language.CHINESE.name) {
                Text("${englishStrings.languageChinese} - ${chineseStrings.languageChineseNative}")
            }
        }

        LaunchedEffect(selectedLanguage, isLoading) {
            if (!isLoading) {
                delay(50)
                val select = document.getElementById(LANGUAGE_SELECT_ID)
                if (select != null) {
                    select.asDynamic().value = selectedLanguage.name
                    console.log("Set select value to:", selectedLanguage.name)
                }
            }
        }
    }
}

@Composable
private fun DefaultInstructionSection(
    label: String,
    defaultInstruction: String,
    onDefaultInstructionChange: (String) -> Unit,
) {
    Div({ style { marginBottom(32.px) } }) {
        SettingsFieldLabel(label)

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
            onInput { event -> onDefaultInstructionChange(event.value) }
        }
    }
}

@Composable
private fun CcSettingsSection(
    scope: CoroutineScope,
    userId: String,
    strings: com.silk.shared.i18n.Strings,
    ccBridgeToken: String?,
    ccBridgeConnected: Boolean,
    ccBridgeIp: String?,
    ccTokenVisible: Boolean,
    ccIsGenerating: Boolean,
    ccIsTesting: Boolean,
    ccTestResult: String?,
    ccTestGeneration: Int,
    currentTestGeneration: () -> Int,
    onBridgeTokenChange: (String?) -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onBridgeIpChange: (String?) -> Unit,
    onTokenVisibleChange: (Boolean) -> Unit,
    onGeneratingChange: (Boolean) -> Unit,
    onTestingChange: (Boolean) -> Unit,
    onTestResultChange: (String?) -> Unit,
    onTestGenerationChange: (Int) -> Unit,
    onSaveMessageChange: (String?) -> Unit,
) {
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
            CcBridgeUnconfiguredState(
                scope = scope,
                userId = userId,
                strings = strings,
                ccIsGenerating = ccIsGenerating,
                onGeneratingChange = onGeneratingChange,
                onBridgeTokenChange = onBridgeTokenChange,
                onBridgeConnectedChange = onBridgeConnectedChange,
            )
        } else {
            CcBridgeConfiguredState(
                scope = scope,
                userId = userId,
                strings = strings,
                ccBridgeToken = ccBridgeToken,
                ccBridgeConnected = ccBridgeConnected,
                ccBridgeIp = ccBridgeIp,
                ccTokenVisible = ccTokenVisible,
                ccIsGenerating = ccIsGenerating,
                ccIsTesting = ccIsTesting,
                ccTestResult = ccTestResult,
                ccTestGeneration = ccTestGeneration,
                currentTestGeneration = currentTestGeneration,
                onBridgeTokenChange = onBridgeTokenChange,
                onBridgeConnectedChange = onBridgeConnectedChange,
                onBridgeIpChange = onBridgeIpChange,
                onTokenVisibleChange = onTokenVisibleChange,
                onGeneratingChange = onGeneratingChange,
                onTestingChange = onTestingChange,
                onTestResultChange = onTestResultChange,
                onTestGenerationChange = onTestGenerationChange,
                onSaveMessageChange = onSaveMessageChange,
            )
        }
    }
}

@Composable
private fun CcBridgeUnconfiguredState(
    scope: CoroutineScope,
    userId: String,
    strings: com.silk.shared.i18n.Strings,
    ccIsGenerating: Boolean,
    onGeneratingChange: (Boolean) -> Unit,
    onBridgeTokenChange: (String?) -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
) {
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
                    onGeneratingChange(true)
                    try {
                        val response = ApiClient.generateBridgeToken(userId)
                        if (response.success) {
                            onBridgeTokenChange(response.ccBridgeToken)
                            onBridgeConnectedChange(response.bridgeConnected)
                        }
                    } finally {
                        onGeneratingChange(false)
                    }
                }
            }
        }
    }) {
        Text(if (ccIsGenerating) "..." else strings.ccGenerateToken)
    }
}

@Composable
private fun CcBridgeConfiguredState(
    scope: CoroutineScope,
    userId: String,
    strings: com.silk.shared.i18n.Strings,
    ccBridgeToken: String,
    ccBridgeConnected: Boolean,
    ccBridgeIp: String?,
    ccTokenVisible: Boolean,
    ccIsGenerating: Boolean,
    ccIsTesting: Boolean,
    ccTestResult: String?,
    ccTestGeneration: Int,
    currentTestGeneration: () -> Int,
    onBridgeTokenChange: (String?) -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onBridgeIpChange: (String?) -> Unit,
    onTokenVisibleChange: (Boolean) -> Unit,
    onGeneratingChange: (Boolean) -> Unit,
    onTestingChange: (Boolean) -> Unit,
    onTestResultChange: (String?) -> Unit,
    onTestGenerationChange: (Int) -> Unit,
    onSaveMessageChange: (String?) -> Unit,
) {
    CcBridgeStatusRow(
        ccBridgeConnected = ccBridgeConnected,
        strings = strings,
        ccBridgeIp = ccBridgeIp,
    )

    if (ccBridgeConnected && ccBridgeIp != null) {
        CcBridgeIpRow(ip = ccBridgeIp, strings = strings)
    }

    CcBridgeRefreshRow(
        scope = scope,
        userId = userId,
        strings = strings,
        ccIsTesting = ccIsTesting,
        ccTestResult = ccTestResult,
        ccTestGeneration = ccTestGeneration,
        currentTestGeneration = currentTestGeneration,
        onTestingChange = onTestingChange,
        onTestResultChange = onTestResultChange,
        onTestGenerationChange = onTestGenerationChange,
        onBridgeConnectedChange = onBridgeConnectedChange,
        onBridgeIpChange = onBridgeIpChange,
    )
    CcBridgeTokenRow(
        token = ccBridgeToken,
        tokenVisible = ccTokenVisible,
        strings = strings,
        onTokenVisibleChange = onTokenVisibleChange,
        onSaveMessageChange = onSaveMessageChange,
    )
    CcBridgeRegenerateButton(
        scope = scope,
        userId = userId,
        strings = strings,
        ccIsGenerating = ccIsGenerating,
        onGeneratingChange = onGeneratingChange,
        onBridgeTokenChange = onBridgeTokenChange,
        onBridgeConnectedChange = onBridgeConnectedChange,
        onTokenVisibleChange = onTokenVisibleChange,
    )
    CcBridgeHelpText(strings = strings)
}

@Composable
private fun CcBridgeStatusRow(
    ccBridgeConnected: Boolean,
    strings: com.silk.shared.i18n.Strings,
    ccBridgeIp: String?,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            gap(8.px)
            marginBottom(if (ccBridgeConnected && ccBridgeIp != null) 8.px else 16.px)
        }
    }) {
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
}

@Composable
private fun CcBridgeIpRow(ip: String, strings: com.silk.shared.i18n.Strings) {
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
            Text(ip)
        }
    }
}

@Composable
private fun CcBridgeRefreshRow(
    scope: CoroutineScope,
    userId: String,
    strings: com.silk.shared.i18n.Strings,
    ccIsTesting: Boolean,
    ccTestResult: String?,
    ccTestGeneration: Int,
    currentTestGeneration: () -> Int,
    onTestingChange: (Boolean) -> Unit,
    onTestResultChange: (String?) -> Unit,
    onTestGenerationChange: (Int) -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onBridgeIpChange: (String?) -> Unit,
) {
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
                        onTestingChange(true)
                        onTestResultChange(null)
                        val generation = ccTestGeneration + 1
                        onTestGenerationChange(generation)
                        try {
                            recoverSuspendNonCancellation(
                                block = {
                                    val response = ApiClient.getBridgeStatus(userId)
                                    onBridgeConnectedChange(response.bridgeConnected)
                                    onBridgeIpChange(response.bridgeIp)
                                    onTestResultChange(if (response.bridgeConnected) strings.ccTestSuccess else strings.ccTestFailed)
                                },
                                recover = { error ->
                                    console.error("刷新 Bridge 状态失败:", error)
                                    onTestResultChange(strings.ccTestFailed)
                                },
                            )
                        } finally {
                            onTestingChange(false)
                        }
                        delay(10_000)
                        if (currentTestGeneration() == generation) {
                            onTestResultChange(null)
                        }
                    }
                }
            }
        }) {
            Text(if (ccIsTesting) strings.ccRefreshingStatus else strings.ccRefreshStatus)
        }

        if (ccTestResult != null) {
            Span({
                style {
                    fontSize(13.px)
                    property("font-weight", "500")
                    color(if (ccTestResult == strings.ccTestSuccess) Color(SilkColors.success) else Color(SilkColors.error))
                }
            }) {
                Text(ccTestResult)
            }
        }
    }
}

@Composable
private fun CcBridgeTokenRow(
    token: String,
    tokenVisible: Boolean,
    strings: com.silk.shared.i18n.Strings,
    onTokenVisibleChange: (Boolean) -> Unit,
    onSaveMessageChange: (String?) -> Unit,
) {
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
                onClick { onTokenVisibleChange(!tokenVisible) }
            }) {
                Text(if (tokenVisible) token else "••••••••••••••••")
            }

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
                    copyToClipboard(token)
                    onSaveMessageChange(strings.ccTokenCopied)
                }
            }) {
                Text(strings.ccCopyToken)
            }
        }
    }
}

@Composable
private fun CcBridgeRegenerateButton(
    scope: CoroutineScope,
    userId: String,
    strings: com.silk.shared.i18n.Strings,
    ccIsGenerating: Boolean,
    onGeneratingChange: (Boolean) -> Unit,
    onBridgeTokenChange: (String?) -> Unit,
    onBridgeConnectedChange: (Boolean) -> Unit,
    onTokenVisibleChange: (Boolean) -> Unit,
) {
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
                if (!ccIsGenerating && kotlinx.browser.window.confirm(strings.ccRegenerateConfirm)) {
                    scope.launch {
                        onGeneratingChange(true)
                        try {
                            val response = ApiClient.generateBridgeToken(userId)
                            if (response.success) {
                                onBridgeTokenChange(response.ccBridgeToken)
                                onBridgeConnectedChange(response.bridgeConnected)
                                onTokenVisibleChange(true)
                            }
                        } finally {
                            onGeneratingChange(false)
                        }
                    }
                }
            }
        }) {
            Text(if (ccIsGenerating) "..." else strings.ccRegenerateToken)
        }
    }
}

@Composable
private fun CcBridgeHelpText(strings: com.silk.shared.i18n.Strings) {
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

@Composable
private fun SaveMessageBanner(message: String) {
    Div({
        style {
            padding(12.px, 16.px)
            marginBottom(24.px)
            borderRadius(8.px)
            backgroundColor(if (isSuccessSaveMessage(message)) Color("#E8F5E9") else Color("#FFEBEE"))
            color(if (isSuccessSaveMessage(message)) Color("#2E7D32") else Color("#C62828"))
            fontSize(14.px)
        }
    }) {
        Text(message)
    }
}

@Composable
private fun SettingsActionButtons(
    scope: CoroutineScope,
    appState: WebAppState,
    userId: String,
    strings: com.silk.shared.i18n.Strings,
    selectedLanguage: Language,
    defaultInstruction: String,
    isSaving: Boolean,
    onSavingChange: (Boolean) -> Unit,
    onLanguageChange: (Language) -> Unit,
    onDefaultInstructionChange: (String) -> Unit,
    onSaveMessageChange: (String?) -> Unit,
) {
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
                        onSavingChange(true)
                        onSaveMessageChange(null)
                        try {
                            recoverSuspendNonCancellation(
                                block = {
                                    val response = ApiClient.updateUserSettings(
                                        userId = userId,
                                        language = selectedLanguage,
                                        defaultAgentInstruction = defaultInstruction,
                                    )
                                    if (response.success && response.settings != null) {
                                        val savedSettings = response.settings!!
                                        onLanguageChange(savedSettings.language)
                                        onDefaultInstructionChange(savedSettings.defaultAgentInstruction)
                                        onSaveMessageChange(strings.settingsSaved)
                                    } else {
                                        onSaveMessageChange(strings.settingsSaveError)
                                    }
                                },
                                recover = { error ->
                                    console.error("保存设置失败:", error)
                                    onSaveMessageChange(strings.settingsSaveError)
                                },
                            )
                        } finally {
                            onSavingChange(false)
                        }
                    }
                }
            }
        }) {
            Text(if (isSaving) "保存中..." else strings.saveButton)
        }
    }
}

@Composable
fun SettingsScene(appState: WebAppState) {
    val scope = rememberCoroutineScope()
    val user = appState.currentUser ?: return

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

    LaunchedEffect(user.id) {
        scope.launch {
            isLoading = true
            try {
                loadSettingsSceneData(
                    userId = user.id,
                    onLanguageChange = { selectedLanguage = it },
                    onDefaultInstructionChange = { defaultInstruction = it },
                    onCcSettingsLoaded = { token, connected, ip ->
                        ccBridgeToken = token
                        ccBridgeConnected = connected
                        ccBridgeIp = ip
                    },
                )
            } finally {
                isLoading = false
            }
        }
    }

    val strings = getStrings(selectedLanguage)
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
        SettingsHeader(strings = strings, onBack = { appState.navigateBack() })
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
                SettingsLoadingState()
            } else {
                LanguageSection(
                    selectedLanguage = selectedLanguage,
                    isLoading = isLoading,
                    strings = strings,
                    englishStrings = englishStrings,
                    chineseStrings = chineseStrings,
                    onLanguageChange = { selectedLanguage = it },
                )
                DefaultInstructionSection(
                    label = strings.defaultAgentInstructionLabel,
                    defaultInstruction = defaultInstruction,
                    onDefaultInstructionChange = { defaultInstruction = it },
                )
                CcSettingsSection(
                    scope = scope,
                    userId = user.id,
                    strings = strings,
                    ccBridgeToken = ccBridgeToken,
                    ccBridgeConnected = ccBridgeConnected,
                    ccBridgeIp = ccBridgeIp,
                    ccTokenVisible = ccTokenVisible,
                    ccIsGenerating = ccIsGenerating,
                    ccIsTesting = ccIsTesting,
                    ccTestResult = ccTestResult,
                    ccTestGeneration = ccTestGeneration,
                    currentTestGeneration = { ccTestGeneration },
                    onBridgeTokenChange = { ccBridgeToken = it },
                    onBridgeConnectedChange = { ccBridgeConnected = it },
                    onBridgeIpChange = { ccBridgeIp = it },
                    onTokenVisibleChange = { ccTokenVisible = it },
                    onGeneratingChange = { ccIsGenerating = it },
                    onTestingChange = { ccIsTesting = it },
                    onTestResultChange = { ccTestResult = it },
                    onTestGenerationChange = { ccTestGeneration = it },
                    onSaveMessageChange = { saveMessage = it },
                )
                saveMessage?.let { message ->
                    SaveMessageBanner(message)
                }
                SettingsActionButtons(
                    scope = scope,
                    appState = appState,
                    userId = user.id,
                    strings = strings,
                    selectedLanguage = selectedLanguage,
                    defaultInstruction = defaultInstruction,
                    isSaving = isSaving,
                    onSavingChange = { isSaving = it },
                    onLanguageChange = { selectedLanguage = it },
                    onDefaultInstructionChange = { defaultInstruction = it },
                    onSaveMessageChange = { saveMessage = it },
                )
            }
        }
    }
}
