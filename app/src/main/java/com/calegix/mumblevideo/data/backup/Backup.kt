package com.calegix.mumblevideo.data.backup

import com.calegix.mumblevideo.core.CertificateInfo
import com.calegix.mumblevideo.core.ClientCertificate
import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.data.certificates.CertificateRepository
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.servers.ServerRepository
import com.calegix.mumblevideo.data.servers.TrustStore
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.data.settings.SettingsRepository
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The backup file: servers, client certificates with their keys, trusted server certificates and
 * settings, as one JSON document. Field names are a contract with tools that write these files by
 * hand (moving a debug build's data into the release app), so they don't follow the Kotlin names.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    @SerialName("exported_at") val exportedAt: Long = 0,
    val servers: List<BackupServer> = emptyList(),
    val certificates: List<BackupCertificate> = emptyList(),
    @SerialName("trusted_server_certificates") val trustedServerCertificates: List<BackupTrustedServer> = emptyList(),
    val settings: JsonObject? = null,
    @SerialName("selected_certificate_sha1") val selectedCertificateSha1: String? = null,
) {
    companion object {
        const val FORMAT = "mumble-video-backup"
        const val VERSION = 1
    }
}

@Serializable
data class BackupServer(
    val label: String = "",
    val host: String,
    val port: Int = ServerAddress.DEFAULT_PORT,
    val username: String = "",
    val password: String = "",
    val favourite: Boolean = false,
    /** Newline-separated, exactly as the servers table stores them. */
    @SerialName("access_tokens") val accessTokens: String = "",
    @SerialName("last_connected") val lastConnected: Long? = null,
)

@Serializable
data class BackupCertificate(
    val name: String = "",
    val password: String = "",
    @SerialName("common_name") val commonName: String = "",
    val sha1: String = "",
    @SerialName("not_after") val notAfter: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("pkcs12_base64") val pkcs12Base64: String,
)

@Serializable
data class BackupTrustedServer(
    val sha256: String,
    val host: String = "",
    val port: Int = ServerAddress.DEFAULT_PORT,
    @SerialName("accepted_at") val acceptedAt: Long = 0,
)

/** A file that is not a backup this version can read. The message is for the user. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What an import changed, for the summary shown afterwards. */
data class ImportSummary(
    val servers: Int,
    val certificates: Int,
    val trustedServers: Int,
    val settings: Boolean,
    /** Certificates skipped because their PKCS#12 would not open. */
    val unreadableCertificates: Int = 0,
) {
    fun describe(): String {
        val parts = buildList {
            add(count(servers, "server"))
            add(count(certificates, "certificate"))
            add(count(trustedServers, "trusted server"))
            if (settings) add("settings")
        }
        val skipped = if (unreadableCertificates > 0) {
            ". ${count(unreadableCertificates, "certificate")} could not be read and ${
                if (unreadableCertificates == 1) "was" else "were"
            } skipped"
        } else {
            ""
        }
        return "Imported " + parts.joinToString(", ") + skipped
    }

    private fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"
}

/**
 * Exports everything worth moving to another install, and merges a backup back in. Importing is
 * idempotent: servers match on host, port and username, certificates on SHA-1, trusted server
 * certificates on SHA-256, so importing the same file twice changes nothing the second time.
 */
