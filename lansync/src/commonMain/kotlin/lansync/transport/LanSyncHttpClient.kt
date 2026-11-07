package lansync.transport

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import lansync.api.LanSyncTransport
import lansync.model.ChangeBatch
import lansync.model.DeviceInfo
import lansync.model.LAN_PROTOCOL_VERSION
import lansync.model.LibrarySnapshot
import lansync.model.PairRequest
import lansync.model.PairResponse
import lansync.model.PushChangesRequest
import lansync.model.PushChangesResponse
import lansync.model.ServerEvent
import lansync.model.ServerInfo
import lansync.model.ServerStatus
import lansync.model.SnapshotResponse
import lansync.model.SyncChange

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    TLS_VERIFICATION_FAILED,
    AUTHENTICATION_FAILED,
    CONNECTED,
    SYNCHRONIZING,
    UP_TO_DATE,
    NETWORK_UNAVAILABLE,
    PROTOCOL_MISMATCH,
    ERROR,
}

data class LanEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65535) { "Port is invalid" }
    }

    private val urlHost: String get() = if (':' in host && !host.startsWith('[')) "[$host]" else host

    val baseUrl: String get() = "https://$urlHost:$port/api/v1"
    val webSocketUrl: String get() = "wss://$urlHost:$port/api/v1/events"
}

class LanSyncHttpClient(
    private val httpClient: HttpClient,
    private val endpoint: LanEndpoint,
    private val credential: String?,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    },
) : LanSyncTransport {
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    suspend fun serverInfo(): ServerInfo = guarded {
        httpClient.get("${endpoint.baseUrl}/info").body<ServerInfo>().also(::checkProtocol)
    }

    suspend fun pair(
        invitationId: String,
        secret: String,
        client: DeviceInfo,
        rememberDevice: Boolean = true,
    ): PairResponse = guarded {
        httpClient.post("${endpoint.baseUrl}/pair") {
            contentType(ContentType.Application.Json)
            setBody(
                PairRequest(
                    invitationId = invitationId,
                    invitationSecret = secret,
                    client = client,
                    rememberDevice = rememberDevice,
                ),
            )
        }.body<PairResponse>()
    }

    suspend fun serverStatus(): ServerStatus = authenticated {
        httpClient.get("${endpoint.baseUrl}/status") { bearerAuth(requireCredential()) }
            .body<ServerStatus>()
            .also { checkProtocol(it.protocolVersion) }
    }

    override suspend fun getSnapshot(): LibrarySnapshot = authenticated {
        _state.value = ConnectionState.SYNCHRONIZING
        httpClient.get("${endpoint.baseUrl}/snapshot") { bearerAuth(requireCredential()) }
            .body<SnapshotResponse>()
            .also { checkProtocol(it.protocolVersion) }
            .snapshot
            .also { _state.value = ConnectionState.UP_TO_DATE }
    }

    override suspend fun getChanges(afterSequence: Long, limit: Int): ChangeBatch = authenticated {
        require(afterSequence >= 0)
        _state.value = ConnectionState.SYNCHRONIZING
        httpClient.get("${endpoint.baseUrl}/changes") {
            bearerAuth(requireCredential())
            parameter("after", afterSequence)
            parameter("limit", limit)
        }.body<ChangeBatch>().also {
            checkProtocol(it.protocolVersion)
            _state.value = ConnectionState.UP_TO_DATE
        }
    }

    override suspend fun pushChanges(changes: List<SyncChange>): PushChangesResponse = authenticated {
        _state.value = ConnectionState.SYNCHRONIZING
        httpClient.post("${endpoint.baseUrl}/changes") {
            bearerAuth(requireCredential())
            contentType(ContentType.Application.Json)
            setBody(PushChangesRequest(changes = changes))
        }.body<PushChangesResponse>().also { _state.value = ConnectionState.UP_TO_DATE }
    }

    override fun observeRemoteChanges(): Flow<Long> = channelFlow {
        val latestObservedSequence = MutableStateFlow(-1L)
        launch {
            var backoffSeconds = 1L
            while (currentCoroutineContext().isActive) {
                try {
                    _state.value = ConnectionState.CONNECTING
                    httpClient.webSocket(
                        urlString = endpoint.webSocketUrl,
                        request = { bearerAuth(requireCredential()) },
                    ) {
                        _state.value = ConnectionState.CONNECTED
                        backoffSeconds = 1
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            val event = json.decodeFromString<ServerEvent>(text)
                            checkProtocol(event.protocolVersion)
                            if (event is ServerEvent.ChangesAvailable &&
                                event.latestSequence > latestObservedSequence.value
                            ) {
                                latestObservedSequence.value = event.latestSequence
                                this@channelFlow.send(event.latestSequence)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    _state.value = ConnectionState.NETWORK_UNAVAILABLE
                    delay(backoffSeconds.seconds)
                    backoffSeconds = min(backoffSeconds * 2, 30)
                }
            }
        }
        launch {
            while (currentCoroutineContext().isActive) {
                delay(REMOTE_STATUS_FALLBACK_INTERVAL)
                val sequence = runCatching { serverStatus().latestSequence }.getOrNull() ?: continue
                if (sequence > latestObservedSequence.value) {
                    latestObservedSequence.value = sequence
                    this@channelFlow.send(sequence)
                }
            }
        }
    }

    override suspend fun close() {
        _state.value = ConnectionState.DISCONNECTED
        httpClient.close()
    }

    private suspend fun <T> authenticated(block: suspend () -> T): T {
        requireCredential()
        return guarded(block)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        _state.value = ConnectionState.CONNECTING
        return try {
            block().also {
                if (_state.value == ConnectionState.CONNECTING) _state.value = ConnectionState.CONNECTED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = when {
                error.message.orEmpty().contains("certificate", ignoreCase = true) ->
                    ConnectionState.TLS_VERIFICATION_FAILED
                error.message.orEmpty().contains("401") ->
                    ConnectionState.AUTHENTICATION_FAILED
                else -> ConnectionState.ERROR
            }
            throw error
        }
    }

    private fun requireCredential(): String = credential?.takeIf(String::isNotBlank)
        ?: error("Paired-device credential is required")

    private fun checkProtocol(info: ServerInfo) = checkProtocol(info.protocolVersion)

    private fun checkProtocol(version: Int) {
        if (version != LAN_PROTOCOL_VERSION) {
            _state.value = ConnectionState.PROTOCOL_MISMATCH
            error("Unsupported LAN sync protocol version: $version")
        }
    }

    private companion object {
        val REMOTE_STATUS_FALLBACK_INTERVAL = 15.seconds
    }
}
