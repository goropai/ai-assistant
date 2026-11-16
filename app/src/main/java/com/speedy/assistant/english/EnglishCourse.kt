package com.speedy.assistant.english

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Усе для уроків англійської: словник, теми, прогрес учнів, складені моделлю теми. */
class EnglishCourse(
    context: Context,
    /** Запит до "мозку" за JSON (для нових тем); null — не вдалось. */
    private val generateJson: suspend (system: String, prompt: String) -> String?,
) {
    val vocabulary = Vocabulary.parse(context.assets.open("english/words.json").bufferedReader().readText())
    val curriculum = Curriculum.parse(context.assets.open("english/lessons.json").bufferedReader().readText())
    val progress = EnglishProgress(File(context.filesDir, "english.json"))
    val store = UnitStore(File(context.filesDir, "english_units.json"))

    /** Нова тема від моделі — лише після перевірки шаблону. */
    val generate: suspend (Level, List<String>) -> Pair<JSONObject, Unit>? = { level, done ->
        val text = generateJson(UnitGenerator.SYSTEM, UnitGenerator.prompt(level, done))
        text?.let { UnitGenerator.parse(it, level) }.also {
            Log.i("EnglishCourse", if (it != null) "нова тема: ${it.second.title}" else "тему скласти не вдалось")
        }
    }

    /** "Як мій прогрес в англійській?" — без моделі. */
    fun describe(name: String): String {
        val l = progress.learner(name)
        val level = l.level ?: return "Ми ще не займались. Скажи «проведи урок англійської»!"
        val parts = mutableListOf("Твій рівень — ${level.ua}.")
        parts += "Пройдено тем: ${progress.unitIndex(name, level)}."
        parts += "Слів вивчено: ${l.known}."
        if (l.lessons.isNotEmpty()) {
            parts += "Уроків: ${l.lessons.size}."
            val last = l.lessons.last()
            parts += "Останній — ${last.score} з ${last.total}" + (last.pronunciation?.let { ", вимова $it зі ста." } ?: ".")
        }
        return parts.joinToString(" ")
    }
}
