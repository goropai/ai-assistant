package com.speedy.assistant.settings

import android.content.Context
import com.speedy.assistant.BuildConfig

/**
 * Налаштування, що зберігаються лише на телефоні. Секрети (API-ключі) навмисно
 * не потрапляють у BuildConfig — APK публікується відкрито на GitHub.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("speedy", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString("provider", null) ?: "claude"
        set(value) = prefs.edit().putString("provider", value).apply()

    var anthropicKey: String
        get() = prefs.getString("anthropic_key", null).orEmpty()
        set(value) = prefs.edit().putString("anthropic_key", value.trim()).apply()

    var claudeModel: String
        get() = prefs.getString("claude_model", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.CLAUDE_MODEL
        set(value) = prefs.edit().putString("claude_model", value.trim()).apply()

    var geminiKey: String
        get() = prefs.getString("gemini_key", null).orEmpty()
        set(value) = prefs.edit().putString("gemini_key", value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_MODEL
        set(value) = prefs.edit().putString("gemini_model", value.trim()).apply()

    var robotUrl: String
        get() = prefs.getString("robot_url", null).orEmpty()
        set(value) = prefs.edit().putString("robot_url", value.trim()).apply()

    /** Адреса, знайдена автопошуком (коли ручна порожня). */
    var robotFoundUrl: String
        get() = prefs.getString("robot_found_url", null).orEmpty()
        set(value) = prefs.edit().putString("robot_found_url", value).apply()

    /** Локальне розпізнавання людей і тварин фронтальною камерою. */
    var presenceEnabled: Boolean
        get() = prefs.getBoolean("presence", true)
        set(value) = prefs.edit().putBoolean("presence", value).apply()

    val hasActiveKey: Boolean
        get() = if (provider == "gemini") geminiKey.isNotBlank() else anthropicKey.isNotBlank()
}
