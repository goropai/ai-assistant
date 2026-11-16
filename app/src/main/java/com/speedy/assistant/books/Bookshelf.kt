package com.speedy.assistant.books

import org.json.JSONObject
import java.io.File

/** Місце в книзі: розділ (сторінка PDF) і речення в ньому; [translate] — читаємо в перекладі українською. */
data class Place(
    val chapter: Int, val sentence: Int, val chapters: Int = 0, val title: String = "",
    val finished: Boolean = false, val translate: Boolean = false,
)

/**
 * Книги в пам'яті телефона й на картці (див. [StorageScan]). Місце читання — у files/books.json:
 * одне на книгу, а ще — остання книга кожного, хто слухав (і остання взагалі).
 */
class Bookshelf(
    private val storage: StorageScan,
    private val progressFile: File?,
    /** PDF без тексту — розпізнавання сторінок (null — сканів не читаємо). */
    val scans: ScanReader? = null,
) {
    /** Окремий обхід лише книг (тести, або коли медіа не потрібні). */
    constructor(roots: () -> List<File>, progressFile: File?, scans: ScanReader? = null) :
        this(StorageScan(roots) { isBookFile(it) }, progressFile, scans)

    /**
     * Відкрити книгу; PDF-скан — через розпізнавання.
     * @throws NoTextException тексту немає й розпізнати нічим; [OcrNotReadyException] — спершу [ScanReader.prepare].
     */
    fun open(file: File): Book = try {
        Books.open(file)
    } catch (e: NoTextException) {
        if (scans == null || !file.name.endsWith(".pdf", ignoreCase = true)) throw e
        scans.open(file)
    }

    private val places = mutableMapOf<String, Place>()
    private val lastOf = mutableMapOf<String, String>()

    init {
        progressFile?.takeIf { it.exists() }?.let {
            try {
                val o = JSONObject(it.readText())
                o.optJSONObject("books")?.let { b ->
                    for (path in b.keys()) {
                        val p = b.getJSONObject(path)
                        places[path] = Place(p.optInt("chapter"), p.optInt("sentence"), p.optInt("chapters"), p.optString("title"), p.optBoolean("finished"), p.optBoolean("translate"))
                    }
                }
                o.optJSONObject("last")?.let { l -> for (who in l.keys()) lastOf[who] = l.getString(who) }
            } catch (e: Exception) {
                places.clear(); lastOf.clear()
            }
        }
    }

    /** Усі книги (повільно: обхід сховища; результат живе кілька хвилин). Викликати не в головному потоці. */
    fun all(now: Long = System.currentTimeMillis()): List<File> = storage.files(now).filter(::isBookFile)

    /** Книга за сказаною назвою (українською чи латиницею в імені файлу); null — нічого схожого. */
    fun find(query: String): File? = findKnown(query) ?: best(query, all())

    /**
     * Серед книг, які вже читали (books.json) — без обходу сховища. Назва тут — і з самої книги
     * (метадані EPUB/FB2/PDF), і з імені файлу. Лише повний збіг: "Гаррі Поттер і таємна кімната"
     * не має знайти вже прочитаний "…і філософський камінь" — тоді шукаємо по всьому сховищу.
     */
    fun findKnown(query: String): File? {
        val known = synchronized(this) { places.keys.toList() }.map(::File).filter { it.exists() }
        return best(query, known, minScore = 1.0) { "${title(it)} ${Books.titleFromName(it)}" }
    }

    @Synchronized
    fun place(file: File): Place? = places[file.path]

    @Synchronized
    fun save(file: File, reader: String?, place: Place) {
        places[file.path] = place
        reader?.let { lastOf[it] = file.path }
        lastOf[ANYONE] = file.path
        write()
    }

    /** Остання книга цього слухача (або будь-чия, якщо він ще нічого не слухав) — якщо файл ще є. */
    @Synchronized
    fun last(reader: String?): File? =
        (reader?.let { lastOf[it] } ?: lastOf[ANYONE])?.let(::File)?.takeIf { it.exists() }

    /** Назва для мовлення: збережена з самої книги, інакше — з імені файлу. */
    @Synchronized
    fun title(file: File): String = places[file.path]?.title?.takeIf { it.isNotBlank() } ?: Books.titleFromName(file)

    private fun write() {
        val f = progressFile ?: return
        val books = JSONObject()
        places.forEach { (path, p) ->
            books.put(path, JSONObject().put("chapter", p.chapter).put("sentence", p.sentence)
                .put("chapters", p.chapters).put("title", p.title).put("finished", p.finished).put("translate", p.translate))
        }
        f.writeText(JSONObject().put("books", books).put("last", JSONObject(lastOf as Map<*, *>)).toString())
    }

    companion object {
        const val ANYONE = "*"
        /** Менші TXT — журнали, ліцензії, нотатки, а не книги. */
        private const val MIN_TXT_BYTES = 20_000L

        fun isBookFile(f: File): Boolean =
            Books.isBook(f) && (!f.name.endsWith(".txt", ignoreCase = true) || f.length() >= MIN_TXT_BYTES)

        /** Найкраще збігання назви: частка слів запиту, знайдених у назві файлу (з транслітерацією). */
        fun best(query: String, files: List<File>, minScore: Double = 0.5, titleOf: (File) -> String = Books::titleFromName): File? {
            // "і", "в", "з" — не зіставляємо (similar вимагає ≥ 2 літер), цифри — так.
            val q = words(query).filter { it !in FILLER && (it.length >= 2 || it.toIntOrNull() != null) }
            if (q.isEmpty()) return null
            return files.map { it to score(q, words(titleOf(it) + " " + (it.parentFile?.name ?: ""))) }
                .filter { it.second >= minScore }
                .maxWithOrNull(compareBy<Pair<File, Double>> { it.second }.thenBy { it.first.lastModified() })
                ?.first
        }

        private fun score(query: List<String>, title: List<String>): Double =
            query.count { w -> title.any { similar(w, it) } }.toDouble() / query.size

        /**
         * Слова збігаються, якщо однаково починаються (відмінки: "Кобзаря" ~ "Kobzar") або однаково
         * звучать приголосні: англійська назва, почута українською ("клін код" ~ "Clean Code",
         * "пітон" ~ "Python") — голосні розпізнавач і англійське письмо передають по-різному.
         */
        private fun similar(a: String, b: String): Boolean {
            if (a.toIntOrNull() != null || b.toIntOrNull() != null) return a == b
            if (a.length < 2 || b.length < 2) return false
            val n = minOf(4, a.length, b.length)
            if (a.take(n) == b.take(n) && (n == 4 || a == b)) return true
            val sa = skeleton(a)
            val sb = skeleton(b)
            val m = minOf(sa.length, sb.length)
            return if (m < 3) m >= 1 && sa == sb else sa.take(3) == sb.take(3)
        }

        /** Приголосні без повторів: "potter" → "ptr", "dzgavaskript" → "dzgvskrpt". */
        private fun skeleton(w: String): String =
            w.filter { it !in "aeiou" }.replace(Regex("(.)\\1+"), "$1")

        /**
         * Слова назви латиницею, у спрощеному вигляді, однаковому для транслітерації й англійського
         * письма: h=g ("Гаррі" ~ "Harry"), y=i, c=k/s ("code" ~ "код"), j=дж ("Java" ~ "джава").
         */
        fun words(s: String): List<String> =
            translit(s.lowercase()).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.map { w ->
                w.replace("kh", "h").replace("ph", "f").replace("th", "t").replace("ck", "k").replace("qu", "kv")
                    .replace(Regex("c(?=[eiy])"), "s").replace(Regex("c(?!h)"), "k")
                    .replace("q", "k").replace("x", "ks").replace("j", "dzh")
                    .replace('h', 'g').replace('y', 'i').replace('w', 'v')
            }

        private fun translit(s: String): String = buildString {
            for (c in s) append(LATIN[c] ?: c.toString())
        }

        private val LATIN = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e", 'є' to "ie",
            'ж' to "zh", 'з' to "z", 'и' to "y", 'і' to "i", 'ї' to "i", 'й' to "i", 'к' to "k", 'л' to "l",
            'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ь' to "", 'ю' to "iu",
            'я' to "ia", '\'' to "", '’' to "", 'ы' to "y", 'э' to "e", 'ё' to "e", 'ъ' to "",
        )

        /** Слова з прохання, яких у назві немає: "почитай мені ту книжку про…". */
        private val FILLER = words("про книгу книжку книжечку книга ту цю оцю мені нам будь ласка вголос фільм фільми мультик серіал аудіокнигу").toSet()
    }
}
