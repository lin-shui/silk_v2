package com.silk.backend.agents.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Replays durable revocation events so every backend node closes its local sockets. */
internal class AgentRevocationWatcher(
    private val pollIntervalMs: Long = 500L,
    initialSequence: Long = AgentAuthRepository.latestSecurityEventSequence(),
    private val disconnectDevice: suspend (String) -> Unit = ::disconnectLocalDevice,
    private val disconnectAgent: suspend (String) -> Unit = ::disconnectLocalAgent,
) {
    private val logger = LoggerFactory.getLogger(AgentRevocationWatcher::class.java)
    private var lastSequence = initialSequence
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { pollOnce() }
                    .onFailure { logger.warn("Agent revocation poll failed: {}", it.message) }
                delay(pollIntervalMs)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    suspend fun pollOnce() {
        while (true) {
            val events = AgentAuthRepository.listSecurityEventsAfter(lastSequence)
            if (events.isEmpty()) return
            events.forEach { event ->
                processEvent(event)
                lastSequence = event.sequence
            }
        }
    }

    private suspend fun processEvent(event: AgentSecurityEventDto) {
        when (event.action) {
            AgentSecurityEventAction.DEVICE_REVOKED -> event.deviceId?.let { disconnectDevice(it) }
            AgentSecurityEventAction.AGENT_REVOKED -> event.agentInstanceId?.let { disconnectAgent(it) }
            else -> Unit
        }
    }

    companion object {
        private suspend fun disconnectLocalDevice(deviceId: String) {
            AgentConnectionRegistry.disconnectDevice(deviceId)
            AgentBridgeConnectionRegistry.disconnectDevice(deviceId)
            AgentHostConnectionRegistry.disconnectDevice(deviceId)
        }

        private suspend fun disconnectLocalAgent(agentInstanceId: String) {
            AgentConnectionRegistry.disconnectAgent(agentInstanceId)
            AgentBridgeConnectionRegistry.disconnectAgent(agentInstanceId)
            AgentHostConnectionRegistry.disconnectAgent(agentInstanceId)
        }
    }
}
