package com.silk.backend.todos

import com.silk.backend.database.UserTodoItemDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class UserTodoFilePayload(
    val userId: String,
    val items: List<UserTodoItemDto> = emptyList()
)

internal data class ResolvedTodoUpdate(
    val done: Boolean,
    val actionType: String?,
    val actionDetail: String?,
    val reminderId: Long?,
    val taskKind: String,
    val repeatRule: String?,
    val repeatAnchor: String?,
    val templateId: String?,
    val lifecycleState: String,
    val closedAt: Long?,
    val dateBucket: String?,
)

/**
 * 按用户持久化待办（chat_history/user_todos/{userId}.json）
 */
object UserTodoStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val locks = ConcurrentHashMap<String, Any>()

    private fun baseDirs(): List<File> {
        val overrideDir = System.getProperty("silk.userTodoBaseDir")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
        if (overrideDir != null) {
            return listOf(overrideDir)
        }
        return listOf(
            File("chat_history/user_todos"),
            File("backend/chat_history/user_todos"),
            File("../chat_history/user_todos")
        ).distinct()
    }

    private fun fileFor(userId: String): File {
        val safe = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val name = "$safe.json"
        val candidates = baseDirs()
        val existing = candidates
            .map { File(it, name) }
            .firstOrNull { it.isFile }
        if (existing != null) return existing
        val existingDir = candidates.firstOrNull { it.isDirectory }
        val targetDir = existingDir ?: candidates.first()
        targetDir.mkdirs()
        return File(targetDir, name)
    }

    fun load(userId: String): List<UserTodoItemDto> {
        val f = fileFor(userId)
        if (!f.exists()) return emptyList()
        return try {
            val text = f.readText()
            json.decodeFromString<UserTodoFilePayload>(text).items
        } catch (e: SerializationException) {
            println("⚠️ [UserTodoStore] 读取失败 user=${userId.take(8)}… : ${e.message}")
            emptyList()
        } catch (e: IllegalArgumentException) {
            println("⚠️ [UserTodoStore] 读取失败 user=${userId.take(8)}… : ${e.message}")
            emptyList()
        } catch (e: IOException) {
            println("⚠️ [UserTodoStore] 读取失败 user=${userId.take(8)}… : ${e.message}")
            emptyList()
        } catch (e: SecurityException) {
            println("⚠️ [UserTodoStore] 读取失败 user=${userId.take(8)}… : ${e.message}")
            emptyList()
        }
    }

    fun save(userId: String, items: List<UserTodoItemDto>) {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val f = fileFor(userId)
            f.parentFile?.mkdirs()
            val payload = UserTodoFilePayload(userId = userId, items = items)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json.encodeToString(payload))
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    /** 用新列表完全替换该用户待办（用于 LLM 去冗合并后写回）。 */
    fun replaceAll(userId: String, items: List<UserTodoItemDto>) {
        val sorted = items.sortedByDescending { it.updatedAt }
        save(userId, sorted)
    }

    /**
     * 用本次抽取结果做生命周期合并：
     * - 长期模板：按逻辑键 upsert
     * - 短期实例：active 更新；done/cancelled 仅满足回流门槛才重开
     * - 刷新后按模板规则实例化今日任务（幂等）
     */
    fun replaceUndoneWithExtracted(userId: String, incoming: List<ExtractedTodoDraft>) {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val existing = load(userId).toMutableList()
            val now = System.currentTimeMillis()
            for (draft in incoming) {
                val t = draft.title.trim()
                if (t.isEmpty() || t.length > 500) continue
                val at = draft.actionType?.trim()?.lowercase()?.ifBlank { null }
                val ad = draft.actionDetail?.trim()?.ifBlank { null }
                val kind = draft.taskKind.trim().ifBlank { "short_term_instance" }
                val evidenceAt = if (draft.evidenceAt > 0L) draft.evidenceAt else now
                if (kind == "long_term_template") {
                    upsertLongTemplate(existing, draft, t, at, ad, now)
                } else {
                    mergeShortInstanceByState(existing, draft, t, at, ad, evidenceAt, now)
                }
            }
            instantiateRecurringTemplates(existing, now)
            save(userId, existing.sortedByDescending { it.updatedAt })
        }
    }

    private fun upsertLongTemplate(
        existing: MutableList<UserTodoItemDto>,
        draft: ExtractedTodoDraft,
        title: String,
        actionType: String?,
        actionDetail: String?,
        now: Long
    ) {
        val lk = logicalDedupKey(title, actionType, actionDetail, "long_term_template")
        val idx = existing.indexOfFirst {
            it.taskKind == "long_term_template" &&
                logicalDedupKey(it.title, it.actionType, it.actionDetail, "long_term_template") == lk
        }
        val repeatRule = draft.repeatRule?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null }
        val repeatAnchor = draft.repeatAnchor?.trim()?.ifBlank { null }
        if (idx >= 0) {
            val cur = existing[idx]
            existing[idx] = cur.copy(
                title = title,
                sourceGroupId = draft.sourceGroupId?.ifBlank { cur.sourceGroupId } ?: cur.sourceGroupId,
                sourceGroupName = draft.sourceGroupName?.ifBlank { cur.sourceGroupName } ?: cur.sourceGroupName,
                actionType = actionType ?: cur.actionType,
                actionDetail = actionDetail ?: cur.actionDetail,
                taskKind = "long_term_template",
                repeatRule = repeatRule ?: cur.repeatRule,
                repeatAnchor = repeatAnchor ?: cur.repeatAnchor,
                lifecycleState = if (cur.lifecycleState == "cancelled") "cancelled" else "active",
                done = false,
                updatedAt = now
            )
            return
        }
        existing.add(
            UserTodoItemDto(
                id = UUID.randomUUID().toString(),
                title = title,
                sourceGroupId = draft.sourceGroupId?.ifBlank { null },
                sourceGroupName = draft.sourceGroupName?.ifBlank { null },
                actionType = actionType,
                actionDetail = actionDetail,
                createdAt = now,
                updatedAt = now,
                done = false,
                taskKind = "long_term_template",
                repeatRule = repeatRule,
                repeatAnchor = repeatAnchor,
                lifecycleState = "active",
                explicitIntent = draft.explicitIntent
            )
        )
    }

    private fun mergeShortInstanceByState(
        existing: MutableList<UserTodoItemDto>,
        draft: ExtractedTodoDraft,
        title: String,
        actionType: String?,
        actionDetail: String?,
        evidenceAt: Long,
        now: Long
    ) {
        val lk = logicalDedupKey(title, actionType, actionDetail, "short_term_instance")
        val idx = findLatestShortInstanceIndex(existing, lk)
        if (idx == null) {
            existing.add(
                createShortInstanceItem(
                    draft = draft,
                    title = title,
                    actionType = actionType,
                    actionDetail = actionDetail,
                    evidenceAt = evidenceAt,
                    now = now,
                )
            )
            return
        }

        val cur = existing[idx]
        val state = normalizeLifecycleState(cur)
        val closeTs = cur.closedAt ?: cur.updatedAt
        val mergedEvidenceAt = maxOf(cur.lastEvidenceAt ?: 0L, evidenceAt)

        if (state == "active") {
            existing[idx] = mergeIntoActiveShortInstance(
                current = cur,
                draft = draft,
                title = title,
                actionType = actionType,
                actionDetail = actionDetail,
                mergedEvidenceAt = mergedEvidenceAt,
                now = now,
            )
            return
        }
        if (!canReopenShortInstance(state, evidenceAt, closeTs, draft.explicitIntent)) return

        existing[idx] = reopenShortInstance(
            current = cur,
            draft = draft,
            title = title,
            actionType = actionType,
            actionDetail = actionDetail,
            mergedEvidenceAt = mergedEvidenceAt,
            now = now,
        )
    }

    private fun findLatestShortInstanceIndex(
        existing: List<UserTodoItemDto>,
        logicalKey: String,
    ): Int? {
        return existing.indices
            .filter { existing[it].taskKind != "long_term_template" }
            .sortedByDescending { existing[it].updatedAt }
            .firstOrNull {
                logicalDedupKey(
                    existing[it].title,
                    existing[it].actionType,
                    existing[it].actionDetail,
                    "short_term_instance",
                ) == logicalKey
            }
    }

    private fun createShortInstanceItem(
        draft: ExtractedTodoDraft,
        title: String,
        actionType: String?,
        actionDetail: String?,
        evidenceAt: Long,
        now: Long,
    ): UserTodoItemDto {
        return UserTodoItemDto(
            id = UUID.randomUUID().toString(),
            title = title,
            sourceGroupId = draft.sourceGroupId?.ifBlank { null },
            sourceGroupName = draft.sourceGroupName?.ifBlank { null },
            actionType = actionType,
            actionDetail = actionDetail,
            createdAt = now,
            updatedAt = now,
            done = false,
            taskKind = "short_term_instance",
            lifecycleState = "active",
            lastEvidenceAt = evidenceAt,
            explicitIntent = draft.explicitIntent,
        )
    }

    private fun normalizeLifecycleState(item: UserTodoItemDto): String {
        return item.lifecycleState.trim().lowercase(Locale.getDefault()).ifBlank {
            if (item.done) "done" else "active"
        }
    }

    private fun canReopenShortInstance(
        state: String,
        evidenceAt: Long,
        closeTs: Long,
        explicitIntent: Boolean,
    ): Boolean {
        return when (state) {
            "done", "deferred" -> evidenceAt > closeTs
            "cancelled" -> explicitIntent && evidenceAt > closeTs
            else -> false
        }
    }

    private fun mergeIntoActiveShortInstance(
        current: UserTodoItemDto,
        draft: ExtractedTodoDraft,
        title: String,
        actionType: String?,
        actionDetail: String?,
        mergedEvidenceAt: Long,
        now: Long,
    ): UserTodoItemDto {
        return current.copy(
            title = title,
            sourceGroupId = draft.sourceGroupId?.ifBlank { current.sourceGroupId } ?: current.sourceGroupId,
            sourceGroupName = draft.sourceGroupName?.ifBlank { current.sourceGroupName } ?: current.sourceGroupName,
            actionType = actionType ?: current.actionType,
            actionDetail = actionDetail ?: current.actionDetail,
            done = false,
            taskKind = "short_term_instance",
            lifecycleState = "active",
            lastEvidenceAt = mergedEvidenceAt,
            explicitIntent = draft.explicitIntent || current.explicitIntent,
            updatedAt = now,
        )
    }

    private fun reopenShortInstance(
        current: UserTodoItemDto,
        draft: ExtractedTodoDraft,
        title: String,
        actionType: String?,
        actionDetail: String?,
        mergedEvidenceAt: Long,
        now: Long,
    ): UserTodoItemDto {
        return current.copy(
            title = title,
            sourceGroupId = draft.sourceGroupId?.ifBlank { current.sourceGroupId } ?: current.sourceGroupId,
            sourceGroupName = draft.sourceGroupName?.ifBlank { current.sourceGroupName } ?: current.sourceGroupName,
            actionType = actionType ?: current.actionType,
            actionDetail = actionDetail ?: current.actionDetail,
            done = false,
            lifecycleState = "active",
            closedAt = null,
            lastEvidenceAt = mergedEvidenceAt,
            explicitIntent = draft.explicitIntent,
            reopenCount = current.reopenCount + 1,
            updatedAt = now,
        )
    }

    private fun instantiateRecurringTemplates(existing: MutableList<UserTodoItemDto>, now: Long) {
        instantiateRecurringTemplatesForUserTodo(existing, now)
    }

    fun mergeExtracted(userId: String, incoming: List<ExtractedTodoDraft>) {
        if (incoming.isEmpty()) return
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val existing = load(userId).toMutableList()
            val seenKeys = existing.map { logicalDedupKey(it.title, it.actionType, it.actionDetail) }.toMutableSet()
            val now = System.currentTimeMillis()
            for (draft in incoming) {
                buildMergedExtractedItem(draft, seenKeys, now)?.let { (item, logicalKey) ->
                    existing.add(item)
                    seenKeys.add(logicalKey)
                }
            }
            existing.sortByDescending { it.updatedAt }
            save(userId, existing)
        }
    }

    private fun buildMergedExtractedItem(
        draft: ExtractedTodoDraft,
        seenKeys: Set<String>,
        now: Long,
    ): Pair<UserTodoItemDto, String>? {
        val title = draft.title.trim()
        if (title.isEmpty() || title.length > 500) return null
        val actionType = draft.actionType?.trim()?.lowercase()?.ifBlank { null }
        val actionDetail = draft.actionDetail?.trim()?.ifBlank { null }
        val logicalKey = logicalDedupKey(title, actionType, actionDetail)
        if (logicalKey in seenKeys) return null
        return UserTodoItemDto(
            id = UUID.randomUUID().toString(),
            title = title,
            sourceGroupId = draft.sourceGroupId?.ifBlank { null },
            sourceGroupName = draft.sourceGroupName?.ifBlank { null },
            actionType = actionType,
            actionDetail = actionDetail,
            createdAt = now,
            updatedAt = now,
            done = false
        ) to logicalKey
    }

    fun setItemDone(userId: String, itemId: String, done: Boolean): Boolean {
        return updateItem(userId, itemId, done = done)
    }

    /** 部分字段更新待办项（title/actionType/actionDetail/done/executedAt/reminderId） */
    fun updateItem(
        userId: String,
        itemId: String,
        done: Boolean? = null,
        title: String? = null,
        actionType: String? = null,
        actionDetail: String? = null,
        executedAt: Long? = null,
        reminderId: Long? = null,
        clearReminderId: Boolean = false,
        taskKind: String? = null,
        repeatRule: String? = null,
        repeatAnchor: String? = null,
        activeFrom: Long? = null,
        activeTo: Long? = null,
        templateId: String? = null,
        lifecycleState: String? = null,
        closedAt: Long? = null,
        lastEvidenceAt: Long? = null,
        explicitIntent: Boolean? = null,
        dateBucket: String? = null,
        reopenCount: Int? = null
    ): Boolean {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val items = load(userId).toMutableList()
            val idx = items.indexOfFirst { it.id == itemId }
            if (idx < 0) return false
            val cur = items[idx]
            val now = System.currentTimeMillis()
            val resolved = resolveTodoUpdate(
                current = cur,
                done = done,
                actionType = actionType,
                actionDetail = actionDetail,
                reminderId = reminderId,
                clearReminderId = clearReminderId,
                taskKind = taskKind,
                repeatRule = repeatRule,
                repeatAnchor = repeatAnchor,
                templateId = templateId,
                lifecycleState = lifecycleState,
                closedAt = closedAt,
                dateBucket = dateBucket,
                now = now,
            )
            items[idx] = cur.copy(
                done = resolved.done,
                title = title ?: cur.title,
                actionType = resolved.actionType,
                actionDetail = resolved.actionDetail,
                executedAt = executedAt ?: cur.executedAt,
                reminderId = resolved.reminderId,
                taskKind = resolved.taskKind,
                repeatRule = resolved.repeatRule,
                repeatAnchor = resolved.repeatAnchor,
                activeFrom = activeFrom ?: cur.activeFrom,
                activeTo = activeTo ?: cur.activeTo,
                templateId = resolved.templateId,
                lifecycleState = resolved.lifecycleState,
                closedAt = resolved.closedAt,
                lastEvidenceAt = lastEvidenceAt ?: cur.lastEvidenceAt,
                explicitIntent = explicitIntent ?: cur.explicitIntent,
                dateBucket = resolved.dateBucket,
                reopenCount = reopenCount ?: cur.reopenCount,
                updatedAt = now
            )
            save(userId, items)
            return true
        }
    }

    private fun resolveTodoUpdate(
        current: UserTodoItemDto,
        done: Boolean?,
        actionType: String?,
        actionDetail: String?,
        reminderId: Long?,
        clearReminderId: Boolean,
        taskKind: String?,
        repeatRule: String?,
        repeatAnchor: String?,
        templateId: String?,
        lifecycleState: String?,
        closedAt: Long?,
        dateBucket: String?,
        now: Long,
    ): ResolvedTodoUpdate {
        return resolveUserTodoUpdate(
            current = current,
            done = done,
            actionType = actionType,
            actionDetail = actionDetail,
            reminderId = reminderId,
            clearReminderId = clearReminderId,
            taskKind = taskKind,
            repeatRule = repeatRule,
            repeatAnchor = repeatAnchor,
            templateId = templateId,
            lifecycleState = lifecycleState,
            closedAt = closedAt,
            dateBucket = dateBucket,
            now = now,
        )
    }

    /** 删除指定待办项 */
    fun deleteItem(userId: String, itemId: String): Boolean {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val items = load(userId).toMutableList()
            val removed = items.removeAll { it.id == itemId }
            if (!removed) return false
            save(userId, items)
            return true
        }
    }

    /**
     * 合并、写库前按「一事一条」去重：同闹钟/日程且时间一致仅一条；标题相似（去掉套话后）合并。
     */
    fun dedupeByLogicalKeyInPlace(userId: String) {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val items = load(userId)
            if (items.isEmpty()) return@synchronized
            val beforeIds = items.map { it.id }.toHashSet()
            var merged = mergeItemsByLogicalKey(items)
            merged = mergeContainedUndoneTitles(merged)
            val afterIds = merged.map { it.id }.toHashSet()
            if (beforeIds == afterIds && merged.size == items.size) return@synchronized
            save(userId, merged.sortedByDescending { it.updatedAt })
            println("✅ [UserTodoStore] 本地去重/合并 ${items.size} → ${merged.size}")
        }
    }

    internal fun logicalDedupKey(title: String, actionType: String?, actionDetail: String?, taskKind: String? = null): String {
        return userTodoLogicalDedupKey(title, actionType, actionDetail, taskKind)
    }

    /** 若归一化标题互为包含则并成一条（解决一事多条）；已完成项也参与合并以清理历史。 */
    private fun mergeContainedUndoneTitles(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
        return mergeContainedUserTodos(items)
    }

    private fun mergeItemsByLogicalKey(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
        return mergeUserTodoItemsByLogicalKey(items)
    }
}

data class ExtractedTodoDraft(
    val title: String,
    val sourceGroupId: String? = null,
    val sourceGroupName: String? = null,
    val actionType: String? = null,
    val actionDetail: String? = null,
    val taskKind: String = "short_term_instance",
    val repeatRule: String? = null,
    val repeatAnchor: String? = null,
    val evidenceAt: Long = 0L,
    val explicitIntent: Boolean = false
)
