package com.calegix.mumblevideo.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface SettingsRepository {
    /** Current settings; starts at defaults until the first read from disk completes. */
    val settings: StateFlow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class DataStoreSettingsRepository(
    private val store: DataStore<AppSettings>,
    scope: CoroutineScope,
) : SettingsRepository {

    override val settings: StateFlow<AppSettings> =
        store.data.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }

    companion object {
        fun create(context: Context, scope: CoroutineScope): DataStoreSettingsRepository {
            val store = DataStoreFactory.create(
                serializer = AppSettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
                scope = scope,
                produceFile = { File(context.filesDir, "datastore/settings.json") },
            )
            return DataStoreSettingsRepository(store, scope)
        }
    }
}

internal object AppSettingsSerializer : Serializer<AppSettings> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        coerceInputValues = true
    }

    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        json.decodeFromString(AppSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable settings", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Unreadable settings", e)
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(json.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}

/** For tests and previews. */
class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.update(transform)
    }
}
