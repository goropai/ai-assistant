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

    override suspend fun complete(system: String, history: List<Turn>): String {
        val client = if (settings.provider == "gemini") {
            settings.geminiKey.takeIf { it.isNotBlank() }
                ?.let { GeminiClient(http, it, settings.geminiModel) }
        } else {
            settings.anthropicKey.takeIf { it.isNotBlank() }
                ?.let { ClaudeClient(http, it, settings.claudeModel, BuildConfig.CLAUDE_EFFORT) }
        }
        return (client ?: throw MissingKeyException()).complete(system, history)
    }
}
