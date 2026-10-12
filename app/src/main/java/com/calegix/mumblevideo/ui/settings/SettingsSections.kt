package com.calegix.mumblevideo.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calegix.mumblevideo.BuildConfig
import com.calegix.mumblevideo.core.AudioLevel
import com.calegix.mumblevideo.core.AudioOutputStream
import com.calegix.mumblevideo.core.CameraFacing
import com.calegix.mumblevideo.core.EchoCancellation
import com.calegix.mumblevideo.core.VideoCodec
import com.calegix.mumblevideo.core.VoiceInputMode
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.data.settings.PttButtonSize
import com.calegix.mumblevideo.data.settings.ThemeMode
import com.calegix.mumblevideo.data.settings.VideoResolution
import com.calegix.mumblevideo.ui.AppViewModelProvider
import com.calegix.mumblevideo.ui.components.ClickPreference
import com.calegix.mumblevideo.ui.components.ListPreference
import com.calegix.mumblevideo.ui.components.PreferenceCategory
import com.calegix.mumblevideo.ui.components.SliderPreference
import com.calegix.mumblevideo.ui.components.SwitchPreference
import com.calegix.mumblevideo.ui.session.inputLabel
import com.calegix.mumblevideo.ui.theme.TalkColors
import kotlinx.coroutines.flow.Flow

@Composable
private fun SettingsColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) { content() }
}

@Composable
private fun settingsState(viewModel: SettingsViewModel): AppSettings =
    viewModel.settings.collectAsStateWithLifecycle().value

@Composable
fun GeneralSettings(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val s = settingsState(viewModel)
    SettingsColumn(modifier) {
        PreferenceCategory("While connected")
        SwitchPreference(
            "Keep screen on",
            s.keepScreenOn,
            { v -> viewModel.update { it.copy(keepScreenOn = v) } },
            summary = "Stop the screen sleeping while the app is open and connected",
        )
        PreferenceCategory("Chat")
        SwitchPreference(
            "Read messages aloud",
            s.textToSpeech,
            { v -> viewModel.update { it.copy(textToSpeech = v) } },
            summary = "Text-to-speech for incoming chat",
        )
        SwitchPreference(
            "Short spoken messages",
            s.shortTextToSpeech,
            { v -> viewModel.update { it.copy(shortTextToSpeech = v) } },
            summary = "Skip the sender and channel when reading aloud",
            enabled = s.textToSpeech,
        )
        SwitchPreference(
            "Load images",
            s.loadImages,
            { v -> viewModel.update { it.copy(loadImages = v) } },
            summary = "Show images embedded in chat messages",
        )
    }
}

