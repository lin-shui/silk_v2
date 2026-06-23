package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NoLiveLiterals
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silk.shared.ChatClient
import com.silk.shared.ConnectionState
import com.silk.shared.models.KnowledgeBaseContextSelection
import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import com.silk.shared.models.SILK_AGENT_DISPLAY_NAME
import com.silk.shared.models.SILK_AGENT_USER_ID
import com.silk.shared.models.UserSettings
import com.silk.shared.models.isAgentUserId
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.browser.window
import kotlinx.browser.document
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.Position
import org.jetbrains.compose.web.css.Style
import org.jetbrains.compose.web.css.StyleSheet
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontStyle
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.gap
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.left
import org.jetbrains.compose.web.css.margin
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginLeft
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxHeight
import org.jetbrains.compose.web.css.maxWidth
import org.jetbrains.compose.web.css.minWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.paddingBottom
import org.jetbrains.compose.web.css.paddingLeft
import org.jetbrains.compose.web.css.paddingTop
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.textAlign
import org.jetbrains.compose.web.css.top
import org.jetbrains.compose.web.css.vh
import org.jetbrains.compose.web.css.vw
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Br
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.renderComposable
import kotlin.js.Date
import kotlin.random.Random
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement

// 文件信息数据类
data class FileInfo(
    val name: String,
    val size: Long,
    val uploadTime: Long,
    val downloadUrl: String
)

// Silk 配色方案
object SilkColors {
    // 主色调 - 温暖的香槟金
    const val primary = "#C9A86C"
    const val primaryDark = "#A8894D"
    const val primaryLight = "#E0CDA0"

    // 次要色调 - 奶油丝绸
    const val secondary = "#E8D5B5"
    const val secondaryDark = "#D4C4A0"

    // 背景色 - 温暖的奶白色
    const val background = "#FDF8F0"
    const val backgroundGradient = "linear-gradient(135deg, #FDF8F0 0%, #F5EDE0 50%, #EDE4D3 100%)"
    const val surface = "#FFFBF5"
    const val surfaceElevated = "#FFFFFF"
    
    // 文字颜色
    const val textPrimary = "#4A4038"
    const val textSecondary = "#8A7B6A"
    const val textLight = "#B8A890"
    
    // 功能色
    const val success = "#7DAE6C"
    const val warning = "#E8B86C"
    const val error = "#D97B7B"
    const val info = "#7BA8C9"
    
    // 边框和分隔线
    const val border = "#E8E0D4"
    const val divider = "#F0E8DC"
}

private fun backendHttpOrigin(): String {
    val protocol = window.location.protocol
    val hostname = window.location.hostname
    val currentPort = window.location.port
    return if (currentPort == BuildConfig.FRONTEND_PORT) {
        "$protocol//$hostname:${BuildConfig.BACKEND_HTTP_PORT}"
    } else {
        window.location.origin
    }
}

internal fun backendWsOrigin(): String {
    val wsProtocol = if (window.location.protocol == "https:") "wss:" else "ws:"
    val currentPort = window.location.port
    val host = if (currentPort == BuildConfig.FRONTEND_PORT) {
        "${window.location.hostname}:${BuildConfig.BACKEND_HTTP_PORT}"
    } else {
        window.location.host
    }
    return "$wsProtocol//$host"
}

// ==================== 安全的 JS 互操作辅助函数（避免在 js("...") 中引用 Kotlin 变量） ====================

private val jsGetUserMedia = js("(function() { return navigator.mediaDevices.getUserMedia({audio: true}); })")
private val jsNewArray = js("(function() { return []; })")
private val jsCreateRecorder = js("(function(stream) { var opts = {mimeType: 'audio/webm;codecs=opus'}; try { return new MediaRecorder(stream, opts); } catch(e) { return new MediaRecorder(stream); } })")
private val jsCreateBlob = js("(function(chunks) { return new Blob(chunks, {type: 'audio/webm'}); })")
private val jsBlobToArrayBuffer = js("(function(blob) { return blob.arrayBuffer(); })")
private val jsArrayBufferToBase64 = js("(function(ab) { var u8 = new Uint8Array(ab); var b = ''; for (var i = 0; i < u8.length; i++) b += String.fromCharCode(u8[i]); return btoa(b); })")
private val jsStopTracks = js("(function(stream) { if (stream && stream.getTracks) { stream.getTracks().forEach(function(t) { t.stop(); }); } })")

internal fun downloadAsFile(content: String, fileName: String) {
    val blob = org.w3c.files.Blob(
        arrayOf(content),
        org.w3c.files.BlobPropertyBag(type = "text/markdown;charset=utf-8")
    )
    val windowJs = js("window")
    val objectUrl = windowJs.URL.createObjectURL(blob) as String
    val anchor = kotlinx.browser.document.createElement("a") as org.w3c.dom.HTMLAnchorElement
    anchor.style.display = "none"
    anchor.href = objectUrl
    anchor.download = fileName
    kotlinx.browser.document.body?.appendChild(anchor)
    anchor.click()
    kotlinx.browser.document.body?.removeChild(anchor)
    windowJs.URL.revokeObjectURL(objectUrl)
}

fun main() {
    console.log("🧵 Silk 正在启动...")
    console.log("1️⃣ 准备渲染...")
    
    renderComposable(rootElementId = "root") {
        console.log("2️⃣ renderComposable 已调用")

        Style(SilkStylesheet)
        console.log("3️⃣ Silk样式已加载")

        SilkApp()
        console.log("4️⃣ 主应用组件已渲染")
    }

    console.log("✅ Silk 启动完成")
}

