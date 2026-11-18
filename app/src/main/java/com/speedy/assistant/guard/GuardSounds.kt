package com.speedy.assistant.guard

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.speedy.assistant.night.WavWriter
import com.speedy.assistant.night.Yamnet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/** Що насторожує на слух (класи YAMNet / AudioSet). Тварин не слухаємо: свої коти й сусідські собаки. */
enum class GuardSound(val ua: String, val classes: IntArray) {
    VOICE("голос", intArrayOf(0, 1, 6, 9, 10, 11)),
    STEPS("кроки", intArrayOf(48)),
    DOOR("двері чи стук", intArrayOf(348, 351, 353)),
    GLASS("розбите скло чи удар", intArrayOf(435, 437, 460, 463, 464)),
    ALARM("сирена чи пожежна сигналізація", intArrayOf(382, 390, 391, 393, 394));

    companion object {
        /** Найсильніший насторожливий звук у вікні, якщо його оцінка вища за поріг. */
        fun of(scores: FloatArray, threshold: Float): Pair<GuardSound, Float>? =
            entries.map { s -> s to s.classes.maxOf { scores[it] } }.maxByOrNull { it.second }?.takeIf { it.second >= threshold }
    }
}

/**
 * Слух сторожа: мікрофон, YAMNet щопів секунди (у тиші — ні). Насторожливий звук двічі поспіль — тривога;
 * запис — [PRE_ROLL_S] с до звуку і [POST_S] с після, у WAV; [onSound] отримує його, коли запис готовий.
 * На час відео (камера пише свій звук) і розмови зі знайомим мікрофон звільняємо ([stop]).
 */
class GuardSounds(
    private val context: Context,
    /** Чи це насторожує зараз (напр. голос, коли поруч знайомий, — ні). */
    private val worth: (GuardSound) -> Boolean,
    private val onSound: (GuardSound, File) -> Unit,
) {
    private var job: Job? = null
    @Volatile var folder: File? = null

    val active: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (active) return
        job = scope.launch(Dispatchers.IO) {
            try {
                listen()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) Log.e(TAG, "слух сторожа зупинився", e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO — основний дозвіл Спіді
    private suspend fun listen() {
        val yamnet = Yamnet(context)
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2 * 4))
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "мікрофон зайнятий" }
            rec.startRecording()
            Log.i(TAG, "сторож слухає")
            val hop = ShortArray(HOP)
            val window = FloatArray(Yamnet.WINDOW)
            val preRoll = ShortArray(RATE * PRE_ROLL_S)
            var preRollPos = 0
            var preRollFilled = 0
            var streak: GuardSound? = null
            var streakCount = 0
            // Запис після тривоги: файл, звук і скільки ще пів секунд писати.
            var clip: Triple<WavWriter, GuardSound, File>? = null
            var clipLeft = 0
            while (currentCoroutineContext().isActive) {
                var read = 0
                while (read < HOP) {
                    val n = rec.read(hop, read, HOP - read)
                    if (n <= 0) error("мікрофон: $n")
                    read += n
                }
                System.arraycopy(window, HOP, window, 0, Yamnet.WINDOW - HOP)
                for (i in 0 until HOP) window[Yamnet.WINDOW - HOP + i] = hop[i] / 32768f

                clip?.let { (writer, sound, file) ->
                    writer.write(hop)
                    if (--clipLeft <= 0) {
                        writer.close()
                        clip = null
                        onSound(sound, file)
                    }
                }
                if (clip == null) {
                    val rms = sqrt(hop.fold(0.0) { s, v -> s + v * v.toDouble() } / HOP)
                    val heard = if (rms < SILENCE_RMS) null else GuardSound.of(yamnet.scores(window), THRESHOLD)
                    val sound = heard?.first?.takeIf(worth)
                    if (sound != null && sound == streak) streakCount++ else { streak = sound; streakCount = if (sound != null) 1 else 0 }
                    if (sound != null && (streakCount >= 2 || sound == GuardSound.GLASS || sound == GuardSound.ALARM)) {
                        Log.i(TAG, "чую: ${sound.ua} (%.2f)".format(heard.second))
                        val dir = folder ?: context.cacheDir
                        val file = File(dir, "sound-${System.currentTimeMillis()}.wav")
                        val writer = WavWriter(file, RATE)
                        val start = if (preRollFilled < preRoll.size) 0 else preRollPos
                        writer.write(preRoll, start, preRollFilled - start)
                        if (start > 0) writer.write(preRoll, 0, start)
                        writer.write(hop)
                        clip = Triple(writer, sound, file)
                        clipLeft = POST_S * 2
                        streak = null; streakCount = 0
                    }
                }
                for (i in 0 until HOP) {
                    preRoll[preRollPos] = hop[i]
                    preRollPos = (preRollPos + 1) % preRoll.size
                }
                preRollFilled = minOf(preRoll.size, preRollFilled + HOP)
            }
            clip?.first?.close()
        } finally {
            rec.release()
            yamnet.close()
        }
    }

    private companion object {
        const val TAG = "GuardSounds"
        const val RATE = 16_000
        const val HOP = RATE / 2
        const val PRE_ROLL_S = 5
        const val POST_S = 5
        const val THRESHOLD = 0.35f
        const val SILENCE_RMS = 60.0
    }
}
