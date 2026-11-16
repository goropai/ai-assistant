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

    /** Стан перебору моделей Gemini (JSON) — щоб не перебирати все наново після перезапуску. */
    var geminiState: String?
        get() = prefs.getString("gemini_state", null)
        set(value) = prefs.edit().putString("gemini_state", value).apply()

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

    // --- PIN батьків (лише хеш із сіллю) ---
    val hasPin: Boolean get() = prefs.getString("pin_hash", null) != null

    fun setPin(pin: String) {
        val salt = Pin.newSalt()
        prefs.edit().putString("pin_salt", salt).putString("pin_hash", Pin.hash(pin, salt)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", null) ?: return false
        val hash = prefs.getString("pin_hash", null) ?: return false
        return Pin.matches(pin, salt, hash)
    }

    /** Хвилин розмови на день для дитини; 0 — без обмеження. */
    var childLimitMinutes: Int
        get() = prefs.getInt("child_limit_min", 15)
        set(value) = prefs.edit().putInt("child_limit_min", value.coerceIn(0, 600)).apply()

    /** Облік часу дітей сьогодні (JSON, див. ChildLimit). */
    var childUsage: String?
        get() = prefs.getString("child_usage", null)
        set(value) = prefs.edit().putString("child_usage", value).apply()

    val hasActiveKey: Boolean
        get() = if (provider == "gemini") geminiKey.isNotBlank() else anthropicKey.isNotBlank()
}
