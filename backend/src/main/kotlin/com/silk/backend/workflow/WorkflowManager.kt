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
    fun createWorkflow(name: String, description: String, userId: String, groupId: String): Workflow =
        createWorkflow(name, description, userId, groupId, "claude_code", "")

    @Synchronized
    fun createWorkflow(name: String, description: String, userId: String, groupId: String, agentType: String, taskFocus: String = ""): Workflow {
        val store = load()
        val workflow = Workflow(
            id = "wf_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = name,
            description = description,
            ownerId = userId,
            groupId = groupId,
            agentType = agentType,
            taskFocus = taskFocus,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        store.workflows.add(workflow)
        save(store)
        logger.info("Created workflow: {} for user {}, groupId={}, agentType={}", workflow.id, userId, groupId, agentType)
        return workflow
    }

    fun getWorkflow(workflowId: String, userId: String): Workflow? =
        load().workflows.find { it.id == workflowId && it.ownerId == userId }

    fun getWorkflowByGroupId(groupId: String): Workflow? =
        load().workflows.find { it.groupId == groupId }

    /**
     * 持久化用户为工作流设置的工作目录（创建时的 initialDir 或运行时的「更改」按钮）。
     * 后端重启后据此 seed CC state 的 workingDir。返回是否真的写盘了（无变化时跳过 I/O）。
     */
    @Synchronized
    fun updateWorkingDir(groupId: String, workingDir: String): Boolean {
        val store = load()
        val idx = store.workflows.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return false
        val old = store.workflows[idx]
        if (old.workingDir == workingDir) return false
        store.workflows[idx] = old.copy(
            workingDir = workingDir,
            updatedAt = System.currentTimeMillis(),
        )
        save(store)
        logger.info("Workflow {} workingDir 持久化: {}", old.id, workingDir)
        return true
    }

    /**
     * 持久化 bridge 上一次的 sessionId + sessionStarted。后端重启后据此发起 resume，
     * 让用户续上之前的对话历史（前提：bridge 端的 ~/.silk/cc_sessions.json 还有这个 session）。
     * 跳过无变化的写入，避免 prompt 高频持久化时的 I/O 抖动。
     */
    @Synchronized
    fun updateSessionState(groupId: String, sessionId: String, sessionStarted: Boolean): Boolean {
        val store = load()
        val idx = store.workflows.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return false
        val old = store.workflows[idx]
        if (old.sessionId == sessionId && old.sessionStarted == sessionStarted) return false
        store.workflows[idx] = old.copy(
            sessionId = sessionId,
            sessionStarted = sessionStarted,
            updatedAt = System.currentTimeMillis(),
        )
        save(store)
        return true
    }

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
