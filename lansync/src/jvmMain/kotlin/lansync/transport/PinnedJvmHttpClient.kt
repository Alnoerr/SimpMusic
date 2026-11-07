package lansync.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import lansync.security.HostIdentityStore

fun pinnedJvmHttpClient(
    expectedSpkiFingerprint: String,
    json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    },
): HttpClient {
    require(expectedSpkiFingerprint.isNotBlank()) { "A host identity fingerprint is required" }
    return HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(WebSockets) {
            pingIntervalMillis = 20_000
            maxFrameSize = 64L * 1024
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        engine {
            https {
                // The certificate is pinned to the host key, not its changeable LAN address.
                serverName = "simpmusic-lan"
                trustManager = PinnedTrustManager(expectedSpkiFingerprint)
            }
        }
    }
}

private class PinnedTrustManager(
    expectedFingerprint: String,
) : X509TrustManager {
    private val expected = runCatching { Base64.getDecoder().decode(expectedFingerprint) }
        .getOrElse { throw IllegalArgumentException("Host fingerprint is not valid Base64", it) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("Server certificate is missing")
        certificate.checkValidity()
        val actual = Base64.getDecoder().decode(HostIdentityStore.spkiFingerprint(certificate))
        if (!MessageDigest.isEqual(expected, actual)) {
            throw CertificateException("Pinned host identity mismatch")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted by this trust manager")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

fun pinnedJvmLanSyncClientFactory(): LanSyncClientFactory =
    LanSyncClientFactory { endpoint, fingerprint, token ->
        LanSyncHttpClient(pinnedJvmHttpClient(fingerprint), endpoint, token)
    }
