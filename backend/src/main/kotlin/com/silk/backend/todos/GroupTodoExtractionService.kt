package com.silk.backend.todos

import com.silk.backend.ai.AIConfig
import com.silk.backend.database.GroupRepository
import com.silk.backend.database.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 从用户所属各群近期聊天记录中提取待办，并合并写入 [UserTodoStore]。
 *
 * - 多路径查找 `chat_history`（兼容从仓库根目录或 backend 子目录启动进程）
 * - LLM 抽取 + 启发式兜底（二选一，不叠加）；每次成功刷新用新列表**替换**未完成项（保留已完成），避免一事多条堆叠
 */
object GroupTodoExtractionService {
    data class ExtractionDiagnostics(
        val userId: String,
        val updatedAt: Long,
        val source: String,
        val totalGroups: Int,
        val transcriptChars: Int,
        val llmDraftCount: Int,
        val heuristicDraftCount: Int,
        val forcedRecurringCount: Int,
        val finalDraftCount: Int,
        val matchedRecurringLines: List<String>,
        val note: String
    )

    private val diagnosticsByUser = ConcurrentHashMap<String, ExtractionDiagnostics>()

    suspend fun refreshTodosForUser(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        val apiKey = AIConfig.API_KEY.trim()
        if (apiKey.isEmpty()) {
            println("ℹ️ [GroupTodoExtractionService] 跳过：未配置 OPENAI_API_KEY")
            recordRefreshSkip(userId, note = "未配置 OPENAI_API_KEY")
            return@withContext
        }
        val preparation = prepareRefreshPreparation(userId, apiKey) ?: return@withContext
        val selection = selectRefreshDrafts(userId, preparation, apiKey)
        applyRefreshDrafts(userId, selection)
        compactAndDedupeStoredTodos(userId, apiKey)
        diagnosticsByUser[userId] = buildRefreshDiagnostics(
            userId = userId,
            preparation = preparation,
            selection = selection,
        )
    }

    private fun recordRefreshSkip(
        userId: String,
        totalGroups: Int = 0,
        note: String,
    ) {
        diagnosticsByUser[userId] = ExtractionDiagnostics(
            userId = userId,
            updatedAt = System.currentTimeMillis(),
            source = "skip",
            totalGroups = totalGroups,
            transcriptChars = 0,
            llmDraftCount = 0,
            heuristicDraftCount = 0,
            forcedRecurringCount = 0,
            finalDraftCount = 0,
            matchedRecurringLines = emptyList(),
            note = note,
        )
    }

    private fun prepareRefreshPreparation(
        userId: String,
        apiKey: String,
    ): RefreshPreparation? {
        val user = UserRepository.findUserById(userId)
        val userName = user?.fullName?.ifBlank { user.loginName } ?: "用户"
        val groups = GroupRepository.getUserGroups(userId)
        if (groups.isEmpty()) {
            println("ℹ️ [GroupTodoExtractionService] 用户无群组，跳过")
            recordRefreshSkip(userId, note = "用户无群组")
            return null
        }

        val slices = collectGroupSlices(groups.map { it.id to it.name })
        if (slices.isEmpty()) {
            println(
                "ℹ️ [GroupTodoExtractionService] 未读到任何群消息文件；已尝试目录: chat_history, backend/chat_history, ../chat_history"
            )
            recordRefreshSkip(userId, totalGroups = groups.size, note = "未读到群消息文件")
            compactAndDedupeStoredTodos(userId, apiKey)
            return null
        }

        val transcript = buildTranscriptString(slices)
        val heuristicDrafts = heuristicFromSlices(slices)
        val latestEvidenceTs = slices.asSequence()
            .flatMap { it.messages.asSequence() }
            .map { it.third }
            .maxOrNull() ?: System.currentTimeMillis()
        println(
            "📋 [GroupTodoExtractionService] 摘录长度=${transcript.length}，群段=${slices.size}，启发式候选=${heuristicDrafts.size}"
        )
        return RefreshPreparation(
            userName = userName,
            sliceCount = slices.size,
            slices = slices,
            transcript = transcript,
            heuristicDrafts = heuristicDrafts,
            latestEvidenceTs = latestEvidenceTs,
        )
    }

