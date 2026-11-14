package com.speedy.assistant.brain

import java.io.IOException

enum class Role { USER, ASSISTANT }

sealed interface Part {
    data class Text(val text: String) : Part
    class Image(val jpeg: ByteArray) : Part
}

data class Turn(val role: Role, val parts: List<Part>)

class LlmException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Зовнішня потужна модель, що відповідає JSON-рядком формату [AgentReply]. */
interface LlmClient {
    suspend fun complete(system: String, history: List<Turn>): String
}
