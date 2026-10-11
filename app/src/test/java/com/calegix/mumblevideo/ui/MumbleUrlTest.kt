package com.calegix.mumblevideo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MumbleUrlTest {
    @Test
    fun `parses host, port, user and password`() {
        val server = MumbleUrl.parse("mumble://nate:secret@voice.example.org:1234/Lobby")!!
        assertEquals("voice.example.org", server.host)
        assertEquals(1234, server.port)
        assertEquals("nate", server.username)
        assertEquals("secret", server.password)
    }

    @Test
    fun `defaults the port and rejects other schemes`() {
        assertEquals(64738, MumbleUrl.parse("mumble://voice.example.org")!!.port)
        assertNull(MumbleUrl.parse("https://voice.example.org"))
        assertNull(MumbleUrl.parse("not a url"))
    }
}
