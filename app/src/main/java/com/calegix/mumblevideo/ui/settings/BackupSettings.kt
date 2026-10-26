package com.calegix.mumblevideo.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calegix.mumblevideo.ui.AppViewModelProvider
import com.calegix.mumblevideo.ui.components.ClickPreference
import com.calegix.mumblevideo.ui.components.PreferenceCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val KEY_WARNING =
    "The backup file holds your certificates' private keys and your server passwords in plain text. " +
        "Anyone with the file can use your identity: keep it private and delete it once restored."

/** Export everything to one JSON file, or merge such a file back in. */
@Composable
fun BackupSettings(
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver

    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.export { resolver.openOutputStream(uri) }
        }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import { resolver.openInputStream(uri) }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        PreferenceCategory("Backup")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Text(
            KEY_WARNING,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ClickPreference(
            "Export backup",
            onClick = {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                exporter.launch("mumble-video-backup-$date.json")
            },
            summary = "Servers, certificates, trusted servers and settings",
            icon = Icons.Outlined.FileDownload,
            enabled = !state.busy,
        )
        ClickPreference(
            "Import backup",
            onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
            summary = "Merges with what is already here; nothing is deleted",
            icon = Icons.Outlined.FileUpload,
            enabled = !state.busy,
        )
    }

    state.result?.let { BackupResultDialog(it, onDismiss = viewModel::consumeResult) }
}

@Composable
fun BackupResultDialog(result: BackupResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.title) },
        text = { Text(result.message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Offers a backup left in the app's import folder; nothing is imported without a tap on Import. */
@Composable
fun BackupDropInDialog(fileName: String, onImport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import backup found?") },
        text = {
            Text(
                "$fileName is waiting in the app's import folder. Importing merges its servers, certificates, " +
                    "trusted servers and settings into this app, then deletes the file.\n\n$KEY_WARNING",
            )
        },
        confirmButton = { TextButton(onClick = onImport) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
