package com.calegix.mumblevideo.client

import com.calegix.mumblevideo.core.CertificateInfo
import com.calegix.mumblevideo.core.ClientCertificate
import com.calegix.mumblevideo.core.MumbleCoreException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Client certificates: self-signed RSA-2048, valid for 20 years, the same shape the desktop client
 * generates, packed as PKCS#12. BouncyCastle only builds the certificate; keys, signing and the
 * PKCS#12 container use the platform providers.
 */
internal object Certificates {
    private const val ALIAS = "mumble"
    private const val YEAR_MS = 365L * 86_400_000L

    fun generate(commonName: String, password: String, now: Long = System.currentTimeMillis()): ClientCertificate {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName).build()
        val serial = BigInteger(64, SecureRandom()).abs()
        val holder = JcaX509v3CertificateBuilder(
            name,
            serial,
            Date(now - 86_400_000L),
            Date(now + 20 * YEAR_MS),
            name,
            keys.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
            .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)

        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry(ALIAS, keys.private, password.toCharArray(), arrayOf(certificate))
        val out = ByteArrayOutputStream()
        store.store(out, password.toCharArray())
        return ClientCertificate(out.toByteArray(), password)
    }

    fun inspect(pkcs12: ByteArray, password: String): CertificateInfo = try {
        val store = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(pkcs12), password.toCharArray()) }
        val alias = store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
            ?: throw MumbleCoreException(MumbleCoreException.Kind.InvalidCertificate, "No private key in the file")
        val cert = store.getCertificate(alias) as X509Certificate
        CertificateInfo(
            commonName = rdn(cert.subjectX500Principal, "CN") ?: cert.subjectX500Principal.name,
            email = rdn(cert.subjectX500Principal, "EMAILADDRESS"),
            issuer = cert.issuerX500Principal.name,
            notBeforeEpochMillis = cert.notBefore.time,
            notAfterEpochMillis = cert.notAfter.time,
            sha1 = MessageDigest.getInstance("SHA-1").digest(cert.encoded).joinToString("") { "%02x".format(it) },
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

    private fun rdn(principal: X500Principal, key: String): String? =
        principal.getName(X500Principal.RFC2253).split(',').map { it.trim() }
            .firstOrNull { it.startsWith("$key=", ignoreCase = true) }?.substringAfter('=')
}
