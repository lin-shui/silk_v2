package com.silk.backend.git

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitContextBuilderTest {
    private val binding = RoomGitBinding("room", owner = "octo", repo = "demo", webhookUrl = "https://silk.test", tokenEncrypted = "v1:secret", webhookSecretEncrypted = "v1:secret", createdBy = "u", createdAt = 1, updatedAt = 1)

    @Test
    fun `only active binding enables bounded context`() {
        val event = GitEventRecord("d", "room", "issues", "opened", "octo/demo", 1, "Bug", "https://github.com/octo/demo/issues/1", "opened", 1)
        val dir = Files.createTempDirectory("git-context").toFile()
        try {
            val store = GitEventStore(dir.absolutePath)
            store.recordDelivery("room", "d", event)
            val context = GitContextBuilder.buildForRoom("room", binding, store)
            assertFalse(context.contains("secret"))
            assertTrue(context.contains("octo/demo"))
            assertTrue(GitContextBuilder.buildForRoom("other", binding, store).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
