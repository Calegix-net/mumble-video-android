package com.calegix.mumblevideo.service

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.VoiceInputMode
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.session.SessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The wired or Bluetooth headset button as push-to-talk (Settings › Audio › Headset button).
 *
 * Headset buttons report a click, not a hold (Bluetooth sends down and up together), so each press
 * toggles talking. The media session is active only while connected in push-to-talk mode with the
 * setting on, so it never steals the button from a music player otherwise.
 */
class HeadsetButton(
    context: Context,
    private val session: SessionController,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val media = MediaSession(context, "MumbleVideoPtt").apply {
        setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val event =
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                            ?: return false
                    if (event.keyCode !in BUTTONS) return false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggle()
                    return true
                }
            },
        )
        // A playing state is what routes media buttons to this session.
        setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
    }

    fun start() {
        scope.launch {
            combine(session.core.connectionState, session.core.voiceState, settings.settings) {
                    connection,
                    voice,
                    prefs,
                ->
                connection is ConnectionState.Connected && voice.inputMode == VoiceInputMode.PushToTalk &&
                    prefs.headsetButtonPtt
            }.distinctUntilChanged().collect { media.isActive = it }
        }
    }

    fun stop() {
        media.isActive = false
        media.release()
    }

    private fun toggle() {
        val core = session.core
        if (settings.settings.value.pttToggle) {
            core.setPushToTalk(true) // the core flips on press in toggle mode
        } else {
            core.setPushToTalk(!core.voiceState.value.pushToTalkActive)
        }
    }

    private companion object {
        val BUTTONS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
