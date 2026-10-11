package com.calegix.mumblevideo.data.servers

import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.data.db.ServerDao
import com.calegix.mumblevideo.data.db.ServerEntity
import com.calegix.mumblevideo.data.db.TrustedCertificateDao
import com.calegix.mumblevideo.data.db.TrustedCertificateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A server in the user's list. */
data class SavedServer(
    val id: Long = 0,
    val label: String,
    val host: String,
    val port: Int = ServerAddress.DEFAULT_PORT,
    /** Empty means "use the default username". */
    val username: String = "",
    val password: String = "",
    val favourite: Boolean = false,
    val accessTokens: List<String> = emptyList(),
    val lastConnectedEpochMillis: Long? = null,
) {
    val address: ServerAddress get() = ServerAddress(host, port)

    val displayName: String get() = label.ifBlank { host }

    /** `mumble://` link for sharing, without the password. */
    fun toMumbleUrl(): String = buildString {
        append("mumble://")
        if (username.isNotBlank()) append(java.net.URLEncoder.encode(username, "UTF-8")).append('@')
        append(host)
        if (port != ServerAddress.DEFAULT_PORT) append(':').append(port)
        append('/')
    }
}

interface ServerRepository {
    val servers: Flow<List<SavedServer>>

    suspend fun get(id: Long): SavedServer?

    /** Inserts when [SavedServer.id] is 0, otherwise updates. Returns the id. */
    suspend fun save(server: SavedServer): Long

    suspend fun delete(id: Long)

    suspend fun markConnected(id: Long, at: Long)
}

class RoomServerRepository(private val dao: ServerDao) : ServerRepository {
    override val servers: Flow<List<SavedServer>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun get(id: Long): SavedServer? = dao.get(id)?.toModel()

    override suspend fun save(server: SavedServer): Long =
        if (server.id == 0L) {
            dao.insert(server.toEntity())
        } else {
            dao.update(server.toEntity())
            server.id
        }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun markConnected(id: Long, at: Long) = dao.markConnected(id, at)

    private fun ServerEntity.toModel() = SavedServer(
        id = id,
        label = label,
        host = host,
        port = port,
        username = username,
        password = password,
        favourite = favourite,
        accessTokens = accessTokens.lines().filter { it.isNotBlank() },
        lastConnectedEpochMillis = lastConnectedEpochMillis,
    )

    private fun SavedServer.toEntity() = ServerEntity(
        id = id,
        label = label,
        host = host,
        port = port,
        username = username,
        password = password,
        favourite = favourite,
        accessTokens = accessTokens.joinToString("\n"),
        lastConnectedEpochMillis = lastConnectedEpochMillis,
    )
}

/** Server certificates the user accepted from a trust prompt. */
interface TrustStore {
    val count: Flow<Int>

    suspend fun fingerprints(): Set<String>

    suspend fun trust(sha256: String, address: ServerAddress, at: Long)

    suspend fun clear()
}

class RoomTrustStore(private val dao: TrustedCertificateDao) : TrustStore {
    override val count: Flow<Int> = dao.observeCount()

    override suspend fun fingerprints(): Set<String> = dao.fingerprints().toSet()

    override suspend fun trust(sha256: String, address: ServerAddress, at: Long) =
        dao.insert(TrustedCertificateEntity(sha256, address.host, address.port, at))

    override suspend fun clear() = dao.clear()
}

class InMemoryServerRepository(initial: List<SavedServer> = emptyList()) : ServerRepository {
    private val state = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override val servers: Flow<List<SavedServer>> =
        state.map { list ->
            list.sortedWith(compareByDescending<SavedServer> { it.favourite }.thenBy { it.label.lowercase() })
        }

    override suspend fun get(id: Long): SavedServer? = state.value.firstOrNull { it.id == id }

    override suspend fun save(server: SavedServer): Long {
        val id = if (server.id == 0L) nextId++ else server.id
        state.update { list -> list.filterNot { it.id == id } + server.copy(id = id) }
        return id
    }

    override suspend fun delete(id: Long) = state.update { list -> list.filterNot { it.id == id } }

    override suspend fun markConnected(id: Long, at: Long) =
        state.update { list -> list.map { if (it.id == id) it.copy(lastConnectedEpochMillis = at) else it } }
}

class InMemoryTrustStore : TrustStore {
    private val state = MutableStateFlow(emptySet<String>())
    override val count: Flow<Int> = state.map { it.size }

    override suspend fun fingerprints(): Set<String> = state.value

    override suspend fun trust(sha256: String, address: ServerAddress, at: Long) = state.update { it + sha256 }

    override suspend fun clear() {
        state.value = emptySet()
    }
}
