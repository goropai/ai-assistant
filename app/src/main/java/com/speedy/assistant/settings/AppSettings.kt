package com.speedy.assistant.settings

import android.content.Context
import com.speedy.assistant.BuildConfig

/**
 * Налаштування, що зберігаються лише на телефоні. Секрети (API-ключі) навмисно
 * не потрапляють у BuildConfig — APK публікується відкрито на GitHub.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("speedy", Context.MODE_PRIVATE)

    init {
        // Одразу, до читання полів ключів (інакше діалог налаштувань показав би ключ у старому полі).
        if (!prefs.contains("gemini_key_mode")) migrateGeminiKey()
    }

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

    /** Безкоштовний ключ Gemini (запити можуть іти на навчання моделей Google). */
    var geminiFreeKey: String
        get() = prefs.getString("gemini_free_key", null).orEmpty()
        set(value) = prefs.edit().putString("gemini_free_key", value.trim()).apply()

    var geminiFreeState: String?
        get() = prefs.getString("gemini_free_state", null)
        set(value) = prefs.edit().putString("gemini_free_state", value).apply()

    /** Як поєднувати ключі (див. GeminiKeyMode). */
    var geminiKeyMode: String
        get() = prefs.getString("gemini_key_mode", null) ?: migrateGeminiKey()
        set(value) = prefs.edit().putString("gemini_key_mode", value).apply()

    /**
     * До двох ключів був один — безкоштовний: переносимо його в поле безкоштовного (разом зі станом
     * моделей) і режим "лише безкоштовний"; ключ з оплатою людина додасть сама.
     */
    private fun migrateGeminiKey(): String {
        val old = prefs.getString("gemini_key", null).orEmpty()
        val mode = if (old.isNotBlank() && geminiFreeKey.isBlank()) "free" else "paid"
        prefs.edit().apply {
            if (mode == "free") {
                putString("gemini_free_key", old).remove("gemini_key")
                putString("gemini_free_state", prefs.getString("gemini_state", null)).remove("gemini_state")
            }
            putString("gemini_key_mode", mode)
        }.apply()
        return mode
    }

    /** Фото й звук — лише через ключ з оплатою (коли він є). */
    var geminiFreeNoMedia: Boolean
        get() = prefs.getBoolean("gemini_free_no_media", true)
        set(value) = prefs.edit().putBoolean("gemini_free_no_media", value).apply()

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

    /** Раз на 2 тижні фото знайомого — моделі, щоб коротко запам'ятати зовнішність (окуляри, волосся). */
    var appearanceEnabled: Boolean
        get() = prefs.getBoolean("appearance", true)
        set(value) = prefs.edit().putBoolean("appearance", value).apply()

    /** Час від часу цікаві факти під інтереси знайомих. */
    var factsEnabled: Boolean
        get() = prefs.getBoolean("facts", true)
        set(value) = prefs.edit().putBoolean("facts", value).apply()

    // --- вібрація (кожна — окремо) ---
    /** Нагадування вночі / в нічному режимі — вібрацією замість голосу (скаже після дотику). */
    var vibrateQuiet: Boolean
        get() = prefs.getBoolean("vibrate_quiet", true)
        set(value) = prefs.edit().putBoolean("vibrate_quiet", value).apply()

    /** "Спіді, де ти?" — вібрація разом зі звуком. */
    var vibrateFind: Boolean
        get() = prefs.getBoolean("vibrate_find", true)
        set(value) = prefs.edit().putBoolean("vibrate_find", value).apply()

    /** Коротка вібрація на дотик і на звертання "Спіді". */
    var vibrateTouch: Boolean
        get() = prefs.getBoolean("vibrate_touch", true)
        set(value) = prefs.edit().putBoolean("vibrate_touch", value).apply()

    /** Легка вібрація на правильну відповідь в уроці. */
    var vibrateCheer: Boolean
        get() = prefs.getBoolean("vibrate_cheer", true)
        set(value) = prefs.edit().putBoolean("vibrate_cheer", value).apply()

    // --- режим сторожа: куди слати тривогу (канал діє, якщо заповнений) ---
    private fun str(key: String) = prefs.getString(key, null).orEmpty()
    private fun put(key: String, value: String) = prefs.edit().putString(key, value.trim()).apply()

    var guardTelegramToken: String get() = str("guard_tg_token"); set(v) = put("guard_tg_token", v)
    var guardTelegramChat: String get() = str("guard_tg_chat"); set(v) = put("guard_tg_chat", v)
    /** Пошта: сервер SMTP (порт 465 — SSL, 587 — STARTTLS), логін, пароль застосунку, кому. */
    var guardSmtpHost: String get() = str("guard_smtp_host"); set(v) = put("guard_smtp_host", v)
    var guardSmtpPort: Int
        get() = prefs.getInt("guard_smtp_port", 465)
        set(value) = prefs.edit().putInt("guard_smtp_port", value).apply()
    var guardSmtpUser: String get() = str("guard_smtp_user"); set(v) = put("guard_smtp_user", v)
    var guardSmtpPassword: String get() = str("guard_smtp_pass"); set(v) = put("guard_smtp_pass", v)
    var guardEmailTo: String get() = str("guard_email_to"); set(v) = put("guard_email_to", v)
    var guardSmsNumber: String get() = str("guard_sms"); set(v) = put("guard_sms", v)
    var guardCallNumber: String get() = str("guard_call"); set(v) = put("guard_call", v)

    /** Тривога на звуки: чужий голос, двері, стук, скло, сигналізація. */
    var guardSounds: Boolean
        get() = prefs.getBoolean("guard_sounds", true)
        set(value) = prefs.edit().putBoolean("guard_sounds", value).apply()

    /** Тривога на рух, у якому камера не впізнала ні людини, ні тварини. */
    var guardMotion: Boolean
        get() = prefs.getBoolean("guard_motion", true)
        set(value) = prefs.edit().putBoolean("guard_motion", value).apply()

    /** Хвилин між тривогами (менше — не надсилаємо знову, лише записуємо). */
    var guardCooldownMinutes: Int
        get() = prefs.getInt("guard_cooldown", 5)
        set(value) = prefs.edit().putInt("guard_cooldown", value.coerceIn(1, 120)).apply()

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
        get() = if (provider == "gemini") when (geminiKeyMode) {
            "free" -> geminiFreeKey.isNotBlank()
            "paid" -> geminiKey.isNotBlank()
            else -> geminiKey.isNotBlank() || geminiFreeKey.isNotBlank()
        } else anthropicKey.isNotBlank()
}
