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

    /** Додати зразки обличчя; найстаріші відкидаються, щоб не роздувати файл. */
    fun remember(name: String, embeddings: List<FloatArray>) {
        if (embeddings.isEmpty()) return
        val samples = people.getOrPut(name) { mutableListOf() }
        samples += embeddings.map { normalize(it) }
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
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
