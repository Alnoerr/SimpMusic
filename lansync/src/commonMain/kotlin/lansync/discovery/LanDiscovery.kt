package lansync.discovery

import kotlinx.coroutines.flow.StateFlow
import lansync.model.LAN_PROTOCOL_VERSION
import lansync.transport.LanEndpoint

const val LAN_SYNC_SERVICE_TYPE = "_simpmusic._tcp.local."
const val LAN_SYNC_ANDROID_SERVICE_TYPE = "_simpmusic._tcp."

data class DiscoveredLanHost(
    val serviceName: String,
    val deviceId: String,
    val protocolVersion: Int,
    val addresses: List<String>,
    val port: Int,
) {
    init {
        require(serviceName.isNotBlank()) { "Service name is required" }
        require(deviceId.isNotBlank()) { "Device ID is required" }
        require(addresses.isNotEmpty()) { "At least one host address is required" }
        require(port in 1..65535) { "Port is invalid" }
    }

    val compatible: Boolean get() = protocolVersion == LAN_PROTOCOL_VERSION
    val endpoints: List<LanEndpoint> get() = addresses.distinct().map { LanEndpoint(it, port) }
}

enum class DiscoveryState {
    STOPPED,
    STARTING,
    RUNNING,
    PERMISSION_DENIED,
    ERROR,
}

interface LanHostDiscovery {
    val hosts: StateFlow<List<DiscoveredLanHost>>
    val state: StateFlow<DiscoveryState>
    suspend fun start()
    suspend fun stop()
}
