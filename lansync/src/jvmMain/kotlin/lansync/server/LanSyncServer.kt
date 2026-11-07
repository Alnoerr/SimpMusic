package lansync.server

import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import lansync.discovery.LanServiceAdvertiser
import lansync.model.DeviceInfo
import lansync.security.DesktopPairingManager
import lansync.security.HostIdentity

data class LanSyncServerConfig(
    val bindAddress: String = "127.0.0.1",
    val port: Int = 0,
    val device: DeviceInfo,
    val keyStorePath: Path? = null,
    val maxRequestBodyBytes: Long = 2L * 1024 * 1024,
    val maxWebSocketFrameBytes: Long = 64L * 1024,
    val requestReadTimeoutSeconds: Int = 30,
    val responseWriteTimeoutSeconds: Int = 30,
) {
    init {
        require(maxRequestBodyBytes > 0) { "Request body limit must be positive" }
        require(maxWebSocketFrameBytes > 0) { "WebSocket frame limit must be positive" }
        require(requestReadTimeoutSeconds > 0) { "Request read timeout must be positive" }
        require(responseWriteTimeoutSeconds > 0) { "Response write timeout must be positive" }
    }
}

class LanSyncServer(
    private val config: LanSyncServerConfig,
    private val identity: HostIdentity,
    private val host: LanSyncHost,
    val pairingManager: DesktopPairingManager,
    private val advertiser: LanServiceAdvertiser? = null,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    },
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    var boundPort: Int = 0
        private set

    suspend fun start(): Int {
        check(server == null) { "LAN sync server is already running" }
        host.recover()
        val created = embeddedServer(
            factory = Netty,
            environment = applicationEnvironment { },
            configure = {
                shutdownGracePeriod = 2.seconds.inWholeMilliseconds
                shutdownTimeout = 5.seconds.inWholeMilliseconds
                requestReadTimeoutSeconds = config.requestReadTimeoutSeconds
                responseWriteTimeoutSeconds = config.responseWriteTimeoutSeconds
                sslConnector(
                    keyStore = identity.keyStore,
                    keyAlias = identity.keyAlias,
                    keyStorePassword = { identity.password.copyOf() },
                    privateKeyPassword = { identity.password.copyOf() },
                ) {
                    host = config.bindAddress
                    port = config.port
                    config.keyStorePath?.let { keyStorePath = it.toFile() }
                }
            },
            module = {
                installLanSyncRoutes(
                    device = config.device,
                    fingerprint = identity.publicKeyFingerprint,
                    host = host,
                    pairingManager = pairingManager,
                    json = json,
                    maxRequestBodyBytes = config.maxRequestBodyBytes,
                    maxWebSocketFrameBytes = config.maxWebSocketFrameBytes,
                )
            },
        )
        created.start(wait = false)
        boundPort = created.engine.resolvedConnectors().single().port
        server = created
        advertiser?.start(boundPort, config.device.deviceId)
        return boundPort
    }

    suspend fun stop() {
        val current = server ?: return
        server = null
        advertiser?.stop()
        current.stop(2.seconds.inWholeMilliseconds, 5.seconds.inWholeMilliseconds)
        boundPort = 0
    }
}
