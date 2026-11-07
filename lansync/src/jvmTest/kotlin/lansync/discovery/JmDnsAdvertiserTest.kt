package lansync.discovery

import java.net.InetAddress
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class JmDnsAdvertiserTest {
    @Test
    fun registrationIsSingleAndCleanlyReleased() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val advertiser = JmDnsAdvertiser(
            scope = scope,
            addressProvider = { listOf(InetAddress.getLoopbackAddress()) },
            refreshInterval = 1.hours,
        )
        try {
            advertiser.start(42424, "desktop-test")
            advertiser.start(42424, "desktop-test")
            assertEquals(1, advertiser.activeRegistrationCount)

            advertiser.stop()
            assertEquals(0, advertiser.activeRegistrationCount)

            advertiser.start(42424, "desktop-test")
            assertEquals(1, advertiser.activeRegistrationCount)
        } finally {
            advertiser.stop()
            scope.cancel()
        }
    }
}
