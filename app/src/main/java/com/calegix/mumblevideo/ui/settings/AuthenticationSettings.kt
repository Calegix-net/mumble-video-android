package com.calegix.mumblevideo.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calegix.mumblevideo.data.certificates.StoredCertificate
import com.calegix.mumblevideo.ui.AppViewModelProvider
import com.calegix.mumblevideo.ui.components.ClickPreference
import com.calegix.mumblevideo.ui.components.PreferenceCategory
import com.calegix.mumblevideo.ui.components.TextPreference
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mumla's Authentication screen: certificates (generate, import, export, select), username, trust. */
@Composable
fun AuthenticationSettings(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
    viewModel: CertificatesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val trusted by settingsViewModel.trustedCertificateCount.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingExport by viewModel.pendingExport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var generating by rememberSaveable { mutableStateOf(false) }
    var importUri by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClearTrust by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri.toString()
    }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri ->
            val cert = pendingExport
            if (uri == null || cert == null) {
                viewModel.exportFinished(false)
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(cert.pkcs12) } != null
                    }.getOrDefault(false)
                }
                viewModel.exportFinished(ok)
            }
        }
    LaunchedEffect(pendingExport) {
        if (pendingExport != null) exporter.launch("mumble-certificate.p12")
    }

    Box(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            PreferenceCategory("Identity")
            TextPreference(
                "Default username",
                settings.defaultUsername,
                { v -> settingsViewModel.update { it.copy(defaultUsername = v.ifBlank { "Mumbler" }) } },
                summary = "Used for servers that don't set their own",
                icon = Icons.Outlined.Person,
            )

            PreferenceCategory("Client certificates")
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text(
                "Servers recognise registered users by certificate. Keep a backup: losing it loses your registration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            CertificateRow(null, selected = state.selectedId == null, onSelect = { viewModel.select(null) })
            state.certificates.forEach { cert ->
                CertificateRow(
                    cert,
                    selected = state.selectedId == cert.id,
                    onSelect = { viewModel.select(cert.id) },
                    onExport = { viewModel.prepareExport(cert.id) },
                    onDelete = { viewModel.delete(cert.id) },
                )
            }
            ClickPreference("Generate certificate", onClick = {
                generating = true
            }, icon = Icons.Outlined.AddCircleOutline, enabled = !state.busy)
            ClickPreference(
                "Import certificate",
                onClick = { importer.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) },
                summary = "From a .p12 / .pfx file",
                icon = Icons.Outlined.FileUpload,
                enabled = !state.busy,
            )

            PreferenceCategory("Server certificates")
            ClickPreference(
                "Forget trusted server certificates",
                onClick = { confirmClearTrust = true },
                summary = if (trusted == 0) "None trusted yet" else "$trusted trusted; you will be asked again",
                icon = Icons.Outlined.GppBad,
                enabled = trusted > 0,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (generating) {
        var name by rememberSaveable { mutableStateOf(settings.defaultUsername) }
        AlertDialog(
            onDismissRequest = { generating = false },
            title = { Text("Generate certificate") },
            text = {
                OutlinedTextField(value = name, onValueChange = {
                    name = it
                }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.generate(name)
                    generating = false
                }) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = { generating = false }) { Text("Cancel") } },
        )
    }
    importUri?.let { uriString ->
        var password by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Import certificate") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("File password") },
                    placeholder = { Text("Leave empty if none") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    importUri = null
                    scope.launch {
                        val uri = uriString.toUri()
                        val bytes = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }.getOrNull()
                        }
                        if (bytes == null) {
                            snackbar.showSnackbar("Could not read the file")
                        } else {
                            viewModel.import(bytes, password, displayName = "")
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importUri = null }) { Text("Cancel") } },
        )
    }
    if (confirmClearTrust) {
        AlertDialog(
            onDismissRequest = { confirmClearTrust = false },
            title = { Text("Forget trusted certificates?") },
            text = { Text("You will be asked to confirm each server's certificate again the next time you connect.") },
            confirmButton = {
                TextButton(onClick = {
                    settingsViewModel.clearTrustedCertificates()
                    confirmClearTrust = false
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmClearTrust = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CertificateRow(
    cert: StoredCertificate?,
    selected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
    ListItem(
        headlineContent = { Text(cert?.name ?: "No certificate") },
        supportingContent = {
            Text(
                if (cert == null) {
                    "Connect anonymously; you can't register on servers"
                } else {
                    "SHA-1 ${cert.sha1.take(16)}… · expires ${date.format(Date(cert.notAfterEpochMillis))}"
                },
            )
        },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = cert?.let {
            {
                Box {
                    IconButton(onClick = {
                        menu = true
                    }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${cert.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = {
                            Text("Export")
                        }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, onClick = {
                            menu = false
                            onExport()
                        })
                        DropdownMenuItem(text = {
                            Text("Delete")
                        }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}
