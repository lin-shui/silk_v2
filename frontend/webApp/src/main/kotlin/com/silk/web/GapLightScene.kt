package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/**
 * 隙光拾暖数据面板 — 在 Silk Web 中查看小程序内容
 */
@Composable
fun GapLightScene(appState: WebAppState) {
    var serverUrl by remember { mutableStateOf("https://www.ai-silk.cloud/api") }
    var apiToken by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("diaries") }

    var diaryEntries by remember { mutableStateOf<List<GapDiary>>(emptyList()) }
    var storyEntries by remember { mutableStateOf<List<GapStory>>(emptyList()) }
    var serverOk by remember { mutableStateOf<Boolean?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var lastSyncLabel by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Div({
        style {
            height(100.vh); display(DisplayStyle.Flex); flexDirection(FlexDirection.Column)
            backgroundColor(Color(SilkColors.background))
        }
    }) {
        // Header
        Header()
        ConfigBar(serverUrl, apiToken, serverOk, lastSyncLabel, { url, token ->
            serverUrl = url; apiToken = token
        }, { serverOk = it })
        // Tab nav
        TabNav(tab) { tab = it }
        // Content
        Div({
            style { property("flex", "1"); property("overflow-y", "auto"); padding(20.px, 24.px) }
        }) {
            when (tab) {
                "diaries" -> DiaryListPane(diaryEntries, loading, errMsg) {
                    loading = true; errMsg = null
                    scope.launch {
                        val h = pingHealth(serverUrl)
                        serverOk = h
                        if (h != true) { errMsg = "无法连接服务器"; loading = false; return@launch }
                        fetchDiaries(serverUrl, apiToken) { list, err ->
                            diaryEntries = list; errMsg = err; loading = false; lastSyncLabel = nowStr()
                        }
                    }
                }
                "stories" -> StoryListPane(storyEntries, loading, errMsg) {
                    loading = true; errMsg = null
                    scope.launch {
                        fetchStories(serverUrl) { list, err ->
                            storyEntries = list; errMsg = err; loading = false; lastSyncLabel = nowStr()
                        }
                    }
                }
                "bridge" -> BridgeHelpPane()
            }
        }
    }
}

@Composable
private fun Header() {
    Div({
        style {
            padding(16.px, 24.px); backgroundColor(Color.white)
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex); alignItems(AlignItems.Center); justifyContent(JustifyContent.SpaceBetween)
        }
    }) {
        Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); property("gap", "12px") } }) {
            Span({ style { fontSize(24.px) } }) { Text("\uD83C\uDF19") }
            Div({ style { fontSize(20.px); property("font-weight", "bold"); color(Color(SilkColors.textPrimary)) } }) { Text("隙光拾暖") }
            Span({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)) } }) { Text("WeChat Mini-Program") }
        }
    }
}

@Composable
private fun ConfigBar(serverUrl: String, apiToken: String, serverOk: Boolean?, lastSync: String?, onChange: (String, String) -> Unit, onTestResult: (Boolean?) -> Unit) {
    val scope = rememberCoroutineScope()
    Div({
        style {
            padding(12.px, 24.px); backgroundColor(Color(SilkColors.surface))
            property("border-bottom", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex); alignItems(AlignItems.Center); property("gap", "12px"); property("flex-wrap", "wrap")
        }
    }) {
        Span({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)); property("white-space", "nowrap") } }) { Text("服务器:") }
        Input(InputType.Text) {
            style { height(32.px); fontSize(13.px); padding(4.px, 8.px); property("border", "1px solid ${SilkColors.border}"); borderRadius(4.px); minWidth(200.px) }
            value(serverUrl)
            onInput { onChange(it.value, apiToken) }
        }
        Span({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)); property("white-space", "nowrap") } }) { Text("Token:") }
        Input(InputType.Password) {
            style { height(32.px); fontSize(13.px); padding(4.px, 8.px); property("border", "1px solid ${SilkColors.border}"); borderRadius(4.px); minWidth(180.px) }
            value(apiToken)
            onInput { onChange(serverUrl, it.value) }
        }
        Button({
            style { height(32.px); padding(4.px, 16.px); fontSize(13.px); backgroundColor(Color(SilkColors.primary)); color(Color.white); property("border", "none"); borderRadius(4.px); property("cursor", "pointer") }
            onClick {
                scope.launch {
                    val ok = pingHealth(serverUrl)
                    onTestResult(ok)
                }
            }
        }) { Text("检测连接") }
        Span({ style { fontSize(13.px); color(if (serverOk == true) Color(SilkColors.success) else Color(SilkColors.error)) } }) {
            Text(if (serverOk == true) "✅ 已连接" else if (serverOk == false) "❌ 连接失败" else "")
        }
        if (lastSync != null) {
            Span({ style { fontSize(12.px); color(Color(SilkColors.textLight)) } }) { Text("上次: $lastSync") }
        }
    }
}

