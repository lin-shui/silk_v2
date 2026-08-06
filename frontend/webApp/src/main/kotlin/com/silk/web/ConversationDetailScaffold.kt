@file:Suppress("MatchingDeclarationName")

package com.silk.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

internal enum class ConversationDetailVariant(val layoutClass: String) {
    CHAT("silk-chat-layout"),
    WORKFLOW("silk-workflow-layout"),
}

/**
 * Shared geometry contract for every room detail. Feature implementations own
 * their state and content; this scaffold owns sizing, shrinking, and clipping.
 */
@Composable
internal fun ConversationDetailScaffold(
    variant: ConversationDetailVariant,
    content: @Composable () -> Unit,
) {
    Div({ attr("class", "silk-conversation-scaffold") }) {
        val paneClass = if (variant == ConversationDetailVariant.CHAT) " silk-conversation-pane" else ""
        Div({
            attr(
                "class",
                "silk-conversation-scaffold-layout ${variant.layoutClass}$paneClass",
            )
        }) {
            content()
        }
    }
}

/** A vertically arranged conversation pane inside the shared detail scaffold. */
@Composable
internal fun ConversationPaneScaffold(
    className: String,
    content: @Composable () -> Unit,
) {
    Div({ attr("class", "silk-conversation-pane $className") }) {
        content()
    }
}

internal enum class ConversationActionTone(val cssClass: String) {
    DEFAULT(""),
    PRIMARY(" is-primary"),
    DANGER(" is-danger"),
}

/** Shared room header. Callers provide feature-specific subtitle and actions. */
@Composable
internal fun ConversationHeader(
    icon: String,
    title: String,
    className: String = "",
    titleSuffix: (@Composable () -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Div({
        attr(
            "class",
            "silk-conversation-header silk-conversation-fixed-region $className".trim(),
        )
    }) {
        Span({ attr("class", "silk-conversation-header-icon") }) { Text(icon) }
        Div({ attr("class", "silk-conversation-header-title") }) {
            Div({ attr("class", "silk-conversation-header-title-row") }) {
                Span({ attr("class", "silk-conversation-header-title-text") }) { Text(title) }
                titleSuffix?.invoke()
            }
            subtitle?.let { subtitleContent ->
                Div({ attr("class", "silk-conversation-header-subtitle") }) {
                    subtitleContent()
                }
            }
        }
        Div({ attr("class", "silk-conversation-header-actions") }) {
            actions()
        }
    }
}

/** Stable icon action used by chat and Team Channel headers. */
@Composable
internal fun ConversationHeaderActionButton(
    label: String,
    icon: String? = null,
    text: String? = null,
    enabled: Boolean = true,
    tone: ConversationActionTone = ConversationActionTone.DEFAULT,
    onClick: () -> Unit,
) {
    val labelClass = if (text != null) " has-label" else ""
    Button({
        attr("class", "silk-conversation-header-action$labelClass${tone.cssClass}")
        attr("title", label)
        attr("aria-label", label)
        if (!enabled) attr("disabled", "")
        onClick { if (enabled) onClick() }
    }) {
        icon?.let { value ->
            Span({ attr("class", "silk-conversation-header-action-icon") }) { Text(value) }
        }
        text?.let { value ->
            Span({ attr("class", "silk-conversation-header-action-label") }) { Text(value) }
        }
    }
}

@Composable
internal fun ConversationHeaderStatus(text: String) {
    Span({
        attr("class", "silk-conversation-header-status")
        attr("title", text)
    }) { Text(text) }
}

/** Shared composer surface. Message semantics stay in the caller. */
@Composable
internal fun ConversationComposerScaffold(
    className: String = "",
    content: @Composable () -> Unit,
) {
    Div({
        attr(
            "class",
            "silk-conversation-composer silk-conversation-fixed-region $className".trim(),
        )
    }) {
        content()
    }
}

@Composable
internal fun ConversationComposerContext(content: @Composable () -> Unit) {
    Div({ attr("class", "silk-conversation-composer-context") }) { content() }
}

@Composable
internal fun ConversationComposerAccessoryRow(
    className: String = "",
    content: @Composable () -> Unit,
) {
    Div({
        attr("class", "silk-conversation-composer-accessories $className".trim())
    }) {
        content()
    }
}

@Composable
internal fun ConversationComposerPreview(content: @Composable () -> Unit) {
    Div({ attr("class", "silk-conversation-composer-preview") }) { content() }
}

@Composable
internal fun ConversationComposerInputRow(content: @Composable () -> Unit) {
    Div({ attr("class", "silk-conversation-composer-input-row") }) { content() }
}

@Composable
internal fun ConversationComposerInputSurface(content: @Composable () -> Unit) {
    Div({ attr("class", "silk-conversation-composer-input-surface") }) { content() }
}

@Composable
internal fun ConversationComposerToolsRow(content: @Composable () -> Unit) {
    Div({ attr("class", "silk-conversation-composer-tools") }) { content() }
}

@Composable
internal fun ConversationComposerPrimaryAction(
    isGenerating: Boolean,
    enabled: Boolean,
    sendLabel: String,
    stopLabel: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val label = if (isGenerating) stopLabel else sendLabel
    val actionEnabled = isGenerating || enabled
    val stateClass = if (isGenerating) " is-stop" else " is-send"
    Button({
        attr("class", "silk-conversation-composer-primary$stateClass")
        attr("title", label)
        attr("aria-label", label)
        if (!actionEnabled) attr("disabled", "")
        onClick {
            when {
                isGenerating -> onStop()
                enabled -> onSend()
            }
        }
    }) {
        Text(if (isGenerating) "■" else "↑")
    }
}
