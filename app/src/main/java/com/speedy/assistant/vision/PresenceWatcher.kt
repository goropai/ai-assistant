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
    /** Пам'ять облич; null — розпізнавання облич вимкнено. */
    private val people: People? = null,
    /** Імена домашніх тварин; null — не запам'ятовуємо. */
    private val pets: Pets? = null,
    /** Локальні профілі знайомих: візити, одяг. */
    private val profiles: com.speedy.assistant.people.Profiles? = null,
    private val onEvents: (List<PresenceEvent>) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tracker = PresenceTracker()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var detector: CocoDetector? = null
    private var faces: FaceRecognizer? = null
    private var facesBroken = false
    private var lastRun = 0L
    private var lastKinds = emptySet<Kind>()
    private val lastProfileUpdate = HashMap<String, Long>()
    private var clothingCandidate: Pair<String, Int>? = null
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
        executor.execute {
            detector?.close(); detector = null
            faces?.close(); faces = null
        }
        executor.shutdown()
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.uptimeMillis()
            if (broken || now - lastRun < INTERVAL_MS) return
            lastRun = now
            val model = detector() ?: return
            val frame = upright(it.toBitmap(), it.imageInfo.rotationDegrees)

            val detections = model.detect(frame, DIAG_THRESHOLD)
            // Кіт чи собака з низькою впевненістю — у лог, щоб було видно, чи модель їх узагалі помічає.
            detections.filter { d -> d.score < SCORE_THRESHOLD && Kind.fromCoco(d.cocoClass).let { it == Kind.CAT || it == Kind.DOG } }
                .forEach { d -> Log.d(TAG, "майже ${Kind.fromCoco(d.cocoClass)?.ua}: %.2f".format(d.score)) }
            val sightings = detections.filter { d -> d.score >= SCORE_THRESHOLD }.take(MAX_RESULTS).mapNotNull { d ->
                val kind = Kind.fromCoco(d.cocoClass) ?: return@mapNotNull null
                Sighting(
                    kind = kind,
                    score = d.score,
                    cx = (d.left + d.right) / 2,
                    cy = (d.top + d.bottom) / 2,
                    area = (d.right - d.left) * (d.bottom - d.top),
                )
            }
            val kinds = sightings.map { s -> s.kind }.toSet()
            if (kinds != lastKinds) {
                Log.i(TAG, "бачу: ${kinds.joinToString { k -> k.ua }.ifEmpty { "нікого" }} " +
                    sightings.joinToString(prefix = "(", postfix = ")") { s -> "%s %.2f".format(s.kind.ua, s.score) })
                lastKinds = kinds
            }
            // Найбільша тварина в кадрі — її забарвлення для впізнавання.
            val petNames = HashMap<Kind, String>()
            if (pets != null) {
                detections.filter { d -> d.score >= SCORE_THRESHOLD && Kind.fromCoco(d.cocoClass).let { it == Kind.CAT || it == Kind.DOG } }
                    .maxByOrNull { d -> (d.right - d.left) * (d.bottom - d.top) }
                    ?.let { d ->
                        val kind = Kind.fromCoco(d.cocoClass)!!
                        pets.observe(kind, Pets.signature(frame, d.left, d.top, d.right, d.bottom), now)?.let { petNames[kind] = it }
                    }
            }
            val events = tracker.update(now, sightings)
                .map { e -> if (e is PresenceEvent.Arrived && e.kind in petNames) e.copy(name = petNames[e.kind]) else e }
                .toMutableList()
            if (people != null && sightings.any { s -> s.kind == Kind.PERSON }) {
                faceRecognizer()?.let { recognizer ->
                    val observed = try {
                        recognizer.recognize(frame)
                    } catch (e: Exception) {
                        Log.w(TAG, "face recognition failed", e)
                        emptyList()
                    }
                    events += people.update(now, observed)
                }
            }
            val names = people?.presentNames(now).orEmpty()
            if (profiles != null && names.size == 1) noteKnownPerson(names.first(), frame, detections, now)
            val petsNear = petNames.map { (kind, name) -> "${kind.ua} $name" }
            summary = (names + petsNear + tracker.summary()).filter { s -> s.isNotBlank() }.joinToString(", ")
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

    /** Поруч одна знайома людина: візит (не частіше разу на хвилину) і колір верху (3 кадри поспіль). */
    private fun noteKnownPerson(name: String, frame: Bitmap, detections: List<Detection>, now: Long) {
        val profiles = profiles ?: return
        val wall = System.currentTimeMillis()
        if (now - (lastProfileUpdate[name] ?: Long.MIN_VALUE / 2) >= 60_000) {
            lastProfileUpdate[name] = now
            profiles.seen(name, wall)
        }
        val persons = detections.filter { d -> d.score >= SCORE_THRESHOLD && Kind.fromCoco(d.cocoClass) == Kind.PERSON }
        if (persons.size != 1) return
        val d = persons.first()
        val color = com.speedy.assistant.people.Clothing.color(frame, d.left, d.top, d.right, d.bottom) ?: return
        val streak = clothingCandidate?.takeIf { it.first == color }?.second?.plus(1) ?: 1
        clothingCandidate = color to streak
        if (streak == 3) {
            Log.i(TAG, "$name сьогодні в $color")
            profiles.clothing(name, color, wall)
        }
    }

    private fun faceRecognizer(): FaceRecognizer? {
        if (facesBroken) return null
        faces?.let { return it }
        return try {
            FaceRecognizer(context).also { faces = it }
        } catch (e: Throwable) {
            Log.e(TAG, "face recognizer init failed, faces disabled", e)
            facesBroken = true
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
        const val DIAG_THRESHOLD = 0.25f
    }
}
