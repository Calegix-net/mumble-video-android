package com.calegix.mumblevideo.ui

import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.core.VoiceInputMode
import com.calegix.mumblevideo.data.settings.ThemeMode
import com.calegix.mumblevideo.keepCollecting
import com.calegix.mumblevideo.settle
import com.calegix.mumblevideo.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `updates are persisted and audio settings reach the core`() = runTest {
        val graph = TestGraph(this)
        val vm = SettingsViewModel(graph.settings, graph.core, graph.trust)

        vm.update { it.copy(themeMode = ThemeMode.Dark, trueBlack = true, inputMode = VoiceInputMode.Continuous) }
        settle()

        assertEquals(ThemeMode.Dark, graph.settings.settings.value.themeMode)
        assertTrue(graph.settings.settings.value.trueBlack)
        assertEquals(VoiceInputMode.Continuous, graph.core.voiceState.value.inputMode)
    }

    @Test
    fun `trusted certificate count and clearing`() = runTest {
        val graph = TestGraph(this)
        val vm = SettingsViewModel(graph.settings, graph.core, graph.trust)
        keepCollecting(vm.trustedCertificateCount)
        graph.trust.trust("ab", ServerAddress("x"), 0)
        settle()
        assertEquals(1, vm.trustedCertificateCount.value)

        vm.clearTrustedCertificates()
        settle()
        assertEquals(0, vm.trustedCertificateCount.value)
    }

    @Test
    fun `input level meter compares against the threshold`() = runTest {
        val graph = TestGraph(this)
        val vm = SettingsViewModel(graph.settings, graph.core, graph.trust)
        vm.update { it.copy(vadThreshold = 0f) }
        settle()
        assertTrue(vm.inputLevels.first().aboveThreshold)
    }
}
