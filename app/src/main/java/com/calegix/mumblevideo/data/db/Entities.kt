package com.calegix.mumblevideo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val host: String,
    val port: Int,
    /** Empty means "use the default username from settings". */
    val username: String,
    val password: String,
    val favourite: Boolean,
    /** Newline-separated access tokens. */
    @ColumnInfo(name = "access_tokens") val accessTokens: String = "",
    @ColumnInfo(name = "last_connected") val lastConnectedEpochMillis: Long? = null,
)

@Entity(tableName = "certificates")
data class CertificateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** File under filesDir/certificates holding the PKCS#12 bytes. */
    @ColumnInfo(name = "file_name") val fileName: String,
    val password: String,
    @ColumnInfo(name = "common_name") val commonName: String,
    val sha1: String,
    @ColumnInfo(name = "not_after") val notAfterEpochMillis: Long,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
)

/** A server certificate the user chose to trust despite it failing validation. */
@Entity(tableName = "trusted_server_certificates")
data class TrustedCertificateEntity(
    @PrimaryKey val sha256: String,
    val host: String,
    val port: Int,
    @ColumnInfo(name = "accepted_at") val acceptedAtEpochMillis: Long,
)