@Composable
private fun TabNav(current: String, onSelect: (String) -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex); property("border-bottom", "1px solid ${SilkColors.border}")
            backgroundColor(Color(SilkColors.surface))
        }
    }) {
        for ((id, label) in listOf("diaries" to "📔 日记", "stories" to "📖 故事", "bridge" to "🔗 桥接")) {
            val active = current == id
            Div({
                style {
                    padding(10.px, 20.px); fontSize(14.px); property("cursor", "pointer")
                    color(if (active) Color(SilkColors.textPrimary) else Color(SilkColors.textSecondary))
                    property("font-weight", if (active) "bold" else "normal")
                    property("border-bottom", if (active) "2px solid ${SilkColors.primary}" else "0px")
                    property("transition", "all 0.2s")
                }
                onClick { onSelect(id) }
            }) { Text(label) }
        }
    }
}

@Composable
private fun DiaryListPane(items: List<GapDiary>, loading: Boolean, err: String?, onRefresh: () -> Unit) {
    Div({ style { property("max-width", "800px"); property("margin", "0 auto") } }) {
        PaneHeader("📔 日记", loading, onRefresh)
        if (err != null) errorBanner(err)
        if (items.isEmpty() && !loading && err == null) { emptyHint("暂无日记。配置 Token 后点击刷新。"); return@Div }
        items.forEach { e ->
            itemCard {
                Row2 {
                    Span({ style { fontSize(13.px); property("font-weight", "bold"); color(Color(SilkColors.primary)) } }) { Text(e.date) }
                    Span({ style { fontSize(12.px); color(Color(SilkColors.textLight)) } }) { Text("#${e.id.take(8)}") }
                }
                if (e.moodState.isNotBlank() || e.weather.isNotBlank()) {
                    Row2 {
                        if (e.moodState.isNotBlank()) {
                            Span({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)) } }) { Text("😊 ${e.moodState}${if (e.moodCategory.isNotBlank()) " · ${e.moodCategory}" else ""}") }
                        }
                        if (e.weather.isNotBlank()) {
                            Span({ style { fontSize(12.px); color(Color(SilkColors.textSecondary)) } }) { Text("🌤 ${e.weather}") }
                        }
                    }
                }
                if (e.hasWarmth && e.matchedStoryId.isNotBlank()) {
                    Div({ style { fontSize(12.px); color(Color(SilkColors.success)); marginBottom(4.px) } }) { Text("✨ 匹配温暖故事: ${e.matchedStoryId}") }
                }
                Div({ style { fontSize(14.px); color(Color(SilkColors.textPrimary)); property("line-height", "1.6") } }) {
                    Text(e.content.take(300) + if (e.content.length > 300) "…" else "")
                }
            }
        }
    }
}

@Composable
private fun StoryListPane(items: List<GapStory>, loading: Boolean, err: String?, onRefresh: () -> Unit) {
    Div({ style { property("max-width", "800px"); property("margin", "0 auto") } }) {
        PaneHeader("📖 故事长廊", loading, onRefresh)
        if (err != null) errorBanner(err)
        if (items.isEmpty() && !loading && err == null) { emptyHint("暂无已审核的故事。"); return@Div }
        items.forEach { s ->
            itemCard {
                Div({ style { fontSize(16.px); property("font-weight", "bold"); color(Color(SilkColors.textPrimary)); marginBottom(4.px) } }) { Text(s.title) }
                Div({ style { fontSize(13.px); color(Color(SilkColors.textSecondary)); marginBottom(8.px) } }) { Text("${s.figure} · ${s.authorName} · ${s.moodStates.joinToString("、")}") }
                Div({ style { fontSize(14.px); color(Color(SilkColors.textPrimary)); property("line-height", "1.6") } }) {
                    Text(s.content.take(150) + if (s.content.length > 150) "…" else "")
                }
            }
        }
    }
}

@Composable
private fun BridgeHelpPane() {
    Div({ style { property("max-width", "600px"); property("margin", "0 auto"); padding(20.px) } }) {
        Div({ style { fontSize(18.px); property("font-weight", "bold"); color(Color(SilkColors.textPrimary)); marginBottom(16.px) } }) { Text("🔗 桥接状态") }
        itemCard {
            Text("当隙光拾暖后端的 silk_gateway.js 运行时："); Br()
            Ul({ style { marginTop(8.px); property("padding-left", "20px") } }) {
                Li { Text("Silk 聊天中可使用 /gap 命令查看/操作隙光拾暖数据") }
                Li { Text("隙光拾暖中可收到 Silk 的回复通知") }
                Li { Text("本页面可直接查看隙光拾暖中的日记和故事") }
            }
        }
    }
}

