package com.speedy.assistant.settings

/**
 * Варіант голосу Спіді (у налаштуваннях): висота й швидкість мовлення поверх голосу синтезатора.
 * null — як у збірці (`tts.pitch` / `tts.rate` у local.properties).
 */
enum class VoicePreset(val code: String, val ua: String, private val pitch: Float?, private val rate: Float?) {
    SPEEDY("speedy", "Спіді — дзвінкий, як завжди", null, null),
    CHILD("child", "Ще дзвінкіший, як у малечі", 1.6f, 1.1f),
    CALM("calm", "Спокійний — для казок і на ніч", 1.1f, 0.85f),
    NATURAL("natural", "Звичайний голос синтезатора", 1.0f, 1.0f),
    LOW("low", "Низький", 0.75f, 0.95f),
    FAST("fast", "Швидкий", 1.3f, 1.35f);

    fun pitch(default: Float) = pitch ?: default
    fun rate(default: Float) = rate ?: default

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: SPEEDY

        /** Назва голосу синтезатора для людини: "uk-ua-x-hfd-local" → "hfd (без інтернету)". */
        fun voiceLabel(name: String): String {
            val where = when {
                name.endsWith("-local") -> " (без інтернету)"
                name.endsWith("-network") -> " (через інтернет)"
                else -> ""
            }
            val core = name.removeSuffix("-local").removeSuffix("-network").removePrefix("uk-ua-x-").removePrefix("uk-UA-")
            return core + where
        }
    }
}
