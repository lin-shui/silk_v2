package com.silk.backend.todos

import com.silk.backend.database.UserTodoItemDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class UserTodoFilePayload(
    val userId: String,
    val items: List<UserTodoItemDto> = emptyList()
)

/**
 * 按用户持久化待办（chat_history/user_todos/{userId}.json）
 */
object UserTodoStore {
    private const val BASE_DIR = "chat_history/user_todos"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val locks = ConcurrentHashMap<String, Any>()

    private fun fileFor(userId: String): File {
        val safe = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(BASE_DIR, "$safe.json")
    }

    fun load(userId: String): List<UserTodoItemDto> {
        val f = fileFor(userId)
        if (!f.exists()) return emptyList()
        return try {
            val text = f.readText()
            json.decodeFromString<UserTodoFilePayload>(text).items
        } catch (e: Exception) {
            println("⚠️ [UserTodoStore] 读取失败 user=${userId.take(8)}… : ${e.message}")
            emptyList()
        }
    }

    fun save(userId: String, items: List<UserTodoItemDto>) {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            File(BASE_DIR).mkdirs()
            val f = fileFor(userId)
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
     * 用本次从聊天抽取的结果**替换**所有未完成待办；已勾选完成的条目保留（历史完成态）。
     * 避免仅 merge 追加导致「一事多条」永远堆在列表里。
     */
    fun replaceUndoneWithExtracted(userId: String, incoming: List<ExtractedTodoDraft>) {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val existing = load(userId)
            val keptDone = existing.filter { it.done }
            val now = System.currentTimeMillis()
            val seenKeys = mutableSetOf<String>()
            val newUndone = mutableListOf<UserTodoItemDto>()
            for (draft in incoming) {
                val t = draft.title.trim()
                if (t.isEmpty() || t.length > 500) continue
                val at = draft.actionType?.trim()?.lowercase()?.ifBlank { null }
                val ad = draft.actionDetail?.trim()?.ifBlank { null }
                val lk = logicalDedupKey(t, at, ad)
                if (lk in seenKeys) continue
                seenKeys.add(lk)
                newUndone.add(
                    UserTodoItemDto(
                        id = UUID.randomUUID().toString(),
                        title = t,
                        sourceGroupId = draft.sourceGroupId?.ifBlank { null },
                        sourceGroupName = draft.sourceGroupName?.ifBlank { null },
                        actionType = at,
                        actionDetail = ad,
                        createdAt = now,
                        updatedAt = now,
                        done = false
                    )
                )
            }
            save(userId, (keptDone + newUndone).sortedByDescending { it.updatedAt })
        }
    }

