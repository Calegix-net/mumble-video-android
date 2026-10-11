package com.calegix.mumblevideo.core

/** A Mumble server endpoint. */
data class ServerAddress(val host: String, val port: Int = DEFAULT_PORT) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }

    override fun toString(): String = if (port == DEFAULT_PORT) host else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 64738
    }
}

/**
 * A client certificate as a PKCS#12 blob. Mumble identifies registered users by certificate, so
 * the same certificate must be presented every time.
 */
class ClientCertificate(val pkcs12: ByteArray, val password: String = "") {
    // Not a data class: the password and key material should never end up in logs via toString.
    override fun toString(): String = "ClientCertificate(${pkcs12.size} bytes)"
}

/** Everything needed to open one connection. */
data class ConnectRequest(
    val server: ServerAddress,
    val username: String,
    /** Server password, or empty. */
    val password: String = "",
    val certificate: ClientCertificate? = null,
    val accessTokens: List<String> = emptyList(),
    /**
     * Lower-case hex SHA-256 fingerprints of server certificates the user has already accepted.
     * A server presenting one of these is trusted even if the chain does not validate.
     */
    val trustedCertificateSha256: Set<String> = emptySet(),
    /** Tunnel voice over the TLS control channel instead of UDP. */
    val forceTcp: Boolean = false,
    /** Retry automatically when an established connection drops. */
    val autoReconnect: Boolean = true,
)

/** Where the client is in the connection lifecycle. */
sealed interface ConnectionState {

    /** Not connected. [reason] is null only before the first connection attempt. */
    data class Disconnected(val reason: DisconnectReason? = null) : ConnectionState

    data class Connecting(val server: ServerAddress, val phase: ConnectPhase) : ConnectionState

    /**
     * The server's certificate could not be verified and is not in
     * [ConnectRequest.trustedCertificateSha256]. The handshake is paused until
     * [MumbleCore.respondToCertificate] is called.
     */
    data class CertificateTrustRequired(val server: ServerAddress, val certificate: ServerCertificate) :
        ConnectionState

    data class Connected(val server: ServerAddress, val info: ServerInfo) : ConnectionState

    /**
     * An established connection dropped and the core is retrying. [attempt] starts at 1;
     * [retryInMillis] is the delay before the next try, for a countdown in the UI.
     */
    data class Reconnecting(
        val server: ServerAddress,
        val attempt: Int,
        val retryInMillis: Long,
        val lastError: String?,
    ) : ConnectionState

    /** The server refused the login. Terminal: the core does not retry. */
    data class Rejected(val server: ServerAddress, val reason: RejectReason, val message: String) :
        ConnectionState
}

enum class ConnectPhase { Resolving, Handshaking, Authenticating, Synchronising }

/** Mirrors `Reject.RejectType` from Mumble.proto. */
enum class RejectReason {
    None,
    WrongVersion,
    InvalidUsername,
    WrongUserPassword,
    WrongServerPassword,
    UsernameInUse,
    ServerFull,
    NoCertificate,
    AuthenticatorFail,
}

sealed interface DisconnectReason {
    data object UserRequested : DisconnectReason

    data object CertificateRejected : DisconnectReason

    /** The server closed the connection or it timed out, and reconnecting is off or gave up. */
    data class ConnectionLost(val message: String) : DisconnectReason

    /** The connection could not be established at all (DNS, refused, TLS failure). */
    data class Unreachable(val message: String) : DisconnectReason

    data class Kicked(val by: String?, val reason: String, val banned: Boolean) : DisconnectReason
}

/** The server's TLS certificate as shown in a trust prompt. */
data class ServerCertificate(
    val subject: String,
    val issuer: String,
    val notBeforeEpochMillis: Long,
    val notAfterEpochMillis: Long,
    /** Lower-case hex SHA-256 of the DER encoding; this is what gets pinned. */
    val sha256: String,
    /** Why validation failed, e.g. "self-signed" or "hostname mismatch". */
    val problem: String,
    /** The full chain, leaf first, DER encoded, for "view certificate" screens. */
    val chainDer: List<ByteArray> = emptyList(),
)

/** What the server said about itself once connected. */
data class ServerInfo(
    val welcomeText: String,
    val release: String,
    val os: String,
    val osVersion: String,
    val protocolVersion: String,
    val maxUsers: Int,
    /** Whether voice is going over UDP or is tunnelled through TCP. */
    val udpActive: Boolean,
    val latencyMillis: Int,
)
