package com.calegix.mumblevideo.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.HeadsetOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.TalkState
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.ui.theme.TalkColors

@Composable
fun ChannelList(
    rows: List<TreeRow>,
    talkStates: Map<UserSession, TalkState>,
    settings: AppSettings,
    onJoin: (ChannelId) -> Unit,
    onToggle: (ChannelId) -> Unit,
    onUser: (User) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is TreeRow.ChannelRow -> ChannelRowItem(row, settings, onJoin = {
                    onJoin(row.channel.id)
                }, onToggle = { onToggle(row.channel.id) })

                is TreeRow.UserRow -> UserRowItem(
                    row = row,
                    talk = talkStates[row.user.session] ?: TalkState.Passive,
                    compact = settings.compactChannelList,
                    onClick = { onUser(row.user) },
                )
            }
        }
    }
}

private val INDENT = 20.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRowItem(row: TreeRow.ChannelRow, settings: AppSettings, onJoin: () -> Unit, onToggle: () -> Unit) {
    val busy = settings.colorizeBusyChannels && row.totalUsers >= settings.busyChannelThreshold
    val background = when {
        row.isCurrent -> MaterialTheme.colorScheme.secondaryContainer
        busy -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (settings.compactChannelList) 40.dp else 52.dp)
            .background(background)
            .combinedClickable(
                enabled = row.channel.canEnter && !row.isCurrent,
                role = Role.Button,
                onClickLabel = "Join channel",
                onClick = onJoin,
                onLongClickLabel = if (row.expanded) "Collapse" else "Expand",
                onLongClick = onToggle,
            )
            .semantics {
                contentDescription = buildString {
                    append("Channel ${row.channel.name}, ${row.totalUsers} users")
                    if (row.isCurrent) append(", you are here")
                    if (!row.channel.canEnter) append(", locked")
                }
            }
            .padding(start = 8.dp + INDENT * row.depth, end = 8.dp),
    ) {
        if (row.hasChildren) {
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = (if (row.expanded) "Collapse " else "Expand ") + row.channel.name,
                )
            }
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Text(
            row.channel.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (row.isCurrent) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!row.channel.canEnter) StatusIcon(Icons.Outlined.Lock, "Locked")
        if (settings.showUserCount && row.totalUsers > 0) {
            Text(
                if (row.channel.maxUsers >
                    0
                ) {
                    "${row.totalUsers}/${row.channel.maxUsers}"
                } else {
                    row.totalUsers.toString()
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun UserRowItem(row: TreeRow.UserRow, talk: TalkState, compact: Boolean, onClick: () -> Unit) {
    val user = row.user
    val ring by animateColorAsState(
        when (talk) {
            TalkState.Talking -> TalkColors.Talking
            TalkState.Shouting -> TalkColors.Shouting
            TalkState.Whispering -> TalkColors.Whispering
            TalkState.Passive -> Color.Transparent
        },
        label = "talk",
    )
    val status = userStatus(user, talk)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 40.dp else 52.dp)
            .clickable(role = Role.Button, onClickLabel = "Show user", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = user.name + if (row.isSelf) " (you)" else ""
                stateDescription = status
            }
            .padding(start = 8.dp + INDENT * row.depth, end = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (compact) 28.dp else 36.dp)
                .border(3.dp, ring, CircleShape)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .clearAndSetSemantics { },
        ) {
            Text(
                user.name.take(1).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            user.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (row.isSelf) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        when (row.streaming) {
            StreamingKind.Camera -> StatusIcon(Icons.Outlined.Videocam, null, MaterialTheme.colorScheme.primary)

            StreamingKind.Screen -> StatusIcon(
                Icons.AutoMirrored.Outlined.ScreenShare,
                null,
                MaterialTheme.colorScheme.primary,
            )

            null -> Unit
        }
        if (user.prioritySpeaker) StatusIcon(Icons.Outlined.Campaign, null)
        if (user.recording) StatusIcon(Icons.Outlined.FiberManualRecord, null, MaterialTheme.colorScheme.error)
        when {
            user.deafened -> StatusIcon(Icons.Outlined.HeadsetOff, null, MaterialTheme.colorScheme.error)
            user.selfDeafened -> StatusIcon(Icons.Outlined.HeadsetOff, null)
            user.muted || user.suppressed -> StatusIcon(Icons.Outlined.MicOff, null, MaterialTheme.colorScheme.error)
            user.selfMuted -> StatusIcon(Icons.Outlined.MicOff, null)
            talk != TalkState.Passive -> StatusIcon(Icons.AutoMirrored.Outlined.VolumeUp, null, ring)
        }
    }
}

@Composable
private fun StatusIcon(
    icon: ImageVector,
    description: String?,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
}

/** Spoken status for TalkBack, since the icons alone are not announced. */
private fun userStatus(user: User, talk: TalkState): String = buildList {
    when (talk) {
        TalkState.Talking -> add("talking")
        TalkState.Shouting -> add("shouting")
        TalkState.Whispering -> add("whispering to you")
        TalkState.Passive -> Unit
    }
    if (user.muted) add("muted by server")
    if (user.deafened) add("deafened by server")
    if (user.suppressed) add("suppressed")
    if (user.selfMuted) add("muted")
    if (user.selfDeafened) add("deafened")
    if (user.prioritySpeaker) add("priority speaker")
    if (user.recording) add("recording")
}.joinToString(", ")
