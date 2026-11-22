package com.speedy.assistant

import com.speedy.assistant.home.TuyaBleProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuyaBleTest {
    @Test
    fun crcAndVarints() {
        assertEquals(0x4B37, TuyaBleProtocol.crc16("123456789".toByteArray())) // CRC-16/MODBUS
        assertArrayEquals(byteArrayOf(0xC8.toByte(), 0x01), TuyaBleProtocol.packInt(200))
        assertArrayEquals(byteArrayOf(5), TuyaBleProtocol.packInt(5))
        assertEquals(200 to 2, TuyaBleProtocol.unpackInt(byteArrayOf(0xC8.toByte(), 0x01), 0))
    }

    @Test
    fun packetsRoundTrip() {
        val key = TuyaBleProtocol.md5("abcdef".toByteArray())
        val iv = ByteArray(16) { it.toByte() }
        val data = TuyaBleProtocol.pairRequest("tuyabc1234567890", "abcdef".toByteArray(), "bf0123456789abcdef")
        assertEquals(44, data.size)
        val packets = TuyaBleProtocol.buildPackets(7, TuyaBleProtocol.PAIR, data, 0, TuyaBleProtocol.FLAG_SESSION, key, iv, 3)
        assertTrue(packets.all { it.size <= TuyaBleProtocol.MTU })
        assertEquals(0x30, packets[0][2].toInt()) // версія протоколу << 4 (після номера й довжини)
        val r = TuyaBleProtocol.Reassembler()
        val full = packets.mapNotNull { r.feed(it) }.single()
        val msg = TuyaBleProtocol.decrypt(full) { if (it == TuyaBleProtocol.FLAG_SESSION) key else null }!!
        assertEquals(7, msg.seq)
        assertEquals(TuyaBleProtocol.PAIR, msg.code)
        assertArrayEquals(data, msg.data)
        // Не той ключ — CRC не зійдеться.
        assertNull(TuyaBleProtocol.decrypt(full) { TuyaBleProtocol.md5("zzzzzz".toByteArray()) })
    }

    @Test
    fun datapointsV3AndV4() {
        // dp1 температура 211 (21.1 °C), dp2 вологість 49, dp4 батарея (enum) 2.
        val v3 = byteArrayOf(1, 2, 4, 0, 0, 0, 0xD3.toByte(), 2, 2, 4, 0, 0, 0, 49, 4, 4, 1, 2)
        assertEquals(mapOf(1 to 211, 2 to 49, 4 to 2), TuyaBleProtocol.datapoints(TuyaBleProtocol.RECEIVE_DP, v3))
        // Мінусова температура.
        val cold = byteArrayOf(1, 2, 4, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xCE.toByte())
        assertEquals(mapOf(1 to -50), TuyaBleProtocol.datapoints(TuyaBleProtocol.RECEIVE_DP, cold))
        // Підписані: 2 байти номера, прапорці, далі як звичайні (1 байт довжини).
        val signed = byteArrayOf(0, 1, 0, 1, 2, 4, 0, 0, 0, 0xD3.toByte())
        assertEquals(mapOf(1 to 211), TuyaBleProtocol.datapoints(TuyaBleProtocol.RECEIVE_SIGN_DP, signed))
        // З часом: байт типу (0 — 13 цифр мс, 1 — 4 байти секунд).
        val timed = byteArrayOf(0) + "1763760000000".toByteArray() + byteArrayOf(2, 2, 4, 0, 0, 0, 49)
        assertEquals(mapOf(2 to 49), TuyaBleProtocol.datapoints(TuyaBleProtocol.RECEIVE_TIME_DP, timed))
        val timedSec = byteArrayOf(1, 0x69, 0x20, 0, 0, 2, 2, 4, 0, 0, 0, 50)
        assertEquals(mapOf(2 to 50), TuyaBleProtocol.datapoints(TuyaBleProtocol.RECEIVE_TIME_DP, timedSec))
        // V4 (0x8006): 4 байти заголовка, далі id, 3 байти прапорців, тип, 2 байти довжини.
        val v4 = byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 4, 0, 0, 0, 0xD3.toByte())
        assertEquals(mapOf(1 to 211), TuyaBleProtocol.datapoints(TuyaBleProtocol.RECEIVE_DP_V4, v4))
    }

    @Test
    fun datapointsFd50V4() {
        // Справжній кадр датчика TH05: 26.6 °C, вологість 41, батарея 12, шкала °C (enum 0), dp23 і dp24 — 0.
        val hex = "00000012118000010200040000010a0202000400000029040200040000000c090400010017020004000000001802000400000000"
        val frame = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(
            mapOf(1 to 266, 2 to 41, 4 to 12, 9 to 0, 23 to 0, 24 to 0),
            TuyaBleProtocol.datapoints(TuyaBleProtocol.RECEIVE_DP_V4, frame, fd50 = true),
        )
        // Кадр, що не розбирається повністю за FD50, — старим форматом.
        val legacy = byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 4, 0, 0, 0, 0xD3.toByte(), 7)
        assertEquals(mapOf(1 to 211), TuyaBleProtocol.datapoints(TuyaBleProtocol.RECEIVE_DP_V4, legacy, fd50 = true))
    }

    @Test
    fun answersDevice() {
        // Запит часу: мс текстом + пояс у сотих години (Київ узимку +2 → 200).
        val time1 = TuyaBleProtocol.responseFor(TuyaBleProtocol.TIME1_REQ, ByteArray(0), 1763760000000, 120)!!
        assertEquals("1763760000000", String(time1.copyOf(13)))
        assertArrayEquals(byteArrayOf(0, 200.toByte()), time1.copyOfRange(13, 15))
        assertEquals(0, TuyaBleProtocol.responseFor(TuyaBleProtocol.RECEIVE_DP, ByteArray(3), 0, 0)!!.size)
        assertArrayEquals(byteArrayOf(0, 7, 1, 0), TuyaBleProtocol.responseFor(TuyaBleProtocol.RECEIVE_SIGN_DP, byteArrayOf(0, 7, 1, 9), 0, 0))
        assertNull(TuyaBleProtocol.responseFor(TuyaBleProtocol.PAIR, ByteArray(1), 0, 0))
        assertArrayEquals(byteArrayOf(0x00, 0xF3.toByte()), TuyaBleProtocol.FD50_DEVICE_INFO)
    }

    @Test
    fun fd50HelloIsOneWrite() {
        val key = TuyaBleProtocol.md5("abcdef".toByteArray())
        val packets = TuyaBleProtocol.buildPackets(1, TuyaBleProtocol.DEVICE_INFO, TuyaBleProtocol.FD50_DEVICE_INFO, 0,
            TuyaBleProtocol.FLAG_LOGIN, key, ByteArray(16), 2, chunk = TuyaBleProtocol.FD50_CHUNK)
        assertEquals(1, packets.size)
        assertEquals(0x20, packets[0][2].toInt())
    }
}
