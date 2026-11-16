package com.speedy.assistant.vision

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Імена домашніх тварин. Модель облич — для людей, тож тварину впізнаємо за забарвленням:
 * гістограма кольорів її рамки в кадрі ([signature]). Якщо кіт у домі один — ім'я завжди
 * йому; кількох котів розрізняємо, якщо вони різного кольору. Усе — лише на телефоні.
 * Спільна для потоку камери й головного потоку, тож методи синхронізовані.
 */
class Pets(private val file: File?) {
    data class Pet(val name: String, val kind: Kind, val signature: FloatArray)

    private val pets = mutableListOf<Pet>()
    private var recent: Triple<Kind, FloatArray, Long>? = null

    init {
        load()
    }

    /** Тварина в кадрі: запам'ятати як "щойно бачену" і повернути ім'я, якщо знайома. */
    @Synchronized
    fun observe(kind: Kind, signature: FloatArray, now: Long): String? {
        recent = Triple(kind, signature, now)
        return identify(kind, signature)
    }

    @Synchronized
    fun identify(kind: Kind, signature: FloatArray): String? {
        val same = pets.filter { it.kind == kind }
        if (same.isEmpty()) {
            // Модель часом плутає кота й собаку — тоді впізнаємо за кольором, але суворіше.
            return pets.maxByOrNull { similarity(it.signature, signature) }
                ?.takeIf { similarity(it.signature, signature) >= THRESHOLD }?.name
        }
        val best = same.maxByOrNull { similarity(it.signature, signature) }!!
        val score = similarity(best.signature, signature)
        // Одна тварина цього виду — поблажливо (світло міняє кольори); кілька — суворіше.
        return best.name.takeIf { score >= if (same.size == 1) LONE_THRESHOLD else THRESHOLD }
    }

    /** Назвати тварину, яку щойно бачили. false — тварини в кадрі не було. */
    @Synchronized
    fun name(name: String, now: Long): Boolean {
        val (kind, signature, at) = recent ?: return false
        if (now - at > RECENT_MS) return false
        val existing = identify(kind, signature)
        if (existing != null) pets.removeAll { it.name == existing && it.kind == kind } // перейменування
        pets.removeAll { it.name.equals(name, ignoreCase = true) && it.kind == kind }
        pets += Pet(name, kind, signature)
        save()
        return true
    }

    @Synchronized
    fun recentKind(now: Long): Kind? = recent?.takeIf { now - it.third <= RECENT_MS }?.first

    private fun load() {
        val f = file?.takeIf { it.exists() } ?: return
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sig = o.getJSONArray("signature")
                pets += Pet(o.getString("name"), Kind.valueOf(o.getString("kind")),
                    FloatArray(sig.length()) { sig.getDouble(it).toFloat() })
            }
        } catch (e: Exception) {
            pets.clear()
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        pets.forEach { p ->
            arr.put(JSONObject().put("name", p.name).put("kind", p.kind.name)
                .put("signature", JSONArray(p.signature.map { it.toDouble() })))
        }
        f.writeText(arr.toString())
    }

    companion object {
        const val THRESHOLD = 0.65f
        const val LONE_THRESHOLD = 0.3f
        const val RECENT_MS = 60_000L
        private const val HUE_BINS = 12
        private const val GRAY_BINS = 4

        /** Перетин нормованих гістограм: 1 — однакові, 0 — нічого спільного. */
        fun similarity(a: FloatArray, b: FloatArray): Float = a.indices.fold(0f) { acc, i -> acc + minOf(a[i], b[i]) }

        /**
         * Колірний відбиток рамки (частки кадру): 12 відтінків для кольорових пікселів +
         * 4 рівні яскравості для сірих/чорних/білих (у котів таких багато).
         */
        fun signature(frame: Bitmap, left: Float, top: Float, right: Float, bottom: Float): FloatArray {
            val x0 = (left * frame.width).toInt().coerceIn(0, frame.width - 1)
            val y0 = (top * frame.height).toInt().coerceIn(0, frame.height - 1)
            val x1 = (right * frame.width).toInt().coerceIn(x0 + 1, frame.width)
            val y1 = (bottom * frame.height).toInt().coerceIn(y0 + 1, frame.height)
            val hist = FloatArray(HUE_BINS + GRAY_BINS)
            val hsv = FloatArray(3)
            val step = maxOf(1, (x1 - x0) / 24)
            var count = 0
            for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                val bin = if (hsv[1] < 0.25f || hsv[2] < 0.15f) {
                    HUE_BINS + (hsv[2] * GRAY_BINS).toInt().coerceAtMost(GRAY_BINS - 1)
                } else {
                    (hsv[0] / 360f * HUE_BINS).toInt().coerceAtMost(HUE_BINS - 1)
                }
                hist[bin]++
                count++
            }
            if (count > 0) for (i in hist.indices) hist[i] /= count
            return hist
        }
    }
}
