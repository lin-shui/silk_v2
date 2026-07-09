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
import com.silk.shared.models.ContentBlock
import kotlinx.coroutines.launch
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
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
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

fun backendHttpOrigin(): String {
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

@Suppress("UnusedPrivateMember")
private fun parseFileNameFromContentDisposition(contentDisposition: String?): String? {
    if (contentDisposition.isNullOrBlank()) return null
    val fileNameStar = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
        .find(contentDisposition)
        ?.groupValues
        ?.getOrNull(1)
    if (!fileNameStar.isNullOrBlank()) {
        return fileNameStar.replace("%20", " ")
    }
    return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
        .find(contentDisposition)
        ?.groupValues
        ?.getOrNull(1)
}

/** Strip provider prefix and middle-ellipsis long model ids for compact badges. */
private fun compactModelId(modelId: String, maxLen: Int = 40): String {
    val bare = modelId.substringAfterLast('/').trim()
    if (bare.length <= maxLen) return bare
    val keep = (maxLen - 1) / 2
    return bare.take(keep) + "…" + bare.takeLast(maxLen - keep - 1)
}

fun main() {
    console.log("showCropOverlay init")
    js("""
(function() {
    window.showCropOverlay = function(dataUrl, sessionId, userId, uploadUrl) {
        var MASK_COLOR = 'rgba(0,0,0,0.55)';
        var SELECTION_BORDER = '#3b82f6';
        var HANDLE_SIZE = 8;
        var TOOLBAR_HEIGHT = 44;

        var isSelecting = false;
        var isDragging = false;
        var startX = 0, startY = 0;
        var endX = 0, endY = 0;
        var hasSelection = false;
        var dragHandle = null;
        var dragOffsetX = 0, dragOffsetY = 0;

        var overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;z-index:99999;background:' + MASK_COLOR + ';display:flex;align-items:center;justify-content:center;user-select:none;';

        var img = new Image();
        img.onload = function() {
            var imgW = img.width;
            var imgH = img.height;
            var vw = window.innerWidth;
            var vh = window.innerHeight;
            var padX = 60;
            var padY = 80;
            var scale = Math.min((vw - padX) / imgW, (vh - padY) / imgH, 1);
            var dispW = Math.round(imgW * scale);
            var dispH = Math.round(imgH * scale);

            overlay.innerHTML = '';

            var wrapper = document.createElement('div');
            wrapper.style.cssText = 'position:relative;display:inline-block;border-radius:4px;overflow:hidden;box-shadow:0 4px 40px rgba(0,0,0,0.5);';

            var dispImg = document.createElement('img');
            dispImg.src = dataUrl;
            dispImg.style.cssText = 'display:block;width:' + dispW + 'px;height:' + dispH + 'px;';
            wrapper.appendChild(dispImg);

            var canvas = document.createElement('canvas');
            canvas.width = dispW;
            canvas.height = dispH;
            canvas.style.cssText = 'position:absolute;top:0;left:0;cursor:crosshair;';
            var ctx = canvas.getContext('2d');
            wrapper.appendChild(canvas);

            // toolbar: positioned OUTSIDE the wrapper (overflow:hidden would clip it)
            var toolbar = document.createElement('div');
            toolbar.style.cssText = 'margin-top:12px;display:none;align-items:center;gap:12px;background:#1e1e1e;padding:6px 16px;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,0.3);';
            toolbar.innerHTML = '<span id="crop-dims" style="color:#aaa;font-size:13px;margin-right:4px;"></span>'
                + '<button id="crop-confirm" style="background:#3b82f6;color:#fff;border:none;padding:6px 20px;border-radius:6px;font-size:14px;cursor:pointer;">✓ Confirm</button>'
                + '<button id="crop-cancel" style="background:transparent;color:#ccc;border:1px solid #555;padding:6px 20px;border-radius:6px;font-size:14px;cursor:pointer;">Cancel</button>';

            overlay.style.flexDirection = 'column';
            overlay.appendChild(wrapper);
            overlay.appendChild(toolbar);
            document.body.appendChild(overlay);

            function imgCoords(e) {
                var rect = canvas.getBoundingClientRect();
                return { x: e.clientX - rect.left, y: e.clientY - rect.top };
            }

            function actualCoords(dispX, dispY) {
                return { x: Math.round(dispX / scale), y: Math.round(dispY / scale) };
            }

            function clamp(v, min, max) { return Math.min(Math.max(v, min), max); }

            function redraw() {
                ctx.clearRect(0, 0, dispW, dispH);
                if (!hasSelection) return;
                var l = Math.min(startX, endX);
                var t = Math.min(startY, endY);
                var r = Math.max(startX, endX);
                var b = Math.max(startY, endY);
                var w = r - l;
                var h = b - t;
                if (w < 1 || h < 1) return;

                ctx.fillStyle = MASK_COLOR;
                ctx.fillRect(0, 0, dispW, t);
                ctx.fillRect(0, b, dispW, dispH - b);
                ctx.fillRect(0, t, l, h);
                ctx.fillRect(r, t, dispW - r, h);

                ctx.strokeStyle = SELECTION_BORDER;
                ctx.lineWidth = 2;
                ctx.strokeRect(l, t, w, h);

                ctx.fillStyle = '#fff';
                ctx.strokeStyle = SELECTION_BORDER;
                ctx.lineWidth = 1.5;
                var handles = [
                    { x: l, y: t }, { x: r, y: t },
                    { x: l, y: b }, { x: r, y: b }
                ];
                for (var i = 0; i < handles.length; i++) {
                    ctx.fillRect(handles[i].x - HANDLE_SIZE/2, handles[i].y - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE);
                    ctx.strokeRect(handles[i].x - HANDLE_SIZE/2, handles[i].y - HANDLE_SIZE/2, HANDLE_SIZE, HANDLE_SIZE);
                }

                var actual = actualCoords(w, h);
                var dimsEl = document.getElementById('crop-dims');
                if (dimsEl) dimsEl.textContent = actual.x + ' × ' + actual.y;
            }

            function getSelectionRect() {
                var l = Math.min(startX, endX);
                var t = Math.min(startY, endY);
                var r = Math.max(startX, endX);
                var b = Math.max(startY, endY);
                return { l: l, t: t, r: r, b: b, w: r - l, h: b - t };
            }

            function hitTest(e) {
                var p = imgCoords(e);
                if (!hasSelection) return null;
                var r = getSelectionRect();
                var hh = HANDLE_SIZE / 2;
                if (Math.abs(p.x - r.l) <= hh && Math.abs(p.y - r.t) <= hh) return 'tl';
                if (Math.abs(p.x - r.r) <= hh && Math.abs(p.y - r.t) <= hh) return 'tr';
                if (Math.abs(p.x - r.l) <= hh && Math.abs(p.y - r.b) <= hh) return 'bl';
                if (Math.abs(p.x - r.r) <= hh && Math.abs(p.y - r.b) <= hh) return 'br';
                if (p.x >= r.l && p.x <= r.r && p.y >= r.t && p.y <= r.b) return 'move';
                return null;
            }

            canvas.addEventListener('mousedown', function(e) {
                var p = imgCoords(e);
                var hit = hitTest(e);
                if (hit === 'move') {
                    isDragging = true;
                    dragHandle = 'move';
                    dragOffsetX = p.x - startX;
                    dragOffsetY = p.y - startY;
                    canvas.style.cursor = 'grabbing';
                    return;
                }
                if (hit) {
                    isDragging = true;
                    dragHandle = hit;
                    dragOffsetX = p.x;
                    dragOffsetY = p.y;
                    return;
                }
                isSelecting = true;
                hasSelection = true;
                startX = p.x;
                startY = p.y;
                endX = p.x;
                endY = p.y;
                toolbar.style.display = 'none';
            });

            window.addEventListener('mousemove', function(e) {
                var p = imgCoords(e);
                if (isSelecting) {
                    endX = clamp(p.x, 0, dispW);
                    endY = clamp(p.y, 0, dispH);
                    redraw();
                    return;
                }
                if (isDragging) {
                    if (dragHandle === 'move') {
                        var dx = p.x - dragOffsetX;
                        var dy = p.y - dragOffsetY;
                        var r = getSelectionRect();
                        startX = clamp(dx, 0, dispW - r.w);
                        startY = clamp(dy, 0, dispH - r.h);
                        endX = startX + r.w;
                        endY = startY + r.h;
                    } else {
                        var r = getSelectionRect();
                        var fixL = Math.min(startX, endX);
                        var fixT = Math.min(startY, endY);
                        var fixR = Math.max(startX, endX);
                        var fixB = Math.max(startY, endY);
                        if (dragHandle === 'tl' || dragHandle === 'tr') startY = clamp(p.y, 0, fixB - 10);
                        if (dragHandle === 'bl' || dragHandle === 'br') endY = clamp(p.y, fixT + 10, dispH);
                        if (dragHandle === 'tl' || dragHandle === 'bl') startX = clamp(p.x, 0, fixR - 10);
                        if (dragHandle === 'tr' || dragHandle === 'br') endX = clamp(p.x, fixL + 10, dispW);
                    }
                    redraw();
                    return;
                }
                var h = hitTest(e);
                canvas.style.cursor = h === 'move' ? 'move' : h ? 'nwse-resize' : 'crosshair';
            });

            window.addEventListener('mouseup', function(e) {
                if (isSelecting || isDragging) {
                    isSelecting = false;
                    isDragging = false;
                    dragHandle = null;
                    var r = getSelectionRect();
                    if (r.w > 5 && r.h > 5) {
                        toolbar.style.display = 'flex';
                    } else {
                        hasSelection = false;
                        redraw();
                        toolbar.style.display = 'none';
                    }
                }
            });

            document.getElementById('crop-confirm').addEventListener('click', function() {
                var r = getSelectionRect();
                if (r.w < 2 || r.h < 2) return;
                var sx = Math.round(r.l / scale);
                var sy = Math.round(r.t / scale);
                var sw = Math.round(r.w / scale);
                var sh = Math.round(r.h / scale);
                var cropCanvas = document.createElement('canvas');
                cropCanvas.width = sw;
                cropCanvas.height = sh;
                var cropCtx = cropCanvas.getContext('2d');
                var fullImg = new Image();
                fullImg.onload = function() {
                    cropCtx.drawImage(fullImg, sx, sy, sw, sh, 0, 0, sw, sh);
                    cropCanvas.toBlob(function(blob) {
                        window.__pendingScreenshotBlob = blob;
                        document.body.removeChild(overlay);
                        if (window.__screenshotDone) { window.__screenshotDone(); window.__screenshotDone = undefined; }
                    }, 'image/png');
                };
                fullImg.src = dataUrl;
            });

            document.getElementById('crop-cancel').addEventListener('click', function() {
                document.body.removeChild(overlay);
                if (window.__screenshotDone) { window.__screenshotDone(); window.__screenshotDone = undefined; }
            });

            function onKeyDown(e) {
                if (e.key === 'Escape') {
                    document.body.removeChild(overlay);
                    if (window.__screenshotDone) { window.__screenshotDone(); window.__screenshotDone = undefined; }
                    window.removeEventListener('keydown', onKeyDown);
                }
            }
            window.addEventListener('keydown', onKeyDown);
        };
        img.src = dataUrl;
    };
})();
""")
    console.log("🧵 Silk 正在启动...")
    console.log("1️⃣ 准备渲染...")
    
    renderComposable(rootElementId = "root") {
        console.log("2️⃣ renderComposable 已调用")
        
        Style(SilkStylesheet)
        SilkChatStyles.inject()
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
    
    // 处理华为 OAuth 回调
    LaunchedEffect(Unit) {
        val handled = handleOAuthCallback(appState)
        if (handled) {
            console.log("✅ OAuth 回调处理完成")
        }
    }

    if (appState.currentScene == Scene.LOGIN) {
        LoginScene(appState)
    } else if (appState.currentScene == Scene.NICKNAME_SETUP) {
        NicknameSetupScene(appState)
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
                    property("overflow", if (appState.currentScene == Scene.SETTINGS) "auto" else "hidden")
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

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught", "SwallowedException")
@Composable
fun ChatScene(appState: WebAppState) {
    console.log("🎬 ChatScene被调用")
    
    val group = appState.selectedGroup
    val user = appState.currentUser
    val scope = rememberCoroutineScope()
    var userGroups by remember(user?.id) { mutableStateOf<List<Group>>(emptyList()) }
    var unreadCounts by remember(user?.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var isLoadingGroups by remember(user?.id) { mutableStateOf(true) }
    var sidebarCcStatus by remember(user?.id) { mutableStateOf<Map<String, CcConnectTokenInfo>>(emptyMap()) }
    var chatListWidth by remember { mutableStateOf(LayoutPrefs.getInt("silk_chat_list_w", 320)) }
    var chatListCollapsed by remember { mutableStateOf(LayoutPrefs.getBool("silk_chat_list_collapsed", false)) }
    ensureLayoutStylesInjected()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    val strings = com.silk.shared.i18n.getStrings(com.silk.shared.models.Language.CHINESE)
    
    console.log("   群组:", group?.name ?: "null")
    console.log("   用户:", user?.fullName ?: "null")
    
    if (group == null || user == null) {
        console.log("⚠️ 群组或用户为空，显示错误页面")
        Div({ style { padding(20.px) } }) {
            Text("错误：缺少群组或用户信息")
            Button({ onClick { appState.navigateBack() } }) {
                Text("返回")
            }
        }
        return
    }

    suspend fun refreshSidebarGroups() {
        isLoadingGroups = true
        try {
            val groupsResponse = ApiClient.getUserGroups(user.id)
            if (groupsResponse.success) {
                userGroups = (groupsResponse.groups ?: emptyList()).filterNot { it.name.startsWith("wf_") }
            }
            val unreadResponse = ApiClient.getUnreadCounts(user.id)
            if (unreadResponse.success) {
                unreadCounts = unreadResponse.unreadCounts
            }
            val statusMap = mutableMapOf<String, CcConnectTokenInfo>()
            userGroups.forEach { g ->
                val info = ApiClient.getCcConnectTokenInfo(g.id, user.id)
                if (info != null && info.success) {
                    statusMap[g.id] = info
                }
            }
            sidebarCcStatus = statusMap
        } catch (e: Exception) {
            console.error("❌ 加载聊天室群组列表失败:", e)
        } finally {
            isLoadingGroups = false
        }
    }

    LaunchedEffect(user.id, group.id) {
        refreshSidebarGroups()
    }

    // KB 内联引用：注册 window 桥，供聊天内 [[kb:...]] 链接点击跳转到知识库对应条目
    DisposableEffect(appState) {
        val bridge: (String?, String) -> Unit = { topicId, entryId ->
            appState.openKnowledgeBaseEntry(entryId = entryId, topicId = topicId)
        }
        window.asDynamic().__silkOpenKnowledgeBaseEntry = bridge
        onDispose {
            window.asDynamic().__silkOpenKnowledgeBaseEntry = null
        }
    }

    LaunchedEffect(user.id) {
        while (true) {
            kotlinx.coroutines.delay(30000)
            try {
                val unreadResponse = ApiClient.getUnreadCounts(user.id)
                if (unreadResponse.success) {
                    unreadCounts = unreadResponse.unreadCounts
                }
            } catch (e: Exception) {
                console.error("❌ 刷新未读消息失败:", e)
            }
        }
    }
    
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
        if (chatListCollapsed) {
            ReopenBar(onExpand = {
                chatListCollapsed = false
                LayoutPrefs.setBool("silk_chat_list_collapsed", false)
            })
        } else {
        Div({
            style {
                width(chatListWidth.px)
                property("flex-shrink", "0")
                property("border-right", "1px solid ${SilkColors.border}")
                backgroundColor(Color("rgba(255,255,255,0.88)"))
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("overflow", "hidden")
                property("backdrop-filter", "blur(6px)")
            }
        }) {
            Div({
                style {
                    padding(12.px, 16.px)
                    property("border-bottom", "1px solid ${SilkColors.border}")
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                }
            }) {
                Button({
                    attr("title", "收起列表")
                    style {
                        backgroundColor(Color("rgba(255,255,255,0.2)"))
                        color(Color(SilkColors.textSecondary))
                        property("border", "1px solid ${SilkColors.border}")
                        borderRadius(6.px)
                        padding(4.px, 9.px)
                        property("cursor", "pointer")
                        property("flex-shrink", "0")
                    }
                    onClick {
                        chatListCollapsed = true
                        LayoutPrefs.setBool("silk_chat_list_collapsed", true)
                    }
                }) { Text("«") }
                Span({
                    style {
                        fontSize(16.px)
                        color(Color(SilkColors.primary))
                        property("font-weight", "700")
                        property("letter-spacing", "1px")
                        property("flex-shrink", "0")
                    }
                }) {
                    Text("Silk")
                }
                Div({
                    style {
                        property("flex", "1")
                    }
                })
                Span({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                        property("white-space", "nowrap")
                        property("max-width", "120px")
                    }
                }) {
                    Text(user.fullName)
                }
                // 🤖 与 Silk 对话按钮
                Button({
                    style {
                        padding(4.px, 8.px)
                        backgroundColor(Color("#7BA8C9"))
                        color(Color.white)
                        border { width(0.px) }
                        borderRadius(6.px)
                        property("cursor", "pointer")
                        property("box-shadow", "0 2px 8px rgba(123, 168, 201, 0.4)")
                        fontSize(14.px)
                        property("flex-shrink", "0")
                    }
                    onClick {
                        scope.launch {
                            val uid = appState.currentUser?.id ?: return@launch
                            val r = ApiClient.startSilkPrivateChat(uid)
                            if (r.success && r.group != null) appState.selectGroup(r.group!!)
                        }
                    }
                }) { Text("🤖") }
                
                // ☰ 下拉菜单
                var showMenu by remember { mutableStateOf(false) }
                Div({
                    style {
                        position(Position.Relative)
                    }
                }) {
                    Button({
                        style {
                            padding(4.px, 10.px)
                            backgroundColor(Color("rgba(255,255,255,0.2)"))
                            color(Color(SilkColors.textPrimary))
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color(SilkColors.border))
                            }
                            borderRadius(6.px)
                            property("cursor", "pointer")
                            fontSize(16.px)
                        }
                        onClick { showMenu = !showMenu }
                    }) { Text("☰") }
                    
                    if (showMenu) {
                        Div({
                            style {
                                position(Position.Fixed)
                                property("top", "0")
                                property("left", "0")
                                property("right", "0")
                                property("bottom", "0")
                                property("z-index", "99")
                            }
                            onClick { showMenu = false }
                        })
                        Div({
                            style {
                                position(Position.Absolute)
                                property("top", "100%")
                                property("right", "0")
                                property("z-index", "100")
                                backgroundColor(Color("#2a2a2a"))
                                borderRadius(10.px)
                                property("box-shadow", "0 4px 20px rgba(0,0,0,0.3)")
                                property("min-width", "160px")
                                padding(6.px)
                                marginTop(6.px)
                            }
                            onClick { showMenu = false }
                        }) {
                            Div({
                                style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }
                                onClick { showCreateDialog = true; showMenu = false }
                            }) { Text("➕ ${strings.createButton}") }
                            Div({
                                style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }
                                onClick { showJoinDialog = true; showMenu = false }
                            }) { Text("🔗 ${strings.joinButton}") }
                            Div({
                                style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }
                                onClick { appState.navigateTo(Scene.CONTACTS); showMenu = false }
                            }) { Text("👤 ${strings.contactsButton}") }
                            Div({
                                style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }
                                onClick { appState.navigateTo(Scene.SETTINGS); showMenu = false }
                            }) { Text("⚙️ ${strings.settingsButton}") }
                            Div({
                                style { padding(10.px, 14.px); fontSize(14.px); color(Color.white); borderRadius(6.px); property("cursor", "pointer"); property("white-space", "nowrap") }
                                onClick { appState.logout(); showMenu = false }
                            }) { Text("🚪 ${strings.logoutButton}") }
                        }
                    }
                }
            }

            Div({
                style {
                    property("flex", "1")
                    property("overflow-y", "auto")
                    padding(10.px)
                }
            }) {
                when {
                    isLoadingGroups -> {
                        Div({
                            style {
                                color(Color(SilkColors.textSecondary))
                                fontSize(13.px)
                                textAlign("center")
                                padding(18.px)
                            }
                        }) {
                            Text("加载群组中...")
                        }
                    }
                    userGroups.isEmpty() -> {
                        Div({
                            style {
                                color(Color(SilkColors.textSecondary))
                                fontSize(13.px)
                                textAlign("center")
                                padding(18.px)
                            }
                        }) {
                            Text("暂无群组")
                        }
                    }
                    else -> {
                        val silkPrivateGroups = userGroups.filter { it.name.startsWith("[Silk]") }
                        val ccGroups = userGroups.filter { sidebarCcStatus.containsKey(it.id) }
                        val silkNormalGroups = userGroups.filter { !it.name.startsWith("[Silk]") && !sidebarCcStatus.containsKey(it.id) }

                        @Composable
                        fun renderGroupItem(item: Group) {
                            val isActive = item.id == group.id
                            val unread = unreadCounts[item.id] ?: 0
                            val ccInfo = sidebarCcStatus[item.id]
                            val isCcGroup = ccInfo != null
                            val isSilkPrivate = item.name.startsWith("[Silk]")
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
                                onClick {
                                    if (item.id != group.id) {
                                        scope.launch {
                                            ApiClient.markGroupAsRead(user.id, item.id)
                                            unreadCounts = unreadCounts - item.id
                                            appState.selectGroup(item)
                                        }
                                    }
                                }
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
                                    val typeBadge: String? = when {
                                        isCcGroup -> {
                                            val raw = (ccInfo?.agentType ?: "").lowercase().trim()
                                            when {
                                                raw.startsWith("claude") -> "claude"
                                                raw.startsWith("cursor") -> "cursor"
                                                raw.startsWith("gemini") -> "gemini"
                                                raw.startsWith("codex")  -> "codex"
                                                raw.startsWith("copilot") -> "copilot"
                                                raw.isBlank() -> "cc"
                                                else -> raw
                                            }
                                        }
                                        isSilkPrivate -> null
                                        else -> "silk"
                                    }
                                    if (typeBadge != null) {
                                        Span({
                                            style {
                                                fontSize(9.px)
                                                padding(1.px, 5.px)
                                                borderRadius(3.px)
                                                property("font-weight", "600")
                                                property("letter-spacing", "0.3px")
                                                property("flex-shrink", "0")
                                                if (isCcGroup) {
                                                    if (ccInfo?.connected == true) {
                                                        backgroundColor(Color("#E8F5E9"))
                                                        color(Color("#2E7D32"))
                                                    } else {
                                                        backgroundColor(Color("#FFF3E0"))
                                                        color(Color("#E65100"))
                                                    }
                                                } else {
                                                    backgroundColor(Color("rgba(201, 168, 108, 0.15)"))
                                                    color(Color(SilkColors.primary))
                                                }
                                            }
                                        }) {
                                            Text(if (isCcGroup && ccInfo?.connected != true) "$typeBadge ⏸" else typeBadge)
                                        }
                                    }
                                    if (unread > 0) {
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
                                            Text(if (unread > 99) "99+" else unread.toString())
                                        }
                                    }
                                }
                                // 非 Silk 专属对话才显示邀请码
                                if (!isSilkPrivate) {
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
                        }

                        // --- Section 1: Silk 专属对话 ---
                        if (silkPrivateGroups.isNotEmpty()) {
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "8px")
                                    marginBottom(8.px)
                                }
                            }) {
                                Span({
                                    style {
                                        fontSize(11.px)
                                        color(Color(SilkColors.primary))
                                        property("font-weight", "700")
                                        property("letter-spacing", "1px")
                                        property("white-space", "nowrap")
                                    }
                                }) { Text("Silk AI") }
                                Div({
                                    style {
                                        property("flex", "1")
                                        height(1.px)
                                        backgroundColor(Color(SilkColors.primary))
                                        property("opacity", "0.3")
                                    }
                                })
                            }
                            silkPrivateGroups.forEach { renderGroupItem(it) }
                        }

                        // --- Section 2: CC-Connect 群组 ---
                        if (ccGroups.isNotEmpty()) {
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "8px")
                                    marginTop(if (silkPrivateGroups.isNotEmpty()) 12.px else 0.px)
                                    marginBottom(8.px)
                                }
                            }) {
                                Span({
                                    style {
                                        fontSize(11.px)
                                        color(Color("#2E7D32"))
                                        property("font-weight", "700")
                                        property("letter-spacing", "1px")
                                        property("white-space", "nowrap")
                                    }
                                }) { Text("CC-Connect") }
                                Div({
                                    style {
                                        property("flex", "1")
                                        height(1.px)
                                        backgroundColor(Color("#4CAF50"))
                                        property("opacity", "0.3")
                                    }
                                })
                            }
                            ccGroups.forEach { renderGroupItem(it) }
                        }

                        // --- Section 3: Silk 普通群组 ---
                        if (silkNormalGroups.isNotEmpty()) {
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "8px")
                                    marginTop(if (silkPrivateGroups.isNotEmpty() || ccGroups.isNotEmpty()) 12.px else 0.px)
                                    marginBottom(8.px)
                                }
                            }) {
                                Span({
                                    style {
                                        fontSize(11.px)
                                        color(Color(SilkColors.textSecondary))
                                        property("font-weight", "700")
                                        property("letter-spacing", "1px")
                                        property("white-space", "nowrap")
                                    }
                                }) { Text("Silk Groups") }
                                Div({
                                    style {
                                        property("flex", "1")
                                        height(1.px)
                                        backgroundColor(Color(SilkColors.textSecondary))
                                        property("opacity", "0.2")
                                    }
                                })
                            }
                            silkNormalGroups.forEach { renderGroupItem(it) }
                        }
                    }
                }
            }
        }
        ColumnResizer(
            isLeftPanel = true,
            minWidth = 220,
            maxWidth = 520,
            currentWidth = { chatListWidth },
            onResize = { chatListWidth = it },
            onCommit = { LayoutPrefs.setInt("silk_chat_list_w", chatListWidth) },
        )
        } // close 列表非折叠分支

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
    
    // 创建/加入群组对话框
    if (showCreateDialog) {
        CreateGroupDialog(
            appState = appState,
            strings = strings,
            onDismiss = { showCreateDialog = false },
            onGroupCreated = { newGroup ->
                userGroups = userGroups + newGroup
            },
            onComplete = { showCreateDialog = false }
        )
    }
    if (showJoinDialog) {
        JoinGroupDialog(
            appState = appState,
            strings = strings,
            onDismiss = { showJoinDialog = false },
            onGroupJoined = { newGroup ->
                userGroups = userGroups + newGroup
                showJoinDialog = false
            }
        )
    }
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

