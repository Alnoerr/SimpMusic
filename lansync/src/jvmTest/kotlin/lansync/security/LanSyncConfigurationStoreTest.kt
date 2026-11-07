package lansync.security

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath

class LanSyncConfigurationStoreTest {
    @Test
    fun enablementAndStableDeviceIdentitySurviveRestart() = runTest {
        val path = createTempDirectory("lansync-config").resolve("configuration.json")
        val expected = LanSyncConfiguration(
            enabled = true,
            localDeviceId = "phone-stable",
            lastSuccessfulSyncMillis = 42,
        )

        JsonLanSyncConfigurationStore(path.toString().toPath()).write(expected)

        assertEquals(expected, JsonLanSyncConfigurationStore(path.toString().toPath()).read())
    }
}