@Composable
fun SilkApp() {
    val appState = remember { WebAppState() }
    val scope = rememberCoroutineScope()

    DisposableEffect(appState) {
        val bridge: (String?, String) -> Unit = { topicId, entryId ->
            appState.openKnowledgeBaseEntry(entryId = entryId, topicId = topicId)
        }
        window.asDynamic().__silkOpenKnowledgeBaseEntry = bridge
        onDispose {
            window.asDynamic().__silkOpenKnowledgeBaseEntry = null
        }
    }

    if (appState.currentScene == Scene.LOGIN) {
        LoginScene(appState)
    } else {
        Div({
            style {
                display(DisplayStyle.Flex)
                height(100.vh)
                width(100.vw)
                property("overflow", "hidden")
            }
        }) {
            SilkNavRail(appState)
            Div({
                style {
                    property("flex", "1")
                    minWidth(0.px)
                    height(100.percent)
                    property("overflow", "hidden")
                    position(Position.Relative)
                }
            }) {
                if (appState.currentScene == Scene.SETTINGS) {
                    SettingsScene(appState)
                } else {
                    key(appState.currentTab) {
                        when (appState.currentTab) {
                            NavTab.SILK -> SilkTabContent(appState)
                            NavTab.WORKFLOW -> WorkflowScene(appState)
                            NavTab.KNOWLEDGE_BASE -> KnowledgeBaseScene(appState)
                            NavTab.AUDIO_DUPLEX -> AudioDuplexScene(appState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SilkNavRail(appState: WebAppState) {
    Div({
        style {
            width(72.px)
            property("flex-shrink", "0")
            height(100.vh)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            alignItems(AlignItems.Center)
            property("background", "linear-gradient(180deg, ${SilkColors.primaryDark} 0%, ${SilkColors.primary} 100%)")
            property("border-right", "1px solid ${SilkColors.border}")
            paddingTop(16.px)
            paddingBottom(16.px)
            position(Position.Relative)
            property("z-index", "10")
        }
    }) {
        // Logo
        Div({
            style {
                paddingBottom(24.px)
                property("cursor", "default")
            }
        }) {
            Span({
                style {
                    fontSize(16.px)
                    fontWeight("bold")
                    color(Color.white)
                    property("letter-spacing", "2px")
                }
            }) { Text("SILK") }
        }

        // Tab items
        NavRailItem("Silk", appState.currentTab == NavTab.SILK, "\uD83D\uDCAC") {
            appState.selectTab(NavTab.SILK)
        }
        NavRailItem("工作流", appState.currentTab == NavTab.WORKFLOW, "\uD83D\uDD17") {
            appState.selectTab(NavTab.WORKFLOW)
        }
        NavRailItem("知识库", appState.currentTab == NavTab.KNOWLEDGE_BASE, "\uD83D\uDCDA") {
            appState.selectTab(NavTab.KNOWLEDGE_BASE)
        }
        NavRailItem("音频双工", appState.currentTab == NavTab.AUDIO_DUPLEX, "📞") {
            appState.selectTab(NavTab.AUDIO_DUPLEX)
        }

        // Spacer
        Div({ style { property("flex", "1") } })

        // Settings
        Div({
            style {
                property("cursor", "pointer")
                padding(8.px)
                borderRadius(8.px)
                property("transition", "background 0.2s")
            }
            onClick { appState.navigateTo(Scene.SETTINGS) }
        }) {
            Span({
                style {
                    fontSize(20.px)
                    property("filter", "grayscale(1) brightness(2)")
                }
            }) { Text("\u2699\uFE0F") }
        }
    }
}

@Composable
fun NavRailItem(label: String, isActive: Boolean, icon: String, onClick: () -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            alignItems(AlignItems.Center)
            property("cursor", "pointer")
            padding(8.px)
            marginBottom(4.px)
            borderRadius(12.px)
            width(56.px)
            if (isActive) {
                backgroundColor(Color("rgba(255,255,255,0.25)"))
            }
            property("transition", "background 0.2s")
        }
        onClick { onClick() }
    }) {
        Span({ style { fontSize(22.px) } }) { Text(icon) }
        Span({
            style {
                fontSize(10.px)
                color(Color.white)
                marginTop(2.px)
                property("white-space", "nowrap")
            }
        }) { Text(label) }
    }
}

@Composable
fun SilkTabContent(appState: WebAppState) {
    when (appState.currentScene) {
        Scene.GROUP_LIST -> GroupListScene(appState)
        Scene.CONTACTS -> ContactsScene(appState)
        Scene.CHAT_ROOM -> {
            if (appState.selectedGroup != null && appState.currentUser != null) {
                ChatScene(appState)
            } else {
                Div({ style { padding(20.px) } }) {
                    Text("状态错误，请返回重试")
                    Button({ onClick { appState.navigateBack() } }) {
                        Text("返回群组列表")
                    }
                }
            }
        }
        else -> GroupListScene(appState)
    }
}

@Composable
fun ChatScene(appState: WebAppState) {
    console.log("🎬 ChatScene被调用")

    val group = appState.selectedGroup
    val user = appState.currentUser
    console.log("   群组:", group?.name ?: "null")
    console.log("   用户:", user?.fullName ?: "null")

    if (group == null || user == null) {
        console.log("⚠️ 群组或用户为空，显示错误页面")
        ChatSceneMissingContext(appState)
        return
    }

    val scope = rememberCoroutineScope()
    var userGroups by remember(user.id) { mutableStateOf<List<Group>>(emptyList()) }
    var unreadCounts by remember(user.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var isLoadingGroups by remember(user.id) { mutableStateOf(true) }

    ChatSceneEffects(
        user = user,
        group = group,
        setLoading = { isLoadingGroups = it },
        onGroupsLoaded = { userGroups = it },
        onUnreadCountsLoaded = { unreadCounts = it },
    )

    console.log("✅ 群组和用户都有效，渲染聊天界面")
    Div({
        style {
            display(DisplayStyle.Flex)
            height(100.percent)
            width(100.percent)
            property("overflow", "hidden")
            property("background", SilkColors.backgroundGradient)
        }
    }) {
        ChatSceneSidebar(
            currentGroup = group,
            userGroups = userGroups,
            unreadCounts = unreadCounts,
            isLoadingGroups = isLoadingGroups,
            onSelectGroup = { item ->
                scope.launch {
                    ApiClient.markGroupAsRead(user.id, item.id)
                    unreadCounts = unreadCounts - item.id
                    appState.selectGroup(item)
                }
            },
        )

        Div({
            style {
                property("flex", "1")
                minWidth(0.px)
                height(100.percent)
                property("overflow", "hidden")
            }
        }) {
            ChatAppWithGroup(user, group, appState)
        }
    }
}

@Composable
private fun ChatSceneMissingContext(appState: WebAppState) {
    Div({ style { padding(20.px) } }) {
        Text("错误：缺少群组或用户信息")
        Button({ onClick { appState.navigateBack() } }) {
            Text("返回")
        }
    }
}

@Composable
private fun ChatSceneEffects(
    user: User,
    group: Group,
    setLoading: (Boolean) -> Unit,
    onGroupsLoaded: (List<Group>) -> Unit,
    onUnreadCountsLoaded: (Map<String, Int>) -> Unit,
) {
    LaunchedEffect(user.id, group.id) {
        refreshChatSceneSidebar(user, setLoading, onGroupsLoaded, onUnreadCountsLoaded)
    }

    LaunchedEffect(user.id) {
        pollChatSceneUnreadCounts(user, onUnreadCountsLoaded)
    }
}

@Composable
private fun ChatSceneSidebar(
    currentGroup: Group,
    userGroups: List<Group>,
    unreadCounts: Map<String, Int>,
    isLoadingGroups: Boolean,
    onSelectGroup: (Group) -> Unit,
) {
    Div({
        style {
            width(320.px)
            property("flex-shrink", "0")
            property("border-right", "1px solid ${SilkColors.border}")
            backgroundColor(Color("rgba(255,255,255,0.88)"))
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("overflow", "hidden")
            property("backdrop-filter", "blur(6px)")
        }
    }) {
        ChatSceneSidebarHeader()
        ChatSceneSidebarContent(
            currentGroup = currentGroup,
            userGroups = userGroups,
            unreadCounts = unreadCounts,
            isLoadingGroups = isLoadingGroups,
            onSelectGroup = onSelectGroup,
        )
    }
}

@Composable
private fun ChatSceneSidebarHeader() {
    Div({
        style {
            padding(16.px, 16.px, 12.px, 16.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
            color(Color(SilkColors.textPrimary))
            fontSize(16.px)
            property("font-weight", "700")
            property("letter-spacing", "1px")
        }
    }) {
        Text("全部群组")
    }
}

@Composable
private fun ChatSceneSidebarContent(
    currentGroup: Group,
    userGroups: List<Group>,
    unreadCounts: Map<String, Int>,
    isLoadingGroups: Boolean,
    onSelectGroup: (Group) -> Unit,
) {
    Div({
        style {
            property("flex", "1")
            property("overflow-y", "auto")
            padding(10.px)
        }
    }) {
        when {
            isLoadingGroups -> ChatSceneSidebarPlaceholder("加载群组中...")
            userGroups.isEmpty() -> ChatSceneSidebarPlaceholder("暂无群组")
            else -> {
                userGroups.forEach { item ->
                    ChatSceneSidebarGroupCard(
                        item = item,
                        isActive = item.id == currentGroup.id,
                        unreadCount = unreadCounts[item.id] ?: 0,
                        onClick = {
                            if (item.id != currentGroup.id) {
                                onSelectGroup(item)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatSceneSidebarPlaceholder(text: String) {
    Div({
        style {
            color(Color(SilkColors.textSecondary))
            fontSize(13.px)
            textAlign("center")
            padding(18.px)
        }
    }) {
        Text(text)
    }
}

@Composable
private fun ChatSceneSidebarGroupCard(
    item: Group,
    isActive: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
) {
    Div({
        style {
            padding(12.px, 14.px)
            marginBottom(8.px)
            borderRadius(8.px)
            backgroundColor(
                if (isActive) Color("rgba(201, 168, 108, 0.2)")
                else Color(SilkColors.surfaceElevated)
            )
            property("border", if (isActive) "1px solid ${SilkColors.primary}" else "1px solid ${SilkColors.border}")
            property("box-shadow", if (isActive) "0 2px 8px rgba(169, 137, 77, 0.22)" else "0 1px 4px rgba(169, 137, 77, 0.08)")
            property("cursor", "pointer")
            property("transition", "all 0.2s ease")
        }
        onClick { onClick() }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                property("gap", "10px")
            }
        }) {
            Span({
                style {
                    color(Color(SilkColors.textPrimary))
                    fontSize(14.px)
                    property("font-weight", if (isActive) "700" else "600")
                    property("flex", "1")
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("white-space", "nowrap")
                }
            }) {
                Text(item.name)
            }
            ChatSceneUnreadBadge(unreadCount)
        }
        Div({
            style {
                color(Color(SilkColors.textSecondary))
                fontSize(11.px)
                marginTop(4.px)
                property("letter-spacing", "1px")
            }
        }) {
            Text("[${item.invitationCode}]")
        }
    }
}

@Composable
private fun ChatSceneUnreadBadge(unreadCount: Int) {
    if (unreadCount <= 0) {
        return
    }

    Span({
        style {
            minWidth(22.px)
            height(22.px)
            padding(0.px, 6.px)
            borderRadius(11.px)
            backgroundColor(Color("#FF5722"))
            color(Color.white)
            fontSize(11.px)
            property("font-weight", "700")
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
        }
    }) {
        Text(if (unreadCount > 99) "99+" else unreadCount.toString())
    }
}

private suspend fun refreshChatSceneSidebar(
    user: User,
    setLoading: (Boolean) -> Unit,
    onGroupsLoaded: (List<Group>) -> Unit,
    onUnreadCountsLoaded: (Map<String, Int>) -> Unit,
) {
    setLoading(true)
    try {
        recoverSuspendNonCancellation(
            block = {
                val groupsResponse = ApiClient.getUserGroups(user.id)
                if (groupsResponse.success) {
                    onGroupsLoaded((groupsResponse.groups ?: emptyList()).filterNot { it.name.startsWith("wf_") })
                }
                fetchChatSceneUnreadCounts(user.id)?.let(onUnreadCountsLoaded)
            },
            recover = { error ->
                console.error("❌ 加载聊天室群组列表失败:", error)
            },
        )
    } finally {
        setLoading(false)
    }
}

private suspend fun pollChatSceneUnreadCounts(
    user: User,
    onUnreadCountsLoaded: (Map<String, Int>) -> Unit,
) {
    while (true) {
        kotlinx.coroutines.delay(30000)
        recoverSuspendNonCancellation(
            block = {
                fetchChatSceneUnreadCounts(user.id)?.let(onUnreadCountsLoaded)
            },
            recover = { error ->
                console.error("❌ 刷新未读消息失败:", error)
            },
        )
    }
}

private suspend fun fetchChatSceneUnreadCounts(userId: String): Map<String, Int>? {
    val unreadResponse = ApiClient.getUnreadCounts(userId)
    return if (unreadResponse.success) unreadResponse.unreadCounts else null
}

// Silk样式表 - 丝滑温暖风格
object SilkStylesheet : StyleSheet() {
    val container by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        height(100.percent)
        fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
        property("overflow", "hidden")
        property("background", SilkColors.backgroundGradient)
    }
    
    val header by style {
        property("flex-shrink", "0")
        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
        color(Color.white)
        padding(8.px, 16.px)
        fontSize(18.px)
        property("font-weight", "600")
        property("letter-spacing", "2px")
        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.2)")
    }
    
    val statusBar by style {
        property("flex-shrink", "0")
        padding(4.px, 16.px)
        display(DisplayStyle.Flex)
        property("justify-content", "space-between")
        property("align-items", "center")
        property("font-size", "12px")
        property("letter-spacing", "1px")
    }
    
    val messagesContainer by style {
        property("flex", "1")
        property("min-height", "0")
        property("overflow-y", "auto")
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        padding(12.px)
        property("background", SilkColors.backgroundGradient)
    }
    
    val messageCard by style {
        backgroundColor(Color(SilkColors.surfaceElevated))
        borderRadius(12.px)
        padding(14.px, 16.px)
        marginBottom(10.px)
        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.08)")
        property("border", "1px solid ${SilkColors.border}")
        property("transition", "all 0.2s ease")
    }
    
    val messageHeader by style {
        display(DisplayStyle.Flex)
        property("justify-content", "space-between")
        marginBottom(6.px)
    }
    
    val userName by style {
        property("font-weight", "600")
        color(Color(SilkColors.primary))
        property("letter-spacing", "0.5px")
    }
    
    val timestamp by style {
        fontSize(11.px)
        color(Color(SilkColors.textLight))
        property("font-style", "italic")
    }
    
    val systemMessage by style {
        fontSize(12.px)
        color(Color(SilkColors.textSecondary))
        property("text-align", "center")
        marginBottom(8.px)
        property("font-style", "italic")
    }
    
    val inputContainer by style {
        display(DisplayStyle.Flex)
        property("flex-shrink", "0")
        padding(8.px, 16.px)
        backgroundColor(Color(SilkColors.surfaceElevated))
        property("border-top", "1px solid ${SilkColors.border}")
        property("gap", "8px")
        property("box-shadow", "0 -2px 8px rgba(169, 137, 77, 0.05)")
    }
    
    val input by style {
        property("flex", "1")
        padding(10.px)
        border {
            width(1.px)
            style(LineStyle.Solid)
            color(Color(SilkColors.border))
        }
        borderRadius(8.px)
        fontSize(14.px)
        fontFamily("'Noto Serif SC'", "'Cormorant Garamond'", "Georgia", "serif")
        property("background", SilkColors.surface)
        property("color", SilkColors.textPrimary)
        property("transition", "all 0.2s ease")
    }
    
    val button by style {
        padding(8.px, 20.px)
        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
        color(Color.white)
        border { width(0.px) }
        borderRadius(8.px)
        property("cursor", "pointer")
        fontSize(14.px)
        property("font-weight", "600")
        property("letter-spacing", "1px")
        property("transition", "all 0.2s ease")
        property("box-shadow", "0 2px 8px rgba(169, 137, 77, 0.25)")
    }
    
    val buttonHover by style {
        property("background", "linear-gradient(135deg, ${SilkColors.primaryDark} 0%, #8A7040 100%)")
        property("transform", "translateY(-1px)")
        property("box-shadow", "0 4px 12px rgba(169, 137, 77, 0.35)")
    }
    
    // 临时消息样式 - 更柔和
    val transientMessageCard by style {
        backgroundColor(Color(SilkColors.secondary))
        borderRadius(12.px)
        padding(12.px, 14.px)
        marginBottom(10.px)
        property("opacity", "0.85")
        property("font-style", "italic")
        property("font-size", "13px")
        property("border-left", "3px solid ${SilkColors.warning}")
        property("box-shadow", "0 2px 6px rgba(169, 137, 77, 0.1)")
    }
    
    // 进度条样式 - 丝滑金色
    val progressBarContainer by style {
        marginTop(10.px)
        marginBottom(8.px)
    }
    
    val progressBar by style {
        width(100.percent)
        height(4.px)
        backgroundColor(Color(SilkColors.border))
        borderRadius(2.px)
        property("overflow", "hidden")
        property("position", "relative")
    }
    
    val progressFill by style {
        height(100.percent)
        property("background", "linear-gradient(90deg, ${SilkColors.primary}, ${SilkColors.primaryLight})")
        property("transition", "width 0.3s ease")
        property("box-shadow", "0 0 8px rgba(201, 168, 108, 0.5)")
    }
    
    // ==================== AI 消息卡片样式 ====================
    // AI 消息卡片 - 渐变背景
    val aiMessageCard by style {
        property("background", "linear-gradient(135deg, #F8FBFF 0%, #EEF4FF 50%, #E8F0FE 100%)")
        borderRadius(16.px)
        padding(16.px, 20.px)
        marginBottom(12.px)
        property("box-shadow", "0 4px 20px rgba(59, 130, 246, 0.15)")
        property("border", "1px solid rgba(59, 130, 246, 0.2)")
        property("position", "relative")
        property("overflow", "hidden")
    }
    
    // AI 头像区域
    val aiAvatar by style {
        width(36.px)
        height(36.px)
        property("background", "linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%)")
        borderRadius(50.percent)
        display(DisplayStyle.Flex)
        property("justify-content", "center")
        property("align-items", "center")
        property("font-size", "18px")
        property("flex-shrink", "0")
    }
    
    // AI 消息头部
    val aiMessageHeader by style {
        display(DisplayStyle.Flex)
        property("align-items", "center")
        property("gap", "10px")
        marginBottom(12.px)
    }
    
    // AI 标签
    val aiBadge by style {
        padding(4.px, 10.px)
        property("background", "linear-gradient(135deg, #3B82F6 0%, #8B5CF6 100%)")
        borderRadius(12.px)
        color(Color.white)
        fontSize(11.px)
        property("font-weight", "600")
        property("letter-spacing", "0.5px")
    }
    
    // AI 消息内容区域
    val aiMessageContent by style {
        property("line-height", "1.8")
        property("color", "#1E293B")
        property("font-size", "14px")
    }
    
    // Markdown 标题样式
    val markdownH1 by style {
        fontSize(20.px)
        property("font-weight", "700")
        color(Color("#1E293B"))
        marginTop(16.px)
        marginBottom(12.px)
        paddingBottom(8.px)
        property("border-bottom", "2px solid #E2E8F0")
    }
    
    val markdownH2 by style {
        fontSize(18.px)
        property("font-weight", "600")
        color(Color("#334155"))
        marginTop(14.px)
        marginBottom(10.px)
        property("border-left", "3px solid #3B82F6")
        paddingLeft(10.px)
    }
    
    val markdownH3 by style {
        fontSize(16.px)
        property("font-weight", "600")
        color(Color("#475569"))
        marginTop(12.px)
        marginBottom(8.px)
    }
    
    // Markdown 代码块
    val markdownCodeBlock by style {
        property("background", "#1E293B")
        color(Color("#E2E8F0"))
        padding(16.px)
        borderRadius(8.px)
        property("font-family", "'JetBrains Mono', 'Fira Code', monospace")
        fontSize(13.px)
        property("overflow-x", "auto")
        marginTop(10.px)
        marginBottom(10.px)
        property("line-height", "1.6")
    }
    
    // Markdown 行内代码
    val markdownInlineCode by style {
        property("background", "rgba(59, 130, 246, 0.1)")
        color(Color("#3B82F6"))
        padding(2.px, 6.px)
        borderRadius(4.px)
        property("font-family", "'JetBrains Mono', 'Fira Code', monospace")
        fontSize(13.px)
    }
    
    // Markdown 引用
    val markdownBlockquote by style {
        property("border-left", "4px solid #3B82F6")
        paddingLeft(16.px)
        marginLeft(0.px)
        property("background", "rgba(59, 130, 246, 0.05)")
        padding(12.px, 16.px)
        borderRadius(0.px, 8.px, 8.px, 0.px)
        marginTop(10.px)
        marginBottom(10.px)
        property("font-style", "italic")
        color(Color("#64748B"))
    }
    
    // Markdown 列表
    val markdownList by style {
        marginLeft(20.px)
        marginTop(8.px)
        marginBottom(8.px)
    }
    
    val markdownListItem by style {
        marginBottom(6.px)
        property("line-height", "1.6")
        property("position", "relative")
    }
    
    // Markdown 链接
    val markdownLink by style {
        color(Color("#3B82F6"))
        property("text-decoration", "none")
        property("border-bottom", "1px solid rgba(59, 130, 246, 0.3)")
        property("transition", "all 0.2s")
    }
    
    // Markdown 分割线
    val markdownHr by style {
        property("border", "none")
        height(1.px)
        property("background", "linear-gradient(90deg, transparent, #E2E8F0, transparent)")
        marginTop(16.px)
        marginBottom(16.px)
    }
    
    // Markdown 表格
    val markdownTable by style {
        width(100.percent)
        property("border-collapse", "collapse")
        marginTop(10.px)
        marginBottom(10.px)
        property("font-size", "13px")
    }
    
    val markdownTableHeader by style {
        property("background", "rgba(59, 130, 246, 0.1)")
        property("font-weight", "600")
        padding(10.px)
        property("text-align", "left")
        property("border-bottom", "2px solid #E2E8F0")
    }
    
    val markdownTableCell by style {
        padding(10.px)
        property("border-bottom", "1px solid #E2E8F0")
    }
    
    // Markdown 加粗
    val markdownBold by style {
        property("font-weight", "700")
        color(Color("#1E293B"))
    }
    
    // Markdown 斜体
    val markdownItalic by style {
        property("font-style", "italic")
        color(Color("#64748B"))
    }
}

@Composable
fun ChatAppWithGroup(user: User, group: Group, appState: WebAppState) {
    console.log("🎯 ChatAppWithGroup - 用户:", user.fullName, "群组:", group.name)
    
    val scope = rememberCoroutineScope()
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<com.silk.shared.models.Language>(com.silk.shared.models.Language.CHINESE) }
    val strings = com.silk.shared.i18n.getStrings(userLanguage)
    
    // 动态生成 WebSocket URL，兼容同源代理与本地分端口开发
    val wsUrl = remember {
        val url = backendWsOrigin()
        console.log("🔌 WebSocket URL: $url")
        url
    }
    
    val chatClient = remember { ChatClient(wsUrl) }
    val messages by chatClient.messages.collectAsState()
    val transientMessage by chatClient.transientMessage.collectAsState()
    val statusMessages by chatClient.statusMessages.collectAsState()
    val connectionState by chatClient.connectionState.collectAsState()
    val isGenerating by chatClient.isGenerating.collectAsState()
    val pendingQuestionId by chatClient.pendingQuestionId.collectAsState()
    // Track if we've sent the default instruction for this session
    var hasSentDefaultInstruction by remember { mutableStateOf(false) }
    
    var messageText by remember { mutableStateOf("") }
    var kbContextSelection by remember(group.id) { mutableStateOf(KnowledgeBaseContextSelection()) }
    var showInvitationDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var isExportingMarkdown by remember { mutableStateOf(false) }
    var exportMarkdownHint by remember { mutableStateOf<String?>(null) }
    var showFolderExplorer by remember { mutableStateOf(false) }
    var isLoadingFiles by remember { mutableStateOf(false) }

    // ASR 语音输入状态
    var isVoiceRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    var mediaRecorderJs by remember { mutableStateOf<dynamic>(null) }
    var audioChunksJs by remember { mutableStateOf<dynamic>(null) }
    
    // 添加成员到群组相关状态
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var groupMembers by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var isLoadingContacts by remember { mutableStateOf(false) }
    var addMemberResult by remember { mutableStateOf<String?>(null) }
    
    // 查看成员列表相关状态
    var showMembersDialog by remember { mutableStateOf(false) }
    var selectedMemberForInvite by remember { mutableStateOf<GroupMember?>(null) }
    var isInvitingMember by remember { mutableStateOf(false) }
    var inviteMemberResult by remember { mutableStateOf<String?>(null) }
    
    // @ mention 功能状态
    var showMentionMenu by remember { mutableStateOf(false) }
    var mentionSearchText by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    var mentionMenuPosition by remember { mutableStateOf(Pair(0.0, 0.0)) } // (left, bottom)
    
    // 消息撤回相关状态：正在撤回中的消息ID集合，防止重复点击
    var recallingMessageIds by remember { mutableStateOf(setOf<String>()) }
    
    // 消息选择模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(setOf<String>()) }
    
    // 消息转发相关状态
    var showForwardDialog by remember { mutableStateOf(false) }
    var messageToForward by remember { mutableStateOf<Message?>(null) }
    var userGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoadingGroups by remember { mutableStateOf(false) }
    var forwardResult by remember { mutableStateOf<String?>(null) }
    var kbCaptureDraft by remember { mutableStateOf<KnowledgeCaptureDraft?>(null) }
    var kbCaptureTopics by remember { mutableStateOf<List<KBTopicItem>>(emptyList()) }
    var kbCaptureGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var kbCaptureSelectedSpaceId by remember { mutableStateOf(PERSONAL_SPACE_ID) }
    var kbCaptureSelectedTopicId by remember { mutableStateOf("") }
    var kbCaptureTitle by remember { mutableStateOf("") }
    var kbCaptureContent by remember { mutableStateOf("") }
    var kbCaptureSaving by remember { mutableStateOf(false) }
    var kbCaptureResult by remember { mutableStateOf<String?>(null) }
    
    // 从群组成员列表和消息历史中提取用户列表（去重）
    // 优先使用 groupMembers（包含所有成员），然后补充消息历史中的成员
    val sessionUsers = remember(groupMembers, messages) {
        val users = mutableSetOf<Pair<String, String>>() // (id, name)
        // 始终添加 Silk AI
        users.add(SILK_AGENT_USER_ID to "🤖 $SILK_AGENT_DISPLAY_NAME")
        // 添加群组成员列表中的所有成员
        groupMembers.forEach { member ->
            users.add(member.id to member.fullName)
        }
        // 添加当前用户（以防万一）
        users.add(user.id to user.fullName)
        // 从消息中提取其他用户（补充可能不在成员列表中的用户，如已退群的用户）
        messages.forEach { msg ->
            if (!isAgentUserId(msg.userId) && msg.userId != user.id) {
                users.add(msg.userId to msg.userName)
            }
        }
        users.toList()
    }

    val resetKnowledgeCaptureDialog = {
        kbCaptureDraft = null
        kbCaptureTopics = emptyList()
        kbCaptureGroups = emptyList()
        kbCaptureSelectedSpaceId = PERSONAL_SPACE_ID
        kbCaptureSelectedTopicId = ""
        kbCaptureTitle = ""
        kbCaptureContent = ""
        kbCaptureSaving = false
        kbCaptureResult = null
    }

    ChatAppEffects(
        user = user,
        group = group,
        currentScene = appState.currentScene,
        chatClient = chatClient,
        isSelectionMode = isSelectionMode,
        onSelectionCleared = {
            isSelectionMode = false
            selectedMessageIds = emptySet()
        },
        messagesSize = messages.size,
        transientMessage = transientMessage,
        statusMessagesSize = statusMessages.size,
        onLanguageLoaded = { userLanguage = it },
        onSessionReset = { hasSentDefaultInstruction = false },
        onGroupMembersLoaded = { groupMembers = it },
    )

    Div({ classes(SilkStylesheet.container) }) {
        ChatAppHeader(
            scope = scope,
            user = user,
            group = group,
            appState = appState,
            chatClient = chatClient,
            messages = messages,
            strings = strings,
            isSelectionMode = isSelectionMode,
            selectedMessageIds = selectedMessageIds,
            isExportingMarkdown = isExportingMarkdown,
            exportMarkdownHint = exportMarkdownHint,
            onSelectionModeChanged = { isSelectionMode = it },
            onSelectedMessageIdsChanged = { selectedMessageIds = it },
            onMessageForwardChanged = { messageToForward = it },
            onGroupTargetsLoaded = { userGroups = it },
            onGroupTargetsLoadingChanged = { isLoadingGroups = it },
            onForwardDialogVisibilityChanged = { showForwardDialog = it },
            onExportingChanged = { isExportingMarkdown = it },
            onExportMarkdownHintChanged = { exportMarkdownHint = it },
            onShowFolderExplorer = {
                showFolderExplorer = true
                isLoadingFiles = true
            },
            onShowInvitationDialog = { showInvitationDialog = true },
            onContactsLoaded = { contacts = it },
            onGroupMembersLoaded = { groupMembers = it },
            onContactsLoadingChanged = { isLoadingContacts = it },
            onShowAddMemberDialog = { showAddMemberDialog = true },
            onShowMembersDialog = { showMembersDialog = true },
        )

        ChatAppConnectionBanner(
            scope = scope,
            connectionState = connectionState,
            strings = strings,
            chatClient = chatClient,
            user = user,
            group = group,
        )

        ChatAppMessagePane(
            scope = scope,
            user = user,
            group = group,
            messages = messages,
            transientMessage = transientMessage,
            statusMessages = statusMessages,
            chatClient = chatClient,
            recallingMessageIds = recallingMessageIds,
            isSelectionMode = isSelectionMode,
            selectedMessageIds = selectedMessageIds,
            onRecallingMessageIdsChanged = { recallingMessageIds = it },
            onMessageToForwardChanged = { messageToForward = it },
            onGroupTargetsLoaded = { userGroups = it },
            onGroupTargetsLoadingChanged = { isLoadingGroups = it },
            onForwardDialogVisibilityChanged = { showForwardDialog = it },
            onSelectionModeChanged = { isSelectionMode = it },
            onSelectedMessageIdsChanged = { selectedMessageIds = it },
            onCaptureToKnowledgeBase = { message ->
                scope.launch {
                    val context = loadKnowledgeCaptureContext(user.id)
                    val preferredSpaceId = preferredKnowledgeCaptureSpaceId(group.id, context.topics)
                    kbCaptureDraft = KnowledgeCaptureDraft(
                        message = message,
                        sourceType = KBSourceType.CHAT,
                        sourceGroupId = group.id,
                        preferredSpaceId = preferredSpaceId,
                    )
                    kbCaptureTopics = context.topics
                    kbCaptureGroups = context.groups
                    kbCaptureSelectedSpaceId = preferredSpaceId
                    kbCaptureSelectedTopicId = defaultKnowledgeCaptureTopicId(context.topics, preferredSpaceId).orEmpty()
                    kbCaptureTitle = buildDefaultKnowledgeCaptureTitle(message.content)
                    kbCaptureContent = message.content
                    kbCaptureSaving = false
                    kbCaptureResult = if (context.topics.isEmpty()) "还没有可用主题，请先去知识库创建主题。" else null
                }
            },
        )

        ChatAppDragAndDropEffect(group = group, user = user)

        ChatAppInputSection(
            scope = scope,
            user = user,
            group = group,
            strings = strings,
            connectionState = connectionState,
            chatClient = chatClient,
            statusMessages = statusMessages,
            sessionUsers = sessionUsers,
            pendingQuestionId = pendingQuestionId,
            isGenerating = isGenerating,
            isUploading = isUploading,
            isVoiceRecording = isVoiceRecording,
            isTranscribing = isTranscribing,
            mediaRecorderJs = mediaRecorderJs,
            audioChunksJs = audioChunksJs,
            messageText = messageText,
            kbContextSelection = kbContextSelection,
            showMentionMenu = showMentionMenu,
            mentionSearchText = mentionSearchText,
            mentionStartIndex = mentionStartIndex,
            mentionMenuPosition = mentionMenuPosition,
            onMediaRecorderChanged = { mediaRecorderJs = it },
            onAudioChunksChanged = { audioChunksJs = it },
            onVoiceRecordingChanged = { isVoiceRecording = it },
            onTranscribingChanged = { isTranscribing = it },
            onMessageTextChanged = { messageText = it },
            onKnowledgeBaseContextSelectionChanged = { kbContextSelection = it },
            onShowMentionMenuChanged = { showMentionMenu = it },
            onMentionSearchTextChanged = { mentionSearchText = it },
            onMentionStartIndexChanged = { mentionStartIndex = it },
            onMentionMenuPositionChanged = { mentionMenuPosition = it },
        )
    }

    kbCaptureDraft?.let { draft ->
        KnowledgeBaseCaptureDialog(
            draft = draft,
            spaceOptions = buildKnowledgeSpaceOptions(kbCaptureGroups),
            topics = kbCaptureTopics,
            selectedSpaceId = kbCaptureSelectedSpaceId,
            selectedTopicId = kbCaptureSelectedTopicId,
            title = kbCaptureTitle,
            content = kbCaptureContent,
            isSaving = kbCaptureSaving,
            resultMessage = kbCaptureResult,
            onSelectedSpaceIdChange = { kbCaptureSelectedSpaceId = it },
            onSelectedTopicIdChange = { kbCaptureSelectedTopicId = it },
            onTitleChange = { kbCaptureTitle = it },
            onContentChange = { kbCaptureContent = it },
            onDismiss = resetKnowledgeCaptureDialog,
            onConfirm = {
                if (!canSubmitKnowledgeCapture(kbCaptureSaving, kbCaptureSelectedTopicId, kbCaptureTitle, kbCaptureContent)) {
                    return@KnowledgeBaseCaptureDialog
                }
                scope.launch {
                    kbCaptureSaving = true
                    val created = ApiClient.captureKBEntry(
                        topicId = kbCaptureSelectedTopicId,
                        title = kbCaptureTitle.trim(),
                        content = kbCaptureContent,
                        tags = emptyList(),
                        userId = user.id,
                        source = KBEntrySource(
                            sourceType = draft.sourceType,
                            sourceGroupId = draft.sourceGroupId,
                            workflowId = draft.workflowId,
                            messageIds = listOf(draft.message.id),
                        ),
                    )
                    kbCaptureSaving = false
                    if (created == null) {
                        kbCaptureResult = "保存失败，请确认目标主题仍可写。"
                    } else {
                        resetKnowledgeCaptureDialog()
                        appState.openKnowledgeBaseEntry(created.id, created.topicId)
                    }
                }
            },
        )
    }
    
    ChatAppDialogsAndUploads(
        scope = scope,
        user = user,
        group = group,
        appState = appState,
        chatClient = chatClient,
        strings = strings,
        showForwardDialog = showForwardDialog,
        messageToForward = messageToForward,
        userGroups = userGroups,
        isLoadingGroups = isLoadingGroups,
        forwardResult = forwardResult,
        showInvitationDialog = showInvitationDialog,
        showAddMemberDialog = showAddMemberDialog,
        contacts = contacts,
        groupMembers = groupMembers,
        isLoadingContacts = isLoadingContacts,
        addMemberResult = addMemberResult,
        showMembersDialog = showMembersDialog,
        selectedMemberForInvite = selectedMemberForInvite,
        isInvitingMember = isInvitingMember,
        inviteMemberResult = inviteMemberResult,
        showFolderExplorer = showFolderExplorer,
        onForwardDialogVisibilityChanged = { showForwardDialog = it },
        onMessageToForwardChanged = { messageToForward = it },
        onForwardResultChanged = { forwardResult = it },
        onInvitationDialogVisibilityChanged = { showInvitationDialog = it },
        onAddMemberDialogVisibilityChanged = { showAddMemberDialog = it },
        onGroupMembersLoaded = { groupMembers = it },
        onAddMemberResultChanged = { addMemberResult = it },
        onMembersDialogVisibilityChanged = { showMembersDialog = it },
        onSelectedMemberForInviteChanged = { selectedMemberForInvite = it },
        onInvitingMemberChanged = { isInvitingMember = it },
        onInviteMemberResultChanged = { inviteMemberResult = it },
        onShowFolderExplorerChanged = { showFolderExplorer = it },
    )
}

@Composable
private fun ChatAppEffects(
    user: User,
    group: Group,
    currentScene: Scene,
    chatClient: ChatClient,
    isSelectionMode: Boolean,
    onSelectionCleared: () -> Unit,
    messagesSize: Int,
    transientMessage: Message?,
    statusMessagesSize: Int,
    onLanguageLoaded: (com.silk.shared.models.Language) -> Unit,
    onSessionReset: () -> Unit,
    onGroupMembersLoaded: (List<GroupMember>) -> Unit,
) {
    LaunchedEffect(user.id, currentScene) {
        if (currentScene == Scene.CHAT_ROOM) {
            recoverSuspendNonCancellation(
                block = {
                    val response = ApiClient.getUserSettings(user.id)
                    if (response.success) {
                        response.settings?.language?.let(onLanguageLoaded)
                    }
                },
                recover = { error ->
                    console.error("Failed to load user settings:", error)
                },
            )
        }
    }

    DisposableEffect(Unit) {
        val handler: (org.w3c.dom.events.Event) -> Unit = { event ->
            val key = event.asDynamic().key as? String
            if (key == "Escape" && isSelectionMode) {
                onSelectionCleared()
            }
        }
        window.addEventListener("keydown", handler)
        onDispose { window.removeEventListener("keydown", handler) }
    }

    LaunchedEffect(group.id) {
        console.log("🔌 准备建立WebSocket连接...")
        console.log("   群组ID:", group.id, "群组名:", group.name)

        onSessionReset()
        chatClient.clearMessages()

        launch {
            try {
                val membersResponse = ApiClient.getGroupMembers(group.id)
                val members = membersResponse.members.sortedByDescending { it.id == group.hostId }
                onGroupMembersLoaded(members)
                console.log("✅ 群成员列表已加载，共 ${members.size} 人")
            } catch (e: dynamic) {
                console.error("❌ 加载群成员列表失败:", e.toString())
            }
        }

        try {
            console.log("🔌 开始连接WebSocket...")
            chatClient.connect(user.id, user.fullName, group.id)
            console.log("✅ WebSocket连接成功")
        } catch (e: dynamic) {
            console.error("❌ WebSocket连接失败:", e.toString())
        }
    }

    DisposableEffect(group.id) {
        onDispose {
            kotlinx.coroutines.MainScope().launch {
                try {
                    ApiClient.markGroupAsRead(user.id, group.id)
                    console.log("✅ 清理：已标记群组为已读")
                } catch (error: dynamic) {
                    console.warn("⚠️ 清理：标记群组已读失败:", error)
                }
            }
        }
    }

    LaunchedEffect(messagesSize, transientMessage, statusMessagesSize) {
        js("""
            setTimeout(function() {
                var messagesContainer = document.getElementById('messages');
                if (messagesContainer) {
                    messagesContainer.scrollTop = messagesContainer.scrollHeight;
                }
            }, 100);
        """)
    }
}

@Composable
private fun ChatAppHeader(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    group: Group,
    appState: WebAppState,
    chatClient: ChatClient,
    messages: List<Message>,
    strings: com.silk.shared.i18n.Strings,
    isSelectionMode: Boolean,
    selectedMessageIds: Set<String>,
    isExportingMarkdown: Boolean,
    exportMarkdownHint: String?,
    onSelectionModeChanged: (Boolean) -> Unit,
    onSelectedMessageIdsChanged: (Set<String>) -> Unit,
    onMessageForwardChanged: (Message?) -> Unit,
    onGroupTargetsLoaded: (List<Group>) -> Unit,
    onGroupTargetsLoadingChanged: (Boolean) -> Unit,
    onForwardDialogVisibilityChanged: (Boolean) -> Unit,
    onExportingChanged: (Boolean) -> Unit,
    onExportMarkdownHintChanged: (String?) -> Unit,
    onShowFolderExplorer: () -> Unit,
    onShowInvitationDialog: () -> Unit,
    onContactsLoaded: (List<Contact>) -> Unit,
    onGroupMembersLoaded: (List<GroupMember>) -> Unit,
    onContactsLoadingChanged: (Boolean) -> Unit,
    onShowAddMemberDialog: () -> Unit,
    onShowMembersDialog: () -> Unit,
) {
    Div({
        classes(SilkStylesheet.header)
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "12px")
        }
    }) {
        Button({
            style {
                padding(6.px, 12.px)
                backgroundColor(Color("rgba(255,255,255,0.15)"))
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", "pointer")
                fontSize(16.px)
                property("backdrop-filter", "blur(4px)")
                property("transition", "all 0.2s ease")
            }
            onClick {
                scope.launch {
                    handleChatAppBackNavigation(user, group, appState, chatClient)
                }
            }
        }) {
            Text("←")
        }

        Div({
            style {
                property("flex", "1")
                property("letter-spacing", "2px")
            }
        }) {
            Text(group.name)
        }

        if (isSelectionMode) {
            ChatAppSelectionToolbar(
                scope = scope,
                user = user,
                group = group,
                messages = messages,
                selectedMessageIds = selectedMessageIds,
                onSelectionModeChanged = onSelectionModeChanged,
                onSelectedMessageIdsChanged = onSelectedMessageIdsChanged,
                onMessageForwardChanged = onMessageForwardChanged,
                onGroupTargetsLoaded = onGroupTargetsLoaded,
                onGroupTargetsLoadingChanged = onGroupTargetsLoadingChanged,
                onForwardDialogVisibilityChanged = onForwardDialogVisibilityChanged,
            )
        } else {
            ChatAppActionToolbar(
                scope = scope,
                user = user,
                group = group,
                strings = strings,
                isExportingMarkdown = isExportingMarkdown,
                exportMarkdownHint = exportMarkdownHint,
                onExportingChanged = onExportingChanged,
                onExportMarkdownHintChanged = onExportMarkdownHintChanged,
                onShowFolderExplorer = onShowFolderExplorer,
                onShowInvitationDialog = onShowInvitationDialog,
                onContactsLoaded = onContactsLoaded,
                onGroupMembersLoaded = onGroupMembersLoaded,
                onContactsLoadingChanged = onContactsLoadingChanged,
                onShowAddMemberDialog = onShowAddMemberDialog,
                onShowMembersDialog = onShowMembersDialog,
            )
        }
    }
}

@Composable
private fun ChatAppSelectionToolbar(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    group: Group,
    messages: List<Message>,
    selectedMessageIds: Set<String>,
    onSelectionModeChanged: (Boolean) -> Unit,
    onSelectedMessageIdsChanged: (Set<String>) -> Unit,
    onMessageForwardChanged: (Message?) -> Unit,
    onGroupTargetsLoaded: (List<Group>) -> Unit,
    onGroupTargetsLoadingChanged: (Boolean) -> Unit,
    onForwardDialogVisibilityChanged: (Boolean) -> Unit,
) {
    val hasSelection = selectedMessageIds.isNotEmpty()
    Div({
        style {
            display(DisplayStyle.Flex)
            property("gap", "10px")
            alignItems(AlignItems.Center)
        }
    }) {
        Span({
            style {
                fontSize(13.px)
                color(Color.white)
                property("opacity", "0.9")
            }
        }) {
            Text("已选择 ${selectedMessageIds.size} 条")
        }

        Button({
            style {
                padding(8.px, 14.px)
                backgroundColor(Color(if (hasSelection) "rgba(255,255,255,0.25)" else "rgba(255,255,255,0.10)"))
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", if (hasSelection) "pointer" else "default")
                fontSize(13.px)
                property("transition", "all 0.2s ease")
            }
            if (hasSelection) {
                onClick {
                    val selectedContent = buildSelectedMessagesCopyText(messages, selectedMessageIds)
                    if (selectedContent.isNotEmpty()) {
                        copyTextToClipboard(selectedContent)
                    }
                    onSelectionModeChanged(false)
                    onSelectedMessageIdsChanged(emptySet())
                }
            }
        }) {
            Text("📋复制")
        }

        Button({
            style {
                padding(8.px, 14.px)
                backgroundColor(Color(if (hasSelection) "rgba(255,255,255,0.25)" else "rgba(255,255,255,0.10)"))
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", if (hasSelection) "pointer" else "default")
                fontSize(13.px)
                property("transition", "all 0.2s ease")
            }
            if (hasSelection) {
                onClick {
                    onMessageForwardChanged(buildSelectedMessagesForwardMessage(user, messages, selectedMessageIds))
                    scope.launch {
                        loadChatAppForwardTargets(
                            user = user,
                            currentGroupId = group.id,
                            includeWorkflowGroups = true,
                            onLoadingChanged = onGroupTargetsLoadingChanged,
                            onGroupsLoaded = onGroupTargetsLoaded,
                            onDialogVisibilityChanged = onForwardDialogVisibilityChanged,
                        )
                    }
                }
            }
        }) {
            Text("↗转发")
        }

        Button({
            style {
                padding(8.px, 14.px)
                backgroundColor(Color("rgba(255,255,255,0.15)"))
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", "pointer")
                fontSize(13.px)
                property("transition", "all 0.2s ease")
            }
            onClick {
                onSelectionModeChanged(false)
                onSelectedMessageIdsChanged(emptySet())
            }
        }) {
            Text("✕ 取消")
        }
    }
}

@Composable
private fun ChatAppActionToolbar(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    group: Group,
    strings: com.silk.shared.i18n.Strings,
    isExportingMarkdown: Boolean,
    exportMarkdownHint: String?,
    onExportingChanged: (Boolean) -> Unit,
    onExportMarkdownHintChanged: (String?) -> Unit,
    onShowFolderExplorer: () -> Unit,
    onShowInvitationDialog: () -> Unit,
    onContactsLoaded: (List<Contact>) -> Unit,
    onGroupMembersLoaded: (List<GroupMember>) -> Unit,
    onContactsLoadingChanged: (Boolean) -> Unit,
    onShowAddMemberDialog: () -> Unit,
    onShowMembersDialog: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("gap", "10px")
            alignItems(AlignItems.Center)
        }
    }) {
        Button({
            style {
                padding(10.px, 14.px)
                backgroundColor(Color("rgba(255,255,255,0.2)"))
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", "pointer")
                fontSize(18.px)
                property("backdrop-filter", "blur(4px)")
                property("transition", "all 0.2s ease")
            }
            onClick { onShowFolderExplorer() }
        }) {
            Text("📁")
        }

        ChatAppExportControls(
            scope = scope,
            user = user,
            group = group,
            isExportingMarkdown = isExportingMarkdown,
            exportMarkdownHint = exportMarkdownHint,
            onExportingChanged = onExportingChanged,
            onExportMarkdownHintChanged = onExportMarkdownHintChanged,
        )

        Button({
            style {
                padding(10.px, 14.px)
                backgroundColor(Color("rgba(255,255,255,0.15)"))
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", "pointer")
                fontSize(16.px)
                property("backdrop-filter", "blur(4px)")
                property("transition", "all 0.2s ease")
            }
            onClick { onShowInvitationDialog() }
        }) {
            Text(strings.inviteButton)
        }

        Button({
            style {
                padding(10.px, 14.px)
                backgroundColor(Color("rgba(255,255,255,0.2)"))
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", "pointer")
                fontSize(16.px)
                property("backdrop-filter", "blur(4px)")
                property("transition", "all 0.2s ease")
            }
            onClick {
                scope.launch {
                    loadChatAppContactsAndMembers(
                        user = user,
                        group = group,
                        onLoadingChanged = onContactsLoadingChanged,
                        onContactsLoaded = onContactsLoaded,
                        onGroupMembersLoaded = onGroupMembersLoaded,
                    )
                    onShowAddMemberDialog()
                }
            }
        }) {
            Text("➕")
        }

        Button({
            style {
                padding(10.px, 14.px)
                backgroundColor(Color("rgba(255,255,255,0.15)"))
                color(Color.white)
                border { width(0.px) }
                borderRadius(8.px)
                property("cursor", "pointer")
                fontSize(14.px)
                property("backdrop-filter", "blur(4px)")
                property("transition", "all 0.2s ease")
            }
            onClick {
                scope.launch {
                    loadChatAppContactsAndMembers(
                        user = user,
                        group = group,
                        onLoadingChanged = onContactsLoadingChanged,
                        onContactsLoaded = onContactsLoaded,
                        onGroupMembersLoaded = onGroupMembersLoaded,
                    )
                    onShowMembersDialog()
                }
            }
        }) {
            Text(strings.membersButton)
        }
    }
}

@Composable
private fun ChatAppExportControls(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    group: Group,
    isExportingMarkdown: Boolean,
    exportMarkdownHint: String?,
    onExportingChanged: (Boolean) -> Unit,
    onExportMarkdownHintChanged: (String?) -> Unit,
) {
    Button({
        style {
            padding(10.px, 14.px)
            backgroundColor(Color(if (isExportingMarkdown) "rgba(255,255,255,0.35)" else "rgba(255,255,255,0.2)"))
            color(Color.white)
            border { width(0.px) }
            borderRadius(8.px)
            property("cursor", if (isExportingMarkdown) "not-allowed" else "pointer")
            fontSize(16.px)
            property("backdrop-filter", "blur(4px)")
            property("transition", "all 0.2s ease")
            property("opacity", if (isExportingMarkdown) "0.85" else "1")
        }
        onClick {
            if (isExportingMarkdown) return@onClick
            scope.launch {
                exportChatAppMarkdown(
                    user = user,
                    group = group,
                    onExportingChanged = onExportingChanged,
                    onHintChanged = onExportMarkdownHintChanged,
                )
            }
        }
    }) {
        Text(if (isExportingMarkdown) "导出中..." else "📝")
    }

    exportMarkdownHint?.let { hint ->
        Span({
            style {
                fontSize(11.px)
                color(Color.white)
                property("max-width", "260px")
                property("overflow", "hidden")
                property("text-overflow", "ellipsis")
                property("white-space", "nowrap")
            }
            title(hint)
        }) {
            Text(hint)
        }
    }

    if (ObsidianVaultManager.isSupported()) {
        Span({
            style {
                fontSize(11.px)
                color(Color("rgba(255,255,255,0.6)"))
                property("cursor", "pointer")
                property("text-decoration", "underline")
                property("margin-left", "4px")
            }
            title("重新选择 Obsidian Vault 目录")
            onClick {
                scope.launch {
                    recoverSuspendNonCancellation(
                        block = {
                            ObsidianVaultManager.clearCachedHandle()
                            ObsidianVaultManager.pickVaultDirectory()
                            onExportMarkdownHintChanged("Vault 目录已更新")
                        },
                        recover = { error ->
                            if (error.message?.contains("abort", ignoreCase = true) != true) {
                                onExportMarkdownHintChanged("更换目录失败: ${error.message}")
                            }
                        },
                    )
                }
            }
        }) {
            Text("📂")
        }
    }
}

@Composable
private fun ChatAppConnectionBanner(
    scope: kotlinx.coroutines.CoroutineScope,
    connectionState: ConnectionState,
    strings: com.silk.shared.i18n.Strings,
    chatClient: ChatClient,
    user: User,
    group: Group,
) {
    if (connectionState == ConnectionState.CONNECTED) {
        return
    }

    Div({
        classes(SilkStylesheet.statusBar)
        style {
            property("background", when (connectionState) {
                ConnectionState.CONNECTED -> "linear-gradient(90deg, ${SilkColors.success}, #8DBE7C)"
                ConnectionState.CONNECTING -> "linear-gradient(90deg, ${SilkColors.warning}, #ECC88C)"
                ConnectionState.DISCONNECTED -> "linear-gradient(90deg, ${SilkColors.error}, #E99B9B)"
            })
            color(Color.white)
        }
    }) {
        Span {
            Text(when (connectionState) {
                ConnectionState.CONNECTING -> "⟳ ${strings.connecting}"
                ConnectionState.DISCONNECTED -> "✗ ${strings.disconnected}"
                ConnectionState.CONNECTED -> ""
            })
        }

        if (connectionState == ConnectionState.DISCONNECTED) {
            Button({
                classes(SilkStylesheet.button)
                style {
                    padding(8.px, 16.px)
                    fontSize(12.px)
                }
                onClick {
                    scope.launch {
                        chatClient.connect(user.id, user.fullName, group.id)
                    }
                }
            }) {
                Text(strings.reconnecting)
            }
        }
    }
}

@Composable
private fun ChatAppMessagePane(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    group: Group,
    messages: List<Message>,
    transientMessage: Message?,
    statusMessages: List<Message>,
    chatClient: ChatClient,
    recallingMessageIds: Set<String>,
    isSelectionMode: Boolean,
    selectedMessageIds: Set<String>,
    onRecallingMessageIdsChanged: (Set<String>) -> Unit,
    onMessageToForwardChanged: (Message?) -> Unit,
    onGroupTargetsLoaded: (List<Group>) -> Unit,
    onGroupTargetsLoadingChanged: (Boolean) -> Unit,
    onForwardDialogVisibilityChanged: (Boolean) -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit,
    onSelectedMessageIdsChanged: (Set<String>) -> Unit,
    onCaptureToKnowledgeBase: (Message) -> Unit,
) {
    val contextTrayStatus = statusMessages.lastOrNull(::isKnowledgeBaseContextStatusMessage)
    val visibleStatusMessages = statusMessages.filterNot { it.id == contextTrayStatus?.id }

    Div({
        classes(SilkStylesheet.messagesContainer)
        id("messages")
        style {
            property("position", "relative")
            property("transition", "all 0.2s ease")
        }
    }) {
        Div({
            style {
                property("flex", "1")
            }
        }) {}

        if (visibleStatusMessages.isNotEmpty()) {
            Div({
                style {
                    backgroundColor(Color("#F5F5F5"))
                    borderRadius(8.px)
                    padding(10.px, 14.px)
                    marginBottom(8.px)
                    property("border-left", "3px solid #9E9E9E")
                }
            }) {
                visibleStatusMessages.forEach { status ->
                    Div({
                        style {
                            color(Color("#757575"))
                            fontSize(13.px)
                            fontStyle("italic")
                            marginBottom(4.px)
                            property("white-space", "pre-wrap")
                            property("word-break", "break-word")
                        }
                    }) {
                        Text(status.content)
                    }
                }
            }
        }

        val lastMessageId = messages.lastOrNull()?.id
        messages.forEach { message ->
            MessageItem(
                message = message,
                isTransient = false,
                isLastMessage = message.id == lastMessageId,
                currentUserId = user.id,
                currentUserName = user.fullName,
                chatClient = chatClient,
                isRecalling = message.id in recallingMessageIds,
                onRecall = { messageId ->
                    if (messageId !in recallingMessageIds) {
                        onRecallingMessageIdsChanged(recallingMessageIds + messageId)
                        scope.launch {
                            try {
                                recoverSuspendNonCancellation(
                                    block = {
                                        val response = ApiClient.recallMessage(group.id, messageId, user.id)
                                        if (!response.success) {
                                            window.alert("撤回失败: ${response.message}")
                                        }
                                    },
                                    recover = { error ->
                                        console.error("❌ 撤回消息失败:", error)
                                        window.alert("撤回失败: ${error.message}")
                                    },
                                )
                            } finally {
                                onRecallingMessageIdsChanged(recallingMessageIds - messageId)
                            }
                        }
                    }
                },
                onCopy = { content ->
                    copyTextToClipboard(content)
                    console.log("✅ 消息已复制到剪贴板")
                },
                onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                onForward = { msg ->
                    onMessageToForwardChanged(msg)
                    scope.launch {
                        loadChatAppForwardTargets(
                            user = user,
                            currentGroupId = group.id,
                            includeWorkflowGroups = false,
                            onLoadingChanged = onGroupTargetsLoadingChanged,
                            onGroupsLoaded = onGroupTargetsLoaded,
                            onDialogVisibilityChanged = onForwardDialogVisibilityChanged,
                        )
                    }
                },
                onDelete = { messageId ->
                    scope.launch {
                        recoverSuspendNonCancellation(
                            block = {
                                val response = ApiClient.deleteMessage(group.id, messageId, user.id)
                                if (!response.success) {
                                    window.alert("删除失败: ${response.message}")
                                }
                            },
                            recover = { error ->
                                console.error("❌ 删除消息失败:", error)
                                window.alert("删除失败: ${error.message}")
                            },
                        )
                    }
                },
                isSelectionMode = isSelectionMode,
                isSelected = message.id in selectedMessageIds,
                onToggleSelection = { id ->
                    onSelectedMessageIdsChanged(
                        if (id in selectedMessageIds) selectedMessageIds - id else selectedMessageIds + id
                    )
                },
                onEnterSelectionMode = { id ->
                    onSelectionModeChanged(true)
                    onSelectedMessageIdsChanged(setOf(id))
                },
            )
        }

        transientMessage?.let { message ->
            if (shouldRenderInlineTransientMessage(message)) {
                MessageItem(
                    message = message.copy(category = com.silk.shared.models.MessageCategory.NORMAL),
                    isTransient = true,
                    currentUserId = user.id,
                    currentUserName = user.fullName,
                    chatClient = chatClient,
                    onCopy = { content ->
                        copyTextToClipboard(content)
                        console.log("✅ 消息已复制到剪贴板")
                    },
                    onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                    onForward = { msg ->
                        onMessageToForwardChanged(msg)
                        scope.launch {
                            loadChatAppForwardTargets(
                                user = user,
                                currentGroupId = group.id,
                                includeWorkflowGroups = false,
                                onLoadingChanged = onGroupTargetsLoadingChanged,
                                onGroupsLoaded = onGroupTargetsLoaded,
                                onDialogVisibilityChanged = onForwardDialogVisibilityChanged,
                            )
                        }
                    },
                )
            } else {
                TransientMessageItem(message)
            }
        }
    }
}

@Composable
private fun ChatAppDragAndDropEffect(group: Group, user: User) {
    DisposableEffect(group.id) {
        val sessionId = group.id
        val userId = user.id
        val uploadUrl = "${backendHttpOrigin()}/api/files/upload"
        val primaryColor = SilkColors.primary

        window.asDynamic().tempDragDropSessionId = sessionId
        window.asDynamic().tempDragDropUserId = userId
        window.asDynamic().tempDragDropUploadUrl = uploadUrl
        window.asDynamic().tempDragDropPrimaryColor = primaryColor

        js("""
            setTimeout(function() {
                var container = document.getElementById('messages');
                if (!container) {
                    console.error('❌ Drag-and-drop: messages container not found');
                    return;
                }
                console.log('✅ Drag-and-drop: messages container found');
                if (container._dragHandlers) {
                    container.removeEventListener('dragenter', container._dragHandlers.dragenter);
                    container.removeEventListener('dragover', container._dragHandlers.dragover);
                    container.removeEventListener('dragleave', container._dragHandlers.dragleave);
                    container.removeEventListener('drop', container._dragHandlers.drop);
                    if (container._dragHandlers.overlay && container._dragHandlers.overlay.parentNode) {
                        container._dragHandlers.overlay.parentNode.removeChild(container._dragHandlers.overlay);
                    }
                    delete container._dragHandlers;
                }
                var sessionId = window.tempDragDropSessionId;
                var userId = window.tempDragDropUserId;
                var uploadUrl = window.tempDragDropUploadUrl;
                var primaryColor = window.tempDragDropPrimaryColor;
                var overlay = document.createElement('div');
                overlay.id = 'drag-drop-overlay';
                overlay.style.cssText = 'position: absolute; top: 0; left: 0; right: 0; bottom: 0; ' +
                    'background: rgba(201, 168, 108, 0.1); display: none; ' +
                    'align-items: center; justify-content: center; z-index: 100; pointer-events: none; ' +
                    'border-radius: 8px;';
                var overlayContent = document.createElement('div');
                overlayContent.style.cssText = 'background: #FFFFFF; padding: 32px 48px; ' +
                    'border-radius: 16px; box-shadow: 0 8px 32px rgba(169, 137, 77, 0.3); ' +
                    'border: 2px solid ' + primaryColor + '; text-align: center;';
                overlayContent.innerHTML = '<div style="font-size: 48px; margin-bottom: 16px;">📎</div>' +
                    '<div style="font-size: 18px; color: ' + primaryColor + '; font-weight: 600; margin-bottom: 8px;">拖放文件到此区域上传</div>' +
                    '<div style="font-size: 14px; color: #8A7B6A;">释放文件即可上传</div>';
                overlay.appendChild(overlayContent);
                container.appendChild(overlay);
                var dragEnterCount = 0;
                var handleDragEnter = function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    dragEnterCount++;
                    console.log('📎 Drag enter, count:', dragEnterCount);
                    container.style.border = '3px dashed ' + primaryColor;
                    container.style.background = 'linear-gradient(135deg, rgba(224, 205, 160, 0.4) 0%, rgba(232, 213, 181, 0.4) 100%)';
                    container.style.boxShadow = 'inset 0 0 20px rgba(224, 205, 160, 0.6)';
                    overlay.style.display = 'flex';
                    overlay.style.alignItems = 'center';
                    overlay.style.justifyContent = 'center';
                };
                var handleDragOver = function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    if (event.dataTransfer) {
                        event.dataTransfer.dropEffect = 'copy';
                    }
                };
                var handleDragLeave = function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    dragEnterCount--;
                    if (dragEnterCount <= 0) {
                        dragEnterCount = 0;
                        container.style.border = '';
                        container.style.background = '';
                        container.style.boxShadow = '';
                        overlay.style.display = 'none';
                    }
                };
                var handleDrop = function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    dragEnterCount = 0;
                    container.style.border = '';
                    container.style.background = '';
                    container.style.boxShadow = '';
                    overlay.style.display = 'none';
                    var dataTransfer = event.dataTransfer;
                    if (!dataTransfer || !dataTransfer.files || dataTransfer.files.length === 0) {
                        return;
                    }
                    var file = dataTransfer.files[0];
                    console.log('📁 拖放文件: ' + file.name + ', 大小: ' + file.size);
                    var formData = new FormData();
                    formData.append('sessionId', sessionId);
                    formData.append('userId', userId);
                    formData.append('file', file);
                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', uploadUrl, true);
                    xhr.onload = function() {
                        if (xhr.status === 200) {
                            var response = JSON.parse(xhr.responseText);
                            console.log('✅ 上传成功: ' + response.fileName);
                            window.alert('文件上传成功: ' + response.fileName);
                        } else {
                            console.log('❌ 上传失败: ' + xhr.statusText);
                            window.alert('文件上传失败: ' + xhr.statusText);
                        }
                    };
                    xhr.onerror = function() {
                        console.log('❌ 上传错误');
                        window.alert('文件上传失败，请检查网络连接');
                    };
                    xhr.send(formData);
                };
                container.addEventListener('dragenter', handleDragEnter);
                container.addEventListener('dragover', handleDragOver);
                container.addEventListener('dragleave', handleDragLeave);
                container.addEventListener('drop', handleDrop);
                container._dragHandlers = {
                    dragenter: handleDragEnter,
                    dragover: handleDragOver,
                    dragleave: handleDragLeave,
                    drop: handleDrop,
                    overlay: overlay
                };
                console.log('✅ Drag-and-drop: handlers attached');
            }, 200);
        """)

        onDispose {
            js("""
                (function() {
                    var container = document.getElementById('messages');
                    if (container && container._dragHandlers) {
                        container.removeEventListener('dragenter', container._dragHandlers.dragenter);
                        container.removeEventListener('dragover', container._dragHandlers.dragover);
                        container.removeEventListener('dragleave', container._dragHandlers.dragleave);
                        container.removeEventListener('drop', container._dragHandlers.drop);
                        if (container._dragHandlers.overlay && container._dragHandlers.overlay.parentNode) {
                            container._dragHandlers.overlay.parentNode.removeChild(container._dragHandlers.overlay);
                        }
                        delete container._dragHandlers;
                    }
                })();
            """)
            window.asDynamic().tempDragDropSessionId = undefined
            window.asDynamic().tempDragDropUserId = undefined
            window.asDynamic().tempDragDropUploadUrl = undefined
            window.asDynamic().tempDragDropPrimaryColor = undefined
        }
    }
}

@Composable
private fun ChatAppInputSection(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    group: Group,
    strings: com.silk.shared.i18n.Strings,
    connectionState: ConnectionState,
    chatClient: ChatClient,
    statusMessages: List<Message>,
    sessionUsers: List<Pair<String, String>>,
    pendingQuestionId: String?,
    isGenerating: Boolean,
    isUploading: Boolean,
    isVoiceRecording: Boolean,
    isTranscribing: Boolean,
    mediaRecorderJs: dynamic,
    audioChunksJs: dynamic,
    messageText: String,
    kbContextSelection: KnowledgeBaseContextSelection,
    showMentionMenu: Boolean,
    mentionSearchText: String,
    mentionStartIndex: Int,
    mentionMenuPosition: Pair<Double, Double>,
    onMediaRecorderChanged: (dynamic) -> Unit,
    onAudioChunksChanged: (dynamic) -> Unit,
    onVoiceRecordingChanged: (Boolean) -> Unit,
    onTranscribingChanged: (Boolean) -> Unit,
    onMessageTextChanged: (String) -> Unit,
    onKnowledgeBaseContextSelectionChanged: (KnowledgeBaseContextSelection) -> Unit,
    onShowMentionMenuChanged: (Boolean) -> Unit,
    onMentionSearchTextChanged: (String) -> Unit,
    onMentionStartIndexChanged: (Int) -> Unit,
    onMentionMenuPositionChanged: (Pair<Double, Double>) -> Unit,
) {
    if (connectionState != ConnectionState.CONNECTED) {
        return
    }

    val sendMessage = {
        if (messageText.isNotBlank()) {
            val msg = messageText
            onMessageTextChanged("")
            scope.launch {
                chatClient.sendMessage(
                    user.id,
                    user.fullName,
                    msg,
                    kbContextSelection.takeIf(::hasKnowledgeBaseContextSelection),
                )
            }
        }
    }

    Div({
        classes(SilkStylesheet.inputContainer)
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("gap", "12px")
        }
    }) {
        KnowledgeBaseContextTray(
            statusMessages = statusMessages,
            selection = kbContextSelection,
            onSelectionChange = onKnowledgeBaseContextSelectionChanged,
        )
        ChatAppSilkShortcut(group = group, messageText = messageText, onMessageTextChanged = onMessageTextChanged)
        ChatAppTextInput(
            group = group,
            strings = strings,
            sessionUsers = sessionUsers,
            pendingQuestionId = pendingQuestionId,
            messageText = messageText,
            showMentionMenu = showMentionMenu,
            mentionSearchText = mentionSearchText,
            mentionStartIndex = mentionStartIndex,
            mentionMenuPosition = mentionMenuPosition,
            onMessageTextChanged = onMessageTextChanged,
            onShowMentionMenuChanged = onShowMentionMenuChanged,
            onMentionSearchTextChanged = onMentionSearchTextChanged,
            onMentionStartIndexChanged = onMentionStartIndexChanged,
            onMentionMenuPositionChanged = onMentionMenuPositionChanged,
        )
        ChatAppInputKeyHandler(messageText = messageText, onMessageTextChanged = onMessageTextChanged, sendMessage = sendMessage)
        ChatAppInputActions(
            scope = scope,
            user = user,
            strings = strings,
            chatClient = chatClient,
            isGenerating = isGenerating,
            isUploading = isUploading,
            isVoiceRecording = isVoiceRecording,
            isTranscribing = isTranscribing,
            mediaRecorderJs = mediaRecorderJs,
            audioChunksJs = audioChunksJs,
            messageText = messageText,
            onMediaRecorderChanged = onMediaRecorderChanged,
            onAudioChunksChanged = onAudioChunksChanged,
            onVoiceRecordingChanged = onVoiceRecordingChanged,
            onTranscribingChanged = onTranscribingChanged,
            onMessageTextChanged = onMessageTextChanged,
            sendMessage = sendMessage,
        )
    }
}

private fun isKnowledgeBaseContextStatusMessage(message: Message): Boolean {
    return message.category == com.silk.shared.models.MessageCategory.AGENT_STATUS &&
        message.references.any { it.kind == "available" && parseKnowledgeBaseDeepLink(it.path) != null }
}

internal fun hasKnowledgeBaseContextSelection(selection: KnowledgeBaseContextSelection): Boolean {
    return selection.pinnedEntryIds.isNotEmpty() || selection.excludedEntryIds.isNotEmpty()
}

internal fun togglePinnedKnowledgeBaseEntry(
    selection: KnowledgeBaseContextSelection,
    entryId: String,
): KnowledgeBaseContextSelection {
    val pinned = selection.pinnedEntryIds.toMutableList()
    val excluded = selection.excludedEntryIds.toMutableList()
    if (entryId in pinned) {
        pinned.removeAll { it == entryId }
    } else {
        pinned += entryId
        excluded.removeAll { it == entryId }
    }
    return KnowledgeBaseContextSelection(
        pinnedEntryIds = pinned.distinct(),
        excludedEntryIds = excluded.distinct(),
    )
}

internal fun toggleExcludedKnowledgeBaseEntry(
    selection: KnowledgeBaseContextSelection,
    entryId: String,
): KnowledgeBaseContextSelection {
    val pinned = selection.pinnedEntryIds.toMutableList()
    val excluded = selection.excludedEntryIds.toMutableList()
    if (entryId in excluded) {
        excluded.removeAll { it == entryId }
    } else {
        excluded += entryId
        pinned.removeAll { it == entryId }
    }
    return KnowledgeBaseContextSelection(
        pinnedEntryIds = pinned.distinct(),
        excludedEntryIds = excluded.distinct(),
    )
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun KnowledgeBaseContextTray(
    statusMessages: List<Message>,
    selection: KnowledgeBaseContextSelection,
    onSelectionChange: (KnowledgeBaseContextSelection) -> Unit,
) {
    val status = statusMessages.lastOrNull(::isKnowledgeBaseContextStatusMessage) ?: return
    val references = status.references.filter { it.kind == "available" && parseKnowledgeBaseDeepLink(it.path) != null }
    if (references.isEmpty()) return
    val pinnedCount = selection.pinnedEntryIds.size
    val excludedCount = selection.excludedEntryIds.size

    Div({
        style {
            property("border", "1px solid #E8E0D4")
            borderRadius(12.px)
            padding(12.px)
            property("background", "linear-gradient(135deg, #FFFBF2 0%, #FFF7E5 100%)")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "10px")
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                property("justify-content", "space-between")
                alignItems(AlignItems.Center)
                property("gap", "12px")
            }
        }) {
            Div {
                Div({
                    style {
                        fontSize(13.px)
                        color(Color("#8B7355"))
                        fontWeight("600")
                    }
                }) { Text("本轮 Context Tray") }
                Div({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        marginTop(4.px)
                    }
                }) { Text(status.content) }
                if (pinnedCount > 0 || excludedCount > 0) {
                    Div({
                        style {
                            fontSize(12.px)
                            color(Color(SilkColors.textSecondary))
                            marginTop(4.px)
                        }
                    }) {
                        Text("下轮偏好：固定 $pinnedCount，排除 $excludedCount")
                    }
                }
            }
            ContextTrayBadge("KB ${references.size}", SilkColors.primary)
        }

        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "8px")
            }
        }) {
            references.forEach { ref ->
                val kbLink = parseKnowledgeBaseDeepLink(ref.path)
                val entryId = kbLink?.entryId
                val isPinned = entryId != null && entryId in selection.pinnedEntryIds
                val isExcluded = entryId != null && entryId in selection.excludedEntryIds
                Button({
                    style {
                        backgroundColor(Color("#FFFFFF"))
                        border(0.px)
                        borderRadius(10.px)
                        padding(10.px, 12.px)
                        property("cursor", "pointer")
                        property("text-align", "left")
                        property("box-shadow", "0 1px 0 rgba(201, 168, 108, 0.14)")
                    }
                    onClick {
                        kbLink?.let { openKnowledgeBaseEntryLink(entryId = it.entryId, topicId = it.topicId) }
                    }
                }) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            property("justify-content", "space-between")
                            alignItems(AlignItems.Center)
                            property("gap", "10px")
                        }
                    }) {
                        Div({
                            style {
                                fontSize(13.px)
                                color(Color(SilkColors.textPrimary))
                                fontWeight("600")
                                property("flex", "1")
                                property("min-width", "0")
                                property("word-break", "break-word")
                            }
                        }) { Text("[available:${ref.index}] ${ref.title}") }
                        ContextTrayBadge(
                            label = when (ref.origin) {
                                "manual" -> "手动"
                                "pin" -> "固定"
                                else -> "自动"
                            },
                            accent = when (ref.origin) {
                                "manual" -> SilkColors.success
                                "pin" -> SilkColors.primaryDark
                                else -> SilkColors.info
                            },
                        )
                    }
                    ref.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Div({
                            style {
                                fontSize(12.px)
                                color(Color(SilkColors.textSecondary))
                                marginTop(6.px)
                            }
                        }) { Text("加入原因：$reason") }
                    }
                    ref.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                        Div({
                            style {
                                fontSize(12.px)
                                color(Color(SilkColors.textLight))
                                marginTop(6.px)
                                property("display", "-webkit-box")
                                property("-webkit-line-clamp", "2")
                                property("-webkit-box-orient", "vertical")
                                property("overflow", "hidden")
                                property("word-break", "break-word")
                            }
                        }) { Text(snippet) }
                    }
                    entryId?.let { resolvedEntryId ->
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                property("flex-wrap", "wrap")
                                property("gap", "8px")
                                marginTop(8.px)
                            }
                        }) {
                            ContextTrayActionButton(
                                label = if (isPinned) "取消固定" else "固定下轮",
                                accent = SilkColors.primaryDark,
                            ) {
                                onSelectionChange(togglePinnedKnowledgeBaseEntry(selection, resolvedEntryId))
                            }
                            if (ref.origin != "manual") {
                                ContextTrayActionButton(
                                    label = if (isExcluded) "恢复自动" else "排除下轮",
                                    accent = if (isExcluded) SilkColors.info else SilkColors.textSecondary,
                                ) {
                                    onSelectionChange(toggleExcludedKnowledgeBaseEntry(selection, resolvedEntryId))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextTrayBadge(label: String, accent: String) {
    Span({
        style {
            backgroundColor(Color("rgba(201, 168, 108, 0.12)"))
            color(Color(accent))
            borderRadius(999.px)
            padding(3.px, 8.px)
            fontSize(11.px)
            fontWeight("600")
            property("white-space", "nowrap")
        }
    }) { Text(label) }
}

@Composable
private fun ContextTrayActionButton(
    label: String,
    accent: String,
    onClick: () -> Unit,
) {
    Button({
        style {
            backgroundColor(Color("#FFFDF8"))
            color(Color(accent))
            property("border", "1px solid rgba(201, 168, 108, 0.28)")
            borderRadius(999.px)
            padding(4.px, 10.px)
            fontSize(11.px)
            fontWeight("600")
            property("cursor", "pointer")
        }
        onClick {
            it.stopPropagation()
            onClick()
        }
    }) { Text(label) }
}

@Composable
private fun ChatAppSilkShortcut(
    group: Group,
    messageText: String,
    onMessageTextChanged: (String) -> Unit,
) {
    if (group.name.startsWith("[Silk]")) {
        return
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("justify-content", "flex-start")
            property("gap", "8px")
            alignItems(AlignItems.Center)
        }
    }) {
        Button({
            style {
                padding(6.px, 12.px)
                backgroundColor(Color("rgba(201, 168, 108, 0.15)"))
                color(Color(SilkColors.primary))
                border {
                    width(1.px)
                    style(LineStyle.Solid)
                    color(Color(SilkColors.primary))
                }
                borderRadius(16.px)
                property("cursor", "pointer")
                fontSize(13.px)
                property("font-weight", "500")
                property("transition", "all 0.2s ease")
                property("white-space", "nowrap")
            }
            onClick {
                val input = document.getElementById("chat-input") as? org.w3c.dom.HTMLTextAreaElement
                if (input != null) {
                    val cursorPos = input.selectionStart ?: messageText.length
                    val beforeCursor = messageText.substring(0, cursorPos)
                    val afterCursor = messageText.substring(cursorPos)
                    onMessageTextChanged("$beforeCursor@Silk $afterCursor")
                    window.setTimeout({
                        val newPos = cursorPos + 6
                        input.setSelectionRange(newPos, newPos)
                        input.focus()
                    }, 0)
                } else {
                    onMessageTextChanged(
                        if (messageText.isEmpty() || messageText.endsWith(" ")) "${messageText}@Silk "
                        else "${messageText} @Silk "
                    )
                }
            }
        }) {
            Text("@Silk")
        }
    }
}

@Composable
private fun ChatAppTextInput(
    group: Group,
    strings: com.silk.shared.i18n.Strings,
    sessionUsers: List<Pair<String, String>>,
    pendingQuestionId: String?,
    messageText: String,
    showMentionMenu: Boolean,
    mentionSearchText: String,
    mentionStartIndex: Int,
    mentionMenuPosition: Pair<Double, Double>,
    onMessageTextChanged: (String) -> Unit,
    onShowMentionMenuChanged: (Boolean) -> Unit,
    onMentionSearchTextChanged: (String) -> Unit,
    onMentionStartIndexChanged: (Int) -> Unit,
    onMentionMenuPositionChanged: (Pair<Double, Double>) -> Unit,
) {
    Div({
        style {
            property("position", "relative")
            width(100.percent)
        }
    }) {
        TextArea {
            classes(SilkStylesheet.input)
            value(messageText)
            onInput { event ->
                val newValue = event.value
                val oldValue = messageText
                onMessageTextChanged(newValue)

                if (newValue.length > oldValue.length && newValue.lastOrNull() == '@') {
                    val input = document.getElementById("chat-input") as? org.w3c.dom.HTMLElement
                    if (input != null) {
                        val rect = input.getBoundingClientRect()
                        onMentionMenuPositionChanged(Pair(rect.left, window.innerHeight - rect.top + 4))
                    }
                    onShowMentionMenuChanged(true)
                    onMentionStartIndexChanged(newValue.length - 1)
                    onMentionSearchTextChanged("")
                }

                if (showMentionMenu && mentionStartIndex >= 0) {
                    val textAfterAt = newValue.substring(mentionStartIndex + 1)
                    val spaceIndex = textAfterAt.indexOf(' ')
                    if (spaceIndex >= 0) {
                        onShowMentionMenuChanged(false)
                    } else {
                        onMentionSearchTextChanged(textAfterAt)
                    }
                }
            }
            attr("placeholder", when {
                pendingQuestionId != null -> "回答 Claude Code 的问题..."
                group.name.startsWith("[Silk]") -> strings.silkChatInputPlaceholder
                else -> strings.messageInputPlaceholder
            })
            attr("rows", "2")
            attr("id", "chat-input")
            style {
                width(100.percent)
                property("box-sizing", "border-box")
                property("resize", "none")
            }
        }

        if (showMentionMenu) {
            ChatAppMentionMenu(
                sessionUsers = sessionUsers,
                mentionSearchText = mentionSearchText,
                mentionStartIndex = mentionStartIndex,
                mentionMenuPosition = mentionMenuPosition,
                messageText = messageText,
                onMessageTextChanged = onMessageTextChanged,
                onShowMentionMenuChanged = onShowMentionMenuChanged,
                onMentionStartIndexChanged = onMentionStartIndexChanged,
                strings = strings,
            )
        }
    }
}

@Composable
private fun ChatAppMentionMenu(
    sessionUsers: List<Pair<String, String>>,
    mentionSearchText: String,
    mentionStartIndex: Int,
    mentionMenuPosition: Pair<Double, Double>,
    messageText: String,
    onMessageTextChanged: (String) -> Unit,
    onShowMentionMenuChanged: (Boolean) -> Unit,
    onMentionStartIndexChanged: (Int) -> Unit,
    strings: com.silk.shared.i18n.Strings,
) {
    Div({
        style {
            property("position", "fixed")
            property("left", "${mentionMenuPosition.first}px")
            property("bottom", "${mentionMenuPosition.second}px")
            backgroundColor(Color(SilkColors.surface))
            border {
                width(1.px)
                style(LineStyle.Solid)
                color(Color(SilkColors.border))
            }
            borderRadius(8.px)
            property("box-shadow", "0 4px 12px rgba(0,0,0,0.15)")
            property("z-index", "9999")
            property("max-height", "200px")
            property("overflow-y", "auto")
            property("min-width", "200px")
        }
    }) {
        val filteredUsers = sessionUsers.filter { (_, name) ->
            mentionSearchText.isEmpty() || name.lowercase().contains(mentionSearchText.lowercase())
        }

        if (filteredUsers.isEmpty()) {
            Div({
                style {
                    padding(12.px, 16.px)
                    color(Color(SilkColors.textSecondary))
                    fontSize(14.px)
                }
            }) {
                Text(strings.noMatchingUsers)
            }
        } else {
            filteredUsers.forEach { (userId, userName) ->
                Div({
                    style {
                        padding(10.px, 16.px)
                        property("cursor", "pointer")
                        property("transition", "background-color 0.15s ease")
                    }
                    onClick {
                        val beforeAt = messageText.substring(0, mentionStartIndex)
                        val displayName = if (isAgentUserId(userId)) "Silk" else userName
                        onMessageTextChanged("$beforeAt@$displayName ")
                        onShowMentionMenuChanged(false)
                        onMentionStartIndexChanged(-1)
                        window.setTimeout({
                            val input = document.getElementById("chat-input")
                            input?.asDynamic()?.focus()
                        }, 0)
                    }
                    onMouseEnter {
                        (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = SilkColors.secondary
                    }
                    onMouseLeave {
                        (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = "transparent"
                    }
                }) {
                    Span({
                        style {
                            fontSize(14.px)
                            color(Color(SilkColors.textPrimary))
                            if (isAgentUserId(userId)) {
                                property("font-weight", "600")
                            }
                        }
                    }) {
                        Text(userName)
                    }
                    if (isAgentUserId(userId)) {
                        Span({
                            style {
                                fontSize(12.px)
                                color(Color(SilkColors.textSecondary))
                                marginLeft(8.px)
                            }
                        }) {
                            Text("(设置AI角色)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatAppInputKeyHandler(
    messageText: String,
    onMessageTextChanged: (String) -> Unit,
    sendMessage: () -> Unit,
) {
    DisposableEffect(messageText) {
        val handler: (dynamic) -> Unit = { event: dynamic ->
            val key = event.key as? String
            val shiftKey = event.shiftKey as? Boolean ?: false
            val isComposing = (event.isComposing as? Boolean) ?: false

            if (key == "Enter" && !shiftKey && !isComposing) {
                event.preventDefault()
                sendMessage()
            } else if (key == "Enter" && shiftKey) {
                event.preventDefault()
                val input = js("document.getElementById('chat-input')")
                val start = input.selectionStart as? Int ?: messageText.length
                val end = input.selectionEnd as? Int ?: start
                val before = messageText.substring(0, start)
                val after = messageText.substring(end)
                onMessageTextChanged("$before\n$after")
                window.setTimeout({
                    val newPos = start + 1
                    input.setSelectionRange(newPos, newPos)
                }, 0)
            }
        }

        val input = js("document.getElementById('chat-input')")
        input?.addEventListener("keydown", handler)
        onDispose { input?.removeEventListener("keydown", handler) }
    }
}

@Composable
private fun ChatAppInputActions(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    strings: com.silk.shared.i18n.Strings,
    chatClient: ChatClient,
    isGenerating: Boolean,
    isUploading: Boolean,
    isVoiceRecording: Boolean,
    isTranscribing: Boolean,
    mediaRecorderJs: dynamic,
    audioChunksJs: dynamic,
    messageText: String,
    onMediaRecorderChanged: (dynamic) -> Unit,
    onAudioChunksChanged: (dynamic) -> Unit,
    onVoiceRecordingChanged: (Boolean) -> Unit,
    onTranscribingChanged: (Boolean) -> Unit,
    onMessageTextChanged: (String) -> Unit,
    sendMessage: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("justify-content", "flex-end")
            property("gap", "10px")
            alignItems(AlignItems.Center)
        }
    }) {
        ChatAppUploadButton(isUploading = isUploading, inputId = "folder-upload-input", idleText = "📁", loadingText = "⏳", title = "上传整个目录")
        ChatAppUploadButton(isUploading = isUploading, inputId = "file-upload-input", idleText = "📎", loadingText = "⏳", title = "上传单个文件")
        ChatAppVoiceActionButton(
            scope = scope,
            mediaRecorderJs = mediaRecorderJs,
            audioChunksJs = audioChunksJs,
            messageText = messageText,
            isVoiceRecording = isVoiceRecording,
            isTranscribing = isTranscribing,
            onMediaRecorderChanged = onMediaRecorderChanged,
            onAudioChunksChanged = onAudioChunksChanged,
            onVoiceRecordingChanged = onVoiceRecordingChanged,
            onTranscribingChanged = onTranscribingChanged,
            onMessageTextChanged = onMessageTextChanged,
        )

        if (isGenerating) {
            Button({
                style {
                    padding(12.px, 24.px)
                    backgroundColor(Color("#FF4D4F"))
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    property("cursor", "pointer")
                    fontSize(14.px)
                    property("font-weight", "600")
                    property("transition", "all 0.2s ease")
                }
                onClick {
                    chatClient.stopGeneration(user.id, user.fullName)
                }
            }) {
                Text(strings.stopButton)
            }
        } else {
            Button({
                classes(SilkStylesheet.button)
                onClick { sendMessage() }
            }) {
                Text(strings.sendButton)
            }
        }
    }
}

@Composable
private fun ChatAppUploadButton(
    isUploading: Boolean,
    inputId: String,
    idleText: String,
    loadingText: String,
    title: String,
) {
    Button({
        style {
            padding(12.px, 14.px)
            backgroundColor(Color(SilkColors.secondary))
            color(Color(SilkColors.textPrimary))
            border { width(0.px) }
            borderRadius(8.px)
            property("cursor", if (isUploading) "not-allowed" else "pointer")
            fontSize(18.px)
            property("transition", "all 0.2s ease")
            property("opacity", if (isUploading) "0.6" else "1")
        }
        attr("title", title)
        onClick {
            if (!isUploading) {
                document.getElementById(inputId)?.asDynamic()?.click()
            }
        }
    }) {
        Text(if (isUploading) loadingText else idleText)
    }
}

@Composable
private fun ChatAppVoiceActionButton(
    scope: kotlinx.coroutines.CoroutineScope,
    mediaRecorderJs: dynamic,
    audioChunksJs: dynamic,
    messageText: String,
    isVoiceRecording: Boolean,
    isTranscribing: Boolean,
    onMediaRecorderChanged: (dynamic) -> Unit,
    onAudioChunksChanged: (dynamic) -> Unit,
    onVoiceRecordingChanged: (Boolean) -> Unit,
    onTranscribingChanged: (Boolean) -> Unit,
    onMessageTextChanged: (String) -> Unit,
) {
    when {
        isTranscribing -> {
            Button({
                style {
                    padding(12.px, 14.px)
                    backgroundColor(Color(SilkColors.secondary))
                    color(Color(SilkColors.textSecondary))
                    border { width(0.px) }
                    borderRadius(8.px)
                    fontSize(14.px)
                    property("cursor", "not-allowed")
                    property("opacity", "0.7")
                }
            }) {
                Text("识别中...")
            }
        }

        isVoiceRecording -> {
            Button({
                style {
                    padding(12.px, 14.px)
                    backgroundColor(Color("#FF4D4F"))
                    color(Color.white)
                    border { width(0.px) }
                    borderRadius(8.px)
                    property("cursor", "pointer")
                    fontSize(14.px)
                    property("font-weight", "600")
                    property("transition", "all 0.2s ease")
                }
                attr("title", "停止录音并识别")
                onClick {
                    onVoiceRecordingChanged(false)
                    try {
                        val recorder = mediaRecorderJs
                        if (recorder != null) {
                            recorder.stop()
                        }
                    } catch (e: dynamic) {
                        console.log("停止录音失败:", e)
                        onTranscribingChanged(false)
                    }
                }
            }) {
                Text("⏹ 停止")
            }
        }

        else -> {
            Button({
                style {
                    padding(12.px, 14.px)
                    backgroundColor(Color(SilkColors.secondary))
                    color(Color(SilkColors.textPrimary))
                    border { width(0.px) }
                    borderRadius(8.px)
                    property("cursor", "pointer")
                    fontSize(18.px)
                    property("transition", "all 0.2s ease")
                }
                attr("title", "语音输入")
                onClick {
                    scope.launch {
                        startChatAppVoiceRecording(
                            audioChunksJs = audioChunksJs,
                            messageText = messageText,
                            onMediaRecorderChanged = onMediaRecorderChanged,
                            onAudioChunksChanged = onAudioChunksChanged,
                            onVoiceRecordingChanged = onVoiceRecordingChanged,
                            onTranscribingChanged = onTranscribingChanged,
                            onMessageTextChanged = onMessageTextChanged,
                        )
                    }
                }
            }) {
                Text("🎤")
            }
        }
    }
}

private suspend fun startChatAppVoiceRecording(
    audioChunksJs: dynamic,
    messageText: String,
    onMediaRecorderChanged: (dynamic) -> Unit,
    onAudioChunksChanged: (dynamic) -> Unit,
    onVoiceRecordingChanged: (Boolean) -> Unit,
    onTranscribingChanged: (Boolean) -> Unit,
    onMessageTextChanged: (String) -> Unit,
) {
    try {
        console.log("[ASR] 请求麦克风...")
        val stream = jsGetUserMedia().unsafeCast<kotlin.js.Promise<dynamic>>().await()
        console.log("[ASR] 获取到音频流")
        val chunks = jsNewArray()
        onAudioChunksChanged(chunks)
        val recorder = jsCreateRecorder(stream)
        recorder.ondataavailable = { event: dynamic ->
            chunks.push(event.data)
            Unit
        }
        recorder.onstop = {
            console.log("[ASR] 录音已停止，开始转写...")
            onTranscribingChanged(true)
            kotlinx.coroutines.MainScope().launch {
                try {
                    recoverSuspendNonCancellation(
                        block = {
                            val chunkSource = audioChunksJs ?: chunks
                            val blob = jsCreateBlob(chunkSource)
                            val arrayBuffer = jsBlobToArrayBuffer(blob).unsafeCast<kotlin.js.Promise<dynamic>>().await()
                            val base64 = jsArrayBufferToBase64(arrayBuffer) as String
                            console.log("[ASR] base64 长度:", base64.length)
                            val result = ApiClient.transcribeAudio(base64, "webm")
                            console.log("[ASR] 结果: success=${result.success}, text=${result.text.take(50)}")
                            if (result.success && result.text.isNotBlank()) {
                                onMessageTextChanged(
                                    if (messageText.isNotBlank()) "$messageText ${result.text}" else result.text
                                )
                            } else {
                                console.log("[ASR] 失败:", result.error ?: "未知错误")
                            }
                        },
                        recover = { error ->
                            console.log("[ASR] 识别出错:", error)
                        },
                    )
                } finally {
                    onTranscribingChanged(false)
                    try {
                        jsStopTracks(stream)
                    } catch (error: dynamic) {
                        console.warn("[ASR] 停止音轨失败:", error)
                    }
                    console.log("[ASR] 流程结束")
                }
            }
            Unit
        }
        onMediaRecorderChanged(recorder)
        recorder.start()
        onVoiceRecordingChanged(true)
        console.log("[ASR] 开始录音")
    } catch (e: dynamic) {
        console.log("[ASR] 无法启动录音:", e)
    }
}

@Composable
private fun ChatAppDialogsAndUploads(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    group: Group,
    appState: WebAppState,
    chatClient: ChatClient,
    strings: com.silk.shared.i18n.Strings,
    showForwardDialog: Boolean,
    messageToForward: Message?,
    userGroups: List<Group>,
    isLoadingGroups: Boolean,
    forwardResult: String?,
    showInvitationDialog: Boolean,
    showAddMemberDialog: Boolean,
    contacts: List<Contact>,
    groupMembers: List<GroupMember>,
    isLoadingContacts: Boolean,
    addMemberResult: String?,
    showMembersDialog: Boolean,
    selectedMemberForInvite: GroupMember?,
    isInvitingMember: Boolean,
    inviteMemberResult: String?,
    showFolderExplorer: Boolean,
    onForwardDialogVisibilityChanged: (Boolean) -> Unit,
    onMessageToForwardChanged: (Message?) -> Unit,
    onForwardResultChanged: (String?) -> Unit,
    onInvitationDialogVisibilityChanged: (Boolean) -> Unit,
    onAddMemberDialogVisibilityChanged: (Boolean) -> Unit,
    onGroupMembersLoaded: (List<GroupMember>) -> Unit,
    onAddMemberResultChanged: (String?) -> Unit,
    onMembersDialogVisibilityChanged: (Boolean) -> Unit,
    onSelectedMemberForInviteChanged: (GroupMember?) -> Unit,
    onInvitingMemberChanged: (Boolean) -> Unit,
    onInviteMemberResultChanged: (String?) -> Unit,
    onShowFolderExplorerChanged: (Boolean) -> Unit,
) {
    if (showForwardDialog && messageToForward != null) {
        ChatAppForwardDialog(
            scope = scope,
            user = user,
            group = group,
            messageToForward = messageToForward,
            userGroups = userGroups,
            isLoadingGroups = isLoadingGroups,
            forwardResult = forwardResult,
            onForwardDialogVisibilityChanged = onForwardDialogVisibilityChanged,
            onMessageToForwardChanged = onMessageToForwardChanged,
            onForwardResultChanged = onForwardResultChanged,
        )
    }

    if (showInvitationDialog) {
        InvitationDialog(
            group = group,
            strings = strings,
            onDismiss = { onInvitationDialogVisibilityChanged(false) },
        )
    }

    if (showAddMemberDialog) {
        AddMemberDialog(
            contacts = contacts,
            groupMembers = groupMembers,
            isLoading = isLoadingContacts,
            result = addMemberResult,
            strings = strings,
            onAddMember = { contact ->
                scope.launch {
                    val response = ApiClient.addMemberToGroup(group.id, contact.contactId)
                    onAddMemberResultChanged(
                        if (response.success) {
                            val membersResponse = ApiClient.getGroupMembers(group.id)
                            onGroupMembersLoaded(membersResponse.members.sortedByDescending { it.id == group.hostId })
                            strings.memberAdded.replace("{name}", contact.contactName)
                        } else {
                            "❌ ${response.message}"
                        }
                    )
                }
            },
            onDismiss = {
                onAddMemberDialogVisibilityChanged(false)
                onAddMemberResultChanged(null)
            },
        )
    }

    if (showMembersDialog) {
        MembersDialog(
            members = groupMembers,
            contacts = contacts,
            currentUserId = user.id,
            isLoading = isLoadingContacts,
            strings = strings,
            onMemberClick = { member ->
                if (contacts.any { it.contactId == member.id }) {
                    scope.launch {
                        onMembersDialogVisibilityChanged(false)
                        openPrivateChatFromChatMembers(user, member, appState, chatClient)
                    }
                } else {
                    onSelectedMemberForInviteChanged(member)
                }
            },
            onDismiss = {
                onMembersDialogVisibilityChanged(false)
                onSelectedMemberForInviteChanged(null)
                onInviteMemberResultChanged(null)
            },
        )
    }

    selectedMemberForInvite?.let { member ->
        ChatAppInviteContactDialog(
            scope = scope,
            user = user,
            member = member,
            strings = strings,
            isInvitingMember = isInvitingMember,
            inviteMemberResult = inviteMemberResult,
            onSelectedMemberForInviteChanged = onSelectedMemberForInviteChanged,
            onInvitingMemberChanged = onInvitingMemberChanged,
            onInviteMemberResultChanged = onInviteMemberResultChanged,
        )
    }

    ChatAppHiddenUploadInputs(group = group, user = user)

    if (showFolderExplorer) {
        FolderExplorerDialog(
            groupId = group.id,
            strings = strings,
            onDismiss = { onShowFolderExplorerChanged(false) },
        )
    }
}

@Composable
private fun ChatAppForwardDialog(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    group: Group,
    messageToForward: Message,
    userGroups: List<Group>,
    isLoadingGroups: Boolean,
    forwardResult: String?,
    onForwardDialogVisibilityChanged: (Boolean) -> Unit,
    onMessageToForwardChanged: (Message?) -> Unit,
    onForwardResultChanged: (String?) -> Unit,
) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.6)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1100")
            property("backdrop-filter", "blur(4px)")
        }
        onClick {
            onForwardDialogVisibilityChanged(false)
            onMessageToForwardChanged(null)
            onForwardResultChanged(null)
        }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(16.px)
                padding(28.px)
                width(400.px)
                maxWidth(90.vw)
                property("max-height", "70vh")
                property("overflow-y", "auto")
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
            }
            onClick { it.stopPropagation() }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    alignItems(AlignItems.Center)
                    marginBottom(16.px)
                }
            }) {
                Span({
                    style {
                        fontSize(18.px)
                        property("font-weight", "bold")
                        color(Color(SilkColors.primary))
                    }
                }) { Text("💬 转发到对话") }
                Span({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) { Text("1 条消息") }
            }

            Div({
                style {
                    backgroundColor(Color("#F5F5F5"))
                    borderRadius(8.px)
                    padding(12.px)
                    marginBottom(16.px)
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                    property("max-height", "60px")
                    property("overflow", "hidden")
                }
            }) {
                Text("${messageToForward.userName}: ${messageToForward.content.take(80)}${if (messageToForward.content.length > 80) "..." else ""}")
            }

            forwardResult?.let { result ->
                Div({
                    style {
                        textAlign("center")
                        marginBottom(12.px)
                        fontSize(14.px)
                        color(if (result.contains("✅")) Color("#10B981") else Color("#EF4444"))
                    }
                }) { Text(result) }
            }

            when {
                isLoadingGroups -> ChatAppForwardDialogPlaceholder("加载中...")
                userGroups.isEmpty() -> ChatAppForwardDialogPlaceholder("没有其他对话可转发")
                else -> {
                    userGroups.forEach { targetGroup ->
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                padding(12.px)
                                borderRadius(8.px)
                                property("cursor", "pointer")
                                property("transition", "background-color 0.2s")
                            }
                            onClick {
                                scope.launch {
                                    onForwardResultChanged(null)
                                    val success = ApiClient.sendMessageToGroup(
                                        groupId = targetGroup.id,
                                        userId = user.id,
                                        userName = user.fullName,
                                        content = "📨 转发自【${group.name}】:\n\n${messageToForward.userName}: ${messageToForward.content}",
                                    )
                                    if (success) {
                                        onForwardResultChanged("✅ 已转发到 ${targetGroup.name}")
                                        kotlinx.coroutines.delay(1000)
                                        onForwardDialogVisibilityChanged(false)
                                        onMessageToForwardChanged(null)
                                        onForwardResultChanged(null)
                                    } else {
                                        onForwardResultChanged("❌ 转发失败")
                                    }
                                }
                            }
                        }) {
                            Div({
                                style {
                                    property("width", "40px")
                                    property("height", "40px")
                                    borderRadius(50.percent)
                                    backgroundColor(Color(SilkColors.primary))
                                    display(DisplayStyle.Flex)
                                    justifyContent(JustifyContent.Center)
                                    alignItems(AlignItems.Center)
                                    property("margin-right", "12px")
                                    property("flex-shrink", "0")
                                }
                            }) {
                                Span({
                                    style {
                                        color(Color("#FFFFFF"))
                                        fontSize(16.px)
                                        property("font-weight", "bold")
                                    }
                                }) { Text(targetGroup.name.take(1)) }
                            }
                            Span({
                                style {
                                    fontSize(15.px)
                                    color(Color(SilkColors.textPrimary))
                                }
                            }) { Text(targetGroup.name) }
                        }
                    }
                }
            }

            Div({
                style {
                    marginTop(16.px)
                    textAlign("center")
                }
            }) {
                Span({
                    style {
                        fontSize(14.px)
                        color(Color(SilkColors.textSecondary))
                        property("cursor", "pointer")
                        padding(8.px, 24.px)
                        borderRadius(8.px)
                        backgroundColor(Color("#F5F5F5"))
                    }
                    onClick {
                        onForwardDialogVisibilityChanged(false)
                        onMessageToForwardChanged(null)
                        onForwardResultChanged(null)
                    }
                }) { Text("取消") }
            }
        }
    }
}

