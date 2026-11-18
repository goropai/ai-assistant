package com.speedy.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Кілька секунд звуку навколо — WAV у пам'яті (16 кГц моно, ~32 КБ/с), для моделі ("що це за пісня?"). */
object SoundClip {
    private const val RATE = 16_000

    /**
     * Запис і чи там справді щось звучить: YAMNet хоч у [MIN_WINDOWS] вікнах (~1 с) почув не тло
     * (тишу, шум кімнати, гул мережі), а музику, голос, спів чи виразний звук. Гучність сама не
     * годиться: тиша кімнати на J5 така ж гучна, як тиха музика.
     */
    class Clip(val wav: ByteArray, val rms: Double, val soundWindows: Int, val topClasses: List<Int>) {
        val audible: Boolean get() = soundWindows >= MIN_WINDOWS
    }

    private const val MIN_WINDOWS = 2
    private const val SCORE = 0.3f
    /** Нижче — справжня тиша, модель не запускаємо. */
    private const val SILENCE_RMS = 40.0
    /** Тло: тиша, приміщення, шуми, гул мережі, луна, "польовий запис". */
    private val BACKGROUND = setOf(494, 500, 501, 502, 503, 504, 505, 506, 507, 508, 509, 510, 511, 512, 513, 514, 515, 520)

    /** null — мікрофон зайнятий. Мікрофон має бути вільний (слух Спіді зупинено). */
    @SuppressLint("MissingPermission") // RECORD_AUDIO — основний дозвіл Спіді
    suspend fun record(context: android.content.Context, seconds: Int): Clip? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2))
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            rec.startRecording()
            val pcm = ByteArray(RATE * 2 * seconds)
            var read = 0
            while (read < pcm.size) {
                val n = rec.read(pcm, read, pcm.size - read)
                if (n <= 0) break
                read += n
            }
            if (read == 0) return@withContext null
            val rms = rms(pcm, read)
            val (windows, tops) = if (rms < SILENCE_RMS) 0 to emptyList() else classify(context, pcm, read)
            Clip(wav(pcm, read), rms, windows, tops)
        } catch (e: Exception) {
            Log.w("SoundClip", "запис не вдався", e)
            null
        } finally {
            rec.release()
        }
    }

    /** Скільки вікон (крок 0.5 с) не тло, і найчастіші класи — для журналу. */
    private fun classify(context: android.content.Context, pcm: ByteArray, size: Int): Pair<Int, List<Int>> {
        val yamnet = try { com.speedy.assistant.night.Yamnet(context) } catch (e: Exception) { return Int.MAX_VALUE to emptyList() }
        try {
            val samples = size / 2
            val window = FloatArray(com.speedy.assistant.night.Yamnet.WINDOW)
            var count = 0
            val tops = mutableListOf<Int>()
            var start = 0
            while (start + window.size <= samples) {
                for (i in window.indices) {
                    val j = (start + i) * 2
                    window[i] = ((pcm[j].toInt() and 0xff) or (pcm[j + 1].toInt() shl 8)).toShort() / 32768f
                }
                val scores = yamnet.scores(window)
                var top = 0
                for (i in scores.indices) if (scores[i] > scores[top]) top = i
                tops += top
                if (top !in BACKGROUND && scores[top] >= SCORE) count++
                start += RATE / 2
            }
            return count to tops.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        } finally {
            yamnet.close()
        }
    }

    private fun rms(pcm: ByteArray, size: Int): Double {
        var sum = 0.0
        val samples = size / 2
        for (i in 0 until samples) {
            val v = ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort().toDouble()
            sum += v * v
        }
        return if (samples == 0) 0.0 else kotlin.math.sqrt(sum / samples)
    }

    private fun wav(pcm: ByteArray, size: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
        }
        return ByteArrayOutputStream(44 + size).apply { write(header.array()); write(pcm, 0, size) }.toByteArray()
    }
}
