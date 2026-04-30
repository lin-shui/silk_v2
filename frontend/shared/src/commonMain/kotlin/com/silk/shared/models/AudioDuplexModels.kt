package com.silk.shared.models

/** 双工会话状态 */
enum class DuplexState {
    IDLE,
    CONNECTING,
    QUEUED,
    PREPARING,
    STREAMING,
    DISCONNECTED,
    ERROR
}

/** 对话记录条目 */
data class TranscriptItem(
    val role: String,
    val text: String,
    val timestamp: Long
)
