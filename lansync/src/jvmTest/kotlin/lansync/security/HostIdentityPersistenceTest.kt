package lansync.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class HostIdentityPersistenceTest {
    @Test
    fun identityAndCertificatePinSurviveRestart() {
        val path = Files.createTempDirectory("simpmusic-host-identity").resolve("host.p12")
        val password = "test-only-password".toCharArray()

        val first = HostIdentityStore(path).loadOrCreate(password)
        val second = HostIdentityStore(path).loadOrCreate(password)

        assertEquals(first.publicKeyFingerprint, second.publicKeyFingerprint)
        assertEquals(first.certificate.encoded.toList(), second.certificate.encoded.toList())
        assertFails { HostIdentityStore(path).loadOrCreate("wrong-password".toCharArray()) }
    }
}
