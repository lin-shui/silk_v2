package com.silk.backend.git

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Synchronized JSON store for bindings, display events and webhook delivery idempotency. */
class GitEventStore(
    private val baseDir: String =
        System.getProperty("silk.workflowDir")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("SILK_WORKFLOW_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${System.getProperty("user.home")}/.silk-data/workflows",
    private val now: () -> Long = { System.currentTimeMillis() },
    private val deliveryTtlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val maxDeliveryIds: Int = 10_000,
) {
    companion object {
        private const val MAX_STORED_EVENTS = 1_000
        private val sharedStores = ConcurrentHashMap<String, GitEventStore>()

        fun shared(baseDir: String = defaultBaseDir()): GitEventStore =
            sharedStores.computeIfAbsent(File(baseDir).absolutePath) { GitEventStore(it) }

        private fun defaultBaseDir(): String =
            System.getProperty("silk.workflowDir")?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv("SILK_WORKFLOW_DIR")?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${System.getProperty("user.home")}/.silk-data/workflows"
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(GitEventStore::class.java)
    private val storeFile get() = File(baseDir, "git_integration_store.json")

    init { File(baseDir).mkdirs() }

    @Synchronized
    fun getBinding(roomId: String): RoomGitBinding? = load().bindings.firstOrNull { it.roomId == roomId }

    @Synchronized
    fun listBindings(): List<RoomGitBinding> = load().bindings

    internal fun pollingLeasePath(): Path = File(baseDir, ".git_polling.lock").toPath()

    @Synchronized
    fun getPollingState(roomId: String): GitPollingState = load().pollingStates[roomId] ?: GitPollingState()

    @Synchronized
    fun getResourceSnapshots(roomId: String): List<GitResourceSnapshot> = load().resourceSnapshots[roomId].orEmpty()

    @Synchronized
    fun putPollingBaselineIfCurrent(
        expectedBinding: RoomGitBinding,
        state: GitPollingState,
        snapshots: List<GitResourceSnapshot>,
    ): Boolean {
        val store = load()
        val bindingIndex = store.bindings.indexOfFirst { it.roomId == expectedBinding.roomId }
        val current = store.bindings.getOrNull(bindingIndex)
        if (current == null || !current.matches(expectedBinding) || current.ingestionMode != GitIngestionMode.POLLING) return false
        val updatedBinding = if (current.status == GitBindingStatus.ACTIVE) current else current.copy(status = GitBindingStatus.ACTIVE, updatedAt = now())
        save(
            store.copy(
                bindings = store.bindings.toMutableList().also { it[bindingIndex] = updatedBinding },
                pollingStates = store.pollingStates + (expectedBinding.roomId to state),
                resourceSnapshots = store.resourceSnapshots + (expectedBinding.roomId to snapshots),
            )
        )
        return true
    }

    @Synchronized
    fun putBinding(binding: RoomGitBinding) {
        val store = load()
        save(store.copy(bindings = store.bindings.filterNot { it.roomId == binding.roomId } + binding))
    }

    @Synchronized
    fun putWebhookBinding(binding: RoomGitBinding) {
        val store = load()
        save(
            store.copy(
                bindings = store.bindings.filterNot { it.roomId == binding.roomId } + binding,
                pollingStates = store.pollingStates - binding.roomId,
                resourceSnapshots = store.resourceSnapshots - binding.roomId,
            )
        )
    }

    @Synchronized
    fun putPollingBinding(binding: RoomGitBinding, state: GitPollingState, snapshots: List<GitResourceSnapshot>) {
        val store = load()
        save(
            store.copy(
                bindings = store.bindings.filterNot { it.roomId == binding.roomId } + binding,
                pollingStates = store.pollingStates + (binding.roomId to state),
                resourceSnapshots = store.resourceSnapshots + (binding.roomId to snapshots),
            )
        )
    }

    @Synchronized
    fun putPollingBindingIfCurrent(
        expectedBinding: RoomGitBinding,
        replacement: RoomGitBinding,
        state: GitPollingState,
        snapshots: List<GitResourceSnapshot>,
    ): Boolean {
        val store = load()
        val current = store.bindings.firstOrNull { it.roomId == expectedBinding.roomId }
        if (current == null || !current.matches(expectedBinding)) return false
        save(
            store.copy(
                bindings = store.bindings.filterNot { it.roomId == expectedBinding.roomId } + replacement,
                pollingStates = store.pollingStates + (expectedBinding.roomId to state),
                resourceSnapshots = store.resourceSnapshots + (expectedBinding.roomId to snapshots),
            )
        )
        return true
    }

    @Synchronized
    fun putWebhookBindingIfCurrent(expectedBinding: RoomGitBinding, replacement: RoomGitBinding): Boolean {
        val store = load()
        val current = store.bindings.firstOrNull { it.roomId == expectedBinding.roomId }
        if (current == null || !current.matches(expectedBinding)) return false
        save(
            store.copy(
                bindings = store.bindings.filterNot { it.roomId == expectedBinding.roomId } + replacement,
                pollingStates = store.pollingStates - expectedBinding.roomId,
                resourceSnapshots = store.resourceSnapshots - expectedBinding.roomId,
            )
        )
        return true
    }

    @Synchronized
    fun updateLastDelivery(roomId: String, timestamp: Long): Boolean {
        val store = load()
        val index = store.bindings.indexOfFirst { it.roomId == roomId }
        if (index < 0) return false
        val binding = store.bindings[index]
        if (binding.lastDeliveryAt != null && binding.lastDeliveryAt >= timestamp) return false
        val updated = binding.copy(lastDeliveryAt = timestamp, updatedAt = maxOf(binding.updatedAt, timestamp))
        save(store.copy(bindings = store.bindings.toMutableList().also { it[index] = updated }))
        return true
    }

    @Synchronized
    fun updateBindingStatus(roomId: String, status: GitBindingStatus): RoomGitBinding? {
        val store = load()
        val index = store.bindings.indexOfFirst { it.roomId == roomId }
        if (index < 0) return null
        val current = store.bindings[index]
        if (current.status == status) return current
        val updated = current.copy(status = status, updatedAt = now())
        save(store.copy(bindings = store.bindings.toMutableList().also { it[index] = updated }))
        return updated
    }

    @Synchronized
    fun removeBinding(roomId: String): RoomGitBinding? {
        val store = load()
        val removed = store.bindings.firstOrNull { it.roomId == roomId }
        val prefix = "$roomId\u0000"
        save(
            store.copy(
                bindings = store.bindings.filterNot { it.roomId == roomId },
                events = store.events.filterNot { it.roomId == roomId },
                processedDeliveryIds = store.processedDeliveryIds.filterKeys { !it.startsWith(prefix) },
                pollingStates = store.pollingStates - roomId,
                resourceSnapshots = store.resourceSnapshots - roomId,
            )
        )
        return removed
    }

    @Synchronized
    fun listEvents(roomId: String, limit: Int = 20): List<GitEventRecord> =
        load().events.filter { it.roomId == roomId }.sortedByDescending { it.createdAt }.take(limit.coerceAtLeast(0))

    /** Atomically checks delivery id, records it and appends the event. */
    @Synchronized
    fun recordDelivery(roomId: String, deliveryId: String, event: GitEventRecord? = null): Boolean {
        if (roomId.isBlank() || deliveryId.isBlank()) return false
        val currentTime = now()
        val store = prune(load(), currentTime)
        val key = deliveryKey(roomId, deliveryId)
        val eventKey = event?.dedupeKey?.takeIf { it.isNotBlank() }?.let { dedupeKey(roomId, it) }
        if (key in store.processedDeliveryIds || (eventKey != null && eventKey in store.processedDeliveryIds)) return false
        val ids = store.processedDeliveryIds.toMutableMap().apply {
            this[key] = currentTime
            eventKey?.let { this[it] = currentTime }
        }
        val boundedIds = ids.entries.sortedByDescending { it.value }.take(maxDeliveryIds).associate { it.key to it.value }
        val events = if (event == null) store.events else retainEvents(store.events + event)
        save(store.copy(events = events, processedDeliveryIds = boundedIds))
        return true
    }

    /** Atomically commits a polling batch before it is dispatched to the Team Channel. */
    @Synchronized
    @Suppress("CyclomaticComplexMethod")
    fun recordPollingBatch(
        expectedBinding: RoomGitBinding,
        state: GitPollingState,
        snapshots: List<GitResourceSnapshot>,
        events: List<GitEventRecord>,
    ): List<GitEventRecord>? {
        val currentTime = now()
        val store = prune(load(), currentTime)
        val bindingIndex = store.bindings.indexOfFirst { it.roomId == expectedBinding.roomId }
        val binding = store.bindings.getOrNull(bindingIndex)
        if (binding == null || !binding.matches(expectedBinding) || binding.ingestionMode != GitIngestionMode.POLLING) return null
        val ids = store.processedDeliveryIds.toMutableMap()
        val accepted = mutableListOf<GitEventRecord>()
        for (event in events) {
            val delivery = deliveryKey(expectedBinding.roomId, event.deliveryId)
            val semantic = event.dedupeKey.takeIf { it.isNotBlank() }?.let { dedupeKey(expectedBinding.roomId, it) }
            if (delivery in ids || (semantic != null && semantic in ids)) continue
            ids[delivery] = currentTime
            semantic?.let { ids[it] = currentTime }
            accepted += event.copy(delivered = false, source = GitEventSource.POLLING)
        }
        val boundedIds = ids.entries.sortedByDescending { it.value }.take(maxDeliveryIds).associate { it.key to it.value }
        val existingEvents = retainEvents(store.events + accepted)
        val bindingUpdated = binding.copy(
            lastDeliveryAt = if (accepted.isEmpty()) binding.lastDeliveryAt else currentTime,
            status = GitBindingStatus.ACTIVE,
            updatedAt = if (accepted.isEmpty() && binding.status == GitBindingStatus.ACTIVE) {
                binding.updatedAt
            } else {
                maxOf(binding.updatedAt, currentTime)
            },
        )
        save(
            store.copy(
                bindings = store.bindings.toMutableList().also { it[bindingIndex] = bindingUpdated },
                events = existingEvents,
                processedDeliveryIds = boundedIds,
                pollingStates = store.pollingStates + (expectedBinding.roomId to state),
                resourceSnapshots = store.resourceSnapshots + (expectedBinding.roomId to snapshots),
            )
        )
        return accepted
    }

    @Synchronized
    fun recordPollingFailure(
        expectedBinding: RoomGitBinding,
        state: GitPollingState,
        status: GitBindingStatus,
    ): Boolean {
        val store = load()
        val bindingIndex = store.bindings.indexOfFirst { it.roomId == expectedBinding.roomId }
        val binding = store.bindings.getOrNull(bindingIndex)
        if (binding == null || !binding.matches(expectedBinding) || binding.ingestionMode != GitIngestionMode.POLLING) return false
        val updatedBinding = if (binding.status == status) binding else binding.copy(status = status, updatedAt = now())
        save(
            store.copy(
                bindings = store.bindings.toMutableList().also { it[bindingIndex] = updatedBinding },
                pollingStates = store.pollingStates + (expectedBinding.roomId to state),
            )
        )
        return true
    }

    @Synchronized
    fun pendingEvents(roomId: String? = null): List<GitEventRecord> = load().events.filter {
        !it.delivered && (roomId == null || it.roomId == roomId)
    }

    @Synchronized
    fun markEventDelivered(roomId: String, deliveryId: String): Boolean {
        val store = load()
        var changed = false
        val events = store.events.map { event ->
            if (event.roomId == roomId && event.deliveryId == deliveryId && !event.delivered) {
                changed = true
                event.copy(delivered = true)
            } else event
        }
        if (changed) save(store.copy(events = events))
        return changed
    }

    @Synchronized
    fun hasProcessedDelivery(roomId: String, deliveryId: String): Boolean =
        deliveryKey(roomId, deliveryId) in prune(load(), now()).processedDeliveryIds

    @Synchronized
    fun clearEvents(roomId: String) {
        val store = load()
        val prefix = "$roomId\u0000"
        save(
            store.copy(
                events = store.events.filterNot { it.roomId == roomId },
                processedDeliveryIds = store.processedDeliveryIds.filterKeys { !it.startsWith(prefix) },
            )
        )
    }

    private fun deliveryKey(roomId: String, deliveryId: String): String = "$roomId\u0000$deliveryId"

    private fun dedupeKey(roomId: String, semanticKey: String): String = "$roomId\u0000dedupe\u0000$semanticKey"

    /** Never evict an undelivered event; only completed history is bounded. */
    private fun retainEvents(events: List<GitEventRecord>): List<GitEventRecord> {
        val pending = events.filterNot { it.delivered }
        val delivered = events.filter { it.delivered }
            .takeLast((MAX_STORED_EVENTS - pending.size).coerceAtLeast(0))
        return delivered + pending
    }

    private fun RoomGitBinding.matches(expected: RoomGitBinding): Boolean =
        roomId == expected.roomId && owner == expected.owner && repo == expected.repo &&
            tokenEncrypted == expected.tokenEncrypted && updatedAt == expected.updatedAt &&
            status == expected.status && ingestionMode == expected.ingestionMode &&
            hookId == expected.hookId && webhookUrl == expected.webhookUrl &&
            webhookSecretEncrypted == expected.webhookSecretEncrypted

    private fun prune(store: GitIntegrationStoreData, currentTime: Long): GitIntegrationStoreData {
        val cutoff = currentTime - deliveryTtlMs
        val ids = store.processedDeliveryIds.filterValues { it >= cutoff }
            .entries.sortedByDescending { it.value }.take(maxDeliveryIds).associate { it.key to it.value }
        return if (ids == store.processedDeliveryIds) store else store.copy(processedDeliveryIds = ids)
    }

    private fun load(): GitIntegrationStoreData = if (!storeFile.isFile) {
        GitIntegrationStoreData()
    } else {
        runCatching { json.decodeFromString<GitIntegrationStoreData>(storeFile.readText()) }
            .getOrElse { error -> logger.warn("Unable to load Git integration store: {}", error.message); GitIntegrationStoreData() }
    }

    private fun save(store: GitIntegrationStoreData) {
        File(baseDir).mkdirs()
        val tmp = File(baseDir, "${storeFile.name}.tmp")
        tmp.writeText(json.encodeToString(store))
        try {
            Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
