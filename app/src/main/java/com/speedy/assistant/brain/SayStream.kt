package com.speedy.assistant.brain

/**
 * Потокова відповідь моделі приходить шматками JSON: {"mood":"happy","say":"Привіт! Як ти?", ...}.
 * Щоб не чекати кінця, витягуємо "mood" і текст "say" з незавершеного JSON і віддаємо
 * готові речення — Спіді починає говорити, поки модель ще дописує решту.
 */
class SayStream(
    private val onMood: (String) -> Unit,
    private val onSentence: (String) -> Unit,
) {
    private val raw = StringBuilder()
    private var moodSent = false
    private var emitted = 0 // скільки символів "say" вже віддали
    var spokeAnything = false
        private set

    fun append(delta: String) {
        raw.append(delta)
        if (!moodSent) {
            PartialJson.string(raw, "mood")?.takeIf { it.second }?.let { (mood, _) ->
                moodSent = true
                onMood(mood)
            }
        }
        val (say, closed) = PartialJson.string(raw, "say") ?: return
        var start = emitted
        while (true) {
            val end = sentenceEnd(say, start) ?: break
            emit(say.substring(start, end))
            start = end
        }
        emitted = start
        if (closed && emitted < say.length) {
            emit(say.substring(emitted))
            emitted = say.length
        }
    }

    private fun emit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        spokeAnything = true
        onSentence(t)
    }

    companion object {
        /** Кінець речення після [from]: ".!?…" + пробіл; довгий шмат — ріжемо по комі. */
        fun sentenceEnd(text: String, from: Int): Int? {
            for (i in from until text.length - 1) {
                val c = text[i]
                if ((c == '.' || c == '!' || c == '?' || c == '…') && text[i + 1] == ' ') return i + 1
                if ((c == ',' || c == ';' || c == '—') && i - from >= LONG_CLAUSE && text[i + 1] == ' ') return i + 1
            }
            return null
        }

        private const val LONG_CLAUSE = 90
    }
}

/** Читання рядкового поля з JSON, який ще не дописаний. */
object PartialJson {
    /** @return значення (наскільки вже відоме) і чи рядок закритий; null — поле ще не почалось. */
    fun string(json: CharSequence, key: String): Pair<String, Boolean>? {
        val keyAt = indexOfKey(json, key) ?: return null
        var i = keyAt + key.length + 2
        while (i < json.length && (json[i].isWhitespace() || json[i] == ':')) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' -> return out.toString() to true
                c == '\\' -> {
                    if (i + 1 >= json.length) return out.toString() to false
                    when (val e = json[i + 1]) {
                        'n' -> out.append(' ')
                        't' -> out.append(' ')
                        'u' -> {
                            if (i + 5 >= json.length) return out.toString() to false
                            out.append(json.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(e) // \" \\ \/
                    }
                    i += 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString() to false
    }

    private fun indexOfKey(json: CharSequence, key: String): Int? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(needle, from)
            if (at < 0) return null
            // ключ, а не значення: далі має йти двокрапка
            var j = at + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] == ':') return at
            from = at + 1
        }
    }
}
