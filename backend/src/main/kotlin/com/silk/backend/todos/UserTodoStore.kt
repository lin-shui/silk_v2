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
@Suppress("TooGenericExceptionCaught")
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
            existing.add(createShortInstanceItem(draft, title, actionType, actionDetail, evidenceAt, now))
            return
        }

        val cur = existing[idx]
        val state = normalizeLifecycleState(cur)
        val closeTs = cur.closedAt ?: cur.updatedAt
        val mergedEvidenceAt = maxOf(cur.lastEvidenceAt ?: 0L, evidenceAt)

        if (state == "active") {
            existing[idx] = mergeIntoActiveShortInstance(cur, draft, title, actionType, actionDetail, mergedEvidenceAt, now)
            return
        }
        if (!canReopenShortInstance(state, evidenceAt, closeTs, draft.explicitIntent)) return
        existing[idx] = reopenShortInstance(cur, draft, title, actionType, actionDetail, mergedEvidenceAt, now)
    }

    private fun findLatestShortInstanceIndex(existing: List<UserTodoItemDto>, logicalKey: String): Int? {
        return existing.indices
            .filter { existing[it].taskKind != "long_term_template" }
            .sortedByDescending { existing[it].updatedAt }
            .firstOrNull {
                logicalDedupKey(
                    existing[it].title,
                    existing[it].actionType,
                    existing[it].actionDetail,
                    "short_term_instance"
                ) == logicalKey
            }
    }

    private fun createShortInstanceItem(
        draft: ExtractedTodoDraft,
        title: String,
        actionType: String?,
        actionDetail: String?,
        evidenceAt: Long,
        now: Long
    ): UserTodoItemDto = UserTodoItemDto(
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
        explicitIntent = draft.explicitIntent
    )

    private fun normalizeLifecycleState(item: UserTodoItemDto): String =
        item.lifecycleState.trim().lowercase(Locale.getDefault()).ifBlank {
            if (item.done) "done" else "active"
        }

    private fun canReopenShortInstance(
        state: String,
        evidenceAt: Long,
        closeTs: Long,
        explicitIntent: Boolean
    ): Boolean = when (state) {
        "done", "deferred" -> evidenceAt > closeTs
        "cancelled" -> explicitIntent && evidenceAt > closeTs
        else -> false
    }

    private fun mergeIntoActiveShortInstance(
        cur: UserTodoItemDto,
        draft: ExtractedTodoDraft,
        title: String,
        actionType: String?,
        actionDetail: String?,
        mergedEvidenceAt: Long,
        now: Long
    ): UserTodoItemDto = cur.copy(
        title = title,
        sourceGroupId = draft.sourceGroupId?.ifBlank { cur.sourceGroupId } ?: cur.sourceGroupId,
        sourceGroupName = draft.sourceGroupName?.ifBlank { cur.sourceGroupName } ?: cur.sourceGroupName,
        actionType = actionType ?: cur.actionType,
        actionDetail = actionDetail ?: cur.actionDetail,
        done = false,
        taskKind = "short_term_instance",
        lifecycleState = "active",
        lastEvidenceAt = mergedEvidenceAt,
        explicitIntent = draft.explicitIntent || cur.explicitIntent,
        updatedAt = now
    )

    private fun reopenShortInstance(
        cur: UserTodoItemDto,
        draft: ExtractedTodoDraft,
        title: String,
        actionType: String?,
        actionDetail: String?,
        mergedEvidenceAt: Long,
        now: Long
    ): UserTodoItemDto = cur.copy(
        title = title,
        sourceGroupId = draft.sourceGroupId?.ifBlank { cur.sourceGroupId } ?: cur.sourceGroupId,
        sourceGroupName = draft.sourceGroupName?.ifBlank { cur.sourceGroupName } ?: cur.sourceGroupName,
        actionType = actionType ?: cur.actionType,
        actionDetail = actionDetail ?: cur.actionDetail,
        done = false,
        lifecycleState = "active",
        closedAt = null,
        lastEvidenceAt = mergedEvidenceAt,
        explicitIntent = draft.explicitIntent,
        reopenCount = cur.reopenCount + 1,
        updatedAt = now
    )

    private fun instantiateRecurringTemplates(existing: MutableList<UserTodoItemDto>, now: Long) {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val bucket = today.toString()
        val templates = existing.filter { it.taskKind == "long_term_template" && it.lifecycleState != "cancelled" }
        for (tpl in templates) {
            val instance = buildTemplateInstanceIfDue(tpl, today, bucket, existing, now) ?: continue
            existing.add(instance)
        }
    }

    private fun buildTemplateInstanceIfDue(
        tpl: UserTodoItemDto,
        today: LocalDate,
        bucket: String,
        existing: List<UserTodoItemDto>,
        now: Long
    ): UserTodoItemDto? {
        if (!isTemplateDueToday(tpl, today)) return null
        val existsToday = existing.any {
            it.taskKind == "short_term_instance" &&
                it.templateId == tpl.id &&
                it.dateBucket == bucket
        }
        if (existsToday) return null
        val actionDetail = tpl.actionDetail ?: tpl.repeatAnchor
        return UserTodoItemDto(
            id = UUID.randomUUID().toString(),
            title = tpl.title,
            sourceGroupId = tpl.sourceGroupId,
            sourceGroupName = tpl.sourceGroupName,
            actionType = tpl.actionType,
            actionDetail = actionDetail,
            createdAt = now,
            updatedAt = now,
            done = false,
            taskKind = "short_term_instance",
            templateId = tpl.id,
            lifecycleState = "active",
            lastEvidenceAt = now,
            explicitIntent = false,
            dateBucket = bucket
        )
    }

    private fun isTemplateDueToday(tpl: UserTodoItemDto, today: LocalDate): Boolean {
        if (!isWithinActiveWindow(tpl, today)) return false
        return when (tpl.repeatRule?.trim()?.lowercase(Locale.getDefault())) {
            "workday" -> HolidayCalendarCn.isWorkday(today)
            "yearly" -> matchesYearlyAnchor(tpl.repeatAnchor, today)
            "monthly" -> matchesMonthlyAnchor(tpl.repeatAnchor, today)
            "custom" -> false
            else -> false
        }
    }

    private fun isWithinActiveWindow(tpl: UserTodoItemDto, today: LocalDate): Boolean {
        val from = tpl.activeFrom?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        if (from != null && today.isBefore(from)) return false
        val to = tpl.activeTo?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        if (to != null && today.isAfter(to)) return false
        return true
    }

    private fun matchesYearlyAnchor(repeatAnchor: String?, today: LocalDate): Boolean {
        val anchor = repeatAnchor?.trim() ?: return false
        val mmdd = "%02d-%02d".format(today.monthValue, today.dayOfMonth)
        return anchor == mmdd
    }

    private fun matchesMonthlyAnchor(repeatAnchor: String?, today: LocalDate): Boolean {
        val anchor = repeatAnchor?.trim() ?: return false
        val day = anchor.toIntOrNull() ?: return false
        return day == today.dayOfMonth
    }

    fun mergeExtracted(userId: String, incoming: List<ExtractedTodoDraft>) {
        if (incoming.isEmpty()) return
        val lock = locks.computeIfAbsent(userId) { Any() }
        synchronized(lock) {
            val existing = load(userId).toMutableList()
            val seenKeys = existing.map { logicalDedupKey(it.title, it.actionType, it.actionDetail) }.toMutableSet()
            val now = System.currentTimeMillis()
            for (draft in incoming) {
                val item = buildMergeExtractedItem(draft, seenKeys, now) ?: continue
                existing.add(item)
            }
            existing.sortByDescending { it.updatedAt }
            save(userId, existing)
        }
    }

    /** 构造一条去重后的合并待办；若标题非法或逻辑键已存在则返回 null。命中后会把逻辑键登记到 [seenKeys]。 */
    private fun buildMergeExtractedItem(
        draft: ExtractedTodoDraft,
        seenKeys: MutableSet<String>,
        now: Long
    ): UserTodoItemDto? {
        val t = draft.title.trim()
        if (t.isEmpty() || t.length > 500) return null
        val at = draft.actionType?.trim()?.lowercase()?.ifBlank { null }
        val ad = draft.actionDetail?.trim()?.ifBlank { null }
        val lk = logicalDedupKey(t, at, ad)
        if (lk in seenKeys) return null
        seenKeys.add(lk)
        return UserTodoItemDto(
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
            val resolvedLifecycle = resolveUpdatedLifecycle(cur, lifecycleState, done)
            val resolvedDone = resolveUpdatedDone(cur, resolvedLifecycle, done)
            val resolvedClosedAt = resolveUpdatedClosedAt(cur, resolvedLifecycle, closedAt, now)
            items[idx] = cur.copy(
                done = resolved.done,
                title = title ?: cur.title,
                actionType = normalizeLowercaseUpdate(actionType, cur.actionType),
                actionDetail = normalizePlainUpdate(actionDetail, cur.actionDetail),
                executedAt = executedAt ?: cur.executedAt,
                reminderId = resolveUpdatedReminderId(cur, reminderId, clearReminderId),
                taskKind = taskKind?.trim()?.ifBlank { cur.taskKind } ?: cur.taskKind,
                repeatRule = normalizeLowercaseUpdate(repeatRule, cur.repeatRule),
                repeatAnchor = normalizePlainUpdate(repeatAnchor, cur.repeatAnchor),
                activeFrom = activeFrom ?: cur.activeFrom,
                activeTo = activeTo ?: cur.activeTo,
                templateId = normalizePlainUpdate(templateId, cur.templateId),
                lifecycleState = resolvedLifecycle,
                closedAt = resolvedClosedAt,
                lastEvidenceAt = lastEvidenceAt ?: cur.lastEvidenceAt,
                explicitIntent = explicitIntent ?: cur.explicitIntent,
                dateBucket = normalizePlainUpdate(dateBucket, cur.dateBucket),
                reopenCount = reopenCount ?: cur.reopenCount,
                updatedAt = now
            )
            save(userId, items)
            return true
        }
    }

    private fun resolveUpdatedLifecycle(cur: UserTodoItemDto, lifecycleState: String?, done: Boolean?): String = when {
        lifecycleState != null -> lifecycleState.trim().lowercase(Locale.getDefault()).ifBlank { cur.lifecycleState }
        done == null -> cur.lifecycleState
        done -> if (cur.lifecycleState == "cancelled" || cur.lifecycleState == "deferred") cur.lifecycleState else "done"
        else -> "active"
    }

    private fun resolveUpdatedDone(cur: UserTodoItemDto, resolvedLifecycle: String, done: Boolean?): Boolean = when {
        done != null -> done
        resolvedLifecycle == "active" -> false
        resolvedLifecycle == "done" || resolvedLifecycle == "cancelled" || resolvedLifecycle == "deferred" -> true
        else -> cur.done
    }

    private fun resolveUpdatedClosedAt(cur: UserTodoItemDto, resolvedLifecycle: String, closedAt: Long?, now: Long): Long? = when {
        closedAt != null -> if (closedAt <= 0L) null else closedAt
        resolvedLifecycle == "active" -> null
        resolvedLifecycle == cur.lifecycleState -> cur.closedAt
        else -> now
    }

    private fun resolveUpdatedReminderId(cur: UserTodoItemDto, reminderId: Long?, clearReminderId: Boolean): Long? = when {
        clearReminderId -> null
        reminderId == null -> cur.reminderId
        else -> reminderId
    }

    /** 字段更新：null=保持原值；空白=清空；否则 trim+小写后写入（空白回退 null）。 */
    private fun normalizeLowercaseUpdate(value: String?, current: String?): String? = when {
        value == null -> current
        value.isBlank() -> null
        else -> value.trim().lowercase(Locale.getDefault()).ifBlank { null }
    }

    /** 字段更新：null=保持原值；空白=清空；否则 trim 后写入（空白回退 null）。 */
    private fun normalizePlainUpdate(value: String?, current: String?): String? = when {
        value == null -> current
        value.isBlank() -> null
        else -> value.trim().ifBlank { null }
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
        val at = actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null }
        var adNorm = normalizeActionDetailForKey(at, actionDetail)
        val kindPrefix = if (taskKind?.trim()?.lowercase(Locale.getDefault()) == "long_term_template") "lt:" else ""
        // actionType=alarm but missing actionDetail: try to extract time from title
        if ((at == "alarm" || at == "calendar") && adNorm == null) {
            adNorm = extractTimeFromTitle(title)
            if (adNorm != null) return "${kindPrefix}$at:$adNorm"
        }
        if ((at == "alarm" || at == "calendar") && adNorm != null) {
            return "${kindPrefix}$at:$adNorm"
        }
        return "${kindPrefix}t:${normKey(title)}"
    }

    /**
     * Wraps a Chinese-numeral parse attempt to distinguish two different null states:
     * - outer null  → regex did NOT match; caller may fall through to next parser
     * - outer non-null, inner null → regex matched but numeral was unparseable; caller must stop
     * - outer non-null, inner non-null → matched and successfully parsed
     */
    @JvmInline
    private value class ChineseTimeResult(val value: String?)

    /** Try to parse a time string like "七点起床" → "07:00", "下午3点半开会" → "15:30" */
    private fun extractTimeFromTitle(title: String): String? {
        val t = title.trim()
        val pm = t.contains("下午") || t.contains("晚上") || t.contains("傍晚")
        // Chinese numeral branches: once regex matches, commit — return result or null (no fall-through)
        parseTitleChineseHalfHour(t, pm)?.let { return it.value }
        parseTitleChineseExactHour(t, pm)?.let { return it.value }
        // Arabic numeral + 点：匹配后即视为结果归宿（无效则 return null，不继续尝试 HH:mm）
        val arabicHour = Regex("(\\d{1,2})\\s*点").find(t)
        if (arabicHour != null) {
            return parseTitleArabicHour(t, pm, arabicHour)
        }
        parseTitleHourMinute(t)?.let { return it }
        return null
    }

    // Chinese numeral + 点半 (e.g. 十二点半)
    // Returns null if regex did not match; returns ChineseTimeResult (with possibly-null value) if matched.
    private fun parseTitleChineseHalfHour(t: String, pm: Boolean): ChineseTimeResult? {
        val cnHalf = Regex("([一二三四五六七八九十两零]+点半)").find(t) ?: return null
        val cn = cnHalf.groupValues[1].replace("点半", "")
        val h = parseCnHour(cn) ?: return ChineseTimeResult(null)
        val hour = if (pm && h in 1..11) h + 12 else h
        return ChineseTimeResult(if (hour in 0..23) "%02d:%02d".format(hour, 30) else null)
    }

    // Chinese numeral + 点 (e.g. 七点)
    // Returns null if regex did not match; returns ChineseTimeResult (with possibly-null value) if matched.
    private fun parseTitleChineseExactHour(t: String, pm: Boolean): ChineseTimeResult? {
        val cnExact = Regex("([一二三四五六七八九十两零]+点)").find(t) ?: return null
        val cn = cnExact.groupValues[1].replace("点", "")
        val h = parseCnHour(cn) ?: return ChineseTimeResult(null)
        val hour = if (pm && h in 1..11) h + 12 else h
        return ChineseTimeResult(if (hour in 0..23) "%02d:%02d".format(hour, 0) else null)
    }

    // Arabic numeral + 点 (e.g. 7点, 3点半)
    private fun parseTitleArabicHour(t: String, pm: Boolean, match: MatchResult): String? {
        val h = match.groupValues[1].toIntOrNull() ?: return null
        var hour = h
        if (pm) {
            if (h in 1..11) hour = h + 12
            else if (h == 12) hour = 12
        }
        if (hour !in 0..23) return null
        val minute = if (t.contains("半")) 30 else 0
        return "%02d:%02d".format(hour, minute)
    }

    // HH:mm format
    private fun parseTitleHourMinute(t: String): String? {
        val hm = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(t) ?: return null
        val h = hm.groupValues[1].toIntOrNull() ?: return null
        val m = hm.groupValues[2].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) "%02d:%02d".format(h, m) else null
    }

    /** Parse Chinese numeral hour string: "七"→7, "十二"→12, "十"→10 */
    private fun parseCnHour(cn: String): Int? {
        val map = mapOf('一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9,'十' to 10,'两' to 2,'零' to 0)
        if (cn.length == 1) return map[cn.single()]
        if (cn.length == 2 && cn[0] == '十') {
            val unit = map[cn[1]] ?: 0
            return 10 + unit
        }
        if (cn.length == 2) {
            val tens = map[cn[0]] ?: return null
            val units = map[cn[1]] ?: return null
            return tens * 10 + units
        }
        return null
    }

    private fun normKey(title: String): String {
        var s = title.trim().lowercase(Locale.getDefault())
        s = s.replace(Regex("\\s+"), " ")
        s = s.replace(Regex("[“”‘’，。、；:!?！．·…~～\"'（）()\\[\\]【】|/《》〈〉{}]+"), "")
        s = s.replace(
            Regex("^(设置闹钟|设闹钟|闹钟|提醒我|提醒|记得|记得要|请|帮忙|麻烦|帮我|好的我会|好的|没问题|收到|知道了|明白了|ok|好)\\s*[:：]?\\s*"),
            ""
        )
        s = s.replace(Regex("[:：]\\s*$"), "")
        s = s.replace(Regex("^\\s*(设置|设定|安排?|创建?|添加?)\\s*[:：]?\\s*"), "")
        s = s.replace(
            Regex("的?(闹钟|提醒|叫醒|起床铃|日程|日历|事项)$"),
            ""
        )
        return s.trim().ifEmpty { title.trim().lowercase(Locale.getDefault()) }
    }


    @Suppress("UnusedParameter")
    private fun normalizeActionDetailForKey(actionType: String?, detail: String?): String? {
        if (detail == null) return null
        val t = detail.trim().lowercase(Locale.getDefault()).ifBlank { return null }
        parseExactHourMinuteKey(t)?.let { return it }
        parseFullDateTimeKey(t)?.let { return it }
        parseRelativeDateTimeKey(t)?.let { return it }
        return normKey(t)
    }

    // "HH:mm" exact。匹配但解析失败时回退到 normKey(t)，与原始一致。
    private fun parseExactHourMinuteKey(t: String): String? {
        val hm = Regex("^(\\d{1,2})\\s*[:：]\\s*(\\d{2})$").find(t) ?: return null
        val h = hm.groupValues[1].toIntOrNull() ?: return normKey(t)
        val m = hm.groupValues[2].toIntOrNull() ?: return normKey(t)
        if (h in 0..23 && m in 0..59) return "%02d:%02d".format(h, m)
        return null
    }

    // "YYYY-MM-DD HH:mm" or "YYYY-MM-DDTHH:mm"
    private fun parseFullDateTimeKey(t: String): String? {
        val fullDt = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ]?(\\d{1,2})[:：](\\d{2})").find(t) ?: return null
        val h = fullDt.groupValues[4].toIntOrNull() ?: return normKey(t)
        val m = fullDt.groupValues[5].toIntOrNull() ?: return normKey(t)
        if (h in 0..23 && m in 0..59) {
            return "%s-%s-%s %02d:%02d".format(
                fullDt.groupValues[1], fullDt.groupValues[2], fullDt.groupValues[3], h, m
            )
        }
        return null
    }

    // "今天 15:00" / "明天 9:30"
    private fun parseRelativeDateTimeKey(t: String): String? {
        val relTime = Regex("^(今天|明天|后天|大后天|下周)\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(t) ?: return null
        val h = relTime.groupValues[2].toIntOrNull() ?: return normKey(t)
        val m = relTime.groupValues[3].toIntOrNull() ?: return normKey(t)
        if (h in 0..23 && m in 0..59) return "%s %02d:%02d".format(relTime.groupValues[1], h, m)
        return null
    }

    /** 若归一化标题互为包含则并成一条（解决一事多条）；已完成项也参与合并以清理历史。 */
    private fun mergeContainedUndoneTitles(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
        val pool = items.toMutableList()
        if (pool.size < 2) return items
        val now = System.currentTimeMillis()
        while (pool.size > 1) {
            val applied = applyFirstContainedMerge(pool, now)
            if (!applied) break
        }
        return pool
    }

    /** 找到首个可合并的标题对并就地合并；找到并合并返回 true，否则返回 false。 */
    private fun applyFirstContainedMerge(pool: MutableList<UserTodoItemDto>, now: Long): Boolean {
        for (i in 0 until pool.size) {
            for (j in i + 1 until pool.size) {
                val merged = tryMergeByContainedNormTitle(pool[i], pool[j], now) ?: continue
                pool.removeAt(j)
                pool.removeAt(i)
                pool.add(merged)
                return true
            }
        }
        return false
    }

    private fun isStructuredScheduleType(actionType: String?): Boolean {
        val t = actionType?.trim()?.lowercase(Locale.getDefault()) ?: ""
        return t == "alarm" || t == "calendar"
    }

    /** 两条都是结构化（alarm/calendar）时：逻辑键不同代表确属不同时间，不可合并。 */
    private fun structuredSchedulesBlockMerge(a: UserTodoItemDto, b: UserTodoItemDto): Boolean {
        if (!isStructuredScheduleType(a.actionType) || !isStructuredScheduleType(b.actionType)) return false
        return logicalDedupKey(a.title, a.actionType, a.actionDetail) !=
            logicalDedupKey(b.title, b.actionType, b.actionDetail)
    }

    private fun hasContainedNormalizedTitle(a: UserTodoItemDto, b: UserTodoItemDto): Boolean {
        val na = normKey(a.title)
        val nb = normKey(b.title)
        if (na.length < 2 || nb.length < 2) return false
        return na.contains(nb) || nb.contains(na)
    }

    private fun tryMergeByContainedNormTitle(
        a: UserTodoItemDto,
        b: UserTodoItemDto,
        now: Long
    ): UserTodoItemDto? {
        // 模板与实例属于不同语义层，禁止互并（否则会吞掉长期模板）
        if (a.taskKind != b.taskKind) return null
        // For same-key structured alarm/calendar: let mergeItemsByLogicalKey handle
        // Here we catch cross-key semantic overlap via normKey containment
        if (structuredSchedulesBlockMerge(a, b)) return null
        if (!hasContainedNormalizedTitle(a, b)) return null

        val anyDone = a.done || b.done
        val newer = if (a.updatedAt >= b.updatedAt) a else b
        val other = if (newer.id == a.id) b else a
        val shortTitle = if (a.title.trim().length <= b.title.trim().length) a.title.trim() else b.title.trim()
        val mergedExec = listOfNotNull(a.executedAt, b.executedAt).minOrNull()
        val mergedRem = newer.reminderId ?: other.reminderId
            ?: a.reminderId ?: b.reminderId
        return newer.copy(
            title = shortTitle.take(500),
            actionType = (newer.actionType ?: other.actionType)
                ?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null },
            actionDetail = listOfNotNull(a.actionDetail?.trim(), b.actionDetail?.trim())
                .filter { it.isNotEmpty() }
                .maxByOrNull { it.length }
                ?.ifBlank { null },
            done = anyDone,
            executedAt = mergedExec,
            reminderId = mergedRem,
            updatedAt = now
        )
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
