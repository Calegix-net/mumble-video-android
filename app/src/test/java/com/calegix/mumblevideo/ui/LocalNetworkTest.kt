package com.calegix.mumblevideo.ui

import com.calegix.mumblevideo.ui.servers.LocalNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun `private, tether and mDNS addresses are local`() {
        listOf(
            "10.120.215.148",
            "192.168.1.5",
            "172.20.0.1",
            "169.254.3.4",
            "nas.local",
            "server",
            "box.home.arpa",
            "[fd00::1]",
        )
            .forEach { assertTrue(it, LocalNetwork.isLocal(it)) }
    }

    @Test
    fun `public names and addresses are not`() {
        listOf("mumble.example.org", "8.8.8.8", "172.32.0.1", "2001:db8::1").forEach {
            assertFalse(it, LocalNetwork.isLocal(it))
        }
    }
}
