package com.silk.backend.card

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardBuilderTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `build simple card with header and text`() {
        val cardJson = CardBuilder("测试标题", template = "blue")
            .addText("这是一段文本")
            .build()

        val card = json.decodeFromString<CardContent>(cardJson)
        assertEquals("测试标题", card.header.title)
        assertEquals("blue", card.header.template)
        assertEquals(1, card.elements.size)
        assertEquals("text", card.elements[0].tag)
        assertEquals("这是一段文本", card.elements[0].content)
        assertFalse(card.disabled)
    }

    @Test
    fun `build card with all element types`() {
        val cardJson = CardBuilder("完整卡片")
            .addText("说明文字")
            .addDivider()
            .addButton("确认", value = "confirm", type = ButtonType.PRIMARY)
            .addButton("取消", value = "cancel", type = ButtonType.DANGER)
            .addTextInput("username", placeholder = "请输入用户名")
            .addTextArea("comment", placeholder = "请输入备注", rows = 3)
            .build()

        val card = json.decodeFromString<CardContent>(cardJson)
        assertEquals(6, card.elements.size)
        assertEquals("text", card.elements[0].tag)
        assertEquals("divider", card.elements[1].tag)
        assertEquals("button", card.elements[2].tag)
        assertEquals("confirm", card.elements[2].value)
        assertEquals("primary", card.elements[2].type)
        assertEquals("button", card.elements[3].tag)
        assertEquals("danger", card.elements[3].type)
        assertEquals("text_input", card.elements[4].tag)
        assertEquals("username", card.elements[4].name)
        assertEquals("text_area", card.elements[5].tag)
        assertEquals(3, card.elements[5].rows)
    }

    @Test
    fun `build disabled card`() {
        val cardJson = CardBuilder("已回答", template = "green")
            .addText("你选择了：确认")
            .buildDisabled()

        val card = json.decodeFromString<CardContent>(cardJson)
        assertTrue(card.disabled)
        assertEquals("green", card.header.template)
    }

    @Test
    fun `build produces valid JSON string`() {
        val cardJson = CardBuilder("标题")
            .addButton("按钮", value = "btn")
            .build()

        val reparsed = json.decodeFromString<CardContent>(cardJson)
        assertEquals("标题", reparsed.header.title)
    }
}
