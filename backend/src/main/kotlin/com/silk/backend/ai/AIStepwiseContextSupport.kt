package com.silk.backend.ai

import com.silk.backend.agents.core.AgentRuntime
import com.silk.backend.models.ChatHistoryEntry
import org.slf4j.Logger

internal fun buildPatientContext(chatHistory: List<ChatHistoryEntry>, hostId: String?): String {
    if (chatHistory.isEmpty()) {
        return "【聊天历史】\n暂无聊天记录。用户刚刚加入对话。"
    }

    val contextBuilder = StringBuilder()
    contextBuilder.append("【完整对话记录】\n")
    contextBuilder.append("以下是去除AI回复后的完整对话，用于诊断参考：\n\n")

    val userMessages = chatHistory
        .filter { !AgentRuntime.isAgentUserId(it.senderId) }
        .filter { it.messageType == "TEXT" }
        .filter { !it.content.startsWith("@诊断") && !it.content.startsWith("@diagnosis") }
        .takeLast(50)

    if (userMessages.isEmpty()) {
        contextBuilder.append("暂无对话记录。\n")
    } else {
        contextBuilder.append("对话记录（共 ${userMessages.size} 条）：\n")
        contextBuilder.append("═".repeat(50) + "\n\n")

        userMessages.forEach { entry ->
            val timestamp = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(entry.timestamp))
            val rolePrefix = if (hostId != null && entry.senderId == hostId) {
                "医生${entry.senderName}叙述"
            } else {
                "病人${entry.senderName}叙述"
            }
            contextBuilder.append("[$timestamp] $rolePrefix: ${entry.content}\n\n")
        }

        contextBuilder.append("═".repeat(50) + "\n")
    }

    if (userMessages.isNotEmpty()) {
        contextBuilder.append("\n【统计信息】\n")
        contextBuilder.append("参与人数: ${userMessages.map { it.senderId }.distinct().size}\n")
        contextBuilder.append("消息总数: ${userMessages.size}\n")

        if (hostId != null) {
            val doctorMsgCount = userMessages.count { it.senderId == hostId }
            val patientMsgCount = userMessages.size - doctorMsgCount
            contextBuilder.append("医生消息: ${doctorMsgCount} 条\n")
            contextBuilder.append("病人消息: ${patientMsgCount} 条\n")
        }
    }

    contextBuilder.append("\n【分析要求】\n")
    contextBuilder.append("请仔细阅读以上完整的聊天历史，理解用户的需求、问题和讨论的上下文。\n")
    contextBuilder.append("基于这些对话内容，进行专业的分析和建议。\n")
    return contextBuilder.toString()
}

