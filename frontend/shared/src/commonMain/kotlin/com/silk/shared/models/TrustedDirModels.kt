package com.silk.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class TrustedDirCheckResponse(
    val trusted: Boolean,
    val bridgeConnected: Boolean,
    val bridgeId: String? = null,
)

@Serializable
data class TrustedDirRecordDto(
    val bridgeId: String,
    val path: String,
    val trustedAt: Long,
)

@Serializable
data class TrustedDirListResponse(
    val entries: List<TrustedDirRecordDto>,
)

@Serializable
data class AddTrustRequest(
    val path: String,
)
