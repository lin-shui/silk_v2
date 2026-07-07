package com.silk.backend.kb

import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBMemoryMetadata
import com.silk.backend.models.KBMemoryType
import com.silk.backend.models.KBSourceType
import kotlinx.serialization.Serializable

private val explicitMemoryRegexes = listOf(
    Regex("""^\s*(?:(?:请|麻烦)\s*)?(?:你要\s*)?记住(?:一下)?(?:[：:,\s]+|(?=\S))(.+?)\s*$""", RegexOption.IGNORE_CASE),
    Regex("""^\s*remember(?:\s+that)?(?:[：:,\s]+|(?=\S))(.+?)\s*$""", RegexOption.IGNORE_CASE),
)
internal const val MEMORY_PROMPT_HEADER =
    "以下是与当前问题相关的长期记忆，仅作为辅助上下文；如果与用户本轮最新要求冲突，必须以本轮要求为准。"

@Serializable
data class ExplicitMemoryCapture(
    val content: String,
    val type: KBMemoryType,
    val title: String,
    val key: String? = null,
)

@Serializable
data class AutoMemoryCapture(
    val content: String,
    val type: KBMemoryType,
    val title: String,
    val key: String,
)

internal fun detectExplicitMemoryCapture(userInput: String): ExplicitMemoryCapture? {
    val content = explicitMemoryRegexes.firstNotNullOfOrNull { regex ->
        regex.matchEntire(userInput)?.groupValues?.getOrNull(1)
    }?.trim()?.trimEnd('。', '.', '!', '！')
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    val type = inferMemoryType(content)
    return ExplicitMemoryCapture(
        content = content,
        type = type,
        title = buildMemoryTitle(type, content),
        key = buildMemoryKey(type, content),
    )
}

internal fun detectAutoMemoryCaptures(userInput: String): List<AutoMemoryCapture> {
    val normalizedInput = userInput.trim()
    if (normalizedInput.isEmpty() || detectExplicitMemoryCapture(normalizedInput) != null) {
        return emptyList()
    }

    val captures = linkedMapOf<String, AutoMemoryCapture>()
    detectResponseLanguagePreference(normalizedInput)?.let { captures[it.key] = it }
    detectResponseStylePreference(normalizedInput)?.let { captures[it.key] = it }
    detectCodeLanguagePreference(normalizedInput)?.let { captures[it.key] = it }
    return captures.values.toList()
}

internal fun inferMemoryType(content: String): KBMemoryType {
    val normalized = content.lowercase()
    return when {
        listOf("以后", "下次", "默认", "优先", "请用", "请按", "回答时", "回复时", "输出", "格式", "步骤").any { normalized.contains(it) } ->
            KBMemoryType.PROCEDURAL
        listOf("喜欢", "偏好", "习惯", "常用", "技术栈", "语言", "风格", "尽量", "不要").any { normalized.contains(it) } ->
            KBMemoryType.PREFERENCE
        listOf("我是", "我叫", "我在", "我的职位", "我的角色", "我负责", "我用的是").any { normalized.contains(it) } ->
            KBMemoryType.PROFILE
        else -> KBMemoryType.EPISODIC
    }
}

internal fun buildMemoryKey(type: KBMemoryType, content: String): String {
    val normalized = content.lowercase()
        .replace(Regex("""[^\p{IsHan}a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(64)
    return "${type.name.lowercase()}:$normalized"
}

internal fun buildMemoryTitle(type: KBMemoryType, content: String): String {
    val prefix = when (type) {
        KBMemoryType.PROFILE -> "Profile"
        KBMemoryType.PREFERENCE -> "Preference"
        KBMemoryType.EPISODIC -> "Memory"
        KBMemoryType.PROCEDURAL -> "Procedure"
    }
    val snippet = content.replace(Regex("""\s+"""), " ").trim().take(32)
    return "$prefix: $snippet"
}

internal fun buildMemoryTags(type: KBMemoryType): List<String> {
    return listOf("memory", "memory:${type.name.lowercase()}")
}

internal fun buildMemoryReason(entry: KBEntry): String {
    val typeLabel = when (entry.memory?.type ?: KBMemoryType.EPISODIC) {
        KBMemoryType.PROFILE -> "用户画像"
        KBMemoryType.PREFERENCE -> "用户偏好"
        KBMemoryType.EPISODIC -> "长期记忆"
        KBMemoryType.PROCEDURAL -> "执行偏好"
    }
    val sourceLabel = when (entry.source.sourceType) {
        KBSourceType.AI_RESPONSE -> "来自自动记忆"
        KBSourceType.CHAT -> "来自用户显式记忆"
        else -> "来自长期记忆"
    }
    return "$typeLabel，$sourceLabel"
}

internal fun defaultMemoryMetadata(
    type: KBMemoryType,
    key: String?,
    explicit: Boolean = true,
): KBMemoryMetadata {
    return KBMemoryMetadata(
        type = type,
        key = key?.trim()?.takeIf { it.isNotEmpty() },
        explicit = explicit,
        capturedAt = System.currentTimeMillis(),
    )
}

private fun detectResponseLanguagePreference(userInput: String): AutoMemoryCapture? {
    val compact = userInput.lowercase().replace(Regex("""\s+"""), "")
    val language = when {
        listOf("用中文回答", "中文回复", "请用中文", "默认中文", "说中文").any { compact.contains(it) } -> "中文"
        listOf("用英文回答", "英文回复", "请用英文", "默认英文", "说英文", "english").any { compact.contains(it) } -> "英文"
        else -> null
    } ?: return null

    return AutoMemoryCapture(
        content = "请默认用${language}回答",
        type = KBMemoryType.PROCEDURAL,
        title = "Procedure: 默认用${language}回答",
        key = "response_language",
    )
}

private fun detectResponseStylePreference(userInput: String): AutoMemoryCapture? {
    val normalized = userInput.lowercase()
    val style = when {
        Regex("""(简洁|精简|简短|直接一点)""").containsMatchIn(normalized) -> "简洁"
        Regex("""(详细|展开|分步骤|解释清楚|细一点)""").containsMatchIn(normalized) -> "详细"
        else -> null
    } ?: return null

    if (!Regex("""(回答|回复|输出|说明|写|风格|以后|下次|默认|请|尽量)""").containsMatchIn(normalized)) {
        return null
    }

    return AutoMemoryCapture(
        content = "回答风格偏好：$style",
        type = KBMemoryType.PREFERENCE,
        title = "Preference: 回答风格 $style",
        key = "response_style",
    )
}

private fun detectCodeLanguagePreference(userInput: String): AutoMemoryCapture? {
    val match = Regex(
        """(?:代码|示例|样例|demo|实现).{0,12}?(?:用|使用|写成|给我).{0,4}?(kotlin|java|python|typescript|javascript|go|rust|swift|c#|cpp|c\+\+)""",
        RegexOption.IGNORE_CASE,
    ).find(userInput) ?: return null
    val rawLanguage = match.groupValues.getOrNull(1)?.trim().orEmpty()
    if (rawLanguage.isEmpty()) return null
    val language = when (rawLanguage.lowercase()) {
        "c#" -> "C#"
        "cpp", "c++" -> "C++"
        "javascript" -> "JavaScript"
        "typescript" -> "TypeScript"
        else -> rawLanguage.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    return AutoMemoryCapture(
        content = "代码示例优先使用 $language",
        type = KBMemoryType.PREFERENCE,
        title = "Preference: 代码语言 $language",
        key = "code_language_preference",
    )
}
