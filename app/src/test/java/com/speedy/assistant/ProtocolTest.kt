package com.speedy.assistant

import com.speedy.assistant.brain.AgentReply
import com.speedy.assistant.face.Mood
import com.speedy.assistant.robot.RobotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun parsesNavigationStep() {
        val reply = AgentReply.parse(
            """{"say":"Ще трохи","mood":"thinking","action":{"type":"move","camera":"none",
               "direction":"forward","duration_ms":600,"then_look":true}}"""
        )
        assertEquals("Ще трохи", reply.say)
        assertEquals(Mood.THINKING, reply.mood)
        assertEquals("move", reply.action.type)
        assertEquals("forward", reply.action.direction)
        assertEquals(600L, reply.action.durationMs)
        assertTrue(reply.action.thenLook)
    }

    @Test
    fun mapsOldCameraNames() {
        assertEquals("forward", AgentReply.parse("""{"action":{"type":"look","camera":"front"}}""").action.camera)
        assertEquals("backward", AgentReply.parse("""{"action":{"type":"look","camera":"back"}}""").action.camera)
        assertFalse(AgentReply.parse("""{"say":"x","action":{"type":"none"}}""").action.thenLook)
    }

    @Test
    fun nonJsonBecomesSpeech() {
        val reply = AgentReply.parse("Привіт!")
        assertEquals("Привіт!", reply.say)
        assertEquals("none", reply.action.type)
    }

    @Test
    fun parsesRobotStatus() {
        val s = RobotStatus.parse("""{"ok":true,"distance_cm":23,"moving":false,"last":"obstacle"}""")!!
        assertEquals(23, s.distanceCm)
        assertEquals("obstacle", s.last)
        assertFalse(s.moving)
        // -1 = датчика немає або попереду вільно
        assertNull(RobotStatus.parse("""{"ok":true,"distance_cm":-1,"moving":true,"last":"forward"}""")!!.distanceCm)
        assertNull(RobotStatus.parse("not json"))
    }
}
