package com.silk.backend.todos

import com.silk.backend.ChatHistoryManager
import com.silk.backend.ai.AIConfig
import com.silk.backend.database.UserTodoItemDto
import com.silk.backend.models.ChatHistory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal const val MAX_MESSAGES_PER_GROUP = 55
internal const val MAX_TRANSCRIPT_CHARS = 26_000
internal const val MAX_TODOS_FOR_COMPACT = 80
internal const val MAX_COMPACT_INPUT_CHARS = 48_000
internal const val MAX_TODOS_PER_REFRESH = 12

private val groupTodoHttpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofMillis(AIConfig.TIMEOUT))
    .build()

private val groupTodoJson = Json { ignoreUnknownKeys = true }

private val historyBaseDirs: List<String> = listOf(
    "chat_history",
    "backend/chat_history",
    "../chat_history"
).distinct()

internal val skipSmallTalk = Regex(
    """^(好的|嗯|行|可以|谢谢|收到|哈哈|哈哈哈哈|嗯嗯|对的|是|不是|没错|明白|了解|OK|ok|6|牛|赞|玫瑰|咖啡)[\s!！。…~～]*$"""
)

@Serializable
private data class SimpleMsg(val role: String, val content: String)

@Serializable
private data class SimpleChatRequest(
    val model: String,
    val messages: List<SimpleMsg>,
    val temperature: Double = 0.35,
    @SerialName("max_tokens")
    val maxTokens: Int = 2048,
    val stream: Boolean = false
)

@Serializable
private data class CompactTodoInputRow(
    val id: String,
    val title: String,
    val actionType: String? = null,
    val actionDetail: String? = null,
    val done: Boolean = false,
    val sourceGroupId: String? = null,
    val sourceGroupName: String? = null
)

internal data class GroupSlice(
    val groupId: String,
    val groupName: String,
    val messages: List<Triple<String, String, Long>>
)

internal data class RefreshPreparation(
    val userName: String,
    val sliceCount: Int,
    val slices: List<GroupSlice>,
    val transcript: String,
    val heuristicDrafts: List<ExtractedTodoDraft>,
    val latestEvidenceTs: Long,
)

internal data class RefreshDraftSelection(
    val llmDrafts: List<ExtractedTodoDraft>,
    val heuristicDraftCount: Int,
    val forcedRecurringDrafts: List<ExtractedTodoDraft>,
    val recurringLines: List<String>,
    val finalDrafts: List<ExtractedTodoDraft>,
    val jsonOk: Boolean,
    val llmOrParseFailed: Boolean,
)

private data class HeuristicCandidateLine(
    val text: String,
    val candidate: String,
    val isAlarmLine: Boolean,
    val fromChecklist: Boolean
)

private data class RecurringTemplateLine(
    val text: String,
    val isWorkdayHabit: Boolean
)

internal fun buildRefreshSystemPrompt(): String = """你是「真人待办」整理助手。输入为多群聊天节选，格式为 [发送者]: 内容。

核心原则（严格执行）：
- **一事一条**：同一个意图/事项在整个对话中只输出一条待办，禁止任何形式的重复。
- 只为人（真实用户）需要落实的「一件事」各写一条待办；不要把 AI 助手（如 Silk、🤖）的寒暄、泛泛解释、长篇回复拆成多条待办。
- **禁止**把助手回复里的建议列表、客套话、「好的我可以帮你」等整段当成待办；除非聊天里**明确**出现了用户要执行的具体动作。
- 同一件事合并成一条 title（例如用户说「提醒我七点起床」→ title="7点起床闹钟" 一条即可，去掉"设置闹钟""提醒我"等套话前缀）。
- title 用简短可执行中文，10-30 字为佳；**禁止**把多句话、列表、段落原样粘贴进一条 title。
- 输出前必须逐条检查：删除任何两条之间仅有措辞差异、语义实质相同的待办。

actionType / actionDetail（能填就填，影响手机端是否显示「运行」按钮）：
- 用户要闹钟/叫醒/起床：actionType="alarm"，actionDetail **必须**填 24 小时制时间如 "07:00" 或 "19:30"（从聊天推断具体时间）。
- 要日程/会议：actionType="calendar"，actionDetail **必须**填 "YYYY-MM-DD HH:mm" 或 "明天 15:00" 等含时间的可解析片段。
- 普通事务：actionType="none" 或省略。

**硬性上限：todos 数组最多 $MAX_TODOS_PER_REFRESH 条**；宁可少输出也不要重复。
输出：仅一段 JSON，无 Markdown。
格式：{"todos":[{"title":"...","actionType":"alarm","actionDetail":"07:00","sourceGroupId":"可省略","sourceGroupName":"可省略"}]}"""

