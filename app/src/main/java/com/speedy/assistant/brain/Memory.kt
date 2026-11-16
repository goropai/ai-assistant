package com.speedy.assistant.brain

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
        file?.writeText(JSONArray(facts).toString())
        return true
    }

    @Synchronized
    fun clear() {
        facts.clear()
        file?.delete()
    }

    companion object {
        const val MAX_FACTS = 40
        const val MAX_LENGTH = 160
    }
}
