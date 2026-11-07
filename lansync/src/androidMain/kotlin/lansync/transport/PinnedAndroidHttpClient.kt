package lansync.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

fun pinnedAndroidHttpClient(
    expectedSpkiFingerprint: String,
    json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    },
): HttpClient {
    val pin = runCatching { Base64.getDecoder().decode(expectedSpkiFingerprint) }
        .getOrElse { throw IllegalArgumentException("Host fingerprint is not valid Base64", it) }
    require(pin.isNotEmpty()) { "A host identity fingerprint is required" }

    val trustManager = AndroidPinnedTrustManager(pin)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }
    val okHttp = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .hostnameVerifier { _, session -> session.matchesPin(pin) }
        .build()

    return HttpClient(OkHttp) {
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
            preconfigured = okHttp
        }
    }
}

private class AndroidPinnedTrustManager(
    private val expected: ByteArray,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("Server certificate is missing")
        certificate.checkValidity()
        if (!MessageDigest.isEqual(expected, certificate.spkiSha256())) {
            throw CertificateException("Pinned host identity mismatch")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted by this trust manager")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun SSLSession.matchesPin(expected: ByteArray): Boolean {
    val certificate = runCatching { peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return false
    return MessageDigest.isEqual(expected, certificate.spkiSha256())
}

private fun X509Certificate.spkiSha256(): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)

fun pinnedAndroidLanSyncClientFactory(): LanSyncClientFactory =
    LanSyncClientFactory { endpoint, fingerprint, token ->
        LanSyncHttpClient(pinnedAndroidHttpClient(fingerprint), endpoint, token)
    }
