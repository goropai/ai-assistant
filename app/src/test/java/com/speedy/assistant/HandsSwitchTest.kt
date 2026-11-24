package com.speedy.assistant

import com.speedy.assistant.phone.SpeedyHands
import org.junit.Assert.assertEquals
import org.junit.Test

class HandsSwitchTest {
    private val ours = "com.speedy.assistant/com.speedy.assistant.phone.SpeedyHands"

    @Test
    fun withService() {
        assertEquals(ours, SpeedyHands.withService("", ours))
        // Чужі служби лишаються, наша — один раз.
        assertEquals("com.other/.Svc:$ours", SpeedyHands.withService("com.other/.Svc", ours))
        assertEquals("com.other/.Svc:$ours", SpeedyHands.withService("$ours:com.other/.Svc", ours))
    }
}
