package com.calegix.mumblevideo.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.calegix.mumblevideo.BuildConfig
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.ui.navigation.AppNavHost
import com.calegix.mumblevideo.ui.session.ChatImages
import com.calegix.mumblevideo.ui.theme.MumbleVideoTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_DEBUG_POST = "com.calegix.mumblevideo.action.DEBUG_POST"
    }

    private val viewModel: MainViewModel by viewModels { AppViewModelProvider.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        // Only a fresh launch carries a new link: a restored activity, or one reopened from
        // recents, still holds the old intent and must not reopen the add-server form.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val connection by viewModel.connectionState.collectAsStateWithLifecycle()
            val dialog by viewModel.dialog.collectAsStateWithLifecycle()
            val link by viewModel.pendingLink.collectAsStateWithLifecycle()

            val keepOn = settings.keepScreenOn && connection is ConnectionState.Connected
            LaunchedEffect(keepOn) {
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            MumbleVideoTheme(
                themeMode = settings.themeMode,
                trueBlack = settings.trueBlack,
                dynamicColor = settings.dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(
                        connectionState = connection,
                        pendingLink = link,
                        onConsumeLink = viewModel::consumeLink,
                    )
                    dialog?.let {
                        ConnectionDialogHost(
                            dialog = it,
                            onAnswerCertificate = viewModel::answerCertificate,
                            onDismiss = viewModel::dismissDialog,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let(viewModel::onMumbleLink)
        if (BuildConfig.DEBUG && intent?.action == ACTION_DEBUG_POST) debugPost(intent)
    }

    /**
     * Debug builds only: posts a note, and optionally a screenshot of this window, to the current
     * channel. For progress reports driven over adb:
     *
     *     adb shell am start -n com.calegix.mumblevideo.debug/com.calegix.mumblevideo.ui.MainActivity \
     *         -a com.calegix.mumblevideo.action.DEBUG_POST --es text "..." --ez screenshot true
     */
    private fun debugPost(intent: Intent) {
        val text = intent.getStringExtra("text")
        val screenshot = intent.getBooleanExtra("screenshot", false)
        lifecycleScope.launch {
            delay(1_000) // let the window settle after coming to the front
            val image = if (screenshot) {
                captureWindow()?.let { bitmap ->
                    withContext(Dispatchers.Default) { ChatImages.toMessage(bitmap, viewModel.imageLimit()) }
                }
            } else {
                null
            }
            viewModel.post(text, image)
        }
    }

    /** The window as the user sees it, video surfaces included (PixelCopy reads the composited frame). */
    private suspend fun captureWindow(): Bitmap? = suspendCancellableCoroutine { cont ->
        val view = window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
        }, Handler(Looper.getMainLooper()))
    }

    // Hardware push-to-talk key (Settings > Audio > Push-to-talk key). Auto-repeat of a held key
    // is swallowed too, so holding it doesn't leak repeats to whatever else listens for it.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && viewModel.isPttKey(keyCode)) return true
        return viewModel.onKey(keyCode, down = true) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        viewModel.onKey(keyCode, down = false) || super.onKeyUp(keyCode, event)
}
