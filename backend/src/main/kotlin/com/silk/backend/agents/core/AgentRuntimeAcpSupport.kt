package com.silk.backend.agents.core

import com.silk.backend.Message
import com.silk.backend.agents.acp.AcpClient
import com.silk.backend.agents.acp.PermissionResponse
import com.silk.backend.agents.acp.SessionUpdateNotification
import com.silk.backend.card.CardReplyHandler
import com.silk.backend.card.CardReplyPayload
import com.silk.backend.card.CardReplyRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val editTools = setOf("Write", "Edit", "NotebookEdit")

internal fun agentRuntimeParseStructuredQuestions(questions: JsonArray?): List<StructuredQuestion>? =
    questions?.mapNotNull { element ->
        when (element) {
            is JsonObject -> {
                val question = element["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val header = element["header"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val options = element["options"]?.jsonArray?.mapNotNull optionLoop@{ optionElement ->
                    val optionObject = optionElement as? JsonObject ?: return@optionLoop null
                    val label = optionObject["label"]?.jsonPrimitive?.contentOrNull ?: return@optionLoop null
                    val description = optionObject["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    QuestionOption(label = label, description = description)
                }.orEmpty()
                StructuredQuestion(question = question, header = header, options = options)
            }
            is JsonPrimitive -> element.contentOrNull?.let { StructuredQuestion(question = it) }
            else -> null
        }
    }

internal fun agentRuntimeParseCardReplyAction(
    reply: CardReplyPayload,
    pending: PendingQuestion,
): Pair<Int, String> {
    val action = reply.action
    return when {
        action.startsWith("__opt__") -> {
            val parts = action.removePrefix("__opt__").split("__", limit = 2)
            val questionIndex = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val answer = parts.getOrNull(1) ?: action
            questionIndex to answer
        }
        action.startsWith("__custom__") -> {
            val questionIndex = action.removePrefix("__custom__").toIntOrNull() ?: 0
            val answer = reply.inputs["custom_answer_$questionIndex"] ?: ""
            questionIndex to answer
        }
        else -> {
            val currentIdx = (0 until pending.questions.size).firstOrNull { it !in pending.answers } ?: 0
            currentIdx to action
        }
    }
}

internal fun agentRuntimeSetupAcpHandlers(
    acp: AcpClient,
    acpSessionId: String,
    session: AgentSession,
    descriptor: AgentDescriptor,
    broadcastFn: suspend (Message) -> Unit,
    accumulated: StringBuilder,
    scope: CoroutineScope,
    ctx: GroupAgentContext? = null,
) {
    acp.onSessionUpdate(acpSessionId) { notification ->
        val kind = notification.update["sessionUpdate"]?.let {
            (it as? JsonPrimitive)?.contentOrNull
        }
        if (kind == "permission_request") {
            agentRuntimeHandlePermissionUpdate(notification, session, descriptor, acp, broadcastFn, scope, ctx)
            return@onSessionUpdate
        }
        if (kind == "ask_user_question") {
            agentRuntimeHandleQuestionUpdate(notification, session)
        }
        if (kind == "plan_review") {
            agentRuntimeHandlePlanReviewUpdate(notification, descriptor, acp, broadcastFn, scope)
            return@onSessionUpdate
        }

        val msg = AcpUpdateMapper.map(
            update = notification.update,
            descriptor = descriptor,
            agentType = session.agentType,
            accumulated = accumulated,
        )
        if (msg != null) {
            scope.launch { broadcastFn(msg) }
        }
    }

    acp.onPermissionRequest(acpSessionId) {
        PermissionResponse(
            outcome = buildJsonObject {
                put("kind", "selected")
                put("optionId", "approve")
            },
        )
    }
}

private fun agentRuntimeHandleQuestionUpdate(
    notification: SessionUpdateNotification,
    session: AgentSession,
) {
    val requestId = notification.update["requestId"]?.let { (it as? JsonPrimitive)?.contentOrNull }
    val structuredQuestions = agentRuntimeParseStructuredQuestions(notification.update["questions"]?.jsonArray)
    if (requestId == null || structuredQuestions.isNullOrEmpty()) return
    session.pendingQuestion = PendingQuestion(requestId, structuredQuestions)
    agentRuntimeLogger.info(
        "[AgentRuntime] pendingQuestion set: requestId={}, questionCount={}",
        requestId.take(8),
        structuredQuestions.size,
    )
    val cardId = "agent_question_$requestId"
    CardReplyRouter.register(cardId, object : CardReplyHandler {
        override suspend fun onCardReply(
            reply: CardReplyPayload,
            sessionName: String,
            broadcastFn: suspend (Message) -> Unit,
        ) {
            val pending = session.pendingQuestion
            if (pending == null || pending.requestId != requestId) return
            val (questionIndex, answerText) = agentRuntimeParseCardReplyAction(reply, pending)
            agentRuntimeHandleQuestionReply(session, pending, questionIndex, answerText, broadcastFn)
        }
    })
}

private fun isWithinWorkingDir(filePath: String, workingDir: String): Boolean {
    val absFile = java.io.File(filePath).canonicalPath
    val absDir = java.io.File(workingDir).canonicalPath
    return absFile.startsWith(absDir + java.io.File.separator) || absFile == absDir
}

private fun extractFilePath(toolInput: JsonObject): String? =
    toolInput["file_path"]?.jsonPrimitive?.contentOrNull
        ?: toolInput["notebook_path"]?.jsonPrimitive?.contentOrNull

private fun extractToolInputMap(toolInput: JsonObject): Map<String, String?> =
    toolInput.entries.associate { (key, value) ->
        key to (value as? JsonPrimitive)?.contentOrNull
    }

private fun agentRuntimeHandlePermissionUpdate(
    notification: SessionUpdateNotification,
    session: AgentSession,
    descriptor: AgentDescriptor,
    acp: AcpClient,
    broadcastFn: suspend (Message) -> Unit,
    scope: CoroutineScope,
    ctx: GroupAgentContext?,
) {
    val requestId = notification.update["requestId"]?.jsonPrimitive?.contentOrNull ?: return
    val toolName = notification.update["toolName"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    val toolInput = notification.update["toolInput"]?.jsonObject ?: buildJsonObject {}
    val mode = session.permissionMode
    val workingDir = ctx?.workingDir ?: "/"
    val autoAllow = when (mode) {
        PermissionMode.BYPASS -> true
        PermissionMode.ACCEPT_EDITS -> {
            if (toolName in editTools) {
                val path = extractFilePath(toolInput)
                path != null && isWithinWorkingDir(path, workingDir)
            } else {
                false
            }
        }
        PermissionMode.INTERACTIVE -> false
    }
    if (autoAllow) {
        scope.launch {
            runAgentCatching { AcpExtensions.resolvePermission(acp, requestId, "allow") }
                .onSuccess {
                    agentRuntimeLogger.info("[AgentRuntime] Permission auto-allow: tool={}, mode={}", toolName, mode)
                }
                .onFailure { e ->
                    agentRuntimeLogger.error("[AgentRuntime] resolvePermission failed: {}", e.message)
                }
        }
        return
    }

    val detail = AgentMessages.formatToolDetail(toolName, extractToolInputMap(toolInput))
    val cardMsg = AgentMessages.permissionCard(
        requestId = requestId,
        toolName = toolName,
        toolDetail = detail,
        agentUserId = descriptor.agentUserId,
        agentName = descriptor.displayName,
    )
    val cardId = "agent_perm_$requestId"
    CardReplyRouter.register(cardId, object : CardReplyHandler {
        override suspend fun onCardReply(
            reply: CardReplyPayload,
            sessionName: String,
            broadcastFn: suspend (Message) -> Unit,
        ) {
            val action = reply.action
            val (decision, modeSwitch) = when {
                action.startsWith("perm_allow_") -> "allow" to null
                action.startsWith("perm_deny_") -> "deny" to null
                action.startsWith("perm_accept_edits_") -> "allow" to PermissionMode.ACCEPT_EDITS
                action.startsWith("perm_bypass_") -> "allow" to PermissionMode.BYPASS
                else -> return
            }
            CardReplyRouter.unregister(cardId)
            if (modeSwitch != null) {
                session.permissionMode = modeSwitch
                persistPermissionModeAsync(session.groupId, modeSwitch)
                agentRuntimeLogger.info(
                    "[AgentRuntime] Permission mode changed to {} for session {}",
                    modeSwitch,
                    session.groupId,
                )
            }

            val decisionText = when {
                modeSwitch == PermissionMode.ACCEPT_EDITS -> "允许（已切换到 Accept Edits 模式）"
                modeSwitch == PermissionMode.BYPASS -> "允许（已切换到 Bypass 模式）"
                decision == "allow" -> "允许"
                else -> "拒绝"
            }
            val reason = if (decision == "deny") "用户拒绝了此操作" else ""
            broadcastFn(
                AgentMessages.permissionCardResolved(
                    requestId = requestId,
                    toolName = toolName,
                    toolDetail = detail,
                    decision = decisionText,
                    approved = (decision == "allow"),
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ),
            )
            if (modeSwitch != null) {
                val modeLabel = when (modeSwitch) {
                    PermissionMode.ACCEPT_EDITS -> "Accept Edits"
                    PermissionMode.BYPASS -> "Bypass"
                    else -> modeSwitch.name
                }
                broadcastFn(
                    AgentMessages.system(
                        "已切换到 $modeLabel 权限模式",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ),
                )
            }

            runAgentCatching {
                AcpExtensions.resolvePermission(acp, requestId, decision, reason)
            }.onSuccess {
                agentRuntimeLogger.info("[AgentRuntime] Permission resolved: tool={}, decision={}", toolName, decision)
            }.onFailure { e ->
                agentRuntimeLogger.error("[AgentRuntime] resolvePermission failed: {}", e.message)
                broadcastFn(
                    AgentMessages.system(
                        "权限决定发送失败: ${e.message}",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ),
                )
            }
        }
    })
    scope.launch { broadcastFn(cardMsg) }
}

private fun agentRuntimeHandlePlanReviewUpdate(
    notification: SessionUpdateNotification,
    descriptor: AgentDescriptor,
    acp: AcpClient,
    broadcastFn: suspend (Message) -> Unit,
    scope: CoroutineScope,
) {
    val requestId = notification.update["requestId"]?.jsonPrimitive?.contentOrNull ?: return
    val planContent = notification.update["planContent"]?.jsonPrimitive?.contentOrNull ?: ""
    agentRuntimeLogger.info(
        "[AgentRuntime] Plan review received: requestId={}, planLen={}",
        requestId.take(8),
        planContent.length,
    )
    val cardMsg = AgentMessages.planReviewCard(
        requestId = requestId,
        planContent = planContent,
        agentUserId = descriptor.agentUserId,
        agentName = descriptor.displayName,
    )
    val cardId = "agent_plan_review_$requestId"
    CardReplyRouter.register(cardId, object : CardReplyHandler {
        override suspend fun onCardReply(
            reply: CardReplyPayload,
            sessionName: String,
            broadcastFn: suspend (Message) -> Unit,
        ) {
            val action = reply.action
            val (decision, feedback, decisionText) = when {
                action.startsWith("plan_allow_") -> Triple("allow", "", "批准执行")
                action.startsWith("plan_deny_feedback_") -> {
                    val feedback = reply.inputs["plan_feedback_$requestId"] ?: ""
                    Triple("deny_with_feedback", feedback, "拒绝并反馈: $feedback")
                }
                action.startsWith("plan_deny_") -> Triple("deny", "", "拒绝")
                else -> return
            }
            CardReplyRouter.unregister(cardId)
            broadcastFn(
                AgentMessages.planReviewCardResolved(
                    requestId = requestId,
                    decision = decisionText,
                    approved = (decision == "allow"),
                    agentUserId = descriptor.agentUserId,
                    agentName = descriptor.displayName,
                ),
            )
            runAgentCatching {
                AcpExtensions.resolvePlanReview(acp, requestId, decision, feedback)
            }.onSuccess {
                agentRuntimeLogger.info("[AgentRuntime] Plan review resolved: decision={}", decision)
            }.onFailure { e ->
                agentRuntimeLogger.error("[AgentRuntime] resolvePlanReview failed: {}", e.message)
                broadcastFn(
                    AgentMessages.system(
                        "计划审批决定发送失败: ${e.message}",
                        agentUserId = descriptor.agentUserId,
                        agentName = descriptor.displayName,
                    ),
                )
            }
        }
    })
    scope.launch { broadcastFn(cardMsg) }
}
