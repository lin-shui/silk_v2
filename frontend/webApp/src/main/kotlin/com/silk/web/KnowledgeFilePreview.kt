package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.Position
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.left
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.maxHeight
import org.jetbrains.compose.web.css.maxWidth
import org.jetbrains.compose.web.css.minWidth
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.top
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

// ──────────────────────────────────────────────
// File preview dispatcher — routes by mimeType
// ──────────────────────────────────────────────

/**
 * 根据 MIME 类型渲染对应文件预览。
 * @param downloadUrl 文件下载/预览 URL
 * @param fileName 文件名（用于 fallback 显示）
 * @param mimeType MIME 类型
 * @param fileSize 文件大小（字节），用于显示
 * @param maxHeightPx 预览区域最大高度
 * @param onClose 可选关闭回调（用于 overlay 模式）
 */
@Composable
fun KnowledgeFilePreview(
    downloadUrl: String,
    fileName: String,
    mimeType: String,
    fileSize: Long? = null,
    maxHeightPx: Int = 500,
    onClose: (() -> Unit)? = null,
) {
    when {
        mimeType.startsWith("image/") ->
            KnowledgeImagePreview(downloadUrl, fileName, maxHeightPx, onClose)
        mimeType == "application/pdf" ->
            KnowledgePdfPreview(downloadUrl, fileName, maxHeightPx, onClose)
        mimeType.startsWith("audio/") ->
            KnowledgeAudioPreview(downloadUrl, fileName, onClose)
        mimeType.startsWith("video/") ->
            KnowledgeVideoPreview(downloadUrl, fileName, maxHeightPx, onClose)
        else ->
            KnowledgeGenericFilePreview(downloadUrl, fileName, mimeType, fileSize, onClose)
    }
}

// ──────────────────────────────────────────────
// Image preview — full-size with lightbox
// ──────────────────────────────────────────────

@Composable
private fun KnowledgeImagePreview(
    url: String,
    fileName: String,
    maxHeightPx: Int,
    onClose: (() -> Unit)?,
) {
    var showLightbox by remember { mutableStateOf(false) }

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "8px")
            alignItems(AlignItems.Center)
            backgroundColor(Color(SilkColors.surface))
            padding(12.px)
            borderRadius(8.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
        }
    }) {
        Div({
            style {
                maxWidth(100.percent)
                maxHeight(maxHeightPx.px)
                property("overflow", "hidden")
                property("cursor", "zoom-in")
                borderRadius(4.px)
            }
            onClick { showLightbox = true }
        }) {
            Img(
                src = url,
                alt = fileName,
                attrs = {
                    style {
                        maxWidth(100.percent)
                        maxHeight(maxHeightPx.px)
                        property("object-fit", "contain")
                        display(DisplayStyle.Block)
                        borderRadius(4.px)
                    }
                }
            )
        }
        // Bottom bar: filename + size + open/close actions
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                width(100.percent)
                fontSize(12.px)
                color(Color(SilkColors.textSecondary))
            }
        }) {
            Span({ }) { Text(fileName) }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "12px")
                }
            }) {
                A(
                    href = url,
                    attrs = {
                        attr("target", "_blank")
                        style { color(Color(SilkColors.info)) }
                    }
                ) { Text("原图打开") }
                if (onClose != null) {
                    Span({
                        style {
                            color(Color(SilkColors.info))
                            property("cursor", "pointer")
                            property("text-decoration", "underline")
                        }
                        onClick { onClose() }
                    }) { Text("关闭") }
                }
            }
        }
    }

    // Lightbox overlay
    if (showLightbox) {
        KnowledgeLightboxOverlay(
            url = url,
            alt = fileName,
            onClose = { showLightbox = false },
        )
    }
}

