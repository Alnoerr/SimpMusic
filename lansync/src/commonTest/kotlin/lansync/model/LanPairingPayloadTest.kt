package lansync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LanPairingPayloadTest {
    @Test
    fun versionedPayloadRoundTripsAndRejectsOtherQrCodes() {
        val original = LanPairingPayload(
            hostDeviceId = "desktop-1",
            hostDisplayName = "Living Room PC",
            host = "192.168.1.20",
            port = 8443,
            certificateFingerprint = "fingerprint+/=",
            invitationId = "invite",
            invitationSecret = "secret",
            expiresAtEpochMillis = 2_000,
        )

        val encoded = LanPairingPayloadCodec.encode(original)

        assertTrue(LanPairingPayloadCodec.isLanPairingPayload(encoded))
        assertEquals(original, LanPairingPayloadCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> { LanPairingPayloadCodec.decode("ordinary-account-code") }
    }

    @Test
    fun expiredInvitationIsRejectedBeforeNetworkUse() {
        val payload = LanPairingPayload(
            hostDeviceId = "desktop-1",
            hostDisplayName = "Desktop",
            host = "10.0.0.2",
            port = 443,
            certificateFingerprint = "fingerprint",
            invitationId = "invite",
            invitationSecret = "secret",
            expiresAtEpochMillis = 99,
        )

        assertFailsWith<IllegalArgumentException> { payload.requireUsable(100) }
    }
}
