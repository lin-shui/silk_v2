package com.silk.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silk.shared.ChatClient
import com.silk.shared.models.Message
import com.silk.shared.models.MessageType
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// ── Card JSON models (mirrors backend CardModels.kt) ──

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

private data class TemplateColors(val start: Color, val end: Color, val border: Color)

private fun templateColors(template: String): TemplateColors = when (template) {
    "gold" -> TemplateColors(Color(0xFFC9A86C), Color(0xFFA8894D), Color(0xFFC9A86C))
    "green" -> TemplateColors(Color(0xFF27AE60), Color(0xFF1E8449), Color(0xFF27AE60))
    "red" -> TemplateColors(Color(0xFFE74C3C), Color(0xFFC0392B), Color(0xFFE74C3C))
    "blue" -> TemplateColors(Color(0xFF3498DB), Color(0xFF2980B9), Color(0xFF3498DB))
    "orange" -> TemplateColors(Color(0xFFE67E22), Color(0xFFD35400), Color(0xFFE67E22))
    else -> TemplateColors(Color(0xFFC9A86C), Color(0xFFA8894D), Color(0xFFC9A86C))
}

private val cardJson = Json { ignoreUnknownKeys = true }

// ── Helper functions ──

private fun parseCard(content: String): CardContent? = try {
    cardJson.decodeFromString<CardContent>(content)
} catch (_: Exception) {
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

// ── Main Composable ──

@Composable
fun CardMessageItem(
    message: Message,
    chatClient: ChatClient,
    currentUserId: String,
    userName: String,
): Boolean = when (message.type) {
    MessageType.CARD -> {
        CardMessageRenderer(
            message = message,
            chatClient = chatClient,
            currentUserId = currentUserId,
            userName = userName,
        )
        true
    }
    MessageType.CARD_REPLY -> true
    else -> false
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
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFF5F5F5),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Text(
                text = "[卡片解析失败]",
                modifier = Modifier.padding(8.dp),
                color = Color(0xFF999999),
                fontSize = 13.sp,
            )
        }
        return
    }

    val inputValues = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(card) { initInputDefaults(card.elements, inputValues) }

    var disabledForContent by remember { mutableStateOf<String?>(null) }
    val localDisabled = disabledForContent != null && disabledForContent == message.content
    val isDisabled = localDisabled || card.disabled

    val colors = templateColors(card.header.template)
    val borderColor = if (isDisabled) Color(0xFFDDDDDD) else colors.border

    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 4.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(colors.start, colors.end)),
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = card.header.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .then(if (isDisabled) Modifier.background(Color(0x0A000000)) else Modifier),
            ) {
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
    }
}

// ── Element rendering ──

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
    Text(
        text = element.content ?: "",
        fontSize = 14.sp,
        color = Color(0xFF333333),
        lineHeight = 20.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

@Composable
private fun CardDividerElement() {
    Divider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = Color(0xFFEEEEEE),
    )
}

@Composable
private fun CardButtonElement(
    element: CardElement,
    disabled: Boolean,
    onSubmit: (String) -> Unit,
) {
    val buttonValue = element.value ?: ""
    when (element.type) {
        "primary" -> Button(
            onClick = { onSubmit(buttonValue) },
            enabled = !disabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFC9A86C),
                disabledContainerColor = Color(0xFFF0F0F0),
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = element.text ?: "",
                fontWeight = FontWeight.Medium,
                color = if (disabled) Color(0xFFBBBBBB) else Color.White,
            )
        }
        "danger" -> Button(
            onClick = { onSubmit(buttonValue) },
            enabled = !disabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE74C3C),
                disabledContainerColor = Color(0xFFF0F0F0),
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = element.text ?: "",
                fontWeight = FontWeight.Medium,
                color = if (disabled) Color(0xFFBBBBBB) else Color.White,
            )
        }
        else -> OutlinedButton(
            onClick = { onSubmit(buttonValue) },
            enabled = !disabled,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = element.text ?: "",
                color = if (disabled) Color(0xFFBBBBBB) else Color(0xFF666666),
            )
        }
    }
}

@Composable
private fun CardTextInputElement(
    element: CardElement,
    disabled: Boolean,
    inputValues: MutableMap<String, String>,
) {
    val name = element.name ?: return
    OutlinedTextField(
        value = inputValues[name] ?: "",
        onValueChange = { inputValues[name] = it },
        placeholder = element.placeholder?.let { ph -> { Text(ph, fontSize = 14.sp) } },
        enabled = !disabled,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

@Composable
private fun CardTextAreaElement(
    element: CardElement,
    disabled: Boolean,
    inputValues: MutableMap<String, String>,
) {
    val name = element.name ?: return
    OutlinedTextField(
        value = inputValues[name] ?: "",
        onValueChange = { inputValues[name] = it },
        placeholder = element.placeholder?.let { ph -> { Text(ph, fontSize = 14.sp) } },
        enabled = !disabled,
        minLines = element.rows ?: 3,
        maxLines = (element.rows ?: 3) + 2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}
