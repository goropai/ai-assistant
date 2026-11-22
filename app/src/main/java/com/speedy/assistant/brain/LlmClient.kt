package com.speedy.assistant.brain

import java.io.IOException

enum class Role { USER, ASSISTANT }

sealed interface Part {
    data class Text(val text: String) : Part
    class Image(val jpeg: ByteArray) : Part
    /** Звук навколо (WAV) — чує лише Gemini; Claude отримує замість нього примітку. */
    class Audio(val wav: ByteArray) : Part
}

data class Turn(val role: Role, val parts: List<Part>)

open class LlmException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Чи є зв'язок із "мозком" — показується крапкою внизу екрана. */
enum class BrainStatus { UNKNOWN, OK, DOWN, NO_KEY }

/** Зовнішня потужна модель, що відповідає JSON-рядком формату [AgentReply]. */
interface LlmClient {
    suspend fun complete(system: String, history: List<Turn>): String

    /** Те саме, але текст віддається шматками в [onDelta] по мірі генерації. */
    suspend fun stream(system: String, history: List<Turn>, onDelta: (String) -> Unit): String =
        complete(system, history).also(onDelta)

    /** Довільний JSON за інструкцією (теми уроків, огляд кімнати з [image]); null — не вміє / не вдалось. */
    suspend fun generateJson(system: String, prompt: String, image: ByteArray? = null): String? = null

    /** Пошук в інтернеті засобами моделі; коротка текстова відповідь або null (не вміє / не вдалось). */
    suspend fun search(query: String): String? = null

    /** Довгий текст з інтернету за інструкцією [prompt] (казка, вірш); null — не вміє / не вдалось. */
    suspend fun findText(prompt: String): String? = null

    /** Адреси сторінок, які знайшов пошук моделі за [query] (найкращі перші); порожньо — не вміє / не вдалось. */
    suspend fun findPages(query: String): List<String> = emptyList()

    /** Перевірка зв'язку без витрати токенів (список моделей). true — сервіс відповідає. */
    suspend fun ping(): Boolean = true
}

/** Завдання для пошукового запиту: коротко, фактами, українською — далі це читає основна модель. */
fun searchPrompt(query: String) =
    "Знайди в інтернеті актуальну відповідь на запит: \"$query\". " +
        "Відповідай українською, 3–6 речень, лише факти: цифри, дати, імена, джерело. Без вступів."

/** Завдання для пошуку сторінок: лише знайти (адреси беремо з результатів пошуку, не з відповіді). */
fun pagesPrompt(query: String) =
    "Знайди в інтернеті сторінки з повним текстом: \"$query\". Відповідай одним реченням."