@Composable
private fun ChatAppForwardDialogPlaceholder(text: String) {
    Div({
        style {
            textAlign("center")
            padding(20.px)
            color(Color(SilkColors.textSecondary))
        }
    }) { Text(text) }
}

@Composable
private fun ChatAppInviteContactDialog(
    scope: kotlinx.coroutines.CoroutineScope,
    user: User,
    member: GroupMember,
    strings: com.silk.shared.i18n.Strings,
    isInvitingMember: Boolean,
    inviteMemberResult: String?,
    onSelectedMemberForInviteChanged: (GroupMember?) -> Unit,
    onInvitingMemberChanged: (Boolean) -> Unit,
    onInviteMemberResultChanged: (String?) -> Unit,
) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.6)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1100")
            property("backdrop-filter", "blur(4px)")
        }
        onClick {
            onSelectedMemberForInviteChanged(null)
            onInviteMemberResultChanged(null)
        }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(16.px)
                padding(28.px)
                width(380.px)
                maxWidth(90.vw)
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                property("border", "1px solid ${SilkColors.border}")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({
                style {
                    color(Color(SilkColors.primary))
                    marginBottom(20.px)
                    fontSize(18.px)
                    property("font-weight", "600")
                    textAlign("center")
                }
            }) {
                Text(strings.addContact)
            }

            Div({
                style {
                    textAlign("center")
                    marginBottom(20.px)
                    color(Color(SilkColors.textPrimary))
                }
            }) {
                Text(strings.memberNotInContacts.replace("{name}", member.fullName))
                Br()
                Text(strings.sendContactRequestQuestion)
            }

            inviteMemberResult?.let { result ->
                Div({
                    style {
                        textAlign("center")
                        marginBottom(16.px)
                        color(
                            if (result.contains(strings.contactRequestSent) || result.contains("✅")) Color("#10B981")
                            else Color("#EF4444")
                        )
                        fontSize(14.px)
                    }
                }) {
                    Text(result)
                }
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.Center)
                    gap(12.px)
                }
            }) {
                Button({
                    style {
                        backgroundColor(Color(SilkColors.background))
                        color(Color(SilkColors.textSecondary))
                        border {
                            width(1.px)
                            style(LineStyle.Solid)
                            color(Color(SilkColors.border))
                        }
                        padding(10.px, 20.px)
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                    }
                    onClick {
                        onSelectedMemberForInviteChanged(null)
                        onInviteMemberResultChanged(null)
                    }
                }) {
                    Text(strings.cancelButton)
                }

                Button({
                    style {
                        backgroundColor(Color(SilkColors.primary))
                        color(Color.white)
                        border { style(LineStyle.None) }
                        padding(10.px, 20.px)
                        borderRadius(8.px)
                        property("cursor", if (isInvitingMember) "not-allowed" else "pointer")
                        property("opacity", if (isInvitingMember) "0.6" else "1")
                        fontSize(14.px)
                        property("font-weight", "500")
                    }
                    onClick {
                        if (!isInvitingMember && inviteMemberResult == null) {
                            scope.launch {
                                onInvitingMemberChanged(true)
                                val response = ApiClient.sendContactRequestById(user.id, member.id)
                                onInviteMemberResultChanged(
                                    if (response.success) "✅ ${strings.contactRequestSent}"
                                    else "❌ ${response.message}"
                                )
                                onInvitingMemberChanged(false)

                                if (response.success) {
                                    kotlinx.coroutines.delay(1500)
                                    onSelectedMemberForInviteChanged(null)
                                    onInviteMemberResultChanged(null)
                                }
                            }
                        }
                    }
                }) {
                    Text(if (isInvitingMember) strings.sendingRequest else strings.sendRequest)
                }
            }
        }
    }
}

