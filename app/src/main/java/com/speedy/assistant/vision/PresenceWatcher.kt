package com.speedy.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Локальне (без інтернету) розпізнавання людей, котів і собак фронтальною камерою.
 * Модель EfficientDet-Lite0 (COCO) запускається раз на [INTERVAL_MS] на кадрі 320×240 —
 * для слабкого процесора це прийнятно і не з'їдає батарею так, як відео в хмару.
 *
 * Камеру ділимо з [CameraSnapshot]: поки Спіді зайнята, спостереження треба зупиняти ([stop]).
 */
class PresenceWatcher(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val onEvents: (List<PresenceEvent>) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tracker = PresenceTracker()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var detector: CocoDetector? = null
    private var lastRun = 0L
    /** Прив'язка камери асинхронна: між start() і готовністю провайдера могли викликати stop(). */
    private var wanted = false
    private var binding = false
    @Volatile private var broken = false

    /** Хто зараз поруч ("людина, кіт") — для контексту "великого мозку". */
    @Volatile var summary: String = ""
        private set

    /** Викликати з головного потоку. */
    fun start() {
        wanted = true
        if (analysis != null || binding || broken) return
        binding = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            binding = false
            if (!wanted || analysis != null) return@addListener
            try {
                val p = future.get().also { provider = it }
                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                useCase.setAnalyzer(executor, ::analyze)
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
                analysis = useCase
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Звільнити камеру (для фото або щоб зберегти батарею). Викликати з головного потоку. */
    fun stop() {
        wanted = false
        analysis?.let { provider?.unbind(it) }
        analysis = null
    }

    fun release() {
        stop()
        executor.execute { detector?.close(); detector = null }
        executor.shutdown()
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.uptimeMillis()
            if (broken || now - lastRun < INTERVAL_MS) return
            lastRun = now
            val model = detector() ?: return
            val frame = upright(it.toBitmap(), it.imageInfo.rotationDegrees)

            val sightings = model.detect(frame, SCORE_THRESHOLD).take(MAX_RESULTS).mapNotNull { d ->
                val kind = Kind.fromCoco(d.cocoClass) ?: return@mapNotNull null
                Sighting(
                    kind = kind,
                    score = d.score,
                    cx = (d.left + d.right) / 2,
                    cy = (d.top + d.bottom) / 2,
                    area = (d.right - d.left) * (d.bottom - d.top),
                )
            }
            val events = tracker.update(now, sightings)
            summary = tracker.summary()
            if (events.isNotEmpty()) main.post { onEvents(events) }
        }
    }

    private fun detector(): CocoDetector? {
        detector?.let { return it }
        return try {
            CocoDetector(context, MODEL, threads = 2).also { detector = it }
        } catch (e: Throwable) {
            // Напр. немає нативної бібліотеки для цього процесора — вимикаємось, а не падаємо.
            Log.e(TAG, "detector init failed, presence disabled", e)
            broken = true
            main.post { stop() }
            null
        }
    }

    private fun upright(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val TAG = "PresenceWatcher"
        const val MODEL = "efficientdet_lite0.tflite"
        const val INTERVAL_MS = 1500L
        const val SCORE_THRESHOLD = 0.5f
        const val MAX_RESULTS = 5
    }
}
