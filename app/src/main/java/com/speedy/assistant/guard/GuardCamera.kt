package com.speedy.assistant.guard

import android.annotation.SuppressLint
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.speedy.assistant.vision.CocoDetector
import com.speedy.assistant.vision.FaceRecognizer
import com.speedy.assistant.vision.Kind
import com.speedy.assistant.vision.People
import com.speedy.assistant.vision.Pets
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Зір сторожа: передня й задня камери по черзі (дві одразу J5 не тримає), кадр 320×240 раз на
 * [INTERVAL_MS]. Знайоме обличчя чи своя тварина — спокій; незнайомі людина чи тварина, або
 * стійкий рух, у якому нікого не видно, — [onDetect]. Поки щось відбувається — не перемикаємо камеру.
 */
class GuardCamera(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val people: People?,
    private val pets: Pets?,
    /** Тривога на рух без людини й тварини. */
    private val motionAlarm: () -> Boolean,
    /** Знайоме обличчя (головний потік). */
    private val onFriend: (String) -> Unit,
    /** Тривога: що побачила (головний потік). */
    private val onDetect: (String) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var capture: ImageCapture? = null
    private var front = true
    private var wanted = false
    private var binding = false
    @Volatile private var since = 0L
    @Volatile private var busyUntil = 0L

    private var detector: CocoDetector? = null
    private var faces: FaceRecognizer? = null
    private var lastRun = 0L
    private var previous: IntArray? = null
    private var skipFrames = 0
    private var strangerStreak = 0
    private var animalStreak = 0
    private var motionStreak = 0

    val running: Boolean get() = analysis != null

    /** Головний потік. */
    fun start() {
        wanted = true
        bind()
    }

    fun stop() {
        wanted = false
        provider?.unbindAll()
        analysis = null
        capture = null
    }

    fun release() {
        stop()
        executor.execute { detector?.close(); detector = null; faces?.close(); faces = null }
        executor.shutdown()
    }

    private fun bind() {
        if (analysis != null || binding || !wanted) return
        binding = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            binding = false
            if (!wanted || analysis != null) return@addListener
            try {
                val p = future.get().also { provider = it }
                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                useCase.setAnalyzer(executor, ::analyze)
                val still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                p.unbindAll()
                p.bindToLifecycle(owner, selector(), useCase, still)
                analysis = useCase
                capture = still
                since = SystemClock.uptimeMillis()
                executor.execute { previous = null; skipFrames = SETTLE_FRAMES }
                Log.d(TAG, "камера: ${if (front) "передня" else "задня"}")
            } catch (e: Exception) {
                Log.e(TAG, "камера не вмикається (${if (front) "передня" else "задня"})", e)
                // Задньої може не бути або вона зайнята — лишаємось на передній.
                if (!front) { front = true; main.postDelayed(::bind, 1_000) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun selector() = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun switchCamera() {
        if (!wanted) return
        provider?.unbindAll()
        analysis = null
        capture = null
        front = !front
        bind()
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.uptimeMillis()
            if (now - lastRun < INTERVAL_MS || now < busyUntil) return
            lastRun = now
            val frame = upright(it.toBitmap(), it.imageInfo.rotationDegrees)
            val moved = motion(frame)
            val model = detector() ?: return
            val found = model.detect(frame, SCORE).mapNotNull { d -> Kind.fromCoco(d.cocoClass)?.let { k -> k to d } }
            val persons = found.filter { (k, _) -> k == Kind.PERSON }
            val animals = found.filter { (k, _) -> k == Kind.CAT || k == Kind.DOG }

            var calm = true
            if (persons.isNotEmpty()) {
                calm = false
                val known = people?.let { p -> recognizer()?.let { r ->
                    try { r.recognize(frame).mapNotNull { f -> p.whoIs(f.embedding) } } catch (e: Exception) { emptyList() }
                } }.orEmpty()
                if (known.isNotEmpty()) {
                    strangerStreak = 0
                    main.post { onFriend(known.first()) }
                } else if (++strangerStreak >= 2) {
                    strangerStreak = 0
                    alarm("незнайома людина")
                }
            } else strangerStreak = 0
            if (animals.isNotEmpty() && persons.isEmpty()) {
                calm = false
                val (kind, d) = animals.maxBy { (_, d) -> (d.right - d.left) * (d.bottom - d.top) }
                val name = pets?.identify(kind, Pets.signature(frame, d.left, d.top, d.right, d.bottom))
                if (name != null) animalStreak = 0
                else if (++animalStreak >= 2) {
                    animalStreak = 0
                    alarm("незнайом${if (kind == Kind.CAT) "ий кіт" else "ий собака"}")
                }
            } else animalStreak = 0
            if (found.isEmpty() && moved) {
                calm = false
                if (++motionStreak >= MOTION_FRAMES && motionAlarm()) {
                    motionStreak = 0
                    alarm("рух у кімнаті")
                }
            } else if (!moved) motionStreak = 0

            // Тихо — по черзі дивимось іншою камерою.
            if (calm && strangerStreak == 0 && motionStreak == 0 && now - since >= SWITCH_MS) main.post(::switchCamera)
        }
    }

    private fun alarm(what: String) {
        val where = if (front) "передня камера" else "задня камера"
        Log.i(TAG, "тривога: $what ($where)")
        busyUntil = SystemClock.uptimeMillis() + QUIET_AFTER_MS
        main.post { onDetect("$what ($where)") }
    }

    /** Різниця з попереднім кадром тієї ж камери: сітка 32×24 яскравостей. */
    private fun motion(frame: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(frame, GRID_W, GRID_H, true)
        val px = IntArray(GRID_W * GRID_H)
        small.getPixels(px, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        val luma = IntArray(px.size) { i -> val c = px[i]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 }
        val prev = previous
        previous = luma
        if (skipFrames > 0) { skipFrames--; return false }
        if (prev == null) return false
        // Загальна зміна світла (хмара, лампа) — зсув середнього не рахуємо.
        val shift = (luma.average() - prev.average()).toInt()
        val changed = luma.indices.count { i -> abs(luma[i] - prev[i] - shift) > PIXEL_DIFF }
        return changed > px.size * MOTION_SHARE
    }

    /** Фото повної якості з поточної камери. Головний потік. */
    suspend fun takePhoto(file: File): File? {
        val still = capture ?: return null
        return suspendCancellableCoroutine { cont ->
            still.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) { if (cont.isActive) cont.resume(file) }
                    override fun onError(e: ImageCaptureException) {
                        Log.w(TAG, "фото не вдалося", e)
                        if (cont.isActive) cont.resume(null)
                    }
                })
        }
    }

    /**
     * Відео зі звуком ([seconds] с): на цей час камера лише пише (три потоки разом J5 не тягне), потім —
     * знову спостереження. Мікрофон має бути вільний. Головний потік.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordVideo(file: File, seconds: Int): File? {
        val p = provider ?: return null
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.LOWEST)).build()
        val video = VideoCapture.withOutput(recorder)
        p.unbindAll()
        analysis = null
        capture = null
        try {
            p.bindToLifecycle(owner, selector(), video)
        } catch (e: Exception) {
            Log.w(TAG, "відео не вмикається", e)
            bind()
            return null
        }
        val result = suspendCancellableCoroutine { cont ->
            val recording = video.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        if (event.hasError()) Log.w(TAG, "відео: помилка ${event.error}", event.cause)
                        if (cont.isActive) cont.resume(file.takeIf { it.exists() && it.length() > 0 })
                    }
                }
            main.postDelayed({ recording.stop() }, seconds * 1000L)
            cont.invokeOnCancellation { recording.stop() }
        }
        p.unbind(video)
        bind()
        return result
    }

    private fun detector(): CocoDetector? = detector ?: try {
        CocoDetector(context, MODEL, threads = 2).also { detector = it }
    } catch (e: Throwable) {
        Log.e(TAG, "детектор не вмикається", e); null
    }

    private fun recognizer(): FaceRecognizer? = faces ?: try {
        FaceRecognizer(context).also { faces = it }
    } catch (e: Throwable) {
        Log.e(TAG, "розпізнавання облич не вмикається", e); null
    }

    private fun upright(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val TAG = "GuardCamera"
        const val MODEL = "efficientdet_lite0.tflite"
        const val INTERVAL_MS = 1_000L
        const val SCORE = 0.5f
        /** Скільки дивимось однією камерою, коли тихо. */
        const val SWITCH_MS = 10_000L
        /** Після перемикання камера підлаштовує експозицію — кадри не порівнюємо. */
        const val SETTLE_FRAMES = 2
        /** Після тривоги — пауза, поки знімаємо й надсилаємо. */
        const val QUIET_AFTER_MS = 20_000L
        const val GRID_W = 32
        const val GRID_H = 24
        const val PIXEL_DIFF = 25
        const val MOTION_SHARE = 0.04
        const val MOTION_FRAMES = 3
    }
}