@Composable
private fun ChatAppHiddenUploadInputs(group: Group, user: User) {
    org.jetbrains.compose.web.dom.Input(org.jetbrains.compose.web.attributes.InputType.File) {
        id("file-upload-input")
        style { display(DisplayStyle.None) }
        attr("accept", "*/*")
        attr("multiple", "false")
        onChange {
            val sessionId = group.id
            val userId = user.id
            val uploadUrl = "${backendHttpOrigin()}/api/files/upload"

            js("""
                (function() {
                    var input = document.getElementById('file-upload-input');
                    if (input && input.files && input.files.length > 0) {
                        var file = input.files[0];
                        console.log('📁 选择文件: ' + file.name + ', 大小: ' + file.size);
                        var formData = new FormData();
                        formData.append('sessionId', sessionId);
                        formData.append('userId', userId);
                        formData.append('file', file);
                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', uploadUrl, true);
                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                var response = JSON.parse(xhr.responseText);
                                console.log('✅ 上传成功: ' + response.fileName);
                                window.alert('文件上传成功: ' + response.fileName);
                            } else {
                                console.log('❌ 上传失败: ' + xhr.statusText);
                                window.alert('文件上传失败: ' + xhr.statusText);
                            }
                        };
                        xhr.onerror = function() {
                            console.log('❌ 上传错误');
                            window.alert('文件上传失败，请检查网络连接');
                        };
                        xhr.send(formData);
                        input.value = '';
                    }
                })();
            """)
        }
    }

    org.jetbrains.compose.web.dom.Input(org.jetbrains.compose.web.attributes.InputType.File) {
        id("folder-upload-input")
        style { display(DisplayStyle.None) }
        attr("webkitdirectory", "true")
        attr("directory", "true")
        attr("multiple", "true")
        onChange {
            val sessionId = group.id
            val userId = user.id
            val uploadUrl = "${backendHttpOrigin()}/api/files/upload"

            js("""
                (function() {
                    var input = document.getElementById('folder-upload-input');
                    if (!input || !input.files || input.files.length === 0) return;
                    var supportedExtensions = [
                        '.txt', '.md', '.markdown', '.json', '.xml', '.html', '.htm', '.css',
                        '.yaml', '.yml', '.csv', '.log', '.ini', '.conf', '.cfg',
                        '.js', '.ts', '.jsx', '.tsx', '.kt', '.kts', '.java', '.py', '.pyw',
                        '.c', '.cpp', '.cc', '.h', '.hpp', '.cs', '.go', '.rs', '.rb',
                        '.php', '.swift', '.scala', '.groovy', '.lua', '.r', '.m', '.mm',
                        '.sh', '.bash', '.zsh', '.ps1', '.bat', '.cmd',
                        '.sql', '.graphql', '.proto',
                        '.pdf'
                    ];
                    var files = input.files;
                    var filesToUpload = [];
                    for (var i = 0; i < files.length; i++) {
                        var file = files[i];
                        var ext = '.' + file.name.split('.').pop().toLowerCase();
                        if (supportedExtensions.indexOf(ext) !== -1) {
                            filesToUpload.push(file);
                        }
                    }
                    if (filesToUpload.length === 0) {
                        window.alert('所选目录中没有支持的文件类型');
                        input.value = '';
                        return;
                    }
                    console.log('📁 准备上传 ' + filesToUpload.length + ' 个文件（共 ' + files.length + ' 个文件）');
                    window.alert('准备上传 ' + filesToUpload.length + ' 个文件...');
                    var uploaded = 0;
                    var failed = 0;
                    function uploadNext(index) {
                        if (index >= filesToUpload.length) {
                            window.alert('上传完成！成功: ' + uploaded + ', 失败: ' + failed);
                            input.value = '';
                            return;
                        }
                        var file = filesToUpload[index];
                        console.log('📤 上传 (' + (index + 1) + '/' + filesToUpload.length + '): ' + file.name);
                        var formData = new FormData();
                        formData.append('sessionId', sessionId);
                        formData.append('userId', userId);
                        formData.append('file', file);
                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', uploadUrl, true);
                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                uploaded++;
                                console.log('✅ (' + uploaded + ') ' + file.name);
                            } else {
                                failed++;
                                console.log('❌ ' + file.name + ': ' + xhr.statusText);
                            }
                            uploadNext(index + 1);
                        };
                        xhr.onerror = function() {
                            failed++;
                            console.log('❌ 网络错误: ' + file.name);
                            uploadNext(index + 1);
                        };
                        xhr.send(formData);
                    }
                    uploadNext(0);
                })();
            """)
        }
    }
}

