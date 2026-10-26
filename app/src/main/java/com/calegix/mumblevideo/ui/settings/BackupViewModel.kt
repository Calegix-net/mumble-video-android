package com.calegix.mumblevideo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.data.backup.BackupException
import com.calegix.mumblevideo.data.backup.BackupManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The outcome of an export or import, shown in a dialog. */
data class BackupResult(val title: String, val message: String, val imported: Boolean = false)

data class BackupUiState(
    val busy: Boolean = false,
    val result: BackupResult? = null,
    /** A backup found in the drop-in folder, waiting for the user to import it or not. */
    val dropIn: File? = null,
)

class BackupViewModel(
    private val backup: BackupManager,
    /** The app's own external import folder; null when external storage is unavailable. */
    private val dropInDirectory: () -> File?,
) : ViewModel() {

    private val state = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = state.asStateFlow()

    private var checkedDropIn = false

    /** Writes a backup to the stream the user picked. */
    fun export(open: () -> OutputStream?) = work {
        val text = backup.export()
        val written = withContext(Dispatchers.IO) {
            try {
                open()?.use { it.write(text.encodeToByteArray()) } != null
            } catch (e: IOException) {
                false
            }
        }
        BackupResult(
            if (written) "Backup exported" else "Export failed",
            if (written) {
                "Keep the file somewhere private: it holds your certificates' private keys and server passwords."
            } else {
                "Could not write the file."
            },
        )
    }

    /** Merges the backup in the stream the user picked into this install. */
    fun import(open: () -> InputStream?) = work {
        val text = withContext(Dispatchers.IO) {
            try {
                open()?.use { readLimited(it) }
            } catch (e: IOException) {
                null
            }
        }
        if (text ==
            null
        ) {
            BackupResult("Import failed", "Could not read the file, or it is too big.")
        } else {
            importText(text)
        }
    }

    /** Looks once per launch for a backup left in the drop-in folder (see [AppContainer]). */
    fun checkDropIn() {
        if (checkedDropIn) return
        checkedDropIn = true
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                dropInDirectory()?.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
                    ?.maxByOrNull { it.lastModified() }
            }
            if (found != null) state.update { it.copy(dropIn = found) }
        }
    }

    fun importDropIn() {
        val file = state.value.dropIn ?: return
        state.update { it.copy(dropIn = null) }
        work {
            val text = withContext(Dispatchers.IO) {
                try {
                    file.inputStream().use { readLimited(it) }
                } catch (e: IOException) {
                    null
                }
            }
            val result = if (text ==
                null
            ) {
                BackupResult("Import failed", "Could not read ${file.name}.")
            } else {
                importText(text)
            }
            if (result.imported) withContext(Dispatchers.IO) { file.delete() }
            result
        }
    }

    fun dismissDropIn() = state.update { it.copy(dropIn = null) }

    fun consumeResult() = state.update { it.copy(result = null) }

    private suspend fun importText(text: String): BackupResult = try {
        BackupResult("Backup imported", backup.import(text).describe(), imported = true)
    } catch (e: BackupException) {
        BackupResult("Import failed", e.message.orEmpty())
    }

    /** Null when the stream is bigger than any real backup. */
    private fun readLimited(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray().decodeToString()
    }

    private fun work(block: suspend () -> BackupResult) {
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage errors and the like. The message is left out: it could quote file content.
                BackupResult("Something went wrong", "The operation failed (${e.javaClass.simpleName}).")
            } finally {
                state.update { it.copy(busy = false) }
            }
            state.update { it.copy(result = result) }
        }
    }

    private companion object {
        /** Far above any real backup (a certificate is a few kB); stops a wrong pick eating memory. */
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
