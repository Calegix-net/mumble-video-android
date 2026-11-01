package com.calegix.mumblevideo.ui.session

import android.app.Application
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Native graphics for real text layout: link hit-testing depends on where glyphs land.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class LinkTextTest {
    @get:Rule val compose = createComposeRule()

    private val copied = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    /**
     * [selectable] wraps the text in a SelectionContainer as the chat does. Off where a long press
     * would really start a selection: Robolectric's Magnifier crashes when it is dismissed.
     */
    private fun show(html: String, selectable: Boolean = true) {
        compose.setContent {
            val uris = object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
            CompositionLocalProvider(LocalUriHandler provides uris) {
                val text = @Composable {
                    LinkText(
                        AnnotatedString.fromHtml(html),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black,
                        modifier = Modifier.testTag("text"),
                        onCopyLink = { copied += it },
                    )
                }
                if (selectable) SelectionContainer { text() } else text()
            }
        }
    }

    @Test
    fun `long press on a link copies its href, not the shown text, and doesn't open it`() {
        show("""<a href="https://example.org/a/long/path?x=1">example.org</a>""")
        compose.onNodeWithTag("text").performTouchInput { longClick(center) }
        compose.waitForIdle()
        assertEquals(listOf("https://example.org/a/long/path?x=1"), copied)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `tap on a link still opens it`() {
        show("""<a href="https://example.org/">example.org</a>""")
        compose.onNodeWithTag("text").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf("https://example.org/"), opened)
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `mumble links copy too`() {
        show("""<a href="mumble://skyn3t.lol:64738/Lobby?version=1.2.0">join us</a>""")
        compose.onNodeWithTag("text").performTouchInput { longClick(center) }
        compose.waitForIdle()
        assertEquals(listOf("mumble://skyn3t.lol:64738/Lobby?version=1.2.0"), copied)
    }

    @Test
    fun `long press on plain text beside a link copies nothing`() {
        show("""plain words first, then <a href="https://example.org/">a link</a>""", selectable = false)
        compose.onNodeWithTag("text").performTouchInput { longClick(centerLeft.copy(x = 4f)) }
        compose.waitForIdle()
        assertTrue(copied.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `text without links is left alone`() {
        show("just words", selectable = false)
        compose.onNodeWithTag("text").performTouchInput { longClick(center) }
        compose.waitForIdle()
        assertNull(copied.firstOrNull())
    }
}
