package com.silk.backend.todos

import com.silk.backend.database.UserTodoItemDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

private data class ContainedTitleMerge(
    val firstIndex: Int,
    val secondIndex: Int,
    val mergedItem: UserTodoItemDto,
)

internal fun instantiateRecurringTemplatesForUserTodo(existing: MutableList<UserTodoItemDto>, now: Long) {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val bucket = today.toString()
    val templates = existing.filter { it.taskKind == "long_term_template" && it.lifecycleState != "cancelled" }
    for (template in templates) {
        buildRecurringInstanceIfDue(
            template = template,
            existing = existing,
            today = today,
            bucket = bucket,
            now = now,
        )?.let(existing::add)
    }
}

private fun buildRecurringInstanceIfDue(
    template: UserTodoItemDto,
    existing: List<UserTodoItemDto>,
    today: LocalDate,
    bucket: String,
    now: Long,
): UserTodoItemDto? {
    if (!isTemplateDueToday(template, today)) return null
    val existsToday = existing.any {
        it.taskKind == "short_term_instance" &&
            it.templateId == template.id &&
            it.dateBucket == bucket
    }
    if (existsToday) return null
    return UserTodoItemDto(
        id = UUID.randomUUID().toString(),
        title = template.title,
        sourceGroupId = template.sourceGroupId,
        sourceGroupName = template.sourceGroupName,
        actionType = template.actionType,
        actionDetail = template.actionDetail ?: template.repeatAnchor,
        createdAt = now,
        updatedAt = now,
        done = false,
        taskKind = "short_term_instance",
        templateId = template.id,
        lifecycleState = "active",
        lastEvidenceAt = now,
        explicitIntent = false,
        dateBucket = bucket,
    )
}

private fun isTemplateDueToday(template: UserTodoItemDto, today: LocalDate): Boolean {
    if (!isWithinActiveWindow(template, today)) return false
    return when (template.repeatRule?.trim()?.lowercase(Locale.getDefault())) {
        "workday" -> HolidayCalendarCn.isWorkday(today)
        "yearly" -> matchesYearlyAnchor(template.repeatAnchor, today)
        "monthly" -> matchesMonthlyAnchor(template.repeatAnchor, today)
        "custom" -> false
        else -> false
    }
}