internal fun offlineDiagnosisResult(taskName: String, context: String): String {
    val symptoms = extractSymptomsFromContext(context)
    val symptomsText = if (symptoms.isNotEmpty()) symptoms.joinToString("、") else "相关症状"

    return when (taskName) {
        "中西医疾病的诊断" -> """
【西医诊断】
1. 西医诊断：基于患者主诉「$symptomsText」，可能的疾病包括：[需要结合四诊进行诊断]
2. 中医诊断：根据症状表现，中医病名可能为：[需要辨证论治]
3. 鉴别诊断：需要排除类似疾病，建议进一步检查
4. 诊断依据：根据患者主诉症状和聊天历史中的描述

【离线模式】配置 API Key 后可获得详细的专业诊断。
""".trimIndent()

        "中医辨证分型" -> """
【中医辨证分型】
1. 八纲辨证：根据症状表现，初步判断为 [里证/表证]、[寒证/热证]、[虚证/实证]
2. 脏腑辨证：可能涉及脏腑 [需要四诊合参]
3. 气血津液辨证：初步判断为 [气虚/血瘀/津液不足等]
4. 六经辨证：根据症状可能属于 [太阳/阳明/少阳等]
5. 主要证型总结：[需要专业中医师综合判断]

【离线模式】配置 API Key 后可获得详细的辨证分析。
""".trimIndent()

        "中医的病因病机分析" -> """
【病因病机分析】
1. 病因：根据患者情况，可能涉及外感、内伤等因素
2. 病机：发病机理需要结合四诊信息综合判断
3. 病位：病变主要涉及的脏腑和经络
4. 病性：虚实寒热的具体性质
5. 病势：疾病的发展趋势和预后

【离线模式】配置 API Key 后可获得详细的病因病机分析。
""".trimIndent()

        "中医体质诊断" -> """
【中医体质诊断】
1. 九种体质分类：根据表现，需要评估是否属于气虚质、阳虚质、阴虚质等
2. 主要体质类型：需要通过详细问诊确定
3. 兼夹体质：可能存在多种体质兼夹
4. 体质与疾病关系：体质因素在疾病发生发展中的作用
5. 体质调理建议：根据体质类型提供调理方案

【离线模式】配置 API Key 后可获得详细的体质分析。
""".trimIndent()

        "分析汇总" -> """
【综合分析汇总】
1. 整体病情评估：综合中西医诊断，患者病情需要系统治疗
2. 中西医诊断的关联性：中西医诊断相互印证，病机明确
3. 核心病机总结：[需要根据前面的辨证分析总结]
4. 治疗的关键点：治疗需要注重调理脏腑功能，扶正祛邪
5. 预期治疗难度：根据病情轻重和患者配合度综合评估

【离线模式】配置 API Key 后可获得详细的综合分析。
""".trimIndent()

        "中医处方建议" -> """
【中医处方建议】
1. 治疗法则：根据证型，采用相应的治法和治则
2. 推荐方剂：建议选用经典方剂，如 [需要专业中医师开具]
3. 方剂组成：药物名称及剂量需要根据患者具体情况调整
4. 方解：方剂配伍体现了中医的整体观念和辨证论治原则
5. 加减化裁：根据兼症和体质进行个性化调整
6. 服用方法：煎服方法、服用时间、疗程等需要医嘱指导

【离线模式】配置 API Key 后可获得具体的处方建议。
⚠️ 重要提示：中药处方需要由持证中医师开具，切勿自行配药。
""".trimIndent()

        "推荐中成药" -> """
【推荐中成药】
1. 推荐中成药：根据证型，可考虑相应的中成药（需医师指导）
2. 功效主治：各药物的具体功效和适应证
3. 用法用量：严格按照说明书或医嘱服用
4. 注意事项：注意禁忌症和不良反应
5. 配合建议：中成药可与汤药配合使用，增强疗效

【离线模式】配置 API Key 后可获得具体的中成药建议。
⚠️ 重要提示：请在医师指导下使用中成药。
""".trimIndent()

        "针灸处方及针灸方法" -> """
【针灸治疗方案】
1. 主穴选择：根据证型选择主要穴位
2. 配穴选择：配合辅助穴位增强疗效
3. 针刺方法：进针方向、深度、手法需要专业针灸师操作
4. 灸法选择：温针灸、艾灸等方法
5. 疗程安排：建议每周2-3次，疗程因人而异
6. 注意事项：孕妇、出血性疾病等禁忌

【离线模式】配置 API Key 后可获得详细的针灸方案。
⚠️ 重要提示：针灸需要由专业针灸师进行，切勿自行操作。
""".trimIndent()

        "艾灸选穴及艾灸方法" -> """
【艾灸治疗方案】
1. 艾灸主穴：根据证型和病机选择主要艾灸穴位
2. 艾灸配穴：辅助穴位配合，增强疗效
3. 艾灸方法：
   - 艾灸类型：温和灸、隔姜灸等
   - 施灸时间：每穴15-20分钟
   - 操作要点：保持适当距离，感觉温热为度
4. 灸量把握：
   - 施灸时间：根据个体耐受度调整
   - 施灸强度：以患者感觉舒适为宜
5. 疗程安排：
   - 每日或隔日1次
   - 连续施灸7-10天为一疗程
6. 注意事项：
   - 禁忌症：孕妇、急性炎症、出血倾向等
   - 注意事项：避免烫伤，保持通风
   - 灸后调护：注意保暖，避免受风

【离线模式】配置 API Key 后可获得详细的艾灸方案。
⚠️ 重要提示：首次艾灸建议在专业人士指导下进行。
""".trimIndent()

        "饮食运动起居调养方案" -> """
【生活调养方案】
1. 饮食调理：
   - 宜食：根据证型选择合适的食材
   - 忌食：避免辛辣刺激、生冷食物（具体需根据证型）
   - 食疗方：建议在医师指导下选用
2. 运动建议：
   - 运动方式：适度的有氧运动，如散步、八段锦
   - 运动时间：每天30分钟左右
   - 注意事项：避免剧烈运动，量力而行
3. 起居调摄：
   - 作息时间：规律作息，早睡早起
   - 生活习惯：保持心情舒畅
   - 情志调理：避免过度紧张和焦虑
4. 季节养生：根据四时节气调整养生方案

【离线模式】配置 API Key 后可获得个性化的调养建议。
""".trimIndent()

        "预后说明" -> """
【预后说明】
1. 疾病预后：需要根据具体病情判断，及时治疗预后较好
2. 治疗周期：根据病情轻重，一般需要1-3个月
3. 复发可能：注意调理可降低复发风险
4. 注意事项：
   - 遵医嘱服药
   - 注意饮食起居调理
   - 保持良好心态
5. 复诊建议：建议1-2周复诊一次，观察治疗效果
6. 长期管理：慢性疾病需要长期调理，定期复查

【离线模式】配置 API Key 后可获得详细的预后分析。

【免责声明】
本诊断分析仅供参考，不构成医疗建议。
请务必咨询专业中医师进行正式诊断和治疗。
""".trimIndent()

        else -> "【$taskName】\n正在处理患者信息..."
    }
}

