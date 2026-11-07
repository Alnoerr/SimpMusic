package lansync.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lansync.model.PairRequest
import lansync.model.PairResponse

data class PairingInvitation(
    val invitationId: String,
    val secret: String,
    val hostFingerprint: String,
    val expiresAtEpochMillis: Long,
)

class PairingException(message: String) : IllegalArgumentException(message)

class DesktopPairingManager(
    private val hostFingerprint: String,
    private val peerStore: TrustedPeerStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val invitationLifetimeMillis: Long = 2 * 60_000L,
    private val temporarySessionLifetimeMillis: Long = 8 * 60 * 60_000L,
) {
    private val mutex = Mutex()
    private val invitations = mutableMapOf<String, PendingInvitation>()
    private val temporaryPeers = mutableMapOf<String, TemporaryPeer>()
    private val _revocations = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val revocations: SharedFlow<String> = _revocations.asSharedFlow()

    suspend fun createInvitation(): PairingInvitation = mutex.withLock {
        pruneExpiredLocked()
        val id = randomToken(16)
        val secret = randomToken(32)
        invitations[id] = PendingInvitation(
            secretVerifier = digest(secret),
            expiresAtEpochMillis = nowMillis() + invitationLifetimeMillis,
        )
        PairingInvitation(id, secret, hostFingerprint, invitations.getValue(id).expiresAtEpochMillis)
    }

    suspend fun cancelInvitation(invitationId: String) {
        mutex.withLock { invitations.remove(invitationId) }
    }

    suspend fun pair(request: PairRequest): PairResponse = mutex.withLock {
        pruneExpiredLocked()
        val pending = invitations[request.invitationId] ?: throw PairingException("Invitation is invalid or expired")
        pending.attempts++
        if (pending.attempts > MAX_ATTEMPTS) {
            invitations.remove(request.invitationId)
            throw PairingException("Invitation is invalid or expired")
        }
        if (!MessageDigest.isEqual(pending.secretVerifier, digest(request.invitationSecret))) {
            throw PairingException("Invitation is invalid or expired")
        }
        require(request.client.deviceId.isNotBlank() && request.client.displayName.isNotBlank()) {
            "Client identity is incomplete"
        }
        invitations.remove(request.invitationId)
        val credentialSecret = randomToken(32)
        val credential = "${request.client.deviceId}.$credentialSecret"
        val peer = TrustedPeer(
            deviceId = request.client.deviceId,
            displayName = request.client.displayName,
            credentialVerifier = Base64.getEncoder().encodeToString(digest(credentialSecret)),
            pairedAtEpochMillis = nowMillis(),
        )
        if (request.rememberDevice) {
            val peers = peerStore.readAll().filterNot { it.deviceId == request.client.deviceId } + peer
            peerStore.writeAll(peers)
        } else {
            temporaryPeers[peer.deviceId] = TemporaryPeer(
                peer = peer,
                expiresAtEpochMillis = nowMillis() + temporarySessionLifetimeMillis,
            )
        }
        PairResponse(
            peerId = request.client.deviceId,
            accessToken = credential,
            certificateFingerprint = hostFingerprint,
        )
    }

    suspend fun authenticate(credential: String?): TrustedPeer? = mutex.withLock {
        pruneExpiredLocked()
        val value = credential?.takeIf { it.length <= MAX_CREDENTIAL_LENGTH } ?: return@withLock null
        val separator = value.indexOf('.')
        if (separator <= 0 || separator == value.lastIndex) return@withLock null
        val deviceId = value.substring(0, separator)
        val secret = value.substring(separator + 1)
        val persistent = peerStore.readAll().firstOrNull { it.deviceId == deviceId && !it.revoked }
        val peer = persistent ?: temporaryPeers[deviceId]?.peer ?: return@withLock null
        val expected = runCatching { Base64.getDecoder().decode(peer.credentialVerifier) }.getOrNull() ?: return@withLock null
        peer.takeIf { MessageDigest.isEqual(expected, digest(secret)) }
    }

    suspend fun revoke(deviceId: String): Boolean = mutex.withLock {
        val peers = peerStore.readAll()
        val index = peers.indexOfFirst { it.deviceId == deviceId && !it.revoked }
        val removedTemporary = temporaryPeers.remove(deviceId) != null
        if (index < 0 && !removedTemporary) return@withLock false
        if (index >= 0) {
            peerStore.writeAll(peers.map { if (it.deviceId == deviceId) it.copy(revoked = true) else it })
        }
        _revocations.tryEmit(deviceId)
        true
    }

    suspend fun rename(deviceId: String, displayName: String) = update(deviceId) {
        it.copy(displayName = displayName.trim().takeIf(String::isNotEmpty) ?: it.displayName)
    }

    suspend fun setAutoSync(deviceId: String, enabled: Boolean) = update(deviceId) {
        it.copy(autoSync = enabled)
    }

    suspend fun trustedDevices(): List<TrustedPeer> = peerStore.readAll().filterNot(TrustedPeer::revoked)

    private suspend fun update(deviceId: String, transform: (TrustedPeer) -> TrustedPeer) = mutex.withLock {
        val peers = peerStore.readAll()
        require(peers.any { it.deviceId == deviceId && !it.revoked }) { "Trusted device was not found" }
        peerStore.writeAll(peers.map { if (it.deviceId == deviceId) transform(it) else it })
    }

    private fun pruneExpiredLocked() {
        val now = nowMillis()
        invitations.entries.removeAll { it.value.expiresAtEpochMillis < now }
        temporaryPeers.entries.removeAll { it.value.expiresAtEpochMillis < now }
    }

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(secureRandom::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())

    private data class PendingInvitation(
        val secretVerifier: ByteArray,
        val expiresAtEpochMillis: Long,
        var attempts: Int = 0,
    )

    private data class TemporaryPeer(
        val peer: TrustedPeer,
        val expiresAtEpochMillis: Long,
    )

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val MAX_CREDENTIAL_LENGTH = 512
    }
}
