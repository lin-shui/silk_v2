package com.silk.backend.card

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ButtonType(val value: String) {
    PRIMARY("primary"),
    DANGER("danger"),
    DEFAULT("default"),
}

class CardBuilder(
    title: String,
    template: String = "blue",
) {
    private val header = CardHeader(title = title, template = template)
    private val elements = mutableListOf<CardElement>()

    fun addText(content: String) = apply {
        elements.add(CardElement(tag = "text", content = content))
    }

    fun addDivider() = apply {
        elements.add(CardElement(tag = "divider"))
    }

    fun addButton(text: String, value: String, type: ButtonType = ButtonType.DEFAULT) = apply {
        elements.add(CardElement(tag = "button", text = text, value = value, type = type.value))
    }

    fun addTextInput(name: String, placeholder: String? = null, defaultValue: String? = null) = apply {
        elements.add(CardElement(tag = "text_input", name = name, placeholder = placeholder, defaultValue = defaultValue))
    }

    fun addTextArea(name: String, placeholder: String? = null, defaultValue: String? = null, rows: Int? = null) = apply {
        elements.add(CardElement(tag = "text_area", name = name, placeholder = placeholder, defaultValue = defaultValue, rows = rows))
    }

    fun build(): String {
        val card = CardContent(header = header, elements = elements.toList(), disabled = false)
        return Json.encodeToString(card)
    }

    fun buildDisabled(): String {
        val card = CardContent(header = header, elements = elements.toList(), disabled = true)
        return Json.encodeToString(card)
    }
}
