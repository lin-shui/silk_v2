package com.silk.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Hr
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.marginBottom
import org.jetbrains.compose.web.css.marginRight
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.attributes.InputType
import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import com.silk.shared.ChatClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.launch

// ── Card JSON models (frontend parsing, mirrors backend CardModels) ──

@Serializable
private data class CardContent(
    val header: CardHeader,
    val elements: List<CardElement>,
    val disabled: Boolean = false,
)

@Serializable
private data class CardHeader(
    val title: String,
    val template: String = "blue",
)

@Serializable
private data class CardElement(
    val tag: String,
    val content: String? = null,
    val text: String? = null,
    val value: String? = null,
    val type: String? = null,
    val name: String? = null,
    val placeholder: String? = null,
    val defaultValue: String? = null,
    val rows: Int? = null,
)

// ── Theme color mapping ──

private fun templateToGradient(template: String): String = when (template) {
    "gold" -> "linear-gradient(135deg, #C9A86C, #A8894D)"
    "green" -> "linear-gradient(135deg, #27ae60, #1e8449)"
    "red" -> "linear-gradient(135deg, #e74c3c, #c0392b)"
    "blue" -> "linear-gradient(135deg, #3498db, #2980b9)"
    else -> "linear-gradient(135deg, #C9A86C, #A8894D)"
}

private val cardJson = Json { ignoreUnknownKeys = true }

// ── Main Composable ──

private fun buildCardReplyJson(
    messageId: String,
    buttonValue: String,
    inputs: Map<String, String>,
): String = buildJsonObject {
    put("cardId", JsonPrimitive(messageId))
    put("action", JsonPrimitive(buttonValue))
    put("inputs", buildJsonObject {
        inputs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    })
}.toString()

private fun shouldSkipSubmit(
    buttonValue: String,
    isDisabled: Boolean,
    inputValues: Map<String, String>,
): Boolean {
    if (isDisabled) return true
    if (buttonValue.startsWith("__custom__")) {
        val qi = buttonValue.removePrefix("__custom__")
        val customText = inputValues["custom_answer_$qi"] ?: ""
        if (customText.isBlank()) return true
    }
    return false
}

private fun parseCard(content: String): CardContent? = try {
    cardJson.decodeFromString<CardContent>(content)
} catch (e: IllegalArgumentException) {
    console.warn("Card parse failed: ${e.message}")
    null
} catch (e: kotlinx.serialization.SerializationException) {
    console.warn("Card parse failed: ${e.message}")
    null
}

private fun initInputDefaults(elements: List<CardElement>, inputValues: MutableMap<String, String>) {
    elements.forEach { el ->
        if ((el.tag == "text_input" || el.tag == "text_area") && el.name != null) {
            if (!inputValues.containsKey(el.name)) {
                inputValues[el.name] = el.defaultValue ?: ""
            }
        }
    }
}

