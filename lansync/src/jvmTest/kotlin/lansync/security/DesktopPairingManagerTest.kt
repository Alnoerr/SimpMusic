package lansync.security

import kotlinx.coroutines.test.runTest
import lansync.model.DeviceInfo
import lansync.model.PairRequest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPairingManagerTest {
    @Test
    fun invitationIsSingleUseAndCredentialIsPerDevice() = runTest {
        val store = MemoryPeerStore()
        val manager = DesktopPairingManager("fingerprint", store)
        val invitation = manager.createInvitation()
        val request = PairRequest(
            invitationId = invitation.invitationId,
            invitationSecret = invitation.secret,
            client = DeviceInfo("phone", "Phone"),
        )

        val paired = manager.pair(request)

        assertNotNull(manager.authenticate(paired.accessToken))
        assertFailsWith<PairingException> { manager.pair(request) }
        assertNull(manager.authenticate("phone.wrong"))
    }

    @Test
    fun expiredInvitationAndRevokedCredentialAreRejected() = runTest {
        var now = 1_000L
        val manager = DesktopPairingManager(
            hostFingerprint = "fingerprint",
            peerStore = MemoryPeerStore(),
            nowMillis = { now },
            invitationLifetimeMillis = 100,
        )
        val expired = manager.createInvitation()
        now += 101
        assertFailsWith<PairingException> {
            manager.pair(
                PairRequest(
                    invitationId = expired.invitationId,
                    invitationSecret = expired.secret,
                    client = DeviceInfo("phone", "Phone"),
                ),
            )
        }

        val active = manager.createInvitation()
        val response = manager.pair(
            PairRequest(
                invitationId = active.invitationId,
                invitationSecret = active.secret,
                client = DeviceInfo("phone", "Phone"),
            ),
        )
        assertTrue(manager.revoke("phone"))
        assertNull(manager.authenticate(response.accessToken))
    }

    private class MemoryPeerStore : TrustedPeerStore {
        private var peers = emptyList<TrustedPeer>()
        override suspend fun readAll() = peers
        override suspend fun writeAll(peers: List<TrustedPeer>) {
            this.peers = peers
        }
    }
}
