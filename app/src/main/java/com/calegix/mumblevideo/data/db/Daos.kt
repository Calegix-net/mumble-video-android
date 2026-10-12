package com.calegix.mumblevideo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY favourite DESC, label COLLATE NOCASE")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun get(id: Long): ServerEntity?

    @Insert
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM servers WHERE label = :label AND host = :host AND port = 64738 AND username = ''")
    suspend fun deleteExample(label: String, host: String)

    @Query("UPDATE servers SET last_connected = :at WHERE id = :id")
    suspend fun markConnected(id: Long, at: Long)
}

@Dao
interface CertificateDao {
    @Query("SELECT * FROM certificates ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CertificateEntity>>

    @Query("SELECT * FROM certificates WHERE id = :id")
    suspend fun get(id: Long): CertificateEntity?

    @Insert
    suspend fun insert(certificate: CertificateEntity): Long

    @Delete
    suspend fun delete(certificate: CertificateEntity)
}

@Dao
interface TrustedCertificateDao {
    @Query("SELECT sha256 FROM trusted_server_certificates")
    suspend fun fingerprints(): List<String>

    @Query("SELECT COUNT(*) FROM trusted_server_certificates")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TrustedCertificateEntity)

    @Query("DELETE FROM trusted_server_certificates")
    suspend fun clear()
}
