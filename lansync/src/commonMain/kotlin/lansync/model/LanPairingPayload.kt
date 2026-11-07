package lansync.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lansync.transport.LanEndpoint

private const val LAN_PAIRING_PREFIX = "simpmusic-lan://pair/v1?"

@Serializable
data class LanPairingPayload(
    val protocolVersion: Int = LAN_PROTOCOL_VERSION,
    val hostDeviceId: String,
    val hostDisplayName: String,
    val host: String,
    val port: Int,
    val certificateFingerprint: String,
    val invitationId: String,
    val invitationSecret: String,
    val expiresAtEpochMillis: Long,
) {
    val endpoint: LanEndpoint get() = LanEndpoint(host, port)

    fun requireUsable(nowEpochMillis: Long) {
        require(protocolVersion == LAN_PROTOCOL_VERSION) { "Unsupported LAN pairing protocol" }
        require(hostDeviceId.isNotBlank() && hostDisplayName.isNotBlank()) { "Host identity is incomplete" }
        require(certificateFingerprint.isNotBlank()) { "Host fingerprint is missing" }
        require(invitationId.isNotBlank() && invitationSecret.isNotBlank()) { "Pairing invitation is incomplete" }
        require(expiresAtEpochMillis >= nowEpochMillis) { "Pairing invitation has expired" }
    }
}

object LanPairingPayloadCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(payload: LanPairingPayload): String =
        LAN_PAIRING_PREFIX + percentEncode(json.encodeToString(LanPairingPayload.serializer(), payload))

    fun decode(value: String): LanPairingPayload {
        require(value.startsWith(LAN_PAIRING_PREFIX)) { "This is not a SimpMusic LAN pairing code" }
        return try {
            json.decodeFromString(
                LanPairingPayload.serializer(),
                percentDecode(value.removePrefix(LAN_PAIRING_PREFIX)),
            )
        } catch (cause: Throwable) {
            throw IllegalArgumentException("LAN pairing code is invalid", cause)
        }
    }

    fun isLanPairingPayload(value: String): Boolean = value.startsWith(LAN_PAIRING_PREFIX)

    private fun percentEncode(value: String): String =
        value.encodeToByteArray().joinToString("") { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (char.isLetterOrDigit() || char in "-._~") char.toString() else "%${unsigned.toString(16).uppercase().padStart(2, '0')}"
        }

    private fun percentDecode(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                require(index + 2 < value.length) { "Incomplete percent escape" }
                bytes += value.substring(index + 1, index + 3).toInt(16).toByte()
                index += 3
            } else {
                bytes += value[index].code.toByte()
                index++
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
