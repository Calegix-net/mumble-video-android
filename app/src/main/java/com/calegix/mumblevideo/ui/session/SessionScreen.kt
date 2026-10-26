package com.calegix.mumblevideo.ui.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.HeadsetOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsVoice
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ConnectPhase
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.TalkState
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VoiceInputMode
import com.calegix.mumblevideo.ui.AppViewModelProvider
import com.calegix.mumblevideo.ui.video.VideoPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SessionTab(val label: String) { Channels("Channels"), Chat("Chat"), Video("Video") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(
    onOpenSettings: () -> Unit,
    onOpenStream: (StreamKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = viewModel(factory = AppViewModelProvider.Factory),
    chatViewModel: ChatViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sendImage: (Uri) -> Unit = remember(chatViewModel) {
        { uri ->
            chatViewModel.sendImage { limit ->
                withContext(Dispatchers.Default) { loadPicture(context, uri)?.let { ChatImages.toMessage(it, limit) } }
            }
        }
    }
    val channelActions = remember(viewModel) {
        ChannelActions(viewModel::joinChannel, viewModel::toggleCollapsed, viewModel::showUser, viewModel::pushToTalk)
    }
    val talk by viewModel.talkStates.collectAsStateWithLifecycle()
    val chat by chatViewModel.uiState.collectAsStateWithLifecycle()
    val draft by chatViewModel.draft.collectAsStateWithLifecycle()
    val user by viewModel.userDetails.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // Expanded width (tablets, unfolded foldables, landscape large screens): channels and chat
    // sit side by side as list and detail, so Chat drops out of the navigation.
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val wide = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    // While typing, the bottom bar would sit between the composer and the keyboard; hide it.
    val imeVisible = WindowInsets.isImeVisible
    val suiteType = if (imeVisible && !wide) {
        NavigationSuiteType.None
    } else {
        NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    }
    var tab by rememberSaveable { mutableStateOf(SessionTab.Channels) }
    if (wide && tab == SessionTab.Chat) tab = SessionTab.Channels
    val chatVisible = tab == SessionTab.Chat || (wide && tab == SessionTab.Channels)
    LaunchedEffect(chatVisible, chat.entries.size) { if (chatVisible) chatViewModel.markRead() }

    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showTokens by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Back from a connected session goes to the server list without disconnecting, as in Mumla;
    // the notification and the session entry on the list bring it back.
    BackHandler { onBack() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SessionTopBar(
                state = state,
                onToggleMute = viewModel::toggleMute,
                onToggleDeafen = viewModel::toggleDeafen,
                onInputMode = viewModel::setInputMode,
                onServerInfo = { showInfo = true },
                onAccessTokens = { showTokens = true },
                onSettings = onOpenSettings,
                onDisconnect = viewModel::disconnect,
            )
        },
    ) { padding ->
        NavigationSuiteScaffold(
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            layoutType = suiteType,
            navigationSuiteItems = {
                SessionTab.entries.filter { !(wide && it == SessionTab.Chat) }.forEach { item ->
                    item(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                        icon = {
                            val badge = when (item) {
                                SessionTab.Chat -> state.unreadMessages
                                SessionTab.Video -> state.streamCount
                                SessionTab.Channels -> if (wide) state.unreadMessages else 0
                            }
                            BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) {
                                Icon(
                                    when (item) {
                                        SessionTab.Channels -> Icons.Outlined.Forum
                                        SessionTab.Chat -> Icons.AutoMirrored.Outlined.Chat
                                        SessionTab.Video -> Icons.Outlined.VideoLibrary
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                ConnectionBanner(state.connection)
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        SessionTab.Channels -> if (wide) {
                            Row(Modifier.fillMaxSize()) {
                                ChannelsWithVoice(state, talk, channelActions, Modifier.width(400.dp))
                                VerticalDivider()
                                ChatPane(
                                    chat,
                                    draft,
                                    chatViewModel::setDraft,
                                    chatViewModel::send,
                                    chatViewModel::selectTarget,
                                    sendImage,
                                    Modifier.weight(1f),
                                )
                            }
                        } else {
                            ChannelsWithVoice(state, talk, channelActions, Modifier.fillMaxSize())
                        }

                        SessionTab.Chat -> ChatPane(
                            chat,
                            draft,
                            chatViewModel::setDraft,
                            chatViewModel::send,
                            chatViewModel::selectTarget,
                            sendImage,
                        )

                        SessionTab.Video -> VideoPane(onOpenStream = onOpenStream)
                    }
                }
            }
        }
    }

    user?.let { details ->
        UserSheet(
            details = details,
            onDismiss = viewModel::dismissUser,
            onPrivateMessage = {
                chatViewModel.selectTarget(MessageTarget.ToUser(details.user.session))
                viewModel.dismissUser()
                tab = if (wide) SessionTab.Channels else SessionTab.Chat
            },
        )
    }
    if (showInfo) ServerInfoDialog(state, onDismiss = { showInfo = false })
    if (showTokens) {
        AccessTokensDialog(
            initial = viewModel.accessTokens(),
            onSave = {
                viewModel.setAccessTokens(it)
                showTokens = false
            },
            onDismiss = { showTokens = false },
        )
    }
}

/** What the channel list can ask for, so it takes no ViewModel. */
private class ChannelActions(
    val join: (ChannelId) -> Unit,
    val toggle: (ChannelId) -> Unit,
    val showUser: (User) -> Unit,
    val pushToTalk: (Boolean) -> Unit,
)

@Composable
private fun ChannelsWithVoice(
    state: SessionUiState,
    talk: Map<UserSession, TalkState>,
    actions: ChannelActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (state.rows.isEmpty() && state.connection !is ConnectionState.Connected) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Waiting for the server…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ChannelList(
                rows = state.rows,
                talkStates = talk,
                settings = state.settings,
                onJoin = actions.join,
                onToggle = actions.toggle,
                onUser = actions.showUser,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.showPttButton) {
            PushToTalkButton(state.voice, state.settings.pttButtonSize.heightDp, onPress = actions.pushToTalk)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTopBar(
    state: SessionUiState,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onInputMode: (VoiceInputMode) -> Unit,
    onServerInfo: () -> Unit,
    onAccessTokens: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var modes by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(state.serverName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    state.voice.transmitting -> "Transmitting · ${state.currentChannel.orEmpty()}"
                    else -> state.currentChannel ?: statusText(state.connection)
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            IconToggleButton(checked = state.voice.selfMuted, onCheckedChange = {
                onToggleMute()
            }, enabled = state.connected) {
                Icon(
                    if (state.voice.selfMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                    contentDescription = if (state.voice.selfMuted) "Unmute" else "Mute",
                )
            }
            IconToggleButton(checked = state.voice.selfDeafened, onCheckedChange = {
                onToggleDeafen()
            }, enabled = state.connected) {
                Icon(
                    if (state.voice.selfDeafened) Icons.Outlined.HeadsetOff else Icons.Outlined.Headset,
                    contentDescription = if (state.voice.selfDeafened) "Undeafen" else "Deafen",
                )
            }
            Box {
                IconButton(onClick = {
                    menu = true
                }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Input: " + inputLabel(state.voice.inputMode)) },
                        leadingIcon = { Icon(Icons.Outlined.SettingsVoice, null) },
                        onClick = {
                            menu = false
                            modes = true
                        },
                    )
                    DropdownMenuItem(text = {
                        Text("Server information")
                    }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = {
                        menu = false
                        onServerInfo()
                    })
                    DropdownMenuItem(text = {
                        Text("Access tokens")
                    }, leadingIcon = { Icon(Icons.Outlined.Key, null) }, onClick = {
                        menu = false
                        onAccessTokens()
                    })
                    DropdownMenuItem(text = {
                        Text("Settings")
                    }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = {
                        menu = false
                        onSettings()
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = {
                        Text("Disconnect")
                    }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) }, onClick = {
                        menu = false
                        onDisconnect()
                    })
                }
                DropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
                    VoiceInputMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(inputLabel(mode)) },
                            leadingIcon = { RadioButton(selected = mode == state.voice.inputMode, onClick = null) },
                            onClick = {
                                onInputMode(mode)
                                modes = false
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ConnectionBanner(connection: ConnectionState) {
    val text = when (connection) {
        is ConnectionState.Connecting -> statusText(connection)

        is ConnectionState.Reconnecting ->
            "Connection lost. Reconnecting in ${(connection.retryInMillis / 1000).coerceAtLeast(
                1,
            )} s (attempt ${connection.attempt})"

        is ConnectionState.CertificateTrustRequired -> "Waiting for certificate decision"

        else -> null
    } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun statusText(state: ConnectionState): String = when (state) {
    is ConnectionState.Connecting -> when (state.phase) {
        ConnectPhase.Resolving -> "Looking up ${state.server.host}…"
        ConnectPhase.Handshaking -> "Securing connection…"
        ConnectPhase.Authenticating -> "Logging in…"
        ConnectPhase.Synchronising -> "Loading channels…"
    }

    is ConnectionState.Connected -> "Connected"

    is ConnectionState.Reconnecting -> "Reconnecting…"

    is ConnectionState.CertificateTrustRequired -> "Certificate check"

    is ConnectionState.Rejected -> "Rejected"

    is ConnectionState.Disconnected -> "Disconnected"
}

internal fun inputLabel(mode: VoiceInputMode) = when (mode) {
    VoiceInputMode.PushToTalk -> "Push-to-talk"
    VoiceInputMode.VoiceActivity -> "Voice activity"
    VoiceInputMode.Continuous -> "Continuous"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserSheet(details: UserDetails, onDismiss: () -> Unit, onPrivateMessage: () -> Unit) {
    val user = details.user
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(user.name, style = MaterialTheme.typography.headlineSmall)
            val facts = buildList {
                add(if (user.registeredId != null) "Registered" else "Unregistered")
                if (user.prioritySpeaker) add("Priority speaker")
                if (user.recording) add("Recording")
                if (user.muted) add("Muted by server")
                if (user.deafened) add("Deafened by server")
                if (user.selfMuted) add("Muted")
                if (user.selfDeafened) add("Deafened")
            }
            Text(facts.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                details.loadingComment -> LinearProgressIndicator(Modifier.fillMaxWidth())

                details.comment != null -> Text(
                    AnnotatedString.fromHtml(details.comment),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPrivateMessage) { Text("Send private message") }
            }
        }
    }
}

@Composable
private fun ServerInfoDialog(state: SessionUiState, onDismiss: () -> Unit) {
    val connected = state.connection as? ConnectionState.Connected
    val caps = state.capabilities
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server information") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (connected == null) {
                    Text("Not connected")
                } else {
                    val info = connected.info
                    InfoLine("Address", connected.server.toString())
                    InfoLine("Version", "${info.release} (protocol ${info.protocolVersion})")
                    InfoLine("OS", "${info.os} ${info.osVersion}")
                    InfoLine("Voice", if (info.udpActive) "UDP" else "TCP tunnel")
                    InfoLine("Latency", if (info.latencyMillis > 0) "${info.latencyMillis} ms" else "measuring…")
                    InfoLine("Max users", info.maxUsers.takeIf { it > 0 }?.toString() ?: NOT_ADVERTISED)
                    InfoLine("Audio bandwidth", "${caps.maxAudioBandwidth / 1000} kbit/s")
                    InfoLine("Video", if (caps.video) "Supported" else "Not supported")
                    if (caps.video) {
                        InfoLine(
                            "Video bandwidth",
                            caps.maxVideoBandwidth.takeIf { it > 0 }?.let { "${it / 1000} kbit/s per stream" }
                                ?: NOT_ADVERTISED,
                        )
                        InfoLine(
                            "Streams per channel",
                            caps.maxVideoStreamsPerChannel.takeIf { it > 0 }?.toString() ?: NOT_ADVERTISED,
                        )
                    }
                    if (info.welcomeText.isNotBlank()) {
                        Text(
                            "Welcome message",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        // AlertDialog's own container and text colours, which the server's colours must read on.
                        RichHtmlText(
                            info.welcomeText,
                            background = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccessTokensDialog(initial: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Access tokens") },
        text = {
            Column {
                Text(
                    "One per line. Tokens act like passwords for channels that require them.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = text, onValueChange = {
                    text = it
                }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.lines()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Decodes a picked picture, already scaled down so a huge photo never sits in memory at full size. */
internal fun loadPicture(context: Context, uri: Uri): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 2048) {
            val factor = 2048f / longest
            decoder.setTargetSize((info.size.width * factor).toInt(), (info.size.height * factor).toInt())
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

/** For limits a server did not state; v1.9.x servers state none of the video ones. */
private const val NOT_ADVERTISED = "not advertised"
