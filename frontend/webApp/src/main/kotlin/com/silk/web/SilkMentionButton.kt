package com.silk.web

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.style
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

internal fun insertSilkMention(text: String, cursorPosition: Int): Pair<String, Int> {
    val cursor = cursorPosition.coerceIn(0, text.length)
    val beforeCursor = text.substring(0, cursor)
    val afterCursor = text.substring(cursor)
    val leadingSpace = if (beforeCursor.isNotEmpty() && !beforeCursor.last().isWhitespace()) " " else ""
    val inserted = "$leadingSpace@Silk "
    return "$beforeCursor$inserted$afterCursor" to beforeCursor.length + inserted.length
}

@Composable
internal fun SilkMentionButton(
    inputElementId: String,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
) {
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
                val input = document.getElementById(inputElementId) as? org.w3c.dom.HTMLTextAreaElement
                val cursorPosition = input?.selectionStart ?: messageText.length
                val insertion = insertSilkMention(messageText, cursorPosition)
                onMessageTextChange(insertion.first)
                window.setTimeout({
                    val updatedInput = document.getElementById(inputElementId) as? org.w3c.dom.HTMLTextAreaElement
                    updatedInput?.setSelectionRange(insertion.second, insertion.second)
                    updatedInput?.focus()
                }, 0)
            }
        }) {
            Text("@Silk")
        }
    }
}
