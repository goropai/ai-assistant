package com.speedy.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Короткі "робо-звуки" замість слів: Спіді подає голос лише тоді, коли є що сказати —
 * стан (розряд, немає мережі, сумно) чи бажання (хоче уваги), або у відповідь на звертання.
 */
enum class Chirp(vararg val notes: Note) {
    /** Почула звертання — слухаю. */
    ACK(Note(900f, 1400f, 70)),
    HAPPY(Note(700f, 1100f, 80), Note(1100f, 1600f, 90)),
    SAD(Note(700f, 380f, 380)),
    /** Хоче на зарядку: повільне "в'янення". */
    LOW_BATTERY(Note(880f, 880f, 120), Note(0f, 0f, 80), Note(660f, 660f, 120), Note(0f, 0f, 80), Note(440f, 330f, 260)),
    /** Пропала мережа. */
    NO_NETWORK(Note(420f, 420f, 90), Note(0f, 0f, 70), Note(420f, 420f, 90), Note(0f, 0f, 70), Note(420f, 300f, 160)),
    /** Нудно, хоче, щоб з нею погралися. */
    ATTENTION(Note(1000f, 1500f, 60), Note(1500f, 1000f, 60), Note(1000f, 1700f, 90), Note(0f, 0f, 120), Note(1200f, 1800f, 80)),
    /** Щось цікаве (котик, нове обличчя). */
    CURIOUS(Note(600f, 1300f, 160), Note(1300f, 1100f, 70)),
}

/** Тон із плавною зміною частоти; частота 0 — пауза. */
data class Note(val fromHz: Float, val toHz: Float, val ms: Int)

class ChirpPlayer(private val volume: Float = 0.5f) {

    suspend fun play(chirp: Chirp) = withContext(Dispatchers.Default) {
        val pcm = synthesize(chirp.notes.toList())
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // той самий потік, що й голос (TTS)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(pcm.size * 1000L / RATE + 30)
        } finally {
            track.release()
        }
    }

    private fun synthesize(notes: List<Note>): ShortArray {
        val out = ShortArray(notes.sumOf { it.ms } * RATE / 1000)
        var pos = 0
        var phase = 0.0
        for (note in notes) {
            val n = note.ms * RATE / 1000
            val fade = min(n / 4, RATE * 8 / 1000) // 8 мс — без клацань
            for (i in 0 until n) {
                if (note.fromHz > 0f) {
                    val hz = note.fromHz + (note.toHz - note.fromHz) * i / n
                    phase += 2 * PI * hz / RATE
                    val envelope = min(1f, min(i, n - 1 - i).toFloat() / fade.coerceAtLeast(1))
                    out[pos] = (sin(phase) * envelope * volume * Short.MAX_VALUE).toInt().toShort()
                }
                pos++
            }
        }
        return out
    }

    private companion object {
        const val RATE = 22050
    }
}
