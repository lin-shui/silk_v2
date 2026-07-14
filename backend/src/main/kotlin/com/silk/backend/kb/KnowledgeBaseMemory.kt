package com.silk.backend.kb

import com.silk.backend.models.ArchivedMemoryVersion
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBEntryStatus
import com.silk.backend.models.KBMemoryMetadata
import com.silk.backend.models.KBMemoryType
import com.silk.backend.models.KBSourceType
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

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
    if (containsSensitiveContent(normalizedInput)) {
        return emptyList()
    }

    val captures = linkedMapOf<String, AutoMemoryCapture>()
    detectResponseLanguagePreference(normalizedInput)?.let { captures[it.key] = it }
    detectResponseStylePreference(normalizedInput)?.let { captures[it.key] = it }
    detectCodeLanguagePreference(normalizedInput)?.let { captures[it.key] = it }
    detectTechStackPreference(normalizedInput)?.let { captures[it.key] = it }
    detectOutputFormatPreference(normalizedInput)?.let { captures[it.key] = it }
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

private val commonTechStacks = listOf(
    "react", "vue", "angular", "svelte", "next.js", "nuxt", "nestjs",
    "spring", "spring boot", "ktor", "django", "flask", "fastapi", "rails", "laravel",
    "kotlin", "java", "python", "typescript", "javascript", "go", "golang",
    "rust", "swift", "c#", "c++", "cpp", "scala", "elixir", "php",
    "postgresql", "mysql", "mongodb", "redis", "sqlite", "elasticsearch",
    "docker", "kubernetes", "k8s", "aws", "gcp", "azure",
    "graphql", "grpc", "rest api", "websocket",
)

private val techStackContextPattern = Regex(
    """(?:我|我们|项目|团队|现在|主要|当前)(?:.{0,12}?)(?:在用|用|使用|技术栈是?|开发用|后端用?|前端用?|数据库用?|部署用?)(?:.{0,8}?)(?:\S+)""",
    RegexOption.IGNORE_CASE,
)

private val techStackNameMap = mapOf(
    "c#" to "C#",
    "c++" to "C++",
    "cpp" to "C++",
    "javascript" to "JavaScript",
    "typescript" to "TypeScript",
    "golang" to "Go",
    "k8s" to "Kubernetes",
    "gcp" to "GCP",
    "aws" to "AWS",
    "postgresql" to "PostgreSQL",
    "mysql" to "MySQL",
    "mongodb" to "MongoDB",
    "sqlite" to "SQLite",
    "graphql" to "GraphQL",
    "grpc" to "gRPC",
    "elasticsearch" to "Elasticsearch",
    "kubernetes" to "Kubernetes",
)

