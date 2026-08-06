package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.silk.shared.i18n.Strings
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginLeft
import org.jetbrains.compose.web.css.marginTop
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

internal enum class ConversationMemberTone {
    PRIMARY,
    SUCCESS,
    INFO,
    MUTED,
}

internal data class ConversationRoomMemberItem(
    val id: String,
    val displayName: String,
    val detail: String = "",
    val roleLabel: String = "",
    val avatarText: String = displayName.firstOrNull()?.toString() ?: "?",
    val avatarTone: ConversationMemberTone = ConversationMemberTone.MUTED,
    val actionLabel: String? = null,
    val secondaryActionLabel: String? = null,
    val canRemove: Boolean = false,
)

internal data class ConversationRoomMemberCandidateItem(
    val id: String,
    val displayName: String,
    val detail: String = "",
)

internal fun isRoomMemberSearchDisabled(query: String, busy: Boolean): Boolean =
    busy || query.isBlank()

internal fun roomMemberCandidateEmptyMessage(
    strings: Strings,
    query: String,
    searchAttempted: Boolean,
): String = when {
    query.isBlank() -> strings.noContactsToAdd
    !searchAttempted -> strings.memberSearchPrompt
    else -> strings.noAddableUsers
}

@Suppress("CyclomaticComplexMethod")
@Composable
internal fun ConversationRoomMembersDialog(
    strings: Strings,
    members: List<ConversationRoomMemberItem>,
    candidates: List<ConversationRoomMemberCandidateItem>,
    canAddMembers: Boolean,
    query: String,
    loading: Boolean,
    busy: Boolean,
    errorMessage: String?,
    successMessage: String?,
    emptyCandidatesMessage: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAdd: (String) -> Unit,
    onMemberAction: (String) -> Unit = {},
    onSecondaryMemberAction: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var showAddMembers by remember { mutableStateOf(false) }
    var pendingRemovalId by remember { mutableStateOf<String?>(null) }

    ModalOverlay(onDismiss = onDismiss) {
        Div({
            style {
                width(520.px)
                property("max-width", "calc(100vw - 32px)")
                property("max-height", "calc(100vh - 48px)")
                property("overflow-y", "auto")
                backgroundColor(Color(SilkColors.surfaceElevated))
                borderRadius(8.px)
                padding(20.px)
                property("box-sizing", "border-box")
                property("box-shadow", "0 12px 36px rgba(0,0,0,0.18)")
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    alignItems(AlignItems.Center)
                    property("gap", "12px")
                    marginBottom(16.px)
                }
            }) {
                Div({ style { property("min-width", "0") } }) {
                    H3({
                        style {
                            marginTop(0.px)
                            marginBottom(2.px)
                            color(Color(SilkColors.textPrimary))
                            fontSize(18.px)
                        }
                    }) { Text(strings.groupMembersTitle) }
                    Div({ style { color(Color(SilkColors.textSecondary)); fontSize(12.px) } }) {
                        Text("${members.size} ${strings.membersButton.lowercase()}")
                    }
                }
                Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); property("gap", "8px") } }) {
                    if (canAddMembers) {
                        MemberPanelButton(
                            label = if (showAddMembers) strings.closeButton else strings.addMemberButton,
                            primary = !showAddMembers,
                            onClick = { showAddMembers = !showAddMembers },
                        )
                    }
                    Button({
                        attr("type", "button")
                        attr("title", strings.closeButton)
                        attr("aria-label", strings.closeButton)
                        style {
                            width(32.px)
                            height(32.px)
                            padding(0.px)
                            border(0.px)
                            backgroundColor(Color("transparent"))
                            color(Color(SilkColors.textSecondary))
                            fontSize(20.px)
                            property("cursor", "pointer")
                        }
                        onClick { onDismiss() }
                    }) { Text("×") }
                }
            }

            if (showAddMembers && canAddMembers) {
                Div({
                    style {
                        padding(14.px, 0.px)
                        marginBottom(16.px)
                        property("border-top", "1px solid ${SilkColors.border}")
                        property("border-bottom", "1px solid ${SilkColors.border}")
                    }
                }) {
                    val searchDisabled = isRoomMemberSearchDisabled(query, busy)
                    Div({
                        style {
                            display(DisplayStyle.Flex)
                            property("gap", "8px")
                            marginBottom(12.px)
                        }
                    }) {
                        Input(InputType.Text) {
                            value(query)
                            onInput { onQueryChange(it.value) }
                            onKeyDown { event ->
                                if (event.key == "Enter" && !searchDisabled) {
                                    event.preventDefault()
                                    onSearch()
                                }
                            }
                            attr("placeholder", strings.memberSearchPlaceholder)
                            style {
                                property("flex", "1")
                                property("min-width", "0")
                                height(36.px)
                                padding(0.px, 10.px)
                                borderRadius(6.px)
                                border(1.px, LineStyle.Solid, Color(SilkColors.border))
                                property("box-sizing", "border-box")
                            }
                        }
                        MemberPanelButton(
                            label = strings.searchButton,
                            primary = true,
                            disabled = searchDisabled,
                            onClick = onSearch,
                        )
                    }

                    if (loading) {
                        MemberPanelEmptyText(strings.loading)
                    } else if (candidates.isEmpty()) {
                        MemberPanelEmptyText(emptyCandidatesMessage)
                    } else {
                        candidates.forEach { candidate ->
                            MemberCandidateRow(candidate, busy) { onAdd(candidate.id) }
                        }
                    }
                }
            }

            errorMessage?.let { MemberPanelFeedback(it, isError = true) }
            successMessage?.let { MemberPanelFeedback(it, isError = false) }

            Div({ style { color(Color(SilkColors.textSecondary)); fontSize(12.px); marginBottom(6.px) } }) {
                Text("${strings.groupMembersTitle} · ${members.size}")
            }
            if (loading && members.isEmpty()) {
                MemberPanelEmptyText(strings.loading)
            } else if (members.isEmpty()) {
                MemberPanelEmptyText(strings.noMembers)
            } else {
                members.forEach { member ->
                    val confirmingRemoval = pendingRemovalId == member.id
                    MemberRow(
                        member = member,
                        busy = busy,
                        confirmingRemoval = confirmingRemoval,
                        onAction = { onMemberAction(member.id) },
                        onSecondaryAction = { onSecondaryMemberAction(member.id) },
                        onRemove = {
                            if (confirmingRemoval) {
                                pendingRemovalId = null
                                onRemove(member.id)
                            } else {
                                pendingRemovalId = member.id
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberCandidateRow(
    candidate: ConversationRoomMemberCandidateItem,
    busy: Boolean,
    onAdd: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.SpaceBetween)
            property("gap", "12px")
            padding(9.px, 0.px)
            property("border-bottom", "1px solid ${SilkColors.border}")
        }
    }) {
        Div({ style { property("min-width", "0") } }) {
            Div({ style { color(Color(SilkColors.textPrimary)); fontSize(14.px); fontWeight("500") } }) {
                Text(candidate.displayName)
            }
            if (candidate.detail.isNotBlank()) {
                Div({ style { color(Color(SilkColors.textSecondary)); fontSize(12.px); marginTop(2.px) } }) {
                    Text(candidate.detail)
                }
            }
        }
        MemberPanelButton(
            label = if (busy) "添加中..." else "添加",
            primary = true,
            disabled = busy,
            onClick = onAdd,
        )
    }
}

@Composable
private fun MemberRow(
    member: ConversationRoomMemberItem,
    busy: Boolean,
    confirmingRemoval: Boolean,
    onAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onRemove: () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.SpaceBetween)
            property("gap", "12px")
            property("min-height", "50px")
            property("border-bottom", "1px solid ${SilkColors.border}")
        }
    }) {
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                property("gap", "10px")
                property("min-width", "0")
            }
        }) {
            Div({
                style {
                    width(32.px)
                    height(32.px)
                    borderRadius(16.px)
                    display(DisplayStyle.Flex)
                    alignItems(AlignItems.Center)
                    justifyContent(JustifyContent.Center)
                    backgroundColor(Color(member.avatarTone.backgroundColor()))
                    color(Color.white)
                    fontSize(13.px)
                    fontWeight("600")
                    property("flex-shrink", "0")
                }
            }) { Text(member.avatarText) }
            Div({ style { property("min-width", "0") } }) {
                Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); property("gap", "6px") } }) {
                    Span({ style { color(Color(SilkColors.textPrimary)); fontSize(14.px); fontWeight("500") } }) {
                        Text(member.displayName)
                    }
                    if (member.roleLabel.isNotBlank()) {
                        Span({
                            style {
                                padding(1.px, 6.px)
                                borderRadius(4.px)
                                backgroundColor(Color(SilkColors.secondary))
                                color(Color(SilkColors.primaryDark))
                                fontSize(10.px)
                            }
                        }) { Text(member.roleLabel) }
                    }
                }
                if (member.detail.isNotBlank()) {
                    Div({ style { color(Color(SilkColors.textSecondary)); fontSize(12.px); marginTop(2.px) } }) {
                        Text(member.detail)
                    }
                }
            }
        }
        Div({ style { display(DisplayStyle.Flex); alignItems(AlignItems.Center); property("gap", "6px") } }) {
            member.actionLabel?.let { label ->
                MemberPanelButton(label = label, disabled = busy, onClick = onAction)
            }
            member.secondaryActionLabel?.let { label ->
                MemberPanelButton(label = label, disabled = busy, onClick = onSecondaryAction)
            }
            if (member.canRemove) {
                MemberPanelButton(
                    label = if (confirmingRemoval) "确认移除" else "移除",
                    danger = true,
                    primary = confirmingRemoval,
                    disabled = busy,
                    onClick = onRemove,
                )
            }
        }
    }
}

