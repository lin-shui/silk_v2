package com.silk.backend.database

import com.silk.backend.SilkAgent
import com.silk.shared.models.RoomKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
private data class WorkflowRoomKindMigrationStore(
    val workflows: List<WorkflowRoomKindMigrationRef> = emptyList(),
)

@Serializable
private data class WorkflowRoomKindMigrationRef(
    val groupId: String = "",
    val name: String = "",
)

@Serializable
private data class WorkspaceRoomKindMigrationStore(
    val workspaces: List<WorkspaceRoomKindMigrationRef> = emptyList(),
)

@Serializable
private data class WorkspaceRoomKindMigrationRef(
    val roomId: String = "",
)

/** Backfills explicit RoomKind values from authoritative legacy metadata. Safe to run on every startup. */
object RoomKindMigration {
    private val logger = LoggerFactory.getLogger(RoomKindMigration::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val validKinds = RoomKind.entries.map(RoomKind::name).toSet()

    fun run() {
        val workflowDir = System.getProperty("silk.workflowDir")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv("SILK_WORKFLOW_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${System.getProperty("user.home")}/.silk-data/workflows"

        val workflowRooms = readWorkflowRooms(File(workflowDir, "workflow_store.json"))
        val workflowRoomIds = workflowRooms.keys
        val workspaceRoomIds = readWorkspaceRoomIds(File(workflowDir, "workspace_store.json"))
        val explicitWorkflowRoomIds = workflowRoomIds + workspaceRoomIds

        transaction {
            explicitWorkflowRoomIds.forEach { roomId ->
                Groups.update({ Groups.id eq roomId }) {
                    it[roomKind] = RoomKind.WORKFLOW.name
                }
            }

            migrateWorkflowNames(workflowRooms)

            Groups.selectAll().forEach { row ->
                val groupId = row[Groups.id]
                val name = row[Groups.name]
                val storedKind = row[Groups.roomKind]
                if (storedKind !in validKinds) {
                    logger.warn("Unknown room_kind '{}' for room {}, resetting to CHAT", storedKind, groupId)
                    Groups.update({ Groups.id eq groupId }) {
                        it[roomKind] = RoomKind.CHAT.name
                    }
                }
                if (groupId !in explicitWorkflowRoomIds && name.startsWith("wf_")) {
                    logger.warn("Legacy workflow-like room has no workflow metadata: roomId={}, name={}", groupId, name)
                }
            }

            val silkCandidates = Groups
                .select { Groups.name like "[Silk]%" }
                .filter { it[Groups.roomKind] != RoomKind.WORKFLOW.name }
            silkCandidates.forEach { row ->
                val groupId = row[Groups.id]
                val memberIds = GroupMembers
                    .select { GroupMembers.groupId eq groupId }
                    .map { it[GroupMembers.userId] }
                if (memberIds.size == 2 && SilkAgent.AGENT_ID in memberIds) {
                    Groups.update({ Groups.id eq groupId }) {
                        it[roomKind] = RoomKind.SILK_PRIVATE.name
                    }
                }
            }
        }

        logger.info(
            "RoomKind migration complete: workflowMetadata={}, workspaceMetadata={}",
            workflowRoomIds.size,
            workspaceRoomIds.size,
        )
    }

    private fun readWorkflowRooms(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<WorkflowRoomKindMigrationStore>(file.readText()).workflows
                .mapNotNull { workflow ->
                    workflow.groupId.trim().takeIf(String::isNotEmpty)?.let { it to workflow.name.trim() }
                }
                .toMap()
        }.getOrElse {
            logger.warn("Unable to read RoomKind migration source {}: {}", file.path, it.message)
            emptyMap()
        }
    }

    private fun migrateWorkflowNames(workflowRooms: Map<String, String>) {
        val currentNames = Groups.selectAll().associate { row -> row[Groups.id] to row[Groups.name] }
        val migrationCandidates = workflowRooms.keys.filterTo(mutableSetOf()) { roomId ->
            currentNames[roomId]?.startsWith("wf_") == true
        }
        val reservedNames = currentNames
            .filterKeys { it !in migrationCandidates }
            .values
            .toMutableSet()

        workflowRooms.forEach { (roomId, workflowName) ->
            if (workflowName.isBlank() || roomId !in migrationCandidates) return@forEach
            val migratedName = uniqueName(workflowName, reservedNames)
            Groups.update({ Groups.id eq roomId }) {
                it[name] = migratedName
            }
            reservedNames += migratedName
        }
    }

    private fun uniqueName(baseName: String, reservedNames: Set<String>): String {
        if (baseName !in reservedNames) return baseName
        var suffix = 1
        while ("$baseName ($suffix)" in reservedNames) suffix += 1
        return "$baseName ($suffix)"
    }

    private fun readWorkspaceRoomIds(file: File): Set<String> = readStore(file) {
        json.decodeFromString<WorkspaceRoomKindMigrationStore>(it).workspaces
            .mapNotNull { workspace -> workspace.roomId.trim().takeIf(String::isNotEmpty) }
            .toSet()
    }

    private fun readStore(file: File, decode: (String) -> Set<String>): Set<String> {
        if (!file.exists()) return emptySet()
        return runCatching { decode(file.readText()) }
            .getOrElse {
                logger.warn("Unable to read RoomKind migration source {}: {}", file.path, it.message)
                emptySet()
            }
    }
}