private fun formatTechStackName(raw: String): String {
    return raw.split(" ").joinToString(" ") { word ->
        techStackNameMap[word.lowercase()]
            ?: word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

private fun detectTechStackPreference(userInput: String): AutoMemoryCapture? {
    val normalized = userInput.lowercase()
    val matchedStacks = commonTechStacks.filter { stack ->
        val escaped = Regex.escape(stack)
        Regex("""\b$escaped\b""", RegexOption.IGNORE_CASE).containsMatchIn(userInput)
    }
    if (matchedStacks.isEmpty() || !techStackContextPattern.containsMatchIn(normalized)) {
        return null
    }
    val stackLabel = matchedStacks.map(::formatTechStackName).distinct().take(5).joinToString(", ")
    return AutoMemoryCapture(
        content = "技术栈偏好：$stackLabel",
        type = KBMemoryType.PREFERENCE,
        title = "Preference: 技术栈 $stackLabel",
        key = "tech_stack_preference",
    )
}

private val outputFormatPatterns = listOf(
    Regex("""(?:用|以|按|按格式|格式)(?:.{0,8}?)(表格|列表|markdown|json|yaml|xml|csv|流程图|思维导图|要点|分点|条目)"""),
    Regex("""(?:输出|回答|回复|给我)(?:.{0,8}?)(表格|列表|markdown|json|yaml|xml|csv|流程图|思维导图|要点|分点)"""),
    Regex("""(?:默认|以后|请)(?:.{0,4}?)(表格|列表|markdown|json|yaml|xml|csv|要点|分点|条目)"""),
)

private fun detectOutputFormatPreference(userInput: String): AutoMemoryCapture? {
    val formatLabels = mapOf(
        "表格" to "表格",
        "列表" to "列表",
        "markdown" to "Markdown",
        "json" to "JSON",
        "yaml" to "YAML",
        "xml" to "XML",
        "csv" to "CSV",
        "流程图" to "流程图",
        "思维导图" to "思维导图",
        "要点" to "要点",
        "分点" to "分点",
        "条目" to "条目",
    )
    val matchResult = outputFormatPatterns.firstNotNullOfOrNull { pattern ->
        pattern.find(userInput)
    } ?: return null
    val format = matchResult.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val label = formatLabels[format] ?: format
    return AutoMemoryCapture(
        content = "输出格式偏好：$label",
        type = KBMemoryType.PREFERENCE,
        title = "Preference: 输出格式 $label",
        key = "output_format_preference",
    )
}

private val sensitivePatterns = listOf(
    Regex("""(?:密码|password|passwd|secret|token|api[_\s]?key|apikey|access[_\s]?key).{0,5}?[:：=]\s*\S+""", RegexOption.IGNORE_CASE),
    Regex("""(?:sk-[a-zA-Z0-9]{20,}|ghp_[a-zA-Z0-9]{20,}|xox[bpras]-\d+-\d+-[a-zA-Z0-9]+)"""),
    Regex("""(?:-----BEGIN\s*(?:RSA\s*)?PRIVATE\s*KEY-----)"""),
    Regex("""\b\d{3}[-.\s]?\d{2}[-.\s]?\d{4}\b"""),
    Regex("""\b(?:\d[ -]*?){13,16}\b"""),
)

internal fun containsSensitiveContent(userInput: String): Boolean {
    return sensitivePatterns.any { it.containsMatchIn(userInput) }
}

// ──────────────────────────────────────────────
// Phase 3: Merge And Conflict Handling
// ──────────────────────────────────────────────

/**
 * 语义相似度检测：两条记忆内容是否高度相似（可用于合并判定）。
 * 使用字符 tri-gram（三元组）Jaccard 相似度，对中文短文本更敏感。
 */
internal fun memoryContentSimilarity(a: String, b: String): Double {
    val trigramsA = extractTrigrams(normalizeMemoryText(a))
    val trigramsB = extractTrigrams(normalizeMemoryText(b))
    if (trigramsA.isEmpty() && trigramsB.isEmpty()) return 1.0
    if (trigramsA.isEmpty() || trigramsB.isEmpty()) return 0.0
    val intersection = trigramsA.intersect(trigramsB).size.toDouble()
    val union = trigramsA.union(trigramsB).size.toDouble()
    return intersection / union
}

private fun extractTrigrams(text: String): Set<String> {
    val cleaned = text.replace(" ", "")
    if (cleaned.length < 3) return setOf(cleaned)
    return cleaned.windowed(3).toSet()
}

private fun normalizeMemoryText(raw: String): String {
    return raw.lowercase()
        .replace(Regex("""[^\p{IsHan}a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

/**
 * 当一条记忆被新值覆盖时，将旧内容归档到 `archivedVersions`。
 * 返回更新后的 metadata；若旧内容与当前 content 一致则不做归档。
 */
internal fun archiveOldVersion(
    existingEntry: KBEntry,
    newContent: String,
    reason: String = "被新偏好覆盖",
): KBMemoryMetadata? {
    val meta = existingEntry.memory ?: return null
    val existingKey = meta.key
        ?: buildMemoryKey(meta.type, existingEntry.content)
    if (normalizeMemoryText(existingEntry.content) == normalizeMemoryText(newContent)) {
        return meta.copy(key = existingKey)
    }
    val archivedVersion = ArchivedMemoryVersion(
        content = existingEntry.content,
        title = existingEntry.title,
        archivedAt = System.currentTimeMillis(),
        reason = reason,
    )
    return meta.copy(
        key = existingKey,
        archivedVersions = meta.archivedVersions + archivedVersion,
    )
}

/**
 * Consolidation report 用于记录单次 consolidation 的统计信息。
 */
@Serializable
data class ConsolidationReport(
    val mergedPairs: Int = 0,
    val expiredRemoved: Int = 0,
    val archivedCount: Int = 0,
    val totalBefore: Int = 0,
    val totalAfter: Int = 0,
)

/**
 * 近重复记忆合并阈值：content Jaccard 相似度 >= 此值视为近重复。
 */
internal const val MERGE_SIMILARITY_THRESHOLD = 0.60

/**
 * EPISODIC 记忆 TTL（毫秒）：超过此时间的 episodic 记忆将被自动归档或删除。
 * 默认 90 天。
 */
internal const val EPISODIC_TTL_MS = 90L * 24 * 60 * 60 * 1000

/**
 * PROCEDURAL 与 PREFERENCE 的不活跃衰减 TTL（毫秒）：
 * 超过此时间未被访问的 procedural/preference 记忆被打上低优先级标记。
 * 默认 180 天。
 */
internal const val PREFERENCE_TTL_MS = 180L * 24 * 60 * 60 * 1000

/**
 * 合并同 topic 内高度相似的记忆条目。
 * 匹配条件：同 memory type、content Jaccard >= MERGE_SIMILARITY_THRESHOLD。
 * 合并规则：保留较新的条目，将较旧的条目内容追加到 archivedVersions 后删除旧条目。
 * @return 被合并删除的 entry id 列表
 */
internal fun mergeNearDuplicateMemories(entries: MutableList<KBEntry>): List<String> {
    val memoryEntries = entries.filter { it.memory != null && it.status == KBEntryStatus.PUBLISHED }
    val removedIds = mutableListOf<String>()

    for (i in memoryEntries.indices) {
        val a = memoryEntries[i]
        if (a.id in removedIds || a.memory == null) continue
        val aMeta = a.memory!!

        for (j in (i + 1) until memoryEntries.size) {
            val b = memoryEntries[j]
            val bMeta = b.memory
            val canMerge = b.id !in removedIds && bMeta != null && bMeta.type == aMeta.type &&
                memoryContentSimilarity(a.content, b.content) >= MERGE_SIMILARITY_THRESHOLD
            if (canMerge) {
                mergePairIntoEntries(entries, a, b, removedIds)
            }
        }
    }
    return removedIds
}

private fun mergePairIntoEntries(entries: MutableList<KBEntry>, a: KBEntry, b: KBEntry, removedIds: MutableList<String>) {
    val (keep, remove) = if (a.updatedAt >= b.updatedAt) a to b else b to a
    if (remove.memory == null) return
    val archivedVersion = ArchivedMemoryVersion(
        content = remove.content,
        title = remove.title,
        archivedAt = System.currentTimeMillis(),
        reason = "与「${keep.title}」近重复合并",
    )
    val keepIdx = entries.indexOfFirst { it.id == keep.id }
    if (keepIdx < 0) return
    entries[keepIdx] = entries[keepIdx].copy(
        memory = keep.memory?.copy(
            archivedVersions = (keep.memory?.archivedVersions ?: emptyList()) + archivedVersion,
        ),
        tags = (keep.tags + remove.tags).distinct(),
        updatedAt = System.currentTimeMillis(),
    )
    entries.removeAll { it.id == remove.id }
    removedIds.add(remove.id)
}

/**
 * 对过期记忆应用 TTL 衰减：
 * - EPISODIC 超过 `EPISODIC_TTL_MS` 未访问 → 标记为 ARCHIVED
 * - PREFERENCE/PROCEDURAL 超过 `PREFERENCE_TTL_MS` 未访问 → 降低搜索优先级（通过标记）
 * @return (archivedEpisodicIds, stalePreferenceIds) 分类统计
 */
internal fun applyTTLDecay(entries: MutableList<KBEntry>): Pair<List<String>, List<String>> {
    val now = System.currentTimeMillis()
    val archivedEpisodicIds = mutableListOf<String>()
    val stalePreferenceIds = mutableListOf<String>()

    val candidateIndices = entries.indices.filter { i ->
        val e = entries[i]
        e.memory != null && e.status == KBEntryStatus.PUBLISHED
    }
    for (i in candidateIndices) {
        val entry = entries[i]
        val meta = entry.memory!!
        val lastAccess = meta.lastAccessedAt.takeIf { it > 0 } ?: meta.capturedAt
        val age = now - lastAccess

        when (meta.type) {
            KBMemoryType.EPISODIC -> {
                if (age > EPISODIC_TTL_MS) {
                    entries[i] = entry.copy(
                        status = KBEntryStatus.ARCHIVED,
                        updatedAt = now,
                        memory = meta.copy(
                            archivedVersions = meta.archivedVersions + ArchivedMemoryVersion(
                                content = entry.content,
                                title = entry.title,
                                archivedAt = now,
                                reason = "EPISODIC 记忆超过 TTL（${EPISODIC_TTL_MS / 86400000L}天）自动归档",
                            ),
                        ),
                    )
                    archivedEpisodicIds.add(entry.id)
                }
            }
            KBMemoryType.PREFERENCE, KBMemoryType.PROCEDURAL -> {
                if (age > PREFERENCE_TTL_MS) {
                    stalePreferenceIds.add(entry.id)
                }
            }
            KBMemoryType.PROFILE -> { }
        }
    }
    return archivedEpisodicIds to stalePreferenceIds
}

/**
 * 主 consolidation 入口：对指定用户的记忆 topic 执行去重合并与 TTL 衰减。
 * 调用方（KB Manager）需要负责加锁和持久化。
 *
 * @param entries 用户的全部记忆条目（写时复制，会被就地修改）
 * @return ConsolidationReport 报告本次操作统计
 */
internal fun consolidateMemories(entries: MutableList<KBEntry>): ConsolidationReport {
    val totalBefore = entries.count { it.memory != null && it.status == KBEntryStatus.PUBLISHED }
    val mergedIds = mergeNearDuplicateMemories(entries)
    val (archivedIds, staleIds) = applyTTLDecay(entries)
    val totalAfter = entries.count { it.memory != null && it.status == KBEntryStatus.PUBLISHED }
    return ConsolidationReport(
        mergedPairs = mergedIds.size,
        expiredRemoved = archivedIds.size,
        archivedCount = staleIds.size,
        totalBefore = totalBefore,
        totalAfter = totalAfter,
    )
}

/**
 * 计算记忆的 recency 加权分（用于上下文检索中的排序加分）。
 * 越近访问的条目加分越高；从未访问的以 capturedAt 为准。
 */
internal fun recencyScore(meta: KBMemoryMetadata, now: Long = System.currentTimeMillis()): Double {
    val refTime = meta.lastAccessedAt.takeIf { it > 0 } ?: meta.capturedAt
    if (refTime <= 0) return 0.0
    val ageHours = (now - refTime).toDouble() / 3_600_000.0
    return when {
        ageHours <= 1 -> 8.0   // 最近 1 小时
        ageHours <= 24 -> 6.0  // 最近 1 天
        ageHours <= 168 -> 4.0 // 最近 1 周
        ageHours <= 720 -> 2.0 // 最近 1 月
        ageHours <= 4320 -> 1.0// 最近 6 月
        else -> 0.0
    }
}

/**
 * 更新记忆的访问时间与计数。
 */
internal fun markMemoryAccessed(meta: KBMemoryMetadata): KBMemoryMetadata {
    return meta.copy(
        lastAccessedAt = System.currentTimeMillis(),
        accessedCount = meta.accessedCount + 1,
    )
}