@Composable
private fun MemberPanelButton(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    disabled: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        danger && primary -> SilkColors.error
        primary -> SilkColors.primary
        else -> "transparent"
    }
    val foreground = when {
        primary -> "#FFFFFF"
        danger -> SilkColors.error
        else -> SilkColors.textSecondary
    }
    Button({
        attr("type", "button")
        if (disabled) attr("disabled", "")
        style {
            height(32.px)
            padding(0.px, 11.px)
            borderRadius(6.px)
            border(1.px, LineStyle.Solid, Color(if (danger) SilkColors.error else SilkColors.border))
            backgroundColor(Color(background))
            color(Color(foreground))
            fontSize(12.px)
            property("white-space", "nowrap")
            property("cursor", if (disabled) "default" else "pointer")
            property("opacity", if (disabled) "0.55" else "1")
        }
        onClick { if (!disabled) onClick() }
    }) { Text(label) }
}

@Composable
private fun MemberPanelEmptyText(message: String) {
    Div({
        style {
            padding(18.px, 8.px)
            color(Color(SilkColors.textSecondary))
            fontSize(13.px)
            property("text-align", "center")
            property("white-space", "pre-line")
        }
    }) { Text(message) }
}

@Composable
private fun MemberPanelFeedback(message: String, isError: Boolean) {
    Div({
        style {
            color(Color(if (isError) SilkColors.error else "#2E7D32"))
            fontSize(13.px)
            marginBottom(12.px)
        }
    }) { Text(message) }
}

private fun ConversationMemberTone.backgroundColor(): String = when (this) {
    ConversationMemberTone.PRIMARY -> SilkColors.primary
    ConversationMemberTone.SUCCESS -> SilkColors.success
    ConversationMemberTone.INFO -> SilkColors.info
    ConversationMemberTone.MUTED -> SilkColors.textSecondary
}