@Composable
private fun KnowledgeLightboxOverlay(
    url: String,
    alt: String,
    onClose: () -> Unit,
) {
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { event ->
            if ((event as? KeyboardEvent)?.key == "Escape") {
                onClose()
            }
        }
        document.addEventListener("keydown", listener)
        onDispose { document.removeEventListener("keydown", listener) }
    }

    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            property("width", "100vw")
            property("height", "100vh")
            backgroundColor(Color("rgba(0, 0, 0, 0.85)"))
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.Center)
            property("z-index", "9999")
            property("cursor", "zoom-out")
        }
        onClick { onClose() }
    }) {
        Img(
            src = url,
            alt = alt,
            attrs = {
                style {
                    maxWidth(90.percent)
                    maxHeight(90.percent)
                    property("object-fit", "contain")
                    borderRadius(4.px)
                    property("box-shadow", "0 8px 40px rgba(0,0,0,0.3)")
                }
                // 阻止点击图片本身关闭（只在点击背景时关闭）
                onClick { event -> event.stopPropagation() }
            }
        )
    }
}

// ──────────────────────────────────────────────
// PDF preview — embed + fallback
// ──────────────────────────────────────────────

@Composable
private fun KnowledgePdfPreview(
    url: String,
    fileName: String,
    maxHeightPx: Int,
    onClose: (() -> Unit)?,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "8px")
            backgroundColor(Color(SilkColors.surface))
            padding(12.px)
            borderRadius(8.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
        }
    }) {
        // Header
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                fontSize(12.px)
                color(Color(SilkColors.textSecondary))
            }
        }) {
            Span({ }) { Text("📄 $fileName") }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "12px")
                }
            }) {
                A(
                    href = url,
                    attrs = {
                        attr("target", "_blank")
                        style { color(Color(SilkColors.info)) }
                    }
                ) { Text("下载 PDF") }
                if (onClose != null) {
                    Span({
                        style {
                            color(Color(SilkColors.info))
                            property("cursor", "pointer")
                            property("text-decoration", "underline")
                        }
                        onClick { onClose() }
                    }) { Text("关闭") }
                }
            }
        }
        // PDF embed
        Div({
            style {
                width(100.percent)
                height(minOf(maxHeightPx, 600).px)
                borderRadius(4.px)
                property("overflow", "hidden")
                border(1.px, LineStyle.Solid, Color(SilkColors.border))
            }
        }) {
            val embedHtml = """
                <object data="${url}" type="application/pdf" style="width:100%;height:100%;border:none;">
                    <p>PDF 预览不可用，请<a href="${url}" target="_blank" rel="noopener">下载查看</a>。</p>
                </object>
            """.trimIndent()
            Div({
                attr("innerHTML", embedHtml)
                style {
                    width(100.percent)
                    height(100.percent)
                }
            })
        }
    }
}

// ──────────────────────────────────────────────
// Audio preview — HTML5 <audio>
// ──────────────────────────────────────────────

@Composable
private fun KnowledgeAudioPreview(
    url: String,
    fileName: String,
    onClose: (() -> Unit)?,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "10px")
            backgroundColor(Color(SilkColors.surface))
            padding(16.px)
            borderRadius(8.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                fontSize(12.px)
                color(Color(SilkColors.textSecondary))
            }
        }) {
            Span({ }) { Text("🎵 $fileName") }
            if (onClose != null) {
                Span({
                    style {
                        color(Color(SilkColors.info))
                        property("cursor", "pointer")
                        property("text-decoration", "underline")
                    }
                    onClick { onClose() }
                }) { Text("关闭") }
            }
        }
        val audioHtml = """
            <audio controls style="width:100%;" preload="metadata">
                <source src="${url}">
                您的浏览器不支持音频播放，请<a href="${url}" target="_blank" rel="noopener">下载后播放</a>。
            </audio>
        """.trimIndent()
        Div({
            attr("innerHTML", audioHtml)
            style {
                width(100.percent)
            }
        })
    }
}

// ──────────────────────────────────────────────
// Video preview — HTML5 <video>
// ──────────────────────────────────────────────

