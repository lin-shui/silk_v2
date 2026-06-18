package com.silk.backend.pdf

import com.silk.backend.ChatHistoryManager
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.ai.AIStepwiseAgent
import com.silk.backend.database.GroupRepository
import org.slf4j.LoggerFactory

private val pdfExtractorLogger = LoggerFactory.getLogger(PDFReportGenerator::class.java)
private val commonChineseDiagnosisKeywords = listOf("不寐", "虚劳", "头痛")

private enum class DiagnosisSection {
    NONE,
    WESTERN,
    CHINESE
}

internal fun extractDoctorInstructions(sessionName: String): String {
    return runCatching {
        val groupId = if (sessionName.startsWith("group_")) {
            sessionName.removePrefix("group_")
        } else {
            return ""
        }

        val group = GroupRepository.findGroupById(groupId)
        val hostId = group?.hostId ?: return ""

        val historyManager = ChatHistoryManager()
        val chatHistory = historyManager.loadChatHistory(sessionName)
        val messages = chatHistory?.messages ?: return ""

        val doctorMessages = messages
            .filter { it.senderId == hostId }
            .filter { !it.content.startsWith("@诊断") && !it.content.startsWith("@diagnosis") }
            .sortedBy { it.timestamp }

        if (doctorMessages.isEmpty()) {
            return ""
        }

        doctorMessages.mapIndexed { index, msg ->
            val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(msg.timestamp))
            "${index + 1}. [$timeStr] ${msg.content}"
        }.joinToString("\n\n")
    }.getOrElse { extractionFailure ->
        pdfExtractorLogger.warn("⚠️ 提取医生指令失败: {}", extractionFailure.message)
        ""
    }
}

internal fun extractDiagnosisSummary(diagnosisResult: AIStepwiseAgent.DiagnosisResult): String {
    val diagnosisStep = diagnosisResult.stepResults["中西医疾病的诊断"]
    if (diagnosisStep == null || !diagnosisStep.success) {
        return ""
    }

    val fullText = diagnosisStep.result
    val westernDiseases = mutableListOf<String>()
    val chineseDiseases = mutableListOf<String>()

    var currentSection = DiagnosisSection.NONE
    for (line in fullText.lineSequence()) {
        val trimmed = line.trim()
        currentSection = detectDiagnosisSection(trimmed, currentSection)
        collectDiagnosisLine(trimmed, currentSection, westernDiseases, chineseDiseases)
    }

    return buildDiagnosisSummary(fullText, westernDiseases, chineseDiseases)
}

private fun detectDiagnosisSection(
    trimmed: String,
    currentSection: DiagnosisSection
): DiagnosisSection = when {
    trimmed.contains("西医诊断") || trimmed.contains("【西医") -> DiagnosisSection.WESTERN
    trimmed.contains("中医诊断") || trimmed.contains("【中医") -> DiagnosisSection.CHINESE
    else -> currentSection
}

private fun collectDiagnosisLine(
    trimmed: String,
    currentSection: DiagnosisSection,
    westernDiseases: MutableList<String>,
    chineseDiseases: MutableList<String>
) {
    if (trimmed.isEmpty()) {
        return
    }

    val normalized = trimmed.removeDiagnosisListPrefix()
    when {
        currentSection == DiagnosisSection.WESTERN && shouldCollectWesternDiagnosis(trimmed) ->
            westernDiseases.add(normalized)
        currentSection == DiagnosisSection.CHINESE && shouldCollectChineseDiagnosis(trimmed) ->
            chineseDiseases.add(normalized)
    }
}

private fun shouldCollectWesternDiagnosis(trimmed: String): Boolean =
    hasDiagnosisLead(trimmed) || trimmed.contains("可能") || trimmed.contains("考虑")

private fun shouldCollectChineseDiagnosis(trimmed: String): Boolean =
    hasDiagnosisLead(trimmed) || commonChineseDiagnosisKeywords.any(trimmed::contains)

private fun hasDiagnosisLead(trimmed: String): Boolean =
    trimmed.matches(Regex(".*[：:].+")) || trimmed.matches(Regex("\\d+[.、].*"))

private fun String.removeDiagnosisListPrefix(): String =
    replace(Regex("^\\d+[.、]\\s*"), "")

private fun buildDiagnosisSummary(
    fullText: String,
    westernDiseases: List<String>,
    chineseDiseases: List<String>
): String = buildString {
    appendDiagnosisSection("【西医】", westernDiseases)
    if (isNotEmpty() && chineseDiseases.isNotEmpty()) {
        append("\n")
    }
    appendDiagnosisSection("【中医】", chineseDiseases)

    if (isEmpty()) {
        append(fullText.replace(Regex("\\s+"), " ").take(150))
        if (fullText.length > 150) {
            append("...")
        }
    }
}

private fun StringBuilder.appendDiagnosisSection(
    title: String,
    diseases: List<String>
) {
    if (diseases.isEmpty()) {
        return
    }

    val joined = diseases.joinToString("；")
    append(title)
    append(diseases.take(2).joinToString("；").take(100))
    if (diseases.size > 2 || joined.length > 100) {
        append("...")
    }
}

internal fun extractUserSymptomsFromHistory(sessionName: String): String {
    return runCatching {
        val historyManager = ChatHistoryManager()
        val chatHistory = historyManager.loadChatHistory(sessionName)
        val messages = chatHistory?.messages ?: return ""

        val userMessages = messages
            .filter { !AgentRuntime.isAgentUserId(it.senderId) }
            .filter { it.messageType == "TEXT" }
            .filter { !it.content.startsWith("@诊断") && !it.content.startsWith("@diagnosis") }
            .filter { it.content.isNotEmpty() && it.content.length >= 3 }
            .sortedBy { it.timestamp }

        if (userMessages.isEmpty()) {
            return "暂无症状描述"
        }

        userMessages.mapIndexed { index, msg ->
            val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(msg.timestamp))
            "${index + 1}. [$timeStr] ${msg.senderName}: ${msg.content}"
        }.take(5).joinToString("\n\n")
    }.getOrElse { extractionFailure ->
        pdfExtractorLogger.warn("⚠️ 提取用户症状失败: {}", extractionFailure.message)
        "提取症状失败"
    }
}
