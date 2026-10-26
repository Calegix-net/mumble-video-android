package com.calegix.mumblevideo.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.calegix.mumblevideo.core.MessageTarget
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChatPane(
    state: ChatUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectTarget: (MessageTarget?) -> Unit,
    onSendImage: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) listState.animateScrollToItem(state.entries.lastIndex)
    }
    Column(modifier.fillMaxSize().imePadding()) {
        if (state.entries.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "No messages yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(state.entries, key = { it.message.id }) { MessageBubble(it, state.loadImages) }
            }
        }
        Composer(state, draft, onDraftChange, onSend, onSelectTarget, onSendImage)
    }
}

@Composable
private fun MessageBubble(entry: ChatEntry, loadImages: Boolean) {
    val message = entry.message
    val own = message.isOwn
    val private = message.target is MessageTarget.ToUser
    val time = remember(message.timestampEpochMillis) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestampEpochMillis))
    }
    val parts = remember(message.html) { ChatImages.split(message.html) }
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        own -> scheme.primaryContainer to scheme.onPrimaryContainer
        private -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.surfaceContainerHigh to scheme.onSurface
    }
    val secondary = content.copy(alpha = 0.75f)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .background(container, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                if (own) "You → ${entry.targetLabel}" else "${message.senderName} → ${entry.targetLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            parts.forEach { part ->
                when (part) {
                    is ChatImages.Part.Html -> {
                        val text = remember(part) { AnnotatedString.fromHtml(part.html) }
                        Text(text, style = MaterialTheme.typography.bodyLarge, color = content)
                    }

                    is ChatImages.Part.Image -> if (loadImages) {
                        ChatImage(part.bytes, "Picture from ${if (own) "you" else message.senderName}")
                    } else {
                        Text(
                            "Picture hidden (Settings › General › Load images)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondary,
                        )
                    }
                }
            }
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectTarget: (MessageTarget?) -> Unit,
    onSendImage: (Uri) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // The system photo picker: no storage permission, and only the chosen picture is shared.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onSendImage)
    }
    // The system camera app writes the photo to a file of ours, shared with it for that one shot.
    val context = LocalContext.current
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingPhoto?.toUri()
        pendingPhoto = null
        if (taken && uri != null) onSendImage(uri)
    }
    val launchCamera = {
        val uri = ChatPhotos.newCaptureUri(context)
        pendingPhoto = uri.toString()
        camera.launch(uri)
    }
    // We declare CAMERA (for video), and an app that declares it may only start the camera app
    // once it holds it.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }
    var photoMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)) {
        Box {
            AssistChip(
                onClick = { menu = true },
                label = { Text("To: " + (state.target?.label ?: "#${state.currentChannelLabel}")) },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("My current channel") },
                    onClick = {
                        onSelectTarget(null)
                        menu = false
                    },
                )
                state.availableTargets.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.label) },
                        onClick = {
                            onSelectTarget(target.target)
                            menu = false
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { photoMenu = true }, enabled = !state.sendingImage) {
                    if (state.sendingImage) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Send a picture")
                    }
                }
                DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Take photo") },
                        leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                        onClick = {
                            photoMenu = false
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Choose photo") },
                        leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                        onClick = {
                            photoMenu = false
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Message") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun ChatImage(bytes: ByteArray, description: String) {
    // Decoded off the main thread and within ChatImages' size limits: a crafted picture can
    // declare a size far beyond what the phone has memory for.
    // Null while decoding, then a list holding the picture or nothing if it could not be shown.
    val decoded by produceState<List<ImageBitmap>?>(null, bytes) {
        value = withContext(Dispatchers.Default) { listOfNotNull(ChatImages.decode(bytes)?.asImageBitmap()) }
    }
    val result = decoded ?: return
    val bitmap = result.firstOrNull()
    if (bitmap == null) {
        Text("Picture could not be shown", style = MaterialTheme.typography.bodyMedium)
        return
    }
    var viewing by rememberSaveable { mutableStateOf(false) }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "View full screen") { viewing = true },
    )
    if (viewing) ImageViewer(bitmap, description, onDismiss = { viewing = false })
}
