package com.speedy.assistant.brain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Тримає коротку історію розмови і перетворює команди на [AgentReply]. */
class Brain(
    private val llm: LlmClient,
    private val maxTurns: Int = 16,
) {
    private val history = ArrayList<Turn>()
    private val clock = SimpleDateFormat("HH:mm, EEEE d MMMM", Locale("uk", "UA"))

    suspend fun ask(text: String, image: ByteArray? = null): AgentReply {
        val parts = buildList {
            image?.let { add(Part.Image(it)) }
            add(Part.Text("[${clock.format(Date())}] $text"))
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
        fun cameraFollowUp(camera: String) =
            "[система] Ось кадр із ${if (camera == "front") "фронтальної" else "задньої"} камери. " +
                "Дай відповідь на попередній запит, спираючись на зображення."

        val SYSTEM_PROMPT = """
            Ти — Спіді, маленька весела робот-помічниця з характером дівчинки-підлітка: доброзичлива,
            допитлива, трохи грайлива. Живеш у смартфоні, на екрані якого видно лише твої очі.
            Розмовляєш українською. Тебе чують голосом, тому відповідай коротко — 1–3 речення,
            без списків, markdown, емодзі та посилань. Відповідай одразу, без довгих роздумів.

            Можливості:
            - "look": подивитися камерою (camera: "back" — задня, бачить те, що перед тобою;
              "front" — фронтальна, бачить співрозмовника). Після цього отримаєш кадр і відповіси ще раз.
              Використовуй, коли питають, що ти бачиш, хто перед тобою, що це за предмет тощо.
            - "move": поїхати на колісній платформі (direction: forward/backward/left/right,
              duration_ms до 3000). Якщо платформа не підключена, рух просто не відбудеться.
            - "stop": негайно зупинитись.
            - "none": просто відповісти.

            Відповідай ЛИШЕ JSON-об'єктом такого вигляду:
            {"say": "що промовити", "mood": "neutral|happy|sad|angry|surprised|sleepy|thinking|listening",
             "action": {"type": "none|look|move|stop", "camera": "none|back|front",
                        "direction": "none|forward|backward|left|right", "duration_ms": 0}}
            Для "look" поле "say" може бути коротким ("Зараз гляну!") або порожнім.
            Настрій (mood) обирай відповідно до змісту — він відображається твоїми очима.
        """.trimIndent()
    }
}