    private fun selectRefreshDrafts(
        userId: String,
        preparation: RefreshPreparation,
        apiKey: String,
    ): RefreshDraftSelection {
        val raw = callLlmOrNull(
            system = buildRefreshSystemPrompt(),
            user = buildRefreshUserPrompt(preparation.userName, userId, preparation.transcript),
            apiKey = apiKey,
            temperature = 0.2,
            failurePrefix = "❌ [GroupTodoExtractionService] LLM 调用失败"
        )

        val parseResult = raw?.let(::parseTodoJsonStrict)
        val llmDrafts = parseResult?.first ?: emptyList()
        val jsonOk = parseResult?.second == true
        val llmOrParseFailed = raw == null || !jsonOk
        val sourceDrafts = selectPrimaryDrafts(
            llmDrafts = llmDrafts,
            llmOrParseFailed = llmOrParseFailed,
            heuristicDrafts = preparation.heuristicDrafts,
        )
        val (forcedRecurringDrafts, recurringLines) = extractRecurringTemplateDrafts(preparation.slices)
        val finalDrafts = normalizeDraftsWithKind(
            dedupeDrafts(sourceDrafts + forcedRecurringDrafts),
            preparation.latestEvidenceTs
        ).take(MAX_TODOS_PER_REFRESH)
        return RefreshDraftSelection(
            llmDrafts = llmDrafts,
            heuristicDraftCount = preparation.heuristicDrafts.size,
            forcedRecurringDrafts = forcedRecurringDrafts,
            recurringLines = recurringLines,
            finalDrafts = finalDrafts,
            jsonOk = jsonOk,
            llmOrParseFailed = llmOrParseFailed,
        )
    }

    private fun applyRefreshDrafts(
        userId: String,
        selection: RefreshDraftSelection,
    ) {
        if (!shouldReplaceUndone(selection)) {
            println("ℹ️ [GroupTodoExtractionService] 抽取失败且无启发式结果，保留原未完成待办（LLM/JSON 异常）")
            return
        }
        UserTodoStore.replaceUndoneWithExtracted(userId, selection.finalDrafts)
        println(
            "✅ [GroupTodoExtractionService] 已用本次抽取替换未完成待办 ${selection.finalDrafts.size} 条" +
                "（来源=${refreshSourceLabel(selection)}，jsonOk=${selection.jsonOk}）"
        )
    }

    private fun compactAndDedupeStoredTodos(userId: String, apiKey: String) {
        compactStoredTodosForUser(userId, apiKey)
        UserTodoStore.dedupeByLogicalKeyInPlace(userId)
    }

    private fun buildRefreshDiagnostics(
        userId: String,
        preparation: RefreshPreparation,
        selection: RefreshDraftSelection,
    ): ExtractionDiagnostics = ExtractionDiagnostics(
        userId = userId,
        updatedAt = System.currentTimeMillis(),
        source = when {
            selection.llmDrafts.isNotEmpty() -> "llm+forced_recurring"
            selection.llmOrParseFailed && selection.heuristicDraftCount > 0 -> "heuristic+forced_recurring"
            selection.forcedRecurringDrafts.isNotEmpty() -> "forced_recurring_only"
            else -> "none"
        },
        totalGroups = preparation.sliceCount,
        transcriptChars = preparation.transcript.length,
        llmDraftCount = selection.llmDrafts.size,
        heuristicDraftCount = selection.heuristicDraftCount,
        forcedRecurringCount = selection.forcedRecurringDrafts.size,
        finalDraftCount = selection.finalDrafts.size,
        matchedRecurringLines = selection.recurringLines.take(6),
        note = if (selection.jsonOk) "json_ok" else "json_not_ok_or_empty",
    )

    fun getDiagnostics(userId: String): ExtractionDiagnostics {
        return diagnosticsByUser[userId] ?: ExtractionDiagnostics(
            userId = userId,
            updatedAt = System.currentTimeMillis(),
            source = "none",
            totalGroups = 0,
            transcriptChars = 0,
            llmDraftCount = 0,
            heuristicDraftCount = 0,
            forcedRecurringCount = 0,
            finalDraftCount = 0,
            matchedRecurringLines = emptyList(),
            note = "暂无抽取记录"
        )
    }
}
