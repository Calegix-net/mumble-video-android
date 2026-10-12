package com.calegix.mumblevideo.client.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrientationTrackerTest {
    @Test
    fun `a new quarter turn counts only once clearly reached`() {
        assertEquals(90, OrientationTracker.quantise(95, current = 0))
        assertNull(OrientationTracker.quantise(55, current = 0)) // nearer 90, but not clearly
        assertEquals(0, OrientationTracker.quantise(40, current = 0)) // still "upright"
        assertEquals(0, OrientationTracker.quantise(350, current = 270))
        assertEquals(270, OrientationTracker.quantise(250, current = 0))
    }
}
