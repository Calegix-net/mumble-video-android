package com.calegix.mumblevideo.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/** Settings sections, mirroring Mumla's grouping plus Video, Notifications and Advanced. */
enum class SettingsSection(val title: String, val summary: String, val icon: ImageVector) {
    General("General", "Screen, text-to-speech, chat images", Icons.Outlined.Settings),
    Authentication("Authentication", "Certificates, default username", Icons.Outlined.Shield),
    Audio("Audio", "Input method, push-to-talk, quality", Icons.AutoMirrored.Outlined.VolumeUp),
    Appearance("Appearance", "Theme, true black, channel list", Icons.Outlined.Palette),
    Video("Video", "Camera, resolution, codec, data saver", Icons.Outlined.Videocam),
    Notifications("Notifications", "Messages, streams, watchers", Icons.Outlined.Notifications),
    Advanced("Advanced", "Connection, reconnecting, developer", Icons.Outlined.SettingsSuggest),
    About("About", "Version, licences", Icons.Outlined.Info),
}

/**
 * Settings as list and detail: side by side on expanded widths, one at a time on phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialSection: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    var selected by rememberSaveable {
        mutableStateOf(
            initialSection?.let { name ->
                SettingsSection.entries.firstOrNull { it.name.equals(name, true) }
            },
        )
    }
    val shown = selected ?: if (wide) SettingsSection.General else null

    BackHandler(enabled = !wide && selected != null) { selected = null }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (!wide && shown != null) shown.title else "Settings") },
                navigationIcon = {
                    IconButton(onClick = { if (!wide && selected != null) selected = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (wide) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                SectionList(selected = shown, onSelect = {
                    selected = it
                }, modifier = Modifier.width(360.dp).fillMaxHeight())
                VerticalDivider()
                SectionContent(shown ?: SettingsSection.General, Modifier.weight(1f))
            }
        } else if (shown == null) {
            SectionList(selected = null, onSelect = {
                selected = it
            }, modifier = Modifier.padding(padding).fillMaxSize())
        } else {
            SectionContent(shown, Modifier.padding(padding).fillMaxSize())
        }
    }
}

@Composable
private fun SectionList(
    selected: SettingsSection?,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        items(SettingsSection.entries) { section ->
            val isSelected = section == selected
            ListItem(
                headlineContent = { Text(section.title) },
                supportingContent = { Text(section.summary) },
                leadingContent = { Icon(section.icon, contentDescription = null) },
                colors = ListItemDefaults.colors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.background
                    },
                ),
                modifier = Modifier
                    .clickable { onSelect(section) }
                    .semantics { this.selected = isSelected },
            )
        }
    }
}

@Composable
private fun SectionContent(section: SettingsSection, modifier: Modifier = Modifier) {
    when (section) {
        SettingsSection.General -> GeneralSettings(modifier)
        SettingsSection.Authentication -> AuthenticationSettings(modifier)
        SettingsSection.Audio -> AudioSettings(modifier)
        SettingsSection.Appearance -> AppearanceSettings(modifier)
        SettingsSection.Video -> VideoSettings(modifier)
        SettingsSection.Notifications -> NotificationSettings(modifier)
        SettingsSection.Advanced -> AdvancedSettings(modifier)
        SettingsSection.About -> AboutSettings(modifier)
    }
}
