@file:Suppress("MatchingDeclarationName") // 本文件聚合多个布局辅助（LayoutPrefs / ColumnResizer / ReopenBar）

package com.silk.web

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import kotlinx.browser.localStorage
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.paddingTop
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/** UI 布局偏好持久化（面板宽度、列表折叠态），复用 auth 同款 localStorage。 */
object LayoutPrefs {
    fun getInt(key: String, default: Int): Int = try {
        localStorage.getItem(key)?.toIntOrNull() ?: default
    } catch (_: Throwable) {
        default
    }

    fun setInt(key: String, value: Int) {
        try {
            localStorage.setItem(key, value.toString())
        } catch (_: Throwable) {
        }
    }

    fun getBool(key: String, default: Boolean): Boolean = try {
        when (localStorage.getItem(key)) {
            "1" -> true
            "0" -> false
            else -> default
        }
    } catch (_: Throwable) {
        default
    }

    fun setBool(key: String, value: Boolean) {
        try {
            localStorage.setItem(key, if (value) "1" else "0")
        } catch (_: Throwable) {
        }
    }
}

// 列拖拽：放在 @Composable 外（本仓库约定 js("...") 不入 composable，避免 Compose LiveLiteral 崩溃）。
// 参数：startX, startWidth, isLeft(左面板 true → 新宽=startW+dx；右面板 false → 新宽=startW-dx),
//       minW, maxW, onResize(每次移动回调新宽度), onCommit(松手回调)。窗口级 mousemove/mouseup 保证拖出分隔条也跟手。
private val jsStartColumnResize: dynamic = js(
    """
    (function(startX, startWidth, isLeft, minW, maxW, onResize, onCommit) {
        document.body.style.userSelect = 'none';
        document.body.style.cursor = 'col-resize';
        function onMove(e) {
            var dx = e.clientX - startX;
            var w = isLeft ? (startWidth + dx) : (startWidth - dx);
            if (w < minW) w = minW;
            if (w > maxW) w = maxW;
            onResize(w);
        }
        function onUp() {
            window.removeEventListener('mousemove', onMove);
            window.removeEventListener('mouseup', onUp);
            document.body.style.userSelect = '';
            document.body.style.cursor = '';
            onCommit();
        }
        window.addEventListener('mousemove', onMove);
        window.addEventListener('mouseup', onUp);
    })
    """
)

/** 可拖拽的竖直分隔条（VSCode 式）。拖动实时改宽度，松手时 onCommit 持久化。 */
@Composable
fun ColumnResizer(
    isLeftPanel: Boolean,
    minWidth: Int,
    maxWidth: Int,
    currentWidth: () -> Int,
    onResize: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    Div({
        classes("silk-col-resizer")
        style {
            width(6.px)
            property("flex-shrink", "0")
            property("cursor", "col-resize")
            height(100.percent)
            backgroundColor(Color(SilkColors.divider))
        }
        onMouseDown { e ->
            e.preventDefault()
            jsStartColumnResize(
                e.clientX,
                currentWidth(),
                isLeftPanel,
                minWidth,
                maxWidth,
                { w: Int -> onResize(w) },
                { onCommit() },
            )
        }
    }) {}
}

/** 列表折叠后的窄重开条：点击展开。 */
@Composable
fun ReopenBar(onExpand: () -> Unit) {
    Div({
        classes("silk-reopen-bar")
        style {
            width(28.px)
            property("flex-shrink", "0")
            height(100.percent)
            property("border-right", "1px solid ${SilkColors.border}")
            backgroundColor(Color(SilkColors.surface))
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            alignItems(AlignItems.Center)
            paddingTop(12.px)
            property("cursor", "pointer")
        }
        attr("title", "展开列表")
        onClick { onExpand() }
    }) {
        Span({ style { fontSize(16.px); color(Color(SilkColors.textSecondary)) } }) { Text("»") }
    }
}

private const val LAYOUT_STYLE_ID = "silk-layout-styles"

/** 注入分隔条/重开条的 hover 高亮（一次性）。 */
fun ensureLayoutStylesInjected() {
    if (document.getElementById(LAYOUT_STYLE_ID) != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = LAYOUT_STYLE_ID
    style.textContent =
        ".silk-col-resizer:hover{background-color:${SilkColors.primary}!important;}" +
        ".silk-reopen-bar:hover{background-color:${SilkColors.secondary}!important;}"
    document.head?.appendChild(style)
}