    fun mergeExtracted(userId: String, incoming: List<ExtractedTodoDraft>) {
        if (incoming.isEmpty()) return
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val existing = load(userId).toMutableList()
            val seenKeys = existing.map { logicalDedupKey(it.title, it.actionType, it.actionDetail) }.toMutableSet()
            val now = System.currentTimeMillis()
            for (draft in incoming) {
                val t = draft.title.trim()
                if (t.isEmpty() || t.length > 500) continue
                val at = draft.actionType?.trim()?.lowercase()?.ifBlank { null }
                val ad = draft.actionDetail?.trim()?.ifBlank { null }
                val lk = logicalDedupKey(t, at, ad)
                if (lk in seenKeys) continue
                existing.add(
                    UserTodoItemDto(
                        id = UUID.randomUUID().toString(),
                        title = t,
                        sourceGroupId = draft.sourceGroupId?.ifBlank { null },
                        sourceGroupName = draft.sourceGroupName?.ifBlank { null },
                        actionType = at,
                        actionDetail = ad,
                        createdAt = now,
                        updatedAt = now,
                        done = false
                    )
                )
                seenKeys.add(lk)
            }
            existing.sortByDescending { it.updatedAt }
            save(userId, existing)
        }
    }

    fun setItemDone(userId: String, itemId: String, done: Boolean): Boolean {
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val items = load(userId).toMutableList()
            val idx = items.indexOfFirst { it.id == itemId }
            if (idx < 0) return false
            val cur = items[idx]
            val now = System.currentTimeMillis()
            items[idx] = cur.copy(done = done, updatedAt = now)
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

    internal fun logicalDedupKey(title: String, actionType: String?, actionDetail: String?): String {
        val at = actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null }
        val adNorm = normalizeActionDetailForKey(at, actionDetail)
        if ((at == "alarm" || at == "calendar") && adNorm != null) {
            return "$at:$adNorm"
        }
        return "t:${normKey(title)}"
    }

    private fun normKey(title: String): String {
        var s = title.trim().lowercase(Locale.getDefault())
        s = s.replace(Regex("\\s+"), " ")
        s = s.replace(Regex("[，。、；:!？!．·…~～\"'\"''（）()\\[\\]【】|]+"), "")
        s = s.replace(
            Regex("^(设置闹钟|设闹钟|闹钟|提醒我|记得|记得要|请|帮忙|麻烦)\\s*[:：]?\\s*"),
            ""
        )
        return s.trim().ifEmpty { title.trim().lowercase(Locale.getDefault()) }
    }

    private fun normalizeActionDetailForKey(actionType: String?, detail: String?): String? {
        if (detail == null) return null
        val t = detail.trim().lowercase(Locale.getDefault()).ifBlank { return null }
        val hm = Regex("^(\\d{1,2})\\s*[:：]\\s*(\\d{2})$").find(t)
        if (hm != null) {
            val h = hm.groupValues[1].toIntOrNull() ?: return normKey(t)
            val m = hm.groupValues[2].toIntOrNull() ?: return normKey(t)
            if (h in 0..23 && m in 0..59) return "%02d:%02d".format(h, m)
        }
        return normKey(t)
    }

    /** 未完成且非闹钟/日程结构化项：若归一化标题互为包含则并成一条（解决模型拆成多条近义句）。 */
    private fun mergeContainedUndoneTitles(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
        val done = items.filter { it.done }
        var undone = items.filter { !it.done }.toMutableList()
        if (undone.size < 2) return items
        val now = System.currentTimeMillis()
        var changed = true
        while (changed && undone.size > 1) {
            changed = false
            pair@ for (i in 0 until undone.size) {
                for (j in i + 1 until undone.size) {
                    val a = undone[i]
                    val b = undone[j]
                    val merged = tryMergeByContainedNormTitle(a, b, now) ?: continue
                    undone.removeAt(j)
                    undone.removeAt(i)
                    undone.add(merged)
                    changed = true
                    break@pair
                }
            }
        }
        return done + undone
    }

    private fun tryMergeByContainedNormTitle(
        a: UserTodoItemDto,
        b: UserTodoItemDto,
        now: Long
    ): UserTodoItemDto? {
        if (a.done || b.done) return null
        val ta = a.actionType?.trim()?.lowercase(Locale.getDefault()) ?: ""
        val tb = b.actionType?.trim()?.lowercase(Locale.getDefault()) ?: ""
        if (ta == "alarm" || ta == "calendar" || tb == "alarm" || tb == "calendar") {
            if (logicalDedupKey(a.title, a.actionType, a.actionDetail) !=
                logicalDedupKey(b.title, b.actionType, b.actionDetail)
            ) {
                return null
            }
        }
        val na = normKey(a.title)
        val nb = normKey(b.title)
        if (na.length < 4 || nb.length < 4) return null
        val contained = (na.contains(nb) || nb.contains(na))
        if (!contained) return null
        val newer = if (a.updatedAt >= b.updatedAt) a else b
        val other = if (newer.id == a.id) b else a
        val shortTitle = if (a.title.trim().length <= b.title.trim().length) a.title.trim() else b.title.trim()
        return newer.copy(
            title = shortTitle.take(500),
            actionType = (newer.actionType ?: other.actionType)
                ?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null },
            actionDetail = listOfNotNull(a.actionDetail?.trim(), b.actionDetail?.trim())
                .filter { it.isNotEmpty() }
                .maxByOrNull { it.length }
                ?.ifBlank { null },
            done = false,
            updatedAt = now
        )
    }

    private fun mergeItemsByLogicalKey(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
        val byKey = items.groupBy { logicalDedupKey(it.title, it.actionType, it.actionDetail) }
        val now = System.currentTimeMillis()
        return byKey.values.map { g ->
            val sorted = g.sortedByDescending { it.updatedAt }
            val newest = sorted.first()
            val mergedRow = g.size > 1
            val anyDone = g.any { it.done }
            val bestDetail = g.mapNotNull { it.actionDetail?.trim()?.takeIf { x -> x.isNotEmpty() } }
                .maxByOrNull { it.length }
            val preferredType = g.mapNotNull { it.actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null } }
                .firstOrNull { it == "alarm" || it == "calendar" }
                ?: newest.actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null }
            val shortTitle = g.map { it.title.trim() }.filter { it.isNotEmpty() }
                .minByOrNull { it.length } ?: newest.title
            newest.copy(
                title = shortTitle.take(500),
                actionType = preferredType?.ifBlank { null },
                actionDetail = (bestDetail ?: newest.actionDetail)?.trim()?.ifBlank { null },
                done = anyDone,
                updatedAt = if (mergedRow) now else newest.updatedAt
            )
        }
    }
}

data class ExtractedTodoDraft(
    val title: String,
    val sourceGroupId: String? = null,
    val sourceGroupName: String? = null,
    val actionType: String? = null,
    val actionDetail: String? = null
)
