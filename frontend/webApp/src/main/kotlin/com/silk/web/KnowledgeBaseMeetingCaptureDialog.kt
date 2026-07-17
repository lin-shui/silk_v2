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
import org.jetbrains.compose.web.css.maxWidth
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
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

internal fun buildDefaultMeetingCaptureTitle(topic: KBTopicItem?): String {
    return topic?.name?.takeIf { it.isNotBlank() }?.let { "$it 会议纪要" } ?: "会议纪要"
}

internal fun parseKnowledgeCaptureTags(raw: String): List<String> {
    return raw.split(',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun parseKnowledgeCaptureConfidence(raw: String): Double? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toDoubleOrNull() ?: return null
    return value.coerceIn(0.0, 1.0)
}

internal fun buildMeetingCaptureSource(topic: KBTopicItem?, confidenceText: String): KBEntrySource {
    return KBEntrySource(
        sourceType = KBSourceType.MEETING,
        sourceGroupId = topic?.takeIf { it.spaceType == KnowledgeSpaceType.TEAM }?.groupId,
        confidence = parseKnowledgeCaptureConfidence(confidenceText),
    )
}

@Composable
@Suppress("CyclomaticComplexMethod", "LongParameterList")
internal fun KnowledgeBaseMeetingCaptureDialog(
    spaceOptions: List<KnowledgeSpaceOption>,
    topics: List<KBTopicItem>,
    selectedSpaceId: String,
    selectedTopicId: String,
    title: String,
    content: String,
    tagsText: String,
    status: KBEntryStatus,
    confidenceText: String,
    isSaving: Boolean,
    resultMessage: String?,
    onSelectedSpaceIdChange: (String) -> Unit,
    onSelectedTopicIdChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onTagsTextChange: (String) -> Unit,
    onStatusChange: (KBEntryStatus) -> Unit,
    onConfidenceTextChange: (String) -> Unit,
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
                width(620.px)
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
            }) { Text("会议纪要入库") }

            Div({
                style {
                    fontSize(13.px)
                    color(Color(SilkColors.textSecondary))
                }
            }) {
                Text("为会议纪要走一遍统一 KB capture 契约，可直接存成候选，或显式发布到目标空间。")
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
                        height(200.px)
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
                    property("gap", "12px")
                    property("flex-wrap", "wrap")
                }
            }) {
                KnowledgeCaptureField("标签") {
                    Input(InputType.Text) {
                        value(tagsText)
                        onInput { onTagsTextChange(it.value) }
                        attr("placeholder", "meeting, minutes")
                        style {
                            width(280.px)
                            maxWidth(100.percent)
                            height(40.px)
                            borderRadius(8.px)
                            border(1.px, LineStyle.Solid, Color(SilkColors.border))
                            padding(0.px, 10.px)
                            property("box-sizing", "border-box")
                        }
                    }
                }

                KnowledgeCaptureField("状态") {
                    Select({
                        style {
                            width(160.px)
                            height(40.px)
                            borderRadius(8.px)
                            border(1.px, LineStyle.Solid, Color(SilkColors.border))
                            padding(0.px, 10.px)
                            backgroundColor(Color.white)
                            color(Color(SilkColors.textPrimary))
                        }
                        attr("value", status.name)
                        onChange { event ->
                            val next = event.value?.let { runCatching { KBEntryStatus.valueOf(it) }.getOrNull() } ?: return@onChange
                            onStatusChange(next)
                        }
                    }) {
                        Option(value = KBEntryStatus.CANDIDATE.name) { Text("候选") }
                        Option(value = KBEntryStatus.PUBLISHED.name) { Text("已发布") }
                    }
                }

                KnowledgeCaptureField("置信度") {
                    val rawConfidence = confidenceText.trim().toDoubleOrNull()
                    val clampedConfidence = rawConfidence?.coerceIn(0.0, 1.0)
                    val isConfidenceOutOfRange = rawConfidence != null && rawConfidence != clampedConfidence
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            flexDirection(FlexDirection.Column)
                            property("gap", "4px")
                        }
                    }) {
                        Input(InputType.Text) {
                            value(confidenceText)
                            onInput { onConfidenceTextChange(it.value) }
                            attr("inputmode", "decimal")
                            attr("placeholder", "0.90")
                            style {
                                width(120.px)
                                height(40.px)
                                borderRadius(8.px)
                                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                                padding(0.px, 10.px)
                                property("box-sizing", "border-box")
                            }
                        }
                        if (isConfidenceOutOfRange) {
                            Div({
                                style {
                                    fontSize(11.px)
                                    color(Color("#C85046"))
                                }
                            }) {
                                Text("超出范围(0–1)，实际存为 ${clampedConfidence?.let { "${(it * 100).toInt()}%" } ?: "0%"}")
                            }
                        }
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
                CaptureBadge(knowledgeStatusLabel(status), if (status == KBEntryStatus.PUBLISHED) SilkColors.success else SilkColors.primaryDark)
                CaptureBadge("会议沉淀", SilkColors.primary)
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
                        backgroundColor(Color(if (canSubmit) SilkColors.primary else SilkColors.border))
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
                }) { Text(if (isSaving) "保存中..." else if (status == KBEntryStatus.PUBLISHED) "发布纪要" else "存入候选") }
            }
        }
    }
}
