package com.silk.backend.pdf

import com.silk.backend.ai.AIStepwiseAgent
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.properties.AreaBreakType
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * PDF 报告生成器
 * 使用 iText 库生成格式化的诊断报告 PDF
 * 支持中文字体
 */
class PDFReportGenerator {

    private val logger = LoggerFactory.getLogger(PDFReportGenerator::class.java)
    
    /**
     * 生成诊断报告 PDF
     * 
     * @param diagnosisResult 诊断结果
     * @param sessionName 会话名称（群组ID，格式：group_<uuid>）
     * @param patientInfo 患者信息（从聊天历史提取）
     * @param userName 用户名称
     * @param summaryReportText 文字版总结报告（与聊天室显示内容一致）
     * @param groupDisplayName 群组显示名称（可选，如果提供则用于标题）
     * @return Pair<PDF文件路径, 下载URL>
     */
    fun generateDiagnosisReportPDF(
        diagnosisResult: AIStepwiseAgent.DiagnosisResult,
        sessionName: String,
        patientInfo: String,
        userName: String = "用户",
        summaryReportText: String = "",
        groupDisplayName: String? = null
    ): Pair<String, String> {
        // 创建 PDF 保存目录
        val pdfDir = File("chat_history/$sessionName/reports")
        pdfDir.mkdirs()
        
        // ✅ 生成文件名：完全避免空格和特殊字符
        val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))  // ✅ 使用下划线代替空格
        
        // 使用群组显示名称作为文件名的一部分
        val reportTitle = groupDisplayName ?: sessionName
        
        // ✅ 清理文件名：移除所有特殊字符，空格也替换为下划线
        val safeTitleName = reportTitle
            .replace(Regex("[/\\\\:*?\"<>|']"), "_")  // 替换文件系统不安全的字符
            .replace(Regex("\\s+"), "_")  // ✅ 所有空格替换为下划线
            .replace(Regex("[^\\w\\u4e00-\\u9fa5_-]"), "_")  // 只保留字母、数字、中文、下划线、连字符
            .replace(Regex("_{2,}"), "_")  // 合并连续的下划线
            .trim('_')  // 去除首尾下划线
        
        // 文件名格式：群组名称_日期_时间.pdf（完全无空格）
        val fileName = "${safeTitleName}_$dateTime.pdf"
        logger.debug("📋 生成的文件名: '{}' (长度: {})", fileName, fileName.length)
        
        val pdfPath = "${pdfDir.path}/$fileName"
        
        var document: Document? = null

        val reportResult = runCatching {
            // 创建 PDF 文档
            val writer = PdfWriter(pdfPath)
            val reportDocument = Document(PdfDocument(writer))
            document = reportDocument

            // ✅ 设置页面边距，增加可用宽度（默认边距为36pt左右）
            // 将边距从默认36pt减少到20pt，增加页面可用宽度
            reportDocument.setMargins(20f, 20f, 20f, 20f)  // 上、右、下、左
            
            // ✅ 为此 PDF 文档创建独立的字体对象（避免跨文档重用）
            val chineseFont = createChineseFont()  // 中文字体
            logger.info("✅ 字体加载完成：中文字体")
            
            // 生成报告标题（包含群组名称和生成时间）
            val reportGeneratedTime = LocalDateTime.now()
            val reportTitle = groupDisplayName ?: sessionName
            
            logger.debug("📋 PDF生成开始: '{}'", reportTitle)
            logger.debug("📋 groupDisplayName: {}", groupDisplayName)
            logger.debug("📋 sessionName: {}", sessionName)
            logger.debug("📋 诊断步骤数: {}", diagnosisResult.stepResults.size)
            logger.debug("📋 总结报告长度: {}", summaryReportText.length)
            
            addReportHeader(reportDocument, reportTitle, reportGeneratedTime, chineseFont)
            
            // 第一部分：患者信息表格
            addPatientInfo(reportDocument, patientInfo, userName, sessionName, diagnosisResult, chineseFont)
            
            // 分页：患者信息 → 诊断步骤
            reportDocument.add(AreaBreak(AreaBreakType.NEXT_PAGE))
            
            // 第二部分：诊断步骤表格
            addDiagnosisStepsTable(reportDocument, diagnosisResult, chineseFont)
            
            // 分页：诊断步骤 → 总结报告
            if (summaryReportText.isNotEmpty() && summaryReportText.length > 100) {
                reportDocument.add(AreaBreak(AreaBreakType.NEXT_PAGE))
                
                // 第三部分：格式化的总结报告
                addSummaryReportSection(reportDocument, summaryReportText, chineseFont)
            }
            
            addReportFooter(reportDocument, chineseFont)
            
            // ✅ 正常关闭文档
            reportDocument.close()
            logger.info("✅ PDF文档已正确关闭")
            logger.info("✅ PDF 报告已生成并保存: {}", pdfPath)

            // 生成下载 URL（对文件名进行 URL 编码，处理中文和特殊字符）
            // 使用 URLEncoder 但替换 '+' 为 '%20'，因为在 URL 路径中空格应该是 %20 而不是 +
            val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
                .replace("+", "%20")  // URL 路径中空格应该是 %20
                .replace("%2F", "/")  // 恢复斜杠（如果有的话）
            val downloadUrl = "/download/report/$sessionName/$encodedFileName"

            Pair(pdfPath, downloadUrl)
        }

        return reportResult.getOrElse { generationFailure ->
            logger.error("❌ PDF 生成失败: {}", generationFailure.message, generationFailure)
            closeDocumentAfterFailure(document)
            throw IllegalStateException("PDF 生成失败：${generationFailure.message}", generationFailure)
        }
    }
}
