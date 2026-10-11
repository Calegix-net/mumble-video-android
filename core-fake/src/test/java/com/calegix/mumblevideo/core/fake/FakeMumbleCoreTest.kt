package com.calegix.mumblevideo.core.fake

import com.calegix.mumblevideo.core.ConnectRequest
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.DisconnectReason
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.RejectReason
import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.core.VoiceInputMode
import kotlin.random.Random
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeMumbleCoreTest {
    private fun TestScope.core() = FakeMumbleCore(backgroundScope, Random(1), simulateActivity = false, clock = { 0L })

    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    private fun request(host: String, trusted: Set<String> = emptySet(), autoReconnect: Boolean = true) =
        ConnectRequest(ServerAddress(host), "tester", trustedCertificateSha256 = trusted, autoReconnect = autoReconnect)

    @Test
    fun `first connect asks for trust, then syncs`() = runTest {
        val core = core()
        core.connect(request("a.example"))
        settle()
        val prompt = core.connectionState.value as ConnectionState.CertificateTrustRequired
        assertEquals(64, prompt.certificate.sha256.length)

        core.respondToCertificate(true)
        settle()
        assertTrue(core.connectionState.value is ConnectionState.Connected)
        assertEquals("tester", core.serverState.value.selfUser?.name)
        assertTrue(core.capabilities.value.video)

        // Trusting up front skips the prompt.
        core.connect(request("a.example", trusted = setOf(prompt.certificate.sha256)))
        settle()
        assertTrue(core.connectionState.value is ConnectionState.Connected)
    }

    @Test
    fun `rejecting the certificate disconnects`() = runTest {
        val core = core()
        core.connect(request("a.example"))
        settle()
        core.respondToCertificate(false)
        settle()
        assertEquals(ConnectionState.Disconnected(DisconnectReason.CertificateRejected), core.connectionState.value)
    }

    @Test
    fun `scripted failures`() = runTest {
        val core = core()
        core.connect(request("full.example"))
        settle()
        core.respondToCertificate(true)
        settle()
        assertEquals(RejectReason.ServerFull, (core.connectionState.value as ConnectionState.Rejected).reason)

        core.connect(request("unreachable.example"))
        settle()
        val state = core.connectionState.value as ConnectionState.Disconnected
        assertTrue(state.reason is DisconnectReason.Unreachable)
    }

    @Test
    fun `flaky servers drop, retry, and come back`() = runTest {
        val core = core()
        core.connect(request("flaky.example"))
        advanceTimeBy(2_000)
        core.respondToCertificate(true)
        advanceTimeBy(2_000)
        assertTrue(core.connectionState.value is ConnectionState.Connected)

        advanceTimeBy(20_500)
        val reconnecting = core.connectionState.value as ConnectionState.Reconnecting
        assertEquals(1, reconnecting.attempt)
        advanceTimeBy(3_000)
        assertEquals(2, (core.connectionState.value as ConnectionState.Reconnecting).attempt)
        advanceTimeBy(3_500)
        assertTrue(core.connectionState.value is ConnectionState.Connected)
        core.disconnect()
    }

    @Test
    fun `commands need a connection`() = runTest {
        val core = core()
        try {
            core.joinChannel(com.calegix.mumblevideo.core.ChannelId.ROOT)
            fail("expected NotConnected")
        } catch (e: MumbleCoreException) {
            assertEquals(MumbleCoreException.Kind.NotConnected, e.kind)
        }
    }

    @Test
    fun `push-to-talk toggles when configured to`() = runTest {
        val core = core()
        core.setAudioConfig(
            com.calegix.mumblevideo.core.AudioConfig(inputMode = VoiceInputMode.PushToTalk, pushToTalkToggles = true),
        )
        core.setPushToTalk(true)
        core.setPushToTalk(false)
        assertTrue(core.voiceState.value.pushToTalkActive)
        core.setPushToTalk(true)
        assertFalse(core.voiceState.value.pushToTalkActive)
    }

    @Test
    fun `generated certificates can be inspected`() = runTest {
        val core = core()
        val cert = core.generateCertificate("Nate")
        assertEquals("Nate", core.inspectCertificate(cert.pkcs12).commonName)
    }
}
