package com.silk.backend.ai

import com.silk.backend.EnvLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 验证 DirectModelAgent 的 [autocli] 工具路径（ProcessBuilder、白名单、注入拦截）。
 * 需要本机 PATH 或 .env 中的 [AUTOCLI_PATH] 指向可执行的 autocli；
 * 集成断言在 [AIConfig.AUTOCLI_ENABLED] 为 false 时自动跳过。
 */
class DirectModelAgentAutoCliTest {
    @BeforeTest
    fun setup() {
        ToolPolicyManager.resetForTest()
        EnvLoader.load()
    }

    @Test
    fun `autocli rejects shell metacharacters when enabled`() = runBlocking {
        if (!AIConfig.AUTOCLI_ENABLED) return@runBlocking

        val agent = DirectModelAgent(sessionId = "test_session")
        val result = agent.executeToolForTest(
            toolName = "autocli",
            arguments = """{"command":"hackernews top; echo evil"}""",
            requestUserId = "u1",
            accessibleSessionIds = listOf("test_session")
        )
        assertContains(result, "安全限制")
        assertContains(result, "不允许")
    }

    @Test
    fun `autocli rejects site not in sandbox whitelist when enabled`() = runBlocking {
        if (!AIConfig.AUTOCLI_ENABLED) return@runBlocking

        val agent = DirectModelAgent(sessionId = "test_session")
        val result = agent.executeToolForTest(
            toolName = "autocli",
            arguments = """{"command":"not-a-real-site top"}""",
            requestUserId = "u1",
            accessibleSessionIds = listOf("test_session")
        )
        assertContains(result, "安全限制")
        assertContains(result, "不在允许列表")
    }

    @Test
    fun `autocli executes whitelisted public command and returns payload when enabled`() = runBlocking {
        if (!AIConfig.AUTOCLI_ENABLED) {
            return@runBlocking
        }
        val cliPath = Paths.get(AIConfig.AUTOCLI_PATH)
        if (!Files.isRegularFile(cliPath) || !Files.isExecutable(cliPath)) {
            return@runBlocking
        }

        val agent = DirectModelAgent(sessionId = "test_session")
        val result = agent.executeToolForTest(
            toolName = "autocli",
            arguments = """{"command":"devto top --limit 1"}""",
            requestUserId = "u1",
            accessibleSessionIds = listOf("test_session")
        )

        if (result.startsWith("⏱️") || result.startsWith("❌")) {
            fail("AutoCLI 调用失败（网络或服务）: ${result.take(500)}")
        }
        assertTrue(result.contains("AutoCLI 结果"), "应包含成功前缀: ${result.take(200)}")
        assertTrue(
            result.contains("[citation:") || result.contains("\"title\"") || result.contains("rank"),
            "应含 citation 标记或 JSON 字段: ${result.take(400)}"
        )
    }

    @Test
    fun `autocli tool appears in available tools when enabled`() {
        EnvLoader.load()
        if (!AIConfig.AUTOCLI_ENABLED) return

        val agent = DirectModelAgent(sessionId = "test_session")
        val names = agent.availableToolNamesForTest()
        assertTrue("autocli" in names, "启用 AUTOCLI 时应暴露 autocli 工具: $names")
    }
}
