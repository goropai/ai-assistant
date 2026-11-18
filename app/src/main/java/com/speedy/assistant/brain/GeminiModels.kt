package com.speedy.assistant.brain

/**
 * Перебір моделей Gemini: у кожної моделі свої ліміти, тож коли одна вичерпала квоту
 * чи перевантажена, інша часто ще відповідає. Список моделей береться з API (ListModels)
 * для конкретного ключа — назви не вшиті в код і не застаріють.
 * Стан живе між запитами: модель, що "відпочиває", пропускаємо; остання, що відповіла, — перша.
 */
class GeminiModels(
    /** Збережений стан (остання робоча модель, хто відпочиває) — переживає перезапуск. */
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
) {
    enum class Failure(val cooldownMs: Long) {
        /** 429: вичерпано квоту моделі. */
        RATE_LIMIT(10 * 60_000L),
        /** 500/503 або не встигла відповісти. */
        UNAVAILABLE(2 * 60_000L),
        /** 404: модель прибрали або вона недоступна для ключа. */
        NOT_FOUND(24 * 60 * 60_000L),
    }

    private val resting = HashMap<String, Long>()
    private var lastGood: String? = null
    var available: List<String> = emptyList()
        private set
    var listedAt = Long.MIN_VALUE / 2
        private set

    init {
        try {
            load()?.let { org.json.JSONObject(it) }?.let { json ->
                lastGood = json.optString("lastGood").ifBlank { null }
                json.optJSONObject("resting")?.let { r -> r.keys().forEach { resting[it] = r.getLong(it) } }
            }
        } catch (e: Exception) {
            // зіпсований стан — почнемо з чистого
        }
    }

    private fun persist() {
        save(
            org.json.JSONObject()
                .put("lastGood", lastGood.orEmpty())
                .put("resting", org.json.JSONObject(resting.mapValues { it.value }))
                .toString()
        )
    }

    @Synchronized
    fun setAvailable(models: List<String>, now: Long) {
        available = models.filter(::suitable)
        listedAt = now
    }

    /** Порядок спроб для одного запиту (не більше [max]). */
    @Synchronized
    fun order(configured: String, now: Long, max: Int = 4): List<String> {
        val preferred = available.sortedBy(::rank)
        // Остання модель, що відповіла, — першою: налаштована могла зависати чи зникнути.
        return (listOfNotNull(lastGood, configured) + preferred)
            .distinct()
            .filter { (resting[it] ?: Long.MIN_VALUE) <= now }
            .ifEmpty { listOf(configured) } // усі відпочивають — пробуємо налаштовану
            .take(max)
    }

    @Synchronized
    fun failed(model: String, failure: Failure, now: Long) {
        resting[model] = now + failure.cooldownMs
        if (lastGood == model) lastGood = null
        persist()
    }

    @Synchronized
    fun succeeded(model: String) {
        val changed = lastGood != model || resting.containsKey(model)
        resting.remove(model)
        lastGood = model
        if (changed) persist()
    }

    companion object {
        const val LIST_TTL_MS = 12 * 60 * 60_000L

        /** Лише текстові моделі Gemini, що вміють generateContent із JSON-відповіддю. */
        fun suitable(name: String): Boolean {
            val n = name.lowercase()
            if (!n.startsWith("gemini")) return false // gemma та інші — без systemInstruction/JSON
            // transcribe — розпізнавач мовлення, banana — малює зображення: розмовляти не вміють.
            val excluded = listOf("embedding", "image", "tts", "audio", "live", "vision", "aqa", "robotics", "computer-use", "exp-", "transcribe", "banana")
            return excluded.none { it in n }
        }

        /** Спершу швидкі й дешеві (flash), далі lite, далі решта (pro — найсуворіші ліміти). */
        fun rank(name: String): Int {
            val n = name.lowercase()
            val base = when {
                "flash-lite" in n -> 1
                "flash" in n -> 0
                "pro" in n -> 3
                else -> 2
            }
            return base * 2 + if ("preview" in n) 1 else 0
        }

        /** Чи вимикати "роздуми": thinkingBudget 0 приймають flash-моделі. */
        fun canDisableThinking(name: String) = "flash" in name.lowercase()
    }
}
