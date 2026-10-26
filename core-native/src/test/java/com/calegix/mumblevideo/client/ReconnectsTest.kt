package com.calegix.mumblevideo.client

import com.calegix.mumblevideo.client.NativeMumbleCore.Outcome
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.RejectReason
import com.calegix.mumblevideo.core.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectsTest {
    private val server = ServerAddress("mumble.example.org", 64738)

    private fun rejected(reason: RejectReason) =
        Outcome.Final(ConnectionState.Rejected(server, reason, "Username already in use"))

    @Test
    fun `backoff doubles from 2 s and stops at 30 s`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map(Reconnects::backoffMillis))
    }

    @Test
    fun `username in use is waited out while reconnecting, and only then`() {
        val inUse = rejected(RejectReason.UsernameInUse)
        val retried = Reconnects.retriable(inUse, reconnecting = true)
        assertTrue(retried is Outcome.Dropped)
        assertFalse((retried as Outcome.Dropped).wasConnected)
        assertSame("a first connect says so at once", inUse, Reconnects.retriable(inUse, reconnecting = false))
        val wrongPassword = rejected(RejectReason.WrongUserPassword)
        assertSame(wrongPassword, Reconnects.retriable(wrongPassword, reconnecting = true))
    }
}