@Composable
fun AudioSettings(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val s = settingsState(viewModel)
    var capturingKey by rememberSaveable { mutableStateOf(false) }
    SettingsColumn(modifier) {
        PreferenceCategory("Input")
        ListPreference(
            "Input method",
            VoiceInputMode.entries.map { it to inputLabel(it) },
            s.inputMode,
            { v -> viewModel.update { it.copy(inputMode = v) } },
            summary = "When your microphone is sent",
        )
        SliderPreference(
            "Input volume",
            s.inputGain,
            { v -> viewModel.update { it.copy(inputGain = v) } },
            valueRange = 0f..2f,
            label = { "${(it * 100).toInt()}%" },
        )

        if (s.inputMode == VoiceInputMode.VoiceActivity) {
            PreferenceCategory("Voice activity")
            SliderPreference(
                "Detection threshold",
                s.vadThreshold,
                { v -> viewModel.update { it.copy(vadThreshold = v) } },
                valueRange = 0f..1f,
                label = { "${(it * 100).toInt()}%" },
                summary = "Speak normally: the bar should cross the line only while you talk",
                below = { VadMeter(viewModel.inputLevels, s.vadThreshold) },
            )
        }

        PreferenceCategory("Push-to-talk")
        ClickPreference(
            "Push-to-talk key",
            onClick = { capturingKey = true },
            summary = s.pttKeyCode?.let { "Bound to ${KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_")}" }
                ?: "Use a hardware key (volume, camera, a keyboard key) to talk",
            icon = Icons.Outlined.Keyboard,
        )
        SwitchPreference(
            "Headset button",
            s.headsetButtonPtt,
            { v -> viewModel.update { it.copy(headsetButtonPtt = v) } },
            summary = "Use the wired or Bluetooth headset button as push-to-talk",
        )
        SwitchPreference(
            "Toggle instead of hold",
            s.pttToggle,
            { v -> viewModel.update { it.copy(pttToggle = v) } },
            summary = "Press once to start talking, again to stop",
        )
        SwitchPreference("Push-to-talk sound", s.pttSound, { v ->
            viewModel.update { it.copy(pttSound = v) }
        }, summary = "Click when you start and stop talking")
        SwitchPreference(
            "Hide on-screen button",
            s.hidePttButton,
            { v -> viewModel.update { it.copy(hidePttButton = v) } },
            summary = "For when you only use a hardware key",
        )
        SwitchPreference(
            "Half duplex",
            s.halfDuplex,
            { v -> viewModel.update { it.copy(halfDuplex = v) } },
            summary = "Silence others while you talk, to avoid echo on speaker",
        )

        PreferenceCategory("Quality")
        ListPreference(
            "Bitrate",
            AppSettings.BITRATES.map { it to "${it / 1000} kbit/s" },
            s.bitrate,
            { v -> viewModel.update { it.copy(bitrate = v) } },
            summary = "The server may cap this",
        )
        ListPreference(
            "Audio per packet",
            AppSettings.FRAMES_PER_PACKET.map { it to "${it * 10} ms" },
            s.framesPerPacket,
            { v -> viewModel.update { it.copy(framesPerPacket = v) } },
            summary = "Longer saves bandwidth, shorter lowers latency",
        )

        PreferenceCategory("Processing")
        SwitchPreference("Noise suppression", s.noiseSuppression, { v ->
            viewModel.update { it.copy(noiseSuppression = v) }
        })
        SwitchPreference("Automatic gain control", s.automaticGainControl, { v ->
            viewModel.update { it.copy(automaticGainControl = v) }
        })
        ListPreference(
            "Echo cancellation",
            listOf(EchoCancellation.Off to "Off", EchoCancellation.System to "Device echo canceller"),
            s.echoCancellation,
            { v -> viewModel.update { it.copy(echoCancellation = v) } },
        )

        PreferenceCategory("Output")
        ListPreference(
            "Output stream",
            listOf(
                AudioOutputStream.VoiceCall to "Voice call (earpiece capable)",
                AudioOutputStream.Media to "Media (speaker)",
            ),
            s.outputStream,
            { v -> viewModel.update { it.copy(outputStream = v) } },
            summary = "Which volume slider controls Mumble",
        )
        SwitchPreference(
            "Bluetooth headset",
            s.bluetoothHeadset,
            { v -> viewModel.update { it.copy(bluetoothHeadset = v) } },
            summary = "Route voice through a connected Bluetooth headset",
        )
    }
    if (capturingKey) {
        PttKeyDialog(
            current = s.pttKeyCode,
            onKey = { code ->
                viewModel.update { it.copy(pttKeyCode = code) }
                capturingKey = false
            },
            onDismiss = { capturingKey = false },
        )
    }
}

/** Live input level against the threshold; the mic is open only while this is on screen. */
@Composable
private fun VadMeter(levels: Flow<AudioLevel>, threshold: Float) {
    val level by levels.collectAsStateWithLifecycle(AudioLevel(0f, false))
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .onSizeChanged { size = it }
            .semantics { contentDescription = "Microphone level ${(level.level * 100).toInt()} percent" },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.level)
                .background(if (level.aboveThreshold) TalkColors.Talking else MaterialTheme.colorScheme.outline),
        )
        Box(
            Modifier
                .offset { IntOffset((size.width * threshold).toInt(), 0) }
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface),
        )
    }
}

