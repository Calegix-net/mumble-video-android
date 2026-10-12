package com.calegix.mumblevideo.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.calegix.mumblevideo.core.VoiceState
import com.calegix.mumblevideo.ui.theme.TalkColors

/** The big hold-to-talk button, sized from Appearance settings. */
@Composable
fun PushToTalkButton(
    voice: VoiceState,
    heightDp: Int,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = voice.pushToTalkActive
    val color by animateColorAsState(
        if (active) TalkColors.Talking else MaterialTheme.colorScheme.primaryContainer,
        label = "ptt",
    )
    val content = if (active) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .height(heightDp.dp)
            .background(color, RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress(true)
                        tryAwaitRelease()
                        onPress(false)
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = "Push to talk"
                stateDescription = if (active) "Talking" else "Not talking"
                // TalkBack users double-tap-and-hold; a plain activation toggles a short burst.
                onClick {
                    onPress(true)
                    onPress(false)
                    true
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Mic, contentDescription = null, tint = content)
            Text(
                if (active) "Talking" else "Hold to talk",
                style = MaterialTheme.typography.titleMedium,
                color = content,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