@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "TooGenericExceptionCaught", "SwallowedException", "UnusedPrivateProperty")
@Composable
fun ChatAppWithGroup(user: User, group: Group, appState: WebAppState) {
    console.log("🎯 ChatAppWithGroup - 用户:", user.fullName, "群组:", group.name)
    
    val scope = rememberCoroutineScope()
    
    // Language and strings
    var userLanguage by remember { mutableStateOf<com.silk.shared.models.Language>(com.silk.shared.models.Language.CHINESE) }
    val strings = com.silk.shared.i18n.getStrings(userLanguage)
    
    // Load user language preference
    // Reload when user changes OR when navigating to chat scene
    LaunchedEffect(user.id, appState.currentScene) {
        if (appState.currentScene == Scene.CHAT_ROOM) {
            scope.launch {
                try {
                    val response = ApiClient.getUserSettings(user.id)
                    if (response.success && response.settings != null) {
                        userLanguage = response.settings!!.language
                    }
                } catch (e: Exception) {
                    console.error("Failed to load user settings:", e)
                }
            }
        }
    }
    
    // cc-connect status for this group
    var ccConnectInfo by remember(group.id) { mutableStateOf<CcConnectTokenInfo?>(null) }
    LaunchedEffect(group.id) {
        val info = ApiClient.getCcConnectTokenInfo(group.id, user.id)
        if (info != null && info.success) ccConnectInfo = info
    }

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
    val ccMetadataJson by chatClient.ccMetadataJson.collectAsState()
    val contentBlocks by chatClient.transientContentBlocks.collectAsState()
    val interactiveOptions by chatClient.interactiveOptions.collectAsState()

    // Update ccConnectInfo when metadata arrives via WebSocket
    LaunchedEffect(ccMetadataJson) {
        val raw = ccMetadataJson ?: return@LaunchedEffect
        try {
            val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<CcMetadataEvent>(raw)
            val current = ccConnectInfo ?: return@LaunchedEffect
            ccConnectInfo = current.copy(
                mode = parsed.mode ?: current.mode,
                model = parsed.model ?: current.model,
                availableModes = if (parsed.availableModes.isNullOrEmpty()) current.availableModes else parsed.availableModes,
                availableModels = if (parsed.availableModels.isNullOrEmpty()) current.availableModels else parsed.availableModels,
            )
        } catch (e: Exception) {
            console.log("cc_metadata parse error:", e)
        }
    }
    // Track if we've sent the default instruction for this session
    var hasSentDefaultInstruction by remember { mutableStateOf(false) }
    
    var messageText by remember { mutableStateOf("") }
    var kbContextSelection by remember(group.id) { mutableStateOf(KnowledgeBaseContextSelection()) }
    var kbPersistentExcludedSpaceIds by remember(group.id) { mutableStateOf<List<String>>(emptyList()) }
    var kbContextSelectionTouched by remember(group.id) { mutableStateOf(false) }
    var kbCaptureDraft by remember { mutableStateOf<KnowledgeCaptureDraft?>(null) }
    var kbCaptureTopics by remember { mutableStateOf<List<KBTopicItem>>(emptyList()) }
    var kbCaptureGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var kbCaptureSelectedSpaceId by remember { mutableStateOf(PERSONAL_SPACE_ID) }
    var kbCaptureSelectedTopicId by remember { mutableStateOf("") }
    var kbCaptureTitle by remember { mutableStateOf("") }
    var kbCaptureContent by remember { mutableStateOf("") }
    var kbCaptureSaving by remember { mutableStateOf(false) }
    var kbCaptureResult by remember { mutableStateOf<String?>(null) }
    val resetKnowledgeCaptureDialog: () -> Unit = {
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
    val onCaptureToKnowledgeBase: (Message) -> Unit = { message ->
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
    }
    LaunchedEffect(user.id, group.id) {
        kbPersistentExcludedSpaceIds = ApiClient.getKBContextPreferences(user.id).excludedSpaceIds
        if (!kbContextSelectionTouched) {
            kbContextSelection = mergeKnowledgeBaseContextSelectionWithPersistentSpaces(
                restoredSelection = kbContextSelection.takeIf {
                    it.pinnedEntryIds.isNotEmpty() || it.excludedEntryIds.isNotEmpty()
                },
                persistentExcludedSpaceIds = kbPersistentExcludedSpaceIds,
            )
        }
    }
    LaunchedEffect(messages.size, user.id, kbPersistentExcludedSpaceIds, kbContextSelectionTouched) {
        if (kbContextSelectionTouched) return@LaunchedEffect
        if (kbContextSelection.pinnedEntryIds.isNotEmpty() || kbContextSelection.excludedEntryIds.isNotEmpty()) {
            return@LaunchedEffect
        }
        val restoredSelection = latestKnowledgeBaseContextSelection(messages, user.id) ?: return@LaunchedEffect
        kbContextSelection = mergeKnowledgeBaseContextSelectionWithPersistentSpaces(
            restoredSelection = restoredSelection,
            persistentExcludedSpaceIds = kbPersistentExcludedSpaceIds,
        )
    }
    var showInvitationDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var pendingPasteImage by remember { mutableStateOf<dynamic?>(null) }
    var pendingPasteImageUrl by remember { mutableStateOf<String?>(null) }
    var isScreenshotCrop by remember { mutableStateOf(false) }
    var isExportingMarkdown by remember { mutableStateOf(false) }
    var exportMarkdownHint by remember { mutableStateOf<String?>(null) }
    var showFolderExplorer by remember { mutableStateOf(false) }
    var folderFiles by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    
    // Drag-and-drop state
    var isDraggingOver by remember { mutableStateOf(false) }

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
    
    // cc-connect token dialog
    var showCcConnectTokenDialog by remember { mutableStateOf(false) }

    // cc-connect mode/model dropdown state
    var showModeDropdown by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }

    // 消息撤回相关状态：正在撤回中的消息ID集合，防止重复点击
    var recallingMessageIds by remember { mutableStateOf(setOf<String>()) }
    
    // 消息选择模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(setOf<String>()) }
    
    // Escape 键退出选择模式
    DisposableEffect(Unit) {
        val handler: (org.w3c.dom.events.Event) -> Unit = { event ->
            val key = event.asDynamic().key as? String
            if (key == "Escape" && isSelectionMode) {
                isSelectionMode = false
                selectedMessageIds = emptySet()
            }
        }
        window.addEventListener("keydown", handler)
        onDispose { window.removeEventListener("keydown", handler) }
    }
    
    // 消息转发相关状态
    var showForwardDialog by remember { mutableStateOf(false) }
    var messageToForward by remember { mutableStateOf<Message?>(null) }
    var userGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoadingGroups by remember { mutableStateOf(false) }
    var forwardResult by remember { mutableStateOf<String?>(null) }
    
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
    
    LaunchedEffect(group.id) {
        console.log("🔌 准备建立WebSocket连接...")
        console.log("   群组ID:", group.id, "群组名:", group.name)
        
        hasSentDefaultInstruction = false
        chatClient.clearMessages()
        
        // 并行：加载群成员 + 建立 WebSocket，互不阻塞
        launch {
            try {
                val membersResponse = ApiClient.getGroupMembers(group.id)
                groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                console.log("✅ 群成员列表已加载，共 ${groupMembers.size} 人")
            } catch (e: dynamic) {
                console.error("❌ 加载群成员列表失败:", e.toString())
            }
        }
        
        try {
            console.log("🔌 开始连接WebSocket...")
            val jwt = JwtManager.getAccessToken()
            chatClient.connect(user.id, user.fullName, group.id, token = jwt)
            console.log("✅ WebSocket连接成功")
        } catch (e: dynamic) {
            console.error("❌ WebSocket连接失败:", e.toString())
        }
    }
    
    DisposableEffect(group.id) {
        onDispose {
            // connect() 内部会静默断开旧连接，此处只负责标记已读
            try {
                scope.launch {
                    try {
                        ApiClient.markGroupAsRead(user.id, group.id)
                        console.log("✅ 清理：已标记群组为已读")
                    } catch (_: dynamic) {}
                }
            } catch (_: dynamic) {}
        }
    }
    
    val activeChatNavigationTarget = appState.chatNavigationTarget?.takeIf { it.groupId == group.id }

    // 自动滚动到底部 — 包括 contentBlocks（cc-connect / Claudian 流式回复的主要通道）
    // 以及 interactiveOptions（交互式按钮出现在底部时）
    LaunchedEffect(messages.size, transientMessage, statusMessages.size, contentBlocks, interactiveOptions, activeChatNavigationTarget?.requestId) {
        if (activeChatNavigationTarget?.messageId?.isNullOrBlank() == false) return@LaunchedEffect
        js("""
            setTimeout(function() {
                var messagesContainer = document.getElementById('messages');
                if (messagesContainer) {
                    messagesContainer.scrollTop = messagesContainer.scrollHeight;
                }
            }, 100);
        """)
    }

    LaunchedEffect(activeChatNavigationTarget?.requestId, messages.size, transientMessage?.id) {
        val target = activeChatNavigationTarget ?: return@LaunchedEffect
        val messageId = target.messageId
        if (messageId.isNullOrBlank()) {
            appState.consumeChatNavigationTarget(target.requestId)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(80)
        if (scrollMessageIntoContainer(CHAT_MESSAGES_CONTAINER_ID, messageId)) {
            appState.consumeChatNavigationTarget(target.requestId)
        }
    }
    
    Div({ classes(SilkStylesheet.container) }) {
        // Header - 丝滑风格
        Div({ 
            classes(SilkStylesheet.header)
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "12px")
            }
        }) {
            // 返回按钮
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
                    console.log("👈 用户点击返回按钮")
                    scope.launch {
                        // 1. 先断开WebSocket连接
                        try {
                            console.log("🔌 正在断开WebSocket...")
                            chatClient.disconnect()
                            console.log("✅ WebSocket已断开")
                        } catch (e: dynamic) {
                            console.log("ℹ️ WebSocket断开（忽略错误）")
                        }
                        
                        // 2. 等待服务器完成所有消息处理
                        kotlinx.coroutines.delay(300)
                        
                        // 3. 最后标记已读 - 在断开连接之后调用
                        // 这样可以确保标记时间晚于用户发送的所有消息
                        try {
                            ApiClient.markGroupAsRead(user.id, group.id)
                            console.log("✅ 已标记群组为已读")
                        } catch (e: dynamic) {
                            console.log("⚠️ 标记已读失败")
                        }
                        
                        // 4. 返回到群组列表
                        console.log("📋 返回到群组列表")
                        appState.navigateBack()
                    }
                }
            }) {
                Text("←")
            }
            
            Div({ 
                style { 
                    property("flex", "1") 
                    display(DisplayStyle.Flex)
                    property("flex-direction", "column")
                    property("gap", "2px")
                } 
            }) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("gap", "8px")
                        property("letter-spacing", "2px")
                    }
                }) {
                    Text(group.name)
                    if (ccConnectInfo != null) {
                        Span({
                            style {
                                fontSize(10.px)
                                padding(2.px, 8.px)
                                borderRadius(4.px)
                                property("font-weight", "600")
                                property("letter-spacing", "0.5px")
                                property("cursor", "pointer")
                                property("transition", "opacity 0.2s ease")
                                if (ccConnectInfo?.connected == true) {
                                    backgroundColor(Color("#E8F5E9"))
                                    color(Color("#2E7D32"))
                                } else {
                                    backgroundColor(Color("#FFF3E0"))
                                    color(Color("#E65100"))
                                }
                            }
                            attr("title", "Click to view token & connection info")
                            onClick { showCcConnectTokenDialog = true }
                        }) {
                            val label = if (ccConnectInfo?.connected == true) {
                                val agentName = run {
                                    val raw = (ccConnectInfo?.agentType ?: "").lowercase().trim()
                                    when {
                                        raw.startsWith("claude") -> "Claude"
                                        raw.startsWith("cursor") -> "Cursor"
                                        raw.startsWith("gemini") -> "Gemini"
                                        raw.startsWith("codex")  -> "Codex"
                                        raw.startsWith("copilot") -> "Copilot"
                                        raw.isBlank() -> "agent"
                                        else -> raw
                                    }
                                }
                                "cc-connect ($agentName)"
                            } else {
                                "cc-connect (offline)"
                            }
                            Text(label)
                        }
                    }
                }
                val cwdText = ccConnectInfo?.cwd
                if (ccConnectInfo?.connected == true && !cwdText.isNullOrBlank()) {
                    Span({
                        style {
                            fontSize(11.px)
                            color(Color(SilkColors.textSecondary))
                            property("font-family", "monospace")
                            property("letter-spacing", "0")
                            property("overflow", "hidden")
                            property("text-overflow", "ellipsis")
                            property("white-space", "nowrap")
                        }
                        title(cwdText)
                    }) {
                        Text("📁 $cwdText")
                    }
                }
            }
            
            // 右侧按钮组
            if (isSelectionMode) {
                // 选择模式工具栏
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
                    
                    // 复制选中消息
                    Button({
                        style {
                            padding(8.px, 14.px)
                            backgroundColor(Color(if (selectedMessageIds.isNotEmpty()) "rgba(255,255,255,0.25)" else "rgba(255,255,255,0.10)"))
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", if (selectedMessageIds.isNotEmpty()) "pointer" else "default")
                            fontSize(13.px)
                            property("transition", "all 0.2s ease")
                        }
                        if (selectedMessageIds.isNotEmpty()) {
                            onClick {
                                val selectedContent = messages
                                    .filter { it.id in selectedMessageIds }
                                    .sortedBy { it.timestamp }
                                    .joinToString("\n\n") { "${it.userName}:\n${it.content}" }
                                if (selectedContent.isNotEmpty()) {
                                    copyTextToClipboard(selectedContent)
                                }
                                isSelectionMode = false
                                selectedMessageIds = emptySet()
                            }
                        }
                    }) {
                        Text("📋复制")
                    }
                    
                    // 删除选中消息
                    Button({
                        style {
                            padding(8.px, 14.px)
                            backgroundColor(Color(if (selectedMessageIds.isNotEmpty()) "rgba(255,80,80,0.35)" else "rgba(255,255,255,0.10)"))
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", if (selectedMessageIds.isNotEmpty()) "pointer" else "default")
                            fontSize(13.px)
                            property("transition", "all 0.2s ease")
                        }
                        if (selectedMessageIds.isNotEmpty()) {
                            onClick {
                                val idsToDelete = selectedMessageIds
                                scope.launch {
                                    chatClient.removeMessages(idsToDelete)
                                    for (msgId in idsToDelete) {
                                        try {
                                            ApiClient.deleteMessage(group.id, msgId, user.id)
                                        } catch (e: dynamic) {
                                            console.log("删除消息失败:", msgId, e)
                                        }
                                    }
                                }
                                isSelectionMode = false
                                selectedMessageIds = emptySet()
                            }
                        }
                    }) {
                        Text("🗑删除")
                    }
                    
                    // 转发选中消息
                    Button({
                        style {
                            padding(8.px, 14.px)
                            backgroundColor(Color(if (selectedMessageIds.isNotEmpty()) "rgba(255,255,255,0.25)" else "rgba(255,255,255,0.10)"))
                            color(Color.white)
                            border { width(0.px) }
                            borderRadius(8.px)
                            property("cursor", if (selectedMessageIds.isNotEmpty()) "pointer" else "default")
                            fontSize(13.px)
                            property("transition", "all 0.2s ease")
                        }
                        if (selectedMessageIds.isNotEmpty()) {
                            onClick {
                                val selectedContent = messages
                                    .filter { it.id in selectedMessageIds }
                                    .sortedBy { it.timestamp }
                                    .joinToString("\n\n") { "[${it.userName}] ${it.content}" }
                                val syntheticMsg = Message(
                                    id = "forward-multi",
                                    userId = user.id,
                                    userName = user.fullName,
                                    content = selectedContent,
                                    type = MessageType.TEXT,
                                    timestamp = js("Date.now()").unsafeCast<Double>().toLong()
                                )
                                messageToForward = syntheticMsg
                                scope.launch {
                                    isLoadingGroups = true
                                    val response = ApiClient.getUserGroups(user.id)
                                    userGroups = response.groups?.filter { it.id != group.id } ?: emptyList()
                                    isLoadingGroups = false
                                    showForwardDialog = true
                                }
                            }
                        }
                    }) {
                        Text("↗转发")
                    }
                    
                    // 取消选择
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
                            isSelectionMode = false
                            selectedMessageIds = emptySet()
                        }
                    }) {
                        Text("✕ 取消")
                    }
                }
            } else {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "10px")
                    alignItems(AlignItems.Center)
                }
            }) {
                // 📁 文件夹按钮 - 查看session文件
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
                    onClick {
                        showFolderExplorer = true
                        isLoadingFiles = true
                        // FolderExplorerDialog 会自动加载文件列表
                    }
                }) {
                    Text("📁")
                }

                // 📝 导出按钮 - 导出聊天为 Obsidian Markdown
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
                            isExportingMarkdown = true
                            exportMarkdownHint = "正在导出..."
                            try {
                                var vaultHandle: dynamic = null
                                if (ObsidianVaultManager.isSupported()) {
                                    vaultHandle = ObsidianVaultManager.getCachedHandleIfValid()
                                    if (vaultHandle == null) {
                                        exportMarkdownHint = "请选择 Obsidian Vault 目录..."
                                        vaultHandle = ObsidianVaultManager.pickVaultDirectory()
                                    }
                                }

                                exportMarkdownHint = "正在获取聊天记录..."
                                val result = ApiClient.exportGroupMarkdown(group.id, user.id)
                                if (!result.success) {
                                    exportMarkdownHint = "导出失败：${result.message}"
                                    window.alert("导出失败：${result.message}")
                                    return@launch
                                }
                                val fileName = result.fileName.ifBlank { "silk_group_${group.id}.md" }

                                if (vaultHandle != null) {
                                    exportMarkdownHint = "正在写入 Vault..."
                                    try {
                                        val relativePath = ObsidianVaultManager.saveToVault(
                                            vaultHandle, group.name, result.markdown, fileName
                                        )
                                        console.log("✅ 已导出到 Obsidian Vault:", relativePath)
                                        exportMarkdownHint = "已导出: $relativePath"
                                    } catch (t: Throwable) {
                                        console.warn("Vault 写入失败，回退到下载:", t)
                                        downloadAsFile(result.markdown, fileName)
                                        exportMarkdownHint = "Vault写入失败，已下载：$fileName"
                                    }
                                } else {
                                    downloadAsFile(result.markdown, fileName)
                                    console.log("✅ 聊天记录已导出:", fileName)
                                    exportMarkdownHint = "导出成功：$fileName"
                                }
                            } catch (t: Throwable) {
                                val msg = t.message ?: t.toString()
                                if (msg.contains("abort", ignoreCase = true)) {
                                    exportMarkdownHint = "已取消"
                                } else {
                                    console.error("❌ 导出异常:", t)
                                    exportMarkdownHint = "导出异常: $msg"
                                    window.alert("导出失败: $msg")
                                }
                            } finally {
                                isExportingMarkdown = false
                            }
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
                                try {
                                    ObsidianVaultManager.clearCachedHandle()
                                    ObsidianVaultManager.pickVaultDirectory()
                                    exportMarkdownHint = "Vault 目录已更新"
                                } catch (e: Exception) {
                                    if (e.message?.contains("abort", ignoreCase = true) != true) {
                                        exportMarkdownHint = "更换目录失败: ${e.message}"
                                    }
                                }
                            }
                        }
                    }) {
                        Text("📂")
                    }
                }
                
                // 非 Silk 专属对话才显示邀请、添加成员、查看成员按钮
                if (!group.name.startsWith("[Silk]")) {
                
                // 邀请按钮
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
                    onClick { showInvitationDialog = true }
                }) {
                    Text(strings.inviteButton)
                }
                
                // ➕ 添加成员按钮
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
                        // 加载联系人和群组成员
                        scope.launch {
                            isLoadingContacts = true
                            val contactsResponse = ApiClient.getContacts(user.id)
                            contacts = contactsResponse.contacts ?: emptyList()
                            val membersResponse = ApiClient.getGroupMembers(group.id)
                            // 将群主排在第一位
                            groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                            isLoadingContacts = false
                            showAddMemberDialog = true
                        }
                    }
                }) {
                    Text("➕")
                }
                
                // 👥 查看成员按钮
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
                        // 加载联系人和群组成员
                        scope.launch {
                            isLoadingContacts = true
                            val contactsResponse = ApiClient.getContacts(user.id)
                            contacts = contactsResponse.contacts ?: emptyList()
                            val membersResponse = ApiClient.getGroupMembers(group.id)
                            // 将群主排在第一位
                            groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                            isLoadingContacts = false
                            showMembersDialog = true
                        }
                    }
                }) {
                    Text(strings.membersButton)
                }
                } // end if !group.name.startsWith("[Silk]")
            }
            } // close else (non-selection mode)
        }
        
        // Auto-reconnect logic: on disconnect, refresh token then retry with exponential backoff
        var reconnectCount by remember { mutableStateOf(0) }
        LaunchedEffect(connectionState) {
            if (connectionState == ConnectionState.DISCONNECTED) {
                val maxRetries = 5
                while (reconnectCount < maxRetries) {
                    val delayMs = 2000L * (1L shl reconnectCount.coerceAtMost(4)) // 2,4,8,16,32s
                    kotlinx.coroutines.delay(delayMs)
                    reconnectCount++
                    console.log("🔄 [AutoReconnect] 尝试 $reconnectCount/$maxRetries ...")
                    try {
                        // 每次重连前尝试刷新 JWT（静默刷新，token 未过期也安全）
                        var jwt = JwtManager.getAccessToken()
                        if (!jwt.isNullOrBlank()) {
                            val refreshResult = ApiClient.refreshAccessToken()
                            if (refreshResult.success && refreshResult.accessToken != null) {
                                jwt = refreshResult.accessToken
                                JwtManager.setAccessToken(jwt)
                                if (refreshResult.refreshToken != null) {
                                    JwtManager.setRefreshToken(refreshResult.refreshToken)
                                }
                            } else {
                                // 刷新成功但无 token → refresh token 可能也过期了
                                console.log("🔒 [AutoReconnect] Token 刷新失败，停止重连")
                                break
                            }
                        } else {
                            // 没有 stored token → 无法继续
                            console.log("🔒 [AutoReconnect] 无存储的 Token，停止重连")
                            break
                        }
                        chatClient.connect(user.id, user.fullName, group.id, token = jwt)
                        kotlinx.coroutines.delay(500)
                        if (connectionState == ConnectionState.CONNECTED) {
                            console.log("✅ [AutoReconnect] 重连成功")
                            reconnectCount = 0
                            return@LaunchedEffect
                        }
                    } catch (e: Exception) {
                        console.log("⚠️ [AutoReconnect] 异常: ${e.message}")
                    }
                }
                console.log("🔒 [AutoReconnect] 全部重连失败，跳转登录页")
                appState.logout()
            }
        }
        
        // Status Bar - only show when not connected
        if (connectionState != ConnectionState.CONNECTED) {
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
                        ConnectionState.DISCONNECTED -> "✗ ${strings.disconnected} · 自动重连中..."
                        else -> ""
                    })
                }
            }
        }
        
        // Messages container with drag-and-drop support
        // flex: 1 spacer pushes content to bottom; overflow-y: auto enables scroll
        Div({ 
            classes(SilkStylesheet.messagesContainer)
            id(CHAT_MESSAGES_CONTAINER_ID)
            style {
                property("position", "relative")
                property("transition", "all 0.2s ease")
            }
        }) {
            // Spacer: flex: 1 pushes all subsequent content to the visual bottom
            Div({
                style {
                    property("flex", "1")
                }
            }) {}

            // 显示系统状态消息（灰色）；KB 上下文状态条改由输入区上方的 KnowledgeBaseContextTray 展示
            val visibleStatusMessages = statusMessages.filterNot(::isKnowledgeBaseContextStatusMessage)
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

            // 显示所有普通消息
            val lastMessageId = messages.lastOrNull()?.id
            messages.forEach { message ->
                MessageItem(
                    message = message,
                    isTransient = false,
                    isLastMessage = message.id == lastMessageId,
                    currentUserId = user.id,
                    currentUserName = user.fullName,
                    groupId = group.id,
                    isRecalling = message.id in recallingMessageIds,
                    chatClient = chatClient,
                    onRecall = { messageId ->
                        if (messageId !in recallingMessageIds) {
                            recallingMessageIds = recallingMessageIds + messageId
                            scope.launch {
                                try {
                                    val response = ApiClient.recallMessage(group.id, messageId, user.id)
                                    if (!response.success) {
                                        window.alert("撤回失败: ${response.message}")
                                    }
                                } catch (e: Exception) {
                                    console.error("❌ 撤回消息失败:", e)
                                    window.alert("撤回失败: ${e.message}")
                                } finally {
                                    recallingMessageIds = recallingMessageIds - messageId
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
                        messageToForward = msg
                        scope.launch {
                            isLoadingGroups = true
                            val response = ApiClient.getUserGroups(user.id)
                            userGroups = response.groups?.filter { it.id != group.id && !it.name.startsWith("wf_") } ?: emptyList()
                            isLoadingGroups = false
                            showForwardDialog = true
                        }
                    },
                    onDelete = { messageId ->
                        scope.launch {
                            try {
                                val response = ApiClient.deleteMessage(group.id, messageId, user.id)
                                if (!response.success) {
                                    window.alert("删除失败: ${response.message}")
                                }
                            } catch (e: Exception) {
                                console.error("❌ 删除消息失败:", e)
                                window.alert("删除失败: ${e.message}")
                            }
                        }
                    },
                    isSelectionMode = isSelectionMode,
                    isSelected = message.id in selectedMessageIds,
                    onToggleSelection = { id ->
                        selectedMessageIds = if (id in selectedMessageIds)
                            selectedMessageIds - id
                        else
                            selectedMessageIds + id
                    },
                    onEnterSelectionMode = { id ->
                        isSelectionMode = true
                        selectedMessageIds = setOf(id)
                    }
                )
            }

            // 显示临时消息（如果有）
            transientMessage?.let { message ->
                val isStructuredCcContent = message.content.contains("<!--CC_TURN-->")
                val shouldShowTransientMsg = message.content.isNotBlank() &&
                    message.currentStep == null &&
                    message.totalSteps == null &&
                    (isStructuredCcContent || !isLikelyAgentStatusContent(message.content))
                if (shouldShowTransientMsg) {
                    MessageItem(
                        message = message.copy(category = com.silk.shared.models.MessageCategory.NORMAL),
                        isTransient = true,
                        currentUserId = user.id,
                        groupId = group.id,
                        onCopy = { content ->
                            copyTextToClipboard(content)
                            console.log("✅ 消息已复制到剪贴板")
                        },
                        onCaptureToKnowledgeBase = onCaptureToKnowledgeBase,
                        onForward = { msg ->
                            messageToForward = msg
                            scope.launch {
                                isLoadingGroups = true
                                val response = ApiClient.getUserGroups(user.id)
                                userGroups = response.groups?.filter { it.id != group.id && !it.name.startsWith("wf_") } ?: emptyList()
                                isLoadingGroups = false
                                showForwardDialog = true
                            }
                        }
                    )
                } else {
                    TransientMessageItem(message)
                }
            }

            // 显示流式 content blocks（thinking / tool_use / text 结构化渲染）
            if (contentBlocks.isNotEmpty()) {
                Div({
                    classes(SilkStylesheet.aiMessageContent)
                    style { property("margin-top", "4px") }
                }) {
                    // Thinking block rendered outside the loop for stable composition identity
                    val thinkingBlock = contentBlocks.firstOrNull { it.type == "thinking" }
                    if (thinkingBlock != null) {
                        ThinkingBlock(content = thinkingBlock.content, isComplete = thinkingBlock.isComplete)
                    }
                    for (block in contentBlocks) {
                        when (block.type) {
                            "thinking" -> { /* rendered above */ }
                            "text" -> MarkdownContent(content = block.content, references = emptyList())
                            "tool_use" -> ToolCallBlock(name = block.toolName, summary = block.content, content = block.content)
                        }
                    }
                }
            }

            // 显示交互式按钮选项（cc-connect 提问）
            if (interactiveOptions.isNotEmpty()) {
                console.log("🔘 [UI] rendering InteractiveOptions: ${interactiveOptions.size} options: ${interactiveOptions.map { it.label }}")
                InteractiveOptions(
                    options = interactiveOptions,
                    onAnswer = { value ->
                        chatClient.sendCcAnswer(value)
                    }
                )
            }
        }

        // Drag-and-drop event handlers - directly manipulate DOM for immediate visual feedback
        DisposableEffect(group.id) {
            val sessionId = group.id
            val userId = user.id
            val uploadUrl = "${backendHttpOrigin()}/api/files/upload"
            val primaryColor = SilkColors.primary
            
            // Store values in window for JavaScript to access
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
                    
                    // Clean up existing handlers if any
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
                    
                    // Create overlay element for drag feedback
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
                    
                    // Store handlers for cleanup
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
        
        // Input区域（添加诊断按钮）- 丝滑风格
        if (connectionState == ConnectionState.CONNECTED) {
            Div({ 
                classes(SilkStylesheet.inputContainer)
                style {
                    display(DisplayStyle.Flex)
                    property("flex-direction", "column")
                    property("gap", "12px")
                }
            }) {
                val isSilkPrivateChat = group.name.startsWith("[Silk]")
                val isCcConnectGroup = ccConnectInfo != null
                val currentMemberRole = groupMembers.find { it.id == user.id }?.role
                val canTriggerCc = currentMemberRole == "HOST" || currentMemberRole == "OPERATOR"
                val showCcButton = isCcConnectGroup && groupMembers.size >= 2 && canTriggerCc
                val ccTriggerName = run {
                    val raw = (ccConnectInfo?.agentType ?: "").lowercase().trim()
                    when {
                        raw.startsWith("claude") -> "claude"
                        raw.startsWith("cursor") -> "cursor"
                        raw.startsWith("gemini") -> "gemini"
                        raw.startsWith("codex")  -> "codex"
                        raw.startsWith("copilot") -> "copilot"
                        raw.isBlank() -> "cc"
                        else -> raw
                    }
                }
                val ccPrefix = "@$ccTriggerName"

                // @Silk 快捷按钮（在 Silk 私聊和 cc-connect 群组中隐藏）
                if (!isSilkPrivateChat && !isCcConnectGroup) {
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
                                val currentText = messageText
                                val cursorPos = input.selectionStart ?: currentText.length
                                val beforeCursor = currentText.substring(0, cursorPos)
                                val afterCursor = currentText.substring(cursorPos)
                                messageText = "$beforeCursor@Silk $afterCursor"
                                window.setTimeout({
                                    val newPos = cursorPos + 6
                                    input.setSelectionRange(newPos, newPos)
                                    input.focus()
                                }, 0)
                            } else {
                                messageText = if (messageText.isEmpty() || messageText.endsWith(" ")) {
                                    "${messageText}@Silk "
                                } else {
                                    "${messageText} @Silk "
                                }
                            }
                        }
                    }) {
                        Text("@Silk")
                    }
                }
                }

                // 代理触发按钮（按连接的代理类型显示 @claude/@cursor 等，仅多人群 HOST/OPERATOR 可见）
                if (showCcButton) {
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("justify-content", "flex-start")
                        property("gap", "8px")
                        alignItems(AlignItems.Center)
                        property("flex-wrap", "wrap")
                    }
                }) {
                    Button({
                        style {
                            padding(6.px, 12.px)
                            backgroundColor(Color("rgba(76, 175, 80, 0.12)"))
                            color(Color("#4CAF50"))
                            border {
                                width(1.px)
                                style(LineStyle.Solid)
                                color(Color("#4CAF50"))
                            }
                            borderRadius(16.px)
                            property("cursor", "pointer")
                            fontSize(13.px)
                            property("font-weight", "500")
                            property("transition", "all 0.2s ease")
                            property("white-space", "nowrap")
                        }
                        onClick {
                            val prefixWithSpace = "$ccPrefix "
                            val input = document.getElementById("chat-input") as? org.w3c.dom.HTMLTextAreaElement
                            if (input != null) {
                                val currentText = messageText
                                val cursorPos = input.selectionStart ?: currentText.length
                                val beforeCursor = currentText.substring(0, cursorPos)
                                val afterCursor = currentText.substring(cursorPos)
                                messageText = "$beforeCursor$prefixWithSpace$afterCursor"
                                window.setTimeout({
                                    val newPos = cursorPos + prefixWithSpace.length
                                    input.setSelectionRange(newPos, newPos)
                                    input.focus()
                                }, 0)
                            } else {
                                messageText = if (messageText.isEmpty() || messageText.endsWith(" ")) {
                                    "$messageText$prefixWithSpace"
                                } else {
                                    "$messageText $prefixWithSpace"
                                }
                            }
                        }
                    }) {
                        Text(ccPrefix)
                    }

                    // mode/model badges inline with @agent button
                    if (ccConnectInfo?.connected == true) {
                        val modeKey = ccConnectInfo?.mode ?: ""
                        val modelName = ccConnectInfo?.model ?: ""

                        if (showModeDropdown || showModelDropdown) {
                            Div({
                                style {
                                    property("position", "fixed")
                                    property("top", "0"); property("left", "0")
                                    property("right", "0"); property("bottom", "0")
                                    property("z-index", "999")
                                }
                                onClick { showModeDropdown = false; showModelDropdown = false }
                            })
                        }

                        // Mode
                        if (modeKey.isNotBlank() || !ccConnectInfo?.availableModes.isNullOrEmpty()) {
                            val modeName = ccConnectInfo?.availableModes?.find { it.key == modeKey }?.name ?: modeKey
                            val (modeBg, modeFg, modeBorder) = when (modeKey) {
                                "force", "bypassPermissions", "yolo" -> Triple("#FFF0F0", "#C62828", "#FFCDD2")
                                "plan" -> Triple("#EDF4FF", "#1565C0", "#BBDEFB")
                                "ask" -> Triple("#F0FFF0", "#2E7D32", "#C8E6C9")
                                else -> Triple("#F7F7F7", "#555555", "#E0E0E0")
                            }
                            Div({ style { property("position", "relative"); display(DisplayStyle.InlineBlock) } }) {
                                Span({
                                    style {
                                        fontSize(13.px); padding(4.px, 12.px); borderRadius(14.px)
                                        property("font-weight", "500"); property("cursor", "pointer")
                                        property("transition", "all 0.15s ease"); property("user-select", "none")
                                        backgroundColor(Color(modeBg)); color(Color(modeFg))
                                        property("border", "1px solid $modeBorder")
                                    }
                                    attr("title", "Permission mode")
                                    onClick { showModeDropdown = !showModeDropdown; showModelDropdown = false }
                                }) { Text("⚙ ${modeName.ifBlank { "mode" }}") }
                                if (showModeDropdown && !ccConnectInfo?.availableModes.isNullOrEmpty()) {
                                    Div({
                                        style {
                                            property("position", "absolute"); property("bottom", "100%"); property("left", "0")
                                            property("z-index", "1000"); marginBottom(6.px)
                                            backgroundColor(Color.white); borderRadius(10.px)
                                            property("box-shadow", "0 4px 20px rgba(0,0,0,0.12)")
                                            property("min-width", "180px"); padding(6.px)
                                            property("border", "1px solid #E8E8E8")
                                        }
                                    }) {
                                        Div({ style { padding(6.px, 12.px); fontSize(11.px); color(Color("#999")); property("font-weight", "500"); property("text-transform", "uppercase"); property("letter-spacing", "0.5px") } }) { Text("Mode") }
                                        ccConnectInfo?.availableModes?.forEach { opt ->
                                            val isCurrent = opt.key == modeKey
                                            Div({
                                                style {
                                                    padding(8.px, 12.px); borderRadius(8.px); property("cursor", "pointer"); fontSize(14.px)
                                                    if (isCurrent) { backgroundColor(Color("#F0F4FF")); property("font-weight", "600"); color(Color("#1565C0")) }
                                                }
                                                onClick { showModeDropdown = false; if (!isCurrent) chatClient.sendCcCommand(user.id, "/mode ${opt.key}") }
                                                onMouseOver { (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = if (isCurrent) "#F0F4FF" else "#F5F5F5" }
                                                onMouseOut { (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = if (isCurrent) "#F0F4FF" else "" }
                                            }) { Text(if (isCurrent) "✓ ${opt.name}" else "  ${opt.name}") }
                                        }
                                    }
                                }
                            }
                        }

                        // Model
                        if (!ccConnectInfo?.availableModels.isNullOrEmpty()) {
                            val badgeModel = if (modelName.isNotBlank()) compactModelId(modelName) else "default"
                            val modelTitle = if (modelName.isNotBlank()) modelName else "agent default"
                            Div({ style { property("position", "relative"); display(DisplayStyle.InlineBlock); maxWidth(320.px) } }) {
                                Span({
                                    style {
                                        fontSize(13.px); padding(4.px, 12.px); borderRadius(14.px)
                                        property("font-weight", "500"); property("cursor", "pointer")
                                        property("transition", "all 0.15s ease"); property("user-select", "none")
                                        backgroundColor(Color("#F8F0FF")); color(Color("#6A1B9A"))
                                        property("border", "1px solid #E1BEE7")
                                        display(DisplayStyle.InlineBlock); maxWidth(100.percent)
                                        property("overflow", "hidden"); property("text-overflow", "ellipsis")
                                        property("white-space", "nowrap"); property("vertical-align", "middle")
                                        property("font-family", "ui-monospace, SFMono-Regular, Menlo, monospace")
                                    }
                                    attr("title", "Model: $modelTitle")
                                    onClick { showModelDropdown = !showModelDropdown; showModeDropdown = false }
                                }) { Text("🤖 $badgeModel") }
                                if (showModelDropdown) {
                                    Div({
                                        style {
                                            property("position", "absolute"); property("bottom", "100%"); property("left", "0")
                                            property("z-index", "1000"); marginBottom(6.px)
                                            backgroundColor(Color.white); borderRadius(10.px)
                                            property("box-shadow", "0 4px 20px rgba(0,0,0,0.12)")
                                            minWidth(360.px); property("max-width", "min(520px, 92vw)")
                                            property("max-height", "min(420px, 60vh)")
                                            property("overflow-y", "auto"); padding(6.px)
                                            property("border", "1px solid #E8E8E8")
                                        }
                                    }) {
                                        Div({ style { padding(6.px, 12.px); fontSize(11.px); color(Color("#999")); property("font-weight", "500"); property("text-transform", "uppercase"); property("letter-spacing", "0.5px") } }) { Text("Model") }
                                        ccConnectInfo?.availableModels?.forEach { opt ->
                                            val isCurrent = opt.name == modelName
                                            Div({
                                                style {
                                                    padding(8.px, 12.px); borderRadius(8.px); property("cursor", "pointer")
                                                    property("white-space", "normal"); property("word-break", "break-all")
                                                    if (isCurrent) { backgroundColor(Color("#F3E5F5")); color(Color("#6A1B9A")) }
                                                }
                                                onClick { showModelDropdown = false; if (!isCurrent) chatClient.sendCcCommand(user.id, "/model switch ${opt.name}") }
                                                onMouseOver { (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = if (isCurrent) "#F3E5F5" else "#F5F5F5" }
                                                onMouseOut { (it.target as? org.w3c.dom.HTMLElement)?.style?.backgroundColor = if (isCurrent) "#F3E5F5" else "" }
                                            }) {
                                                Div({
                                                    style {
                                                        fontSize(13.px)
                                                        if (isCurrent) property("font-weight", "600")
                                                        property("font-family", "ui-monospace, SFMono-Regular, Menlo, monospace")
                                                        property("line-height", "1.45")
                                                    }
                                                }) { Text(if (isCurrent) "✓ ${opt.name}" else opt.name) }
                                                if (opt.desc.isNotBlank()) {
                                                    Div({
                                                        style {
                                                            fontSize(12.px); color(Color("#888")); marginTop(2.px)
                                                            property("line-height", "1.35"); property("word-break", "break-word")
                                                        }
                                                    }) { Text(opt.desc) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                
                // 第一行：输入框占据整行
                // 发送消息的函数
                                val sendMessage: () -> Unit = {
                    val text = messageText
                    val pendingImg = pendingPasteImage
                    if (text.isNotBlank() || pendingImg != null) {
                        messageText = ""
                        pendingPasteImage = null
                        val pendingUrl = pendingPasteImageUrl
                        pendingPasteImageUrl = null
                        if (pendingUrl != null) {
                            js("window.URL.revokeObjectURL(pendingUrl)")
                        }
                        if (pendingImg == null && text.isNotBlank()) {
                            scope.launch {
                                chatClient.sendMessage(
                                    user.id,
                                    user.fullName,
                                    text,
                                    kbContextSelection.takeIf(::hasKnowledgeBaseContextSelection),
                                )
                            }
                        }
                        if (pendingImg != null) {
                            isUploading = true
                            // 图片+文字一起通过 HTTP 上传，后端保存后广播单条 ##PREVIEW_IMAGE 消息
                            val w = js("window")
                            w.__upGid = group.id
                            w.__upUid = user.id
                            w.__upUrl = "${backendHttpOrigin()}/api/files/upload"
                            w.__upFile = pendingImg
                            w.__upText = if (text.isNotBlank()) text else ""
                            js("""
                                var fd = new FormData();
                                fd.append("sessionId", window.__upGid);
                                fd.append("userId", window.__upUid);
                                fd.append("file", window.__upFile);
                                fd.append("text", window.__upText);
                                var xhr = new XMLHttpRequest();
                                xhr.open("POST", window.__upUrl, true);
                                xhr.onload = function() { window.__upIsUploading = false; };
                                xhr.onerror = function() { window.__upIsUploading = false; };
                                xhr.send(fd);
                            """)
                            js("window.__upIsUploading = true;")
                        }
                    }
                }






                

                                // 粘贴图片预览
                if (pendingPasteImageUrl != null) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            property("gap", "8px")
                            padding(8.px, 12.px)
                            marginBottom(8.px)
                            backgroundColor(Color("rgba(201, 168, 108, 0.08)"))
                            borderRadius(8.px)
                            property("border", "1px solid rgba(201, 168, 108, 0.25)")
                        }
                    }) {
                        Img(src = pendingPasteImageUrl!!) {
                            style {
                                width(60.px)
                                height(60.px)
                                property("object-fit", "cover")
                                borderRadius(4.px)
                            }
                        }
                        Span({
                            style {
                                fontSize(13.px)
                                color(Color(SilkColors.textSecondary))
                                property("flex", "1")
                            }
                        }) { Text("Send with image") }
                        Span({
                            style {
                                fontSize(16.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                padding(4.px)
                            }
                            onClick {
                                val url = pendingPasteImageUrl
                                pendingPasteImage = null
                                pendingPasteImageUrl = null
                                if (url != null) { js("window.URL.revokeObjectURL(url)") }
                            }
                        }) { Text("x") }
                    }
                }

                KnowledgeBaseContextTray(
                    statusMessages = statusMessages,
                    selection = kbContextSelection,
                    onSelectionChange = {
                        kbContextSelectionTouched = true
                        kbContextSelection = it
                    },
                )
// 输入框容器（用于定位 mention 菜单）
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
                            messageText = newValue
                            
                            // 检测 @ 符号
                            if (newValue.length > oldValue.length) {
                                val lastChar = newValue.lastOrNull()
                                if (lastChar == '@') {
                                    // 计算输入框位置用于 fixed 定位菜单
                                    val input = document.getElementById("chat-input") as? org.w3c.dom.HTMLElement
                                    if (input != null) {
                                        val rect = input.getBoundingClientRect()
                                        mentionMenuPosition = Pair(rect.left, window.innerHeight - rect.top + 4)
                                    }
                                    showMentionMenu = true
                                    mentionStartIndex = newValue.length - 1
                                    mentionSearchText = ""
                                }
                            }
                            
                            // 如果在 mention 模式，更新搜索文本
                            if (showMentionMenu && mentionStartIndex >= 0) {
                                val textAfterAt = newValue.substring(mentionStartIndex + 1)
                                val spaceIndex = textAfterAt.indexOf(' ')
                                if (spaceIndex >= 0) {
                                    // 用户输入了空格，关闭菜单
                                    showMentionMenu = false
                                } else {
                                    mentionSearchText = textAfterAt
                                }
                            }
                        }
                        attr("placeholder", when {
                            pendingQuestionId != null -> "回答 Claude Code 的问题..."
                            group.name.startsWith("[Silk]") -> strings.silkChatInputPlaceholder
                            ccConnectInfo != null -> "Message cc-connect agent..."
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
                    
                                        // 粘贴图片处理（存入待发送队列，不立即上传）
                    DisposableEffect(Unit) {
                        val handler: (org.w3c.dom.events.Event) -> Unit = { event ->
                            val clipboardEvent = event.asDynamic()
                            val items = clipboardEvent.clipboardData?.items
                            if (items != null) {
                                for (idx in 0 until items.length) {
                                    val item = items[idx]
                                    if (item.kind == "file" && item.type.startsWith("image/")) {
                                        event.preventDefault()
                                        val fileBlob = item.getAsFile()
                                        if (fileBlob != null) {
                                            pendingPasteImage = fileBlob
                                            pendingPasteImageUrl = js("window.URL.createObjectURL(fileBlob)").toString()
                                        }
                                        break
                                    }
                                }
                            }
                        }
                        val input = document.getElementById("chat-input") as? org.w3c.dom.HTMLElement
                        input?.addEventListener("paste", handler)
                        onDispose {
                            input?.removeEventListener("paste", handler)
                        }
                    }

                    
                    // @ Mention 下拉菜单 - 使用 fixed 定位避免被 overflow:hidden 裁剪
                    if (showMentionMenu) {
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
                            // 过滤用户列表
                            val filteredUsers = sessionUsers.filter { (_, name) ->
                                mentionSearchText.isEmpty() || 
                                name.lowercase().contains(mentionSearchText.lowercase())
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
                                            // 插入 @用户名
                                            val beforeAt = messageText.substring(0, mentionStartIndex)
                                            val displayName = if (isAgentUserId(userId)) "Silk" else userName
                                            messageText = "$beforeAt@$displayName "
                                            showMentionMenu = false
                                            mentionStartIndex = -1
                                            
                                            // 聚焦输入框 (使用 window.setTimeout 确保在下一个事件循环执行)
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
                }
                
                // 添加键盘事件监听
                DisposableEffect(Unit) {
                    val handler: (dynamic) -> Unit = { event: dynamic ->
                        val key = event.key as? String
                        val shiftKey = event.shiftKey as? Boolean ?: false
                        // 输入法合成中（如中文拼音按 Enter 确认），不发送
                        val isComposing = (event.isComposing as? Boolean) ?: false

                        if (key == "Enter" && !shiftKey && !isComposing) {
                            event.preventDefault()
                            sendMessage()
                        } else if (key == "Enter" && shiftKey) {
                            // Shift+Enter：在光标处插入换行
                            event.preventDefault()
                            val input = js("document.getElementById('chat-input')")
                            val start = input.selectionStart as? Int ?: messageText.length
                            val end = input.selectionEnd as? Int ?: start
                            val before = messageText.substring(0, start)
                            val after = messageText.substring(end)
                            messageText = "$before\n$after"
                            // 光标移到换行后
                            window.setTimeout({
                                val newPos = start + 1
                                input.setSelectionRange(newPos, newPos)
                            }, 0)
                        }
                    }

                    val input = js("document.getElementById('chat-input')")
                    input?.addEventListener("keydown", handler)

                    onDispose {
                        input?.removeEventListener("keydown", handler)
                    }
                }
                
                // 第二行：按钮组靠右对齐
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        property("justify-content", "flex-end")
                        property("gap", "10px")
                        alignItems(AlignItems.Center)
                    }
                }) {
                    // 📁 上传目录按钮
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
                        attr("title", "上传整个目录")
                        onClick {
                            if (!isUploading) {
                                js("""
                                    var input = document.getElementById('folder-upload-input');
                                    if (input) input.click();
                                """)
                            }
                        }
                    }) {
                        Text(if (isUploading) "⏳" else "📁")
                    }
                    
                    // 📎 上传单文件按钮
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
                        attr("title", "上传单个文件")
                        onClick {
                            if (!isUploading) {
                                js("""
                                    var input = document.getElementById('file-upload-input');
                                    if (input) input.click();
                                """)
                            }
                        }
                    }) {
                        Text(if (isUploading) "⏳" else "📎")
                    }

                    // 📷 截屏按钮（区域截图）
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
                        attr("title", "截取屏幕区域")
                        onClick {
                            if (!isUploading) {
                                isScreenshotCrop = true
                                val sessionId = group.id
                                val userId = user.id
                                val uploadUrl = "${backendHttpOrigin()}/api/files/upload"
                                // 注册 JS 回调以重置 Kotlin 状态（保持注册直到 JS 侧使用完毕）
                                window.asDynamic().__screenshotDone = {
                                    val blob = js("window.__pendingScreenshotBlob")
                                    if (blob != null) {
                                        pendingPasteImage = blob
                                        pendingPasteImageUrl = js("window.URL.createObjectURL(blob)").toString()
                                        js("window.__pendingScreenshotBlob = null")
                                    }
                                    isScreenshotCrop = false
                                }
                                js("""
                                    (function() {
                                        var sid = sessionId;
                                        var uid_ = userId;
                                        var url = uploadUrl;
                                        navigator.mediaDevices.getDisplayMedia().then(function(stream) {
                                            var video = document.createElement('video');
                                            video.srcObject = stream;
                                            video.onloadedmetadata = function() {
                                                video.play();
                                                var canvas = document.createElement('canvas');
                                                canvas.width = video.videoWidth;
                                                canvas.height = video.videoHeight;
                                                var ctx = canvas.getContext('2d');
                                                ctx.drawImage(video, 0, 0);
                                                stream.getTracks().forEach(function(t) { t.stop(); });
                                                var dataUrl = canvas.toDataURL('image/png');
                                                window.showCropOverlay(dataUrl, sid, uid_, url);
                                            };
                                        }).catch(function(e) {
                                            console.log('截图取消:', e);
                                            if (window.__screenshotDone) { window.__screenshotDone(); window.__screenshotDone = undefined; }
                                        });
                                    })();
                                """)
                            }
                        }
                    }) {
                        Text("📷")
                    }

                    // 🎤 语音输入按钮
                    if (isTranscribing) {
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
                    } else if (isVoiceRecording) {
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
                                isVoiceRecording = false
                                try {
                                    val recorder = mediaRecorderJs
                                    if (recorder != null) {
                                        recorder.stop()
                                    }
                                } catch (e: dynamic) {
                                    console.log("停止录音失败:", e)
                                    isTranscribing = false
                                }
                            }
                        }) {
                            Text("⏹ 停止")
                        }
                    } else {
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
                                    try {
                                        console.log("[ASR] 请求麦克风...")
                                        val stream = jsGetUserMedia()
                                            .unsafeCast<kotlin.js.Promise<dynamic>>().await()
                                        console.log("[ASR] 获取到音频流")
                                        val chunks = jsNewArray()
                                        audioChunksJs = chunks
                                        val recorder = jsCreateRecorder(stream)
                                        recorder.ondataavailable = { event: dynamic ->
                                            chunks.push(event.data)
                                            Unit
                                        }
                                        recorder.onstop = {
                                            console.log("[ASR] 录音已停止，开始转写...")
                                            isTranscribing = true
                                            scope.launch {
                                                try {
                                                    val blob = jsCreateBlob(chunks)
                                                    val arrayBuffer = jsBlobToArrayBuffer(blob)
                                                        .unsafeCast<kotlin.js.Promise<dynamic>>().await()
                                                    val base64 = jsArrayBufferToBase64(arrayBuffer) as String
                                                    console.log("[ASR] base64 长度:", base64.length)
                                                    val result = ApiClient.transcribeAudio(base64, "webm")
                                                    console.log("[ASR] 结果: success=${result.success}, text=${result.text.take(50)}")
                                                    if (result.success && result.text.isNotBlank()) {
                                                        messageText = if (messageText.isNotBlank()) "$messageText ${result.text}" else result.text
                                                    } else {
                                                        console.log("[ASR] 失败:", result.error ?: "未知错误")
                                                    }
                                                } catch (t: Throwable) {
                                                    console.log("[ASR] 识别出错:", t)
                                                } finally {
                                                    isTranscribing = false
                                                    try { jsStopTracks(stream) } catch (_: dynamic) {}
                                                    console.log("[ASR] 流程结束")
                                                }
                                            }
                                            Unit
                                        }
                                        mediaRecorderJs = recorder
                                        recorder.start()
                                        isVoiceRecording = true
                                        console.log("[ASR] 开始录音")
                                    } catch (e: dynamic) {
                                        console.log("[ASR] 无法启动录音:", e)
                                    }
                                }
                            }
                        }) {
                            Text("🎤")
                        }
                    }
                    
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
        }
    }
    
    // 知识库入库对话框
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

    // 转发对话框
    if (showForwardDialog && messageToForward != null) {
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
                showForwardDialog = false
                messageToForward = null
                forwardResult = null
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
                // 标题
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
                
                // 消息预览
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
                    Text("${messageToForward!!.userName}: ${messageToForward!!.content.take(80)}${if (messageToForward!!.content.length > 80) "..." else ""}")
                }
                
                // 结果提示
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
                
                // 群组列表
                if (isLoadingGroups) {
                    Div({
                        style {
                            textAlign("center")
                            padding(20.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) { Text("加载中...") }
                } else if (userGroups.isEmpty()) {
                    Div({
                        style {
                            textAlign("center")
                            padding(20.px)
                            color(Color(SilkColors.textSecondary))
                        }
                    }) { Text("没有其他对话可转发") }
                } else {
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
                                val msg = messageToForward ?: return@onClick
                                scope.launch {
                                    forwardResult = null
                                    val forwardContent = "📨 转发自【${group.name}】:\n\n${msg.userName}: ${msg.content}"
                                    val success = ApiClient.sendMessageToGroup(
                                        groupId = targetGroup.id,
                                        userId = user.id,
                                        userName = user.fullName,
                                        content = forwardContent
                                    )
                                    if (success) {
                                        forwardResult = "✅ 已转发到 ${targetGroup.name}"
                                        kotlinx.coroutines.delay(1000)
                                        showForwardDialog = false
                                        messageToForward = null
                                        forwardResult = null
                                    } else {
                                        forwardResult = "❌ 转发失败"
                                    }
                                }
                            }
                        }) {
                            // 群头像
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
                            // 群名
                            Span({
                                style {
                                    fontSize(15.px)
                                    color(Color(SilkColors.textPrimary))
                                }
                            }) { Text(targetGroup.name) }
                        }
                    }
                }
                
                // 取消按钮
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
                            showForwardDialog = false
                            messageToForward = null
                            forwardResult = null
                        }
                    }) { Text("取消") }
                }
            }
        }
    }
    
    // 邀请对话框
    if (showInvitationDialog) {
        InvitationDialog(
            group = group,
            strings = strings,
            onDismiss = { showInvitationDialog = false }
        )
    }
    
    // 添加成员对话框
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
                    addMemberResult = if (response.success) {
                        // 刷新成员列表
                        val membersResponse = ApiClient.getGroupMembers(group.id)
                        // 将群主排在第一位
                        groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                        strings.memberAdded.replace("{name}", contact.contactName)
                    } else {
                        "❌ ${response.message}"
                    }
                }
            },
            onDismiss = { 
                showAddMemberDialog = false
                addMemberResult = null
            }
        )
    }
    
    // 查看成员对话框
    if (showMembersDialog) {
        MembersDialog(
            members = groupMembers,
            contacts = contacts,
            currentUserId = user.id,
            isLoading = isLoadingContacts,
            strings = strings,
            isHost = group.hostId == user.id,
            isCcConnectGroup = ccConnectInfo != null,
            groupId = group.id,
            onMemberClick = { member ->
                // 检查是否是联系人
                val isContact = contacts.any { it.contactId == member.id }
                if (isContact) {
                    // 是联系人，跳转到与该联系人的对话
                    scope.launch {
                        showMembersDialog = false
                        // 先断开当前WebSocket
                        try {
                            chatClient.disconnect()
                        } catch (e: dynamic) { /* ignore disconnect errors */ }
                        
                        // 调用API获取或创建与该联系人的对话
                        val response = ApiClient.startPrivateChat(user.id, member.id)
                        if (response.success && response.group != null) {
                            // 导航到新的对话
                            appState.selectGroup(response.group!!)
                        } else {
                            console.log("❌ 无法创建对话: ${response.message}")
                        }
                    }
                } else {
                    // 不是联系人，弹出邀请确认
                    selectedMemberForInvite = member
                }
            },
            onDismiss = { 
                showMembersDialog = false
                selectedMemberForInvite = null
                inviteMemberResult = null
            },
            onMembersChanged = {
                scope.launch {
                    val membersResponse = ApiClient.getGroupMembers(group.id)
                    groupMembers = membersResponse.members.sortedByDescending { it.id == group.hostId }
                }
            }
        )
    }
    
    // cc-connect token info dialog
    if (showCcConnectTokenDialog && ccConnectInfo != null) {
        CcConnectTokenDialog(
            groupId = group.id,
            userId = user.id,
            tokenInfo = ccConnectInfo!!,
            onTokenRegenerated = { newInfo -> ccConnectInfo = newInfo },
            onDismiss = { showCcConnectTokenDialog = false }
        )
    }

    // 邀请成员加入联系人确认对话框
    selectedMemberForInvite?.let { member ->
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
                selectedMemberForInvite = null
                inviteMemberResult = null
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
                
                // 显示结果消息
                inviteMemberResult?.let { result ->
                    Div({
                        style {
                            textAlign("center")
                            marginBottom(16.px)
                            color(if (result.contains(strings.contactRequestSent) || result.contains("✅")) 
                                Color("#10B981") else Color("#EF4444"))
                            fontSize(14.px)
                        }
                    }) {
                        Text(result)
                    }
                }
                
                // 按钮区域
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
                            selectedMemberForInvite = null
                            inviteMemberResult = null
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
                                    isInvitingMember = true
                                    val response = ApiClient.sendContactRequestById(user.id, member.id)
                                    inviteMemberResult = if (response.success) {
                                        "✅ ${strings.contactRequestSent}"
                                    } else {
                                        "❌ ${response.message}"
                                    }
                                    isInvitingMember = false
                                    
                                    // 成功后延迟关闭
                                    if (response.success) {
                                        kotlinx.coroutines.delay(1500)
                                        selectedMemberForInvite = null
                                        inviteMemberResult = null
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
    
    // 隐藏的单文件上传输入
    org.jetbrains.compose.web.dom.Input(org.jetbrains.compose.web.attributes.InputType.File) {
        id("file-upload-input")
        style {
            display(DisplayStyle.None)
        }
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
    
    // 隐藏的目录上传输入
    org.jetbrains.compose.web.dom.Input(org.jetbrains.compose.web.attributes.InputType.File) {
        id("folder-upload-input")
        style {
            display(DisplayStyle.None)
        }
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
                    
                    // 支持的文件扩展名
                    var supportedExtensions = [
                        // 文本文件
                        '.txt', '.md', '.markdown', '.json', '.xml', '.html', '.htm', '.css',
                        '.yaml', '.yml', '.csv', '.log', '.ini', '.conf', '.cfg',
                        // 源代码
                        '.js', '.ts', '.jsx', '.tsx', '.kt', '.kts', '.java', '.py', '.pyw',
                        '.c', '.cpp', '.cc', '.h', '.hpp', '.cs', '.go', '.rs', '.rb',
                        '.php', '.swift', '.scala', '.groovy', '.lua', '.r', '.m', '.mm',
                        '.sh', '.bash', '.zsh', '.ps1', '.bat', '.cmd',
                        '.sql', '.graphql', '.proto',
                        // 文档
                        '.pdf'
                    ];
                    
                    var files = input.files;
                    var filesToUpload = [];
                    
                    // 筛选支持的文件
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
                    
                    // 逐一上传文件
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
    
    // 文件夹浏览对话框
    if (showFolderExplorer) {
        FolderExplorerDialog(
            groupId = group.id,
            strings = strings,
            onDismiss = { showFolderExplorer = false }
        )
    }
}

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
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
    var refreshTrigger by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(groupId, refreshTrigger) {
        val apiUrl = "${backendHttpOrigin()}/api/files/list/$groupId"
        isLoading = true
        errorMessage = null
        try {
            console.log("📁 请求文件列表:", apiUrl)
            val response = window.fetch(apiUrl).await()
            if (!response.ok) {
                error("HTTP ${response.status}")
            }
            val body = response.text().await()
            val parsed = parseWebFolderContents(body)
            files = parsed.files
            processedUrls = parsed.processedUrls
            console.log("📁 加载完成:", files.size, "文件,", processedUrls.size, "URL")
        } catch (t: Throwable) {
            console.error("❌ 获取文件列表失败:", t)
            errorMessage = t.message ?: "获取失败"
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
                            
                            // 操作按钮组
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    property("gap", "8px")
                                    alignItems(AlignItems.Center)
                                }
                            }) {
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
                            // 删除文件
                            Span({
                                style {
                                    padding(8.px, 12.px)
                                    color(Color("#E57373"))
                                    property("cursor", "pointer")
                                    fontSize(13.px)
                                    property("transition", "all 0.2s ease")
                                    property("border-radius", "4px")
                                }
                                onClick {
                                    if (kotlinx.browser.window.confirm("确定删除 ${fileName} 吗？")) {
                                        scope.launch {
                                            try {
                                                // 从下载链接解析 fileId: /api/files/download/{sessionId}/{fileId}
                                                val parts = downloadUrl.split("/")
                                                val fileId = parts.lastOrNull() ?: fileName
                                                val sessionId = groupId
                                                ApiClient.deleteFile(sessionId, fileId)
                                                // 刷新文件列表
                                                refreshTrigger = refreshTrigger + 1
                                            } catch (e: Exception) {
                                                console.error("删除文件失败:", e)
                                            }
                                        }
                                    }
                                }
                            }) {
                                Text("🗑")
                            }
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
@Suppress("UnusedParameter")
private external object HighlightJs {
    fun highlight(code: String, options: dynamic): dynamic
    fun highlightAuto(code: String): dynamic
    fun getLanguage(languageName: String): dynamic
}

@JsModule("dompurify")
@JsNonModule
@Suppress("UnusedParameter")
private external object DOMPurify {
    fun sanitize(dirty: String, config: dynamic = definedExternally): String
}

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

@JsModule("diff2html")
@JsNonModule
@Suppress("UnusedParameter")
private external object Diff2Html {
    fun html(diffInput: String, configuration: dynamic = definedExternally): String
}

@JsModule("diff2html/bundles/css/diff2html.min.css")
@JsNonModule
private external val diff2htmlStylesheet: dynamic

@Suppress("TopLevelPropertyNaming")
private const val markdownRuntimeStyleId = "silk-markdown-runtime-style"

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
    diff2htmlStylesheet
}

private fun ensureMarkdownStylesInjected() {
    if (document.getElementById(markdownRuntimeStyleId) != null) return

    val styleElement = document.createElement("style") as HTMLElement
    styleElement.id = markdownRuntimeStyleId
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

private val mathDelimiters = listOf(
    MathDelimiter("$$", "$$"),
    MathDelimiter("\\[", "\\]"),
    MathDelimiter("\\(", "\\)")
)

@Suppress("LoopWithTooManyJumpStatements")
private fun normalizeMathBlocks(markdown: String): String {
    val output = StringBuilder()
    var cursor = 0

    while (cursor < markdown.length) {
        var matched = false

        for (delimiter in mathDelimiters) {
            if (!markdown.startsWith(delimiter.open, cursor)) continue

            val contentStart = cursor + delimiter.open.length
            val closingIndex = markdown.indexOf(delimiter.close, contentStart)
            if (closingIndex == -1) continue

            val innerContent = markdown.substring(contentStart, closingIndex)
                // markdown-it 会把数学环境中的 `\\` 吃成 `\`，这里先补一层转义。
                .replace("\\\\", "\\\\\\\\")
            output.append(delimiter.open)
            output.append(innerContent)
            output.append(delimiter.close)
            cursor = closingIndex + delimiter.close.length
            matched = true
            break
        }

        if (!matched) {
            output.append(markdown[cursor])
            cursor += 1
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

@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
private fun fixOrphanCodeFences(markdown: String): String {
    val lines = markdown.split("\n").toMutableList()
    var idx = 0

    while (idx < lines.size) {
        val trimmed = lines[idx].trimStart()

        val opener = Regex("^(`{3,}|~{3,})(\\s*[\\w+#.-]*)?\\s*$").find(trimmed)
        if (opener != null) {
            val fenceStr = opener.groupValues[1]
            val fenceLen = fenceStr.length
            val fenceChar = fenceStr[0]
            val closePattern = Regex("^\\s*${Regex.escape(fenceChar.toString())}{$fenceLen,}\\s*$")

            var closerIdx = -1
            for (j in (idx + 1) until lines.size) {
                if (closePattern.matches(lines[j])) {
                    closerIdx = j
                    break
                }
            }

            if (closerIdx >= 0) {
                // Closed fence — check if content is actually Markdown, not code.
                // If no language tag and content contains headings + bold/lists,
                // the model likely wrapped prose in backticks by mistake.
                val langTag = (opener.groupValues[2]).trim()
                val innerLines = if (closerIdx > idx + 1) lines.subList(idx + 1, closerIdx) else emptyList()
                val innerText = innerLines.joinToString("\n")
                val innerHasHeadings = innerText.contains(Regex("^#{1,6}\\s", RegexOption.MULTILINE))
                val innerHasBold = innerText.contains(Regex("\\*\\*[^*]+\\*\\*"))
                val innerHasLists = innerText.contains(Regex("^[-*+·•]\\s+", RegexOption.MULTILINE))
                val innerHasTable = innerText.contains(Regex("^\\|.+\\|\\s*$", RegexOption.MULTILINE))
                val markdownSignals = listOf(innerHasHeadings, innerHasBold, innerHasLists, innerHasTable).count { it }

                val isLikelyNotCode = (langTag.isEmpty() || langTag == "text" || langTag == "markdown")
                        && markdownSignals >= 2 && innerLines.size >= 3

                if (isLikelyNotCode) {
                    lines.removeAt(closerIdx)
                    lines.removeAt(idx)
                } else {
                    idx = closerIdx + 1
                }
            } else {
                val contentAfter = if (idx + 1 < lines.size) {
                    lines.subList(idx + 1, lines.size).joinToString("\n")
                } else ""

                val hasMarkdownSyntax =
                    contentAfter.contains(Regex("^#{1,6}[\\s]", RegexOption.MULTILINE)) ||
                    contentAfter.contains(Regex("^\\|.+\\|\\s*$", RegexOption.MULTILINE)) ||
                    contentAfter.contains(Regex("^[-*+]\\s+", RegexOption.MULTILINE)) ||
                    contentAfter.contains(Regex("\\*\\*[^*]+\\*\\*"))

                if (hasMarkdownSyntax || contentAfter.length > 500) {
                    lines.removeAt(idx)
                } else {
                    lines.add("`".repeat(fenceLen))
                    idx = lines.size
                }
            }
        } else {
            if (Regex("^(.+[^`\\s])`{3,}\\s*$").containsMatchIn(lines[idx])) {
                lines[idx] = lines[idx].replace(Regex("`{3,}\\s*$"), "")
            }
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
        val highlighted = if (normalizedLanguage.isNotBlank() && HighlightJs.getLanguage(normalizedLanguage) != null) {
            val options = js("{}")
            options.language = normalizedLanguage
            options.ignoreIllegals = true
            HighlightJs.highlight(code, options).value as String
        } else {
            HighlightJs.highlightAuto(code).value as String
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
    // Allow data: URIs for <img> so Claude's data URI images render inline
    config.ADD_DATA_URI_TAGS = arrayOf("img")
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

@Suppress("UnusedParameter")
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
            } else {
                "<span class=\"silk-citation-chip silk-citation-nav\" data-idx=\"$idx\" style=\"cursor:pointer\">$label</span>"
            }
        } else {
            match.value
        }
    }
}

// diff2html / DOMPurify 配置对象：放在 @Composable 外（本文件约定 js("...") 不入 composable，避免 Compose LiveLiteral lowering 崩溃）
private val jsDiff2HtmlConfig = js("({ drawFileList: false, matching: 'lines', outputFormat: 'line-by-line' })")
private val jsDompurifyDiffConfig = js("({ ADD_ATTR: ['class'] })")

/** 构建单文件 diff 的安全 HTML：diff2html 低层 html() → DOMPurify 消毒（顶层非 composable）。 */
private fun buildDiffHtml(patch: String): String {
    if (patch.isBlank()) return "<div class=\"silk-diff-empty\">(无差异内容)</div>"
    val rawHtml = Diff2Html.html(patch, jsDiff2HtmlConfig)
    return DOMPurify.sanitize(rawHtml, jsDompurifyDiffConfig)
}

/**
 * 单文件 unified diff 渲染：buildDiffHtml → innerHTML。
 * v1 只做 GitHub 式增/删配色，不做逐 token 语法高亮（不引入 diff2html-ui，避免绕过 DOMPurify）。
 */
@Composable
fun RenderDiff(patch: String, fileName: String) {
    // 唯一容器 id：文件名 + patch 长度，避免同页多个 diff 冲突
    val containerId = remember(fileName, patch) { "diff-${fileName.hashCode()}-${patch.length}" }
    val safeHtml = remember(patch) { buildDiffHtml(patch) }

    Div({
        classes("silk-diff-body")
        id(containerId)
        // diff2html 的行号 td 是 position:absolute；给容器一个定位上下文（且它随面板内容一起滚动），
        // 否则行号会锚到视口而不随滚轮移动，造成与代码行错位。overflow-x 让超长行可横向滚动。
        style {
            property("position", "relative")
            property("overflow-x", "auto")
        }
    }) { }

    DisposableEffect(containerId, safeHtml) {
        val element = document.getElementById(containerId) as? HTMLElement
        if (element != null) {
            element.innerHTML = safeHtml
        }
        onDispose { }
    }
}

@Suppress("CyclomaticComplexMethod", "UnusedParameter", "TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
@Composable
fun MarkdownContent(
    content: String,
    references: List<com.silk.shared.models.MessageReference> = emptyList(),
    referenceAnchorPrefix: String = ""
) {
    ensureMarkdownStylesInjected()

    val markdownEngine = rememberMarkdownEngine()
    val containerId = remember { "silk-markdown-${Random.nextInt(1_000_000)}" }
    val safeHtml = remember(content, references) {
        try {
            // Strip the cc-connect turn routing marker (invisible to users)
            val cleanContent = content
                .replace("<!--CC_TURN-->\n", "")
                .replace("<!--CC_TURN-->", "")
            // Escape < followed by non-HTML-tag characters (e.g., "<1.2m" → "&lt;1.2m")
            // before any other processing, so DOMPurify won't strip them as invalid tags.
            val htmlSafeContent = cleanContent.replace(Regex("<(?![a-zA-Z/!])"), "&lt;")
            // Convert thinking section (before <!--THINKING_END-->) to collapsible <details>
            // Note: processing on raw `cleanContent` so thinking-text escaping doesn't double-escape
            val thinkingMarker = "<!--THINKING_END-->"
            val withThinkingDetails = if (cleanContent.contains(thinkingMarker)) {
                val idx = cleanContent.indexOf(thinkingMarker)
                val thinkingText = cleanContent.substring(0, idx).trim()
                val tailRaw = cleanContent.substring(idx + thinkingMarker.length).trimStart('\n').trim()
                val tailEffective =
                    if (tailRaw.isBlank()) "*（本次仅有思考过程或未生成正文，请重试。）*" else tailRaw
                val escaped = thinkingText
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>")
                "<details class=\"silk-thinking-details\">\n" +
                "<summary>💭 思考过程</summary>\n" +
                escaped + "\n</details>\n\n" +
                tailEffective.replace(Regex("<(?![a-zA-Z/!])"), "&lt;")
            } else {
                htmlSafeContent
            }
            // Convert tool-call section (before <!--TOOLS_END-->) to collapsible <details>
            // Tools content is rendered through markdown-it to preserve formatting.
            val toolsMarker = "<!--TOOLS_END-->"
            val withToolsDetails = if (withThinkingDetails.contains(toolsMarker)) {
                val tIdx = withThinkingDetails.indexOf(toolsMarker)
                val beforeTools = withThinkingDetails.substring(0, tIdx)
                val afterTools = withThinkingDetails.substring(tIdx + toolsMarker.length).trimStart('\n').trim()
                val detailsEnd = beforeTools.lastIndexOf("</details>")
                val prefix = if (detailsEnd >= 0) beforeTools.substring(0, detailsEnd + "</details>".length) else ""

                // Extract raw tools content from cleanContent (before any escaping)
                val rawToolsStart = if (cleanContent.contains(thinkingMarker)) {
                    cleanContent.indexOf(thinkingMarker) + thinkingMarker.length
                } else 0
                val rawToolsEnd = cleanContent.indexOf(toolsMarker)
                val rawToolsContent = if (rawToolsEnd > rawToolsStart) {
                    cleanContent.substring(rawToolsStart, rawToolsEnd).trim()
                } else ""

                val toolsRenderedHtml = if (rawToolsContent.isNotBlank()) {
                    DOMPurify.sanitize(
                        markdownEngine.render(rawToolsContent),
                        createSanitizeConfig()
                    )
                } else ""

                val toolsDetails = "<details class=\"silk-thinking-details\">\n" +
                    "<summary>🔧 工具调用过程</summary>\n" +
                    toolsRenderedHtml + "\n</details>"
                val answerEffective = if (afterTools.isBlank()) "" else "\n\n$afterTools"
                if (prefix.isNotBlank()) "$prefix\n\n$toolsDetails$answerEffective"
                else "$toolsDetails$answerEffective"
            } else {
                withThinkingDetails
            }
            // Collapse excessive blank lines (3+ → 1 blank line)
            val reducedBlanks = withToolsDetails.replace(Regex("\\n{3,}"), "\n\n")
            // Normalize headings missing space after # (e.g., "##一、" → "## 一、")
            val normalizedHeadings = reducedBlanks.replace(
                Regex("^(#{1,6})([^#\\s])", RegexOption.MULTILINE),
                "$1 $2"
            )
            // Fix tables missing header row (separator as first line)
            val fixedTables = fixHeaderlessTables(normalizedHeadings)
            // Fix orphan code fences that swallow subsequent Markdown content
            val fixedFences = fixOrphanCodeFences(fixedTables)
            // Close blockquotes before section separators (---) and Sources headers
            // so they don't get swallowed into deeply nested blockquotes
            val unquotedBlockquotes = fixedFences
                .replace(Regex("""^>\s*(-{3,})\s*$""", RegexOption.MULTILINE), "\n$1")
                .replace(Regex("""^>\s*(\*\*Sources?:?\*\*)\s*$""", RegexOption.MULTILINE), "\n$1")
            val linked = renderKnowledgeBaseMarkersInHtml(
                linkCitationMarkers(
                    DOMPurify.sanitize(
                        markdownEngine.render(normalizeMathBlocks(unquotedBlockquotes)),
                        createSanitizeConfig()
                    ),
                    references,
                    referenceAnchorPrefix
                )
            )
            linked
        } catch (_: Throwable) {
            // fallback: escape and display raw content
            content.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }
    }

    Div({
        classes("markdown-body", "silk-markdown")
        id(containerId)
    }) { }

    DisposableEffect(containerId, safeHtml) {
        val element = document.getElementById(containerId) as? HTMLElement
        if (element != null) {
            element.innerHTML = safeHtml

            // KB 内联引用：给 [[kb:...]] 渲染出的链接挂上点击处理（→ window.__silkOpenKnowledgeBaseEntry）
            attachKnowledgeBaseLinkHandlers(element)

            // Rewrite HTTP image src through backend proxy to avoid Mixed Content
            val images = element.querySelectorAll("img")
            for (imgIdx in 0 until images.length) {
                val img = images.item(imgIdx) as? HTMLElement ?: continue
                val src = img.getAttribute("src") ?: ""
                if (src.startsWith("http://")) {
                    // 代理在 API 后端，不在前端静态服务器
                    val backendPort = BuildConfig.BACKEND_HTTP_PORT
                    val loc = window.location
                    val proxyBase = "${loc.protocol}//${loc.hostname}:$backendPort"
                    img.setAttribute("src", "$proxyBase/api/image-proxy?url=${js("encodeURIComponent")(src)}")
                }
            }

            val links = element.querySelectorAll("a")
            for (index in 0 until links.length) {
                val link = links.item(index) as? HTMLAnchorElement ?: continue
                val href = link.getAttribute("href") ?: ""
                if (href.startsWith("#")) continue
                link.target = "_blank"
                link.rel = "noopener noreferrer nofollow"
            }

            // Wrap code blocks with language label + copy button
            val preBlocks = element.querySelectorAll("pre.hljs")
            for (preIdx in 0 until preBlocks.length) {
                val pre = preBlocks.item(preIdx) as? HTMLElement ?: continue
                val lang = pre.getAttribute("data-lang") ?: ""

                val wrapper = document.createElement("div") as HTMLElement
                wrapper.className = "silk-code-block"

                val header = document.createElement("div") as HTMLElement
                header.className = "silk-code-header"

                val langSpan = document.createElement("span") as HTMLElement
                langSpan.className = "silk-code-lang"
                langSpan.textContent = lang
                header.appendChild(langSpan)

                val copyBtn = document.createElement("button") as HTMLElement
                copyBtn.className = "silk-code-copy"
                copyBtn.textContent = "复制"
                header.appendChild(copyBtn)

                pre.parentNode?.insertBefore(wrapper, pre)
                wrapper.appendChild(header)
                wrapper.appendChild(pre)

                copyBtn.addEventListener("click", { _ ->
                    val codeText = pre.querySelector("code")?.textContent ?: ""
                    try {
                        val clipboard = window.navigator.asDynamic().clipboard
                        if (clipboard != null) {
                            clipboard.writeText(codeText).then { _: dynamic ->
                                copyBtn.textContent = "已复制 ✓"
                                window.setTimeout({ copyBtn.textContent = "复制" }, 1500)
                            }
                        }
                    } catch (_: Throwable) { }
                })
            }

            try {
                renderMathInElement(element, createMathRenderOptions())
            } catch (error: Throwable) {
                console.warn("Markdown math render failed:", error)
            }

        }

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
@Suppress("CyclomaticComplexMethod", "NO_EXPLICIT_RETURN_TYPE_IN_API_CLASS")
@Composable
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
    val containsImageMd = message.content.contains("![](")
    val isLongContent = message.content.length > 500 && !containsImageMd
    val collapsedPreview = remember(message.content) {
        message.content.trimStart().take(200).ifBlank { "（内容已折叠，点击展开）" }
    }
Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.FlexStart)
            property("gap", "8px")
            if (isSelectionMode) property("cursor", "pointer")
        }
        if (isSelectionMode) {
            onClick { onToggleSelection(message.id) }
        }
    }) {
        if (isSelectionMode) {
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
        
    Div({
        classes(SilkStylesheet.aiMessageCard, "silk-ai-card")
        attr("id", messageDomId(message.id))
        attr("data-message-id", message.id)
        style {
            property("flex", "1")
            property("min-width", "0")
            if (isSelected) {
                property("outline", "2px solid ${SilkColors.primary}")
                backgroundColor(Color("rgba(76, 175, 80, 0.05)"))
            }
        }
    }) {
        // AI 头部标识
        Div({
            classes("silk-ai-header")
        }) {
            // AI 图标
            Div({
                classes("silk-ai-avatar")
            }) {
                Text("🤖")
            }
            
            // AI 名称和时间
            Span({ classes("silk-ai-name") }) {
                val aiDisplayName = message.userName.trimStart().removePrefix("\uD83E\uDD16").trim()
                    .let { if (it.isBlank() || it == "Silk") "Silk AI" else it }
                Text(aiDisplayName)
            }
            Span({ classes("silk-ai-time") }) { Text(timeString) }
            
            // 展开/收起按钮（长内容时显示，DOM 切换 display，不触发 Compose 重组）
            if (isLongContent && !isTransient) {
                Div({ style { property("flex", "1") } }) { }
                Span({
                    attr("data-role", "expand-btn")
                    attr("data-msg", message.id)
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        property("cursor", "pointer")
                        padding(4.px, 8.px)
                        borderRadius(4.px)
                        property("transition", "all 0.2s")
                        property("user-select", "none")
                        property("background", "rgba(201, 168, 108, 0.1)")
                    }
                    onClick {
                        val msgEl = document.getElementById(messageDomId(message.id))
                        if (msgEl != null) {
                            msgEl.querySelector("[data-view='collapsed']").asDynamic().style.display = "none"
                            msgEl.querySelector("[data-view='expanded']").asDynamic().style.display = "block"
                            msgEl.querySelector("[data-role='expand-btn']").asDynamic().style.display = "none"
                            msgEl.querySelector("[data-role='collapse-btn']").asDynamic().style.display = "inline"
                        }
                    }
                }) {
                    Text("📖 展开")
                }
                Span({
                    attr("data-role", "collapse-btn")
                    attr("data-msg", message.id)
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                        property("cursor", "pointer")
                        padding(4.px, 8.px)
                        borderRadius(4.px)
                        property("transition", "all 0.2s")
                        property("user-select", "none")
                        property("background", "rgba(201, 168, 108, 0.1)")
                        display(DisplayStyle.None)
                    }
                    onClick {
                        val msgEl = document.getElementById(messageDomId(message.id))
                        if (msgEl != null) {
                            msgEl.querySelector("[data-view='collapsed']").asDynamic().style.display = "block"
                            msgEl.querySelector("[data-view='expanded']").asDynamic().style.display = "none"
                            msgEl.querySelector("[data-role='expand-btn']").asDynamic().style.display = "inline"
                            msgEl.querySelector("[data-role='collapse-btn']").asDynamic().style.display = "none"
                        }
                    }
                }) {
                    Text("📖 收起")
                }
            }
        }
        
        // 内容区域 — 长内容渲染折叠+展开两个视图，DOM 切换 display（不触发 Compose 重组）
        val contentBlocksForRender = message.contentBlocks
        if (!contentBlocksForRender.isNullOrEmpty()) {
            // 持久化消息回放：从结构化 content blocks 渲染
            Div({ classes(SilkStylesheet.aiMessageContent) }) {
                // Thinking block rendered outside the loop for stable composition identity
                val thinkingBlock = contentBlocksForRender.firstOrNull { it.type == "thinking" }
                if (thinkingBlock != null) {
                    ThinkingBlock(content = thinkingBlock.content, isComplete = thinkingBlock.isComplete)
                }
                for (block in contentBlocksForRender) {
                    when (block.type) {
                        "thinking" -> { /* rendered above */ }
                        "text" -> MarkdownContent(content = block.content, references = message.references)
                        "tool_use" -> ToolCallBlock(name = block.toolName, summary = block.content, content = block.content)
                    }
                }
            }
        } else {
            if (isLongContent && !isTransient) {
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
            Div({
                attr("data-view", "expanded")
                style { display(DisplayStyle.None) }
                classes(SilkStylesheet.aiMessageContent)
            }) {
                StructuredContent(
                    content = message.content,
                    references = message.references,
                    msgId = message.id
                )
                ReferenceSourcesList(
                    references = message.references,
                    anchorPrefix = "msg-${message.id}-"
                )
                // Bottom-center collapse button
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
                        onClick {
                            val msgEl = document.getElementById(messageDomId(message.id))
                            if (msgEl != null) {
                                msgEl.querySelector("[data-view='collapsed']").asDynamic().style.display = "block"
                                msgEl.querySelector("[data-view='expanded']").asDynamic().style.display = "none"
                                msgEl.querySelector("[data-role='expand-btn']").asDynamic().style.display = "inline"
                                msgEl.querySelector("[data-role='collapse-btn']").asDynamic().style.display = "none"
                            }
                        }
                    }) {
                        Text("▲ 收起")
                    }
                }
            }
            // 最后一条消息默认展开，通过 DOM 操作设置初始状态（跟点击按钮同机制，避免 Compose 样式冲突）
            LaunchedEffect(message.id, isLastMessage) {
                val msgEl = document.getElementById(messageDomId(message.id))
                if (msgEl != null) {
                    if (isLastMessage) {
                        msgEl.querySelector("[data-view='collapsed']").asDynamic().style.display = "none"
                        msgEl.querySelector("[data-view='expanded']").asDynamic().style.display = "block"
                        msgEl.querySelector("[data-role='expand-btn']").asDynamic().style.display = "none"
                        msgEl.querySelector("[data-role='collapse-btn']").asDynamic().style.display = "inline"
                    } else {
                        msgEl.querySelector("[data-view='collapsed']").asDynamic().style.display = "block"
                        msgEl.querySelector("[data-view='expanded']").asDynamic().style.display = "none"
                        msgEl.querySelector("[data-role='expand-btn']").asDynamic().style.display = "inline"
                        msgEl.querySelector("[data-role='collapse-btn']").asDynamic().style.display = "none"
                    }
                }
            }
            } else {
            Div({
                classes(SilkStylesheet.aiMessageContent)
            }) {
                StructuredContent(
                    content = message.content,
                    references = message.references,
                    msgId = message.id
                )
                ReferenceSourcesList(
                    references = message.references,
                    anchorPrefix = "msg-${message.id}-"
                )
            }
            }
        }
        // 底部操作栏
        if (!isTransient && !isSelectionMode) {
            Div({ classes("silk-ai-actions") }) {
                Span({
                    style {
                        fontSize(11.px)
                        color(Color(SilkColors.textSecondary))
                        property("cursor", "pointer")
                        padding(4.px, 10.px)
                        borderRadius(4.px)
                        property("transition", "all 0.2s")
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("gap", "4px")
                    }
                    onClick { onCopy(message.content) }
                }) {
                    Text("📋")
                    Text("复制")
                }

                Span({
                    classes("silk-ai-action")
                    onClick { onCaptureToKnowledgeBase(message) }
                }) { Text("📚 入库") }

                Span({
                    classes("silk-ai-action")
                    onClick { onForward(message) }
                }) { Text("↗ 转发") }
                
                Span({
                    style {
                        fontSize(11.px)
                        color(Color("#E57373"))
                        property("cursor", "pointer")
                        padding(4.px, 10.px)
                        borderRadius(4.px)
                        property("transition", "all 0.2s")
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("gap", "4px")
                    }
                    onClick {
                        if (kotlinx.browser.window.confirm("确定要删除这条消息吗？")) {
                            onDelete(message.id)
                        }
                    }
                }) {
                    Text("🗑")
                    Text("删除")
                }

                
                Span({
                    classes("silk-ai-action")
                    onClick { onEnterSelectionMode(message.id) }
                }) { Text("☑ 多选") }
            }
        }
        
        // 临时消息状态指示
        if (isTransient) {
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
    }
    } // close outer selection wrapper
}

@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "UnusedParameter")
@Composable
fun MessageItem(
    message: Message,
    isTransient: Boolean = false,
    currentUserId: String = "",
    currentUserName: String = "",
    groupId: String = "",
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
    
    // 是否是 AI 消息（包含 cc-connect 代理回复）
    val isAIMessage = isAgentUserId(message.userId) || message.userId == "cc-connect"
    
    // AI 消息使用专用卡片
    val isRegularAIText = isAIMessage && message.type == MessageType.TEXT &&
        message.category != com.silk.shared.models.MessageCategory.AGENT_STATUS &&
        message.category != com.silk.shared.models.MessageCategory.AGENT_QUESTION
    if (isRegularAIText) {
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
        return
    }
    
    // 是否可以撤回：只能撤回自己发送的消息，且不是 Silk 的消息
    val canRecall = message.userId == currentUserId && 
                    !isAgentUserId(message.userId) &&
                    message.type == MessageType.TEXT &&
                    !isTransient
    
    // 是否显示操作按钮：文本消息且不是临时消息
    val showActions = message.type == MessageType.TEXT && !isTransient &&
                      message.category != com.silk.shared.models.MessageCategory.AGENT_STATUS &&
                      message.category != com.silk.shared.models.MessageCategory.AGENT_QUESTION

    // Agent 状态消息 - 灰色样式
    if (message.category == com.silk.shared.models.MessageCategory.AGENT_STATUS) {
        Div({
            attr("id", messageDomId(message.id))
            attr("data-message-id", message.id)
            style {
                padding(8.px, 16.px)
                marginBottom(6.px)
                backgroundColor(Color("#F5F5F5"))
                borderRadius(8.px)
                property("border-left", "3px solid #BDBDBD")
                fontSize(13.px)
                color(Color("#757575"))
                property("font-style", "italic")
                property("white-space", "pre-wrap")
                property("word-break", "break-word")
            }
        }) {
            Text(message.content)
        }
        return
    }

    // Agent 提问消息 - 橙色警告样式
    // 注意：仅处理 cc-connect 的文本型提问（type=TEXT，按钮走底部 interactiveOptions）。
    // ACP/Workflow agent 的提问是 CARD 类型（questionCard），必须落到下面 when(type) 的
    // CARD 分支由 CardMessageRenderer 渲染，不能在此被当作文本拦截。
    if (message.category == com.silk.shared.models.MessageCategory.AGENT_QUESTION &&
        message.type != MessageType.CARD && message.type != MessageType.CARD_REPLY) {
        Div({
            attr("id", messageDomId(message.id))
            attr("data-message-id", message.id)
            style {
                padding(12.px, 16.px)
                marginBottom(8.px)
                backgroundColor(Color("#FFF8F0"))
                borderRadius(8.px)
                property("border-left", "3px solid #E8B86C")
                fontSize(14.px)
                color(Color("#5D4E37"))
                property("white-space", "pre-wrap")
                property("word-break", "break-word")
            }
        }) {
            Text(message.content)
        }
        return
    }
    
    when (message.type) {
        MessageType.TEXT -> {
            // 检测PDF下载链接
            val isPdfMessage = message.content.contains("/download/report/") && message.content.contains(".pdf")
            
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                }
            }) {
                if (isSelectionMode) {
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
                        onClick { onToggleSelection(message.id) }
                    }) {
                        if (isSelected) {
                            Span({ style { color(Color.white); fontSize(14.px); property("font-weight", "bold") } }) {
                                Text("\u2713")
                            }
                        }
                    }
                }
            Div({
                classes(SilkStylesheet.messageCard)
                attr("id", messageDomId(message.id))
                attr("data-message-id", message.id)
                style {
                    property("flex", "1")
                    property("min-width", "0")
                    if (message.userId == currentUserId) {
                        property("max-width", "75%")
                        property("margin-left", "auto")
                    }
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
                Div({ classes(SilkStylesheet.messageHeader) }) {
                    Span({ classes(SilkStylesheet.userName) }) {
                        Text(message.userName)
                    }
                    Span({ classes(SilkStylesheet.timestamp) }) {
                        Text(timeString)
                    }
                }
                Div({
                    style {
                        property("white-space", "pre-wrap")
                        property("word-wrap", "break-word")
                        property("line-height", "1.7")
                        property("color", SilkColors.textPrimary)
                    }
                }) {
                    if (isPdfMessage) {
                        // PDF下载消息特殊处理
                        val lines = message.content.split("\n")
                        var pdfUrl: String? = null
                        var fileName: String? = null
                        
                        // 查找PDF路径和文件名
                        lines.forEach { line ->
                            val trimmedLine = line.trim()
                            if (trimmedLine.startsWith("/download/report/") && trimmedLine.contains(".pdf")) {
                                pdfUrl = trimmedLine
                                // 提取文件名（去除路径中的编码字符）
                                fileName = trimmedLine.substringAfterLast("/").replace("%20", " ").replace("%27", "'")
                            }
                        }
                        
                        // 显示消息内容（过滤掉路径行）
                        lines.forEach { line ->
                            val trimmedLine = line.trim()
                            if (!trimmedLine.startsWith("/download/report/") && trimmedLine.isNotEmpty()) {
                                Text(line)
                                Br()
                            }
                        }
                        
                        // 显示下载按钮 - 丝滑绿色
                        if (pdfUrl != null) {
                            val baseUrl = backendHttpOrigin()
                            val fullUrl = "$baseUrl$pdfUrl"
                            
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
                                        // ✅ 使用 fetch + Blob 方式下载PDF，触发浏览器保存对话框
                                        val downloadFileName = fileName ?: "diagnosis_report.pdf"
                                        console.log("开始下载PDF: $fullUrl, 文件名: $downloadFileName")
                                        
                                        // 获取window和document对象（js()返回的已经是dynamic类型）
                                        val window = js("window")
                                        val document = js("document")
                                        
                                        // 使用fetch下载PDF
                                        window.fetch(fullUrl)
                                            .then({ response: dynamic ->
                                                // response已经是JavaScript对象，直接使用
                                                console.log("获取响应:", response)
                                                if (!response.ok) {
                                                    throw js("Error('下载失败: ' + response.status)")
                                                }
                                                response.blob()  // 返回Promise<Blob>
                                            })
                                            .then({ blob: dynamic ->
                                                // blob已经是JavaScript Blob对象，直接使用
                                                console.log("创建Blob对象")
                                                val url = window.URL.createObjectURL(blob)
                                                val a = document.createElement("a")
                                                a.style.display = "none"
                                                a.href = url
                                                a.download = downloadFileName
                                                document.body.appendChild(a)
                                                a.click()
                                                window.URL.revokeObjectURL(url)
                                                document.body.removeChild(a)
                                                console.log("PDF下载成功")
                                            })
                                            .catch({ error: dynamic ->
                                                console.error("下载PDF失败:", error)
                                                window.alert("下载失败: " + error.message)
                                            })
                                    }
                                }) {
                                    Text("📥 下载PDF报告")
                                }
                                
                                // 显示文件名
                                if (fileName != null) {
                                    Div({
                                        style {
                                            fontSize(11.px)
                                            color(Color(SilkColors.textLight))
                                            marginTop(8.px)
                                            property("font-style", "italic")
                                        }
                                    }) {
                                        Text("文件名：$fileName")
                                    }
                                }
                            }
                        }
                    } else {
                        // 普通文本消息 — 用 MarkdownContent 支持图片/格式渲染
                        MarkdownContent(content = message.content)
                    }
                }
                
                // 消息操作按钮行
                if (showActions && !isSelectionMode) {
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            property("justify-content", if (message.userId == currentUserId) "flex-end" else "flex-start")
                            property("gap", "6px")
                            marginTop(8.px)
                            property("opacity", "0.5")
                            property("transition", "opacity 0.2s")
                        }
                    }) {
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick { copyTextToClipboard(message.content) }
                        }) { Text("📋复制") }

                        Span({
                            style {
                                fontSize(11.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick { onCaptureToKnowledgeBase(message) }
                        }) { Text("📚入库") }

                        Span({
                            style {
                                fontSize(11.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick { onForward(message) }
                        }) { Text("↗转发") }
                        
                        if (canRecall && !isRecalling) {
                            Span({
                                style {
                                    fontSize(11.px)
                                    color(Color(SilkColors.textSecondary))
                                    property("cursor", "pointer")
                                    property("padding", "2px 6px")
                                    property("border-radius", "4px")
                                    property("transition", "all 0.2s")
                                }
                                onClick {
                                    if (window.confirm("确定要撤回这条消息吗？")) {
                                        onRecall(message.id)
                                    }
                                }
                            }) { Text("↩撤回") }
                        }
                        
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color("#E57373"))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick {
                                if (kotlinx.browser.window.confirm("确定要删除这条消息吗？")) {
                                    onDelete(message.id)
                                }
                            }
                        }) { Text("🗑删除") }
                        
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick { onEnterSelectionMode(message.id) }
                        }) { Text("☑多选") }
                    }
                }
            }
            } // close selection wrapper Div
        }
        MessageType.FILE -> {
            val fileInfo = remember(message.content) {
                parseWebFileMessageContent(message.content)
            }
            val fileName = fileInfo.fileName
            val fileSize = fileInfo.fileSize
            val downloadUrl = fileInfo.downloadUrl
            val fileIcon = webFileIconForName(fileName)
            val fileSizeStr = formatWebFileSize(fileSize)
            val fileExtLabel = fileName.substringAfterLast(".", "").uppercase().ifBlank { "FILE" }
            
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                }
            }) {
                if (isSelectionMode) {
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
                        onClick { onToggleSelection(message.id) }
                    }) {
                        if (isSelected) {
                            Span({ style { color(Color.white); fontSize(14.px); property("font-weight", "bold") } }) {
                                Text("\u2713")
                            }
                        }
                    }
                }
            Div({
                classes(SilkStylesheet.messageCard)
                attr("id", messageDomId(message.id))
                attr("data-message-id", message.id)
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
                Div({ classes(SilkStylesheet.messageHeader) }) {
                    Span({ classes(SilkStylesheet.userName) }) {
                        Text(message.userName)
                    }
                    Span({ classes(SilkStylesheet.timestamp) }) {
                        Text(timeString)
                    }
                }
                
                // 图片预览（对图片文件）
                if (webIsImageFile(fileName) && downloadUrl.isNotEmpty()) {
                    val baseUrl = backendHttpOrigin()
                    val fullImageUrl = "$baseUrl$downloadUrl"
                    Div({
                        style {
                            borderRadius(8.px)
                            property("overflow", "hidden")
                            property("cursor", "pointer")
                            property("max-width", "360px")
                            property("border", "1px solid ${SilkColors.border}")
                        }
                        onClick {
                            val w = js("window")
                            w.open(fullImageUrl, "_blank")
                        }
                    }) {
                        Img(src = fullImageUrl) {
                            style {
                                width(100.vw)
                                property("max-width", "360px")
                                property("max-height", "300px")
                                property("object-fit", "contain")
                                display(DisplayStyle.Block)
                                backgroundColor(Color("#f0f0f0"))
                            }
                        }
                    }
                }

                // 文件卡片（图片已有预览，不再显示冗余文件卡）
                if (!webIsImageFile(fileName)) {
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
                            val baseUrl = backendHttpOrigin()
                            val fullUrl = "$baseUrl$downloadUrl"
                            console.log("打开文件下载: $fullUrl")
                            
                            // 使用 fetch 下载文件
                            val window = js("window")
                            val document = js("document")
                            
                            window.fetch(fullUrl)
                                .then({ response: dynamic ->
                                    if (!response.ok) {
                                        throw js("Error('下载失败: ' + response.status)")
                                    }
                                    response.blob()
                                })
                                .then({ blob: dynamic ->
                                    val url = window.URL.createObjectURL(blob)
                                    val a = document.createElement("a")
                                    a.style.display = "none"
                                    a.href = url
                                    a.download = fileName
                                    document.body.appendChild(a)
                                    a.click()
                                    window.URL.revokeObjectURL(url)
                                    document.body.removeChild(a)
                                    console.log("文件下载成功")
                                })
                                .catch({ error: dynamic ->
                                    console.error("下载文件失败:", error)
                                    window.alert("下载失败: " + error.message)
                                })
                        }
                    }
                }) {
                    // 文件图标
                    Div({
                        style {
                            fontSize(32.px)
                            padding(8.px)
                            backgroundColor(Color(SilkColors.secondary))
                            borderRadius(8.px)
                        }
                    }) {
                        Text(fileIcon)
                    }
                    
                    // 文件信息
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
                    
                    // 下载按钮
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
                } // end if-not-image
                
                // 文件消息操作按钮行
                if (!isTransient && !isSelectionMode) {
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
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick { onForward(message) }
                        }) { Text("↗转发") }
                        
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color("#E57373"))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick {
                                if (kotlinx.browser.window.confirm("确定要删除这条消息吗？")) {
                                    onDelete(message.id)
                                }
                            }
                        }) { Text("🗑删除") }
                        
                        Span({
                            style {
                                fontSize(11.px)
                                color(Color(SilkColors.textSecondary))
                                property("cursor", "pointer")
                                property("padding", "2px 6px")
                                property("border-radius", "4px")
                                property("transition", "all 0.2s")
                            }
                            onClick { onEnterSelectionMode(message.id) }
                        }) { Text("☑多选") }
                    }
                }
            }
            } // close selection wrapper Div
        }
        MessageType.JOIN, MessageType.LEAVE -> {
            Div({
                classes(SilkStylesheet.systemMessage)
                attr("id", messageDomId(message.id))
                attr("data-message-id", message.id)
            }) {
                Text("• ${message.content} ($timeString)")
            }
        }
        MessageType.SYSTEM -> {
            // 检测是否包含图片预览标记（##PREVIEW_IMAGE:url##）
            val previewMarker = "##PREVIEW_IMAGE:"
            val content = message.content
            if (content.startsWith(previewMarker)) {
                val markerEnd = content.indexOf("##\n", previewMarker.length)
                console.log("🖼 [PREVIEW] content starts with marker: markerEnd=$markerEnd content.length=${content.length}")
                if (markerEnd != -1) {
                    val imgUrl = content.substring(previewMarker.length, markerEnd)
                    val textContent = content.substring(markerEnd + 3)
                    val baseUrl = backendHttpOrigin()
                    val fullUrl = "$baseUrl$imgUrl"
                    console.log("🖼 [PREVIEW] imgUrl=$imgUrl fullUrl=$fullUrl")

                    val isOwnMessage = message.userId == currentUserId
                    console.log("🖼 [PREVIEW] message.userId=$message.userId currentUserId=$currentUserId isOwn=$isOwnMessage")
                    Div({
                        classes(SilkStylesheet.messageCard)
                        attr("id", messageDomId(message.id))
                        attr("data-message-id", message.id)
                        style {
                            property("flex", "1")
                            property("min-width", "0")
                            if (isOwnMessage) {
                                property("max-width", "75%")
                                property("margin-left", "auto")
                            }
                        }
                    }) {
                        Div({ classes(SilkStylesheet.messageHeader) }) {
                            Span({ classes(SilkStylesheet.userName) }) { Text(message.userName) }
                            Span({ classes(SilkStylesheet.timestamp) }) { Text(timeString) }
                        }
                        // 图片预览
                        Div({
                            style {
                                borderRadius(8.px)
                                property("overflow", "hidden")
                                property("max-width", "360px")
                                property("border", "1px solid ${SilkColors.border}")
                                marginBottom(8.px)
                                property("cursor", "pointer")
                            }
                            onClick { val w = js("window"); w.open(fullUrl, "_blank") }
                        }) {
                            Img(src = fullUrl) {
                                style {
                                    width(100.vw)
                                    property("max-width", "360px")
                                    property("max-height", "300px")
                                    property("object-fit", "contain")
                                    display(DisplayStyle.Block)
                                    backgroundColor(Color("#f0f0f0"))
                                }
                                attr("onerror", """
                                    console.error('🖼 [PREVIEW] Img load error:', this.src, 'complete:', this.complete, 'naturalWidth:', this.naturalWidth, 'naturalHeight:', this.naturalHeight);
                                    // 显示 fallback 下载链接
                                    this.style.display = 'none';
                                    this.nextElementSibling && (this.nextElementSibling.style.display = 'block');
                                """.trimIndent())
                                attr("onload", """
                                    console.log('🖼 [PREVIEW] Img loaded:', this.src, 'width:', this.width, 'height:', this.height, 'naturalWidth:', this.naturalWidth, 'naturalHeight:', this.naturalHeight);
                                """.trimIndent())
                            }
                            // 图片加载失败时的 fallback
                            Div({
                                style {
                                    display(DisplayStyle.None)
                                    fontSize(13.px)
                                    color(Color("#E57373"))
                                    padding(8.px)
                                    property("text-align", "center")
                                }
                            }) {
                                Text("🖼 图片加载失败，")
                                Span({
                                    style {
                                        color(Color("#1976D2"))
                                        property("cursor", "pointer")
                                        property("text-decoration", "underline")
                                    }
                                    onClick { val w = js("window"); w.open(fullUrl, "_blank") }
                                }) { Text("点此在新标签页中打开") }
                            }
                        }
                        // 提取内容文字
                        if (textContent.isNotBlank()) {
                            Div({
                                style {
                                    fontSize(13.px)
                                    color(Color(SilkColors.textPrimary))
                                    property("line-height", "1.6")
                                    property("white-space", "pre-wrap")
                                    property("word-break", "break-word")
                                }
                            }) {
                                Text(textContent.trimStart())
                            }
                        }
                    }
                    // 底部操作栏
                    if (!isTransient && !isSelectionMode) {
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                property("justify-content", if (message.userId == currentUserId) "flex-end" else "flex-start")
                                property("gap", "6px")
                                marginTop(12.px)
                                paddingTop(8.px)
                                property("border-top", "1px solid rgba(232, 224, 212, 0.5)")
                            }
                        }) {
                            Span({
                                style {
                                    fontSize(11.px)
                                    color(Color(SilkColors.textSecondary))
                                    property("cursor", "pointer")
                                    padding(4.px, 10.px)
                                    borderRadius(4.px)
                                    property("transition", "all 0.2s")
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "4px")
                                }
                                onClick { onCopy(textContent.ifBlank { "" }) }
                            }) {
                                Text("📋")
                                Text("复制")
                            }
                            Span({
                                style {
                                    fontSize(11.px)
                                    color(Color(SilkColors.textSecondary))
                                    property("cursor", "pointer")
                                    padding(4.px, 10.px)
                                    borderRadius(4.px)
                                    property("transition", "all 0.2s")
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "4px")
                                }
                                onClick { onForward(message) }
                            }) {
                                Text("↗")
                                Text("转发")
                            }
                            if (message.userId == currentUserId && !isAgentUserId(message.userId)) {
                                Span({
                                    style {
                                        fontSize(11.px)
                                        color(Color(SilkColors.textSecondary))
                                        property("cursor", "pointer")
                                        padding(4.px, 10.px)
                                        borderRadius(4.px)
                                        property("transition", "all 0.2s")
                                        display(DisplayStyle.Flex)
                                        alignItems(AlignItems.Center)
                                        property("gap", "4px")
                                    }
                                    onClick { onRecall(message.id) }
                                }) {
                                    Text("↩")
                                    Text("撤回")
                                }
                            }
                            Span({
                                style {
                                    fontSize(11.px)
                                    color(Color("#E57373"))
                                    property("cursor", "pointer")
                                    padding(4.px, 10.px)
                                    borderRadius(4.px)
                                    property("transition", "all 0.2s")
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "4px")
                                }
                                onClick {
                                    if (kotlinx.browser.window.confirm("确定要删除这条消息吗？")) {
                                        onDelete(message.id)
                                    }
                                }
                            }) {
                                Text("🗑")
                                Text("删除")
                            }
                            Span({
                                style {
                                    fontSize(11.px)
                                    color(Color(SilkColors.textSecondary))
                                    property("cursor", "pointer")
                                    padding(4.px, 10.px)
                                    borderRadius(4.px)
                                    property("transition", "all 0.2s")
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "4px")
                                }
                                onClick { onToggleSelection(message.id) }
                            }) {
                                Text("☑")
                                Text("多选")
                            }
                        }
                    }
                } else {
                    Div({
                        classes(SilkStylesheet.systemMessage)
                        attr("id", messageDomId(message.id))
                        attr("data-message-id", message.id)
                    }) {
                        Text("• ${content} ($timeString)")
                    }
                }
            } else {
                // 文件解析/提取内容卡片
                if (content.startsWith("# 图片:") || content.contains("## OCR")) {
                    Div({
                        classes(SilkStylesheet.messageCard)
                        attr("id", messageDomId(message.id))
                        attr("data-message-id", message.id)
                        style { property("flex", "1"); property("min-width", "0") }
                    }) {
                        Div({ classes(SilkStylesheet.messageHeader) }) {
                            Span({ classes(SilkStylesheet.userName) }) { Text(message.userName) }
                            Span({ classes(SilkStylesheet.timestamp) }) { Text(timeString) }
                        }
                        Div({
                            style {
                                fontSize(13.px)
                                color(Color(SilkColors.textPrimary))
                                property("line-height", "1.6")
                                property("white-space", "pre-wrap")
                                property("word-break", "break-word")
                            }
                        }) {
                            Text(content)
                        }
                    }
                } else {
                    Div({
                        classes(SilkStylesheet.systemMessage)
                        attr("id", messageDomId(message.id))
                        attr("data-message-id", message.id)
                    }) {
                        Text("• ${content} ($timeString)")
                    }
                }
            }
        }
        MessageType.RECALL -> {
        }
        MessageType.STOP_GENERATE -> {
        }
        MessageType.CC_COMMAND -> {
        }
        MessageType.CARD -> {
            // ACP/Workflow agent 交互卡片（问题/权限/计划）→ CardMessageRenderer
            chatClient?.let {
                Div({
                    attr("id", messageDomId(message.id))
                    attr("data-message-id", message.id)
                }) {
                    CardMessageRenderer(message, it, currentUserId, currentUserName)
                }
            }
        }
        MessageType.CARD_REPLY -> {
            // 卡片回复（用户点击）由服务器回放为卡片 edit，这里不单独渲染
        }
    }
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

