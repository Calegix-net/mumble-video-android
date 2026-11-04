package com.calegix.mumblevideo.session

import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.data.settings.CarMicrophone
import com.calegix.mumblevideo.settle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CarProjectionTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `Android Auto connecting and leaving reaches the core's audio config`() = runTest {
        val car = MutableStateFlow(false)
        val graph = TestGraph(this, carProjection = car)
        with(graph) { connect() }
        assertFalse(graph.core.audioConfig.carProjection)

        car.value = true
        settle()
        assertTrue(graph.core.audioConfig.carProjection)

        car.value = false
        settle()
        assertFalse(graph.core.audioConfig.carProjection)
    }

    @Test
    fun `connecting while already in the car starts out as media`() = runTest {
        val graph = TestGraph(this, carProjection = MutableStateFlow(true))
        settle()
        with(graph) { connect() }
        assertTrue(graph.core.audioConfig.carProjection)
    }

    @Test
    fun `the car microphone choice decides call or media`() = runTest {
        val graph = TestGraph(this)
        with(graph) { connect() }
        assertFalse("car's mic by default", graph.core.audioConfig.carAsMedia)

        graph.settings.update { it.copy(carMicrophone = CarMicrophone.Phone) }
        settle()
        assertTrue(graph.core.audioConfig.carAsMedia)
    }
}