@Composable
private fun PttKeyDialog(current: Int?, onKey: (Int?) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Push-to-talk key") },
        text = {
            Text(
                "Press the key you want to hold to talk." +
                    (current?.let { "\n\nCurrently: ${KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_")}" } ?: ""),
                modifier = Modifier
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val code = event.key.nativeKeyCode
                        if (code == KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                        onKey(code)
                        true
                    },
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = { TextButton(onClick = { onKey(null) }) { Text("Clear") } },
    )
}

@Composable
fun AppearanceSettings(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val s = settingsState(viewModel)
    SettingsColumn(modifier) {
        PreferenceCategory("Theme")
        ListPreference(
            "Theme",
            listOf(ThemeMode.System to "Follow system", ThemeMode.Light to "Light", ThemeMode.Dark to "Dark"),
            s.themeMode,
            { v -> viewModel.update { it.copy(themeMode = v) } },
        )
        SwitchPreference(
            "True black",
            s.trueBlack,
            { v -> viewModel.update { it.copy(trueBlack = v) } },
            summary = "Pure black backgrounds in dark mode; saves power on OLED screens",
        )
        SwitchPreference(
            "Dynamic colour",
            s.dynamicColor,
            { v -> viewModel.update { it.copy(dynamicColor = v) } },
            summary = "Take colours from your wallpaper",
        )
        PreferenceCategory("Channel list")
        SwitchPreference("Show user counts", s.showUserCount, { v -> viewModel.update { it.copy(showUserCount = v) } })
        SwitchPreference("Compact rows", s.compactChannelList, { v ->
            viewModel.update { it.copy(compactChannelList = v) }
        })
        SwitchPreference(
            "Highlight busy channels",
            s.colorizeBusyChannels,
            { v -> viewModel.update { it.copy(colorizeBusyChannels = v) } },
            summary = "Tint channels with at least ${s.busyChannelThreshold} users",
        )
        SliderPreference(
            "Busy channel threshold",
            s.busyChannelThreshold.toFloat(),
            { v -> viewModel.update { it.copy(busyChannelThreshold = v.toInt()) } },
            valueRange = 2f..20f,
            steps = 17,
            label = { it.toInt().toString() },
            enabled = s.colorizeBusyChannels,
        )
        PreferenceCategory("Push-to-talk button")
        ListPreference(
            "Button size",
            PttButtonSize.entries.map { it to it.name },
            s.pttButtonSize,
            { v -> viewModel.update { it.copy(pttButtonSize = v) } },
        )
    }
}

@Composable
fun VideoSettings(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val s = settingsState(viewModel)
    val resolutions = VideoResolution.entries.map { it to it.label }
    val framerates = AppSettings.FRAMERATES.map { it to "$it fps" }
    SettingsColumn(modifier) {
        PreferenceCategory("Watching")
        SwitchPreference(
            "Start streams automatically",
            s.autoSubscribe,
            { v -> viewModel.update { it.copy(autoSubscribe = v) } },
            summary = "Otherwise tap a stream to start watching it",
            enabled = !s.dataSaver,
        )
        SwitchPreference(
            "Data saver",
            s.dataSaver,
            { v -> viewModel.update { it.copy(dataSaver = v) } },
            summary = "Nothing plays until tapped, and thumbnails ask senders for low bitrate",
        )
        PreferenceCategory("Camera")
        ListPreference(
            "Camera",
            listOf(
                CameraFacing.Front to "Front",
                CameraFacing.Back to "Back",
                CameraFacing.External to "External (USB)",
            ),
            s.cameraFacing,
            { v -> viewModel.update { it.copy(cameraFacing = v) } },
        )
        ListPreference("Resolution", resolutions, s.cameraResolution, { v ->
            viewModel.update { it.copy(cameraResolution = v) }
        })
        ListPreference("Frame rate", framerates, s.cameraFramerate, { v ->
            viewModel.update { it.copy(cameraFramerate = v) }
        })
        PreferenceCategory("Screen sharing")
        ListPreference("Resolution", resolutions, s.screenResolution, { v ->
            viewModel.update { it.copy(screenResolution = v) }
        })
        ListPreference("Frame rate", framerates, s.screenFramerate, { v ->
            viewModel.update { it.copy(screenFramerate = v) }
        })
        SwitchPreference(
            "Share device audio",
            s.shareDeviceAudio,
            { v -> viewModel.update { it.copy(shareDeviceAudio = v) } },
            summary = "Send what the phone is playing along with the screen",
        )
        PreferenceCategory("Encoding")
        ListPreference(
            "Preferred codec",
            listOf<Pair<VideoCodec?, String>>(null to "Automatic") + VideoCodec.entries.map { it to codecLabel(it) },
            s.codecPreference,
            { v -> viewModel.update { it.copy(codecPreference = v) } },
            summary = "Used when the server and device both support it",
        )
    }
}

private fun codecLabel(codec: VideoCodec) = when (codec) {
    VideoCodec.TiledImage -> "Tiled images (screen content)"
    VideoCodec.VP8 -> "VP8"
    VideoCodec.VP9 -> "VP9"
    VideoCodec.AV1 -> "AV1"
    VideoCodec.H264 -> "H.264"
}

@Composable
fun NotificationSettings(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val s = settingsState(viewModel)
    SettingsColumn(modifier) {
        PreferenceCategory("Messages")
        SwitchPreference(
            "Message notifications",
            s.notifyMessages,
            { v -> viewModel.update { it.copy(notifyMessages = v) } },
            summary = "When the app is in the background",
        )
        SwitchPreference(
            "Private messages only",
            s.notifyPrivateOnly,
            { v -> viewModel.update { it.copy(notifyPrivateOnly = v) } },
            enabled = s.notifyMessages,
        )
        SwitchPreference(
            "Vibrate for private messages",
            s.vibrateOnPrivateMessage,
            { v -> viewModel.update { it.copy(vibrateOnPrivateMessage = v) } },
            enabled = s.notifyMessages,
        )
        PreferenceCategory("Video")
        SwitchPreference(
            "New streams",
            s.notifyStreams,
            { v -> viewModel.update { it.copy(notifyStreams = v) } },
            summary = "When someone in your channel starts sharing",
        )
        SwitchPreference(
            "Watchers",
            s.notifyWatchers,
            { v -> viewModel.update { it.copy(notifyWatchers = v) } },
            summary = "When someone starts watching your camera or screen",
        )
    }
}

@Composable
fun AdvancedSettings(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val s = settingsState(viewModel)
    SettingsColumn(modifier) {
        PreferenceCategory("Connection")
        SwitchPreference(
            "Reconnect automatically",
            s.autoReconnect,
            { v -> viewModel.update { it.copy(autoReconnect = v) } },
            summary = "Retry when an established connection drops",
        )
        SwitchPreference(
            "Force TCP",
            s.forceTcp,
            { v -> viewModel.update { it.copy(forceTcp = v) } },
            summary = "Send voice over TCP. Slower, but works on networks that block UDP",
        )
        PreferenceCategory("Developer")
        SwitchPreference(
            "Use simulated server",
            s.useSimulatedCore,
            { v -> viewModel.update { it.copy(useSimulatedCore = v) } },
            summary = "Talk to a scripted fake server instead of real ones. Takes effect when the app restarts",
        )
        SwitchPreference(
            "Simulate connection drops",
            s.simulateConnectionDrops,
            { v -> viewModel.update { it.copy(simulateConnectionDrops = v) } },
            summary = "Simulated server only: every connection drops after 20 s and reconnects",
            enabled = s.useSimulatedCore,
        )
    }
}

@Composable
fun AboutSettings(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    SettingsColumn(modifier) {
        PreferenceCategory("Mumble Video")
        ClickPreference("Version", onClick = {}, summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        ClickPreference(
            "Core",
            onClick = {},
            summary = "Voice: Opus over OCB2-encrypted UDP (TCP fallback). Video receive: not yet " +
                "(waits on mumble-core's video transport over JNI).",
        )
        ClickPreference(
            "Source of the desktop client and server",
            onClick = { uri.openUri("https://github.com/Calegix-net/mumble-video") },
            summary = "github.com/Calegix-net/mumble-video",
        )
        PreferenceCategory("Open-source licences")
        LICENCES.forEach { (name, licence) -> ClickPreference(name, onClick = {}, summary = licence) }
        PreferenceCategory("Acknowledgements")
        Text(
            "Mumble, by the Mumble developers. The layout of this app's screens and settings takes its cue from " +
                "Mumla and Plumble; no code from either is included.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private val LICENCES = listOf(
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
    "AndroidX (Compose, Material 3, Navigation, Lifecycle, Room, DataStore)" to "Apache License 2.0",
    "Material Icons" to "Apache License 2.0",
)
