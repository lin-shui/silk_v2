@file:Suppress("MatchingDeclarationName")

package com.silk.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div

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
