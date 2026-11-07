package lansync.api

import kotlinx.coroutines.flow.StateFlow

enum class LanSyncStatus {
    DISABLED,
    NOT_PAIRED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    SYNCHRONIZING,
    UP_TO_DATE,
    HOST_UNAVAILABLE,
    PERMISSION_REQUIRED,
    AUTHENTICATION_FAILED,
    PROTOCOL_MISMATCH,
    ERROR,
}

data class TrustedDeviceSummary(
    val deviceId: String,
    val displayName: String,
    val connected: Boolean = false,
    val available: Boolean = false,
    val autoSync: Boolean = true,
    val lastSuccessfulSyncMillis: Long? = null,
)

data class LanSyncUiState(
    val enabled: Boolean = false,
    val status: LanSyncStatus = LanSyncStatus.DISABLED,
    val connectedDeviceName: String? = null,
    val availableDeviceCount: Int = 0,
    val lastSuccessfulSyncMillis: Long? = null,
    val pendingChanges: Int = 0,
    val error: String? = null,
    val serverAddress: String? = null,
    val serverPort: Int? = null,
)

interface LanSyncController {
    val state: StateFlow<LanSyncUiState>
    val trustedDevices: StateFlow<List<TrustedDeviceSummary>>

    suspend fun initialize()
    suspend fun setEnabled(enabled: Boolean)
    suspend fun syncNow()
    suspend fun pair(qrPayload: String, trustDevice: Boolean)
    suspend fun createPairingQrPayload(): String
    suspend fun cancelPairing()
    suspend fun disconnect()
    suspend fun forgetDevice(deviceId: String)
    suspend fun renameDevice(deviceId: String, displayName: String)
    suspend fun setDeviceAutoSync(deviceId: String, enabled: Boolean)
}
