package com.speedy.assistant.night

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV (PCM 16 біт, моно): заголовок із розмірами дописується при закритті. VLC грає без кодеків. */
class WavWriter(file: File, private val rate: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(HEADER)) }
    private var bytes = 0L
    private val buf = ByteBuffer.allocate(16_384).order(ByteOrder.LITTLE_ENDIAN)

    /** Секунд записано. */
    val seconds: Double get() = bytes / 2.0 / rate

    fun write(samples: ShortArray, from: Int = 0, count: Int = samples.size - from) {
        var i = from
        val end = from + count
        while (i < end) {
            buf.clear()
            while (i < end && buf.remaining() >= 2) buf.putShort(samples[i++])
            out.write(buf.array(), 0, buf.position())
            bytes += buf.position()
        }
    }

    override fun close() {
        val h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(bytes.toInt())
        out.seek(0)
        out.write(h.array())
        out.close()
    }

    private companion object {
        const val HEADER = 44
    }
}
