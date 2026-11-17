package com.speedy.assistant.books

/**
 * Текст розділу → речення (місце в книзі — номер речення) → фрагменти для мовлення:
 * кілька речень разом, щоб між ними не було пауз синтезатора, але й не надто довго.
 */
object Sentences {
    private const val ENDS = ".!?…"
    private const val CLOSING = "\"»”’)]"

    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            val p = paragraph.replace(Regex("\\s+"), " ").trim()
            if (p.isEmpty()) continue
            var start = 0
            var i = 0
            while (i < p.length) {
                if (p[i] in ENDS) {
                    var end = i + 1
                    while (end < p.length && (p[end] in ENDS || p[end] in CLOSING)) end++
                    // «Чи ж так?» — спитав пес. / "і т. д. далі" — речення триває.
                    val next = p.getOrNull(end + 1)
                    if (end >= p.length || p[end] == ' ' && next != null && !next.isLowerCase() && next !in "—–-") {
                        p.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                        start = end
                    }
                    i = end
                } else i++
            }
            p.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    /** Фрагмент для мовлення: з якого речення починається і що сказати. */
    data class Chunk(val first: Int, val text: String)

    /** Речення від [from], по кілька в фрагменті (до ~[target] символів); задовгі ріжемо. */
    fun chunks(sentences: List<String>, from: Int = 0, target: Int = 250, max: Int = 400): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var first = -1
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) out += Chunk(first, buf.toString())
            buf.clear()
            first = -1
        }
        for (i in from.coerceAtLeast(0) until sentences.size) {
            val s = sentences[i]
            if (s.length > max) {
                flush()
                cut(s, max).forEach { out += Chunk(i, it) }
                continue
            }
            if (buf.isNotEmpty() && buf.length + 1 + s.length > target) flush()
            if (first < 0) first = i
            // Заголовок ("Chapter 1", "Глава 2") без крапки — крапка, щоб синтезатор зробив паузу.
            if (buf.isNotEmpty()) buf.append(if (buf.last() in ENDS || buf.last() in CLOSING) " " else ". ")
            buf.append(s)
        }
        flush()
        return out
    }

    /** Довге речення без крапок (вірш, PDF) — по комах, інакше по пробілах. */
    private fun cut(s: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val at = listOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(" — "))
                .maxOrNull()?.takeIf { it > max / 3 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > max / 3 }
                ?: max
            out += rest.substring(0, at).trim()
            rest = rest.substring(at).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
