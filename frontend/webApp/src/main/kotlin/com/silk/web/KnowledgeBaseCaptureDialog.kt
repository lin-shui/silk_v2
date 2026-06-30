package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.Position
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.left
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.position
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.right
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.top
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

internal data class KnowledgeCaptureDraft(
    val message: com.silk.shared.models.Message,
    val sourceType: KBSourceType,
    val sourceGroupId: String? = null,
    val workflowId: String? = null,
    val preferredSpaceId: String = PERSONAL_SPACE_ID,
)

internal data class KnowledgeCaptureContext(
    val groups: List<Group>,
    val topics: List<KBTopicItem>,
)

internal suspend fun loadKnowledgeCaptureContext(userId: String): KnowledgeCaptureContext {
    val groups = (ApiClient.getUserGroups(userId).groups ?: emptyList()).filterNot { it.name.startsWith("wf_") }
    val topics = ApiClient.getKBTopics(userId)
    return KnowledgeCaptureContext(groups = groups, topics = topics)
}

internal fun buildDefaultKnowledgeCaptureTitle(content: String): String {
    val compact = content
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
    if (compact.isBlank()) return "未命名知识候选"
    return if (compact.length <= 40) compact else compact.take(40) + "..."
}

internal fun preferredKnowledgeCaptureSpaceId(
    preferredGroupId: String?,
    topics: List<KBTopicItem>,
): String {
    if (!preferredGroupId.isNullOrBlank() && topics.any { it.spaceType == KnowledgeSpaceType.TEAM && it.groupId == preferredGroupId }) {
        return preferredGroupId
    }
    return PERSONAL_SPACE_ID
}

internal fun defaultKnowledgeCaptureTopicId(
    topics: List<KBTopicItem>,
    spaceId: String,
): String? = filterTopicsForSpace(topics, spaceId).firstOrNull()?.id

internal fun canSubmitKnowledgeCapture(
    isSaving: Boolean,
    selectedTopicId: String,
    title: String,
    content: String,
): Boolean {
    return !isSaving &&
        selectedTopicId.isNotBlank() &&
        title.isNotBlank() &&
        content.isNotBlank()
}

