package com.silk.backend

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsrRoutesTest {

    @Test
    fun `invalid base64 payload returns bad request`() {
        TestWorkspace().use {
            testApplication {
                application { module() }

                val response = client.post("/api/asr/transcribe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"audio":"not-base64!!!","format":"wav"}""")
                }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains("Invalid base64 audio"))
            }
        }
    }
}
