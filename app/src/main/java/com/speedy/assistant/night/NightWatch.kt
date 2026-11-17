package com.speedy.assistant.night

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** YAMNet (Google, Apache 2.0): 0.975 с звуку 16 кГц → оцінки 521 класу AudioSet; 38 — "Snoring". */
class Yamnet(context: Context) : Closeable {
    private val interpreter: Interpreter = context.assets.openFd(MODEL).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            Interpreter(ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength), Interpreter.Options().setNumThreads(2))
        }
    }
    private val output = Array(1) { FloatArray(CLASSES) }

    /** Оцінка хропіння і найімовірніший клас (для журналу: що ще було чути). */
    fun classify(window: FloatArray): Pair<Float, Int> {
        interpreter.run(window, output)
        val scores = output[0]
        var top = 0
        for (i in scores.indices) if (scores[i] > scores[top]) top = i
        return scores[SNORING] to top
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL = "yamnet.tflite"
        const val WINDOW = 15_600
        const val CLASSES = 521
        const val SNORING = 38
        /** Кілька класів для журналу (решта — номером). */
        val NAMES = mapOf(0 to "мова", 36 to "дихання", 38 to "хропіння", 132 to "музика", 494 to "тиша", 500 to "шум")
    }
}

/**
 * Нічний режим: мікрофон слухає до ранку, YAMNet щопів секунди оцінює хропіння; уривки з хропінням
 * (з кількома секундами до початку) — у WAV, епізоди — у [NightLog]. Звук нікуди не відправляється.
 * У тиші модель не запускаємо — бережемо батарею.
 */
class NightWatch(
    private val context: Context,
    private val log: NightLog,
    /** Тека для записів (кожна ніч — своя підтека за датою). */
    private val root: File,
    /** Ніч скінчилась сама (настав ранок) — повернути звичайний режим. */
    private val onFinished: () -> Unit,
) {
    private var job: Job? = null
    @Volatile var until = 0L; private set

    val active: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, until: Long) {
        if (active) return
        this.until = until
        val folder = File(root, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())).apply { mkdirs() }
        log.begin(System.currentTimeMillis(), folder.path)
        job = scope.launch(Dispatchers.IO) {
            try {
                listen(folder)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) Log.e(TAG, "нічний режим зупинився", e)
            } finally {
                log.end(System.currentTimeMillis())
                Log.i(TAG, "нічний режим завершено: ${log.episodes().size} епізодів, ${log.clips().size} записів")
            }
        }
        job?.invokeOnCompletion { if (System.currentTimeMillis() >= until) onFinished() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO — основний дозвіл Спіді
    private suspend fun listen(folder: File) {
        val yamnet = Yamnet(context)
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2 * 4))
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "мікрофон зайнятий" }
            rec.startRecording()
            Log.i(TAG, "нічний режим: слухаю до ${SimpleDateFormat("H:mm", Locale.US).format(Date(until))}")
            val episodes = SnoreEpisodes()
            val hop = ShortArray(HOP)
            val window = FloatArray(Yamnet.WINDOW)
            val preRoll = ShortArray(RATE * PRE_ROLL_S) // кільцевий буфер останніх секунд
            var preRollPos = 0
            var preRollFilled = 0
            var clip: WavWriter? = null
            var clipsTotal = 0
            var minuteMax = 0f
            var minuteAt = System.currentTimeMillis()
            var minuteTop = mutableMapOf<Int, Int>()

            while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < until) {
                var read = 0
                while (read < HOP) {
                    val n = rec.read(hop, read, HOP - read)
                    if (n <= 0) error("мікрофон: $n")
                    read += n
                }
                // Вікно моделі — останні 0.975 с.
                System.arraycopy(window, HOP, window, 0, Yamnet.WINDOW - HOP)
                for (i in 0 until HOP) window[Yamnet.WINDOW - HOP + i] = hop[i] / 32768f
                clip?.write(hop)
                // Тиша — модель не запускаємо.
                val rms = sqrt(hop.fold(0.0) { s, v -> s + v * v.toDouble() } / HOP)
                val (score, top) = if (rms < SILENCE_RMS) 0f to 494 else yamnet.classify(window)
                minuteMax = maxOf(minuteMax, score)
                minuteTop[top] = (minuteTop[top] ?: 0) + 1

                val now = System.currentTimeMillis()
                when (val e = episodes.feed(now, score)) {
                    is SnoreEpisodes.Event.Started -> {
                        Log.i(TAG, "хропіння почалось (оцінка ${"%.2f".format(score)})")
                        if (clip == null && clipsTotal < MAX_CLIPS) {
                            val f = File(folder, SimpleDateFormat("HH-mm-ss", Locale.US).format(Date(e.since)) + ".wav")
                            clip = WavWriter(f, RATE).also { w ->
                                // Кілька секунд до початку — з кільцевого буфера, у правильному порядку.
                                val start = if (preRollFilled < preRoll.size) 0 else preRollPos
                                w.write(preRoll, start, preRollFilled - start)
                                if (start > 0) w.write(preRoll, 0, start)
                                w.write(hop)
                            }
                            clipsTotal++
                            log.clip(f.path)
                        }
                    }
                    is SnoreEpisodes.Event.Ended -> {
                        Log.i(TAG, "хропіння скінчилось: ${(e.end - e.start) / 1000} с")
                        log.episode(e.start, e.end)
                        clip?.close(); clip = null
                    }
                    null -> {}
                }
                // Уривок не довший за хвилину — щоб ніч не з'їла пам'ять (епізод триває й далі).
                if ((clip?.seconds ?: 0.0) >= MAX_CLIP_S) { clip?.close(); clip = null }

                for (i in 0 until HOP) {
                    preRoll[preRollPos] = hop[i]
                    preRollPos = (preRollPos + 1) % preRoll.size
                }
                preRollFilled = minOf(preRoll.size, preRollFilled + HOP)

                if (now - minuteAt >= 60_000) {
                    val heard = minuteTop.entries.sortedByDescending { it.value }.take(3)
                        .joinToString { (k, v) -> "${Yamnet.NAMES[k] ?: "клас $k"}×$v" }
                    Log.d(TAG, "хвилина: хропіння макс ${"%.2f".format(minuteMax)}, чути: $heard")
                    minuteMax = 0f; minuteTop = mutableMapOf(); minuteAt = now
                }
            }
            episodes.finish()?.let { log.episode(it.start, it.end) }
            clip?.close()
        } finally {
            rec.release()
            yamnet.close()
        }
    }

    private companion object {
        const val TAG = "Night"
        const val RATE = 16_000
        /** Крок — пів секунди: модель бачить кожен звук двічі (вікно ~1 с). */
        const val HOP = RATE / 2
        const val PRE_ROLL_S = 5
        const val MAX_CLIP_S = 60.0
        const val MAX_CLIPS = 40
        /** Нижче — тиша (хропіння в кімнаті значно гучніше). */
        const val SILENCE_RMS = 60.0
    }
}
