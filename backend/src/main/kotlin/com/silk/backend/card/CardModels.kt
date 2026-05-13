package com.silk.backend.card

import kotlinx.serialization.Serializable

/**
 * 交互卡片顶层结构，序列化后作为 Message.content 的 JSON 字符串
 */
@Serializable
data class CardContent(
    val header: CardHeader,
    val elements: List<CardElement>,
    val disabled: Boolean = false,
)

@Serializable
data class CardHeader(
    val title: String,
    val template: String = "blue",
)

/**
 * 卡片元素：通过 tag 字段区分类型。
 * 使用扁平结构 + nullable 字段，避免 sealed class 的多态序列化复杂度。
 */
@Serializable
data class CardElement(
    val tag: String,                       // "text", "divider", "button", "text_input", "text_area"
    val content: String? = null,
    val text: String? = null,
    val value: String? = null,
    val type: String? = null,              // "primary", "danger", "default"
    val name: String? = null,
    val placeholder: String? = null,
    val defaultValue: String? = null,
    val rows: Int? = null,
)

/**
 * 用户点击卡片按钮后发回的 payload（存放在 CARD_REPLY 消息的 content 中）
 */
@Serializable
data class CardReplyPayload(
    val cardId: String,                    // 所回复的卡片消息 ID
    val action: String,                    // 被点击按钮的 value
    val inputs: Map<String, String> = emptyMap(),
)
