package com.silk.backend.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.silk.backend.ChatHistoryManager
import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.ai.AIStepwiseAgent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal fun addReportHeader(
    document: Document,
    groupTitle: String,
    generatedTime: LocalDateTime,
    chineseFont: PdfFont
) {
    val title = createMixedFontParagraph(groupTitle, chineseFont, 20f)
        .setBold()
        .setTextAlignment(TextAlignment.CENTER)
        .setFontColor(primaryColor())
        .setMarginBottom(8f)
    document.add(title)

    val formattedDateTime = generatedTime.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"))
    val subtitle = createMixedFontParagraph("诊断报告 - $formattedDateTime", chineseFont, 11f)
        .setTextAlignment(TextAlignment.CENTER)
        .setFontColor(ColorConstants.GRAY)
        .setMarginBottom(10f)
    document.add(subtitle)

    val systemInfo = createMixedFontParagraph("Silk AI Agent 智能诊断系统", chineseFont, 10f)
        .setTextAlignment(TextAlignment.CENTER)
        .setFontColor(ColorConstants.LIGHT_GRAY)
        .setMarginBottom(20f)
    document.add(systemInfo)

    addHorizontalLine(document)
}

internal fun addPatientInfo(
    document: Document,
    patientInfo: String,
    userName: String,
    sessionName: String,
    diagnosisResult: AIStepwiseAgent.DiagnosisResult,
    chineseFont: PdfFont
) {
    val sectionTitle = applyCharacterSpacing(
        Paragraph(sanitizeText("患者情况"))
            .setFont(chineseFont)
            .setFontSize(14f)
            .setBold()
            .setFontColor(primaryColor())
            .setMarginTop(10f)
            .setMarginBottom(10f)
    )
    document.add(sectionTitle)

    val table = Table(UnitValue.createPercentArray(floatArrayOf(30f, 70f)))
        .useAllAvailableWidth()
        .setMarginBottom(15f)

    val messageCount = patientInfo.lineSequence()
        .firstOrNull { it.contains("用户消息数:") }
        ?.substringAfter(":")
        ?.trim()
        ?.toIntOrNull()
        ?: 0

    val symptoms = extractUserSymptomsFromHistory(sessionName)
    val historyManager = ChatHistoryManager()
    val sessionData = historyManager.loadSessionData(sessionName)
    val participantNames = sessionData?.members
        ?.filter { !AgentRuntime.isAgentUserId(it.userId) }
        ?.map { it.userName }
        ?.distinct()
        ?.filter { it != userName }
        ?.joinToString("、")
        ?: "无其他参与者"
    val doctorInstructions = extractDoctorInstructions(sessionName)
    val diagnosisText = extractDiagnosisSummary(diagnosisResult)

    table.addHeaderCell(createHeaderCell("项目", chineseFont))
    table.addHeaderCell(createHeaderCell("内容", chineseFont))
    table.addCell(createCell("患者姓名", chineseFont = chineseFont))
    table.addCell(createCell(userName, chineseFont = chineseFont))
    table.addCell(createCell("就诊时间", chineseFont = chineseFont))
    table.addCell(
        createCell(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")),
            chineseFont = chineseFont
        )
    )
    table.addCell(createCell("参与人清单", chineseFont = chineseFont))
    table.addCell(createCell(participantNames, chineseFont = chineseFont))
    table.addCell(createCell("消息记录", chineseFont = chineseFont))
    table.addCell(createCell("$messageCount 条", chineseFont = chineseFont))

    if (symptoms.isNotEmpty()) {
        table.addCell(createCell("主诉症状", chineseFont = chineseFont))
        table.addCell(createCell(symptoms, chineseFont = chineseFont))
    }
    if (doctorInstructions.isNotEmpty()) {
        table.addCell(createCell("医生指令", chineseFont = chineseFont))
        table.addCell(createCell(doctorInstructions, chineseFont = chineseFont))
    }
    if (diagnosisText.isNotEmpty()) {
        table.addCell(createCell("疾病诊断", chineseFont = chineseFont))
        table.addCell(createCell(diagnosisText, chineseFont = chineseFont))
    }

    table.addCell(createCell("报告生成时间", chineseFont = chineseFont))
    table.addCell(
        createCell(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")),
            chineseFont = chineseFont
        )
    )
    document.add(table)
}

internal fun addDiagnosisStepsTable(
    document: Document,
    result: AIStepwiseAgent.DiagnosisResult,
    chineseFont: PdfFont
) {
    val sectionTitle = applyCharacterSpacing(
        Paragraph(sanitizeText("诊断详细过程"))
            .setFont(chineseFont)
            .setFontSize(14f)
            .setBold()
            .setFontColor(primaryColor())
            .setMarginTop(15f)
            .setMarginBottom(10f)
    )
    document.add(sectionTitle)

    val table = Table(UnitValue.createPercentArray(floatArrayOf(8f, 30f, 62f)))
        .useAllAvailableWidth()
        .setMarginBottom(20f)

    table.addHeaderCell(createHeaderCell("序号", chineseFont))
    table.addHeaderCell(createHeaderCell("诊断步骤", chineseFont))
    table.addHeaderCell(createHeaderCell("诊断结果", chineseFont))

    result.stepResults.entries.forEachIndexed { index, (stepName, stepResult) ->
        table.addCell(createCellCentered("${index + 1}", chineseFont = chineseFont))

        val nameCell = Cell()
            .add(createMixedFontParagraph(stepName, chineseFont, 9f).setBold())
            .setTextAlignment(TextAlignment.LEFT)
            .setPadding(8f)
            .setBackgroundColor(DeviceRgb(250, 250, 250))
        table.addCell(nameCell)

        val resultText = if (stepResult.success) {
            formatResultForTable(stepResult.result)
        } else {
            "执行失败：${stepResult.error ?: "未知错误"}"
        }

        val resultCell = Cell()
            .add(createMixedFontParagraph(resultText, chineseFont, 9f))
            .setTextAlignment(TextAlignment.LEFT)
            .setPadding(8f)

        if (!stepResult.success) {
            resultCell.setBackgroundColor(DeviceRgb(255, 245, 245))
        }
        table.addCell(resultCell)
    }

    document.add(table)
}

