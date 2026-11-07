package lansync.server

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import lansync.api.MusicLibraryAdapter
import lansync.model.DeviceInfo
import lansync.model.LibrarySnapshot
import lansync.security.DesktopPairingManager
import lansync.security.HostIdentityStore
import lansync.security.JsonTrustedPeerStore
import lansync.storage.InMemoryHostChangeLog
import lansync.transport.LanEndpoint
import lansync.transport.LanSyncHttpClient
import lansync.transport.pinnedJvmHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LanSyncRequestLimitsTest {
    @Test
    fun normalPairingWorksAndMalformedJsonDoesNotBreakServer() = runBlocking {
        val fixture = Fixture()
        try {
            val port = fixture.server.start()
            val endpoint = LanEndpoint("127.0.0.1", port)
            val invitation = fixture.pairing.createInvitation()
            val client = LanSyncHttpClient(
                pinnedJvmHttpClient(fixture.fingerprint),
                endpoint,
                null,
            )
            client.pair(invitation.invitationId, invitation.secret, DeviceInfo("phone", "Phone"))

            val raw = pinnedJvmHttpClient(fixture.fingerprint)
            val malformed = assertFailsWith<ClientRequestException> {
                raw.post("${endpoint.baseUrl}/pair") {
                    contentType(ContentType.Application.Json)
                    setBody("{broken")
                }
            }
            assertEquals(HttpStatusCode.BadRequest, malformed.response.status)
            assertEquals(fixture.fingerprint, client.serverInfo().certificateFingerprint)
            raw.close()
            client.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun fixedLengthAndChunkedOversizedBodiesReturn413() = runBlocking {
        val fixture = Fixture(maxRequestBytes = 512)
        try {
            val port = fixture.server.start()
            val endpoint = LanEndpoint("127.0.0.1", port)
            val client = pinnedJvmHttpClient(fixture.fingerprint)

            val fixed = assertFailsWith<ClientRequestException> {
                client.post("${endpoint.baseUrl}/pair") {
                    contentType(ContentType.Application.Json)
                    setBody("x".repeat(1024))
                }
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, fixed.response.status)

            val chunked = assertFailsWith<ClientRequestException> {
                client.post("${endpoint.baseUrl}/pair") {
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override val contentType: ContentType = ContentType.Application.Json

                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                repeat(5) { channel.writeFully(ByteArray(128) { 'x'.code.toByte() }) }
                            }
                        },
                    )
                }
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, chunked.response.status)
            client.close()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(maxRequestBytes: Long = 2L * 1024 * 1024) {
        private val root = Files.createTempDirectory("lansync-request-limits")
        private val identity = HostIdentityStore(root.resolve("host.p12"))
            .loadOrCreate("test-password".toCharArray())
        val fingerprint = identity.publicKeyFingerprint
        val pairing = DesktopPairingManager(
            fingerprint,
            JsonTrustedPeerStore(root.resolve("peers.json")),
        )
        private val adapter = MemoryAdapter()
        private val host = LanSyncHost(
            adapter,
            InMemoryHostChangeLog(),
            InMemoryHostApplyCheckpointStore(),
        )
        val server = LanSyncServer(
            LanSyncServerConfig(
                device = DeviceInfo("desktop", "Desktop"),
                keyStorePath = root.resolve("host.p12"),
                maxRequestBodyBytes = maxRequestBytes,
            ),
            identity,
            host,
            pairing,
        )

        suspend fun close() {
            server.stop()
            root.toFile().deleteRecursively()
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
