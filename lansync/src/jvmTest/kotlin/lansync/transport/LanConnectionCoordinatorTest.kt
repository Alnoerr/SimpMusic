package lansync.transport

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import lansync.api.MusicLibraryAdapter
import lansync.discovery.DiscoveredLanHost
import lansync.discovery.DiscoveryState
import lansync.discovery.LanHostDiscovery
import lansync.model.DeviceInfo
import lansync.model.LibrarySnapshot
import lansync.security.DesktopPairingManager
import lansync.security.DeviceCredentials
import lansync.security.HostIdentityStore
import lansync.security.JsonLanCredentialStore
import lansync.security.JsonTrustedPeerStore
import lansync.server.InMemoryHostApplyCheckpointStore
import lansync.server.LanSyncHost
import lansync.server.LanSyncServer
import lansync.server.LanSyncServerConfig
import lansync.storage.InMemoryHostChangeLog
import lansync.storage.InMemorySyncJournal
import okio.Path.Companion.toOkioPath
import kotlin.test.Test
import kotlin.test.assertEquals

class LanConnectionCoordinatorTest {
    @Test
    fun manualAndDiscoveredConnectionsReuseThePinnedAuthenticatedSessionAndRecover() = runBlocking {
        val root = Files.createTempDirectory("lansync-coordinator")
        val identity = HostIdentityStore(root.resolve("host.p12"))
            .loadOrCreate("test-password".toCharArray())
        val pairing = DesktopPairingManager(
            identity.publicKeyFingerprint,
            JsonTrustedPeerStore(root.resolve("peers.json")),
        )
        val host = LanSyncHost(
            MemoryAdapter(),
            InMemoryHostChangeLog(),
            InMemoryHostApplyCheckpointStore(),
        )
        val server = LanSyncServer(
            LanSyncServerConfig(
                device = DeviceInfo("desktop", "Desktop"),
                keyStorePath = root.resolve("host.p12"),
            ),
            identity,
            host,
            pairing,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val discovery = FakeDiscovery()
        val store = JsonLanCredentialStore(root.resolve("credentials.json").toOkioPath())
        val creations = AtomicInteger()
        var coordinator: LanConnectionCoordinator? = null

        try {
            val port = server.start()
            val endpoint = LanEndpoint("127.0.0.1", port)
            val invitation = pairing.createInvitation()
            val bootstrap = LanSyncHttpClient(
                pinnedJvmHttpClient(identity.publicKeyFingerprint),
                endpoint,
                null,
            )
            val paired = bootstrap.pair(
                invitation.invitationId,
                invitation.secret,
                DeviceInfo("phone", "Phone"),
            )
            bootstrap.close()
            store.saveCredentials(
                DeviceCredentials(
                    localDeviceId = "phone",
                    pairedDesktopId = "desktop",
                    deviceAuthToken = paired.accessToken,
                    pinnedDesktopFingerprint = identity.publicKeyFingerprint,
                    lastKnownHost = endpoint.host,
                    lastKnownPort = endpoint.port,
                ),
            )

            coordinator = LanConnectionCoordinator(
                discovery = discovery,
                credentialStore = store,
                adapter = MemoryAdapter(),
                journal = InMemorySyncJournal(),
                clientFactory = { target, fingerprint, token ->
                    creations.incrementAndGet()
                    LanSyncHttpClient(pinnedJvmHttpClient(fingerprint), target, token)
                },
                scope = scope,
            )
            coordinator.start()
            await { coordinator.state.value == LanCoordinatorState.CONNECTED }
            assertEquals(1, creations.get())

            coordinator.connectManual("localhost", port)
            await { coordinator.state.value == LanCoordinatorState.CONNECTED && creations.get() == 2 }
            coordinator.connectManual("localhost", port)
            assertEquals(2, creations.get())

            server.stop()
            val restartedPort = server.start()
            discovery.publish(
                DiscoveredLanHost(
                    serviceName = "SimpMusic Sync",
                    deviceId = "desktop",
                    protocolVersion = 2,
                    addresses = listOf("127.0.0.1"),
                    port = restartedPort,
                ),
            )
            await { coordinator.state.value == LanCoordinatorState.CONNECTED && creations.get() == 3 }
            assertEquals("127.0.0.1", store.loadCredentials()?.lastKnownHost)
            assertEquals(restartedPort, store.loadCredentials()?.lastKnownPort)

            discovery.publish(
                DiscoveredLanHost(
                    serviceName = "SimpMusic Sync renamed",
                    deviceId = "desktop",
                    protocolVersion = 2,
                    addresses = listOf("127.0.0.1"),
                    port = restartedPort,
                ),
            )
            delay(300.milliseconds)
            assertEquals(3, creations.get())
        } finally {
            coordinator?.stop()
            server.stop()
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(15_000) {
            while (!condition()) delay(25)
        }
    }

    private class FakeDiscovery : LanHostDiscovery {
        override val hosts = MutableStateFlow<List<DiscoveredLanHost>>(emptyList())
        override val state = MutableStateFlow(DiscoveryState.STOPPED)

        override suspend fun start() {
            state.value = DiscoveryState.RUNNING
        }

        override suspend fun stop() {
            state.value = DiscoveryState.STOPPED
            hosts.value = emptyList()
        }

        fun publish(host: DiscoveredLanHost) {
            hosts.value = listOf(host)
        }
    }

    private class MemoryAdapter : MusicLibraryAdapter {
        private val snapshot = MutableStateFlow(LibrarySnapshot())

        override suspend fun readSnapshot(): LibrarySnapshot = snapshot.value

        override suspend fun applyRemoteChanges(snapshot: LibrarySnapshot) {
            this.snapshot.value = snapshot
        }

        override fun observeLibrary(): Flow<LibrarySnapshot> = snapshot
    }
}
