package lansync.security

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import lansync.model.DeviceInfo
import lansync.model.PairRequest

class TemporaryPairingTest {
    @Test
    fun uncheckedPairingAuthenticatesTemporarilyWithoutPersistingTrust() = runTest {
        var now = 1_000L
        val store = InMemoryTrustedPeerStore()
        val manager = DesktopPairingManager(
            hostFingerprint = "pin",
            peerStore = store,
            nowMillis = { now },
            invitationLifetimeMillis = 1_000,
            temporarySessionLifetimeMillis = 2_000,
        )
        val invitation = manager.createInvitation()
        val response = manager.pair(
            PairRequest(
                invitationId = invitation.invitationId,
                invitationSecret = invitation.secret,
                client = DeviceInfo("phone", "Phone"),
                rememberDevice = false,
            ),
        )

        assertNotNull(manager.authenticate(response.accessToken))
        assertTrue(store.readAll().isEmpty())
        now += 2_001
        assertNull(manager.authenticate(response.accessToken))
    }
}

private class InMemoryTrustedPeerStore : TrustedPeerStore {
    private var peers = emptyList<TrustedPeer>()
    override suspend fun readAll(): List<TrustedPeer> = peers
    override suspend fun writeAll(peers: List<TrustedPeer>) {
        this.peers = peers
    }
}
