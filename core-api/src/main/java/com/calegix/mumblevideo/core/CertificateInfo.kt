package com.calegix.mumblevideo.core

/** Description of a client certificate, for the certificate management screen. */
data class CertificateInfo(
    val commonName: String,
    val email: String?,
    val issuer: String,
    val notBeforeEpochMillis: Long,
    val notAfterEpochMillis: Long,
    /** Lower-case hex SHA-1, which is what Mumble servers show as the user's certificate hash. */
    val sha1: String,
)
