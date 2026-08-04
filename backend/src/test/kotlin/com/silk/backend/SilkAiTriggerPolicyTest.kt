package com.silk.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilkAiTriggerPolicyTest {
    @Test
    fun `team channel requires a leading Silk mention regardless of member count`() {
        assertFalse(shouldTriggerSilkAi(isSilkPrivateChat = false, content = "普通团队消息"))
        assertFalse(shouldTriggerSilkAi(isSilkPrivateChat = false, content = "@Silky 请总结"))
        assertTrue(shouldTriggerSilkAi(isSilkPrivateChat = false, content = "@Silk 请总结"))
        assertTrue(shouldTriggerSilkAi(isSilkPrivateChat = false, content = "  @silk 请总结"))
        assertTrue(shouldTriggerSilkAi(isSilkPrivateChat = false, content = "@Silk，请总结"))
    }

    @Test
    fun `Silk private chat stays implicit and team mention is stripped`() {
        assertTrue(shouldTriggerSilkAi(isSilkPrivateChat = true, content = "直接提问"))
        assertEquals("直接提问", extractSilkRequest("直接提问", isSilkPrivateChat = true))
        assertEquals("请总结", extractSilkRequest("@Silk 请总结", isSilkPrivateChat = false))
        assertEquals("请总结", extractSilkRequest("@Silk，请总结", isSilkPrivateChat = false))
    }
}
