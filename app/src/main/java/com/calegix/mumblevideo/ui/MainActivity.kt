package com.calegix.mumblevideo.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.ui.navigation.AppNavHost
import com.calegix.mumblevideo.ui.theme.MumbleVideoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { AppViewModelProvider.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)

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
