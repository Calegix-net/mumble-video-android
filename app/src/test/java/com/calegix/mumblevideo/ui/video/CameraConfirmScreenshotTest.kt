package com.calegix.mumblevideo.ui.video

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calegix.mumblevideo.core.CameraFacing
import com.calegix.mumblevideo.core.CameraOption
import com.calegix.mumblevideo.core.LocalStream
import com.calegix.mumblevideo.core.LocalVideoState
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VideoCodec
import com.calegix.mumblevideo.data.settings.ThemeMode
import com.calegix.mumblevideo.ui.theme.MumbleVideoTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The camera start question and the live-camera label, for docs/screenshots. Run on request only
 * (see app/build.gradle.kts): `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h915dp-xxhdpi", application = Application::class)
class CameraConfirmScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val front = CameraOption("1", CameraFacing.Front, "Front camera", listOf(1280 to 720), 30)
    private val rear = CameraOption("0", CameraFacing.Back, "Back camera", listOf(1920 to 1080), 30)

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            MumbleVideoTheme(themeMode = ThemeMode.Dark, trueBlack = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun ask(camera: CameraOption, name: String) {
        themed { CameraConfirmDialog(CameraConfirm(camera, 2), onStart = {}, onSwitch = {}, onDismiss = {}) }
        captureScreenRoboImage("../docs/screenshots/camera-confirm-$name.png")
    }

    @Test fun askFront() = ask(front, "front")

    @Test fun askRear() = ask(rear, "rear")

    @Test
    fun liveRear() {
        val stream = LocalStream(StreamKey(UserSession(7), 1), 1280, 720, 30, VideoCodec.H264)
        themed {
            Box(Modifier.padding(12.dp)) {
                OwnVideoControls(
                    VideoUiState(
                        supported = true,
                        local = LocalVideoState(camera = stream),
                        cameraFacing = CameraFacing.Back,
                    ),
                    onToggleCamera = {},
                    onSwitchCamera = {},
                    onStopScreen = {},
                    onScreenConsent = { _, _ -> },
                    onChooseShareAudio = {},
                )
            }
        }
        captureScreenRoboImage("../docs/screenshots/camera-live-rear.png")
    }
}
