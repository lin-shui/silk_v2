package com.silk.shared

import com.silk.shared.i18n.getStrings
import com.silk.shared.models.Language
import com.silk.shared.models.Message
import com.silk.shared.models.MessageCategory
import com.silk.shared.models.MessageType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun messageSerializationPreservesRecallAndStepMetadata() {
        val message = Message(
            id = "msg-1",
            userId = "user-1",
            userName = "Silk",
            content = "撤回通知",
            timestamp = 1_711_111_111_000,
            type = MessageType.RECALL,
            currentStep = 2,
            totalSteps = 4,
            isIncremental = true,
            category = MessageCategory.STEP_PROCESS
        )

        val roundTrip = json.decodeFromString<Message>(json.encodeToString(message))

        assertEquals(MessageType.RECALL, roundTrip.type)
        assertEquals(MessageCategory.STEP_PROCESS, roundTrip.category)
        assertEquals(2, roundTrip.currentStep)
        assertEquals(4, roundTrip.totalSteps)
        assertTrue(roundTrip.isIncremental)
    }

    @Test
    fun languageLookupReturnsExpectedTranslations() {
        val english = getStrings(Language.ENGLISH)
        val chinese = getStrings(Language.CHINESE)

        assertEquals("Settings", english.settingsTitle)
        assertEquals("设置", chinese.settingsTitle)
        assertEquals("Chat with Silk", english.chatWithSilk)
        assertEquals("与 Silk 对话", chinese.chatWithSilk)
    }

    @Test
    fun localizedTemplatesKeepRuntimePlaceholders() {
        val english = getStrings(Language.ENGLISH)
        val chinese = getStrings(Language.CHINESE)

        assertTrue("{count}" in english.groupMembersTitleWithCount)
        assertTrue("{count}" in chinese.groupMembersTitleWithCount)
        assertTrue("{code}" in english.invitationCodeCopied)
        assertTrue("{code}" in chinese.invitationCodeCopied)
    }
}
