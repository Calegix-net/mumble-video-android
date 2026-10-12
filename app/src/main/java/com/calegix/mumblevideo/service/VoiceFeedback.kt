package com.calegix.mumblevideo.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import androidx.core.text.HtmlCompat
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.session.SessionController
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Audible feedback that is not voice: a short tone when push-to-talk starts and stops
 * (Settings › Audio › Push-to-talk sound) and incoming chat read aloud (Settings › General).
 */
class VoiceFeedback(
    private val context: Context,
    private val session: SessionController,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val tones by lazy { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60) }

    fun start() {
        scope.launch {
            session.core.voiceState.map { it.pushToTalkActive }.distinctUntilChanged().drop(1).collect { active ->
                if (settings.settings.value.pttSound) {
                    tones.startTone(if (active) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 80)
                }
            }
        }
        scope.launch {
            session.core.messages.collect { message ->
                val prefs = settings.settings.value
                if (message.isOwn || !prefs.textToSpeech) return@collect
                val text = HtmlCompat.fromHtml(message.html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                    .replace('￼', ' ') // images
                    .trim()
                if (text.isEmpty()) return@collect
                val spoken = when {
                    prefs.shortTextToSpeech -> text
                    message.target is MessageTarget.ToUser -> "${message.senderName}, privately: $text"
                    else -> "${message.senderName} says: $text"
                }
                speak(spoken)
            }
        }
    }

    private val pending = ArrayList<String>()

    /** Speaks [text]; the engine starts lazily, and what arrives before it is ready is queued. */
    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
            return
        }
        pending += text
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    pending.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, it.hashCode().toString()) }
                }
                pending.clear()
            }
        }
    }
}
