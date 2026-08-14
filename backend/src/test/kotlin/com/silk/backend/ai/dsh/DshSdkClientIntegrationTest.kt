// backend/src/test/kotlin/com/silk/backend/ai/dsh/DshSdkClientIntegrationTest.kt
package com.silk.backend.ai.dsh

import com.silk.backend.ai.AIConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真实 dsh runtime 集成冒烟：仅在配置了 DSH_RUNTIME_CMD + DEEPSEEK_API_KEY 时执行，
 * 否则跳过（与 CI 无 key 场景兼容）。
 */
class DshSdkClientIntegrationTest {

    @Test
    fun `prompt streams text from real dsh runtime`() = runBlocking {
        val cmd = AIConfig.DSH_RUNTIME_CMD
        if (cmd.isEmpty() || AIConfig.DEEPSEEK_API_KEY.isBlank()) {
            println("跳过 DshSdkClient 集成冒烟：未配置 DSH_RUNTIME_CMD / DEEPSEEK_API_KEY")
            return@runBlocking
        }
        val root = createTempDir("dsh-it-")
        val client = DshSdkClient(
            launchCommand = cmd,
            runtimeCwd = File(AIConfig.DSH_RUNTIME_CWD),
            sessionRoot = File(root, ".sessions").absolutePath,
            sessionCwd = root.absolutePath,
        )
        try {
            val text = client.prompt("silk-it-1", "用一句中文回答：1+1 等于几？") { }
            assertTrue(text.isNotBlank(), "dsh prompt 返回空文本")
            assertTrue(text.contains("2"), "dsh 回答应包含 2，实际: $text")
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }
}