@Composable
private fun PaneHeader(title: String, loading: Boolean, onRefresh: () -> Unit) {
    Div({
        style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); alignItems(AlignItems.Center); marginBottom(16.px) }
    }) {
        H3({ style { margin(0.px); color(Color(SilkColors.textPrimary)) } }) { Text(title) }
        Button({
            style { height(32.px); padding(4.px, 16.px); fontSize(13.px); property("cursor", "pointer"); backgroundColor(Color(SilkColors.primary)); color(Color.white); property("border", "none"); borderRadius(4.px) }
            onClick { onRefresh() }
        }) { Text(if (loading) "加载中..." else "刷新") }
    }
}

@Composable
private fun errorBanner(msg: String) {
    Div({
        style { padding(12.px); backgroundColor(Color("#FFF0F0")); property("border", "1px solid ${SilkColors.error}"); borderRadius(6.px); marginBottom(12.px); fontSize(13.px); color(Color(SilkColors.error)) }
    }) { Text(msg) }
}

@Composable
private fun emptyHint(text: String) {
    Div({ style { padding(40.px); property("text-align", "center"); color(Color(SilkColors.textLight)); fontSize(14.px) } }) { Text(text) }
}

@Composable
private fun itemCard(content: @Composable () -> Unit) {
    Div({ style { padding(16.px); marginBottom(8.px); backgroundColor(Color.white); borderRadius(8.px); property("border", "1px solid ${SilkColors.border}") } }) { content() }
}

@Composable
private fun Row2(content: @Composable () -> Unit) {
    Div({ style { display(DisplayStyle.Flex); justifyContent(JustifyContent.SpaceBetween); marginBottom(8.px) } }) { content() }
}

// ---- Data classes ----

@Serializable
data class GapDiary(
    val id: String = "",
    val date: String = "",
    val content: String = "",
    val timestamp: Long = 0L,
    val moodCategory: String = "",
    val moodState: String = "",
    val weather: String = "",
    val hasWarmth: Boolean = false,
    val matchedStoryId: String = "",
)

@Serializable
data class GapDiariesResponse(
    val diaries: List<GapDiary> = emptyList(),
    val deletedIds: List<String> = emptyList(),
)

@Serializable
data class GapStory(
    val _id: String = "",
    val title: String = "",
    val figure: String = "",
    val content: String = "",
    val status: String = "",
    val createdAt: String = "",
    val authorName: String = "",
    val moodStates: List<String> = emptyList(),
)

@Serializable
data class GapStoriesResponse(
    val stories: List<GapStory> = emptyList(),
)

// ---- Fetch helpers using window.fetch + await ----

private suspend fun pingHealth(baseUrl: String): Boolean? {
    return try {
        val resp = window.fetch("$baseUrl/health", js("{mode:'cors'}")).await()
        resp.ok as Boolean
    } catch (_: dynamic) { null }
}

private suspend fun fetchDiaries(baseUrl: String, token: String, onResult: (List<GapDiary>, String?) -> Unit) {
    if (token.isBlank()) { onResult(emptyList(), "请先配置 API Token"); return }
    try {
        val resp = window.fetch("$baseUrl/diaries", js("{headers:{'Authorization':'Bearer '+token}}")).await()
        if ((resp.ok as Boolean) != true) { onResult(emptyList(), "HTTP ${resp.status}"); return }
        val text = resp.text().await() as String
        val data = json.decodeFromString<GapDiariesResponse>(text)
        onResult(data.diaries.sortedByDescending { it.timestamp }.take(50), null)
    } catch (e: dynamic) {
        onResult(emptyList(), e.message?.toString() ?: "请求失败")
    }
}

private suspend fun fetchStories(baseUrl: String, onResult: (List<GapStory>, String?) -> Unit) {
    try {
        val resp = window.fetch("$baseUrl/stories", js("{mode:'cors'}")).await()
        if ((resp.ok as Boolean) != true) { onResult(emptyList(), "HTTP ${resp.status}"); return }
        val text = resp.text().await() as String
        val data = json.decodeFromString<GapStoriesResponse>(text)
        onResult(data.stories.take(50), null)
    } catch (e: dynamic) {
        onResult(emptyList(), e.message?.toString() ?: "请求失败")
    }
}

private fun nowStr(): String = js("new Date().toLocaleString('zh-CN')") as String
