package com.calegix.mumblevideo.car

import android.app.Application
import android.os.Looper
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.settle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CarScreensTest {
    @get:Rule val main = MainDispatcherRule()

    private val car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())

    private fun TestScope.show(graph: TestGraph): Pair<CarHomeScreen, ListTemplate> {
        val screen = CarHomeScreen(car, graph.session, graph.servers)
        ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        settle()
        return screen to screen.onGetTemplate() as ListTemplate
    }

    private fun ListTemplate.rowTitles() = singleList!!.items.map { (it as Row).title.toString() }

    private fun TestScope.click(action: Action) {
        action.onClickDelegate!!.sendClick(object : OnDoneCallback {})
        shadowOf(Looper.getMainLooper()).idle()
        settle()
    }

    @Test
    fun `disconnected, the car lists saved servers`() = runTest {
        val graph = TestGraph(this)
        graph.servers.save(TestGraph.SERVER)
        val (_, template) = show(graph)
        assertEquals("Mumble Video", template.title.toString())
        assertEquals(listOf("Test"), template.rowTitles())
    }

    @Test
    fun `connected, the car shows the channel, you, and mute and deafen`() = runTest {
        val graph = TestGraph(this)
        graph.servers.save(TestGraph.SERVER)
        with(graph) { connect() }
        val (screen, template) = show(graph)

        assertTrue(template.title.toString(), template.title.toString().endsWith(" · Test"))
        val rows = template.rowTitles()
        assertEquals("Change channel", rows.first())
        assertEquals("nate (you)", rows[1])
        assertEquals("Disconnect", rows.last())
        val strip = template.actionStrip!!.actions
        assertEquals(2, strip.size)
        assertNotNull("mute is an icon button", strip[0].icon)
        assertEquals("Deafen", strip[1].title.toString())

        click(strip[0])
        assertTrue(graph.core.voiceState.value.selfMuted)
        click(strip[1])
        assertTrue(graph.core.voiceState.value.selfDeafened)
        val after = screen.onGetTemplate() as ListTemplate
        assertEquals("Undeafen", after.actionStrip!!.actions[1].title.toString())
    }
}
