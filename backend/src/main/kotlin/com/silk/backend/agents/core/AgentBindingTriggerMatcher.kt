package com.silk.backend.agents.core

import com.silk.backend.agents.auth.AgentBindingDto
import com.silk.backend.agents.auth.AgentTriggerPolicy
import com.silk.backend.agents.auth.defaultAgentMentionAlias

/** Resolves a TEAM message to one bound Agent without allowing cross-Agent mentions to bleed through. */
internal object AgentBindingTriggerMatcher {
    private val mentionPattern = Regex("^@([A-Za-z0-9_-]+)(?:\\s+(.*))?$", RegexOption.DOT_MATCHES_ALL)

    fun promptFor(binding: AgentBindingDto, text: String): String? {
        val mention = mentionPattern.matchEntire(text.trim())
        val mentionedAlias = mention?.groupValues?.getOrNull(1)?.lowercase()
        val bindingAlias = binding.mentionAlias
            .trim()
            .lowercase()
            .ifBlank { defaultAgentMentionAlias(binding.agentType) }
        val mentionedPrompt = mention?.groupValues?.getOrNull(2)?.trim().orEmpty().takeIf(String::isNotBlank)
        return when (binding.triggerPolicy) {
            AgentTriggerPolicy.EVENT -> null
            AgentTriggerPolicy.MENTION -> if (mentionedAlias == bindingAlias) {
                mentionedPrompt
            } else null
            AgentTriggerPolicy.ALL -> when {
                mentionedAlias != null && mentionedAlias != bindingAlias -> null
                mentionedAlias == bindingAlias -> mentionedPrompt
                text.trim().equals("@silk", ignoreCase = true) ||
                    text.trim().startsWith("@silk ", ignoreCase = true) -> null
                else -> text.trim().takeIf(String::isNotBlank)
            }
        }
    }
}
