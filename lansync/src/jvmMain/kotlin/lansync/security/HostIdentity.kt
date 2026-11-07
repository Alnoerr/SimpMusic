package lansync.security

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

data class HostIdentity(
    val keyStore: KeyStore,
    val keyAlias: String,
    val password: CharArray,
    val certificate: X509Certificate,
    val publicKeyFingerprint: String,
)

class HostIdentityStore(
    private val path: Path,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun loadOrCreate(password: CharArray): HostIdentity {
        require(password.isNotEmpty()) { "A protected keystore password is required" }
        val keyStore = KeyStore.getInstance("PKCS12")
        if (Files.exists(path)) {
            Files.newInputStream(path).use { keyStore.load(it, password) }
        } else {
            keyStore.load(null, password)
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256, secureRandom) }.generateKeyPair()
            val now = Instant.now()
            val subject = X500Name("CN=SimpMusic LAN Sync")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(160, secureRandom).abs(),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(3_650, ChronoUnit.DAYS)),
                subject,
                keyPair.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(
                    arrayOf(
                        GeneralName(GeneralName.dNSName, "simpmusic-lan"),
                        GeneralName(GeneralName.dNSName, "localhost"),
                        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                        GeneralName(GeneralName.iPAddress, "::1"),
                    ),
                ),
            )
            val certificate = JcaX509CertificateConverter().getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)),
            )
            certificate.verify(keyPair.public)
            keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, password, arrayOf(certificate))
            save(keyStore, password)
        }
        val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: error("Host identity does not contain the expected certificate")
        return HostIdentity(
            keyStore = keyStore,
            keyAlias = KEY_ALIAS,
            password = password.copyOf(),
            certificate = certificate,
            publicKeyFingerprint = spkiFingerprint(certificate),
        )
    }

    private fun save(keyStore: KeyStore, password: CharArray) {
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp")
        java.io.FileOutputStream(temp.toFile()).use { output ->
            keyStore.store(output, password)
            output.fd.sync()
        }
        runCatching {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val KEY_ALIAS = "simpmusic-lan-host"

        fun spkiFingerprint(certificate: X509Certificate): String =
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
            )
    }
}
