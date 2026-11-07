package lansync.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lansync.model.DeviceInfo
import lansync.model.LAN_PROTOCOL_VERSION
import lansync.model.PairRequest
import lansync.model.ProtocolError
import lansync.model.PushChangesRequest
import lansync.model.ServerEvent
import lansync.model.ServerInfo
import lansync.model.ServerStatus
import lansync.model.SnapshotResponse
import lansync.security.DesktopPairingManager
import lansync.security.PairingException
import lansync.security.TrustedPeer

internal fun Application.installLanSyncRoutes(
    device: DeviceInfo,
    fingerprint: String,
    host: LanSyncHost,
    pairingManager: DesktopPairingManager,
    json: Json,
    maxRequestBodyBytes: Long,
    maxWebSocketFrameBytes: Long,
) {
    install(ContentNegotiation) { json(json) }
    install(RequestBodyLimit) { bodyLimit { maxRequestBodyBytes } }
    install(WebSockets) {
        pingPeriodMillis = 20.seconds.inWholeMilliseconds
        timeoutMillis = 15.seconds.inWholeMilliseconds
        maxFrameSize = maxWebSocketFrameBytes
    }

    routing {
        route("/api/v1") {
            get("/info") {
                call.respond(
                    ServerInfo(
                        device = device,
                        certificateFingerprint = fingerprint,
                        pairingAvailable = true,
                    ),
                )
            }

            post("/pair") {
                val request = call.receiveLimited<PairRequest>(maxRequestBodyBytes)
                if (request.protocolVersion != LAN_PROTOCOL_VERSION) {
                    call.respond(HttpStatusCode.UpgradeRequired, ProtocolError("protocol_mismatch", "Unsupported protocol version"))
                    return@post
                }
                try {
                    call.respond(pairingManager.pair(request))
                } catch (_: PairingException) {
                    call.respond(HttpStatusCode.Unauthorized, ProtocolError("pairing_rejected", "Invitation is invalid or expired"))
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ProtocolError("invalid_request", "Pairing request is invalid"))
                }
            }

            get("/snapshot") {
                call.requirePeer(pairingManager) ?: return@get
                call.respond(SnapshotResponse(snapshot = host.snapshot(), latestSequence = host.latestSequence()))
            }

            get("/changes") {
                call.requirePeer(pairingManager) ?: return@get
                val after = call.request.queryParameters["after"]?.toLongOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: LanSyncHost.MAX_PULL_CHANGES
                if (after == null || after < 0 || limit !in 1..LanSyncHost.MAX_PULL_CHANGES) {
                    call.respond(HttpStatusCode.BadRequest, ProtocolError("invalid_cursor", "Invalid cursor or limit"))
                    return@get
                }
                call.respond(host.changesAfter(after, limit))
            }

            post("/changes") {
                val peer = call.requirePeer(pairingManager) ?: return@post
                val request = call.receiveLimited<PushChangesRequest>(maxRequestBodyBytes)
                if (request.protocolVersion != LAN_PROTOCOL_VERSION) {
                    call.respond(HttpStatusCode.UpgradeRequired, ProtocolError("protocol_mismatch", "Unsupported protocol version"))
                    return@post
                }
                try {
                    call.respond(host.acceptChanges(peer.deviceId, request.changes))
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ProtocolError("invalid_change", "One or more changes are invalid"))
                }
            }

            get("/status") {
                val peer = call.requirePeer(pairingManager) ?: return@get
                call.respond(ServerStatus(latestSequence = host.latestSequence(), authenticatedDeviceId = peer.deviceId))
            }

            webSocket("/events") {
                val credential = call.bearerCredential()
                val peer = pairingManager.authenticate(credential)
                if (peer == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }
                send(
                    Frame.Text(
                        json.encodeToString<ServerEvent>(
                            ServerEvent.ChangesAvailable(latestSequence = host.latestSequence()),
                        ),
                    ),
                )
                coroutineScope {
                    launch {
                        host.changes.collect { sequence ->
                            send(
                                Frame.Text(
                                    json.encodeToString<ServerEvent>(
                                        ServerEvent.ChangesAvailable(latestSequence = sequence),
                                    ),
                                ),
                            )
                        }
                    }
                    launch {
                        pairingManager.revocations.filter { it == peer.deviceId }.collect {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device revoked"))
                        }
                    }
                }
            }
        }
    }
}

private suspend inline fun <reified T> ApplicationCall.receiveLimited(limit: Long): T =
    try {
        receive<T>()
    } catch (error: BadRequestException) {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is PayloadTooLargeException) throw PayloadTooLargeException(limit)
            cause = cause.cause
        }
        throw error
    }

private suspend fun ApplicationCall.requirePeer(pairingManager: DesktopPairingManager): TrustedPeer? {
    val peer = pairingManager.authenticate(bearerCredential())
    if (peer == null) {
        respond(HttpStatusCode.Unauthorized, ProtocolError("unauthorized", "Valid device credentials are required"))
    }
    return peer
}

private fun ApplicationCall.bearerCredential(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

