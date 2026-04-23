package com.silk.backend.workflow

import com.silk.backend.models.Workflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class WorkflowStore(
    val workflows: MutableList<Workflow> = mutableListOf()
)

class WorkflowManager(
    private val baseDir: String =
        System.getProperty("silk.workflowDir")?.trim()?.takeIf { it.isNotEmpty() } ?: "workflows"
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(WorkflowManager::class.java)
    private val storeFile get() = File("$baseDir/workflow_store.json")

    init {
        File(baseDir).mkdirs()
    }

    @Synchronized
    private fun load(): WorkflowStore {
        return if (storeFile.exists()) {
            try {
                json.decodeFromString(storeFile.readText())
            } catch (e: Exception) {
                logger.error("Failed to load workflow store: {}", e.message)
                WorkflowStore()
            }
        } else WorkflowStore()
    }

    @Synchronized
    private fun save(store: WorkflowStore) {
        File(baseDir).mkdirs()
        val tmp = File("${storeFile.path}.tmp")
        tmp.writeText(json.encodeToString(store))
        Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun listWorkflows(userId: String): List<Workflow> =
        load().workflows.filter { it.ownerId == userId }

    @Synchronized
    fun createWorkflow(name: String, description: String, userId: String, groupId: String): Workflow {
        val store = load()
        val workflow = Workflow(
            id = "wf_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = name,
            description = description,
            ownerId = userId,
            groupId = groupId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        store.workflows.add(workflow)
        save(store)
        logger.info("Created workflow: {} for user {}, groupId={}", workflow.id, userId, groupId)
        return workflow
    }

    fun getWorkflow(workflowId: String, userId: String): Workflow? =
        load().workflows.find { it.id == workflowId && it.ownerId == userId }

    fun getWorkflowByGroupId(groupId: String): Workflow? =
        load().workflows.find { it.groupId == groupId }

    @Synchronized
    fun deleteWorkflow(workflowId: String, userId: String): Boolean {
        val store = load()
        val removed = store.workflows.removeAll { it.id == workflowId && it.ownerId == userId }
        if (removed) {
            save(store)
            logger.info("Deleted workflow: {}", workflowId)
        }
        return removed
    }
}
