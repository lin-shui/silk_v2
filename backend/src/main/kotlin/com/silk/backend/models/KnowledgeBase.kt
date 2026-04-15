package com.silk.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class KBTopic(
    val id: String,
    val name: String,
    val project: String = "",
    val ownerId: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class KBEntry(
    val id: String,
    val topicId: String,
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val ownerId: String,
    val createdAt: Long,
    val updatedAt: Long
)
