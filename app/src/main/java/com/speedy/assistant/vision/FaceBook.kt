package com.speedy.assistant.vision

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * Знайомі обличчя: ім'я → кілька "відбитків" (embedding MobileFaceNet, L2-нормовані).
 * Зберігаються лише на телефоні (files/faces.json), нікуди не надсилаються.
 */
class FaceBook(private val file: File?) {
    private val people = LinkedHashMap<String, MutableList<FloatArray>>()

    init {
        load()
    }

    val names: Set<String> get() = people.keys

    /** Найближче знайоме обличчя, якщо схожість не нижча за [threshold] (косинус). */
    fun match(embedding: FloatArray, threshold: Float = MATCH_THRESHOLD): Pair<String, Float>? =
        people.flatMap { (name, samples) -> samples.map { name to cosine(it, embedding) } }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= threshold }

    /** Результат перевірки нового обличчя перед записом. */
    sealed interface Check {
        /** Узгоджені кадри, жодного збігу з відомими — можна запам'ятати. */
        class New(val samples: List<FloatArray>) : Check
        /** Схоже на вже відоме обличчя — нове ім'я не створюємо. */
        data class LooksLike(val name: String, val similarity: Float) : Check
        /** Замало чітких узгоджених кадрів. */
        data object Unclear : Check
    }

    /**
     * Перевірка нового образу проти всіх наявних: спершу відкидаємо розмиті/випадкові кадри
     * (лишаються ті, що схожі між собою), потім кожен звіряємо з усіма відомими обличчями.
     */
    fun check(samples: List<FloatArray>): Check {
        val consistent = consistent(samples)
        if (consistent.size < MIN_CONSISTENT) return Check.Unclear
        // Суворіше за звичайне впізнавання: нове ім'я — лише якщо образ не схожий ні на кого навіть трохи.
        val best = consistent.mapNotNull { match(it, POSSIBLE_MATCH) }.maxByOrNull { it.second }
        return if (best != null) Check.LooksLike(best.first, best.second) else Check.New(consistent)
    }

    /** Кадри, схожі з більшістю інших (≥ [CONSISTENT]) — один і той самий чіткий образ. */
    private fun consistent(samples: List<FloatArray>): List<FloatArray> {
        if (samples.size <= 1) return samples
        return samples.filterIndexed { i, s ->
            val similar = samples.indices.count { j -> j != i && cosine(s, samples[j]) >= CONSISTENT }
            similar * 2 >= samples.size - 1
        }
    }

    /** Додати зразки обличчя; найстаріші відкидаються, щоб не роздувати файл. */
    fun remember(name: String, embeddings: List<FloatArray>) {
        if (embeddings.isEmpty()) return
        val samples = people.getOrPut(name) { mutableListOf() }
        samples += embeddings.map { normalize(it) }
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
        save()
    }

    /** Нове ім'я для тих самих облич (якщо таке ім'я вже є — зразки об'єднуються). */
    fun rename(old: String, new: String): Boolean {
        val samples = people.remove(old) ?: return false
        val target = people.getOrPut(new) { mutableListOf() }
        target += samples
        while (target.size > MAX_SAMPLES) target.removeAt(0)
        save()
        return true
    }

    /**
     * Обличчя [current] насправді — [name]. Зразки інших імен, дуже схожі на це обличчя,
     * переходять до [name] (жартівливе ім'я тієї самої людини зникає), а зразки іншої людини,
     * з якою просто сплутали, лишаються їй.
     */
    fun reassign(name: String, current: List<FloatArray>) {
        if (current.isEmpty()) return
        val target = people.getOrPut(name) { mutableListOf() }
        for ((other, samples) in people.entries.toList()) {
            if (other == name) continue
            val moved = samples.filter { s -> current.any { cosine(it, s) >= SAME_PERSON } }
            samples.removeAll(moved)
            target += moved
            if (samples.isEmpty()) people.remove(other)
        }
        target += current.map { normalize(it) }
        while (target.size > MAX_SAMPLES) target.removeAt(0)
        save()
    }

    fun forget(name: String): Boolean = (people.remove(name) != null).also { if (it) save() }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val json = JSONObject(f.readText())
            for (name in json.keys()) {
                val arr = json.getJSONArray(name)
                people[name] = MutableList(arr.length()) { i ->
                    val v = arr.getJSONArray(i)
                    FloatArray(v.length()) { v.getDouble(it).toFloat() }
                }
            }
        } catch (e: Exception) {
            people.clear() // зіпсований файл — краще забути, ніж впасти
        }
    }

    private fun save() {
        val f = file ?: return
        val json = JSONObject()
        people.forEach { (name, samples) ->
            json.put(name, JSONArray(samples.map { s -> JSONArray(s.map { it.toDouble() }) }))
        }
        f.writeText(json.toString())
    }

    companion object {
        /** Косинус 0.6 ≈ квадрат відстані 0.8 між нормованими векторами (поріг авторів моделі). */
        const val MATCH_THRESHOLD = 0.6f
        const val MAX_SAMPLES = 8
        /** Кадри одного образу мають бути схожі між собою щонайменше так. */
        const val CONSISTENT = 0.7f
        /** Поріг "можливо, та сама людина" для перевірки перед записом нового імені (впізнавання — 0.6). */
        const val POSSIBLE_MATCH = 0.5f
        const val MIN_CONSISTENT = 2
        /** Так схожі зразки — майже напевно та сама людина (поріг упізнавання — 0.6). */
        const val SAME_PERSON = 0.8f

        fun normalize(v: FloatArray): FloatArray {
            val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x }).coerceAtLeast(1e-10f)
            return FloatArray(v.size) { v[it] / norm }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            val na = normalize(a)
            val nb = normalize(b)
            return na.indices.fold(0f) { acc, i -> acc + na[i] * nb[i] }
        }
    }
}
