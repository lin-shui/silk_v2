package com.silk.backend

import com.silk.backend.database.UnreadRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

abstract class BackendContractTestBase {
    protected val json = Json {
        ignoreUnknownKeys = true
    }

    private val managedProperties = listOf(
        "silk.storage.root",
        "silk.db.url",
        "silk.chat.dir",
        "silk.chat.backup.dir",
        "silk.static.dir"
    )
    private val originalProperties = mutableMapOf<String, String?>()

    protected lateinit var tempRoot: Path
        private set

    @BeforeTest
    fun setUpBackendContractTestBase() {
        tempRoot = Files.createTempDirectory("silk-backend-contract")
        tempRoot.resolve("chat_history").createDirectories()
        tempRoot.resolve("chat_history_backup").createDirectories()
        tempRoot.resolve("static").createDirectories()

        managedProperties.forEach { key ->
            originalProperties[key] = System.getProperty(key)
        }

        System.setProperty("silk.storage.root", tempRoot.toString())
        System.setProperty("silk.db.url", "jdbc:sqlite:${tempRoot.resolve("silk-test.db").toAbsolutePath()}")
        System.setProperty("silk.chat.dir", tempRoot.resolve("chat_history").toString())
        System.setProperty("silk.chat.backup.dir", tempRoot.resolve("chat_history_backup").toString())
        System.setProperty("silk.static.dir", tempRoot.resolve("static").toString())

        resetRoutingStateForTests()
        UnreadRepository.clearForTests()
    }

    @AfterTest
    fun tearDownBackendContractTestBase() {
        resetRoutingStateForTests()
        UnreadRepository.clearForTests()

        managedProperties.forEach { key ->
            val value = originalProperties[key]
            if (value == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
        }
        originalProperties.clear()

        if (::tempRoot.isInitialized) {
            tempRoot.toFile().deleteRecursively()
        }
    }

    protected fun ApplicationTestBuilder.jsonClient(): HttpClient {
        return createClient {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }
}
