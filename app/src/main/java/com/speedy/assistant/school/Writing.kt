package com.speedy.assistant.school

import com.speedy.assistant.english.LessonIO

/** Що вміє екран для письма: показати обрис і дочекатися, поки його обведуть пальцем. */
interface Tracer {
    /**
     * Показати обрис [target] ("shape:circle" чи символ "А", "7") пунктиром; true — обвели (≥ 90 %),
     * false — не торкались [idleMs] чи урок перервали.
     */
    suspend fun trace(target: String, idleMs: Long): Boolean
    fun hide()
}

/**
 * Урок письма (без моделі): 5 обрисів за рівнем — 1: фігури, 2: цифри, 3: великі літери, 4: малі літери.
 * Обвела — похвала; двічі поспіль не торкалась — кінець. Рівень росте від 80 % обведених.
 */
class WritingLesson(
    private val io: LessonIO,
    private val tracer: Tracer,
    private val progress: SchoolProgress,
    private val name: String,
    private val now: () -> Long,
    private val random: kotlin.random.Random = kotlin.random.Random,
) {
    var result: String = ""
        private set

    suspend fun run() {
        val level = progress.level(name, Subject.WRITING).coerceIn(1, LEVELS.size)
        val pool = LEVELS[level - 1]
        val items = pool.shuffled(random).take(ITEMS)
        io.say(INTRO[level - 1])
        var done = 0
        var total = 0
        var idle = 0
        val missed = mutableListOf<String>()
        try {
            for (item in items) {
                io.say(ask(item))
                total++
                if (tracer.trace(item, IDLE_MS)) {
                    done++
                    idle = 0
                    io.cheer()
                    io.say(listOf("Чудово!", "Молодець!", "Гарно вийшло!", "Супер!").random(random))
                } else {
                    missed += item
                    if (++idle >= 2) { io.say("Здається, ти втомився. Допишемо іншим разом!"); break }
                    io.say("Нічого, спробуємо наступну.")
                }
            }
        } finally {
            tracer.hide()
        }
        if (total == 0) return
        val newLevel = progress.finish(name, Subject.WRITING, TOPICS[level - 1], done, total, missed, now())
        val parts = mutableListOf("Обвели $done з $total!")
        if (newLevel > level) parts += "Наступного разу — ${TOPICS[newLevel - 1]}."
        result = parts.joinToString(" ")
        io.say(result)
    }

    private fun ask(item: String): String = when {
        item.startsWith("shape:") -> "Обведи пальчиком ${SHAPES[item.removePrefix("shape:")]}."
        item.first().isDigit() -> "Обведи пальчиком цифру $item."
        else -> "Обведи пальчиком букву ${item}."
    }

    companion object {
        const val ITEMS = 5
        const val IDLE_MS = 30_000L
        private val SHAPES = mapOf(
            "circle" to "коло", "square" to "квадрат", "triangle" to "трикутник", "star" to "зірочку", "heart" to "сердечко",
        )
        val LEVELS: List<List<String>> = listOf(
            SHAPES.keys.map { "shape:$it" },
            (0..9).map { it.toString() },
            "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ".map { it.toString() },
            "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя".map { it.toString() },
        )
        private val TOPICS = listOf("фігури", "цифри", "великі літери", "малі літери")
        private val INTRO = listOf(
            "Будемо малювати фігури! Веди пальчиком по пунктиру.",
            "Будемо писати цифри! Веди пальчиком по пунктиру.",
            "Будемо писати великі літери! Веди пальчиком по пунктиру.",
            "Будемо писати маленькі літери! Веди пальчиком по пунктиру.",
        )
    }
}