private suspend fun handleChatAppBackNavigation(
    user: User,
    group: Group,
    appState: WebAppState,
    chatClient: ChatClient,
) {
    console.log("👈 用户点击返回按钮")
    try {
        console.log("🔌 正在断开WebSocket...")
        chatClient.disconnect()
        console.log("✅ WebSocket已断开")
    } catch (e: dynamic) {
        console.log("ℹ️ WebSocket断开失败（忽略错误）:", e)
    }

    kotlinx.coroutines.delay(300)

    try {
        ApiClient.markGroupAsRead(user.id, group.id)
        console.log("✅ 已标记群组为已读")
    } catch (e: dynamic) {
        console.log("⚠️ 标记已读失败:", e)
    }

    console.log("📋 返回到群组列表")
    appState.navigateBack()
}

private suspend fun exportChatAppMarkdown(
    user: User,
    group: Group,
    onExportingChanged: (Boolean) -> Unit,
    onHintChanged: (String?) -> Unit,
) {
    onExportingChanged(true)
    onHintChanged("正在导出...")
    try {
        recoverSuspendNonCancellation(
            block = {
                var vaultHandle: dynamic = null
                if (ObsidianVaultManager.isSupported()) {
                    vaultHandle = ObsidianVaultManager.getCachedHandleIfValid()
                    if (vaultHandle == null) {
                        onHintChanged("请选择 Obsidian Vault 目录...")
                        vaultHandle = ObsidianVaultManager.pickVaultDirectory()
                    }
                }

                onHintChanged("正在获取聊天记录...")
                val result = ApiClient.exportGroupMarkdown(group.id, user.id)
                if (!result.success) {
                    onHintChanged("导出失败：${result.message}")
                    window.alert("导出失败：${result.message}")
                    return@recoverSuspendNonCancellation
                }
                val fileName = result.fileName.ifBlank { "silk_group_${group.id}.md" }

                if (vaultHandle != null) {
                    onHintChanged("正在写入 Vault...")
                    recoverSuspendNonCancellation(
                        block = {
                            val relativePath = ObsidianVaultManager.saveToVault(
                                vaultHandle, group.name, result.markdown, fileName
                            )
                            console.log("✅ 已导出到 Obsidian Vault:", relativePath)
                            onHintChanged("已导出: $relativePath")
                        },
                        recover = { error ->
                            console.warn("Vault 写入失败，回退到下载:", error)
                            downloadAsFile(result.markdown, fileName)
                            onHintChanged("Vault写入失败，已下载：$fileName")
                        },
                    )
                } else {
                    downloadAsFile(result.markdown, fileName)
                    console.log("✅ 聊天记录已导出:", fileName)
                    onHintChanged("导出成功：$fileName")
                }
            },
            recover = { error ->
                val msg = error.message ?: error.toString()
                if (msg.contains("abort", ignoreCase = true)) {
                    onHintChanged("已取消")
                } else {
                    console.error("❌ 导出异常:", error)
                    onHintChanged("导出异常: $msg")
                    window.alert("导出失败: $msg")
                }
            },
        )
    } finally {
        onExportingChanged(false)
    }
}

private suspend fun loadChatAppContactsAndMembers(
    user: User,
    group: Group,
    onLoadingChanged: (Boolean) -> Unit,
    onContactsLoaded: (List<Contact>) -> Unit,
    onGroupMembersLoaded: (List<GroupMember>) -> Unit,
) {
    onLoadingChanged(true)
    try {
        val contactsResponse = ApiClient.getContacts(user.id)
        onContactsLoaded(contactsResponse.contacts ?: emptyList())
        val membersResponse = ApiClient.getGroupMembers(group.id)
        onGroupMembersLoaded(membersResponse.members.sortedByDescending { it.id == group.hostId })
    } finally {
        onLoadingChanged(false)
    }
}

private suspend fun loadChatAppForwardTargets(
    user: User,
    currentGroupId: String,
    includeWorkflowGroups: Boolean,
    onLoadingChanged: (Boolean) -> Unit,
    onGroupsLoaded: (List<Group>) -> Unit,
    onDialogVisibilityChanged: (Boolean) -> Unit,
) {
    onLoadingChanged(true)
    try {
        val response = ApiClient.getUserGroups(user.id)
        val groups = (response.groups ?: emptyList()).filter { candidate ->
            candidate.id != currentGroupId && (includeWorkflowGroups || !candidate.name.startsWith("wf_"))
        }
        onGroupsLoaded(groups)
        onDialogVisibilityChanged(true)
    } finally {
        onLoadingChanged(false)
    }
}

private suspend fun openPrivateChatFromChatMembers(
    user: User,
    member: GroupMember,
    appState: WebAppState,
    chatClient: ChatClient,
) {
    try {
        chatClient.disconnect()
    } catch (error: dynamic) {
        console.warn("⚠️ 跳转私聊前断开 WebSocket 失败:", error)
    }

    val response = ApiClient.startPrivateChat(user.id, member.id)
    val targetGroup = response.group
    if (response.success && targetGroup != null) {
        appState.selectGroup(targetGroup)
    } else {
        console.log("❌ 无法创建对话: ${response.message}")
    }
}

private fun buildSelectedMessagesCopyText(messages: List<Message>, selectedMessageIds: Set<String>): String =
    messages
        .filter { it.id in selectedMessageIds }
        .sortedBy { it.timestamp }
        .joinToString("\n\n") { "${it.userName}:\n${it.content}" }

private fun buildSelectedMessagesForwardMessage(
    user: User,
    messages: List<Message>,
    selectedMessageIds: Set<String>,
): Message = Message(
    id = "forward-multi",
    userId = user.id,
    userName = user.fullName,
    content = messages
        .filter { it.id in selectedMessageIds }
        .sortedBy { it.timestamp }
        .joinToString("\n\n") { "[${it.userName}] ${it.content}" },
    type = MessageType.TEXT,
    timestamp = js("Date.now()").unsafeCast<Double>().toLong(),
)

