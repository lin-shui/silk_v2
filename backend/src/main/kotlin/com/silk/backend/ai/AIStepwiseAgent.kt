package com.silk.backend.ai

import com.silk.backend.models.ChatHistoryEntry
import com.silk.backend.pdf.PDFReportGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * AI 逐步执行代理
 * 类似于 MoxiTreat 的 DeepSeekDiagnosis.stepwise_diagnosis
 */
class AIStepwiseAgent(
    private val apiKey: String = AIConfig.API_KEY,
    private val sessionName: String = "default_room"
) {
    private val logger = LoggerFactory.getLogger(AIStepwiseAgent::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)  // 强制使用 HTTP/1.1，避免 HTTP/2 升级导致 vLLM 兼容问题
        .connectTimeout(Duration.ofMillis(AIConfig.TIMEOUT))
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    private val pdfGenerator = PDFReportGenerator()
    
    /**
     * 步骤执行结果
     */
    data class StepResult(
        val stepName: String,
        val result: String,
        val success: Boolean,
        val error: String? = null
    )
    
    /**
     * 诊断任务执行结果
     */
    data class DiagnosisResult(
        val patientContext: String,
        val stepResults: Map<String, StepResult>,
        val allSuccess: Boolean
    )
    
    /**
     * 步骤化执行诊断任务
     * 
     * @param chatHistory 聊天历史记录
     * @param callback 实时回调函数，用于发送进度消息到聊天室
     *                 参数：(stepType, message, currentStep, totalSteps)
     * @param userName 用户名（用于 PDF 文件命名）
     * @param groupDisplayName 群组显示名称（用于PDF标题和文件名）
     * @param hostId Host用户ID（用于区分医生和病人）
     * @return 诊断结果
     */
    suspend fun executeStepwiseDiagnosis(
        chatHistory: List<ChatHistoryEntry>,
        callback: suspend (stepType: String, message: String, currentStep: Int?, totalSteps: Int?) -> Unit,
        userName: String = "用户",
        groupDisplayName: String? = null,
        hostId: String? = null
    ): DiagnosisResult {
        // 1. 从聊天历史生成患者上下文（区分医生和病人）
        val patientContext = buildPatientContext(chatHistory, hostId)
        
        val totalSteps = AIConfig.TO_DO_LIST.size
        
        // 发送开始消息
        callback("开始", "🤖 Silk Agent 开始分析聊天历史...", null, totalSteps)
        delay(500)
        
        // 显示 To Do List
        val todoListMessage = buildString {
            append("📋 我将按以下步骤执行：\n")
            AIConfig.TO_DO_LIST.forEachIndexed { index, task ->
                append("${index + 1}. $task\n")
            }
        }
        callback("todo_list", todoListMessage, null, totalSteps)
        delay(1000)
        
        // 存储所有步骤的结果
        val stepResults = mutableMapOf<String, StepResult>()
        
        // 累积的信息（传递给下一步）
        var accumulatedInfo = patientContext
        var allSuccess = true
        
        // 累积的执行摘要（用于临时消息显示）
        val executionSummary = StringBuilder()
        executionSummary.append("📋 诊断执行进度\n")
        executionSummary.append("═".repeat(50) + "\n\n")
        
        // 2. 逐步执行 To Do List
        for ((index, taskName) in AIConfig.TO_DO_LIST.withIndex()) {
            val stepNumber = index + 1
            
            try {
                // ✅ 发送步骤开始消息（isIncremental=false，清空之前的累积内容）
                val stepStartMessage = buildString {
                    append("🔄 正在执行第 $stepNumber/$totalSteps 步：$taskName\n")
                    append("─".repeat(50) + "\n")
                    append("请稍候...\n")
                }
                callback("step_start", stepStartMessage, stepNumber, totalSteps)
                delay(300)
                
                // 执行当前步骤（带流式输出）
                val stepResult = executeDiagnosisStep(
                    taskName = taskName,
                    accumulatedInfo = accumulatedInfo,
                    streamingCallback = callback  // 传递 callback 用于流式输出
                )
                
                // 保存结果
                stepResults[taskName] = stepResult
                
                // ✅ 步骤完成后，发送完整的步骤结果（单独一条消息，可转发）
                if (stepResult.success) {
                    val stepCompleteMessage = buildString {
                        append("✅ 步骤 $stepNumber/$totalSteps：$taskName\n")
                        append("─".repeat(50) + "\n")
                        append(stepResult.result)
                    }
                    callback("step_complete", stepCompleteMessage, stepNumber, totalSteps)
                    
                    // 更新累积信息
                    accumulatedInfo = updateDiagnosisAccumulatedInfo(
                        accumulatedInfo,
                        taskName,
                        stepResult.result
                    )
                } else {
                    allSuccess = false
                    val stepFailMessage = buildString {
                        append("❌ 步骤 $stepNumber/$totalSteps：$taskName\n")
                        append("─".repeat(50) + "\n")
                        append("执行失败\n")
                    }
                    callback("step_complete", stepFailMessage, stepNumber, totalSteps)
                }
                
                // 短暂延迟，避免 API 请求过快
                delay(800)
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                allSuccess = recordDiagnosisStepFailure(stepResults, executionSummary, stepNumber, taskName, e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                allSuccess = recordDiagnosisStepFailure(stepResults, executionSummary, stepNumber, taskName, e)
            } catch (e: IllegalStateException) {
                allSuccess = recordDiagnosisStepFailure(stepResults, executionSummary, stepNumber, taskName, e)
            } catch (e: IllegalArgumentException) {
                allSuccess = recordDiagnosisStepFailure(stepResults, executionSummary, stepNumber, taskName, e)
            }
        }
        
        // 3. 生成总结报告（✅ 不发送到chat，只用于PDF生成）
        delay(200)
        // ✅ 生成总结报告，但不发送到chat（避免超长消息）
        val summaryReport = generateSummaryReport(stepResults, allSuccess, apiKey.isNotEmpty(), logger, ::formatReportWithAI)
        // ✅ 注释掉：不发送总结报告到chat，内容已在PDF中
        // callback("总结报告", summaryReport, null, null)
        
        // 4. 直接生成 PDF 报告（使用总结报告的内容）
        delay(500)
        try {
            val diagnosisResult = DiagnosisResult(
                patientContext = patientContext,
                stepResults = stepResults,
                allSuccess = allSuccess
            )
            
            // 将文字版总结报告也传递给 PDF 生成器，确保内容一致
            val (pdfPath, downloadUrl) = pdfGenerator.generateDiagnosisReportPDF(
                diagnosisResult = diagnosisResult,
                sessionName = sessionName,
                patientInfo = patientContext,
                userName = userName,
                summaryReportText = summaryReport,  // 传递总结报告文本
                groupDisplayName = groupDisplayName  // 传递群组显示名称
            )
            
            // ✅ 修改消息格式：不显示文件路径，只显示友好的文件名
            val displayFileName = pdfPath.substringAfterLast("/")
            val pdfMessage = buildString {
                append("📄 诊断报告已生成\n\n")
                append("文件名：$displayFileName\n\n")
                append("━".repeat(50) + "\n")
                append("📥 点击下方按钮下载完整报告\n")
                append("━".repeat(50) + "\n\n")
                // ✅ 不显示URL路径，改为在Android UI中处理下载
                append("$downloadUrl\n\n")
                append("💡 报告包含完整的诊断信息和建议")
            }
            
            callback("PDF报告", pdfMessage, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.error("⚠️ PDF 生成失败: ${e.message}", e)
            callback("PDF报告", "⚠️ PDF 生成失败：${e.message}\n\n文字版总结报告已在上方显示。", null, null)
        } catch (e: SecurityException) {
            logger.error("⚠️ PDF 生成失败: ${e.message}", e)
            callback("PDF报告", "⚠️ PDF 生成失败：${e.message}\n\n文字版总结报告已在上方显示。", null, null)
        } catch (e: IllegalStateException) {
            logger.error("⚠️ PDF 生成失败: ${e.message}", e)
            callback("PDF报告", "⚠️ PDF 生成失败：${e.message}\n\n文字版总结报告已在上方显示。", null, null)
        } catch (e: IllegalArgumentException) {
            logger.error("⚠️ PDF 生成失败: ${e.message}", e)
            callback("PDF报告", "⚠️ PDF 生成失败：${e.message}\n\n文字版总结报告已在上方显示。", null, null)
        }
        
        // 5. 发送完成消息
        delay(500)
        if (allSuccess) {
            callback("完成", "🎉 所有任务已完成！Silk Agent 执行完毕。\n诊断报告（文字版和 PDF 版）已生成。", null, null)
        } else {
            callback("完成", "⚠️ 任务执行完成，但部分步骤失败。请查看详细结果。", null, null)
        }
        
        // 保存诊断结果供医生更新时使用
        saveDiagnosisResults(sessionName, stepResults, logger)
        
        return DiagnosisResult(
            patientContext = patientContext,
            stepResults = stepResults,
            allSuccess = allSuccess
        )
    }

    /**
     * 执行单个诊断步骤
     * 
     * @param taskName 任务名称
     * @param accumulatedInfo 累积的上下文信息
     * @param streamingCallback 流式输出回调（用于实时显示AI输出）
     */
    private suspend fun executeDiagnosisStep(
        taskName: String,
        accumulatedInfo: String,
        streamingCallback: suspend (String, String, Int?, Int?) -> Unit
    ): StepResult {
        logger.info("🚀 开始执行步骤: $taskName")
        
        // 获取该步骤的具体提示词
        val taskPrompt = AIConfig.TO_DO_PROMPT_MAP[taskName] ?: ""
        
        // 构建完整的提示词
        val fullPrompt = """
${AIConfig.COMMON_PROMPT}

═══════════════════════════════════════
以下是完整的聊天历史和上下文信息：
═══════════════════════════════════════

$accumulatedInfo

═══════════════════════════════════════
当前需要执行的任务：
═══════════════════════════════════════

【任务名称】：$taskName

【任务要求】：
$taskPrompt

请基于以上聊天历史和已有的分析结论，完成当前任务。
"""
        
        // ✅ 移除日志输出以提升性能
        // logger.info("📝 [$taskName] Prompt: ${fullPrompt.length} 字符, 上下文: ${accumulatedInfo.length}, 任务: ${taskPrompt.length}, 预计tokens: ~${fullPrompt.length / 3}")
        
        // ✅ 完整prompt只在DEBUG级别打印
        if (logger.isDebugEnabled) {
            logger.debug("━".repeat(80))
            logger.debug("📄 [$taskName] 完整 Prompt：")
            logger.debug("━".repeat(80))
            logger.debug(fullPrompt)
            logger.debug("━".repeat(80))
        }
        
        logger.info("📤 [$taskName] 开始调用 AI API...")
        
        // 如果没有配置 API Key，返回基于上下文的离线结果
        if (apiKey.isEmpty()) {
            return StepResult(
                stepName = taskName,
                result = offlineDiagnosisResult(taskName, accumulatedInfo),
                success = true
            )
        }
        
        // 调用 AI API（流式输出，增量发送优化）
        var result = ""
        
        return try {
            val fullTextBuffer = StringBuilder()  // 完整文本累积
            val pendingChunks = StringBuilder()   // 待发送的块缓冲
            var lastSendTime = System.currentTimeMillis()
            var lastSentLength = 0  // ✅ 记录已发送的字符位置
            var sendCount = 0  // 统计发送次数
            var totalBytesSent = 0  // 统计总字节数
            
            // 使用流式API调用，带智能缓冲
            result = callStreamingDiagnosisApi(httpClient, json, apiKey, fullPrompt, logger) { chunk ->
                // 累积到完整文本
                fullTextBuffer.append(chunk)
                pendingChunks.append(chunk)
                
                val currentTime = System.currentTimeMillis()
                val timeSinceLastSend = currentTime - lastSendTime
                
                // ✅ 优化：累积3行（换行符）再发送，更频繁更新
                val newlineCount = pendingChunks.count { it == '\n' }
                val shouldSend = newlineCount >= 3 ||  // ✅ 改为3行更新一次
                                 pendingChunks.length >= 300 ||  // ✅ 减少到300字符
                                 timeSinceLastSend >= 1500  // ✅ 改为1.5秒兜底
                
                if (shouldSend && pendingChunks.isNotEmpty()) {
                    // ✅ 计算增量内容（只发送新增的部分）
                    val currentLength = fullTextBuffer.length
                    val incrementalText = fullTextBuffer.substring(lastSentLength, currentLength)
                    
                    // 构建增量消息（只包含新增内容）
                    val streamingMessage = incrementalText
                    
                    // 统计信息
                    sendCount++
                    val messageBytes = streamingMessage.toByteArray().size
                    totalBytesSent += messageBytes
                    
                    // ✅ 移除日志输出以提升性能
                    // if (sendCount % 20 == 1) {
                    //     logger.info("📤 [$taskName] 发送进度: 第 $sendCount 次, 累积 $currentLength 字符, 包含 $newlineCount 个换行")
                    // }
                    
                    // ✅ 以增量临时消息方式发送（isIncremental=true）
                    streamingCallback("streaming_incremental", streamingMessage, null, null)
                    
                    // 更新已发送位置
                    lastSentLength = currentLength
                    pendingChunks.clear()
                    lastSendTime = currentTime
                }
            }
            
            // 最后一次发送（确保所有内容都显示）
            if (pendingChunks.isNotEmpty()) {
                val incrementalText = fullTextBuffer.substring(lastSentLength)
                streamingCallback("streaming_incremental", incrementalText, null, null)
                
                sendCount++
                totalBytesSent += incrementalText.toByteArray().size
            }
            
            // ✅ 移除日志输出以提升性能
            // if (fullTextBuffer.isNotEmpty()) {
            //     val avgCharsPerSend = if (sendCount > 0) finalLength / sendCount else 0
            //     logger.info("✅ [$taskName] 流式输出完成: $finalLength 字符, 发送 $sendCount 次, 平均 $avgCharsPerSend 字符/次")
            // }
            
            // ✅ 移除重复的完成日志
            StepResult(
                stepName = taskName,
                result = result,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            buildFailedDiagnosisStep(logger, taskName, result, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            buildFailedDiagnosisStep(logger, taskName, result, e)
        } catch (e: IllegalStateException) {
            buildFailedDiagnosisStep(logger, taskName, result, e)
        } catch (e: IllegalArgumentException) {
            buildFailedDiagnosisStep(logger, taskName, result, e)
        }
    }
    
    /**
     * 调用 AI API（非流式）
     */
    private suspend fun callAIApi(prompt: String): String {
        val requestBody = ApiRequest(
            model = AIConfig.MODEL,
            messages = listOf(
                ApiMessage(role = "user", content = prompt)
            ),
            temperature = 0.7,
            maxTokens = 65536,  // ✅ 提升到65536，允许生成超详细的诊断内容
            stream = false
        )
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(AIConfig.requireApiBaseUrl()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
            .timeout(Duration.ofMillis(AIConfig.TIMEOUT))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        return if (response.statusCode() == 200) {
            val apiResponse = json.decodeFromString<ApiResponse>(response.body())
            apiResponse.choices.firstOrNull()?.message?.content 
                ?: "API 返回空结果"
        } else {
            error("API 调用失败：${response.statusCode()} - ${response.body()}")
        }
    }
    
    /**
     * 使用AI美化总结报告格式
     * 将报告结构化，添加章节标记
     */
    private suspend fun formatReportWithAI(rawReport: String): String {
        val formatPrompt = """
你是一位专业的医疗文档编辑专家。请将以下中医诊断报告整理成结构化、美观的格式。

【格式要求】：
1. 使用标记来标识不同层级：
   - ##章节标题## 用于主要章节（如"一、中西医诊断"）
   - ###小节标题### 用于子章节（如"1. 西医诊断"）
   - ####要点标题#### 用于要点（如"【病因】"）

2. 章节编排：
   - 一、中西医诊断
   - 二、辨证分型与病因病机
   - 三、体质诊断
   - 四、综合分析
   - 五、治疗方案
     - 中药处方
     - 中成药推荐
     - 针灸治疗
     - 艾灸治疗
     - 生活调养
   - 六、预后说明

3. 内容要求：
   - 删除技术性标记（如"✅"、"═"等）
   - 保持内容的专业性和准确性
   - 段落要清晰，层次分明
   - 每个章节内容要完整

【原始报告】：
$rawReport

请按照上述要求重新整理报告，使其更专业、更易读。只输出格式化后的报告内容，不要添加额外说明。
"""
        
        return callAIApi(formatPrompt)
    }
    
    /**
     * 处理医生诊断更新（Host角色的额外诊断）
     * 整合之前的诊断结果，添加医生医嘱，重新生成完整报告
     * 
     * @param chatHistory 聊天历史
     * @param doctorMessage 医生的诊断消息
     * @param callback 回调函数
     * @param userName 患者名称
     * @param groupDisplayName 群组显示名称
     */
    suspend fun processDoctorDiagnosisUpdate(
        chatHistory: List<ChatHistoryEntry>,
        doctorMessage: String,
        callback: suspend (stepType: String, message: String, currentStep: Int?, totalSteps: Int?) -> Unit,
        userName: String = "用户",
        groupDisplayName: String? = null
    ) {
        // 1. 发送处理开始消息
        callback("processing", "🩺 正在处理医生的诊断意见...", null, null)
        delay(500)
        
        // 2. 生成患者上下文（暂时不区分医生病人，因为医生更新时已经在医嘱中）
        val patientContext = buildPatientContext(chatHistory, null)
        
        // 3. 尝试加载之前的诊断结果和时间戳
        val previousDiagnosis = loadPreviousDiagnosisResults(sessionName, logger)
        val previousDiagnosisResults = previousDiagnosis?.first
        val lastDiagnosisTime = previousDiagnosis?.second ?: 0L
        
        // 4. 过滤聊天历史：只使用上次诊断之后的新消息
        val newMessages = filterMessagesAfterLastDiagnosis(chatHistory, lastDiagnosisTime, logger)
        
        // 5. 构建新消息的上下文
        val newMessagesContext = buildNewMessagesContext(newMessages)
        
        // 6. 构建之前诊断的摘要（✅ 优化：只取关键信息，减少token消耗）
        val previousDiagnosisSummary = buildPreviousDiagnosisSummary(previousDiagnosisResults)
        
        // ✅ 优化Prompt：更简洁直接，减少生成时间
        val systemPrompt = """
基于以下信息，快速生成更新的诊断报告：

$previousDiagnosisSummary

$newMessagesContext

【医生最新意见】
$doctorMessage

要求（简洁回答，不超过500字）：
1. 整合新信息更新诊断
2. 明确治疗方案调整
3. 给出关键注意事项

直接输出更新的诊断报告，使用清晰的分段格式。
        """.trimIndent()
        
        // 5. 调用AI模型（使用Streaming方式）
        callback("processing", "🩺 AI正在快速整合诊断信息...", null, 3)
        delay(100)  // ✅ 减少延迟
        
        val updatedDiagnosis = generateDoctorUpdateDiagnosis(systemPrompt, callback)
        
        // 6. 发送AI的诊断更新结果（最终消息）
        callback("doctor_update", updatedDiagnosis, 2, 3)
        delay(500)
        
        // 7. 整合所有步骤并重新生成PDF报告
        try {
            // 构建完整的步骤结果（医生医嘱 + 之前的诊断步骤）
            val stepResults = buildDoctorUpdateStepResults(
                doctorMessage = doctorMessage,
                previousDiagnosisResults = previousDiagnosisResults,
                updatedDiagnosis = updatedDiagnosis
            )
            
            val diagnosisResult = DiagnosisResult(
                patientContext = patientContext,
                stepResults = stepResults,
                allSuccess = true
            )
            
            // 生成PDF（文件名和标题使用当前时间）
            val (pdfPath, downloadUrl) = pdfGenerator.generateDiagnosisReportPDF(
                diagnosisResult = diagnosisResult,
                sessionName = sessionName,
                patientInfo = patientContext,
                userName = userName,
                summaryReportText = updatedDiagnosis,
                groupDisplayName = groupDisplayName
            )
            
            val pdfMessage = buildDoctorUpdatePdfMessage(pdfPath, downloadUrl)
            
            callback("PDF报告", pdfMessage, null, null)
            
            // 保存当前诊断结果供下次使用
            saveDiagnosisResults(sessionName, stepResults, logger)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.error("❌ 生成更新PDF失败: ${e.message}", e)
        } catch (e: SecurityException) {
            logger.error("❌ 生成更新PDF失败: ${e.message}", e)
        } catch (e: IllegalStateException) {
            logger.error("❌ 生成更新PDF失败: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            logger.error("❌ 生成更新PDF失败: ${e.message}", e)
        }
    }

    private suspend fun generateDoctorUpdateDiagnosis(
        systemPrompt: String,
        callback: suspend (stepType: String, message: String, currentStep: Int?, totalSteps: Int?) -> Unit
    ): String = try {
        val response = StringBuilder()
        val streamState = DoctorUpdateStreamState()

        generateQuickResponse(systemPrompt) { content, isComplete ->
            response.clear()
            response.append(content)

            if (!isComplete) {
                streamDoctorUpdateIncrement(content, streamState, callback)
            }
        }

        response.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        logger.error("❌ AI调用失败: ${e.message}", e)
        "⚠️ AI模型调用失败，无法更新诊断"
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.error("❌ AI调用失败: ${e.message}", e)
        "⚠️ AI模型调用失败，无法更新诊断"
    } catch (e: IllegalStateException) {
        logger.error("❌ AI调用失败: ${e.message}", e)
        "⚠️ AI模型调用失败，无法更新诊断"
    } catch (e: IllegalArgumentException) {
        logger.error("❌ AI调用失败: ${e.message}", e)
        "⚠️ AI模型调用失败，无法更新诊断"
    }

    private suspend fun streamDoctorUpdateIncrement(
        content: String,
        streamState: DoctorUpdateStreamState,
        callback: suspend (stepType: String, message: String, currentStep: Int?, totalSteps: Int?) -> Unit
    ) {
        val newContent = content.safeIncrementFrom(streamState.lastSentContent)
        val currentTime = System.currentTimeMillis()
        val timeSinceLastSend = currentTime - streamState.lastSentTime
        val newlineCount = newContent.count { it == '\n' }

        if (newContent.isEmpty() || (newlineCount < 3 && timeSinceLastSend < 2000)) {
            return
        }

        callback("streaming_incremental", newContent, 1, 3)
        streamState.lastSentContent = content
        streamState.lastSentTime = currentTime
    }

    /**
     * 生成快速响应（对话模式）
     * 使用streaming方式逐步输出
     * @param prompt AI prompt
     * @param callback 回调函数 (累积内容, 是否完成)
     */
    suspend fun generateQuickResponse(
        prompt: String,
        callback: suspend (content: String, isComplete: Boolean) -> Unit
    ) {
        try {
            val response = httpClient.send(
                buildQuickResponseRequest(prompt),
                HttpResponse.BodyHandlers.ofLines()
            )

            if (response.statusCode() == 200) {
                streamQuickResponseLines(response.body().toList(), json, callback)
            } else {
                logger.error("❌ AI API返回错误: ${response.statusCode()}")
                callback("⚠️ AI暂时无法回答，请稍后重试", true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.error("❌ 调用AI API异常: ${e.message}", e)
            callback("⚠️ AI暂时无法回答，请稍后重试", true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error("❌ 调用AI API异常: ${e.message}", e)
            callback("⚠️ AI暂时无法回答，请稍后重试", true)
        } catch (e: IllegalStateException) {
            logger.error("❌ 调用AI API异常: ${e.message}", e)
            callback("⚠️ AI暂时无法回答，请稍后重试", true)
        } catch (e: IllegalArgumentException) {
            logger.error("❌ 调用AI API异常: ${e.message}", e)
            callback("⚠️ AI暂时无法回答，请稍后重试", true)
        }
    }

    private fun buildQuickResponseRequest(prompt: String): HttpRequest {
        val requestBody = ApiRequest(
            model = AIConfig.MODEL,
            messages = listOf(ApiMessage(role = "user", content = prompt)),
            temperature = 0.7,
            maxTokens = 4096,
            stream = true
        )

        return HttpRequest.newBuilder()
            .uri(URI.create("${AIConfig.requireApiBaseUrl()}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofMillis(60000))
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
            .build()
    }

    private class DoctorUpdateStreamState(
        var lastSentContent: String = "",
        var lastSentTime: Long = System.currentTimeMillis()
    )
}

/**
 * API 请求模型
 */
@Serializable
data class ApiRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 65536,  // ✅ 默认值提升到65536，支持超详细的诊断报告
    val stream: Boolean = false  // 支持流式输出
)

@Serializable
data class ApiMessage(
    val role: String,
    val content: String
)

/**
 * API 响应模型
 */
@Serializable
data class ApiResponse(
    val choices: List<ApiChoice>
)

@Serializable
data class ApiChoice(
    val message: ApiResponseMessage
)

@Serializable
data class ApiResponseMessage(
    val content: String
)

/**
 * 流式响应模型（SSE）
 */
@Serializable
data class StreamResponse(
    val choices: List<StreamChoice>
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class StreamDelta(
    val reasoning: String? = null,
    val content: String? = null,
    val role: String? = null
)
