package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.silk.shared.models.GitChangesResponse
import com.silk.shared.models.GitFileChange
import com.silk.shared.models.GitFileStatus
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * 只读"源代码管理"面板：工作树 vs HEAD。
 * 懒加载两段式——列表来自便宜的 /api/agent/changes；某文件展开时才取它的单文件 diff。
 * refreshSignal 由父级（回合结束/打开）自增以触发自动刷新。
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun SourceControlPanel(userId: String, groupId: String, refreshSignal: Int, widthPx: Int) {
    val scope = rememberCoroutineScope()
    var changes by remember { mutableStateOf<GitChangesResponse?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val diffCache = remember { mutableStateMapOf<String, String?>() }   // null = loading

    suspend fun reload() {
        refreshing = true
        changes = ApiClient.getGitChanges(userId, groupId)
        refreshing = false
    }

    // 初次加载 + 父级每次 bump refreshSignal 时自动重拉
    LaunchedEffect(groupId, refreshSignal) { reload() }

    Div({
        style {
            width(widthPx.px)
            property("flex-shrink", "0")
            property("border-left", "1px solid ${SilkColors.border}")
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.percent)
            backgroundColor(Color(SilkColors.surface))
            property("overflow-y", "auto")
        }
    }) {
        // 头部：标题 + 手动刷新
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "8px")
                padding(12.px, 14.px)
                property("border-bottom", "1px solid ${SilkColors.border}")
            }
        }) {
            Span({ style { fontWeight("600"); color(Color(SilkColors.textPrimary)) } }) {
                Text("代码审查")
            }
            // 分支名（detached HEAD 时退化为短 commit）；取不到就不显示
            val branchLabel = changes?.let { it.branch.ifBlank { it.head } }.orEmpty()
            if (branchLabel.isNotBlank()) {
                Span({
                    style {
                        color(Color(SilkColors.textSecondary))
                        fontSize(13.px)
                        property("white-space", "nowrap")
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                        property("min-width", "0")
                    }
                }) { Text("· $branchLabel") }
            }
            Div({ style { property("flex", "1") } }) {}
            Button({
                onClick { scope.launch { reload() } }
                style { property("cursor", "pointer") }
            }) { Text(if (refreshing) "刷新中…" else "↻") }
        }

        val c = changes
        when {
            c == null -> Status("加载中…")
            c.reason == "ccconnect" -> Status("该会话经 cc-connect 接入，暂不支持代码审查")
            !c.connected -> Status("Agent 未连接")
            !c.supported -> Status("当前 Bridge 不支持代码审查")
            !c.isGitRepo -> Status("工作目录不是 Git 仓库")
            !c.success -> Status(c.message.ifBlank { "获取改动失败" })
            c.files.isEmpty() -> Status("没有改动")
            else -> c.files.forEach { file ->
                FileRow(
                    file = file,
                    isOpen = expanded[file.path] == true,
                    patch = diffCache[file.path],
                    onToggle = {
                        val nowOpen = expanded[file.path] != true
                        expanded[file.path] = nowOpen
                        if (nowOpen && !diffCache.containsKey(file.path)) {
                            diffCache[file.path] = null  // 标记加载中
                            scope.launch {
                                val d = ApiClient.getGitFileDiff(userId, groupId, file.path)
                                diffCache[file.path] = if (d.isBinary) "" else d.patch
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Status(text: String) {
    Div({ style { padding(16.px); color(Color(SilkColors.textSecondary)); fontSize(14.px) } }) { Text(text) }
}

@Composable
private fun FileRow(file: GitFileChange, isOpen: Boolean, patch: String?, onToggle: () -> Unit) {
    Div {
        Div({
            onClick { onToggle() }
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "8px")
                padding(8.px, 14.px)
                property("cursor", "pointer")
                property("border-bottom", "1px solid ${SilkColors.border}")
            }
        }) {
            Span({ style { fontFamily("monospace"); width(16.px); color(Color(badgeColor(file.status))) } }) {
                Text(badgeLetter(file.status))
            }
            Span({
                style {
                    property("flex", "1")
                    property("overflow", "hidden")
                    property("text-overflow", "ellipsis")
                    property("white-space", "nowrap")
                    fontSize(13.px)
                }
            }) {
                Text(file.path)
            }
            if (file.additions > 0) Span({ style { color(Color("#2ea043")); fontSize(12.px) } }) { Text("+${file.additions}") }
            if (file.deletions > 0) Span({ style { color(Color("#f85149")); fontSize(12.px) } }) { Text("-${file.deletions}") }
        }
        if (isOpen) {
            when {
                file.binary -> Status("二进制文件，无法显示差异")
                patch == null -> Status("加载差异中…")
                patch.isBlank() -> Status("(无差异内容)")
                else -> RenderDiff(patch = patch, fileName = file.path)
            }
        }
    }
}

private fun badgeLetter(wire: String): String = when (GitFileStatus.fromWire(wire)) {
    GitFileStatus.ADDED, GitFileStatus.UNTRACKED -> "A"
    GitFileStatus.MODIFIED -> "M"
    GitFileStatus.DELETED -> "D"
    GitFileStatus.RENAMED -> "R"
    GitFileStatus.COPIED -> "C"
    GitFileStatus.UNMERGED -> "U"
    else -> "•"
}

private fun badgeColor(wire: String): String = when (GitFileStatus.fromWire(wire)) {
    GitFileStatus.ADDED, GitFileStatus.UNTRACKED -> "#2ea043"
    GitFileStatus.DELETED -> "#f85149"
    GitFileStatus.MODIFIED -> "#d29922"
    else -> "#8b949e"
}