class BackupManager(
    private val servers: ServerRepository,
    private val certificates: CertificateRepository,
    private val trustStore: TrustStore,
    private val settings: SettingsRepository,
    /** Opens a PKCS#12 to check it before storing it: the core's inspectCertificate. */
    private val inspect: suspend (pkcs12: ByteArray, password: String) -> CertificateInfo,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun export(): String {
        val stored = certificates.certificates.first()
        val current = settings.load()
        val file = BackupFile(
            exportedAt = clock(),
            servers = servers.servers.first().map {
                BackupServer(
                    label = it.label,
                    host = it.host,
                    port = it.port,
                    username = it.username,
                    password = it.password,
                    favourite = it.favourite,
                    accessTokens = it.accessTokens.joinToString("\n"),
                    lastConnected = it.lastConnectedEpochMillis,
                )
            },
            certificates = stored.mapNotNull { cert ->
                val material = certificates.load(cert.id) ?: return@mapNotNull null
                BackupCertificate(
                    name = cert.name,
                    password = material.password,
                    commonName = cert.commonName,
                    sha1 = cert.sha1,
                    notAfter = cert.notAfterEpochMillis,
                    createdAt = cert.createdAtEpochMillis,
                    pkcs12Base64 = Base64.getEncoder().encodeToString(material.pkcs12),
                )
            },
            trustedServerCertificates = trustStore.all().map {
                BackupTrustedServer(it.sha256, it.address.host, it.address.port, it.acceptedAtEpochMillis)
            },
            settings = storedSettingsJson.encodeToJsonElement(AppSettings.serializer(), current).jsonObject,
            selectedCertificateSha1 = stored.firstOrNull { it.id == current.selectedCertificateId }?.sha1,
        )
        return fileJson.encodeToString(BackupFile.serializer(), file)
    }

    suspend fun import(text: String): ImportSummary {
        val backup = parse(text)

        val existing = servers.servers.first().toMutableList()
        backup.servers.forEach { entry ->
            val tokens = entry.accessTokens.lines().filter { it.isNotBlank() }
            val match = existing.firstOrNull {
                it.host.equals(entry.host, ignoreCase = true) && it.port == entry.port && it.username == entry.username
            }
            val merged = match?.copy(
                label = entry.label,
                password = entry.password,
                accessTokens = tokens,
                favourite = match.favourite || entry.favourite,
                lastConnectedEpochMillis = listOfNotNull(match.lastConnectedEpochMillis, entry.lastConnected)
                    .maxOrNull(),
            ) ?: SavedServer(
                label = entry.label,
                host = entry.host,
                port = entry.port,
                username = entry.username,
                password = entry.password,
                favourite = entry.favourite,
                accessTokens = tokens,
                lastConnectedEpochMillis = entry.lastConnected,
            )
            val id = servers.save(merged)
            existing.removeAll { it.id == id }
            existing += merged.copy(id = id)
        }

        // SHA-1 to local id: the backup's own values, and whatever the certificate really hashes to.
        val bySha1 = certificates.certificates.first().associate { it.sha1.lowercase() to it.id }.toMutableMap()
        var unreadable = 0
        backup.certificates.forEach { entry ->
            val known = bySha1[entry.sha1.lowercase()]
            if (known != null) return@forEach
            val bytes = try {
                Base64.getMimeDecoder().decode(entry.pkcs12Base64)
            } catch (e: IllegalArgumentException) {
                unreadable++
                return@forEach
            }
            val info = try {
                inspect(bytes, entry.password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unreadable++
                return@forEach
            }
            val id = bySha1[info.sha1.lowercase()] ?: certificates.add(
                entry.name.ifBlank { info.commonName },
                ClientCertificate(bytes, entry.password),
                info,
                if (entry.createdAt > 0) entry.createdAt else clock(),
            )
            bySha1[info.sha1.lowercase()] = id
            if (entry.sha1.isNotBlank()) bySha1[entry.sha1.lowercase()] = id
        }

        backup.trustedServerCertificates.forEach {
            trustStore.trust(it.sha256, ServerAddress(it.host, it.port), it.acceptedAt)
        }

        val selected = backup.selectedCertificateSha1?.let { bySha1[it.lowercase()] }
        var settingsApplied = false
        settings.update { current ->
            val (merged, applied) = backup.settings?.let { mergeSettings(current, it) } ?: (current to false)
            settingsApplied = applied || selected != null
            merged.copy(selectedCertificateId = selected ?: current.selectedCertificateId)
        }

        return ImportSummary(
            servers = backup.servers.size,
            certificates = backup.certificates.size - unreadable,
            trustedServers = backup.trustedServerCertificates.size,
            settings = settingsApplied,
            unreadableCertificates = unreadable,
        )
    }

    private fun parse(text: String): BackupFile {
        val root = try {
            fileJson.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw BackupException("This is not a mumble-video backup file")
        val format = (root["format"] as? JsonPrimitive)?.contentOrNull
        if (format != BackupFile.FORMAT) throw BackupException("This is not a mumble-video backup file")
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: throw BackupException("The backup file has no version")
        if (version > BackupFile.VERSION) {
            throw BackupException(
                "This backup was made by a newer version of the app (format $version). Update the app to import it.",
            )
        }
        return try {
            fileJson.decodeFromJsonElement(BackupFile.serializer(), root)
        } catch (e: SerializationException) {
            throw BackupException("The backup file is damaged or incomplete", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException("The backup file is damaged or incomplete", e)
        }
    }

    /**
     * Takes each setting the backup has and this version knows, keeping the current value for the
     * rest. A value of the wrong type, or an enum this version lacks, is skipped rather than failing
     * the import. The certificate selection is mapped separately (ids differ between installs).
     */
    private fun mergeSettings(current: AppSettings, backup: JsonObject): Pair<AppSettings, Boolean> {
        val known = AppSettings.serializer().descriptor.elementNames.toSet() - "selectedCertificateId"
        var merged = strictSettingsJson.encodeToJsonElement(AppSettings.serializer(), current).jsonObject
        var applied = false
        backup.forEach { (key, value) ->
            if (key !in known) return@forEach
            val candidate = JsonObject(merged + (key to value))
            val readable = try {
                strictSettingsJson.decodeFromJsonElement(AppSettings.serializer(), candidate)
                true
            } catch (e: SerializationException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
            if (readable) {
                merged = candidate
                applied = true
            }
        }
        return strictSettingsJson.decodeFromJsonElement(AppSettings.serializer(), merged) to applied
    }

    private companion object {
        val fileJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** The same shape the settings DataStore writes: only values that differ from the defaults. */
        val storedSettingsJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** Every field written, and nothing coerced, so a bad value fails and can be skipped. */
        val strictSettingsJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
