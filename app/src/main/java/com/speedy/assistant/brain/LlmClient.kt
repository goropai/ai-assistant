package com.speedy.assistant.brain

import java.io.IOException

enum class Role { USER, ASSISTANT }

sealed interface Part {
    data class Text(val text: String) : Part
    class Image(val jpeg: ByteArray) : Part
}

data class Turn(val role: Role, val parts: List<Part>)

open class LlmException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Чи є зв'язок із "мозком" — показується крапкою внизу екрана. */
enum class BrainStatus { UNKNOWN, OK, DOWN, NO_KEY }

/** Зовнішня потужна модель, що відповідає JSON-рядком формату [AgentReply]. */
interface LlmClient {
    suspend fun complete(system: String, history: List<Turn>): String

    /** Перевірка зв'язку без витрати токенів (список моделей). true — сервіс відповідає. */
    suspend fun ping(): Boolean = true
}
