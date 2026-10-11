package com.calegix.mumblevideo.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object ServersRoute

@Serializable
data object PublicServersRoute

@Serializable
data object SessionRoute

@Serializable
data class FullscreenVideoRoute(val session: Int, val streamId: Int)

/** [section] opens straight into one section, by [com.calegix.mumblevideo.ui.settings.SettingsSection] name. */
@Serializable
data class SettingsRoute(val section: String? = null)
