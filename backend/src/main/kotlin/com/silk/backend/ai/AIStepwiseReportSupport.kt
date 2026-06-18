package com.silk.backend.ai

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.io.IOException

internal suspend fun generateSummaryReport(
    stepResults: Map<String, AIStepwiseAgent.StepResult>,
    allSuccess: Boolean,
    hasApiKey: Boolean,
    logger: Logger,
    formatter: suspend (String) -> String
): String {
    val rawReport = buildRawSummaryReport(stepResults, allSuccess)
    if (!hasApiKey) {
        return generateFallbackReport(stepResults, allSuccess)
    }

    return try {
        formatter(rawReport)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.warn("⚠️ AI格式化失败，使用原始格式: ${e.message}", e)
        generateFallbackReport(stepResults, allSuccess)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.warn("⚠️ AI格式化失败，使用原始格式: ${e.message}", e)
        generateFallbackReport(stepResults, allSuccess)
    } catch (e: IllegalStateException) {
        logger.warn("⚠️ AI格式化失败，使用原始格式: ${e.message}", e)
        generateFallbackReport(stepResults, allSuccess)
    } catch (e: IllegalArgumentException) {
        logger.warn("⚠️ AI格式化失败，使用原始格式: ${e.message}", e)
        generateFallbackReport(stepResults, allSuccess)
    }
}

private fun buildRawSummaryReport(
    stepResults: Map<String, AIStepwiseAgent.StepResult>,
    allSuccess: Boolean
): String = buildString {
    append("执行状态: ${if (allSuccess) "全部成功" else "部分失败"}\n")
    append("完成步骤: ${stepResults.count { it.value.success }}/${stepResults.size}\n\n")
    append("各步骤总结：\n\n")

    stepResults.forEach { (taskName, result) ->
        if (result.success) {
            append("$taskName:\n")
            append("${result.result}\n\n")
        } else {
            append("$taskName: 执行失败\n")
            append("错误: ${result.error}\n\n")
        }
    }
}

internal fun generateFallbackReport(
    stepResults: Map<String, AIStepwiseAgent.StepResult>,
    allSuccess: Boolean
): String = buildString {
    appendFallbackReportHeader(stepResults, allSuccess)
    appendReportSection("##一、中西医诊断##", stepResults, "中西医疾病的诊断")
    append("##二、辨证分型与病因病机##\n\n")
    appendReportSection("###1. 辨证分型###", stepResults, "中医辨证分型")
    appendReportSection("###2. 病因病机###", stepResults, "中医的病因病机分析")
    appendReportSection("##三、体质诊断##", stepResults, "中医体质诊断")
    appendReportSection("##四、综合分析##", stepResults, "分析汇总")
    append("##五、治疗方案##\n\n")
    appendReportSection("###1. 中药处方###", stepResults, "中医处方建议")
    appendReportSection("###2. 中成药推荐###", stepResults, "推荐中成药")
    appendReportSection("###3. 针灸治疗###", stepResults, "针灸处方及针灸方法")
    appendReportSection("###4. 艾灸治疗###", stepResults, "艾灸选穴及艾灸方法")
    appendReportSection("###5. 生活调养###", stepResults, "饮食运动起居调养方案")
    appendReportSection("##六、预后说明##", stepResults, "预后说明")
}

private fun StringBuilder.appendFallbackReportHeader(
    stepResults: Map<String, AIStepwiseAgent.StepResult>,
    allSuccess: Boolean
) {
    append("##承山堂中医诊断总结报告##\n\n")
    append("###诊断执行状态###\n")
    append("执行结果: ${if (allSuccess) "✓ 全部成功" else "⚠ 部分失败"}\n")
    append("完成步骤: ${stepResults.count { it.value.success }}/${stepResults.size}\n\n")
}

private fun StringBuilder.appendReportSection(
    title: String,
    stepResults: Map<String, AIStepwiseAgent.StepResult>,
    stepKey: String
) {
    append("$title\n\n")
    appendSuccessfulStepResult(stepResults[stepKey])
}

private fun StringBuilder.appendSuccessfulStepResult(stepResult: AIStepwiseAgent.StepResult?) {
    if (stepResult != null && stepResult.success) {
        append("${stepResult.result}\n\n")
    }
}