private fun knowledgeCaptureSourceLabel(sourceType: KBSourceType): String {
    return when (sourceType) {
        KBSourceType.WORKFLOW -> "工作流沉淀"
        KBSourceType.CHAT -> "聊天沉淀"
        KBSourceType.MANUAL -> "手动创建"
        KBSourceType.MEETING -> "会议沉淀"
        KBSourceType.FILE -> "文件导入"
        KBSourceType.URL -> "URL 导入"
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun KnowledgeBaseCaptureDialog(
    draft: KnowledgeCaptureDraft,
    spaceOptions: List<KnowledgeSpaceOption>,
    topics: List<KBTopicItem>,
    selectedSpaceId: String,
    selectedTopicId: String,
    title: String,
    content: String,
    isSaving: Boolean,
    resultMessage: String?,
    onSelectedSpaceIdChange: (String) -> Unit,
    onSelectedTopicIdChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val visibleTopics = remember(topics, selectedSpaceId) { filterTopicsForSpace(topics, selectedSpaceId) }
    val canSubmit = canSubmitKnowledgeCapture(isSaving, selectedTopicId, title, content)

    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            right(0.px)
            height(100.percent)
            backgroundColor(Color("rgba(42, 33, 20, 0.38)"))
            property("z-index", "1200")
            display(DisplayStyle.Flex)
            property("justify-content", "center")
            property("align-items", "center")
            padding(20.px)
            property("box-sizing", "border-box")
        }
        onClick { onDismiss() }
    }) {
        Div({
            style {
                width(560.px)
                property("max-width", "100%")
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(16.px)
                padding(22.px)
                property("box-shadow", "0 20px 60px rgba(47, 36, 20, 0.22)")
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                property("gap", "14px")
            }
            onClick { it.stopPropagation() }
        }) {
            H3({
                style {
                    marginTop(0.px)
                    marginBottom(0.px)
                    color(Color(SilkColors.textPrimary))
                }
            }) { Text("保存到知识库候选") }

            Div({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                }
            }) {
                Text(
                    when (draft.sourceType) {
                        KBSourceType.WORKFLOW -> "当前工作流消息将以 candidate 进入知识库，后续可在 KB 页面整理后发布。"
                        else -> "当前聊天消息将以 candidate 进入知识库，后续可在 KB 页面整理后发布。"
                    }
                )
            }

            KnowledgeCaptureField("目标空间") {
                Select({
                    style {
                        width(100.percent)
                        height(40.px)
                        borderRadius(8.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(0.px, 10.px)
                        backgroundColor(Color.white)
                        color(Color(SilkColors.textPrimary))
                    }
                    attr("value", selectedSpaceId)
                    onChange { event ->
                        val nextSpaceId = event.value ?: return@onChange
                        onSelectedSpaceIdChange(nextSpaceId)
                        onSelectedTopicIdChange(defaultKnowledgeCaptureTopicId(topics, nextSpaceId).orEmpty())
                    }
                }) {
                    spaceOptions.forEach { option ->
                        Option(value = option.id) { Text(option.label) }
                    }
                }
            }

            KnowledgeCaptureField("目标主题") {
                Select({
                    style {
                        width(100.percent)
                        height(40.px)
                        borderRadius(8.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(0.px, 10.px)
                        backgroundColor(Color.white)
                        color(Color(SilkColors.textPrimary))
                    }
                    attr("value", selectedTopicId)
                    onChange { onSelectedTopicIdChange(it.value ?: "") }
                }) {
                    if (visibleTopics.isEmpty()) {
                        Option(value = "") { Text("当前空间暂无可写主题") }
                    } else {
                        visibleTopics.forEach { topic ->
                            Option(value = topic.id) { Text(topic.name) }
                        }
                    }
                }
            }

            KnowledgeCaptureField("标题") {
                Input(InputType.Text) {
                    value(title)
                    onInput { onTitleChange(it.value) }
                    style {
                        width(100.percent)
                        height(40.px)
                        borderRadius(8.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(0.px, 10.px)
                        property("box-sizing", "border-box")
                    }
                }
            }

            KnowledgeCaptureField("内容") {
                TextArea {
                    value(content)
                    onInput { onContentChange(it.value) }
                    style {
                        width(100.percent)
                        height(180.px)
                        borderRadius(10.px)
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        padding(10.px)
                        fontSize(13.px)
                        property("box-sizing", "border-box")
                        property("resize", "vertical")
                        property("line-height", "1.6")
                    }
                }
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("gap", "8px")
                    property("flex-wrap", "wrap")
                }
            }) {
                CaptureBadge("candidate", SilkColors.primaryDark)
                CaptureBadge(knowledgeCaptureSourceLabel(draft.sourceType), SilkColors.primary)
            }

            resultMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Div({
                    style {
                        fontSize(12.px)
                        color(Color(SilkColors.textSecondary))
                    }
                }) { Text(message) }
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    property("justify-content", "flex-end")
                    property("gap", "10px")
                }
            }) {
                Button({
                    style {
                        backgroundColor(Color(SilkColors.surface))
                        color(Color(SilkColors.textSecondary))
                        border(1.px, LineStyle.Solid, Color(SilkColors.border))
                        borderRadius(8.px)
                        padding(8.px, 16.px)
                        property("cursor", "pointer")
                    }
                    onClick { onDismiss() }
                }) { Text("取消") }
                Button({
                    style {
                        backgroundColor(
                            Color(if (canSubmit) SilkColors.primary else SilkColors.border)
                        )
                        color(Color.white)
                        border(0.px)
                        borderRadius(8.px)
                        padding(8.px, 16.px)
                        property("cursor", if (!isSaving) "pointer" else "not-allowed")
                    }
                    if (!canSubmit) {
                        attr("disabled", "true")
                    }
                    onClick { onConfirm() }
                }) { Text(if (isSaving) "保存中..." else "存入候选") }
            }
        }
    }
}

@Composable
internal fun KnowledgeCaptureField(
    label: String,
    content: @Composable () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("gap", "6px")
        }
    }) {
        Span({
            style {
                fontSize(12.px)
                color(Color(SilkColors.textSecondary))
                fontWeight("600")
            }
        }) { Text(label) }
        content()
    }
}

@Composable
internal fun CaptureBadge(label: String, background: String) {
    Span({
        style {
            backgroundColor(Color(background))
            color(Color.white)
            borderRadius(999.px)
            padding(4.px, 10.px)
            fontSize(11.px)
            fontWeight("600")
            property("display", "inline-flex")
            property("align-items", "center")
        }
    }) { Text(label) }
}
