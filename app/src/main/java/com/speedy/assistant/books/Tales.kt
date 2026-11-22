package com.speedy.assistant.books

/**
 * Казка з інтернету: "знайди казку про пана Коцького" — спершу серед книг, інакше текст через пошук моделі
 * (зберігаємо в files/tales і читаємо як книгу: пауза, "продовжуй", місце запам'ятовується).
 */
object Tales {
    private val request = Regex(
        "^(?:знайди|розкажи|почитай|прочитай|читай)(?:\\s+(?:мені|нам|їй|йому|будь\\s+ласка))*\\s+" +
            "(?:казку|казочку)(?:\\s+(?:мені|нам|їй|йому))*\\s+(?:про\\s+)?(.+)$",
    )

    /** Що шукати ("кота пана коцького") або null: не про казку; без назви — хай розповідає модель. */
    fun parse(phrase: String): String? {
        val t = phrase.lowercase().replace(Regex("[,!?.«»\"]"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^спіді\\s+"), "")
        return request.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }
    }

    /** Завдання для пошуку: перший рядок — назва, далі текст абзацами; не знайшла — "НЕМАЄ". */
    fun prompt(query: String) =
        "Знайди в інтернеті повний текст дитячої казки «$query» українською мовою. " +
            "Перший рядок — назва казки, далі сам текст абзацами: без вступів, коментарів, посилань і розмітки. " +
            "Народна чи класична казка — повний традиційний текст; авторська сучасна — докладний переказ своїми словами. " +
            "Якщо такої казки не знайшла — відповідай одним словом: НЕМАЄ."

    data class Found(val title: String, val text: String)

    /** Розібрати відповідь моделі; null — не знайшла чи тексту замало для казки. */
    fun found(answer: String?): Found? {
        val lines = answer.orEmpty().lines()
            .map { it.replace(Regex("[*#_>`]+"), "").trim() }
            .filter { it.isNotEmpty() && !Regex("^https?://").containsMatchIn(it) }
        if (lines.isEmpty() || lines[0].uppercase().startsWith("НЕМАЄ")) return null
        val title = lines[0].trim('«', '»', '"', ' ', '.').ifBlank { return null }
        val text = lines.drop(1).joinToString("\n")
        if (text.length < MIN_TEXT || title.length > 80) return null
        return Found(title, text)
    }

    /** Ім'я файлу з назви: без недозволених символів, з великої літери. */
    fun fileName(title: String) =
        title.replace(Regex("[^\\p{L}\\d' -]"), "").replace(Regex("\\s+"), " ").trim().take(60)
            .replaceFirstChar { it.uppercase() } + ".txt"

    /** Коротший текст — це відмовка чи опис, а не казка. */
    const val MIN_TEXT = 400

    /** Що шукати, щоб знайти сторінку з повним текстом. */
    fun pagesQuery(query: String) = "казка $query текст українською"

    private val junk = Regex(
        "(?iu)©|&copy;|cookie|коментар|поділит|реклам|підпиш|читайте також|читати також|увійти|реєстрац|меню|copyright|" +
            "сайт|повний текст",
    )
    /** Вбудовані теги — геть без пробілу (буквиця <span>Б</span>ули — одне слово). */
    private val inline = Regex("(?i)</?(?:span|em|strong|b|i|u|a|font|small|big|sup|sub)\\b[^>]*>")
    private val paragraph = Regex("(?is)<p\\b[^>]*>.*?</p>")

    /**
     * Текст казки зі сторінки: найбільший суцільний блок абзаців українською (меню, коментарі, підписи — геть).
     * Багато абзаців <p> — лише з них (навігація й підвал сайту зазвичай поза ними). Короткі рядки (діалоги
     * "— Няв!", пісеньки) — лише всередині блоку. null — схоже, казки тут немає (замало тексту або в ньому
     * немає слів із запиту).
     */
    fun extract(html: String, query: String): String? {
        val clean = html.replace(inline, "")
        val paragraphs = paragraph.findAll(clean).map { it.value }.toList()
        val onlyParagraphs = paragraphs.size >= MIN_PARAGRAPHS
        val lines = Markup.text(if (onlyParagraphs) paragraphs.joinToString("\n") else clean).lines()
        var best: List<String> = emptyList()
        var run = mutableListOf<String>()
        // Між абзацами: короткі рядки (діалоги — лишаються) і "чужі" (підпис картинки, реклама — викидаємо).
        var shortRun = mutableListOf<String>()
        var gap = 0
        fun close() {
            // Короткий кінець ("Та й з'їла!") — лише з абзаців: поза ними короткі рядки після тексту — це меню.
            if (onlyParagraphs && run.isNotEmpty()) run.addAll(shortRun)
            if (run.sumOf { it.length } > best.sumOf { it.length }) best = run
            run = mutableListOf()
            shortRun = mutableListOf()
            gap = 0
        }
        for (line in lines) {
            val foreign = junk.containsMatchIn(line) || !ukrainian(line)
            when {
                foreign && run.isNotEmpty() && line.length < MAX_GAP_LINE && ++gap <= MAX_GAP -> {}
                foreign -> close()
                line.length >= PARAGRAPH -> { run.addAll(shortRun); shortRun.clear(); gap = 0; run += line }
                run.isNotEmpty() && shortRun.size < MAX_SHORT -> shortRun += line
                else -> close()
            }
        }
        close()
        val text = best.joinToString("\n")
        if (text.length < MIN_PAGE_TEXT) return null
        val stems = words(query).filter { it.length >= 4 && !it.startsWith("казк") }.map { it.take(4) }
        if (stems.isNotEmpty() && stems.none { s -> text.lowercase().contains(s) }) return null
        return text
    }

    /** Назва казки з <title> сторінки ("Пан Коцький — українська народна казка | Казки" → "Пан Коцький"). */
    fun pageTitle(html: String): String? {
        val raw = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1) ?: return null
        return Markup.unescape(raw).split(Regex("\\s[|–—-]\\s|[|:]"))
            .map { it.replace(Regex("\\(.*?\\)"), "").trim().trim('«', '»', '"', '.', ' ') }
            .firstOrNull { it.length in 2..60 && !titleJunk.containsMatchIn(it) }
    }

    /** Частини заголовка сторінки, що не є назвою казки. */
    private val titleJunk = Regex("(?iu)казк|читати|бібліотек|онлайн|текст|автор")

    /** Кодування з <meta charset> (для сторінок, де заголовок HTTP його не каже). */
    fun metaCharset(head: String): String? =
        Regex("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)").find(head)?.groupValues?.get(1)

    private fun ukrainian(line: String): Boolean {
        val letters = line.count { it.isLetter() }
        return letters > 0 && line.count { it in '\u0400'..'\u04FF' } >= letters * 0.7
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }

    /** Абзац — від стількох символів; коротші — лише всередині тексту (діалоги, пісеньки), до [MAX_SHORT] підряд. */
    private const val PARAGRAPH = 60
    private const val MAX_SHORT = 20
    /** Від стількох <p> на сторінці текст беремо лише з абзаців. */
    private const val MIN_PARAGRAPHS = 5
    /** Скільки "чужих" рядків поспіль (коротших за [MAX_GAP_LINE]) може розривати текст казки. */
    private const val MAX_GAP = 3
    private const val MAX_GAP_LINE = 200
    /** Менше — це анотація чи уривок, а не казка. */
    const val MIN_PAGE_TEXT = 800
}