internal fun filterMessagesAfterLastDiagnosis(
    chatHistory: List<ChatHistoryEntry>,
    lastDiagnosisTime: Long,
    logger: Logger
): List<ChatHistoryEntry> {
    if (lastDiagnosisTime <= 0) {
        logger.info("📋 无历史时间戳，使用所有消息")
        return chatHistory
    }

    val filtered = chatHistory.filter { it.timestamp > lastDiagnosisTime }
    val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastDiagnosisTime))
    logger.info("📋 过滤消息:")
    logger.info("   上次诊断时间: $dateTime")
    logger.info("   总消息数: ${chatHistory.size}")
    logger.info("   新消息数: ${filtered.size}")
    return filtered
}

internal fun buildNewMessagesContext(newMessages: List<ChatHistoryEntry>): String {
    if (newMessages.isEmpty()) {
        return "【上次诊断后的新对话】\n暂无新对话"
    }

    return buildString {
        append("【上次诊断后的新对话】\n")
        newMessages.forEach { entry ->
            val timestamp = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(entry.timestamp))
            append("[$timestamp] ${entry.senderName}: ${entry.content}\n")
        }
    }
}

internal fun buildPreviousDiagnosisSummary(
    previousDiagnosisResults: Map<String, AIStepwiseAgent.StepResult>?
): String {
    if (previousDiagnosisResults.isNullOrEmpty()) {
        return "【之前的诊断记录】\n暂无之前的诊断记录"
    }

    val keySteps = listOf("中医诊断", "西医诊断", "治疗方案")
    return buildString {
        append("【之前的诊断记录（摘要）】\n")
        previousDiagnosisResults
            .filter { (stepName, _) -> keySteps.any { key -> stepName.contains(key) } }
            .forEach { (stepName, stepResult) ->
                append("$stepName：${stepResult.result.take(100)}...\n")
            }
    }
}

internal fun buildDoctorUpdateStepResults(
    doctorMessage: String,
    previousDiagnosisResults: Map<String, AIStepwiseAgent.StepResult>?,
    updatedDiagnosis: String
): MutableMap<String, AIStepwiseAgent.StepResult> {
    val stepResults = linkedMapOf<String, AIStepwiseAgent.StepResult>()
    stepResults["医生诊断意见"] = AIStepwiseAgent.StepResult(
        stepName = "医生诊断意见",
        result = """
【医生医嘱】
$doctorMessage

【说明】
以下诊断结果是基于初步诊断报告和上述医生医嘱综合更新后的结果。
        """.trimIndent(),
        success = true
    )
    if (previousDiagnosisResults != null) {
        stepResults.putAll(previousDiagnosisResults)
    }
    stepResults["AI综合诊断（更新）"] = AIStepwiseAgent.StepResult(
        stepName = "AI综合诊断（更新）",
        result = updatedDiagnosis,
        success = true
    )
    return stepResults
}

internal fun buildDoctorUpdatePdfMessage(pdfPath: String, downloadUrl: String): String = buildString {
    append("📄 诊断更新报告已生成\n\n")
    append("文件名：${pdfPath.substringAfterLast("/")}\n\n")
    append("━".repeat(50) + "\n")
    append("📥 下载更新报告\n")
    append("━".repeat(50) + "\n\n")
    append("$downloadUrl\n\n")
    append("💡 基于医生的专业意见，诊断已更新\n")
    append("   报告包含：医生医嘱 + 之前诊断 + 综合更新")
}

internal fun String.safeIncrementFrom(previousContent: String): String {
    return if (length > previousContent.length) substring(previousContent.length) else ""
}

private fun extractSymptomsFromContext(context: String): List<String> {
    val symptoms = mutableListOf<String>()
    val commonSymptoms = listOf(
        "头痛", "失眠", "疲劳", "咳嗽", "发热", "腹痛", "腹泻",
        "便秘", "食欲不振", "心悸", "胸闷", "腰痛", "关节痛"
    )

    commonSymptoms.forEach { symptom ->
        if (context.contains(symptom)) {
            symptoms.add(symptom)
        }
    }

    return symptoms
}