@Suppress("CyclomaticComplexMethod")
@Composable
fun CcConnectTokenDialog(
    groupId: String,
    userId: String,
    tokenInfo: CcConnectTokenInfo,
    onTokenRegenerated: (CcConnectTokenInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tokenCopied by remember { mutableStateOf(false) }
    var isRegenerating by remember { mutableStateOf(false) }

    Div({
        style {
            position(Position.Fixed)
            top(0.px); left(0.px)
            width(100.percent); height(100.vh)
            backgroundColor(Color("rgba(74, 64, 56, 0.5)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "1100")
            property("backdrop-filter", "blur(4px)")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(16.px)
                padding(28.px)
                width(460.px)
                maxWidth(90.vw)
                property("box-shadow", "0 8px 32px rgba(169, 137, 77, 0.2)")
                property("border", "1px solid ${SilkColors.border}")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({
                style {
                    marginTop(0.px); marginBottom(20.px)
                    color(Color(SilkColors.textPrimary))
                    property("font-weight", "600")
                }
            }) { Text("cc-connect Token") }

            // Connection status
            Div({
                style {
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    property("gap", "8px")
                    marginBottom(16.px)
                }
            }) {
                Span({
                    style {
                        width(8.px); height(8.px)
                        borderRadius(50.percent)
                        backgroundColor(Color(if (tokenInfo.connected) "#4CAF50" else "#FF9800"))
                        display(DisplayStyle.InlineBlock)
                    }
                }) {}
                Span({
                    style {
                        fontSize(13.px)
                        color(Color(SilkColors.textPrimary))
                    }
                }) {
                    if (tokenInfo.connected) {
                        Text("Connected — ${tokenInfo.agentType ?: "agent"}${if (tokenInfo.project != null) " / ${tokenInfo.project}" else ""}")
                    } else {
                        Text("Offline — waiting for cc-connect to connect")
                    }
                }
            }

            // Token display (host only)
            if (tokenInfo.token != null) {
                Div({
                    style {
                        padding(16.px); borderRadius(8.px)
                        property("background", SilkColors.surface)
                        property("border", "1px solid ${SilkColors.border}")
                        marginBottom(16.px)
                        property("word-break", "break-all")
                        fontFamily("monospace"); fontSize(14.px)
                        color(Color(SilkColors.textPrimary))
                    }
                }) { Text(tokenInfo.token ?: "") }

                // config.toml snippet
                Div({
                    style {
                        fontSize(13.px); color(Color(SilkColors.textSecondary))
                        marginBottom(16.px); property("line-height", "1.6")
                    }
                }) {
                    Text("Paste this token into cc-connect's config.toml:")
                    Div({
                        style {
                            fontSize(12.px); padding(12.px); borderRadius(6.px)
                            property("background", SilkColors.surface)
                            property("border", "1px solid ${SilkColors.border}")
                            property("overflow-x", "auto")
                            color(Color(SilkColors.textPrimary))
                            fontFamily("monospace")
                            property("white-space", "pre")
                            marginTop(8.px)
                        }
                    }) {
                        Text("""[[projects.platforms]]
type = "silk"
[projects.platforms.options]
server = "wss://your-server:15003/ccconnect-bridge"
token  = "${tokenInfo.token ?: ""}"
""")
                    }
                }
            }

            // Action buttons
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    property("gap", "12px")
                }
            }) {
                if (tokenInfo.token != null) {
                    Button({
                        style {
                            padding(10.px, 18.px)
                            backgroundColor(Color(SilkColors.surface))
                            color(Color(SilkColors.textSecondary))
                            property("border", "1px solid ${SilkColors.border}")
                            borderRadius(8.px)
                            property("cursor", if (isRegenerating) "not-allowed" else "pointer")
                            fontSize(13.px)
                            property("opacity", if (isRegenerating) "0.6" else "1")
                        }
                        onClick {
                            if (!isRegenerating) {
                                scope.launch {
                                    isRegenerating = true
                                    val newToken = ApiClient.regenerateCcConnectToken(groupId, userId)
                                    if (newToken != null) {
                                        val updated = tokenInfo.copy(token = newToken)
                                        onTokenRegenerated(updated)
                                        tokenCopied = false
                                    }
                                    isRegenerating = false
                                }
                            }
                        }
                    }) { Text(if (isRegenerating) "Regenerating..." else "Regenerate") }

                    Button({
                        style {
                            padding(10.px, 18.px)
                            backgroundColor(Color(if (tokenCopied) "#4CAF50" else SilkColors.secondary))
                            color(Color(if (tokenCopied) "white" else SilkColors.textPrimary))
                            border { width(0.px) }; borderRadius(8.px)
                            property("cursor", "pointer"); fontSize(13.px)
                        }
                        onClick {
                            tokenInfo.token?.let { token ->
                                kotlinx.browser.window.navigator.clipboard.writeText(token)
                                tokenCopied = true
                            }
                        }
                    }) { Text(if (tokenCopied) "Copied!" else "Copy Token") }
                }

                Button({
                    style {
                        padding(10.px, 18.px)
                        property("background", "linear-gradient(135deg, ${SilkColors.primary} 0%, ${SilkColors.primaryDark} 100%)")
                        color(Color.white); border { width(0.px) }; borderRadius(8.px)
                        property("cursor", "pointer"); fontSize(13.px); property("font-weight", "600")
                    }
                    onClick { onDismiss() }
                }) { Text("Done") }
            }
        }
    }
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
@Suppress("CyclomaticComplexMethod")
@Composable
fun MembersDialog(
    members: List<GroupMember>,
    contacts: List<Contact>,
    currentUserId: String,
    isLoading: Boolean,
    strings: com.silk.shared.i18n.Strings,
    isHost: Boolean = false,
    isCcConnectGroup: Boolean = false,
    groupId: String = "",
    onMemberClick: (GroupMember) -> Unit,
    onDismiss: () -> Unit,
    onMembersChanged: () -> Unit = {},
) {
    val contactIds = contacts.map { it.contactId }.toSet()
    val scope = rememberCoroutineScope()
    
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
                width(420.px)
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
                    property("text-align", "center")
                }
            }) {
                Text(strings.groupMembersTitleWithCount.replace("{count}", members.size.toString()))
            }
            
            if (isLoading) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(20.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.loading)
                }
            } else if (members.isEmpty()) {
                Div({
                    style {
                        property("text-align", "center")
                        padding(20.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) {
                    Text(strings.noMembers)
                }
            } else {
                // 成员列表
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        property("gap", "10px")
                    }
                }) {
                    members.forEach { member ->
                        val isCurrentUser = member.id == currentUserId
                        val isContact = member.id in contactIds
                        val isSilkAI = isAgentUserId(member.id)
                        
                        Div({
                            style {
                                display(DisplayStyle.Flex)
                                justifyContent(JustifyContent.SpaceBetween)
                                alignItems(AlignItems.Center)
                                padding(12.px, 16.px)
                                backgroundColor(Color(SilkColors.surface))
                                borderRadius(10.px)
                                property("box-shadow", "0 2px 4px rgba(0,0,0,0.05)")
                                if (!isCurrentUser && !isSilkAI) {
                                    property("cursor", "pointer")
                                    property("transition", "all 0.2s ease")
                                }
                            }
                            if (!isCurrentUser && !isSilkAI) {
                                onClick { onMemberClick(member) }
                            }
                        }) {
                            // 成员信息
                            Div({
                                style {
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    property("gap", "12px")
                                }
                            }) {
                                // 头像/图标
                                Div({
                                    style {
                                        width(40.px)
                                        height(40.px)
                                        borderRadius(20.px)
                                        backgroundColor(
                                            when {
                                                isSilkAI -> Color(SilkColors.info)
                                                isCurrentUser -> Color(SilkColors.primary)
                                                isContact -> Color(SilkColors.success)
                                                else -> Color(SilkColors.textSecondary)
                                            }
                                        )
                                        display(DisplayStyle.Flex)
                                        justifyContent(JustifyContent.Center)
                                        alignItems(AlignItems.Center)
                                        color(Color.white)
                                        fontSize(18.px)
                                    }
                                }) {
                                    Text(
                                        when {
                                            isSilkAI -> "🤖"
                                            isCurrentUser -> "👤"
                                            isContact -> "✓"
                                            else -> member.fullName.firstOrNull()?.toString() ?: "?"
                                        }
                                    )
                                }
                                
                                // 名字和状态
                                Div {
                                    Div({
                                        style {
                                            fontSize(15.px)
                                            color(Color(SilkColors.textPrimary))
                                            property("font-weight", "500")
                                        }
                                    }) {
                                        Text(member.fullName)
                                        if (isCurrentUser) {
                                            Span({
                                                style {
                                                    fontSize(12.px)
                                                    color(Color(SilkColors.textSecondary))
                                                    marginLeft(8.px)
                                                }
                                            }) {
                                                Text(strings.me)
                                            }
                                        }
                                        if (isCcConnectGroup && !isSilkAI) {
                                            val roleLabel = when (member.role) {
                                                "HOST" -> "Host"
                                                "OPERATOR" -> "Operator"
                                                else -> null
                                            }
                                            if (roleLabel != null) {
                                                Span({
                                                    style {
                                                        fontSize(10.px)
                                                        marginLeft(8.px)
                                                        padding(2.px, 6.px)
                                                        borderRadius(4.px)
                                                        property("font-weight", "500")
                                                        if (member.role == "HOST") {
                                                            backgroundColor(Color("#FFF3E0"))
                                                            color(Color("#E65100"))
                                                        } else {
                                                            backgroundColor(Color("#E3F2FD"))
                                                            color(Color("#1565C0"))
                                                        }
                                                    }
                                                }) {
                                                    Text(roleLabel)
                                                }
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
                                        Text(
                                            when {
                                                isSilkAI -> strings.aiAssistant
                                                isCurrentUser -> strings.currentUser
                                                isContact -> strings.contactClickToChat
                                                else -> strings.clickToAddContact
                                            }
                                        )
                                    }
                                }
                            }
                            
                            // 右侧操作提示
                            if (!isSilkAI) {
                                Div({
                                    style {
                                        display(DisplayStyle.Flex)
                                        alignItems(AlignItems.Center)
                                        property("gap", "8px")
                                    }
                                }) {
                                    val canManageOperator = isCcConnectGroup && isHost && !isCurrentUser && member.role != "HOST"
                                    if (canManageOperator) {
                                        val isOperator = member.role == "OPERATOR"
                                        Div({
                                            style {
                                                fontSize(11.px)
                                                padding(4.px, 10.px)
                                                borderRadius(6.px)
                                                property("cursor", "pointer")
                                                property("transition", "all 0.2s ease")
                                                property("user-select", "none")
                                                if (isOperator) {
                                                    backgroundColor(Color("#E3F2FD"))
                                                    color(Color("#1565C0"))
                                                    property("border", "1px solid #90CAF9")
                                                } else {
                                                    backgroundColor(Color(SilkColors.surface))
                                                    color(Color(SilkColors.textSecondary))
                                                    property("border", "1px solid ${SilkColors.border}")
                                                }
                                            }
                                            onClick {
                                                it.stopPropagation()
                                                scope.launch {
                                                    val ok = ApiClient.setCcConnectOperator(
                                                        groupId, currentUserId, member.id, !isOperator
                                                    )
                                                    if (ok) onMembersChanged()
                                                }
                                            }
                                        }) {
                                            Text(if (isOperator) "Revoke @cc" else "Grant @cc")
                                        }
                                    }
                                    if (!isCurrentUser) {
                                        Div({
                                            style {
                                                fontSize(20.px)
                                                color(Color(SilkColors.textLight))
                                            }
                                        }) {
                                            Text(if (isContact) "💬" else "➕")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // 关闭按钮
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
    }
}

/**
 * 交互式按钮选项（cc-connect AskUserQuestion）。
 * 每个选项显示为一个可点击的按钮，点击后发送答案并禁用按钮。
 */
@Composable
@Suppress("NO_EXPLICIT_RETURN_TYPE_IN_API_CLASS")
@NoLiveLiterals
fun InteractiveOptions(
    options: List<com.silk.shared.models.InteractiveOption>,
    onAnswer: (String) -> Unit
) {
    val clickedOption = remember { mutableStateOf<String?>(null) }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-wrap", "wrap")
            property("gap", "8px")
            marginTop(12.px)
            padding(8.px, 0.px)
        }
    }) {
        for (opt in options) {
            val isClicked = clickedOption.value == opt.value
            Button({
                style {
                    padding(8.px, 20.px)
                    borderRadius(8.px)
                    border {
                        width(if (isClicked) 2.px else 1.px)
                        style(LineStyle.Solid)
                        color(Color(if (isClicked) "#C8A86C" else "#D0D0D0"))
                    }
                    backgroundColor(Color(if (isClicked) "#F5F0E6" else "#FFFFFF"))
                    color(Color("#333333"))
                    fontSize(14.px)
                    property("cursor", if (isClicked) "default" else "pointer")
                    property("transition", "all 0.2s")
                    property("opacity", if (isClicked) "0.7" else "1.0")
                }
                if (!isClicked) {
                    onClick {
                        clickedOption.value = opt.value
                        onAnswer(opt.value)
                    }
                }
            }) {
                Text(opt.label)
            }
        }
    }
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