internal fun buildRefreshUserPrompt(
    userName: String,
    userId: String,
    transcript: String,
): String = "用户显示名：$userName（userId=${userId.take(8)}…）\n\n$transcript"

internal fun selectPrimaryDrafts(
    llmDrafts: List<ExtractedTodoDraft>,
    llmOrParseFailed: Boolean,
    heuristicDrafts: List<ExtractedTodoDraft>,
): List<ExtractedTodoDraft> = when {
    llmDrafts.isNotEmpty() -> llmDrafts
    llmOrParseFailed -> heuristicDrafts
    else -> emptyList()
}

internal fun shouldReplaceUndone(selection: RefreshDraftSelection): Boolean =
    selection.jsonOk || selection.finalDrafts.isNotEmpty()

internal fun refreshSourceLabel(selection: RefreshDraftSelection): String = when {
    selection.llmDrafts.isNotEmpty() -> "模型"
    selection.finalDrafts.isNotEmpty() -> "启发式"
    else -> "清空未完成"
}

internal fun compactStoredTodosForUser(userId: String, apiKey: String) {
    val all = UserTodoStore.load(userId).sortedByDescending { it.updatedAt }
    if (all.isEmpty()) return
    var batch = all.take(MAX_TODOS_FOR_COMPACT)
    if (all.size > MAX_TODOS_FOR_COMPACT) {
        println(
            "ℹ️ [GroupTodoExtractionService] 去冗仅处理最近 ${MAX_TODOS_FOR_COMPACT} 条，其余 ${all.size - MAX_TODOS_FOR_COMPACT} 条保留至下次"
        )
    }
    val rowSerializer = ListSerializer(CompactTodoInputRow.serializer())
    var rows = batch.map {
        CompactTodoInputRow(
            id = it.id,
            title = it.title,
            actionType = it.actionType,
            actionDetail = it.actionDetail,
            done = it.done,
            sourceGroupId = it.sourceGroupId,
            sourceGroupName = it.sourceGroupName
        )
    }
    var payload = groupTodoJson.encodeToString(rowSerializer, rows)
    if (payload.length > MAX_COMPACT_INPUT_CHARS) {
        println("⚠️ [GroupTodoExtractionService] 去冗输入过长，已截断条数")
        while (payload.length > MAX_COMPACT_INPUT_CHARS && rows.size > 1) {
            rows = rows.dropLast(1)
            batch = batch.dropLast(1)
            payload = groupTodoJson.encodeToString(rowSerializer, rows)
        }
        if (payload.length > MAX_COMPACT_INPUT_CHARS) return
    }

    val system = """你是「待办去冗」助手。输入为一个 JSON 数组，每条为已存在的待办，字段含 id、title、actionType、actionDetail、done 等。

任务：
- 合并**语义重复**的条目为一条；合并后 **id 必须等于保留条目的 id**（从输入里选一条保留，不得编造新 id）。
- 缩短冗长、啰嗦的 title：一句一事，尽量 ≤40 字；不要把列表或多段话原样保留；**禁止**把一条拆成多条；**禁止**编造输入中不存在的任务。
- 若组合并：任一条 done 为 true 则结果为 done=true。
- actionType / actionDetail：在组内选更完整、更便于执行的一份（alarm/calendar/none 规则与抽取阶段一致）。

输出：仅一段 JSON，无 Markdown。
格式：{"todos":[{"id":"必须是输入中的id","title":"...","actionType":"alarm","actionDetail":"07:00","sourceGroupId":"可省略","sourceGroupName":"可省略","done":false}]}
输出的每条 id 必须出现在输入 JSON 的 id 集合中。"""

    val raw = callLlmOrNull(
        system = system,
        user = payload,
        apiKey = apiKey,
        failurePrefix = "❌ [GroupTodoExtractionService] 去冗 LLM 失败"
    ) ?: run {
        UserTodoStore.dedupeByLogicalKeyInPlace(userId)
        return
    }
    val originalsById = batch.associateBy { it.id }
    val validIds = batch.map { it.id }.toSet()
    val compacted = parseCompactTodoJson(raw, validIds, originalsById) ?: run {
        println("⚠️ [GroupTodoExtractionService] 去冗解析失败，跳过 LLM 写回（将尝试本地逻辑去重）")
        UserTodoStore.dedupeByLogicalKeyInPlace(userId)
        return
    }
    if (compacted.isEmpty()) {
        println("⚠️ [GroupTodoExtractionService] 去冗结果为空，保留原列表")
        UserTodoStore.dedupeByLogicalKeyInPlace(userId)
        return
    }
    val tail = all.drop(batch.size)
    val merged = (compacted + tail).sortedByDescending { it.updatedAt }
    UserTodoStore.replaceAll(userId, merged)
    println("✅ [GroupTodoExtractionService] 去冗完成：${batch.size} → ${compacted.size}（未参与批次的条目已追加）")
}

