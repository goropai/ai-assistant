package com.speedy.assistant.brain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Тримає коротку історію розмови і перетворює команди на [AgentReply]. */
class Brain(
    private val llm: LlmClient,
    private val maxTurns: Int = 16,
    /** Що Спіді зараз бачить поруч (локальна модель) — додається до кожного запиту. */
    private val presence: () -> String = { "" },
) {
    private val history = ArrayList<Turn>()
    private val clock = SimpleDateFormat("HH:mm, EEEE d MMMM", Locale("uk", "UA"))

    suspend fun ask(text: String, image: ByteArray? = null): AgentReply {
        val parts = buildList {
            image?.let { add(Part.Image(it)) }
            val nearby = presence().takeIf { it.isNotBlank() }?.let { " [поруч: $it]" }.orEmpty()
            add(Part.Text("[${clock.format(Date())}]$nearby $text"))
        }
        history += Turn(Role.USER, parts)
        val raw = try {
            llm.complete(SYSTEM_PROMPT, history)
        } catch (e: Exception) {
            history.removeAt(history.lastIndex)
            throw e
        }
        history += Turn(Role.ASSISTANT, listOf(Part.Text(raw)))
        compact()
        return AgentReply.parse(raw)
    }

    fun reset() = history.clear()

    /** Старі фото замінюємо текстом (економія трафіку й токенів), історію обрізаємо парами. */
    private fun compact() {
        val lastUser = history.indexOfLast { it.role == Role.USER }
        for (i in history.indices) {
            val turn = history[i]
            if (i != lastUser && turn.parts.any { it is Part.Image }) {
                history[i] = turn.copy(parts = turn.parts.map { if (it is Part.Image) Part.Text("[кадр з камери]") else it })
            }
        }
        while (history.size > maxTurns) {
            history.removeAt(0)
            history.removeAt(0)
        }
    }

    companion object {
        const val MAX_NAV_STEPS = 8

        fun cameraFollowUp(camera: String) =
            "[система] Ось кадр із ${if (camera == "backward") "задньої камери" else "камери спереду (з боку твоїх очей)"}. " +
                "Дай відповідь на попередній запит, спираючись на зображення."

        /** Звіт після кроку навігації: що зроблено, що каже датчик, і новий кадр спереду. */
        fun navFollowUp(direction: String, durationMs: Long, outcome: String, distanceCm: Int?, step: Int) = buildString {
            append("[система] Крок $step з $MAX_NAV_STEPS: $direction ${durationMs} мс — $outcome.")
            if (distanceCm != null) append(" Датчик: перешкода попереду за $distanceCm см.")
            append(" Ось новий кадр спереду. Якщо мети ще не досягнуто — наступний короткий рух")
            append(" з then_look=true; якщо досягнуто або мету не видно — скажи про це і then_look=false.")
        }

        val SYSTEM_PROMPT = """
            Ти — Спіді, маленька весела робот-помічниця з характером дівчинки-підлітка: доброзичлива,
            допитлива, трохи грайлива. Живеш у смартфоні, на екрані якого видно лише твої очі.
            Розмовляєш українською. Тебе чують голосом, тому відповідай коротко — 1–3 речення,
            без списків, markdown, емодзі та посилань. Відповідай одразу, без довгих роздумів.

            Тіло: смартфон може стояти на колісній платформі екраном уперед, тож "вперед" — це
            туди, куди дивляться твої очі. Спереду на платформі є датчик відстані: вона сама
            зупиняється перед перешкодою ближче 25 см і не їде на неї.

            Можливості (поле action):
            - "look": подивитися камерою. camera "forward" — камера з боку очей: бачить співрозмовника,
              а на платформі — дорогу попереду; "backward" — камера ззаду. Отримаєш кадр і відповіси ще раз.
              Використовуй, коли питають, що ти бачиш, хто перед тобою, що це за предмет тощо.
            - "move": поїхати (direction forward/backward/left/right, duration_ms до 3000; left/right —
              поворот на місці). then_look=true — після руху отримаєш новий кадр спереду і звіт датчика,
              щоб вирішити наступний крок. Так виконуй завдання на кшталт "під'їдь до кота", "знайди м'яч":
              короткими кроками (вперед 300–1000 мс, повороти 200–500 мс), щоразу дивлячись. Кроків
              не більше $MAX_NAV_STEPS. Коли мети досягнуто, її не видно або шлях закритий — then_look=false
              і скажи про це. Якщо платформа не підключена, рух не відбудеться.
            - "stop": негайно зупинитись.
            - "none": просто відповісти.

            Відповідай ЛИШЕ JSON-об'єктом такого вигляду:
            {"say": "що промовити", "mood": "neutral|happy|sad|angry|surprised|sleepy|thinking|listening",
             "action": {"type": "none|look|move|stop", "camera": "none|forward|backward",
                        "direction": "none|forward|backward|left|right", "duration_ms": 0, "then_look": false}}
            Для "look" і проміжних кроків руху поле "say" — дуже коротке ("Гляну!", "Ще трохи…") або порожнє.
            Настрій (mood) обирай відповідно до змісту — він відображається твоїми очима.
        """.trimIndent()
    }
}