@Composable
fun CardMessageRenderer(
    message: Message,
    chatClient: ChatClient,
    currentUserId: String,
    userName: String,
) {
    val scope = rememberCoroutineScope()
    val card = remember(message.content) { parseCard(message.content) }

    if (card == null) {
        Div({ style { padding(8.px); color(Color("#999")) } }) {
            Text("[卡片解析失败] ${message.content.take(100)}")
        }
        return
    }

    val inputValues = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(card) { initInputDefaults(card.elements, inputValues) }

    // When backend refreshes the card via action="edit", message.content changes → auto-resets
    var disabledForContent by remember { mutableStateOf<String?>(null) }
    val localDisabled = disabledForContent != null && disabledForContent == message.content
    val isDisabled = localDisabled || card.disabled

    CardFrame(card, isDisabled) {
        card.elements.forEach { element ->
            CardBodyElement(element, isDisabled, inputValues) { buttonValue ->
                if (shouldSkipSubmit(buttonValue, isDisabled, inputValues)) return@CardBodyElement
                disabledForContent = message.content
                val replyJson = buildCardReplyJson(message.id, buttonValue, inputValues.toMap())
                scope.launch {
                    chatClient.sendMessage(
                        userId = currentUserId,
                        userName = userName,
                        content = replyJson,
                        type = MessageType.CARD_REPLY,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardFrame(card: CardContent, isDisabled: Boolean, body: @Composable () -> Unit) {
    Div({
        style {
            property("max-width", "80%")
            property("border-radius", "12px")
            property("border", if (isDisabled) "1px solid #ddd" else "1px solid #C9A86C")
            property("overflow", "hidden")
            property("box-shadow", "0 2px 8px rgba(0,0,0,0.08)")
            if (isDisabled) property("opacity", "0.85")
            marginBottom(8.px)
        }
    }) {
        Div({
            style {
                property("background", templateToGradient(card.header.template))
                padding(10.px, 16.px)
                color(Color.white)
                fontSize(15.px)
                property("font-weight", "600")
            }
        }) {
            Text(card.header.title)
        }
        Div({ style { padding(16.px) } }) { body() }
    }
}

@Composable
private fun CardBodyElement(
    element: CardElement,
    isDisabled: Boolean,
    inputValues: MutableMap<String, String>,
    onSubmit: (String) -> Unit,
) {
    when (element.tag) {
        "text" -> CardTextElement(element)
        "divider" -> CardDividerElement()
        "button" -> CardButtonElement(element, isDisabled, onSubmit)
        "text_input" -> CardTextInputElement(element, isDisabled, inputValues)
        "text_area" -> CardTextAreaElement(element, isDisabled, inputValues)
    }
}

@Composable
private fun CardTextElement(element: CardElement) {
    Div({
        style {
            fontSize(14.px)
            color(Color("#333"))
            marginBottom(8.px)
            property("white-space", "pre-wrap")
            property("word-break", "break-word")
        }
    }) {
        Text(element.content ?: "")
    }
}

@Composable
private fun CardDividerElement() {
    Hr({
        style {
            property("border", "none")
            property("border-top", "1px solid #eee")
            property("margin", "12px 0")
        }
    })
}

private data class ButtonStyle(
    val bgColor: String,
    val textColor: String,
    val border: String,
    val fontWeight: String?,
)

private fun resolveButtonStyle(type: String?, disabled: Boolean): ButtonStyle {
    if (disabled) return ButtonStyle("#f0f0f0", "#bbb", "1px solid #eee", null)
    return when (type) {
        "primary" -> ButtonStyle("#C9A86C", "white", "none", "500")
        "danger" -> ButtonStyle("#e74c3c", "white", "none", "500")
        else -> ButtonStyle("white", "#666", "1px solid #ddd", null)
    }
}

@Composable
private fun CardButtonElement(
    element: CardElement,
    disabled: Boolean,
    onSubmit: (String) -> Unit,
) {
    val style = resolveButtonStyle(element.type, disabled)

    Button({
        style {
            padding(8.px, 20.px)
            property("border-radius", "6px")
            property("border", style.border)
            backgroundColor(Color(style.bgColor))
            color(Color(style.textColor))
            fontSize(14.px)
            property("cursor", if (disabled) "not-allowed" else "pointer")
            style.fontWeight?.let { property("font-weight", it) }
            marginRight(8.px)
            marginBottom(8.px)
        }
        if (disabled) attr("disabled", "true")
        onClick { if (!disabled) onSubmit(element.value ?: "") }
    }) {
        Text(element.text ?: "")
    }
}

@Composable
private fun CardTextInputElement(
    element: CardElement,
    disabled: Boolean,
    inputValues: MutableMap<String, String>,
) {
    val name = element.name ?: return
    Input(InputType.Text) {
        value(inputValues[name] ?: "")
        attr("placeholder", element.placeholder ?: "")
        if (disabled) attr("disabled", "true")
        style {
            property("width", "100%")
            padding(8.px, 12.px)
            property("border", "1px solid ${if (disabled) "#eee" else "#ddd"}")
            property("border-radius", "6px")
            fontSize(14.px)
            property("outline", "none")
            property("box-sizing", "border-box")
            if (disabled) {
                backgroundColor(Color("#f9f9f9"))
                color(Color("#bbb"))
            }
            marginBottom(8.px)
        }
        onInput { event -> inputValues[name] = event.value }
    }
}

@Composable
private fun CardTextAreaElement(
    element: CardElement,
    disabled: Boolean,
    inputValues: MutableMap<String, String>,
) {
    val name = element.name ?: return
    TextArea {
        value(inputValues[name] ?: "")
        attr("placeholder", element.placeholder ?: "")
        attr("rows", (element.rows ?: 3).toString())
        if (disabled) attr("disabled", "true")
        style {
            property("width", "100%")
            padding(8.px, 12.px)
            property("border", "1px solid ${if (disabled) "#eee" else "#ddd"}")
            property("border-radius", "6px")
            fontSize(14.px)
            property("outline", "none")
            property("resize", "vertical")
            property("box-sizing", "border-box")
            if (disabled) {
                backgroundColor(Color("#f9f9f9"))
                color(Color("#bbb"))
            }
            marginBottom(8.px)
        }
        onInput { event -> inputValues[name] = event.value }
    }
}
