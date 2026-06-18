package com.silk.backend.pdf

import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.LineSeparator
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.TextAlignment
import org.slf4j.LoggerFactory

private val pdfReportLogger = LoggerFactory.getLogger(PDFReportGenerator::class.java)

internal fun primaryColor() = DeviceRgb(25, 118, 210)

internal fun secondaryColor() = DeviceRgb(76, 175, 80)

internal fun headerBgColor() = DeviceRgb(245, 245, 245)

internal fun createChineseFont(): PdfFont {
    return runCatching {
        PdfFontFactory.createFont(
            "STSong-Light",
            "UniGB-UCS2-H",
            PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED
        )
    }.getOrElse { fontFailure ->
        pdfReportLogger.error("❌ 内置字体加载失败: {}", fontFailure.message, fontFailure)
        PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA)
    }
}

internal fun createMixedFontParagraph(
    text: String,
    chineseFont: PdfFont,
    fontSize: Float = 9f
): Paragraph {
    val cleanedText = sanitizeText(text)
    val paragraph = Paragraph(cleanedText)
        .setFont(chineseFont)
        .setFontSize(fontSize)

    return applyCharacterSpacing(paragraph, warnOnFailure = true)
}

internal fun applyCharacterSpacing(
    paragraph: Paragraph,
    warnOnFailure: Boolean = false
): Paragraph {
    runCatching {
        paragraph.setCharacterSpacing(1.2f)
    }.onFailure { spacingFailure ->
        if (warnOnFailure) {
            pdfReportLogger.warn("⚠️ 设置字符间距失败，使用默认值: {}", spacingFailure.message)
        }
    }
    return paragraph
}

internal fun closeDocumentAfterFailure(document: Document?) {
    runCatching {
        document?.close()
    }.onSuccess {
        pdfReportLogger.info("✅ 异常处理：文档已关闭")
    }.onFailure { closeFailure ->
        pdfReportLogger.warn("⚠️ 异常处理：关闭文档也失败: {}", closeFailure.message)
    }
}

internal fun sanitizeText(text: String): String {
    return text
        .replace(Regex("[\u0000-\u0008\u000B-\u000C\u000E-\u001F\u007F-\u009F]"), "")
        .replace(Regex("[\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]"), "")
        .replace(Regex("[\uE000-\uF8FF]"), "")
        .replace(Regex("[\uFE00-\uFE0F]"), "")
        .replace("\u2018", "'")
        .replace("\u2019", "'")
        .replace("\u201C", "\"")
        .replace("\u201D", "\"")
        .replace("\u2014", "-")
        .replace("\u2013", "-")
        .replace("\u2026", "...")
        .filter { char ->
            when {
                char.code in 0x4E00..0x9FFF -> true
                char.code in 0x3400..0x4DBF -> true
                char.code in 0xF900..0xFAFF -> true
                char.code in 0x3000..0x303F -> true
                char in 'a'..'z' || char in 'A'..'Z' -> true
                char in '0'..'9' -> true
                char.code in 0x0020..0x007E -> true
                char == '\n' || char == '\r' || char == '\t' -> true
                char.code in 0x1F300..0x1F6FF -> true
                else -> false
            }
        }
        .trim()
}

internal fun createHeaderCell(text: String, chineseFont: PdfFont): Cell {
    val paragraph = applyCharacterSpacing(
        Paragraph(sanitizeText(text))
            .setFont(chineseFont)
            .setBold()
            .setFontSize(9.5f)
    )

    return Cell()
        .add(paragraph)
        .setBackgroundColor(headerBgColor())
        .setTextAlignment(TextAlignment.CENTER)
        .setPadding(8f)
}

internal fun createCell(
    text: String,
    textColor: DeviceRgb? = null,
    chineseFont: PdfFont
): Cell {
    val paragraph = applyCharacterSpacing(
        Paragraph(sanitizeText(text))
            .setFont(chineseFont)
            .setFontSize(9f)
    )

    if (textColor != null) {
        paragraph.setFontColor(textColor).setBold()
    }

    return Cell()
        .add(paragraph)
        .setTextAlignment(TextAlignment.LEFT)
        .setPadding(8f)
}

internal fun createCellCentered(
    text: String,
    textColor: DeviceRgb? = null,
    chineseFont: PdfFont
): Cell {
    val paragraph = applyCharacterSpacing(
        Paragraph(sanitizeText(text))
            .setFont(chineseFont)
            .setFontSize(9f)
    )

    if (textColor != null) {
        paragraph.setFontColor(textColor).setBold()
    }

    return Cell()
        .add(paragraph)
        .setTextAlignment(TextAlignment.CENTER)
        .setPadding(8f)
}

internal fun addHorizontalLine(document: Document) {
    val line = LineSeparator(com.itextpdf.kernel.pdf.canvas.draw.SolidLine())
    line.setMarginTop(5f)
    line.setMarginBottom(5f)
    document.add(line)
}
