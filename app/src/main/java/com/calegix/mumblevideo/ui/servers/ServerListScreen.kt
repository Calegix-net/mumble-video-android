package com.calegix.mumblevideo.ui.servers

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onOpenSettings: () -> Unit,
    onOpenPublicServers: () -> Unit,
    onOpenSession: () -> Unit,
    pendingLink: SavedServer?,
    onConsumeLink: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerListViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connectRequest by viewModel.connectRequest.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A mumble:// link opens the add-server form, prefilled.
    val currentOnConsumeLink by rememberUpdatedState(onConsumeLink)
    LaunchedEffect(pendingLink) {
        pendingLink?.let {
            viewModel.addServer(prefill = it)
            currentOnConsumeLink()
        }
    }

    // Ask for the microphone (and notifications on 13+) at the moment it makes sense: connecting.
    // The connection goes ahead either way; without the mic we can still listen.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        connectRequest?.let(viewModel::connect)
    }
    LaunchedEffect(connectRequest) {
        if (connectRequest != null) {
            val wanted = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                // Android 17 blocks apps from the local network without this; ask only when it matters.
                if (Build.VERSION.SDK_INT >= 37 && LocalNetwork.isLocal(connectRequest!!.host)) {
                    add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
            permissions.launch(wanted.toTypedArray())
        }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Servers") },
                actions = {
                    IconButton(onClick = onOpenPublicServers) {
                        Icon(Icons.Outlined.Public, contentDescription = "Public servers")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.addServer() },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Add server") },
                // The animated label doesn't reach the accessibility tree on its own.
                modifier = Modifier.semantics { contentDescription = "Add server" },
            )
        },
    ) { padding ->
        if (!state.loading && state.servers.isEmpty()) {
            EmptyServers(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom =
                        96.dp + padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                activeSession?.let { name ->
                    item(span = { GridItemSpan(maxLineSpan) }) { ActiveSessionCard(name, onOpenSession) }
                }
                items(state.servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        onConnect = { viewModel.requestConnect(server) },
                        onToggleFavourite = { viewModel.toggleFavourite(server) },
                        onEdit = { viewModel.editServer(server) },
                        onDelete = { viewModel.requestDelete(server) },
                        onShare = {
                            val send = Intent(
                                Intent.ACTION_SEND,
                            ).setType("text/plain").putExtra(Intent.EXTRA_TEXT, server.toMumbleUrl())
                            context.startActivity(Intent.createChooser(send, "Share server"))
                        },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        ServerEditorDialog(
            editor = editor,
            onChange = viewModel::updateEditor,
            onSave = { viewModel.saveEditor() },
            onDismiss = viewModel::dismissEditor,
        )
    }
    state.pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("Delete ${server.displayName}?") },
            text = { Text("The server is removed from your list. Its certificate trust is kept.") },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerCard(
    server: SavedServer,
    onConnect: () -> Unit,
    onToggleFavourite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(
        onClick = onConnect,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Connect to ${server.displayName}" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                Text(
                    server.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.address.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server.username.isNotBlank()) {
                    Text(
                        "as ${server.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconToggleButton(checked = server.favourite, onCheckedChange = { onToggleFavourite() }) {
                Icon(
                    if (server.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (server.favourite) "Remove from favourites" else "Add to favourites",
                    tint = if (server.favourite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Box {
                IconButton(onClick = {
                    menu = true
                }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options for ${server.displayName}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Connect") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Login, null) },
                        onClick = {
                            menu = false
                            onConnect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = {
                            menu = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(name: String, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.GraphicEq, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Connected to $name", style = MaterialTheme.typography.titleMedium)
                Text("Tap to return", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmptyServers(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Dns,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("No servers yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Add one with its address, or browse the public list.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerEditorDialog(
    editor: ServerEditor,
    onChange: ((ServerEditor) -> ServerEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.isNew) "Add server" else "Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = editor.label,
                    onValueChange = { v -> onChange { it.copy(label = v) } },
                    label = { Text("Label") },
                    placeholder = { Text(editor.host.ifBlank { "My server" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editor.host,
                        onValueChange = { v -> onChange { it.copy(host = v, hostError = null) } },
                        label = { Text("Address") },
                        isError = editor.hostError != null,
                        supportingText = editor.hostError?.let { { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = editor.port,
                        onValueChange = { v ->
                            onChange { it.copy(port = v.filter(Char::isDigit).take(5), portError = null) }
                        },
                        label = { Text("Port") },
                        placeholder = { Text(ServerAddress.DEFAULT_PORT.toString()) },
                        isError = editor.portError != null,
                        supportingText = editor.portError?.let { { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = editor.username,
                    onValueChange = { v -> onChange { it.copy(username = v) } },
                    label = { Text("Username") },
                    placeholder = { Text("Default username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.password,
                    onValueChange = { v -> onChange { it.copy(password = v) } },
                    label = { Text("Server password") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Favourite", modifier = Modifier.weight(1f))
                    Switch(checked = editor.favourite, onCheckedChange = { v -> onChange { it.copy(favourite = v) } })
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
