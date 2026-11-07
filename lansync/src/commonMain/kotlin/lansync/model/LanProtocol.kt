package lansync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val displayName: String,
)

@Serializable
data class ServerInfo(
    val protocolVersion: Int = LAN_PROTOCOL_VERSION,
    val schemaVersion: Int = 2,
    val device: DeviceInfo,
    val certificateFingerprint: String,
    val pairingAvailable: Boolean,
    val capabilities: Set<String> = setOf(
        "snapshot",
        "incremental_changes",
        "websocket_events",
        "favorites",
        "local_playlists",
        "playlist_entry_ordering",
        "saved_playlist_references",
    ),
)

@Serializable
data class PairRequest(
    val protocolVersion: Int = LAN_PROTOCOL_VERSION,
    val invitationId: String,
    val invitationSecret: String,
    val client: DeviceInfo,
    val rememberDevice: Boolean = true,
)

@Serializable
data class PairResponse(
    val peerId: String,
    val accessToken: String,
    val certificateFingerprint: String,
)

@Serializable
data class ChangeBatch(
    val protocolVersion: Int = LAN_PROTOCOL_VERSION,
    val afterSequence: Long,
    val latestSequence: Long,
    val changes: List<SequencedChange>,
    val snapshotRequired: Boolean = false,
)

@Serializable
data class PushChangesRequest(
    val protocolVersion: Int = LAN_PROTOCOL_VERSION,
    val changes: List<SyncChange>,
)

@Serializable
data class PushChangesResponse(
    val latestSequence: Long,
    val acknowledgedOperationIds: Set<String>,
)

@Serializable
data class SnapshotResponse(
    val protocolVersion: Int = LAN_PROTOCOL_VERSION,
    val snapshot: LibrarySnapshot,
    val latestSequence: Long,
)

@Serializable
data class ServerStatus(
    val protocolVersion: Int = LAN_PROTOCOL_VERSION,
    val latestSequence: Long,
    val authenticatedDeviceId: String,
)

@Serializable
sealed interface ServerEvent {
    val protocolVersion: Int

    @Serializable
    @SerialName("changes_available")
    data class ChangesAvailable(
        override val protocolVersion: Int = LAN_PROTOCOL_VERSION,
        val latestSequence: Long,
    ) : ServerEvent
}

@Serializable
data class ProtocolError(
    val code: String,
    val message: String,
    val supportedProtocolVersion: Int = LAN_PROTOCOL_VERSION,
)
