package com.silk.backend.git

object GitContextBuilder {
    const val DEFAULT_MAX_EVENTS = 20
    const val DEFAULT_MAX_CHARS = 8_000

    fun build(
        events: List<GitEventRecord>,
        maxEvents: Int = DEFAULT_MAX_EVENTS,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        if (maxChars <= 0 || maxEvents <= 0) return ""
        val builder = StringBuilder("Recent GitHub events:\n")
        events.sortedByDescending { it.createdAt }.take(maxEvents).forEach { event ->
            val line = buildString {
                append("- ").append(event.event).append('/').append(event.action)
                if (event.repository.isNotBlank()) append(" ").append(event.repository)
                if (event.issueNumber != null) append(" #").append(event.issueNumber)
                if (event.title.isNotBlank()) append(" ").append(event.title)
                if (event.summary.isNotBlank()) append(" | ").append(event.summary)
                if (event.htmlUrl.isNotBlank()) append(" | ").append(event.htmlUrl)
                append('\n')
            }
            if (builder.length + line.length > maxChars) return@forEach
            builder.append(line)
        }
        return builder.toString().takeIf { it != "Recent GitHub events:\n" }?.take(maxChars).orEmpty()
    }

    fun buildForRoom(
        roomId: String,
        binding: RoomGitBinding?,
        store: GitEventStore,
        maxEvents: Int = DEFAULT_MAX_EVENTS,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String = if (binding?.roomId == roomId && binding.status == GitBindingStatus.ACTIVE) {
        build(store.listEvents(roomId, maxEvents), maxEvents, maxChars)
    } else ""
}
