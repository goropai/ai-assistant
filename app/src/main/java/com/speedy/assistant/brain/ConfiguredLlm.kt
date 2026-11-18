package com.speedy.assistant.brain

import android.util.Log
import com.speedy.assistant.BuildConfig
import com.speedy.assistant.settings.AppSettings
import okhttp3.OkHttpClient

class MissingKeyException : LlmException("API-ключ не задано")

/** Як поєднувати два ключі Gemini: з оплатою і безкоштовний. */
enum class GeminiKeyMode(val code: String, val ua: String) {
    PAID("paid", "лише ключ з оплатою"),
    FREE("free", "лише безкоштовний ключ"),
    FREE_THEN_PAID("free_then_paid", "безкоштовний, а коли вичерпано — з оплатою"),
    PAID_THEN_FREE("paid_then_free", "з оплатою, а після денного ліміту — безкоштовний");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: PAID
    }
}

/** Обирає клієнта за поточними налаштуваннями на кожен запит — зміни діють одразу. */
class ConfiguredLlm(
    private val http: OkHttpClient,
    private val settings: AppSettings,
) : LlmClient {
    /** Стан перебору моделей Gemini (які відпочивають, яка відповіла останньою) — окремо для кожного ключа. */
    private val paidModels = GeminiModels(load = { settings.geminiState }, save = { settings.geminiState = it })
    private val freeModels = GeminiModels(load = { settings.geminiFreeState }, save = { settings.geminiFreeState = it })
    /** Ключ вичерпав квоту — до коли не пробувати (потім перевіряємо знову). */
    private val exhaustedUntil = HashMap<Boolean, Long>()
    /** Яким ключем пішов останній запит — у журнал лише зміну. */
    private var lastUsed: String? = null

    private class Key(val free: Boolean, val client: LlmClient) {
        val label get() = if (free) "безкоштовний" else "з оплатою"
    }

    override suspend fun complete(system: String, history: List<Turn>): String =
        withKey(media = hasMedia(history)) { it.complete(system, history) }

    override suspend fun stream(system: String, history: List<Turn>, onDelta: (String) -> Unit): String =
        withKey(media = hasMedia(history)) { it.stream(system, history, onDelta) }

    override suspend fun search(query: String): String? = firstNonNull { it.search(query) }

    override suspend fun generateJson(system: String, prompt: String): String? = firstNonNull { it.generateJson(system, prompt) }

    /** Стан зв'язку без витрати токенів. */
    suspend fun status(): BrainStatus {
        val client = keys().firstOrNull()?.client ?: return BrainStatus.NO_KEY
        return if (client.ping()) BrainStatus.OK else BrainStatus.DOWN
    }

    /** Ключі за порядком режиму; Claude — один. */
    private fun keys(): List<Key> {
        if (settings.provider != "gemini") {
            return listOfNotNull(settings.anthropicKey.takeIf { it.isNotBlank() }
                ?.let { Key(false, ClaudeClient(http, it, settings.claudeModel, BuildConfig.CLAUDE_EFFORT)) })
        }
        val paid = settings.geminiKey.takeIf { it.isNotBlank() }?.let { Key(false, GeminiClient(http, it, settings.geminiModel, paidModels)) }
        val free = settings.geminiFreeKey.takeIf { it.isNotBlank() }?.let { Key(true, GeminiClient(http, it, settings.geminiModel, freeModels)) }
        return when (GeminiKeyMode.of(settings.geminiKeyMode)) {
            GeminiKeyMode.PAID -> listOfNotNull(paid)
            GeminiKeyMode.FREE -> listOfNotNull(free)
            GeminiKeyMode.FREE_THEN_PAID -> listOfNotNull(free, paid)
            GeminiKeyMode.PAID_THEN_FREE -> listOfNotNull(paid, free)
        }
    }

    /**
     * Перший ключ, що не вичерпав квоту; вичерпав (усі моделі — 429) — наступний, а цей перевіримо через
     * [RECHECK_MS]. Фото й звук — не через безкоштовний ключ, якщо є з оплатою і так налаштовано.
     */
    private suspend fun <T> withKey(media: Boolean, block: suspend (LlmClient) -> T): T {
        val all = keys().ifEmpty { throw MissingKeyException() }
        val now = System.currentTimeMillis()
        val private = media && settings.geminiFreeNoMedia && all.any { !it.free }
        val allowed = all.filter { !(private && it.free) }
        val fresh = allowed.filter { (exhaustedUntil[it.free] ?: 0) <= now }.ifEmpty { allowed }
        var last: LlmException? = null
        for (key in fresh) {
            if (key.label != lastUsed) {
                Log.i(TAG, "ключ: ${key.label} (режим ${settings.geminiKeyMode}${if (private) ", є фото чи звук" else ""})")
                lastUsed = key.label
            }
            try {
                return block(key.client).also { exhaustedUntil.remove(key.free) }
            } catch (e: GeminiClient.GeminiQuotaException) {
                Log.w(TAG, "ключ ${key.label}: квоту вичерпано — ${if (key != fresh.last()) "перемикаюсь" else "інших ключів немає"}")
                exhaustedUntil[key.free] = now + RECHECK_MS
                last = e
            } catch (e: MissingKeyException) {
                throw e
            } catch (e: LlmException) {
                // Жодна модель цього ключа не відповіла (таймаути, перевантаження) — пробуємо інший ключ,
                // але вичерпаним цей не вважаємо.
                if (key == fresh.last()) throw e
                Log.w(TAG, "ключ ${key.label}: не відповів — пробую інший")
                last = e
            }
        }
        throw last ?: LlmException("Ключ з оплатою вичерпано, а фото й звук безкоштовним ключем не надсилаю")
    }

    private suspend fun <T> firstNonNull(block: suspend (LlmClient) -> T?): T? {
        val now = System.currentTimeMillis()
        val all = keys()
        for (key in all.filter { (exhaustedUntil[it.free] ?: 0) <= now }.ifEmpty { all }) block(key.client)?.let { return it }
        return null
    }

    /** Фото чи звук будь-де в розмові (історія надсилається щоразу — і старі фото теж). */
    private fun hasMedia(history: List<Turn>) = history.any { t -> t.parts.any { it is Part.Image || it is Part.Audio } }

    private companion object {
        const val TAG = "ConfiguredLlm"
        /** Вичерпаний ключ пробуємо знову не раніше (хвилинні ліміти минають швидко, денні — ні). */
        const val RECHECK_MS = 15 * 60_000L
    }
}
