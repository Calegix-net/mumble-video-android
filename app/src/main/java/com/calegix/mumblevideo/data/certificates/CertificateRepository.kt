package com.calegix.mumblevideo.data.certificates

import com.calegix.mumblevideo.core.CertificateInfo
import com.calegix.mumblevideo.core.ClientCertificate
import com.calegix.mumblevideo.data.db.CertificateDao
import com.calegix.mumblevideo.data.db.CertificateEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A stored client certificate, without its key material. */
data class StoredCertificate(
    val id: Long,
    val name: String,
    val commonName: String,
    val sha1: String,
    val notAfterEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

interface CertificateRepository {
    val certificates: Flow<List<StoredCertificate>>

    suspend fun add(name: String, certificate: ClientCertificate, info: CertificateInfo, at: Long): Long

    /** The PKCS#12 bytes and password, for connecting or exporting. */
    suspend fun load(id: Long): ClientCertificate?

    suspend fun delete(id: Long)
}

/** Key material lives in app-private files (excluded from backup); metadata in Room. */
class FileCertificateRepository(
    private val dao: CertificateDao,
    private val directory: File,
) : CertificateRepository {

    override val certificates: Flow<List<StoredCertificate>> = dao.observeAll().map { list ->
        list.map {
            StoredCertificate(it.id, it.name, it.commonName, it.sha1, it.notAfterEpochMillis, it.createdAtEpochMillis)
        }
    }

    override suspend fun add(name: String, certificate: ClientCertificate, info: CertificateInfo, at: Long): Long =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val fileName = "${UUID.randomUUID()}.p12"
            File(directory, fileName).writeBytes(certificate.pkcs12)
            dao.insert(
                CertificateEntity(
                    name = name,
                    fileName = fileName,
                    password = certificate.password,
                    commonName = info.commonName,
                    sha1 = info.sha1,
                    notAfterEpochMillis = info.notAfterEpochMillis,
                    createdAtEpochMillis = at,
                ),
            )
        }

    override suspend fun load(id: Long): ClientCertificate? = withContext(Dispatchers.IO) {
        val entity = dao.get(id) ?: return@withContext null
        val file = File(directory, entity.fileName)
        if (!file.exists()) return@withContext null
        ClientCertificate(file.readBytes(), entity.password)
    }

    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val entity = dao.get(id) ?: return@withContext
        File(directory, entity.fileName).delete()
        dao.delete(entity)
    }
}

class InMemoryCertificateRepository : CertificateRepository {
    private val state = MutableStateFlow(emptyList<Pair<StoredCertificate, ClientCertificate>>())
    private var nextId = 1L

    override val certificates: Flow<List<StoredCertificate>> = state.map { list -> list.map { it.first } }

    override suspend fun add(name: String, certificate: ClientCertificate, info: CertificateInfo, at: Long): Long {
        val id = nextId++
        val stored = StoredCertificate(id, name, info.commonName, info.sha1, info.notAfterEpochMillis, at)
        state.update { it + (stored to certificate) }
        return id
    }

    override suspend fun load(id: Long): ClientCertificate? = state.value.firstOrNull { it.first.id == id }?.second

    override suspend fun delete(id: Long) = state.update { list -> list.filterNot { it.first.id == id } }
}