@Composable
fun FolderExplorerDialog(
    groupId: String,
    strings: com.silk.shared.i18n.Strings,
    onDismiss: () -> Unit
) {
    var files by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var processedUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(groupId) {
        val apiUrl = "${backendHttpOrigin()}/api/files/list/$groupId"
        isLoading = true
        errorMessage = null
        try {
            recoverSuspendNonCancellation(
                block = {
                    console.log("📁 请求文件列表:", apiUrl)
                    val response = window.fetch(apiUrl).await()
                    check(response.ok) { "HTTP ${response.status}" }
                    val body = response.text().await()
                    val parsed = parseWebFolderContents(body)
                    files = parsed.files
                    processedUrls = parsed.processedUrls
                    console.log("📁 加载完成:", files.size, "文件,", processedUrls.size, "URL")
                },
                recover = { error ->
                    console.error("❌ 获取文件列表失败:", error)
                    errorMessage = error.message ?: "获取失败"
                },
            )
        } finally {
            isLoading = false
        }
    }
    
    // 对话框背景遮罩
    Div({
        style {
            property("position", "fixed")
            property("top", "0")
            property("left", "0")
            property("right", "0")
            property("bottom", "0")
            backgroundColor(Color("rgba(0,0,0,0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
        }
        onClick { onDismiss() }
    }) {
        // 对话框内容
        Div({
            style {
                backgroundColor(Color(SilkColors.surface))
                borderRadius(16.px)
                padding(24.px)
                property("min-width", "400px")
                property("max-width", "600px")
                property("max-height", "70vh")
                property("box-shadow", "0 8px 32px rgba(0,0,0,0.2)")
                display(DisplayStyle.Flex)
                property("flex-direction", "column")
            }
            onClick { it.stopPropagation() }
        }) {
            // 标题栏
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    alignItems(AlignItems.Center)
                    marginBottom(16.px)
                    paddingBottom(12.px)
                    property("border-bottom", "1px solid ${SilkColors.border}")
                }
            }) {
                H3({
                    style {
                        margin(0.px)
                        color(Color(SilkColors.textPrimary))
                        fontSize(18.px)
                    }
                }) {
                    Text(strings.sessionFiles)
                }
                
                // 关闭按钮
                Button({
                    style {
                        backgroundColor(Color("transparent"))
                        border { width(0.px) }
                        fontSize(20.px)
                        property("cursor", "pointer")
                        color(Color(SilkColors.textSecondary))
                    }
                    onClick { onDismiss() }
                }) {
                    Text("✕")
                }
            }
            
            // 文件列表区域
            Div({
                style {
                    property("flex", "1")
                    property("overflow-y", "auto")
                    property("min-height", "200px")
                }
            }) {
                if (isLoading) {
                    Div({
                        style {
                            property("text-align", "center")
                            padding(40.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) {
                        Text("⏳ ${strings.loading}")
                    }
                } else if (errorMessage != null) {
                    Div({
                        style {
                            property("text-align", "center")
                            padding(40.px)
                            color(Color(SilkColors.error))
                        }
                    }) {
                        Text("❌ $errorMessage")
                    }
                } else if (files.isEmpty() && processedUrls.isEmpty()) {
                    Div({
                        style {
                            property("text-align", "center")
                            padding(40.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) {
                        Text(strings.noFilesYet)
                        Br()
                        Span({
                            style {
                                fontSize(13.px)
                                marginTop(8.px)
                                display(DisplayStyle.Block)
                            }
                        }) {
                            Text(strings.useBottomButtonToUpload)
                        }
                    }
                } else {
                    // 1️⃣ 首先显示已下载的 URL 清单
                    if (processedUrls.isNotEmpty()) {
                        Div({
                            style {
                                marginBottom(16.px)
                            }
                        }) {
                            // URL 清单标题
                            Div({
                                style {
                                    fontSize(14.px)
                                    fontWeight("600")
                                    color(Color(SilkColors.textSecondary))
                                    marginBottom(8.px)
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "6px")
                                }
                            }) {
                                Text("🔗 已下载的网页 (${processedUrls.size})")
                            }
                            
                            processedUrls.forEach { url ->
                                Div({
                                    style {
                                        display(DisplayStyle.Flex)
                                        justifyContent(JustifyContent.SpaceBetween)
                                        alignItems(AlignItems.Center)
                                        padding(10.px, 14.px)
                                        marginBottom(6.px)
                                        backgroundColor(Color("#F0FFF4"))  // 淡绿色背景
                                        borderRadius(8.px)
                                        property("border", "1px solid #C6F6D5")
                                    }
                                }) {
                                    // URL 信息
                                    Div({
                                        style {
                                            display(DisplayStyle.Flex)
                                            alignItems(AlignItems.Center)
                                            property("gap", "10px")
                                            property("flex", "1")
                                            property("overflow", "hidden")
                                        }
                                    }) {
                                        Span({ style { fontSize(16.px) } }) { Text("🌐") }
                                        A(href = url, {
                                            attr("target", "_blank")
                                            style {
                                                color(Color(SilkColors.primary))
                                                fontSize(13.px)
                                                property("text-decoration", "none")
                                                property("overflow", "hidden")
                                                property("text-overflow", "ellipsis")
                                                property("white-space", "nowrap")
                                            }
                                        }) {
                                            Text(url)
                                        }
                                    }
                                    // 状态标记
                                    Span({
                                        style {
                                            backgroundColor(Color("#48BB78"))
                                            color(Color("white"))
                                            padding(2.px, 8.px)
                                            borderRadius(10.px)
                                            fontSize(11.px)
                                        }
                                    }) {
                                        Text("✓ 已索引")
                                    }
                                }
                            }
                        }
                    }
                    
                    // 2️⃣ 然后显示上传的文件列表
                    if (files.isNotEmpty()) {
                        // 文件列表标题
                        if (processedUrls.isNotEmpty()) {
                            Div({
                                style {
                                    fontSize(14.px)
                                    fontWeight("600")
                                    color(Color(SilkColors.textSecondary))
                                    marginBottom(8.px)
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "6px")
                                }
                            }) {
                                Text("📁 上传的文件 (${files.size})")
                            }
                        }
                    }
                    
                    // 显示文件列表
                    files.forEach { file ->
                        val fileName = file.name.ifBlank { strings.unknownFile }
                        val fileSize = file.size
                        val downloadUrl = file.downloadUrl
                        
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(12.px, 16.px)
                                marginBottom(8.px)
                                backgroundColor(Color(SilkColors.surfaceElevated))
                                borderRadius(8.px)
                                property("border", "1px solid ${SilkColors.border}")
                                property("transition", "all 0.2s ease")
                            }
                        }) {
                            // 文件信息
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "12px")
                                }
                            }) {
                                // 文件图标
                                Span({
                                    style {
                                        fontSize(24.px)
                                    }
                                }) {
                                    Text(webFileIconForName(fileName))
                                }
                                
                                Div {
                                    Div({
                                        style {
                                            color(Color(SilkColors.textPrimary))
                                            property("font-weight", "500")
                                        }
                                    }) {
                                        Text(fileName)
                                    }
                                    Div({
                                        style {
                                            fontSize(12.px)
                                            color(Color(SilkColors.textSecondary))
                                            marginTop(2.px)
                                        }
                                    }) {
                                        Text(formatWebFileSize(fileSize))
                                    }
                                }
                            }
                            
                            // 下载按钮
                            Button({
                                style {
                                    padding(8.px, 16.px)
                                    backgroundColor(Color(SilkColors.primary))
                                    color(Color.white)
                                    border { width(0.px) }
                                    borderRadius(6.px)
                                    property("cursor", "pointer")
                                    fontSize(13.px)
                                    property("font-weight", "500")
                                    property("transition", "all 0.2s ease")
                                }
                                onClick {
                                    if (downloadUrl.isNotBlank()) {
                                        val fullUrl = "${backendHttpOrigin()}$downloadUrl"
                                        window.open(fullUrl, "_blank")
                                    }
                                }
                            }) {
                                Text("⬇️ ${strings.download}")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Markdown 渲染组件 ====================

@JsModule("markdown-it")
@JsNonModule
private external class MarkdownIt(options: dynamic = definedExternally) {
    fun render(src: String): String
    fun use(plugin: dynamic, options: dynamic = definedExternally): MarkdownIt
}

@JsModule("markdown-it-task-lists")
@JsNonModule
private external val markdownItTaskLists: dynamic

@JsModule("highlight.js")
@JsNonModule
private external val highlightJsModule: dynamic

@JsModule("dompurify")
@JsNonModule
private external val domPurifyModule: dynamic

@JsModule("katex/contrib/auto-render")
@JsNonModule
private external fun renderMathInElement(element: HTMLElement, options: dynamic = definedExternally)

@JsModule("katex/dist/katex.min.css")
@JsNonModule
private external val katexStylesheet: dynamic

@JsModule("github-markdown-css/github-markdown-light.css")
@JsNonModule
private external val githubMarkdownStylesheet: dynamic

@JsModule("highlight.js/styles/github-dark.css")
@JsNonModule
private external val highlightStylesheet: dynamic

private fun highlightJsGetLanguage(languageName: String): dynamic = highlightJsModule.getLanguage(languageName)

private fun highlightJsHighlight(code: String, options: dynamic): dynamic = highlightJsModule.highlight(code, options)

private fun highlightJsHighlightAuto(code: String): dynamic = highlightJsModule.highlightAuto(code)

private fun sanitizeHtml(dirty: String, config: dynamic): String = domPurifyModule.sanitize(dirty, config) as String

private const val MARKDOWN_RUNTIME_STYLE_ID = "silk-markdown-runtime-style"

private val silkMarkdownRuntimeCss = """
    .silk-markdown.markdown-body {
        color: #1E293B;
        background: transparent;
        font-size: 14px;
        line-height: 1.8;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        overflow-wrap: anywhere;
    }
    .silk-markdown.markdown-body > :first-child {
        margin-top: 0 !important;
    }
    .silk-markdown.markdown-body > :last-child {
        margin-bottom: 0 !important;
    }
    .silk-markdown.markdown-body p,
    .silk-markdown.markdown-body li,
    .silk-markdown.markdown-body td,
    .silk-markdown.markdown-body th {
        white-space: pre-wrap;
    }
    .silk-markdown.markdown-body pre {
        background: #0F172A !important;
        border-radius: 12px;
        padding: 14px 16px;
        overflow-x: auto;
    }
    .silk-markdown.markdown-body pre code {
        background: transparent !important;
        color: inherit !important;
        font-size: 13px;
    }
    .silk-markdown.markdown-body .hljs {
        color: #E5E7EB;
        background: transparent;
    }
    .silk-markdown.markdown-body :not(pre) > code {
        background: rgba(59, 130, 246, 0.10);
        color: #1D4ED8;
        border-radius: 6px;
        padding: 0.15em 0.45em;
        font-size: 0.92em;
    }
    .silk-markdown.markdown-body blockquote {
        color: #5D4E37;
        background: linear-gradient(180deg, rgba(201, 168, 108, 0.12), rgba(201, 168, 108, 0.04));
        border-left: 4px solid #C9A86C;
        border-radius: 0 12px 12px 0;
        padding: 12px 16px;
        margin-top: 12px;
        margin-bottom: 12px;
    }
    .silk-markdown.markdown-body blockquote blockquote {
        background: transparent;
        border-left: 2px solid #D4C5A0;
        margin-top: 8px;
        margin-bottom: 8px;
        padding: 4px 12px;
    }
    .silk-markdown.markdown-body table {
        display: block;
        width: max-content;
        max-width: 100%;
        overflow-x: auto;
        border-radius: 10px;
    }
    .silk-markdown.markdown-body table thead tr {
        background: #EFF6FF;
    }
    .silk-markdown.markdown-body table th,
    .silk-markdown.markdown-body table td {
        border: 1px solid #E2E8F0;
        padding: 8px 12px;
    }
    .silk-markdown.markdown-body hr {
        height: 1px;
        border: 0;
        background: linear-gradient(90deg, rgba(226, 232, 240, 0), rgba(148, 163, 184, 0.75), rgba(226, 232, 240, 0));
    }
    .silk-markdown.markdown-body .katex-display {
        overflow-x: auto;
        overflow-y: hidden;
        padding: 0.35rem 0.15rem;
    }
    .silk-markdown.markdown-body .task-list-item {
        list-style: none;
    }
    .silk-markdown.markdown-body .task-list-item-checkbox {
        margin: 0 0.5rem 0 0;
    }
    .silk-markdown.markdown-body img {
        max-width: 100%;
    }
    .silk-code-block {
        border-radius: 12px;
        overflow: hidden;
        margin: 0.5em 0;
    }
    .silk-code-block pre.hljs {
        border-radius: 0 !important;
        margin: 0 !important;
    }
    .silk-code-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        background: #151E2C;
        padding: 6px 16px;
        font-size: 12px;
        user-select: none;
    }
    .silk-code-lang {
        color: #7B8CA3;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        font-weight: 500;
    }
    .silk-code-copy {
        background: rgba(255, 255, 255, 0.08);
        border: 1px solid rgba(255, 255, 255, 0.12);
        color: #7B8CA3;
        font-size: 12px;
        padding: 2px 10px;
        border-radius: 4px;
        cursor: pointer;
        transition: all 0.2s;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    .silk-code-copy:hover {
        background: rgba(255, 255, 255, 0.16);
        color: #CBD5E1;
    }
    .silk-citation-chip {
        display: inline-block;
        padding: 1px 6px;
        margin: 0 2px;
        background-color: #FFF8ED;
        border: 1px solid #E8D5B5;
        border-radius: 4px;
        font-size: 12px;
        color: #C9A86C;
        text-decoration: none;
        cursor: pointer;
        font-weight: 500;
        line-height: 1.4;
        vertical-align: baseline;
    }
    .silk-citation-chip:hover {
        background-color: #FFF0D5;
        border-color: #C9A86C;
    }
    .silk-thinking-details {
        margin: 8px 0;
        background: #FAF8F4;
        border: 1px solid #E8E0D4;
        border-radius: 8px;
        overflow: hidden;
    }
    .silk-thinking-details summary {
        padding: 8px 12px;
        cursor: pointer;
        user-select: none;
        font-size: 12px;
        color: #8B7355;
        font-weight: 500;
    }
    .silk-thinking-details[open] summary {
        border-bottom: 1px solid #E8E0D4;
    }
    .silk-thinking-details > :not(summary) {
        padding: 8px 12px;
        font-size: 12px;
        color: #8B7355;
        line-height: 1.6;
        background: #FAF8F4;
    }
""".trimIndent()

@Suppress("UNUSED_EXPRESSION")
private fun ensureMarkdownAssetsLoaded() {
    githubMarkdownStylesheet
    katexStylesheet
    highlightStylesheet
}

private fun ensureMarkdownStylesInjected() {
    if (document.getElementById(MARKDOWN_RUNTIME_STYLE_ID) != null) return

    val styleElement = document.createElement("style") as HTMLElement
    styleElement.id = MARKDOWN_RUNTIME_STYLE_ID
    styleElement.textContent = silkMarkdownRuntimeCss
    document.head?.appendChild(styleElement)
}

private fun escapeHtml(raw: String): String {
    return raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private data class MathDelimiter(
    val open: String,
    val close: String
)

private data class MathBlockMatch(
    val renderedBlock: String,
    val nextCursor: Int,
)

private val mathDelimiters = listOf(
    MathDelimiter("$$", "$$"),
    MathDelimiter("\\[", "\\]"),
    MathDelimiter("\\(", "\\)")
)

private fun findMathBlockMatch(markdown: String, cursor: Int): MathBlockMatch? {
    return mathDelimiters.firstNotNullOfOrNull { delimiter ->
        if (!markdown.startsWith(delimiter.open, cursor)) {
            return@firstNotNullOfOrNull null
        }

        val contentStart = cursor + delimiter.open.length
        val closingIndex = markdown.indexOf(delimiter.close, contentStart)
        if (closingIndex == -1) {
            return@firstNotNullOfOrNull null
        }

        val innerContent = markdown.substring(contentStart, closingIndex)
            // markdown-it 会把数学环境中的 `\\` 吃成 `\`，这里先补一层转义。
            .replace("\\\\", "\\\\\\\\")
        MathBlockMatch(
            renderedBlock = delimiter.open + innerContent + delimiter.close,
            nextCursor = closingIndex + delimiter.close.length,
        )
    }
}

private fun normalizeMathBlocks(markdown: String): String {
    val output = StringBuilder()
    var cursor = 0

    while (cursor < markdown.length) {
        val mathBlock = findMathBlockMatch(markdown, cursor)
        if (mathBlock == null) {
            output.append(markdown[cursor])
            cursor += 1
        } else {
            output.append(mathBlock.renderedBlock)
            cursor = mathBlock.nextCursor
        }
    }

    return output.toString()
}

/**
 * Detect Markdown tables whose header row is missing (first table line is
 * the separator like `|:---|:---:|---:|`). Prepend a dummy header row with
 * empty cells so markdown-it recognises them as tables.
 */
private fun fixHeaderlessTables(markdown: String): String {
    val separatorPattern = Regex("""^\|[\s:]*-{2,}[\s:]*(\|[\s:]*-{2,}[\s:]*)*\|?\s*$""")
    val dataRowPattern = Regex("""^\|.+\|""")
    val lines = markdown.lines()
    val result = mutableListOf<String>()

    for (i in lines.indices) {
        val line = lines[i].trim()
        if (separatorPattern.matches(line)) {
            val prevIsHeader = i > 0 && dataRowPattern.containsMatchIn(lines[i - 1].trim())
                    && !separatorPattern.matches(lines[i - 1].trim())
            if (!prevIsHeader) {
                val colCount = line.split("|").count { it.contains("-") }
                val dummyHeader = (1..colCount).joinToString(" | ", "| ", " |") { " " }
                result.add(dummyHeader)
            }
        }
        result.add(lines[i])
    }
    return result.joinToString("\n")
}

private val codeFenceOpenerPattern = Regex("^(`{3,}|~{3,})(\\s*[\\w+#.-]*)?\\s*$")
private val inlineDanglingFencePattern = Regex("^(.+[^`\\s])`{3,}\\s*$")
private val trailingFencePattern = Regex("`{3,}\\s*$")

private fun findClosingFence(lines: List<String>, openerIndex: Int, fenceLen: Int, fenceChar: Char): Int {
    val closePattern = Regex("^\\s*${Regex.escape(fenceChar.toString())}{$fenceLen,}\\s*$")
    return ((openerIndex + 1) until lines.size).firstOrNull { closePattern.matches(lines[it]) } ?: -1
}

private fun likelyMarkdownProse(langTag: String, innerLines: List<String>): Boolean {
    val innerText = innerLines.joinToString("\n")
    val markdownSignals = listOf(
        innerText.contains(Regex("^#{1,6}\\s", RegexOption.MULTILINE)),
        innerText.contains(Regex("\\*\\*[^*]+\\*\\*")),
        innerText.contains(Regex("^[-*+·•]\\s+", RegexOption.MULTILINE)),
        innerText.contains(Regex("^\\|.+\\|\\s*$", RegexOption.MULTILINE))
    ).count { it }

    return (langTag.isEmpty() || langTag == "text" || langTag == "markdown") &&
            markdownSignals >= 2 &&
            innerLines.size >= 3
}

private fun handleClosedFence(lines: MutableList<String>, openerIndex: Int, closerIndex: Int, opener: MatchResult): Int {
    val langTag = opener.groupValues[2].trim()
    val innerLines = if (closerIndex > openerIndex + 1) {
        lines.subList(openerIndex + 1, closerIndex)
    } else {
        emptyList()
    }

    return if (likelyMarkdownProse(langTag, innerLines)) {
        lines.removeAt(closerIndex)
        lines.removeAt(openerIndex)
        openerIndex
    } else {
        closerIndex + 1
    }
}

private fun contentAfterFence(lines: List<String>, openerIndex: Int): String {
    return if (openerIndex + 1 < lines.size) {
        lines.subList(openerIndex + 1, lines.size).joinToString("\n")
    } else {
        ""
    }
}

private fun containsMarkdownSyntax(content: String): Boolean {
    return content.contains(Regex("^#{1,6}[\\s]", RegexOption.MULTILINE)) ||
            content.contains(Regex("^\\|.+\\|\\s*$", RegexOption.MULTILINE)) ||
            content.contains(Regex("^[-*+]\\s+", RegexOption.MULTILINE)) ||
            content.contains(Regex("\\*\\*[^*]+\\*\\*"))
}

private fun handleUnclosedFence(lines: MutableList<String>, openerIndex: Int, fenceLen: Int): Int {
    val contentAfter = contentAfterFence(lines, openerIndex)
    return if (containsMarkdownSyntax(contentAfter) || contentAfter.length > 500) {
        lines.removeAt(openerIndex)
        openerIndex
    } else {
        lines.add("`".repeat(fenceLen))
        lines.size
    }
}

private fun stripInlineDanglingFence(lines: MutableList<String>, index: Int) {
    if (inlineDanglingFencePattern.containsMatchIn(lines[index])) {
        lines[index] = lines[index].replace(trailingFencePattern, "")
    }
}

private fun fixOrphanCodeFences(markdown: String): String {
    val lines = markdown.split("\n").toMutableList()
    var idx = 0

    while (idx < lines.size) {
        val trimmed = lines[idx].trimStart()
        val opener = codeFenceOpenerPattern.find(trimmed)

        if (opener != null) {
            val fenceStr = opener.groupValues[1]
            val closerIdx = findClosingFence(lines, idx, fenceStr.length, fenceStr[0])

            idx = if (closerIdx >= 0) {
                handleClosedFence(lines, idx, closerIdx, opener)
            } else {
                handleUnclosedFence(lines, idx, fenceStr.length)
            }
        } else {
            stripInlineDanglingFence(lines, idx)
            idx++
        }
    }

    return lines.joinToString("\n")
}

private fun highlightCode(code: String, language: String): String {
    val normalizedLanguage = language
        .trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        ?.lowercase()
        .orEmpty()

    val dataLang = if (normalizedLanguage.isNotBlank()) """ data-lang="${escapeHtml(normalizedLanguage)}"""" else ""

    return try {
        val highlighted = if (normalizedLanguage.isNotBlank() && highlightJsGetLanguage(normalizedLanguage) != null) {
            val options = js("{}")
            options.language = normalizedLanguage
            options.ignoreIllegals = true
            highlightJsHighlight(code, options).value as String
        } else {
            highlightJsHighlightAuto(code).value as String
        }

        val className = if (normalizedLanguage.isNotBlank()) "language-${escapeHtml(normalizedLanguage)}" else ""
        """<pre class="hljs"$dataLang><code class="$className">$highlighted</code></pre>"""
    } catch (_: Throwable) {
        val safeLanguage = if (normalizedLanguage.isNotBlank()) """ class="language-${escapeHtml(normalizedLanguage)}"""" else ""
        """<pre class="hljs"$dataLang><code$safeLanguage>${escapeHtml(code)}</code></pre>"""
    }
}

@NoLiveLiterals
private fun createMarkdownEngine(): MarkdownIt {
    ensureMarkdownAssetsLoaded()

    val options = js("{}")
    options.html = true
    options.linkify = true
    options.typographer = true
    options.breaks = false
    options.highlight = { code: String, language: String ->
        highlightCode(code, language)
    }

    val taskListOptions = js("{}")
    taskListOptions.enabled = true
    taskListOptions.label = true
    taskListOptions.labelAfter = true

    return MarkdownIt(options).apply {
        use(markdownItTaskLists, taskListOptions)
    }
}

@NoLiveLiterals
private fun createSanitizeConfig(): dynamic {
    val config = js("{}")
    config.ADD_TAGS = arrayOf("input", "details", "summary")
    config.ADD_ATTR = arrayOf("checked", "disabled", "type", "class", "open")
    return config
}

@NoLiveLiterals
private fun createMathRenderOptions(): dynamic {
    fun delimiter(left: String, right: String, display: Boolean): dynamic {
        val value = js("{}")
        value.left = left
        value.right = right
        value.display = display
        return value
    }

    val options = js("{}")
    options.throwOnError = false
    options.strict = "ignore"
    options.ignoredTags = arrayOf("script", "noscript", "style", "textarea", "pre", "code", "option")
    options.delimiters = arrayOf(
        delimiter("$$", "$$", true),
        delimiter("\\[", "\\]", true),
        delimiter("$", "$", false),
        delimiter("\\(", "\\)", false)
    )
    return options
}

@Composable
private fun rememberMarkdownEngine(): MarkdownIt {
    return remember { createMarkdownEngine() }
}

private fun linkCitationMarkers(
    html: String,
    references: List<com.silk.shared.models.MessageReference>,
    anchorPrefix: String
): String {
    if (references.isEmpty()) return html
    val pattern = Regex("""\[(citation|available):(\d+)\]""")
    return pattern.replace(html) { match ->
        val kind = match.groupValues[1]
        val idx = match.groupValues[2].toIntOrNull() ?: return@replace match.value
        val ref = references.find { it.kind == kind && it.index == idx }
        if (ref != null) {
            val label = if (kind == "citation") "来源 $idx" else "资料 $idx"
            if (ref.url != null) {
                "<a href=\"${ref.url}\" target=\"_blank\" rel=\"noopener noreferrer\" class=\"silk-citation-chip\">$label</a>"
            } else if (parseKnowledgeBaseDeepLink(ref.path) != null) {
                val kbLink = parseKnowledgeBaseDeepLink(ref.path)!!
                "<a href=\"#\" class=\"silk-citation-chip silk-kb-link\" data-kb-entry-id=\"${kbLink.entryId}\">$label</a>"
            } else {
                "<a href=\"#${anchorPrefix}ref-$idx\" class=\"silk-citation-chip silk-citation-nav\" data-idx=\"$idx\">$label</a>"
            }
        } else {
            match.value
        }
    }
}

private const val THINKING_END_MARKER = "<!--THINKING_END-->"

private fun escapeHtmlText(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeInvalidTagStarts(markdown: String): String {
    return markdown.replace(Regex("<(?![a-zA-Z/!])"), "&lt;")
}

private fun contentWithThinkingDetails(content: String): String {
    if (!content.contains(THINKING_END_MARKER)) return escapeInvalidTagStarts(content)

    val markerIndex = content.indexOf(THINKING_END_MARKER)
    val thinkingText = content.substring(0, markerIndex).trim()
    val tailRaw = content.substring(markerIndex + THINKING_END_MARKER.length).trimStart('\n').trim()
    val tailEffective = if (tailRaw.isBlank()) "*（本次仅有思考过程或未生成正文，请重试。）*" else tailRaw
    val escapedThinking = escapeHtmlText(thinkingText).replace("\n", "<br>")

    return "<details class=\"silk-thinking-details\">\n" +
            "<summary>💭 思考过程</summary>\n" +
            escapedThinking + "\n</details>\n\n" +
            escapeInvalidTagStarts(tailEffective)
}

private fun prepareMarkdownForRendering(content: String): String {
    val reducedBlanks = contentWithThinkingDetails(content).replace(Regex("\\n{3,}"), "\n\n")
    val normalizedHeadings = reducedBlanks.replace(
        Regex("^(#{1,6})([^#\\s])", RegexOption.MULTILINE),
        "$1 $2"
    )
    val fixedTables = fixHeaderlessTables(normalizedHeadings)
    val fixedFences = fixOrphanCodeFences(fixedTables)
    return fixedFences
        .replace(Regex("""^>\s*(-{3,})\s*$""", RegexOption.MULTILINE), "\n$1")
        .replace(Regex("""^>\s*(\*\*Sources?:?\*\*)\s*$""", RegexOption.MULTILINE), "\n$1")
}

private fun renderMarkdownSafely(
    content: String,
    references: List<com.silk.shared.models.MessageReference>,
    referenceAnchorPrefix: String,
    markdownEngine: MarkdownIt
): String {
    return try {
        val rendered = markdownEngine.render(normalizeMathBlocks(prepareMarkdownForRendering(content)))
        val sanitized = sanitizeHtml(rendered, createSanitizeConfig())
        renderKnowledgeBaseMarkersInHtml(
            linkCitationMarkers(sanitized, references, referenceAnchorPrefix)
        )
    } catch (_: Throwable) {
        escapeHtmlText(content)
    }
}

private fun decorateMarkdownLinks(element: HTMLElement) {
    val links = element.querySelectorAll("a")
    for (index in 0 until links.length) {
        val link = links.item(index) as? HTMLAnchorElement
        val href = link?.getAttribute("href").orEmpty()
        if (link != null && !href.startsWith("#")) {
            link.target = "_blank"
            link.rel = "noopener noreferrer nofollow"
        }
    }
}

private fun copyCodeBlockToClipboard(pre: HTMLElement, copyBtn: HTMLElement) {
    val codeText = pre.querySelector("code")?.textContent ?: ""
    try {
        val clipboard = window.navigator.asDynamic().clipboard
        if (clipboard != null) {
            clipboard.writeText(codeText).then { _: dynamic ->
                copyBtn.textContent = "已复制 ✓"
                window.setTimeout({ copyBtn.textContent = "复制" }, 1500)
            }
        }
    } catch (_: Throwable) {
    }
}

private fun createCodeHeader(pre: HTMLElement): HTMLElement {
    val header = document.createElement("div") as HTMLElement
    header.className = "silk-code-header"

    val langSpan = document.createElement("span") as HTMLElement
    langSpan.className = "silk-code-lang"
    langSpan.textContent = pre.getAttribute("data-lang") ?: ""
    header.appendChild(langSpan)

    val copyBtn = document.createElement("button") as HTMLElement
    copyBtn.className = "silk-code-copy"
    copyBtn.textContent = "复制"
    copyBtn.addEventListener("click", { _ -> copyCodeBlockToClipboard(pre, copyBtn) })
    header.appendChild(copyBtn)

    return header
}

private fun wrapMarkdownCodeBlocks(element: HTMLElement) {
    val preBlocks = element.querySelectorAll("pre.hljs")
    for (preIdx in 0 until preBlocks.length) {
        val pre = preBlocks.item(preIdx) as? HTMLElement
        if (pre != null) {
            val wrapper = document.createElement("div") as HTMLElement
            wrapper.className = "silk-code-block"
            pre.parentNode?.insertBefore(wrapper, pre)
            wrapper.appendChild(createCodeHeader(pre))
            wrapper.appendChild(pre)
        }
    }
}

private fun renderMarkdownMath(element: HTMLElement) {
    recoverNonCancellation(
        block = {
            renderMathInElement(element, createMathRenderOptions())
        },
        recover = { error ->
            console.warn("Markdown math render failed:", error)
        },
    )
}

private fun updateMarkdownElement(containerId: String, safeHtml: String): HTMLElement? {
    val element = document.getElementById(containerId) as? HTMLElement ?: return null
    element.innerHTML = safeHtml
    decorateMarkdownLinks(element)
    attachKnowledgeBaseLinkHandlers(element)
    wrapMarkdownCodeBlocks(element)
    renderMarkdownMath(element)
    return element
}

@Composable
fun MarkdownContent(
    content: String,
    references: List<com.silk.shared.models.MessageReference> = emptyList(),
    referenceAnchorPrefix: String = ""
) {
    ensureMarkdownStylesInjected()

    val markdownEngine = rememberMarkdownEngine()
    val containerId = remember { "silk-markdown-${Random.nextInt(1_000_000)}" }
    val safeHtml = remember(content, references, referenceAnchorPrefix) {
        renderMarkdownSafely(content, references, referenceAnchorPrefix, markdownEngine)
    }

    Div({
        classes("markdown-body", "silk-markdown")
        id(containerId)
    }) { }

    DisposableEffect(containerId, safeHtml) {
        val element = updateMarkdownElement(containerId, safeHtml)
        onDispose {
            element?.innerHTML = ""
        }
    }
}

@Composable
fun ReferenceSourcesList(
    references: List<com.silk.shared.models.MessageReference>,
    anchorPrefix: String = ""
) {
    if (references.isEmpty()) return

    var isExpanded by remember { mutableStateOf(true) }

    Div({
        style {
            property("margin-top", "8px")
            property("border", "1px solid #E8E0D4")
            property("border-radius", "8px")
            property("background-color", "#FFFBF0")
            property("overflow", "hidden")
        }
    }) {
        Div({
            id("refs-toggle-$anchorPrefix")
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("padding", "8px 12px")
                property("cursor", "pointer")
                property("user-select", "none")
            }
            onClick { isExpanded = !isExpanded }
        }) {
            Span({ style { property("margin-right", "6px") } }) { Text("\uD83D\uDCDA") }
            Span({
                style {
                    fontSize(13.px)
                    color(Color("#8B7355"))
                    fontWeight("500")
                    property("flex", "1")
                }
            }) { Text("参考来源 (${references.size})") }
            Span({
                style {
                    fontSize(12.px)
                    color(Color("#C9A86C"))
                    fontWeight("500")
                }
            }) { Text(if (isExpanded) "收起 ▲" else "展开 ▼") }
        }

        if (isExpanded) {
            Div({
                style {
                    property("border-top", "1px solid #E8E0D4")
                    property("padding", "4px 12px")
                }
            }) {
                references.forEachIndexed { index, ref ->
                    Div({
                        id("${anchorPrefix}ref-${ref.index}")
                        style {
                            display(DisplayStyle.Flex)
                            property("gap", "8px")
                            property("padding", "6px 0")
                            if (index < references.size - 1) {
                                property("border-bottom", "1px solid #F0EBE0")
                            }
                        }
                    }) {
                        Span({
                            style {
                                fontSize(12.px)
                                color(Color("#C9A86C"))
                                fontWeight("500")
                                property("white-space", "nowrap")
                            }
                        }) {
                            Text(if (ref.kind == "citation") "来源 ${ref.index}" else "资料 ${ref.index}")
                        }
                        Div({ style { property("flex", "1"); property("min-width", "0") } }) {
                            if (ref.url != null) {
                                A(href = ref.url, {
                                    attr("target", "_blank")
                                    attr("rel", "noopener noreferrer")
                                    style {
                                        fontSize(13.px)
                                        color(Color("#2F80B7"))
                                        property("text-decoration", "none")
                                        property("word-break", "break-all")
                                    }
                                }) { Text(ref.title) }
                            } else if (parseKnowledgeBaseDeepLink(ref.path) != null) {
                                val kbLink = parseKnowledgeBaseDeepLink(ref.path)!!
                                Span({
                                    style {
                                        fontSize(13.px)
                                        color(Color("#2F80B7"))
                                        property("text-decoration", "underline")
                                        property("text-decoration-style", "dotted")
                                        property("cursor", "pointer")
                                        property("word-break", "break-all")
                                    }
                                    attr("title", "打开知识库文档")
                                    onClick { openKnowledgeBaseEntryLink(entryId = kbLink.entryId, topicId = kbLink.topicId) }
                                }) { Text(ref.title) }
                            } else {
                                Span({
                                    style { fontSize(13.px); color(Color("#333")) }
                                }) { Text(ref.title) }
                            }
                            val snippet = ref.snippet
                            if (snippet != null && snippet.isNotBlank()) {
                                Div({
                                    style {
                                        fontSize(12.px)
                                        color(Color("#999"))
                                        marginTop(2.px)
                                        property("overflow", "hidden")
                                        property("text-overflow", "ellipsis")
                                        property("display", "-webkit-box")
                                        property("-webkit-line-clamp", "2")
                                        property("-webkit-box-orient", "vertical")
                                    }
                                }) { Text(snippet) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== AI 消息卡片组件 ====================

/**
 * AI 消息卡片 - 用于 @silk 的回复
 * 特点：
 * 1. 左侧有 AI 图标和标识
 * 2. 渐变背景色
 * 3. Markdown 内容优化渲染
 * 4. 可折叠的长内容
 */
private fun setAiMessageExpanded(messageId: String, expanded: Boolean) {
    val msgEl = document.getElementById("ai-msg-$messageId") ?: return
    msgEl.querySelector("[data-view='collapsed']").asDynamic().style.display = if (expanded) "none" else "block"
    msgEl.querySelector("[data-view='expanded']").asDynamic().style.display = if (expanded) "block" else "none"
    msgEl.querySelector("[data-role='expand-btn']").asDynamic().style.display = if (expanded) "none" else "inline"
    msgEl.querySelector("[data-role='collapse-btn']").asDynamic().style.display = if (expanded) "inline" else "none"
}

@Composable
private fun AISelectionCheckbox(isSelected: Boolean) {
    Div({
        style {
            width(20.px)
            height(20.px)
            borderRadius(4.px)
            property("border", "2px solid ${if (isSelected) SilkColors.primary else SilkColors.border}")
            backgroundColor(Color(if (isSelected) SilkColors.primary else "transparent"))
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("justify-content", "center")
            property("flex-shrink", "0")
            marginTop(12.px)
            property("transition", "all 0.2s")
            color(Color.white)
            fontSize(12.px)
        }
    }) {
        if (isSelected) Text("✓")
    }
}

@Composable
private fun AIMessageHeader(message: Message, timeString: String, showToggle: Boolean) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "10px")
            marginBottom(12.px)
        }
    }) {
        Div({ classes(SilkStylesheet.aiBadge) }) { Text("🤖") }
        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "2px")
            }
        }) {
            Span({
                style {
                    fontWeight("600")
                    fontSize(14.px)
                    color(Color(SilkColors.primary))
                    property("letter-spacing", "0.5px")
                }
            }) {
                val aiDisplayName = message.userName.trimStart().removePrefix("\uD83E\uDD16").trim()
                    .let { if (it.isBlank() || it == "Silk") "Silk AI" else it }
                Text(aiDisplayName)
            }
            Span({
                style {
                    fontSize(11.px)
                    color(Color(SilkColors.textLight))
                }
            }) { Text(timeString) }
        }
        if (showToggle) AIMessageToggleButtons(message.id)
    }
}

@Composable
private fun AIMessageToggleButtons(messageId: String) {
    Div({ style { property("flex", "1") } }) { }
    AIMessageToggleButton(
        label = "📖 展开",
        role = "expand-btn",
        messageId = messageId,
        initiallyHidden = false,
        expanded = true
    )
    AIMessageToggleButton(
        label = "📖 收起",
        role = "collapse-btn",
        messageId = messageId,
        initiallyHidden = true,
        expanded = false
    )
}

@Composable
private fun AIMessageToggleButton(
    label: String,
    role: String,
    messageId: String,
    initiallyHidden: Boolean,
    expanded: Boolean
) {
    Span({
        attr("data-role", role)
        attr("data-msg", messageId)
        style {
            fontSize(12.px)
            color(Color(SilkColors.textSecondary))
            property("cursor", "pointer")
            padding(4.px, 8.px)
            borderRadius(4.px)
            property("transition", "all 0.2s")
            property("user-select", "none")
            property("background", "rgba(201, 168, 108, 0.1)")
            if (initiallyHidden) display(DisplayStyle.None)
        }
        onClick { setAiMessageExpanded(messageId, expanded) }
    }) {
        Text(label)
    }
}

@Composable
private fun AIMessageBody(message: Message, isLongContent: Boolean, isTransient: Boolean, isLastMessage: Boolean) {
    if (isLongContent && !isTransient) {
        CollapsedAIMessageContent(message)
        ExpandedAIMessageContent(message, showBottomCollapse = true)
        LaunchedEffect(message.id, isLastMessage) {
            setAiMessageExpanded(message.id, isLastMessage)
        }
    } else {
        ExpandedAIMessageContent(message, showBottomCollapse = false)
    }
}

@Composable
private fun CollapsedAIMessageContent(message: Message) {
    val collapsedPreview = remember(message.content) {
        message.content.trimStart().take(200).ifBlank { "（内容已折叠，点击展开）" }
    }
    Div({
        attr("data-view", "collapsed")
        style {
            fontSize(13.px)
            color(Color(SilkColors.textSecondary))
            property("font-style", "italic")
        }
    }) {
        Text("$collapsedPreview...")
    }
}

@Composable
private fun ExpandedAIMessageContent(message: Message, showBottomCollapse: Boolean) {
    Div({
        if (showBottomCollapse) {
            attr("data-view", "expanded")
            style { display(DisplayStyle.None) }
        }
        classes(SilkStylesheet.aiMessageContent)
    }) {
        MarkdownContent(
            content = message.content,
            references = message.references,
            referenceAnchorPrefix = "msg-${message.id}-"
        )
        ReferenceSourcesList(
            references = message.references,
            anchorPrefix = "msg-${message.id}-"
        )
        if (showBottomCollapse) AIMessageBottomCollapseButton(message.id)
    }
}

@Composable
private fun AIMessageBottomCollapseButton(messageId: String) {
    Div({
        attr("data-role", "collapse-bottom-btn")
        style {
            display(DisplayStyle.Flex)
            property("justify-content", "center")
            paddingTop(8.px)
            paddingBottom(4.px)
        }
    }) {
        Span({
            style {
                fontSize(12.px)
                color(Color(SilkColors.textSecondary))
                property("cursor", "pointer")
                padding(4.px, 16.px)
                borderRadius(12.px)
                property("transition", "all 0.2s")
                property("user-select", "none")
                property("background", "rgba(201, 168, 108, 0.1)")
            }
            onClick { setAiMessageExpanded(messageId, false) }
        }) {
            Text("▲ 收起")
        }
    }
}

@Composable
private fun AIMessageActions(
    message: Message,
    onCopy: (String) -> Unit,
    onCaptureToKnowledgeBase: (Message) -> Unit,
    onForward: (Message) -> Unit,
    onDelete: (String) -> Unit,
    onEnterSelectionMode: (String) -> Unit
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("justify-content", "flex-end")
            property("gap", "6px")
            marginTop(12.px)
            paddingTop(8.px)
            property("border-top", "1px solid rgba(232, 224, 212, 0.5)")
        }
    }) {
        AIMessageAction("📋", "复制") { onCopy(message.content) }
        AIMessageAction("📚", "入库") { onCaptureToKnowledgeBase(message) }
        AIMessageAction("↗", "转发") { onForward(message) }
        AIMessageAction("🗑", "删除", color = "#E57373") {
            if (window.confirm("确定要删除这条消息吗？")) onDelete(message.id)
        }
        AIMessageAction("☑", "多选") { onEnterSelectionMode(message.id) }
    }
}

@Composable
private fun AIMessageAction(icon: String, label: String, color: String = SilkColors.textSecondary, onClick: () -> Unit) {
    Span({
        style {
            fontSize(11.px)
            color(Color(color))
            property("cursor", "pointer")
            padding(4.px, 10.px)
            borderRadius(4.px)
            property("transition", "all 0.2s")
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "4px")
        }
        onClick { onClick() }
    }) {
        Text(icon)
        Text(label)
    }
}

@Composable
private fun AITransientStatus() {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "6px")
            marginTop(10.px)
            fontSize(12.px)
            color(Color(SilkColors.warning))
        }
    }) {
        Text("⏳")
        Text("生成中...")
    }
}

