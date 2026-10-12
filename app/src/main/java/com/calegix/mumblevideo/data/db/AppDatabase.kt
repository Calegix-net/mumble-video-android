package com.calegix.mumblevideo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ServerEntity::class, CertificateEntity::class, TrustedCertificateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun servers(): ServerDao

    abstract fun certificates(): CertificateDao

    abstract fun trustedCertificates(): TrustedCertificateDao

    companion object {
        /** [seedExamples] fills a fresh list with the fake core's scripted servers. */
        fun create(context: Context, seedExamples: Boolean): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "mumble-video.db")
                .apply { if (seedExamples) addCallback(SeedServers) }
                .build()
    }
}

/** Servers that exercise the fake core's scripted behaviours, for a first run on the simulated core. */
private object SeedServers : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        EXAMPLE_SERVERS.forEach { (label, host, favourite) ->
            db.execSQL(
                "INSERT INTO servers (label, host, port, username, password, favourite, access_tokens) " +
                    "VALUES (?, ?, 64738, '', '', ?, '')",
                arrayOf<Any>(label, host, favourite),
            )
        }
    }
}

/** The fake core's scripted servers: label, host, favourite. */
internal val EXAMPLE_SERVERS = listOf(
    Triple("Calegix test server", "mumble.calegix.test", 1),
    Triple("Flaky Wi-Fi (drops every 20 s)", "flaky.example.org", 0),
    Triple("Wrong password", "reject.example.org", 0),
    Triple("Legacy server (no video)", "legacy.example.org", 0),
    Triple("Unreachable", "unreachable.example.org", 0),
)
