package com.silk.backend.agents.auth

import com.silk.backend.TestWorkspace
import com.silk.backend.database.AgentSecurityEvents
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRevocationWatcherTest {
    @Test
    fun `durable revocations are delivered to another node in sequence`() = runTest {
        TestWorkspace().use {
            val devices = mutableListOf<String>()
            val agents = mutableListOf<String>()
            val watcher = AgentRevocationWatcher(
                initialSequence = 0,
                disconnectDevice = { devices += it },
                disconnectAgent = { agents += it },
            )
            transaction {
                AgentSecurityEvents.insert { row ->
                    row[userId] = "owner"
                    row[actorId] = "owner"
                    row[action] = AgentSecurityEventAction.AGENT_REVOKED.name
                    row[deviceId] = "device-1"
                    row[agentInstanceId] = "agent-1"
                    row[metadataJson] = "{}"
                    row[createdAt] = LocalDateTime.now()
                }
                AgentSecurityEvents.insert { row ->
                    row[userId] = "owner"
                    row[actorId] = "owner"
                    row[action] = AgentSecurityEventAction.DEVICE_REVOKED.name
                    row[deviceId] = "device-1"
                    row[agentInstanceId] = null
                    row[metadataJson] = "{}"
                    row[createdAt] = LocalDateTime.now()
                }
            }

            watcher.pollOnce()
            watcher.pollOnce()

            assertEquals(listOf("agent-1"), agents)
            assertEquals(listOf("device-1"), devices)
        }
    }
}
