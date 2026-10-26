package com.calegix.mumblevideo.ui.session

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ChatMessage
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.data.settings.ThemeMode
import com.calegix.mumblevideo.ui.theme.MumbleVideoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders welcome messages in the chat pane on the JVM, for docs/screenshots. Run on request only
 * (see app/build.gradle.kts): `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h915dp-xxhdpi", application = Application::class)
class MotdScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("motd/$name")!!.readText()

    private val lobby = MessageTarget.ToChannel(ChannelId(1))
    private val me = MessageTarget.ToUser(UserSession(7))

    private fun message(
        id: Long,
        sender: String?,
        html: String,
        target: MessageTarget = lobby,
        own: Boolean = false,
    ) = ChatEntry(
        ChatMessage(
            id = id,
            timestampEpochMillis = 1_761_436_800_000L + id * 60_000L,
            sender = if (sender == null) null else UserSession(id.toInt() + 10),
            senderName = sender ?: "Server",
            target = target,
            html = html,
            isOwn = own,
        ),
        targetLabel = if (target == me) "@nate" else "#Lobby",
    )

    private fun shot(name: String, state: ChatUiState, dark: Boolean) {
        compose.setContent {
            MumbleVideoTheme(
                themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light,
                trueBlack = dark,
                dynamicColor = false,
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ChatPane(state, draft = "", onDraftChange = {}, onSend = {}, onSelectTarget = {}, onSendImage = {})
                }
            }
        }
        // Parsing and picture decoding run on Dispatchers.Default; let them land.
        repeat(6) {
            Thread.sleep(150)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("../docs/screenshots/motd-${if (dark) "trueblack" else "light"}-$name.png")
    }

    private val calegix get() = ChatUiState(
        welcome = fixture("calegix-test.html"),
        currentChannelLabel = "Lobby",
        entries = listOf(
            message(1, "burg", "anyone up for movie night? I'll share my screen"),
            message(2, "nate", "in 10, grabbing snacks", own = true),
        ),
    )

    // skyn3t.lol has no welcome text; its moderator bot sends this card as a message from the server
    // the moment you connect, so it shows as a server message in the chat.
    private val skyn3t get() = ChatUiState(
        currentChannelLabel = "Lobby",
        entries = listOf(message(1, null, fixture("skyn3t.html"), target = me)),
    )

    // A classic hand-written welcome message: headings, a list, links, a <font color> chosen for a
    // white background (black, navy) that would vanish on true black, and an inline picture.
    private val classic get() = ChatUiState(
        currentChannelLabel = "Lobby",
        welcome = """
            <h2>Welcome to <font color="#000000">Example</font> Voice</h2>
            <p>Please read the rules before talking. <font color="navy">Questions?</font> Ask an <b>admin</b>.</p>
            <ol><li><b>Be kind</b>, no harassment</li><li>Push-to-talk in <i>Lobby</i></li><li>No music bots without asking</li></ol>
            <p>Website: <a href="https://example.org">example.org</a> &middot; Gaming server: <a href="mumble://games.example.org">games</a></p>
            <p align="center"><span style="background-color:#ffff00;color:#000000">&nbsp;Event Saturday 8 PM&nbsp;</span></p>
            <script>alert('never shown')</script>
        """.trimIndent(),
        entries = listOf(message(1, "alice", "hi all")),
    )

    @Test fun calegixLight() = shot("calegix", calegix, dark = false)

    @Test fun calegixTrueBlack() = shot("calegix", calegix, dark = true)

    @Test fun skyn3tLight() = shot("skyn3t", skyn3t, dark = false)

    @Test fun skyn3tTrueBlack() = shot("skyn3t", skyn3t, dark = true)

    @Test fun classicLight() = shot("classic", classic, dark = false)

    @Test fun classicTrueBlack() = shot("classic", classic, dark = true)
}
