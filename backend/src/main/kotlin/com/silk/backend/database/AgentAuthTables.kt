package com.silk.backend.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/** Long-lived public-key enrollment for one local Silk device profile. */
object AgentDevices : Table("agent_devices") {
    val id = varchar("id", 128)
    val userId = varchar("user_id", 128).references(Users.id).index()
    val publicKey = varchar("public_key", 128).uniqueIndex()
    val keyAlgorithm = varchar("key_algorithm", 32)
    val fingerprint = varchar("fingerprint", 128).uniqueIndex()
    val displayName = varchar("display_name", 256)
    val status = varchar("status", 32)
    val platform = varchar("platform", 64)
    val authenticationOrigin = varchar("authentication_origin", 512).nullable()
    val createdAt = datetime("created_at")
    val lastSeenAt = datetime("last_seen_at").nullable()
    val lastSeenIp = varchar("last_seen_ip", 128).nullable()
    val revokedAt = datetime("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * A compact tombstone retained after an old revoked device row is purged.
 * The public key is not a secret; keeping it prevents the same revoked key
 * from being enrolled again after its historical device row is cleaned up.
 */
object AgentDeviceRevocationTombstones : Table("agent_device_revocation_tombstones") {
    val id = varchar("id", 128)
    val userId = varchar("user_id", 128).references(Users.id).index()
    val deviceId = varchar("device_id", 128).index()
    val publicKey = varchar("public_key", 128).uniqueIndex()
    val fingerprint = varchar("fingerprint", 128).uniqueIndex()
    val revokedAt = datetime("revoked_at").index()

    override val primaryKey = PrimaryKey(id)
}

/** One independently approved external Agent running below a trusted device. */
object AgentInstances : Table("agent_instances") {
    val id = varchar("id", 128)
    val userId = varchar("user_id", 128).references(Users.id).index()
    val deviceId = varchar("device_id", 128).references(AgentDevices.id).index()
    val agentType = varchar("agent_type", 64)
    val transportAdapter = varchar("transport_adapter", 32)
    val displayName = varchar("display_name", 256)
    val connectorVersion = varchar("connector_version", 64)
    val capabilitiesJson = text("capabilities_json")
    /** Silk-managed Agent runtime overlay; the device's native config remains untouched. */
    val runtimePermissionMode = varchar("runtime_permission_mode", 32).default("NATIVE_DEFAULT")
    val status = varchar("status", 32)
    val createdAt = datetime("created_at")
    val lastSeenAt = datetime("last_seen_at").nullable()
    val revokedAt = datetime("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Explicit Room or Workspace authorization for an AgentInstance. */
object AgentBindings : Table("agent_bindings") {
    val id = varchar("id", 128)
    val agentInstanceId = varchar("agent_instance_id", 128).references(AgentInstances.id).index()
    val targetType = varchar("target_type", 32)
    val targetId = varchar("target_id", 128).index()
    val messageScope = varchar("message_scope", 32)
    val triggerPolicy = varchar("trigger_policy", 32)
    /** Room-local mention route; Workspace bindings keep an internal unique value. */
    val mentionAlias = varchar("mention_alias", 64).default("")
    /** User-facing two-layer policy. Blank values are migrated from legacy permissions_json. */
    val accessMode = varchar("access_mode", 32).default("")
    val permissionsJson = text("permissions_json")
    val status = varchar("status", 32)
    val createdBy = varchar("created_by", 128).references(Users.id)
    val ownerId = varchar("owner_id", 128).nullable().index()
    val agentOwnerApprovedBy = varchar("agent_owner_approved_by", 128).nullable()
    val agentOwnerApprovedAt = datetime("agent_owner_approved_at").nullable()
    val targetApprovedBy = varchar("target_approved_by", 128).nullable()
    val targetApprovedAt = datetime("target_approved_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at").nullable()
    val revokedBy = varchar("revoked_by", 128).nullable()
    val revokedAt = datetime("revoked_at").nullable()

    init {
        uniqueIndex(
            "uq_agent_binding_target_scope",
            agentInstanceId,
            targetType,
            targetId,
            messageScope,
        )
    }

    override val primaryKey = PrimaryKey(id)
}

/** Immutable snapshots for security-relevant AgentBinding lifecycle decisions. */
object AgentBindingAuditEvents : Table("agent_binding_audit_events") {
    val id = varchar("id", 128)
    val bindingId = varchar("binding_id", 128).index()
    val actorId = varchar("actor_id", 128).index()
    val action = varchar("event_action", 32)
    val snapshotJson = text("snapshot_json")
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(id)
}

/** Database-backed one-time challenges shared by every backend node. */
object AgentConnectionChallenges : Table("agent_connection_challenges") {
    val id = varchar("id", 128)
    val nonce = varchar("nonce", 128)
    val serverOrigin = varchar("server_origin", 512)
    val deviceId = varchar("device_id", 128).index()
    val agentInstanceId = varchar("agent_instance_id", 128).index()
    val publicKey = varchar("public_key", 128)
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at").index()
    val consumedAt = datetime("consumed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Immutable device/Agent security events; the sequence also drives revocation fan-out. */
object AgentSecurityEvents : Table("agent_security_events") {
    val sequence = long("event_sequence").autoIncrement()
    val userId = varchar("user_id", 128).index()
    val actorId = varchar("actor_id", 128).nullable().index()
    val action = varchar("event_action", 64).index()
    val deviceId = varchar("device_id", 128).nullable().index()
    val agentInstanceId = varchar("agent_instance_id", 128).nullable().index()
    val metadataJson = text("metadata_json")
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(sequence)
}

/** Short-lived browser approval transaction. Secrets are stored only as SHA-256 digests. */
object AgentPairingRequests : Table("agent_pairing_requests") {
    val id = varchar("id", 128)
    val requestKind = varchar("request_kind", 32).default("DEVICE_ENROLLMENT")
    val trustedDeviceRequestId = varchar("trusted_device_request_id", 128).nullable().uniqueIndex()
    val protocolVersion = integer("protocol_version")
    val publicKey = varchar("public_key", 128)
    val publicKeyFingerprint = varchar("public_key_fingerprint", 128)
    val keyAlgorithm = varchar("key_algorithm", 32)
    val requestedDeviceName = varchar("requested_device_name", 256)
    val requestedPlatform = varchar("requested_platform", 64)
    val requestedAgentType = varchar("requested_agent_type", 64)
    val requestedAgentDisplayName = varchar("requested_agent_display_name", 256)
    val requestedTransportAdapter = varchar("requested_transport_adapter", 32)
    val requestedConnectorVersion = varchar("requested_connector_version", 64)
    val requestedCapabilitiesJson = text("requested_capabilities_json")
    val intendedOwnerId = varchar("intended_owner_id", 128).nullable().index()
    val serverOrigin = varchar("server_origin", 512)
    val userCodeHash = varchar("user_code_hash", 64).uniqueIndex()
    val devicePollSecretHash = varchar("device_poll_secret_hash", 64).uniqueIndex()
    val state = varchar("state", 32)
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at").index()
    val approvedBy = varchar("approved_by", 128).nullable().index()
    val approvedAt = datetime("approved_at").nullable()
    val deviceId = varchar("device_id", 128).nullable()
    val agentInstanceId = varchar("agent_instance_id", 128).nullable()
    val proofChallengeId = varchar("proof_challenge_id", 128).nullable()
    val proofNonce = varchar("proof_nonce", 128).nullable()
    val proofExpiresAt = datetime("proof_expires_at").nullable()
    val consumedAt = datetime("consumed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