private fun isWithinActiveWindow(template: UserTodoItemDto, today: LocalDate): Boolean {
    val from = template.activeFrom?.let { epochMillis ->
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    if (from != null && today.isBefore(from)) return false

    val to = template.activeTo?.let { epochMillis ->
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    if (to != null && today.isAfter(to)) return false
    return true
}

private fun matchesYearlyAnchor(anchor: String?, today: LocalDate): Boolean {
    val normalizedAnchor = anchor?.trim() ?: return false
    val todayAnchor = "%02d-%02d".format(today.monthValue, today.dayOfMonth)
    return normalizedAnchor == todayAnchor
}

private fun matchesMonthlyAnchor(anchor: String?, today: LocalDate): Boolean {
    val day = anchor?.trim()?.toIntOrNull() ?: return false
    return day == today.dayOfMonth
}

internal fun resolveUserTodoUpdate(
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
    val resolvedLifecycle = resolveUpdatedLifecycle(current, lifecycleState, done)
    return ResolvedTodoUpdate(
        done = resolveUpdatedDone(current, done, resolvedLifecycle),
        actionType = normalizeLowercaseField(actionType, current.actionType),
        actionDetail = normalizePlainField(actionDetail, current.actionDetail),
        reminderId = resolveReminderId(current, reminderId, clearReminderId),
        taskKind = normalizePlainField(taskKind, current.taskKind) ?: current.taskKind,
        repeatRule = normalizeLowercaseField(repeatRule, current.repeatRule),
        repeatAnchor = normalizePlainField(repeatAnchor, current.repeatAnchor),
        templateId = normalizePlainField(templateId, current.templateId),
        lifecycleState = resolvedLifecycle,
        closedAt = resolveUpdatedClosedAt(current, closedAt, resolvedLifecycle, now),
        dateBucket = normalizePlainField(dateBucket, current.dateBucket),
    )
}

private fun resolveUpdatedLifecycle(
    current: UserTodoItemDto,
    lifecycleState: String?,
    done: Boolean?,
): String {
    if (lifecycleState != null) {
        return lifecycleState.trim().lowercase(Locale.getDefault()).ifBlank { current.lifecycleState }
    }
    if (done == null) return current.lifecycleState
    if (!done) return "active"
    return if (current.lifecycleState == "cancelled" || current.lifecycleState == "deferred") {
        current.lifecycleState
    } else {
        "done"
    }
}

private fun resolveUpdatedDone(
    current: UserTodoItemDto,
    done: Boolean?,
    lifecycleState: String,
): Boolean {
    if (done != null) return done
    return when (lifecycleState) {
        "active" -> false
        "done", "cancelled", "deferred" -> true
        else -> current.done
    }
}

private fun resolveUpdatedClosedAt(
    current: UserTodoItemDto,
    closedAt: Long?,
    lifecycleState: String,
    now: Long,
): Long? {
    if (closedAt != null) return closedAt.takeIf { it > 0L }
    if (lifecycleState == "active") return null
    if (lifecycleState == current.lifecycleState) return current.closedAt
    return now
}

private fun resolveReminderId(
    current: UserTodoItemDto,
    reminderId: Long?,
    clearReminderId: Boolean,
): Long? {
    if (clearReminderId) return null
    return reminderId ?: current.reminderId
}

private fun normalizeLowercaseField(value: String?, currentValue: String?): String? {
    if (value == null) return currentValue
    if (value.isBlank()) return null
    return value.trim().lowercase(Locale.getDefault()).ifBlank { null }
}

private fun normalizePlainField(value: String?, currentValue: String?): String? {
    if (value == null) return currentValue
    if (value.isBlank()) return null
    return value.trim().ifBlank { null }
}

internal fun userTodoLogicalDedupKey(
    title: String,
    actionType: String?,
    actionDetail: String?,
    taskKind: String? = null,
): String {
    val normalizedActionType = actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null }
    var normalizedActionDetail = normalizeActionDetailForKey(actionDetail)
    val kindPrefix = if (taskKind?.trim()?.lowercase(Locale.getDefault()) == "long_term_template") "lt:" else ""
    if ((normalizedActionType == "alarm" || normalizedActionType == "calendar") && normalizedActionDetail == null) {
        normalizedActionDetail = extractTimeFromTitle(title)
        if (normalizedActionDetail != null) return "${kindPrefix}${normalizedActionType}:${normalizedActionDetail}"
    }
    if ((normalizedActionType == "alarm" || normalizedActionType == "calendar") && normalizedActionDetail != null) {
        return "${kindPrefix}${normalizedActionType}:${normalizedActionDetail}"
    }
    return "${kindPrefix}t:${normalizeTodoTitleForKey(title)}"
}

private fun extractTimeFromTitle(title: String): String? {
    val trimmed = title.trim()
    val pm = trimmed.contains("下午") || trimmed.contains("晚上") || trimmed.contains("傍晚")
    parseChineseHalfHour(trimmed, pm)?.let { return it }
    parseChineseExactHour(trimmed, pm)?.let { return it }
    parseArabicHour(trimmed, pm)?.let { return it }
    parseHourMinute(trimmed)?.let { return it }
    return null
}

private fun parseChineseHalfHour(text: String, pm: Boolean): String? {
    val match = Regex("([一二三四五六七八九十两零]+点半)").find(text) ?: return null
    val hour = parseChineseHour(match.groupValues[1].replace("点半", "")) ?: return null
    return formatHourMinute(adjustPmHour(hour, pm) ?: return null, 30)
}

private fun parseChineseExactHour(text: String, pm: Boolean): String? {
    val match = Regex("([一二三四五六七八九十两零]+点)").find(text) ?: return null
    val hour = parseChineseHour(match.groupValues[1].replace("点", "")) ?: return null
    return formatHourMinute(adjustPmHour(hour, pm) ?: return null, 0)
}

private fun parseArabicHour(text: String, pm: Boolean): String? {
    val match = Regex("(\\d{1,2})\\s*点").find(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val normalizedHour = adjustPmHour(hour, pm) ?: return null
    val minute = if (text.contains("半")) 30 else 0
    return formatHourMinute(normalizedHour, minute)
}

private fun parseHourMinute(text: String): String? {
    val match = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return formatHourMinute(hour, minute)
}

private fun adjustPmHour(hour: Int, pm: Boolean): Int? {
    val normalizedHour = if (pm && hour in 1..11) hour + 12 else hour
    return normalizedHour.takeIf { it in 0..23 }
}

private fun formatHourMinute(hour: Int, minute: Int): String? {
    if (minute !in 0..59) return null
    return "%02d:%02d".format(hour, minute)
}

private fun parseChineseHour(cn: String): Int? {
    val chineseDigits = mapOf(
        '一' to 1,
        '二' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
        '十' to 10,
        '两' to 2,
        '零' to 0,
    )
    if (cn.length == 1) return chineseDigits[cn.single()]
    if (cn.length == 2 && cn[0] == '十') {
        val unit = chineseDigits[cn[1]] ?: 0
        return 10 + unit
    }
    if (cn.length == 2) {
        val tens = chineseDigits[cn[0]] ?: return null
        val units = chineseDigits[cn[1]] ?: return null
        return tens * 10 + units
    }
    return null
}

private fun normalizeTodoTitleForKey(title: String): String {
    var normalized = title.trim().lowercase(Locale.getDefault())
    normalized = normalized.replace(Regex("\\s+"), " ")
    normalized = normalized.replace(Regex("[“”‘’，。、；:!?！．·…~～\"'（）()\\[\\]【】|/《》〈〉{}]+"), "")
    normalized = normalized.replace(
        Regex("^(设置闹钟|设闹钟|闹钟|提醒我|提醒|记得|记得要|请|帮忙|麻烦|帮我|好的我会|好的|没问题|收到|知道了|明白了|ok|好)\\s*[:：]?\\s*"),
        "",
    )
    normalized = normalized.replace(Regex("[:：]\\s*$"), "")
    normalized = normalized.replace(Regex("^\\s*(设置|设定|安排?|创建?|添加?)\\s*[:：]?\\s*"), "")
    normalized = normalized.replace(Regex("的?(闹钟|提醒|叫醒|起床铃|日程|日历|事项)$"), "")
    return normalized.trim().ifEmpty { title.trim().lowercase(Locale.getDefault()) }
}

private fun normalizeActionDetailForKey(detail: String?): String? {
    if (detail == null) return null
    val normalized = detail.trim().lowercase(Locale.getDefault()).ifBlank { return null }
    parseExactHourMinute(normalized)?.let { return it }
    parseFullDateTime(normalized)?.let { return it }
    parseRelativeDateTime(normalized)?.let { return it }
    return normalizeTodoTitleForKey(normalized)
}

private fun parseExactHourMinute(text: String): String? {
    val match = Regex("^(\\d{1,2})\\s*[:：]\\s*(\\d{2})$").find(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return normalizeTodoTitleForKey(text)
    val minute = match.groupValues[2].toIntOrNull() ?: return normalizeTodoTitleForKey(text)
    if (hour !in 0..23 || minute !in 0..59) return null
    return "%02d:%02d".format(hour, minute)
}

private fun parseFullDateTime(text: String): String? {
    val match = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ]?(\\d{1,2})[:：](\\d{2})").find(text) ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return normalizeTodoTitleForKey(text)
    val minute = match.groupValues[5].toIntOrNull() ?: return normalizeTodoTitleForKey(text)
    if (hour !in 0..23 || minute !in 0..59) return null
    return "%s-%s-%s %02d:%02d".format(
        match.groupValues[1],
        match.groupValues[2],
        match.groupValues[3],
        hour,
        minute,
    )
}

private fun parseRelativeDateTime(text: String): String? {
    val match = Regex("^(今天|明天|后天|大后天|下周)\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(text) ?: return null
    val hour = match.groupValues[2].toIntOrNull() ?: return normalizeTodoTitleForKey(text)
    val minute = match.groupValues[3].toIntOrNull() ?: return normalizeTodoTitleForKey(text)
    if (hour !in 0..23 || minute !in 0..59) return null
    return "%s %02d:%02d".format(match.groupValues[1], hour, minute)
}

private fun isStructuredScheduleType(actionType: String?): Boolean {
    return when (actionType?.trim()?.lowercase(Locale.getDefault())) {
        "alarm", "calendar" -> true
        else -> false
    }
}

internal fun mergeContainedUserTodos(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
    var pool = items.toMutableList()
    if (pool.size < 2) return items
    val now = System.currentTimeMillis()
    while (pool.size > 1) {
        val merge = findContainedTitleMerge(pool, now) ?: break
        pool.removeAt(merge.secondIndex)
        pool.removeAt(merge.firstIndex)
        pool.add(merge.mergedItem)
    }
    return pool
}

private fun findContainedTitleMerge(pool: List<UserTodoItemDto>, now: Long): ContainedTitleMerge? {
    return pool.indices.asSequence().flatMap { firstIndex ->
        (firstIndex + 1 until pool.size).asSequence().mapNotNull { secondIndex ->
            tryMergeByContainedNormTitle(pool[firstIndex], pool[secondIndex], now)?.let { mergedItem ->
                ContainedTitleMerge(
                    firstIndex = firstIndex,
                    secondIndex = secondIndex,
                    mergedItem = mergedItem,
                )
            }
        }
    }.firstOrNull()
}

private fun tryMergeByContainedNormTitle(
    first: UserTodoItemDto,
    second: UserTodoItemDto,
    now: Long,
): UserTodoItemDto? {
    if (first.taskKind != second.taskKind) return null
    if (!canMergeStructuredSchedules(first, second)) return null
    if (!hasContainedNormalizedTitle(first.title, second.title)) return null

    val newer = if (first.updatedAt >= second.updatedAt) first else second
    val other = if (newer.id == first.id) second else first
    return newer.copy(
        title = selectShorterTitle(first.title, second.title).take(500),
        actionType = mergeActionType(newer, other),
        actionDetail = mergeActionDetail(first, second),
        done = first.done || second.done,
        executedAt = listOfNotNull(first.executedAt, second.executedAt).minOrNull(),
        reminderId = newer.reminderId ?: other.reminderId ?: first.reminderId ?: second.reminderId,
        updatedAt = now,
    )
}

private fun canMergeStructuredSchedules(first: UserTodoItemDto, second: UserTodoItemDto): Boolean {
    if (!isStructuredScheduleType(first.actionType) || !isStructuredScheduleType(second.actionType)) return true
    return userTodoLogicalDedupKey(first.title, first.actionType, first.actionDetail) ==
        userTodoLogicalDedupKey(second.title, second.actionType, second.actionDetail)
}

private fun hasContainedNormalizedTitle(firstTitle: String, secondTitle: String): Boolean {
    val first = normalizeTodoTitleForKey(firstTitle)
    val second = normalizeTodoTitleForKey(secondTitle)
    if (first.length < 2 || second.length < 2) return false
    return first.contains(second) || second.contains(first)
}

private fun selectShorterTitle(firstTitle: String, secondTitle: String): String {
    val first = firstTitle.trim()
    val second = secondTitle.trim()
    return if (first.length <= second.length) first else second
}

private fun mergeActionType(newer: UserTodoItemDto, other: UserTodoItemDto): String? {
    return (newer.actionType ?: other.actionType)
        ?.trim()
        ?.lowercase(Locale.getDefault())
        ?.ifBlank { null }
}

private fun mergeActionDetail(first: UserTodoItemDto, second: UserTodoItemDto): String? {
    return listOfNotNull(first.actionDetail?.trim(), second.actionDetail?.trim())
        .filter { it.isNotEmpty() }
        .maxByOrNull { it.length }
        ?.ifBlank { null }
}

internal fun mergeUserTodoItemsByLogicalKey(items: List<UserTodoItemDto>): List<UserTodoItemDto> {
    val byKey = items.groupBy {
        userTodoLogicalDedupKey(it.title, it.actionType, it.actionDetail, it.taskKind)
    }
    val now = System.currentTimeMillis()
    return byKey.values.map { group ->
        val sorted = group.sortedByDescending { it.updatedAt }
        val newest = sorted.first()
        val mergedRow = group.size > 1
        val anyDone = group.any { it.done }
        val bestDetail = group
            .mapNotNull { it.actionDetail?.trim()?.takeIf { detail -> detail.isNotEmpty() } }
            .maxByOrNull { it.length }
        val preferredType = group
            .mapNotNull { it.actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null } }
            .firstOrNull { it == "alarm" || it == "calendar" }
            ?: newest.actionType?.trim()?.lowercase(Locale.getDefault())?.ifBlank { null }
        val shortTitle = group.map { it.title.trim() }.filter { it.isNotEmpty() }
            .minByOrNull { it.length } ?: newest.title
        val mergedExec = group.mapNotNull { it.executedAt }.minOrNull()
        val mergedReminderId = sorted.firstOrNull { it.reminderId != null }?.reminderId
        newest.copy(
            title = shortTitle.take(500),
            actionType = preferredType?.ifBlank { null },
            actionDetail = (bestDetail ?: newest.actionDetail)?.trim()?.ifBlank { null },
            done = anyDone,
            executedAt = mergedExec,
            reminderId = mergedReminderId,
            updatedAt = if (mergedRow) now else newest.updatedAt,
        )
    }
}