private fun formatResultForTable(text: String): String {
    return text
        .replace(Regex("\\n{3,}"), "\n\n")
        .replace(Regex("^\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("【|】|\\[|\\]"), "")
        .trim()
}

internal fun addSummaryReportSection(document: Document, summaryText: String, chineseFont: PdfFont) {
    val sectionTitle = applyCharacterSpacing(
        Paragraph(sanitizeText("诊断总结报告"))
            .setFont(chineseFont)
            .setFontSize(15f)
            .setBold()
            .setFontColor(primaryColor())
            .setMarginTop(20f)
            .setMarginBottom(15f)
    )
    document.add(sectionTitle)

    parseAndRenderFormattedText(document, summaryText, chineseFont)
    addDisclaimer(document, chineseFont)
}

private fun parseAndRenderFormattedText(document: Document, text: String, chineseFont: PdfFont) {
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (shouldSkipFormattedSummaryLine(trimmed)) {
            continue
        }

        when {
            trimmed.startsWith("####") -> {
                val point = extractWrappedSectionText(trimmed, "####").trim()
                val paragraph = createMixedFontParagraph(point, chineseFont, 9.5f)
                    .setBold()
                    .setFontColor(DeviceRgb(100, 100, 100))
                    .setMarginTop(8f)
                    .setMarginBottom(4f)
                document.add(paragraph)
            }

            trimmed.startsWith("###") -> {
                val subtitle = extractWrappedSectionText(trimmed, "###").trim()
                val paragraph = createMixedFontParagraph(subtitle, chineseFont, 10f)
                    .setBold()
                    .setFontColor(secondaryColor())
                    .setMarginTop(10f)
                    .setMarginBottom(6f)
                document.add(paragraph)
            }

            trimmed.startsWith("##") -> {
                val title = extractWrappedSectionText(trimmed, "##").trim()
                val paragraph = createMixedFontParagraph(title, chineseFont, 12f)
                    .setBold()
                    .setFontColor(primaryColor())
                    .setMarginTop(12f)
                    .setMarginBottom(8f)
                document.add(paragraph)
            }

            else -> {
                val paragraph = createMixedFontParagraph(trimmed, chineseFont)
                    .setMarginBottom(5f)
                    .setFirstLineIndent(20f)
                document.add(paragraph)
            }
        }
    }
}

private fun extractWrappedSectionText(text: String, marker: String): String {
    val withoutPrefix = text.removePrefix(marker)
    val endMarkerIndex = withoutPrefix.indexOf(marker)
    return if (endMarkerIndex >= 0) {
        withoutPrefix.substring(0, endMarkerIndex)
    } else {
        withoutPrefix
    }
}

private fun shouldSkipFormattedSummaryLine(trimmed: String): Boolean =
    trimmed.isEmpty() ||
        trimmed.startsWith("═") ||
        trimmed.startsWith("─") ||
        trimmed.contains("Silk AI Agent")

private fun addDisclaimer(document: Document, chineseFont: PdfFont) {
    val disclaimerText = """
        【免责声明】
        
        本诊断报告由 Silk AI Agent 基于患者症状描述自动生成，仅供医疗参考，不构成正式医疗建议。
        
        • 本报告不能替代专业医师的临床诊断
        • 所有治疗方案需在持证中医师指导下实施
        • 中药处方需要由专业中医师开具
        • 针灸、艾灸等治疗需要专业人员操作
        • 如有疑问，请及时就医咨询
        
        承山堂提醒您：生命健康至关重要，请谨慎对待！
    """.trimIndent()

    val disclaimer = createMixedFontParagraph(disclaimerText, chineseFont, 9f)
        .setFontColor(ColorConstants.GRAY)
        .setBackgroundColor(DeviceRgb(255, 248, 225))
        .setPadding(10f)
        .setMarginTop(15f)
    document.add(disclaimer)
}

internal fun addReportFooter(document: Document, chineseFont: PdfFont) {
    addHorizontalLine(document)
    val footer = createMixedFontParagraph(
        "本报告由 Silk AI Agent 自动生成 | 技术支持: DeepSeek AI | 承山堂中医诊所",
        chineseFont,
        9f
    )
        .setTextAlignment(TextAlignment.CENTER)
        .setFontColor(ColorConstants.GRAY)
        .setMarginTop(10f)
    document.add(footer)
}