@Composable
@Suppress("NO_EXPLICIT_RETURN_TYPE_IN_API_CLASS", "UnusedParameter")
@NoLiveLiterals
fun AIMessageCard(
    message: Message,
    timeString: String,
    isTransient: Boolean = false,
    isLastMessage: Boolean = false,
    onCopy: (String) -> Unit = {},
    onCaptureToKnowledgeBase: (Message) -> Unit = {},
    onForward: (Message) -> Unit = {},
    onDelete: (String) -> Unit = {},
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (String) -> Unit = {},
    onEnterSelectionMode: (String) -> Unit = {}
) {
    val isLongContent = message.content.length > 500
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.FlexStart)
            property("gap", "8px")
            if (isSelectionMode) property("cursor", "pointer")
        }
        if (isSelectionMode) onClick { onToggleSelection(message.id) }
    }) {
        if (isSelectionMode) AISelectionCheckbox(isSelected)
        Div({
            classes(SilkStylesheet.aiMessageCard)
            attr("id", "ai-msg-${message.id}")
            style {
                property("flex", "1")
                property("min-width", "0")
                if (isSelected) {
                    property("outline", "2px solid ${SilkColors.primary}")
                    backgroundColor(Color("rgba(76, 175, 80, 0.05)"))
                }
            }
        }) {
            AIMessageHeader(message, timeString, showToggle = isLongContent && !isTransient)
            AIMessageBody(message, isLongContent, isTransient, isLastMessage)
            if (!isTransient && !isSelectionMode) {
                AIMessageActions(message, onCopy, onCaptureToKnowledgeBase, onForward, onDelete, onEnterSelectionMode)
            }
            if (isTransient) AITransientStatus()
        }
    }
}

/**
 * 消息渲染模式 — 集中所有 type + category 的交叉判断，
 * 避免在 MessageItem 中用多处提前拦截 + return 导致分支遗漏。
 */
private enum class MessageRenderMode {
    AI_TEXT,          // AI 常规文本 → AIMessageCard
    AGENT_STATUS,     // Agent 状态 → 灰色斜体
    AGENT_QUESTION,   // Agent 文本提问 → 橙色背景
    CARD,             // 交互卡片 → CardMessageRenderer
    CARD_REPLY,       // 卡片回复 → 绿色摘要
    NORMAL_TEXT,      // 普通用户消息气泡（含撤回/操作按钮）
    FILE,             // 文件消息
    SYSTEM_EVENT,     // JOIN / LEAVE / SYSTEM
    NOOP,             // RECALL / STOP_GENERATE → 不渲染
}

/**
 * 根据 message 的 type 和 category 决定渲染模式。
 * 优先级：CARD/CARD_REPLY 类型 > category 特殊处理 > type 分支。
 */
private fun resolveRenderMode(message: Message): MessageRenderMode {
    // 卡片类型最优先 — 不管 category 是什么
    if (message.type == MessageType.CARD) return MessageRenderMode.CARD
    if (message.type == MessageType.CARD_REPLY) return MessageRenderMode.NOOP

    // category 特殊处理（只对非卡片消息生效）
    if (message.category == com.silk.shared.models.MessageCategory.AGENT_STATUS) return MessageRenderMode.AGENT_STATUS
    if (message.category == com.silk.shared.models.MessageCategory.AGENT_QUESTION) return MessageRenderMode.AGENT_QUESTION

    // type 分支
    return when (message.type) {
        MessageType.TEXT -> if (isAgentUserId(message.userId)) MessageRenderMode.AI_TEXT else MessageRenderMode.NORMAL_TEXT
        MessageType.FILE -> MessageRenderMode.FILE
        MessageType.JOIN, MessageType.LEAVE, MessageType.SYSTEM -> MessageRenderMode.SYSTEM_EVENT
        MessageType.RECALL, MessageType.STOP_GENERATE -> MessageRenderMode.NOOP
        else -> MessageRenderMode.NORMAL_TEXT
    }
}

@Composable
fun MessageItem(
    message: Message,
    isTransient: Boolean = false,
    currentUserId: String = "",
    currentUserName: String = "",
    isLastMessage: Boolean = false,
    isRecalling: Boolean = false,
    chatClient: com.silk.shared.ChatClient? = null,
    onRecall: (String) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onCaptureToKnowledgeBase: (Message) -> Unit = {},
    onForward: (Message) -> Unit = {},
    onDelete: (String) -> Unit = {},
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (String) -> Unit = {},
    onEnterSelectionMode: (String) -> Unit = {}
) {
    val timeString = remember(message.timestamp) {
        formatMessageTimestampForWeb(message.timestamp)
    }

    val renderMode = resolveRenderMode(message)

    when (renderMode) {
        MessageRenderMode.AI_TEXT -> {
            AIMessageCard(
                message = message,
                timeString = timeString,
                isTransient = isTransient,
                isLastMessage = isLastMessage,
                onCopy = onCopy,
                onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                onForward = onForward,
                onDelete = onDelete,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onToggleSelection = onToggleSelection,
                onEnterSelectionMode = onEnterSelectionMode
            )
        }
        MessageRenderMode.AGENT_STATUS -> {
            AgentStatusMessage(message.content)
        }
        MessageRenderMode.AGENT_QUESTION -> {
            AgentQuestionMessage(message.content)
        }
        MessageRenderMode.NORMAL_TEXT -> {
            UserTextMessageCard(
                message = message,
                timeString = timeString,
                currentUserId = currentUserId,
                isTransient = isTransient,
                isRecalling = isRecalling,
                onRecall = onRecall,
                onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                onForward = onForward,
                onDelete = onDelete,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onToggleSelection = onToggleSelection,
                onEnterSelectionMode = onEnterSelectionMode
            )
        }
        MessageRenderMode.FILE -> {
            FileMessageCard(
                message = message,
                timeString = timeString,
                isTransient = isTransient,
                onForward = onForward,
                onDelete = onDelete,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onToggleSelection = onToggleSelection,
                onEnterSelectionMode = onEnterSelectionMode
            )
        }
        MessageRenderMode.SYSTEM_EVENT -> {
            SystemEventMessage(message.content, timeString)
        }
        MessageRenderMode.CARD -> {
            CardMessageContent(message, chatClient, currentUserId, currentUserName)
        }
        MessageRenderMode.CARD_REPLY -> {
            CardReplySummary(message.content)
        }
        MessageRenderMode.NOOP -> { }
    }
}

private data class PdfReportMessage(
    val bodyLines: List<String>,
    val downloadPath: String?,
    val fileName: String?,
)

@Composable
private fun AgentStatusMessage(content: String) {
    AgentBubbleMessage(
        content = content,
        background = "#F5F5F5",
        borderColor = "#BDBDBD",
        textColor = "#757575",
        fontSizePx = 13
    )
}

@Composable
private fun AgentQuestionMessage(content: String) {
    AgentBubbleMessage(
        content = content,
        background = "#FFF8F0",
        borderColor = "#E8B86C",
        textColor = "#5D4E37",
        fontSizePx = 14
    )
}

@Composable
private fun AgentBubbleMessage(
    content: String,
    background: String,
    borderColor: String,
    textColor: String,
    fontSizePx: Int,
) {
    Div({
        style {
            padding(12.px, 16.px)
            marginBottom(8.px)
            backgroundColor(Color(background))
            borderRadius(8.px)
            property("border-left", "3px solid $borderColor")
            fontSize(fontSizePx.px)
            color(Color(textColor))
            property("font-style", "italic")
            property("white-space", "pre-wrap")
            property("word-break", "break-word")
        }
    }) {
        Text(content)
    }
}

@Composable
private fun UserTextMessageCard(
    message: Message,
    timeString: String,
    currentUserId: String,
    isTransient: Boolean,
    isRecalling: Boolean,
    onRecall: (String) -> Unit,
    onCaptureToKnowledgeBase: (Message) -> Unit,
    onForward: (Message) -> Unit,
    onDelete: (String) -> Unit,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: (String) -> Unit,
    onEnterSelectionMode: (String) -> Unit,
) {
    val canRecall = message.userId == currentUserId &&
        !isAgentUserId(message.userId) &&
        !isTransient
    val pdfReport = remember(message.content) { parsePdfReportMessage(message.content) }

    StandardMessageCard(
        message = message,
        timeString = timeString,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection
    ) {
        StandardMessageBody {
            if (pdfReport == null) {
                InlineKnowledgeBaseText(message.content)
            } else {
                PdfReportMessageBody(pdfReport)
            }
        }
        if (!isTransient && !isSelectionMode) {
            TextMessageActions(
                message = message,
                canRecall = canRecall,
                isRecalling = isRecalling,
                onRecall = onRecall,
                onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                onForward = onForward,
                onDelete = onDelete,
                onEnterSelectionMode = onEnterSelectionMode
            )
        }
    }
}

@Composable
private fun FileMessageCard(
    message: Message,
    timeString: String,
    isTransient: Boolean,
    onForward: (Message) -> Unit,
    onDelete: (String) -> Unit,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: (String) -> Unit,
    onEnterSelectionMode: (String) -> Unit,
) {
    val fileInfo = remember(message.content) {
        parseWebFileMessageContent(message.content)
    }
    val fileName = fileInfo.fileName
    val fileSizeStr = formatWebFileSize(fileInfo.fileSize)
    val fileExtLabel = fileName.substringAfterLast(".", "").uppercase().ifBlank { "FILE" }

    StandardMessageCard(
        message = message,
        timeString = timeString,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection
    ) {
        FileDownloadCard(
            fileName = fileName,
            fileSizeStr = fileSizeStr,
            fileExtLabel = fileExtLabel,
            downloadUrl = fileInfo.downloadUrl
        )
        if (!isTransient && !isSelectionMode) {
            FileMessageActions(
                message = message,
                onForward = onForward,
                onDelete = onDelete,
                onEnterSelectionMode = onEnterSelectionMode
            )
        }
    }
}

@Composable
private fun StandardMessageCard(
    message: Message,
    timeString: String,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "8px")
        }
    }) {
        if (isSelectionMode) {
            StandardSelectionCheckbox(
                isSelected = isSelected,
                onClick = { onToggleSelection(message.id) }
            )
        }
        Div({
            classes(SilkStylesheet.messageCard)
            style {
                property("flex", "1")
                property("min-width", "0")
                if (isSelected) {
                    backgroundColor(Color("rgba(76, 175, 80, 0.10)"))
                    property("outline", "2px solid ${SilkColors.primary}")
                }
                if (isSelectionMode) {
                    property("cursor", "pointer")
                }
            }
            if (isSelectionMode) {
                onClick { onToggleSelection(message.id) }
            }
        }) {
            StandardMessageHeader(message.userName, timeString)
            content()
        }
    }
}

@Composable
private fun StandardSelectionCheckbox(isSelected: Boolean, onClick: () -> Unit) {
    Div({
        style {
            width(24.px)
            height(24.px)
            borderRadius(4.px)
            property("border", if (isSelected) "none" else "2px solid ${SilkColors.border}")
            backgroundColor(Color(if (isSelected) SilkColors.primary else "transparent"))
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("justify-content", "center")
            property("cursor", "pointer")
            property("flex-shrink", "0")
            property("transition", "all 0.15s ease")
        }
        onClick { onClick() }
    }) {
        if (isSelected) {
            Span({ style { color(Color.white); fontSize(14.px); property("font-weight", "bold") } }) {
                Text("\u2713")
            }
        }
    }
}

@Composable
private fun StandardMessageHeader(userName: String, timeString: String) {
    Div({ classes(SilkStylesheet.messageHeader) }) {
        Span({ classes(SilkStylesheet.userName) }) {
            Text(userName)
        }
        Span({ classes(SilkStylesheet.timestamp) }) {
            Text(timeString)
        }
    }
}

@Composable
private fun StandardMessageBody(content: @Composable () -> Unit) {
    Div({
        style {
            property("white-space", "pre-wrap")
            property("word-wrap", "break-word")
            property("line-height", "1.7")
            property("color", SilkColors.textPrimary)
        }
    }) {
        content()
    }
}

private fun parsePdfReportMessage(content: String): PdfReportMessage? {
    if (!content.contains("/download/report/") || !content.contains(".pdf")) return null

    var downloadPath: String? = null
    var fileName: String? = null
    val bodyLines = mutableListOf<String>()

    content.split("\n").forEach { line ->
        val trimmedLine = line.trim()
        if (trimmedLine.startsWith("/download/report/") && trimmedLine.contains(".pdf")) {
            downloadPath = trimmedLine
            fileName = decodeDownloadFileName(trimmedLine)
        } else if (trimmedLine.isNotEmpty()) {
            bodyLines += line
        }
    }

    return PdfReportMessage(
        bodyLines = bodyLines,
        downloadPath = downloadPath,
        fileName = fileName
    )
}

private fun decodeDownloadFileName(downloadPath: String): String =
    downloadPath.substringAfterLast("/").replace("%20", " ").replace("%27", "'")

