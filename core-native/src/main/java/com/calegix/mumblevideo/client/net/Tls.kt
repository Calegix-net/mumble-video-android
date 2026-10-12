package com.calegix.mumblevideo.client.net

import com.calegix.mumblevideo.core.ClientCertificate
import com.calegix.mumblevideo.core.ServerCertificate
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Accepts any server chain during the handshake but remembers whether the platform would have
 * trusted it, so the decision can be made afterwards with the user, without a second handshake.
 */
internal class RecordingTrustManager : X509TrustManager {
    private val platform: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

    @Volatile
    var chain: List<X509Certificate> = emptyList()
        private set

    @Volatile
    var problem: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        this.chain = chain.toList()
        problem = try {
            platform.checkServerTrusted(chain, authType)
            null
        } catch (e: CertificateException) {
            describe(chain, e)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    private fun describe(chain: Array<out X509Certificate>, e: CertificateException): String {
        val leaf = chain.firstOrNull() ?: return "The server sent no certificate."
        return when {
            chain.size == 1 && leaf.subjectX500Principal == leaf.issuerX500Principal ->
                "The certificate is self-signed."

            leaf.notAfter.time < System.currentTimeMillis() -> "The certificate has expired."

            else -> e.message ?: "The certificate could not be verified."
        }
    }
}

internal object Tls {
    fun context(certificate: ClientCertificate?, trust: RecordingTrustManager): SSLContext {
        val keyManagers = certificate?.let {
            val store = KeyStore.getInstance("PKCS12").apply {
                load(ByteArrayInputStream(it.pkcs12), it.password.toCharArray())
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(store, it.password.toCharArray()) }
                .keyManagers
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trust), null) }
    }

    /** Describes the server's certificate for a trust prompt, or null if it is fully trusted. */
    fun untrusted(socket: SSLSocket, host: String, trust: RecordingTrustManager): ServerCertificate? {
        val leaf = trust.chain.firstOrNull() ?: return null
        val hostnameOk = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)
        val problem = trust.problem ?: if (!hostnameOk) "The certificate is for a different host name." else return null
        return ServerCertificate(
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notBeforeEpochMillis = leaf.notBefore.time,
            notAfterEpochMillis = leaf.notAfter.time,
            sha256 = sha256(leaf.encoded),
            problem = problem,
            chainDer = trust.chain.map { it.encoded },
        )
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }
}
