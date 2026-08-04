package com.silk.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowRoomMemberContractTest {
    @Test
    fun addMemberBodyContainsEscapedUserId() {
        val userId = "member-\"quoted\""
        val body = workflowRoomMemberRequestBody(userId)

        assertEquals(userId, Json.parseToJsonElement(body).jsonObject["userId"]?.jsonPrimitive?.content)
    }
}
