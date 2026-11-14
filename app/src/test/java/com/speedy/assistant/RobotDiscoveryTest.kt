package com.speedy.assistant

import com.speedy.assistant.robot.RobotDiscovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class RobotDiscoveryTest {

    private val board = InetAddress.getByName("192.168.1.57")

    @Test
    fun parsesReply() {
        assertEquals("http://192.168.1.57", RobotDiscovery.parseReply("SPEEDY_ROBOT 80", board))
        assertEquals("http://192.168.1.57", RobotDiscovery.parseReply("SPEEDY_ROBOT", board))
        assertEquals("http://192.168.1.57:8080", RobotDiscovery.parseReply("SPEEDY_ROBOT 8080\n", board))
        assertNull(RobotDiscovery.parseReply("SPEEDY_DISCOVER", board))
        assertNull(RobotDiscovery.parseReply("hello", board))
    }

    /** Фейкова плата на loopback: відповідає на запит так само, як прошивка ESP32. */
    @Test
    fun findsFakeBoard() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { fake ->
            thread(isDaemon = true) {
                val buf = ByteArray(64)
                val request = DatagramPacket(buf, buf.size)
                fake.receive(request)
                if (String(request.data, 0, request.length) == RobotDiscovery.REQUEST) {
                    val reply = "SPEEDY_ROBOT 80".toByteArray()
                    fake.send(DatagramPacket(reply, reply.size, request.address, request.port))
                }
            }
            val url = RobotDiscovery(port = fake.localPort).discover(timeoutMs = 2000, targets = listOf(loopback))
            assertEquals("http://${loopback.hostAddress}", url)
        }
    }

    @Test
    fun returnsNullWhenNobodyAnswers() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        val silentPort = DatagramSocket(0, loopback).use { it.localPort }
        assertNull(RobotDiscovery(port = silentPort).discover(timeoutMs = 800, targets = listOf(loopback)))
    }
}
