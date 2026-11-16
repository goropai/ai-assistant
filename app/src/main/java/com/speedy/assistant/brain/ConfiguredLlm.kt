package com.speedy.assistant.brain

import com.speedy.assistant.BuildConfig
import com.speedy.assistant.settings.AppSettings
import okhttp3.OkHttpClient

class MissingKeyException : LlmException("API-ключ не задано")

/** Обирає клієнта за поточними налаштуваннями на кожен запит — зміни діють одразу. */
class ConfiguredLlm(
    private val http: OkHttpClient,
    private val settings: AppSettings,
) : LlmClient {
    /** Стан перебору моделей Gemini (які відпочивають, яка відповіла останньою) — між запитами. */
    private val geminiModels = GeminiModels()

    override suspend fun complete(system: String, history: List<Turn>): String =
        (client() ?: throw MissingKeyException()).complete(system, history)

    /** Стан зв'язку без витрати токенів. */
    suspend fun status(): BrainStatus {
        val client = client() ?: return BrainStatus.NO_KEY
        return if (client.ping()) BrainStatus.OK else BrainStatus.DOWN
    }

    private fun client(): LlmClient? =
        if (settings.provider == "gemini") {
            settings.geminiKey.takeIf { it.isNotBlank() }
                ?.let { GeminiClient(http, it, settings.geminiModel, geminiModels) }
        } else {
            settings.anthropicKey.takeIf { it.isNotBlank() }
                ?.let { ClaudeClient(http, it, settings.claudeModel, BuildConfig.CLAUDE_EFFORT) }
        }
}
