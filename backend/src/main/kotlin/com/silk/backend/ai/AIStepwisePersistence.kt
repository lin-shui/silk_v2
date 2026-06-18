package com.silk.backend.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.Logger
import java.io.IOException

internal fun loadPreviousDiagnosisResults(
    sessionName: String,
    logger: Logger
): Pair<Map<String, AIStepwiseAgent.StepResult>, Long>? {
    return try {
        val possiblePaths = listOf(
            "chat_history/$sessionName/last_diagnosis.json",
            "backend/chat_history/$sessionName/last_diagnosis.json"
        )

        val file = possiblePaths
            .map { java.io.File(it) }
            .firstOrNull { it.exists() }

        if (file != null && file.exists()) {
            logger.info("📖 正在加载诊断历史:")
            logger.info("   找到文件: ${file.absolutePath}")
            logger.info("   大小: ${file.length()} bytes")

            val json = file.readText()
            val jsonObject = Json.parseToJsonElement(json).jsonObject
            val timestamp = jsonObject["timestamp"]?.jsonPrimitive?.long ?: 0L
            val resultsObject = jsonObject["results"]?.jsonObject

            val results = mutableMapOf<String, AIStepwiseAgent.StepResult>()
            resultsObject?.forEach { (key, value) ->
                val obj = value.jsonObject
                results[key] = AIStepwiseAgent.StepResult(
                    stepName = obj["stepName"]?.jsonPrimitive?.content ?: "",
                    result = obj["result"]?.jsonPrimitive?.content?.replace("\\n", "\n") ?: "",
                    success = obj["success"]?.jsonPrimitive?.boolean ?: false
                )
            }

            val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(timestamp))
            logger.info("✅ 加载成功")
            logger.info("   诊断时间: $dateTime")
            logger.info("   步骤数量: ${results.size}")
            Pair(results, timestamp)
        } else {
            logger.info("ℹ️ 所有路径都不存在历史诊断文件")
            null
        }
    } catch (e: SerializationException) {
        logger.error("❌ 加载诊断历史失败: ${e.message}", e)
        null
    } catch (e: IOException) {
        logger.error("❌ 加载诊断历史失败: ${e.message}", e)
        null
    } catch (e: SecurityException) {
        logger.error("❌ 加载诊断历史失败: ${e.message}", e)
        null
    } catch (e: IllegalStateException) {
        logger.error("❌ 加载诊断历史失败: ${e.message}", e)
        null
    } catch (e: IllegalArgumentException) {
        logger.error("❌ 加载诊断历史失败: ${e.message}", e)
        null
    }
}

internal fun saveDiagnosisResults(
    sessionName: String,
    stepResults: Map<String, AIStepwiseAgent.StepResult>,
    logger: Logger
) {
    try {
        val file = java.io.File("backend/chat_history/$sessionName/last_diagnosis.json")

        logger.info("💾 正在保存诊断结果:")
        logger.info("   sessionName: $sessionName")
        logger.info("   文件路径: ${file.absolutePath}")
        logger.info("   步骤数量: ${stepResults.size}")

        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            val created = parentDir.mkdirs()
            logger.info("   创建目录: ${if (created) "成功" else "失败"}")
        } else {
            logger.info("   目录已存在: ${parentDir?.absolutePath}")
        }

        val timestamp = System.currentTimeMillis()
        val json = buildString {
            append("{\n")
            append("  \"timestamp\": $timestamp,\n")
            append("  \"results\": {\n")

            stepResults.entries.forEachIndexed { index, (key, value) ->
                append("    \"$key\": {\n")
                append("      \"stepName\": \"${value.stepName.replace("\"", "\\\"")}\",\n")
                append("      \"result\": \"${value.result.replace("\"", "\\\"").replace("\n", "\\n")}\",\n")
                append("      \"success\": ${value.success}\n")
                append("    }")
                if (index < stepResults.size - 1) {
                    append(",")
                }
                append("\n")
            }

            append("  }\n")
            append("}")
        }

        file.writeText(json)

        if (file.exists()) {
            val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(timestamp))
            logger.info("✅ 诊断结果已成功保存")
            logger.info("   诊断时间: $dateTime")
            logger.info("   文件大小: ${file.length()} bytes")
            logger.info("   包含步骤: ${stepResults.keys.joinToString(", ")}")
        } else {
            logger.error("❌ 文件保存后不存在！")
        }
    } catch (e: IOException) {
        logger.error("❌ 保存诊断结果失败: ${e.message}", e)
    } catch (e: SecurityException) {
        logger.error("❌ 保存诊断结果失败: ${e.message}", e)
    } catch (e: IllegalStateException) {
        logger.error("❌ 保存诊断结果失败: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        logger.error("❌ 保存诊断结果失败: ${e.message}", e)
    }
}
