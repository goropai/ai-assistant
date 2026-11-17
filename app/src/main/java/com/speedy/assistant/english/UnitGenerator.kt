package com.speedy.assistant.english

import com.speedy.assistant.util.writeTextAtomic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Теми понад вбудовані 20: модель складає нову тему один раз на рівень, тема зберігається
 * (files/english_units.json) і дістається всім учням цього рівня без нових запитів.
 */
class UnitStore(private val file: File?) {
    private val units = mutableListOf<Unit>()
    private val raw = JSONArray()

    init {
        file?.takeIf { it.exists() }?.let {
            try {
                val arr = JSONArray(it.readText())
                for (i in 0 until arr.length()) raw.put(arr.getJSONObject(i))
                units += Curriculum.parse(arr.toString()).let { c -> Level.entries.flatMap { l -> c.units(l) } }
            } catch (e: Exception) {
                units.clear()
            }
        }
    }

    @Synchronized
    fun units(level: Level): List<Unit> = units.filter { it.level == level }

    @Synchronized
    fun add(json: JSONObject, unit: Unit) {
        units += unit
        raw.put(json)
        file?.writeTextAtomic(raw.toString())
    }
}

object UnitGenerator {
    private val levelText = mapOf(
        Level.KIDS to "дитина 5–8 років, зовсім початковий рівень (pre-A1): прості слова, ігрові фрази, дуже коротко",
        Level.A1 to "дорослий початківець (A1)",
        Level.A2 to "дорослий, рівень A2 (елементарний)",
        Level.B1 to "дорослий, рівень B1 (середній)",
    )

    const val SYSTEM = "Ти методист англійської для україномовних. Складаєш короткі голосові уроки. " +
        "Відповідай ЛИШЕ одним JSON-об'єктом без markdown."

    /** Запит на нову тему: суворий шаблон + уже пройдені теми, щоб не повторюватись. */
    fun prompt(level: Level, done: List<String>): String = """
        Склади нову тему уроку англійської. Учень: ${levelText[level]}.
        Уже пройдені теми (не повторюй): ${done.joinToString("; ")}.
        Формат:
        {"level":"${level.code}","title":"коротка назва українською",
         "theory":"1–3 речення українською, англійські приклади у фігурних дужках, напр. {I can swim}",
         "phrases":[{"en":"коротка англійська фраза","uk":"переклад"}, …рівно 3],
         "dialogue":[{"q":"питання англійською","hint":"те саме питання українською","expect":["ключові слова правильної відповіді англійською, малими літерами", …3–8],"sample":"зразок відповіді"}, …рівно 2]}
        Фрази короткі (до 8 слів), для вимови вголос. Без цифр у фразах.
    """.trimIndent()

    /** Перевірка відповіді моделі: валідний шаблон — тема, інакше null. */
    fun parse(text: String, level: Level): Pair<JSONObject, Unit>? = try {
        val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).put("level", level.code)
        val unit = Curriculum.parse(JSONArray().put(json).toString()).units(level).single()
        val ok = unit.title.isNotBlank() && unit.theory.isNotBlank() && unit.phrases.size in 2..5 &&
            unit.dialogue.size in 1..4 && unit.dialogue.all { it.expect.isNotEmpty() && it.sample.isNotBlank() } &&
            unit.phrases.all { it.en.isNotBlank() && it.uk.isNotBlank() }
        if (ok) json to unit else null
    } catch (e: Exception) {
        null
    }
}
