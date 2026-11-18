package com.speedy.assistant.brain

import com.speedy.assistant.util.writeTextAtomic

import org.json.JSONArray
import java.io.File

/**
 * Довготривала пам'ять Спіді: короткі факти про людей і дім ("Тарас любить каву").
 * Модель сама вирішує, що варто запам'ятати (поле "remember"); факти додаються до
 * системного промпту. Він змінюється лише з новим фактом — кеш промпта майже не скидається.
 * Лише на телефоні (files/memory.json).
 */
class Memory(private val file: File?) {
    private val facts = mutableListOf<String>()

    init {
        file?.takeIf { it.exists() }?.let {
            try {
                val arr = JSONArray(it.readText())
                for (i in 0 until arr.length()) facts += arr.getString(i)
            } catch (e: Exception) {
                facts.clear()
            }
        }
    }

    @Synchronized
    fun all(): List<String> = facts.toList()

    /** @return true, якщо це новий факт. */
    @Synchronized
    fun add(fact: String): Boolean {
        val f = fact.trim().trimEnd('.').take(MAX_LENGTH)
        if (f.length < 3 || facts.any { it.equals(f, ignoreCase = true) }) return false
        facts += f
        while (facts.size > MAX_FACTS) facts.removeAt(0) // найстаріші забуваються
        file?.writeTextAtomic(JSONArray(facts).toString())
        return true
    }

    /** Прибрати факти, у яких є всі [words] (людина сказала "забудь, що …"); скільки прибрано. */
    @Synchronized
    fun forget(words: Set<String>): Int {
        val before = facts.size
        facts.removeAll { f -> forgettable(f, words) }
        if (facts.size != before) file?.writeTextAtomic(JSONArray(facts).toString())
        return before - facts.size
    }

    @Synchronized
    fun clear() {
        facts.clear()
        file?.delete()
    }

    companion object {
        /**
         * Слова, що визначають, про що спогад ("drum and bass" → drum, bass): без коротких службових і без
         * дієслів ставлення ("люблю", "любить" — у факті інша форма).
         */
        fun keywords(text: String): Set<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}'’-]+"))
                .filter { it.length >= 3 && it !in STOP }
                .map { it.take(5) } // основа: "барабани" ~ "барабанів"
                .toSet()

        /** Факт про те саме: містить усі ключові слова (за основою). */
        fun forgettable(fact: String, words: Set<String>): Boolean = words.isNotEmpty() && keywords(fact).containsAll(words)

        private val STOP = setOf("що", "про", "мене", "мені", "мій", "моя", "моє", "мої", "мого", "люблю", "любить",
            "любиш", "любив", "любила", "подобається", "подобаються", "мого", "його", "її", "and", "the", "тобі", "тебе",
            "дуже", "також", "теж", "улюблений", "улюблена", "улюблене", "улюблені", "слухаю", "слухає", "слухати", "той", "те", "це")

        const val MAX_FACTS = 40
        const val MAX_LENGTH = 160
    }
}
