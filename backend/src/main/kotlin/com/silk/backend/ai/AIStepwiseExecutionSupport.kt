package com.silk.backend.ai

import org.slf4j.Logger

internal fun recordDiagnosisStepFailure(
    stepResults: MutableMap<String, AIStepwiseAgent.StepResult>,
    executionSummary: StringBuilder,
    stepNumber: Int,
    taskName: String,
    error: Throwable
): Boolean {
    stepResults[taskName] = AIStepwiseAgent.StepResult(
        stepName = taskName,
        result = "",
        success = false,
        error = error.message
    )
    executionSummary.append("❌ [$stepNumber] $taskName - 异常\n\n")
    return false
}

internal fun buildFailedDiagnosisStep(
    logger: Logger,
    taskName: String,
    partialResult: String,
    error: Throwable
): AIStepwiseAgent.StepResult {
    logger.error("❌ 步骤异常: $taskName - ${error.message}", error)

    return if (partialResult.isNotEmpty()) {
        logger.warn("⚠️ 步骤部分完成: $taskName (已接收 ${partialResult.length} 字符)")
        AIStepwiseAgent.StepResult(
            stepName = taskName,
            result = partialResult + "\n\n⚠️ 注意：此步骤因超时或异常而提前结束，以上为部分结果。",
            success = true,
            error = null
        )
    } else {
        logger.error("❌ 步骤完全失败: $taskName - 无数据返回")
        AIStepwiseAgent.StepResult(
            stepName = taskName,
            result = "",
            success = false,
            error = "步骤执行失败: ${error.message}"
        )
    }
}

internal fun updateDiagnosisAccumulatedInfo(
    currentInfo: String,
    taskName: String,
    stepResult: String
): String {
    val conclusion = extractDiagnosisConclusion(stepResult)
    return """$currentInfo

【$taskName 的结论】：
$conclusion
"""
}

private fun extractDiagnosisConclusion(fullResult: String): String {
    if (fullResult.length <= 300) {
        return fullResult
    }

    val keyLines = fullResult
        .split("\n")
        .filter { line ->
            line.contains("总结") ||
                line.contains("结论") ||
                line.contains("需求") ||
                line.contains("计划") ||
                line.startsWith("1.") ||
                line.startsWith("2.") ||
                line.startsWith("3.")
        }

    return if (keyLines.isNotEmpty()) {
        keyLines.take(5).joinToString("\n")
    } else {
        fullResult.take(300) + "..."
    }
}