private fun startBrowserDownload(
    fullUrl: String,
    downloadFileName: String,
    startLog: String,
    successLog: String,
    failureLog: String,
) {
    console.log("$startLog: $fullUrl, 文件名: $downloadFileName")

    val browserWindow = js("window")
    val browserDocument = js("document")

    browserWindow.fetch(fullUrl)
        .then({ response: dynamic ->
            if (!response.ok) {
                throw js("Error('下载失败: ' + response.status)")
            }
            response.blob()
        })
        .then({ blob: dynamic ->
            val url = browserWindow.URL.createObjectURL(blob)
            val link = browserDocument.createElement("a")
            link.style.display = "none"
            link.href = url
            link.download = downloadFileName
            browserDocument.body.appendChild(link)
            link.click()
            browserWindow.URL.revokeObjectURL(url)
            browserDocument.body.removeChild(link)
            console.log(successLog)
        })
        .catch({ error: dynamic ->
            console.error(failureLog, error)
            browserWindow.alert("$failureLog: " + error.message)
        })
}

@Composable
private fun PdfReportMessageBody(pdfReport: PdfReportMessage) {
    pdfReport.bodyLines.forEach { line ->
        Text(line)
        Br()
    }

    val downloadPath = pdfReport.downloadPath
    if (downloadPath != null) {
        val fullUrl = "${backendHttpOrigin()}$downloadPath"
        val downloadFileName = pdfReport.fileName ?: "diagnosis_report.pdf"

        Div({
            style {
                marginTop(14.px)
            }
        }) {
            Button({
                style {
                    property("background", "linear-gradient(135deg, ${SilkColors.success} 0%, #6A9D5B 100%)")
                    color(Color.white)
                    padding(12.px, 20.px)
                    border {
                        width(0.px)
                    }
                    borderRadius(8.px)
                    fontSize(14.px)
                    property("cursor", "pointer")
                    property("font-weight", "600")
                    property("display", "inline-flex")
                    property("align-items", "center")
                    property("gap", "8px")
                    property("box-shadow", "0 2px 8px rgba(125, 174, 108, 0.3)")
                    property("transition", "all 0.2s ease")
                }
                onClick { event ->
                    event.preventDefault()
                    startBrowserDownload(
                        fullUrl = fullUrl,
                        downloadFileName = downloadFileName,
                        startLog = "开始下载PDF",
                        successLog = "PDF下载成功",
                        failureLog = "下载PDF失败"
                    )
                }
            }) {
                Text("📥 下载PDF报告")
            }

            if (pdfReport.fileName != null) {
                Div({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textLight))
                        marginTop(8.px)
                        property("font-style", "italic")
                    }
                }) {
                    Text("文件名：${pdfReport.fileName}")
                }
            }
        }
    }
}

@Composable
private fun TextMessageActions(
    message: Message,
    canRecall: Boolean,
    isRecalling: Boolean,
    onRecall: (String) -> Unit,
    onCaptureToKnowledgeBase: (Message) -> Unit,
    onForward: (Message) -> Unit,
    onDelete: (String) -> Unit,
    onEnterSelectionMode: (String) -> Unit,
) {
    MessageActionRow {
        MessageAction("📋复制") { copyTextToClipboard(message.content) }
        MessageAction("📚入库") { onCaptureToKnowledgeBase(message) }
        MessageAction("↗转发") { onForward(message) }
        if (canRecall && !isRecalling) {
            MessageAction("↩撤回") {
                if (window.confirm("确定要撤回这条消息吗？")) {
                    onRecall(message.id)
                }
            }
        }
        MessageAction("🗑删除", color = "#E57373") {
            if (kotlinx.browser.window.confirm("确定要删除这条消息吗？")) {
                onDelete(message.id)
            }
        }
        MessageAction("☑多选") { onEnterSelectionMode(message.id) }
    }
}

@Composable
private fun FileDownloadCard(
    fileName: String,
    fileSizeStr: String,
    fileExtLabel: String,
    downloadUrl: String,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "12px")
            padding(12.px)
            backgroundColor(Color(SilkColors.surfaceElevated))
            borderRadius(8.px)
            property("border", "1px solid ${SilkColors.border}")
            property("cursor", "pointer")
            property("transition", "all 0.2s ease")
        }
        onClick {
            if (downloadUrl.isNotEmpty()) {
                startBrowserDownload(
                    fullUrl = "${backendHttpOrigin()}$downloadUrl",
                    downloadFileName = fileName,
                    startLog = "打开文件下载",
                    successLog = "文件下载成功",
                    failureLog = "下载文件失败"
                )
            }
        }
    }) {
        Div({
            style {
                fontSize(32.px)
                padding(8.px)
                backgroundColor(Color(SilkColors.secondary))
                borderRadius(8.px)
            }
        }) {
            Text(webFileIconForName(fileName))
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "4px")
            }
        }) {
            Div({
                style {
                    fontSize(14.px)
                    fontWeight("600")
                    color(Color(SilkColors.textPrimary))
                    property("max-width", "200px")
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("white-space", "nowrap")
                }
            }) {
                Text(fileName)
            }
            Div({
                style {
                    fontSize(12.px)
                    color(Color(SilkColors.textSecondary))
                }
            }) {
                Text("$fileSizeStr • $fileExtLabel")
            }
        }
        Div({
            style {
                marginLeft(8.px)
                fontSize(18.px)
                color(Color(SilkColors.primary))
            }
        }) {
            Text("⬇")
        }
    }
}

@Composable
private fun FileMessageActions(
    message: Message,
    onForward: (Message) -> Unit,
    onDelete: (String) -> Unit,
    onEnterSelectionMode: (String) -> Unit,
) {
    MessageActionRow {
        MessageAction("↗转发") { onForward(message) }
        MessageAction("🗑删除", color = "#E57373") {
            if (kotlinx.browser.window.confirm("确定要删除这条消息吗？")) {
                onDelete(message.id)
            }
        }
        MessageAction("☑多选") { onEnterSelectionMode(message.id) }
    }
}

@Composable
private fun MessageActionRow(content: @Composable () -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("justify-content", "flex-end")
            property("gap", "6px")
            marginTop(8.px)
            property("opacity", "0.5")
            property("transition", "opacity 0.2s")
        }
    }) {
        content()
    }
}

@Composable
private fun MessageAction(text: String, color: String = SilkColors.textSecondary, onClick: () -> Unit) {
    Span({
        style {
            fontSize(11.px)
            color(Color(color))
            property("cursor", "pointer")
            property("padding", "2px 6px")
            property("border-radius", "4px")
            property("transition", "all 0.2s")
        }
        onClick { onClick() }
    }) {
        Text(text)
    }
}

@Composable
private fun SystemEventMessage(content: String, timeString: String) {
    Div({ classes(SilkStylesheet.systemMessage) }) {
        Text("• $content ($timeString)")
    }
}

@Composable
private fun CardMessageContent(
    message: Message,
    chatClient: com.silk.shared.ChatClient?,
    currentUserId: String,
    currentUserName: String,
) {
    if (chatClient != null) {
        CardMessageRenderer(
            message = message,
            chatClient = chatClient,
            currentUserId = currentUserId,
            userName = currentUserName,
        )
    } else {
        Div({ style { padding(8.px); color(Color("#999")) } }) {
            Text("[卡片消息]")
        }
    }
}

@Composable
private fun CardReplySummary(content: String) {
    Div({
        style {
            padding(6.px, 12.px)
            marginBottom(6.px)
            backgroundColor(Color("#F0F8F0"))
            borderRadius(8.px)
            fontSize(13.px)
            color(Color("#4a7c59"))
            property("font-style", "italic")
        }
    }) {
        Text("\u2713 ${cardReplySummaryText(content)}")
    }
}

private fun cardReplySummaryText(content: String): String =
    try {
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(content).jsonObject
        val action = payload["action"]?.jsonPrimitive?.content ?: "unknown"
        when {
            action.startsWith("__opt__") -> {
                val afterPrefix = action.removePrefix("__opt__")
                val idx = afterPrefix.indexOf("__")
                val cleanText = if (idx >= 0) afterPrefix.substring(idx + 2) else action
                "选择: $cleanText"
            }
            action.startsWith("__custom__") -> {
                val questionIndex = action.removePrefix("__custom__")
                val custom = payload["inputs"]?.jsonObject?.get("custom_answer_$questionIndex")
                    ?.jsonPrimitive?.content ?: ""
                if (custom.isNotBlank()) "回复: $custom" else "回复: (自定义)"
            }
            action.startsWith("perm_allow_") -> "允许"
            action.startsWith("perm_deny_") -> "拒绝"
            action.startsWith("perm_accept_edits_") -> "允许所有编辑"
            action.startsWith("perm_bypass_") -> "允许所有操作"
            else -> "选择: $action"
        }
    } catch (_: kotlinx.serialization.SerializationException) {
        "卡片回复"
    } catch (_: IllegalArgumentException) {
        "卡片回复"
    }

@Composable
fun TransientMessageItem(message: Message) {
    // 临时消息：丝滑风格 + 进度条动画
    val timeString = remember(message.timestamp) {
        formatMessageTimestampForWeb(message.timestamp)
    }

    // 循环进度动画状态
    var progress by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        // 循环动画：0 → 100 → 0 不断循环
        while (true) {
            for (i in 0..100) {
                progress = i
                kotlinx.coroutines.delay(20)  // 2秒完成一次循环（100步 * 20ms = 2000ms）
            }
        }
    }

    Div({ classes(SilkStylesheet.transientMessageCard) }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                property("justify-content", "space-between")
                marginBottom(6.px)
            }
        }) {
            Span({
                style {
                    property("font-weight", "600")
                    color(Color(SilkColors.primaryDark))
                }
            }) {
                Text("${message.userName} (处理中...)")
            }
            Span({
                style {
                    fontSize(11.px)
                    color(Color(SilkColors.textLight))
                }
            }) {
                Text(timeString)
            }
        }

        // 如果有步骤信息，显示进度条
        if (message.currentStep != null && message.totalSteps != null) {
            Div({ classes(SilkStylesheet.progressBarContainer) }) {
                // 步骤指示
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("justify-content", "space-between")
                        fontSize(11.px)
                        color(Color(SilkColors.primary))
                        marginBottom(6.px)
                        property("letter-spacing", "0.5px")
                    }
                }) {
                    Span { Text("步骤 ${message.currentStep}/${message.totalSteps}") }
                    Span { Text("处理中...") }
                }

                // 进度条
                Div({ classes(SilkStylesheet.progressBar) }) {
                    Div({
                        classes(SilkStylesheet.progressFill)
                        style {
                            val totalProgress = ((message.currentStep!! - 1) * 100 + progress) / message.totalSteps!!
                            width(totalProgress.percent)
                        }
                    }) {}
                }
            }
        }

        Div({
            style {
                color(Color(SilkColors.textSecondary))
                marginTop(6.px)
                property("white-space", "pre-wrap")
                property("word-wrap", "break-word")
                property("line-height", "1.7")
            }
        }) {
            Text(message.content)
        }
    }
}

// 工具函数
fun generateRandomId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..16)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}

private fun formatMessageTimestampForWeb(
    timestamp: Long,
    referenceTimestamp: Long = Date.now().toLong(),
    includeSeconds: Boolean = true
): String {
    if (timestamp <= 0L) return ""

    val messageDate = shanghaiDate(timestamp)
    val referenceDate = shanghaiDate(referenceTimestamp)

    val timePart = buildString {
        append(messageDate.hours.twoDigits())
        append(":")
        append(messageDate.minutes.twoDigits())
        if (includeSeconds) {
            append(":")
            append(messageDate.seconds.twoDigits())
        }
    }

    if (
        messageDate.year == referenceDate.year &&
        messageDate.month == referenceDate.month &&
        messageDate.day == referenceDate.day
    ) {
        return timePart
    }

    val datePart = if (messageDate.year == referenceDate.year) {
        "${messageDate.month.twoDigits()}-${messageDate.day.twoDigits()}"
    } else {
        "${messageDate.year}-${messageDate.month.twoDigits()}-${messageDate.day.twoDigits()}"
    }

    return "$datePart $timePart"
}

private data class ShanghaiDateParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hours: Int,
    val minutes: Int,
    val seconds: Int
)

private fun shanghaiDate(timestamp: Long): ShanghaiDateParts {
    val shanghaiTime = Date(timestamp.toDouble() + 8 * 60 * 60 * 1000)
    return ShanghaiDateParts(
        year = shanghaiTime.getUTCFullYear(),
        month = shanghaiTime.getUTCMonth() + 1,
        day = shanghaiTime.getUTCDate(),
        hours = shanghaiTime.getUTCHours(),
        minutes = shanghaiTime.getUTCMinutes(),
        seconds = shanghaiTime.getUTCSeconds()
    )
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

internal fun isLikelyAgentStatusContent(content: String): Boolean {
    val text = content.trim()
    if (text.isBlank()) return false

    val statusHints = listOf(
        "正在处理",
        "思考中",
        "使用工具",
        "执行:",
        "处理中",
        "检索",
        "搜索",
        "🤔",
        "🔧",
        "⏳"
    )
    return statusHints.any { hint -> text.contains(hint) }
}

internal fun shouldRenderInlineTransientMessage(message: Message): Boolean {
    return message.content.isNotBlank() &&
            message.currentStep == null &&
            message.totalSteps == null &&
            !isLikelyAgentStatusContent(message.content)
}

internal fun isWorkflowAgentLifecycleMessage(message: Message): Boolean {
    if (message.type != com.silk.shared.models.MessageType.SYSTEM) {
        return false
    }

    val content = message.content
    return content.startsWith("已切换到") ||
            content.contains("已激活") ||
            content.contains("已退出 agent")
}

/**
 * 添加成员对话框
 */
@Composable
fun AddMemberDialog(
    contacts: List<Contact>,
    groupMembers: List<GroupMember>,
    isLoading: Boolean,
    result: String?,
    strings: com.silk.shared.i18n.Strings,
    onAddMember: (Contact) -> Unit,
    onDismiss: () -> Unit
) {
    // 过滤出不在群组中的联系人
    val memberIds = groupMembers.map { it.id }.toSet()
    val availableContacts = contacts.filter { it.contactId !in memberIds }
    
    // 对话框遮罩
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
            property("backdrop-filter", "blur(4px)")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(16.px)
                padding(28.px)
                width(480.px)
                maxWidth(90.vw)
                maxHeight(70.vh)
                property("overflow-y", "auto")
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.15)")
            }
            onClick { it.stopPropagation() }
        }) {
            // 标题
            H3({
                style {
                    margin(0.px, 0.px, 20.px, 0.px)
                    color(Color(SilkColors.textPrimary))
                    fontSize(20.px)
                    property("font-weight", "600")
                }
            }) {
                Text(strings.addMembersToGroup)
            }
            
            // 结果提示
            result?.let {
                Div({
                    style {
                        backgroundColor(
                            if (it.startsWith("✅")) Color("#F0F7EE") else Color("#FFF5F5")
                        )
                        color(if (it.startsWith("✅")) Color(SilkColors.success) else Color(SilkColors.error))
                        padding(14.px)
                        borderRadius(8.px)
                        marginBottom(16.px)
                        fontSize(13.px)
                        property("border", "1px solid ${if (it.startsWith("✅")) SilkColors.success else SilkColors.error}")
                    }
                }) {
                    Text(it)
                }
            }
            
            if (isLoading) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(40.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.loading)
                }
            } else if (availableContacts.isEmpty()) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(40.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.noContactsToAdd)
                }
            } else {
                // 联系人列表
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "12px")
                        maxHeight(400.px)
                        property("overflow-y", "auto")
                    }
                }) {
                    availableContacts.forEach { contact ->
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(16.px, 20.px)
                                backgroundColor(Color(SilkColors.surface))
                                borderRadius(10.px)
                                property("box-shadow", "0 2px 4px rgba(0,0,0,0.05)")
                                property("border", "1px solid ${SilkColors.border}")
                            }
                        }) {
                            // 联系人信息
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    flexDirection(FlexDirection.Column)
                                    property("gap", "4px")
                                }
                            }) {
                                Div({
                                    style {
                                        fontSize(15.px)
                                        color(Color(SilkColors.textPrimary))
                                        property("font-weight", "500")
                                    }
                                }) {
                                    Text(contact.contactName)
                                }
                                Div({
                                    style {
                                        fontSize(13.px)
                                        color(Color(SilkColors.textSecondary))
                                    }
                                }) {
                                    Text(contact.contactPhone)
                                }
                            }
                            
                            // 添加按钮
                            Button({
                                style {
                                    padding(10.px, 20.px)
                                    property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                                    color(Color.white)
                                    border { width(0.px) }
                                    borderRadius(8.px)
                                    fontSize(14.px)
                                    property("cursor", "pointer")
                                    property("font-weight", "500")
                                    property("transition", "all 0.2s ease")
                                }
                                onClick { onAddMember(contact) }
                            }) {
                                Text("添加")
                            }
                        }
                    }
                }
            }
            
            // 关闭按钮
            Div({
                style {
                    textAlign("center")
                    marginTop(24.px)
                }
            }) {
                Button({
                    style {
                        padding(12.px, 28.px)
                        backgroundColor(Color(SilkColors.secondary))
                        color(Color(SilkColors.textPrimary))
                        border { width(0.px) }
                        borderRadius(8.px)
                        property("cursor", "pointer")
                        fontSize(14.px)
                        property("font-weight", "500")
                        property("transition", "all 0.2s ease")
                    }
                    onClick { onDismiss() }
                }) {
                    Text(strings.closeButton)
                }
            }
        }
    }
}

/**
 * 群组成员列表对话框
 */
@Composable
fun MembersDialog(
    members: List<GroupMember>,
    contacts: List<Contact>,
    currentUserId: String,
    isLoading: Boolean,
    strings: com.silk.shared.i18n.Strings,
    onMemberClick: (GroupMember) -> Unit,
    onDismiss: () -> Unit
) {
    val contactIds = contacts.map { it.contactId }.toSet()
    MembersDialogOverlay(onDismiss = onDismiss) {
        MembersDialogSurface {
            MembersDialogTitle(strings, members.size)
            MembersDialogBody(
                members = members,
                contactIds = contactIds,
                currentUserId = currentUserId,
                isLoading = isLoading,
                strings = strings,
                onMemberClick = onMemberClick,
            )
            MembersDialogCloseButton(onDismiss)
        }
    }
}

@Composable
private fun MembersDialogOverlay(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1000")
            property("backdrop-filter", "blur(4px)")
        }
        onClick { onDismiss() }
    }) {
        content()
    }
}

@Composable
private fun MembersDialogSurface(
    content: @Composable () -> Unit,
) {
    Div({
        style {
            backgroundColor(Color(SilkColors.surfaceElevated))
            borderRadius(16.px)
            padding(28.px)
            width(420.px)
            maxWidth(90.vw)
            maxHeight(70.vh)
            property("overflow-y", "auto")
            property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.15)")
        }
        onClick { it.stopPropagation() }
    }) {
        content()
    }
}

@Composable
private fun MembersDialogTitle(strings: com.silk.shared.i18n.Strings, count: Int) {
    H3({
        style {
            margin(0.px, 0.px, 20.px, 0.px)
            color(Color(SilkColors.textPrimary))
            fontSize(20.px)
            property("font-weight", "600")
            property("text-align", "center")
        }
    }) {
        Text(strings.groupMembersTitleWithCount.replace("{count}", count.toString()))
    }
}

@Composable
private fun MembersDialogBody(
    members: List<GroupMember>,
    contactIds: Set<String>,
    currentUserId: String,
    isLoading: Boolean,
    strings: com.silk.shared.i18n.Strings,
    onMemberClick: (GroupMember) -> Unit,
) {
    when {
        isLoading -> MembersDialogStateText(strings.loading)
        members.isEmpty() -> MembersDialogStateText(strings.noMembers)
        else -> MembersDialogMemberList(
            members = members,
            contactIds = contactIds,
            currentUserId = currentUserId,
            strings = strings,
            onMemberClick = onMemberClick,
        )
    }
}

@Composable
private fun MembersDialogStateText(text: String) {
    Div({
        style {
            property("text-align", "center")
            padding(20.px)
            color(Color(SilkColors.textSecondary))
        }
    }) {
        Text(text)
    }
}

@Composable
private fun MembersDialogMemberList(
    members: List<GroupMember>,
    contactIds: Set<String>,
    currentUserId: String,
    strings: com.silk.shared.i18n.Strings,
    onMemberClick: (GroupMember) -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "10px")
        }
    }) {
        members.forEach { member ->
            MembersDialogMemberRow(
                state = rememberMembersDialogMemberState(member, currentUserId, contactIds, strings),
                onClick = { onMemberClick(member) },
            )
        }
    }
}

@Composable
private fun MembersDialogMemberRow(
    state: MembersDialogMemberState,
    onClick: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            padding(12.px, 16.px)
            backgroundColor(Color(SilkColors.surface))
            borderRadius(10.px)
            property("box-shadow", "0 2px 4px rgba(0,0,0,0.05)")
            if (state.isActionable) {
                property("cursor", "pointer")
                property("transition", "all 0.2s ease")
            }
        }
        if (state.isActionable) {
            onClick { onClick() }
        }
    }) {
        MembersDialogMemberSummary(state)
        MembersDialogMemberActionIcon(state)
    }
}

@Composable
private fun MembersDialogMemberSummary(state: MembersDialogMemberState) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            property("gap", "12px")
        }
    }) {
        MembersDialogAvatar(state)
        Div {
            Div({
                style {
                    fontSize(15.px)
                    color(Color(SilkColors.textPrimary))
                    property("font-weight", "500")
                }
            }) {
                Text(state.member.fullName)
                if (state.isCurrentUser) {
                    Span({
                        style {
                            fontSize(12.px)
                            color(Color(SilkColors.textSecondary))
                            marginLeft(8.px)
                        }
                    }) {
                        Text(state.strings.me)
                    }
                }
            }
            Div({
                style {
                    fontSize(12.px)
                    color(Color(SilkColors.textSecondary))
                    marginTop(2.px)
                }
            }) {
                Text(state.statusText)
            }
        }
    }
}

@Composable
private fun MembersDialogAvatar(state: MembersDialogMemberState) {
    Div({
        style {
            width(40.px)
            height(40.px)
            borderRadius(20.px)
            backgroundColor(Color(state.avatarBackground))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            color(Color.white)
            fontSize(18.px)
        }
    }) {
        Text(state.avatarText)
    }
}

@Composable
private fun MembersDialogMemberActionIcon(state: MembersDialogMemberState) {
    if (!state.isActionable) {
        return
    }

    Div({
        style {
            fontSize(20.px)
            color(Color(SilkColors.textLight))
        }
    }) {
        Text(if (state.isContact) "💬" else "➕")
    }
}

@Composable
private fun MembersDialogCloseButton(onDismiss: () -> Unit) {
    Button({
        style {
            width(100.percent)
            marginTop(20.px)
            backgroundColor(Color(SilkColors.textSecondary))
            color(Color.white)
            border { width(0.px) }
            borderRadius(10.px)
            padding(12.px)
            property("cursor", "pointer")
            fontSize(14.px)
            property("font-weight", "500")
        }
        onClick { onDismiss() }
    }) {
        Text("关闭")
    }
}

private data class MembersDialogMemberState(
    val member: GroupMember,
    val strings: com.silk.shared.i18n.Strings,
    val isCurrentUser: Boolean,
    val isContact: Boolean,
    val isSilkAI: Boolean,
    val avatarBackground: String,
    val avatarText: String,
    val statusText: String,
) {
    val isActionable: Boolean
        get() = !isCurrentUser && !isSilkAI
}

private fun rememberMembersDialogMemberState(
    member: GroupMember,
    currentUserId: String,
    contactIds: Set<String>,
    strings: com.silk.shared.i18n.Strings,
): MembersDialogMemberState {
    val isCurrentUser = member.id == currentUserId
    val isContact = member.id in contactIds
    val isSilkAI = isAgentUserId(member.id)

    return MembersDialogMemberState(
        member = member,
        strings = strings,
        isCurrentUser = isCurrentUser,
        isContact = isContact,
        isSilkAI = isSilkAI,
        avatarBackground = when {
            isSilkAI -> SilkColors.info
            isCurrentUser -> SilkColors.primary
            isContact -> SilkColors.success
            else -> SilkColors.textSecondary
        },
        avatarText = when {
            isSilkAI -> "🤖"
            isCurrentUser -> "👤"
            isContact -> "✓"
            else -> member.fullName.firstOrNull()?.toString() ?: "?"
        },
        statusText = when {
            isSilkAI -> strings.aiAssistant
            isCurrentUser -> strings.currentUser
            isContact -> strings.contactClickToChat
            else -> strings.clickToAddContact
        },
    )
}

/**
 * 复制文本到剪贴板（Web版）
 */
fun copyTextToClipboard(text: String) {
    val clipboard = kotlinx.browser.window.navigator.asDynamic().clipboard
    if (clipboard != null) {
        clipboard.writeText(text).then(
            { console.log("✅ 已复制到剪贴板") },
            { _: dynamic -> fallbackCopyToClipboard(text) }
        )
    } else {
        fallbackCopyToClipboard(text)
    }
}

private fun fallbackCopyToClipboard(text: String) {
    val document = kotlinx.browser.document
    val textarea = document.createElement("textarea") as org.w3c.dom.HTMLTextAreaElement
    textarea.value = text
    textarea.style.position = "fixed"
    textarea.style.left = "-9999px"
    document.body?.appendChild(textarea)
    textarea.select()
    try {
        document.execCommand("copy")
        console.log("✅ 使用备用方案复制成功")
    } catch (e: dynamic) {
        console.error("❌ 备用方案复制失败:", e)
    }
    document.body?.removeChild(textarea)
}
