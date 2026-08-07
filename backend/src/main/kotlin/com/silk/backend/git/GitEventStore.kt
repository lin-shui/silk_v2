package com.silk.backend.git

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(GitEventStore::class.java)
    private val storeFile get() = File(baseDir, "git_integration_store.json")

    init { File(baseDir).mkdirs() }

    @Synchronized
    fun getBinding(roomId: String): RoomGitBinding? = load().bindings.firstOrNull { it.roomId == roomId }

    @Synchronized
    fun putBinding(binding: RoomGitBinding) {
        val store = load()
        save(store.copy(bindings = store.bindings.filterNot { it.roomId == binding.roomId } + binding))
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
        if (key in store.processedDeliveryIds) return false
        val ids = store.processedDeliveryIds + (key to currentTime)
        val boundedIds = ids.entries.sortedByDescending { it.value }.take(maxDeliveryIds).associate { it.key to it.value }
        val events = if (event == null) store.events else (store.events + event).takeLast(1_000)
        save(store.copy(events = events, processedDeliveryIds = boundedIds))
        return true
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

    @Synchronized
    fun listPollableBindings(): List<RoomGitBinding> =
        load().bindings.filter {
            it.mode == GitIntegrationMode.POLLING && it.status == GitBindingStatus.ACTIVE
        }

    /** Advance poll watermark. Cursor only moves forward; etag/timestamp always overwrite. */
    @Synchronized
    fun updatePollState(roomId: String, cursor: Long?, etag: String?, polledAt: Long): RoomGitBinding? {
        val store = load()
        val idx = store.bindings.indexOfFirst { it.roomId == roomId }
        if (idx < 0) return null
        val current = store.bindings[idx]
        val updated = current.copy(
            pollCursor = if (cursor != null && (current.pollCursor == null || cursor > current.pollCursor)) cursor
                         else current.pollCursor,
            issuesEtag = etag ?: current.issuesEtag,
            lastPolledAt = polledAt,
            updatedAt = maxOf(current.updatedAt, polledAt),
        )
        save(store.copy(bindings = store.bindings.toMutableList().also { it[idx] = updated }))
        return updated
    }

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