@Composable
private fun KnowledgeVideoPreview(
    url: String,
    fileName: String,
    maxHeightPx: Int,
    onClose: (() -> Unit)?,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "8px")
            backgroundColor(Color(SilkColors.surface))
            padding(12.px)
            borderRadius(8.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween)
                alignItems(AlignItems.Center)
                fontSize(12.px)
                color(Color(SilkColors.textSecondary))
            }
        }) {
            Span({ }) { Text("🎬 $fileName") }
            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "12px")
                }
            }) {
                A(
                    href = url,
                    attrs = {
                        attr("target", "_blank")
                        style { color(Color(SilkColors.info)) }
                    }
                ) { Text("下载") }
                if (onClose != null) {
                    Span({
                        style {
                            color(Color(SilkColors.info))
                            property("cursor", "pointer")
                            property("text-decoration", "underline")
                        }
                        onClick { onClose() }
                    }) { Text("关闭") }
                }
            }
        }
        val displayH = minOf(maxHeightPx, 480)
        val videoHtml = """
            <video controls style="width:100%;max-height:${displayH}px;border-radius:4px;" preload="metadata">
                <source src="$url">
                您的浏览器不支持视频播放，请<a href="$url" target="_blank" rel="noopener">下载后播放</a>。
            </video>
        """.trimIndent()
        Div({
            attr("innerHTML", videoHtml)
            style {
                width(100.percent)
            }
        })
    }
}

// ──────────────────────────────────────────────
// Generic file preview (fallback)
// ──────────────────────────────────────────────

@Composable
private fun KnowledgeGenericFilePreview(
    downloadUrl: String,
    fileName: String,
    mimeType: String,
    fileSize: Long?,
    onClose: (() -> Unit)?,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "12px")
            backgroundColor(Color(SilkColors.surface))
            padding(16.px)
            borderRadius(8.px)
            border(1.px, LineStyle.Solid, Color(SilkColors.border))
            alignItems(AlignItems.Center)
        }
    }) {
        Span({ style { fontSize(32.px) } }) { Text("📁") }
        Span({
            style {
                fontSize(14.px)
                fontWeight("600")
                color(Color(SilkColors.textPrimary))
            }
        }) { Text(fileName) }
        Span({
            style {
                fontSize(12.px)
                color(Color(SilkColors.textLight))
            }
        }) {
            Text("类型: $mimeType${fileSize?.let { " · ${formatKnowledgeFileSize(it)}" } ?: ""}")
        }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "12px")
            }
        }) {
            A(
                href = downloadUrl,
                attrs = {
                    attr("target", "_blank")
                    style {
                        color(Color(SilkColors.info))
                        fontSize(13.px)
                        fontWeight("600")
                    }
                }
            ) { Text("⬇ 下载文件") }
            if (onClose != null) {
                Span({
                    style {
                        color(Color(SilkColors.info))
                        fontSize(13.px)
                        fontWeight("600")
                        property("cursor", "pointer")
                        property("text-decoration", "underline")
                    }
                    onClick { onClose() }
                }) { Text("关闭") }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Overlay / Modal wrapper for file preview
// ──────────────────────────────────────────────

/**
 * 全屏半透明遮罩，居中展示文件预览。
 * 点击遮罩背景或按 Escape 关闭。
 */
@Composable
fun KnowledgeFilePreviewOverlay(
    downloadUrl: String,
    fileName: String,
    mimeType: String,
    fileSize: Long? = null,
    onClose: () -> Unit,
) {
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { event ->
            if ((event as? KeyboardEvent)?.key == "Escape") {
                onClose()
            }
        }
        document.addEventListener("keydown", listener)
        val origOverflow = document.body?.style?.getPropertyValue("overflow") ?: ""
        document.body?.style?.setProperty("overflow", "hidden", "")
        onDispose {
            document.removeEventListener("keydown", listener)
            document.body?.style?.setProperty("overflow", origOverflow, "")
        }
    }

    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            property("width", "100vw")
            property("height", "100vh")
            backgroundColor(Color("rgba(0, 0, 0, 0.6)"))
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.Center)
            property("z-index", "9998")
            property("backdrop-filter", "blur(2px)")
        }
        onClick { onClose() }
    }) {
        Div({
            style {
                maxWidth(90.percent)
                maxHeight(90.percent)
                minWidth(320.px)
                backgroundColor(Color(SilkColors.background))
                borderRadius(12.px)
                property("overflow", "auto")
                padding(16.px)
                property("box-shadow", "0 16px 64px rgba(0,0,0,0.25)")
            }
            onClick { event -> event.stopPropagation() }
        }) {
            KnowledgeFilePreview(
                downloadUrl = downloadUrl,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = fileSize,
                maxHeightPx = 600,
                onClose = onClose,
            )
        }
    }
}
