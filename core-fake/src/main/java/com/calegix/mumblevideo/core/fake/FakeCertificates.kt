package com.calegix.mumblevideo.core.fake

import com.calegix.mumblevideo.core.CertificateInfo
import com.calegix.mumblevideo.core.ClientCertificate
import com.calegix.mumblevideo.core.MumbleCoreException
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Certificate handling for the fake core.
 *
 * Generating a real X.509 certificate needs an ASN.1 encoder the platform does not expose, and
 * the real core will do it with OpenSSL anyway, so "generated" certificates here are a marked
 * placeholder blob. Imported files are genuine PKCS#12 and are parsed for real.
 */
internal object FakeCertificates {
    private const val MAGIC = "MUMBLE-VIDEO-FAKE-P12\n"
    private const val YEAR_MS = 365L * 86_400_000L

    fun generate(commonName: String, password: String, now: Long): ClientCertificate {
        val body = buildString {
            append(MAGIC)
            append("cn=").append(commonName.replace('\n', ' ')).append('\n')
            append("created=").append(now).append('\n')
        }
        return ClientCertificate(body.encodeToByteArray(), password)
    }

    fun inspect(pkcs12: ByteArray, password: String): CertificateInfo {
        val asText = pkcs12.decodeToString()
        if (asText.startsWith(MAGIC)) {
            val fields = asText.removePrefix(MAGIC).lines().filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val created = fields["created"]?.toLongOrNull() ?: 0L
            return CertificateInfo(
                commonName = fields["cn"].orEmpty(),
                email = null,
                issuer = "Self-signed (simulated)",
                notBeforeEpochMillis = created,
                notAfterEpochMillis = created + 20 * YEAR_MS,
                sha1 = sha1Hex(pkcs12),
            )
        }
        return try {
            val store = KeyStore.getInstance("PKCS12")
            store.load(ByteArrayInputStream(pkcs12), password.toCharArray())
            val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
                ?: throw MumbleCoreException(MumbleCoreException.Kind.InvalidCertificate, "No private key in file")
            val cert = store.getCertificate(alias) as X509Certificate
            CertificateInfo(
                commonName = rdn(cert.subjectX500Principal, "CN") ?: cert.subjectX500Principal.name,
                email =
                    rdn(cert.subjectX500Principal, "EMAILADDRESS")
                        ?: rdn(cert.subjectX500Principal, "1.2.840.113549.1.9.1"),
                issuer = cert.issuerX500Principal.name,
                notBeforeEpochMillis = cert.notBefore.time,
                notAfterEpochMillis = cert.notAfter.time,
                sha1 = sha1Hex(cert.encoded),
            )
        } catch (e: MumbleCoreException) {
            throw e
        } catch (e: Exception) {
            throw MumbleCoreException(
                MumbleCoreException.Kind.InvalidCertificate,
                "Could not open the certificate. Is the password right?",
                e,
            )
        }
    }

    private fun rdn(principal: X500Principal, key: String): String? =
        principal.getName(X500Principal.RFC2253).split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=", ignoreCase = true) }
            ?.substringAfter('=')

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
