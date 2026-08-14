// backend/src/test/kotlin/com/silk/backend/ai/dsh/DshSdkClientIntegrationTest.kt
package com.silk.backend.ai.dsh

import com.silk.backend.ai.AIConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 真实 dsh runtime 集成冒烟：仅在配置了 DSH_RUNTIME_CMD + DEEPSEEK_API_KEY 时执行，
 * 否则跳过（与 CI 无 key 场景兼容）。
 */
class DshSdkClientIntegrationTest {

    private fun configured(): Boolean =
        AIConfig.DSH_RUNTIME_CMD.isNotEmpty() && AIConfig.DEEPSEEK_API_KEY.isNotBlank()

    private fun newClient(root: File, promptTimeoutMs: Long = AIConfig.DSH_PROMPT_TIMEOUT_MS, idleTimeoutMs: Long = 600_000L) =
        DshSdkClient(
            launchCommand = AIConfig.DSH_RUNTIME_CMD,
            runtimeCwd = File(AIConfig.DSH_RUNTIME_CWD),
            sessionRoot = File(root, ".sessions").absolutePath,
            sessionCwd = root.absolutePath,
            promptTimeoutMs = promptTimeoutMs,
            idleTimeoutMs = idleTimeoutMs,
        )

    @Test
    fun `prompt streams text from real dsh runtime`() = runBlocking {
        if (!configured()) {
            println("跳过 DshSdkClient 集成冒烟：未配置 DSH_RUNTIME_CMD / DEEPSEEK_API_KEY")
            return@runBlocking
        }
        val root = createTempDir("dsh-it-")
        val client = newClient(root)
        try {
            val text = client.prompt("silk-it-1", "用一句中文回答：1+1 等于几？") { }
            assertTrue(text.isNotBlank(), "dsh prompt 返回空文本")
            assertTrue(text.contains("2"), "dsh 回答应包含 2，实际: $text")
        } finally {
            root.deleteRecursively()
            client.close()
        }
    }

    @Test
    fun `prompt timeout kills runtime and client stays restartable`() = runBlocking {
        if (!configured()) {
            println("跳过：未配置 DSH_RUNTIME_CMD / DEEPSEEK_API_KEY")
            return@runBlocking
        }
        val root = createTempDir("dsh-it-timeout-")
        val client = newClient(root, promptTimeoutMs = 200)
        try {
            var timedOut = false
            try {
                client.prompt("silk-t-1", "用中文写一篇 300 字的短文介绍深度学习。")
            } catch (@Suppress("SwallowedException") e: kotlinx.coroutines.TimeoutCancellationException) {
                timedOut = true
            }
            assertTrue(timedOut, "应触发 prompt 超时")
            assertFalse(client.runtimeAlive(), "超时后 runtime 进程应被终止")
            // 重启验证：同一 client 再次 prompt 不应抛 Stream closed
            runCatching { client.prompt("silk-t-2", "你好") }
            Unit
        } finally {
            root.deleteRecursively()
            client.close()
        }
    }

    @Test
    fun `idle timeout closes runtime after prompt`() = runBlocking {
        if (!configured()) {
            println("跳过：未配置 DSH_RUNTIME_CMD / DEEPSEEK_API_KEY")
            return@runBlocking
        }
        val root = createTempDir("dsh-it-idle-")
        val client = newClient(root, idleTimeoutMs = 1000)
        try {
            client.prompt("silk-i-1", "用一句话回答：1+1 等于几？") { }
            assertTrue(client.runtimeAlive(), "prompt 后 runtime 应存活")
            delay(2500)
            assertFalse(client.runtimeAlive(), "超过空闲阈值后 runtime 应被回收")
        } finally {
            root.deleteRecursively()
            client.close()
        }
    }
}