private fun parseCompactTodoJson(
    raw: String,
    validIds: Set<String>,
    originalsById: Map<String, UserTodoItemDto>
): List<UserTodoItemDto>? {
    val slice = extractJsonObject(raw) ?: return null
    return try {
        val obj = groupTodoJson.parseToJsonElement(slice).jsonObject
        val arr = obj["todos"]?.jsonArray ?: return null
        val now = System.currentTimeMillis()
        val out = mutableListOf<UserTodoItemDto>()
        val seenOutIds = mutableSetOf<String>()
        for (el in arr) {
            val parsed = parseCompactTodoEntry(el, validIds, seenOutIds, originalsById, now) ?: continue
            out.add(parsed)
        }
        if (out.isEmpty()) null else out
    } catch (e: SerializationException) {
        println("⚠️ [GroupTodoExtractionService] 去冗 JSON 解析失败: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        println("⚠️ [GroupTodoExtractionService] 去冗 JSON 解析失败: ${e.message}")
        null
    }
}

private fun parseCompactTodoEntry(
    element: JsonElement,
    validIds: Set<String>,
    seenOutIds: MutableSet<String>,
    originalsById: Map<String, UserTodoItemDto>,
    now: Long
): UserTodoItemDto? {
    val todoObject = element.jsonObject
    val id = todoObject["id"]?.jsonPrimitive?.content?.trim() ?: return null
    if (id !in validIds) {
        println("⚠️ [GroupTodoExtractionService] 去冗跳过未知 id: ${id.take(12)}…")
        return null
    }
    if (!seenOutIds.add(id)) return null

    val original = originalsById[id] ?: return null
    val title = todoObject["title"]?.jsonPrimitive?.content?.trim()
        ?.takeIf(::isCompactTodoTitleValid)
        ?: return null

    return UserTodoItemDto(
        id = id,
        title = title,
        sourceGroupId = parseCompactTodoOptionalText(todoObject, "sourceGroupId") ?: original.sourceGroupId,
        sourceGroupName = parseCompactTodoOptionalText(todoObject, "sourceGroupName") ?: original.sourceGroupName,
        actionType = parseCompactTodoActionType(todoObject),
        actionDetail = parseCompactTodoOptionalText(todoObject, "actionDetail"),
        createdAt = original.createdAt,
        updatedAt = now,
        done = resolveCompactTodoDone(todoObject, original),
        executedAt = original.executedAt,
        reminderId = original.reminderId
    )
}

private fun isCompactTodoTitleValid(title: String): Boolean = title.isNotEmpty() && title.length <= 500

private fun parseCompactTodoOptionalText(
    todoObject: JsonObject,
    key: String
): String? = todoObject[key]
    ?.jsonPrimitive
    ?.content
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private fun parseCompactTodoActionType(todoObject: JsonObject): String? =
    parseCompactTodoOptionalText(todoObject, "actionType")
        ?.lowercase()
        ?.takeIf { it != "null" }

private fun resolveCompactTodoDone(
    todoObject: JsonObject,
    original: UserTodoItemDto
): Boolean {
    val doneValue = todoObject["done"]?.jsonPrimitive ?: return original.done
    doneValue.booleanOrNull?.let { return it }
    return doneValue.content.equals("true", ignoreCase = true) || doneValue.content == "1"
}

private fun loadChatHistoryForGroup(groupId: String): ChatHistory? {
    val sessionName = "group_$groupId"
    return historyBaseDirs.asSequence()
        .mapNotNull { base -> loadNonEmptyChatHistory(base, sessionName) }
        .firstOrNull()
}

private fun loadNonEmptyChatHistory(base: String, sessionName: String): ChatHistory? {
    val historyFile = File(File(base, sessionName), "chat_history.json")
    if (!historyFile.isFile || historyFile.length() == 0L) return null
    return ChatHistoryManager(base)
        .loadChatHistory(sessionName)
        ?.takeIf { it.messages.isNotEmpty() }
}

private fun isContentMessage(msgType: String): Boolean {
    val t = msgType.trim().uppercase()
    return t == "TEXT" || t == "FILE"
}

internal fun collectGroupSlices(groupIdToName: List<Pair<String, String>>): List<GroupSlice> =
    groupIdToName.mapNotNull { (groupId, groupName) ->
        val messages = loadChatHistoryForGroup(groupId)
            ?.messages
            ?.filter { isContentMessage(it.messageType) }
            ?.filter { it.content.isNotBlank() }
            ?.map { Triple(it.senderName, it.content.trim(), it.timestamp) }
            ?.takeLast(MAX_MESSAGES_PER_GROUP)
            .orEmpty()
        messages
            .takeIf { it.isNotEmpty() }
            ?.let { GroupSlice(groupId, groupName, it) }
    }

internal fun buildTranscriptString(slices: List<GroupSlice>): String {
    val sb = StringBuilder()
    for (slice in slices) {
        appendTranscriptSlice(sb, slice)
        if (sb.length >= MAX_TRANSCRIPT_CHARS) {
            return sb.toString().trim()
        }
    }
    return sb.toString().trim()
}

private fun appendTranscriptSlice(sb: StringBuilder, slice: GroupSlice) {
    sb.appendLine("=== 群：${slice.groupName} (id=${slice.groupId}) ===")
    for ((sender, content, _) in slice.messages) {
        val reachedLimit = appendTranscriptLines(sb, sender, content)
        if (reachedLimit) return
    }
    sb.appendLine()
}

private fun appendTranscriptLines(
    sb: StringBuilder,
    sender: String,
    content: String
): Boolean {
    for (normalizedLine in content.lineSequence().mapNotNull(::normalizeTranscriptLine)) {
        sb.appendLine("[$sender]: $normalizedLine")
        if (sb.length >= MAX_TRANSCRIPT_CHARS) return true
    }
    return false
}

private fun normalizeTranscriptLine(line: String): String? {
    val normalized = line.trim().replace("\r", "")
    return normalized.ifEmpty { null }
}

private fun isLikelyAssistantSender(sender: String): Boolean {
    val s = sender.trim()
    if (s.isEmpty()) return false
    if (s.contains("silk", ignoreCase = true)) return true
    if (s.contains("🤖")) return true
    if (s.contains("助手") && s.length <= 12) return true
    return false
}

internal fun heuristicFromSlices(slices: List<GroupSlice>): List<ExtractedTodoDraft> {
    val out = mutableListOf<ExtractedTodoDraft>()
    val checklist = Regex("""^\s*[-*•]\s+\[[ xX]\]\s*(.+)$""")
    val alarmCue = Regex("""(提醒|闹钟|叫醒|起床|几点).{0,80}""")

    for (slice in slices) {
        collectHeuristicDraftsForSlice(slice, checklist, alarmCue, out)
    }
    return dedupeDrafts(out)
}

private fun collectHeuristicDraftsForSlice(
    slice: GroupSlice,
    checklist: Regex,
    alarmCue: Regex,
    out: MutableList<ExtractedTodoDraft>
) {
    slice.messages
        .asSequence()
        .filterNot { (sender, _, _) -> isLikelyAssistantSender(sender) }
        .flatMap { (_, raw, ts) ->
            val collector = HeuristicDraftCollector()
            raw.lineSequence()
                .mapNotNull { line ->
                    val heuristicLine = heuristicCandidateLine(line, checklist, alarmCue)
                    if (heuristicLine != null && collector.accept(heuristicLine)) {
                        createHeuristicDraft(slice, heuristicLine, ts)
                    } else {
                        null
                    }
                }
        }
        .toCollection(out)
}

private fun heuristicCandidateLine(
    line: String,
    checklist: Regex,
    alarmCue: Regex
): HeuristicCandidateLine? {
    val text = line.trim()
    if (!isHeuristicCandidateLine(text)) return null

    val fromChecklist = checklist.find(text)?.groupValues?.getOrNull(1)?.trim()
    val isAlarmLine = alarmCue.containsMatchIn(text)
    val candidate = chooseHeuristicCandidate(text, fromChecklist, isAlarmLine) ?: return null
    return HeuristicCandidateLine(
        text = text,
        candidate = candidate,
        isAlarmLine = isAlarmLine,
        fromChecklist = fromChecklist != null
    )
}

private fun isHeuristicCandidateLine(text: String): Boolean {
    if (text.length !in 4..200) return false
    if (skipSmallTalk.matches(text)) return false
    if (text.startsWith("http://", true) || text.startsWith("https://", true)) return false
    return true
}

private fun chooseHeuristicCandidate(
    text: String,
    fromChecklist: String?,
    isAlarmLine: Boolean
): String? = when {
    fromChecklist != null && fromChecklist.length >= 2 -> fromChecklist
    isAlarmLine && text.length <= 80 -> text
    else -> null
}

private fun createHeuristicDraft(
    slice: GroupSlice,
    heuristicLine: HeuristicCandidateLine,
    timestamp: Long
): ExtractedTodoDraft {
    val hourMinute = extractRoughHourMinute(heuristicLine.text)
    return ExtractedTodoDraft(
        title = compactHeuristicTitle(heuristicLine.candidate),
        sourceGroupId = slice.groupId,
        sourceGroupName = slice.groupName,
        actionType = heuristicActionType(heuristicLine.isAlarmLine, hourMinute),
        actionDetail = hourMinute?.toTimeString(),
        evidenceAt = timestamp,
        explicitIntent = isExplicitTaskIntent(heuristicLine.text)
    )
}

private fun compactHeuristicTitle(candidate: String): String =
    if (candidate.length > 120) candidate.take(117) + "..." else candidate

private fun heuristicActionType(isAlarmLine: Boolean, hourMinute: Pair<Int, Int>?): String =
    if (isAlarmLine || hourMinute != null) "alarm" else "none"

internal fun extractRecurringTemplateDrafts(slices: List<GroupSlice>): Pair<List<ExtractedTodoDraft>, List<String>> {
    val out = mutableListOf<ExtractedTodoDraft>()
    val matchedLines = mutableListOf<String>()
    val workdayHabitCue = Regex("(工作日|每个?工作日|上班日).{0,40}(起床|吃药|提醒|闹钟)|((起床|吃药|提醒|闹钟).{0,40}(工作日|每个?工作日|上班日))")
    val anniversaryCue = Regex("(纪念日|周年|生日)")

    for (slice in slices) {
        collectRecurringDraftsForSlice(slice, workdayHabitCue, anniversaryCue, out, matchedLines)
    }
    return dedupeDrafts(out) to matchedLines.distinct()
}

private fun collectRecurringDraftsForSlice(
    slice: GroupSlice,
    workdayHabitCue: Regex,
    anniversaryCue: Regex,
    out: MutableList<ExtractedTodoDraft>,
    matchedLines: MutableList<String>
) {
    slice.messages
        .asSequence()
        .filterNot { (sender, _, _) -> isLikelyAssistantSender(sender) }
        .flatMap { (_, raw, ts) ->
            raw.lineSequence()
                .mapNotNull { line ->
                    val recurringLine = recurringTemplateLine(line, workdayHabitCue, anniversaryCue)
                    if (recurringLine != null) {
                        matchedLines.add(recurringLine.text.take(120))
                        createRecurringTemplateDraft(slice, recurringLine, ts)
                    } else {
                        null
                    }
                }
        }
        .toCollection(out)
}

private fun recurringTemplateLine(
    line: String,
    workdayHabitCue: Regex,
    anniversaryCue: Regex
): RecurringTemplateLine? {
    val text = line.trim()
    if (!isRecurringCandidateLine(text)) return null

    val isWorkdayHabit = workdayHabitCue.containsMatchIn(text)
    val isAnniversary = anniversaryCue.containsMatchIn(text)
    if (!isWorkdayHabit && !isAnniversary) return null
    return RecurringTemplateLine(text, isWorkdayHabit)
}

private fun isRecurringCandidateLine(text: String): Boolean {
    if (text.length !in 3..200) return false
    if (skipSmallTalk.matches(text)) return false
    if (text.startsWith("http://", true) || text.startsWith("https://", true)) return false
    return true
}

private fun createRecurringTemplateDraft(
    slice: GroupSlice,
    recurringLine: RecurringTemplateLine,
    timestamp: Long
): ExtractedTodoDraft {
    val hm = extractRoughHourMinute(recurringLine.text)
    val repeatAnchor = hm?.toTimeString()
    return ExtractedTodoDraft(
        title = compactRecurringTitle(recurringLine.text),
        sourceGroupId = slice.groupId,
        sourceGroupName = slice.groupName,
        actionType = if (hm != null) "alarm" else "none",
        actionDetail = repeatAnchor,
        taskKind = "long_term_template",
        repeatRule = if (recurringLine.isWorkdayHabit) "workday" else "yearly",
        repeatAnchor = if (recurringLine.isWorkdayHabit) repeatAnchor else null,
        evidenceAt = timestamp,
        explicitIntent = true
    )
}

private fun compactRecurringTitle(text: String): String =
    if (text.length > 80) text.take(77) + "..." else text

private fun Pair<Int, Int>.toTimeString(): String = "%02d:%02d".format(first, second)

private fun isExplicitTaskIntent(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return false
    val explicit = Regex("(请|帮我|安排|提醒|闹钟|开会|准备|提交|截止|deadline|纪念日|每周|每月|每年|工作日|吃药|起床)")
    return explicit.containsMatchIn(t)
}

private fun classifyTaskKind(title: String): Triple<String, String?, String?> {
    val t = title.trim()
    if (t.isEmpty()) return Triple("short_term_instance", null, null)
    if (Regex("(纪念日|周年|生日)").containsMatchIn(t)) {
        val md = Regex("(\\d{1,2})\\s*[-月]\\s*(\\d{1,2})").find(t)
        val anchor = md?.let(::toMonthDayAnchor)
        return Triple("long_term_template", "yearly", anchor)
    }
    if (Regex("(工作日).*(起床|吃药|提醒)|((起床|吃药).*(工作日))").containsMatchIn(t)) {
        val hm = extractRoughHourMinute(t)
        val anchor = hm?.let { "%02d:%02d".format(it.first, it.second) }
        return Triple("long_term_template", "workday", anchor)
    }
    return Triple("short_term_instance", null, null)
}

private fun toMonthDayAnchor(match: MatchResult): String? {
    val month = match.groupValues[1].toIntOrNull()
    val day = match.groupValues[2].toIntOrNull()
    if (month !in 1..12 || day !in 1..31) {
        return null
    }
    return "%02d-%02d".format(month, day)
}

internal fun normalizeDraftsWithKind(list: List<ExtractedTodoDraft>, fallbackTs: Long): List<ExtractedTodoDraft> {
    val out = mutableListOf<ExtractedTodoDraft>()
    for (d in list) {
        val (kind, rule, anchor) = classifyTaskKind(d.title)
        val eAt = if (d.evidenceAt > 0L) d.evidenceAt else fallbackTs
        val existingAnchor = d.repeatAnchor?.trim()?.ifBlank { null }
        val existingRule = d.repeatRule?.trim()?.lowercase()?.ifBlank { null }
        out.add(
            d.copy(
                taskKind = kind,
                repeatRule = existingRule ?: rule,
                repeatAnchor = existingAnchor ?: anchor,
                evidenceAt = eAt
            )
        )
    }
    return out
}

private fun extractRoughHourMinute(text: String): Pair<Int, Int>? {
    val t = text.trim()
    val pm = t.contains("下午") || t.contains("晚上") || t.contains("傍晚")
    parseChineseHalfHour(t, pm)?.let { return it }
    parseChineseExactHour(t, pm)?.let { return it }
    parseHourMinute(t)?.let { return it }
    parseArabicHour(t, pm)?.let { return it }
    return null
}

private fun parseChineseHalfHour(text: String, pm: Boolean): Pair<Int, Int>? {
    val match = Regex("([一二三四五六七八九十两零]+点半)").find(text) ?: return null
    val hour = parseCnHourSimple(match.groupValues[1].replace("点半", "")) ?: return null
    return buildHourMinute(adjustPmHour(hour, pm), 30)
}

private fun parseChineseExactHour(text: String, pm: Boolean): Pair<Int, Int>? {
    val match = Regex("([一二三四五六七八九十两零]+点)").find(text) ?: return null
    val hour = parseCnHourSimple(match.groupValues[1].replace("点", "")) ?: return null
    return buildHourMinute(adjustPmHour(hour, pm), 0)
}

private fun parseHourMinute(text: String): Pair<Int, Int>? {
    val match = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""").find(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return buildHourMinute(hour, minute)
}

private fun parseArabicHour(text: String, pm: Boolean): Pair<Int, Int>? {
    val match = Regex("""(\d{1,2})\s*点""").find(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = if (text.contains("半")) 30 else 0
    return buildHourMinute(adjustPmHour(hour, pm), minute)
}

private fun adjustPmHour(hour: Int, pm: Boolean): Int {
    return if (pm && hour in 1..11) hour + 12 else hour
}

private fun buildHourMinute(hour: Int, minute: Int): Pair<Int, Int>? {
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

private fun parseCnHourSimple(cn: String): Int? {
    val map = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10, '两' to 2, '零' to 0)
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

internal fun dedupeDrafts(list: List<ExtractedTodoDraft>): List<ExtractedTodoDraft> {
    val pickedByKey = linkedMapOf<String, ExtractedTodoDraft>()
    for (d in list) {
        if (isDedupableDraft(d)) {
            val key = draftDedupKey(d)
            val existing = pickedByKey[key]
            pickedByKey[key] = existing?.let { preferredDraft(it, d) } ?: d
        }
    }
    return pickedByKey.values.take(MAX_TODOS_PER_REFRESH)
}

private fun isDedupableDraft(draft: ExtractedTodoDraft): Boolean = draft.title.trim().length >= 2

private fun draftDedupKey(draft: ExtractedTodoDraft): String = UserTodoStore.logicalDedupKey(
    draft.title,
    draft.actionType,
    draft.actionDetail,
    draft.taskKind
)

private fun preferredDraft(
    existing: ExtractedTodoDraft,
    incoming: ExtractedTodoDraft
): ExtractedTodoDraft = when {
    prefersLongTermTemplate(existing, incoming) -> incoming
    prefersLongTermTemplate(incoming, existing) -> existing
    !existing.explicitIntent && incoming.explicitIntent -> incoming
    existing.explicitIntent && !incoming.explicitIntent -> existing
    existing.actionDetail.orEmpty().length < incoming.actionDetail.orEmpty().length -> incoming
    else -> existing
}

private fun prefersLongTermTemplate(
    current: ExtractedTodoDraft,
    candidate: ExtractedTodoDraft
): Boolean = current.taskKind != "long_term_template" && candidate.taskKind == "long_term_template"

private class HeuristicDraftCollector {
    private var usedAlarmLineFallback = false

    fun accept(line: HeuristicCandidateLine): Boolean {
        if (line.fromChecklist) return true
        if (!line.isAlarmLine || usedAlarmLineFallback) return false
        usedAlarmLineFallback = true
        return true
    }
}

private fun callLlm(system: String, user: String, apiKey: String, temperature: Double = 0.35): String {
    val body = SimpleChatRequest(
        model = AIConfig.MODEL.ifBlank { "gpt-4o-mini" },
        messages = listOf(SimpleMsg("system", system), SimpleMsg("user", user)),
        temperature = temperature,
        maxTokens = 2048,
        stream = false
    )
    val req = HttpRequest.newBuilder()
        .uri(URI.create("${AIConfig.requireApiBaseUrl().trimEnd('/')}/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $apiKey")
        .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
        .POST(HttpRequest.BodyPublishers.ofString(groupTodoJson.encodeToString(SimpleChatRequest.serializer(), body)))
        .build()
    val resp = groupTodoHttpClient.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() != 200) {
        error("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
    }
    val root = groupTodoJson.parseToJsonElement(resp.body()).jsonObject
    val choices = root["choices"]?.jsonArray ?: return ""
    val first = choices.firstOrNull()?.jsonObject ?: return ""
    val message = first["message"]?.jsonObject ?: return ""
    return message["content"]?.jsonPrimitive?.content ?: ""
}

internal fun parseTodoJsonStrict(raw: String): Pair<List<ExtractedTodoDraft>, Boolean> {
    val slice = extractJsonObject(raw) ?: return emptyList<ExtractedTodoDraft>() to false
    return try {
        val obj = groupTodoJson.parseToJsonElement(slice).jsonObject
        if (!obj.containsKey("todos")) return emptyList<ExtractedTodoDraft>() to false
        val arr = obj["todos"]?.jsonArray ?: return emptyList<ExtractedTodoDraft>() to false
        val out = mutableListOf<ExtractedTodoDraft>()
        for (el in arr) {
            val o = el.jsonObject
            val title = o["title"]?.jsonPrimitive?.content?.trim() ?: continue
            val gid = o["sourceGroupId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            val gname = o["sourceGroupName"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            val at = o["actionType"]?.jsonPrimitive?.content?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() && it != "null" }
            val ad = o["actionDetail"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
            val tk = o["taskKind"]?.jsonPrimitive?.content?.trim()?.lowercase()
                ?.takeIf { it == "long_term_template" || it == "short_term_instance" }
                ?: "short_term_instance"
            val rr = o["repeatRule"]?.jsonPrimitive?.content?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() }
            val ra = o["repeatAnchor"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
            out.add(
                ExtractedTodoDraft(
                    title = title,
                    sourceGroupId = gid,
                    sourceGroupName = gname,
                    actionType = at,
                    actionDetail = ad,
                    taskKind = tk,
                    repeatRule = rr,
                    repeatAnchor = ra
                )
            )
        }
        out to true
    } catch (e: SerializationException) {
        println("⚠️ [GroupTodoExtractionService] JSON 解析失败: ${e.message}")
        emptyList<ExtractedTodoDraft>() to false
    } catch (e: IllegalArgumentException) {
        println("⚠️ [GroupTodoExtractionService] JSON 解析失败: ${e.message}")
        emptyList<ExtractedTodoDraft>() to false
    }
}

internal fun callLlmOrNull(
    system: String,
    user: String,
    apiKey: String,
    temperature: Double = 0.35,
    failurePrefix: String
): String? = try {
    callLlm(system, user, apiKey, temperature)
} catch (e: CancellationException) {
    throw e
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    println("$failurePrefix: ${e.message}")
    null
} catch (e: IOException) {
    println("$failurePrefix: ${e.message}")
    null
} catch (e: SerializationException) {
    println("$failurePrefix: ${e.message}")
    null
} catch (e: IllegalArgumentException) {
    println("$failurePrefix: ${e.message}")
    null
} catch (e: IllegalStateException) {
    println("$failurePrefix: ${e.message}")
    null
} catch (e: SecurityException) {
    println("$failurePrefix: ${e.message}")
    null
}

private fun extractJsonObject(text: String): String? {
    val t = text.trim()
    val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(t)
    if (fence != null) {
        val inner = fence.groupValues[1].trim()
        if (inner.startsWith("{")) return inner
    }
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start >= 0 && end > start) return t.substring(start, end + 1)
    return null
}
