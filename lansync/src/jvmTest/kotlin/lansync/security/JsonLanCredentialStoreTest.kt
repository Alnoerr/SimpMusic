package lansync.security

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonLanCredentialStoreTest {
    @Test
    fun credentialsPersistReloadAndDeleteWithoutLeavingTemporaryFiles() = runBlocking {
        val root = Files.createTempDirectory("lansync-credentials")
        try {
            val path = root.resolve("credentials.json")
            val credentials = credentials()
            JsonLanCredentialStore(path.toOkioPath()).saveCredentials(credentials)

            assertEquals(credentials, JsonLanCredentialStore(path.toOkioPath()).loadCredentials())
            assertFalse(credentials.toString().contains(credentials.deviceAuthToken))
            assertFalse(Files.exists(root.resolve(".credentials.json.tmp")))

            JsonLanCredentialStore(path.toOkioPath()).clearCredentials()
            assertNull(JsonLanCredentialStore(path.toOkioPath()).loadCredentials())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptOrOversizedCredentialFilesAreRejected() = runBlocking {
        val root = Files.createTempDirectory("lansync-corrupt-credentials")
        try {
            val path = root.resolve("credentials.json")
            Files.writeString(path, "{not-json")
            assertFailsWith<IllegalStateException> {
                JsonLanCredentialStore(path.toOkioPath()).loadCredentials()
            }

            Files.writeString(path, "x".repeat(70 * 1024))
            assertFailsWith<IllegalStateException> {
                JsonLanCredentialStore(path.toOkioPath()).loadCredentials()
            }
            assertTrue(FileSystem.SYSTEM.exists(path.toOkioPath()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun credentials() = DeviceCredentials(
        localDeviceId = "phone",
        pairedDesktopId = "desktop",
        deviceAuthToken = "phone." + "secret".repeat(8),
        pinnedDesktopFingerprint = "fingerprint",
        lastKnownHost = "192.168.1.10",
        lastKnownPort = 42424,
    )
}
