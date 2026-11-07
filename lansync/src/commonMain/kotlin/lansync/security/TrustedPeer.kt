package lansync.security

import kotlinx.serialization.Serializable

@Serializable
data class TrustedPeer(
    val deviceId: String,
    val displayName: String,
    val credentialVerifier: String,
    val pairedAtEpochMillis: Long,
    val autoSync: Boolean = true,
    val lastSuccessfulSyncMillis: Long? = null,
    val revoked: Boolean = false,
)

interface TrustedPeerStore {
    suspend fun readAll(): List<TrustedPeer>
    suspend fun writeAll(peers: List<TrustedPeer>)
}
