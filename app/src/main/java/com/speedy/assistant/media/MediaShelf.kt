package com.speedy.assistant.media

import com.speedy.assistant.books.Bookshelf
import com.speedy.assistant.books.StorageScan
import org.json.JSONObject
import java.io.File

enum class MediaKind { AUDIO, VIDEO }

/**
 * Що вмикати: один файл (фільм, пісня) або тека з кількома файлами одного виду, які йдуть по черзі
 * (аудіокнига з розділів, серіал із серій, альбом). [key] — шлях файлу чи теки.
 */
data class MediaItem(val key: String, val title: String, val kind: MediaKind, val tracks: List<File>)

/** Місце: який файл теки і позиція в ньому; [finished] — дослухали / додивились до кінця. */
data class MediaPlace(val track: Int, val positionMs: Long, val finished: Boolean = false)

object MediaFiles {
    private val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka")
    private val VIDEO = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "3gp", "wmv", "flv", "ts", "mpg", "mpeg", "vob")
    /** Менші — системні звуки, голосові повідомлення, короткі ролики з месенджерів. */
    private const val MIN_AUDIO = 300_000L
    private const val MIN_VIDEO = 3_000_000L

    fun kind(file: File): MediaKind? = when (file.extension.lowercase()) {
        in AUDIO -> MediaKind.AUDIO
        in VIDEO -> MediaKind.VIDEO
        else -> null
    }

    fun isMedia(file: File): Boolean = when (kind(file)) {
        MediaKind.AUDIO -> file.length() >= MIN_AUDIO
        MediaKind.VIDEO -> file.length() >= MIN_VIDEO
        null -> false
    }

    /** "Dune.2021.mkv" → "Dune 2021". */
    fun title(file: File): String =
        file.name.substringBeforeLast('.').replace(Regex("[_.]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** Кожен файл — окремо, а тека з ≥ 2 файлами одного виду — ще й разом, по черзі. */
    fun group(files: List<File>): List<MediaItem> {
        val singles = files.mapNotNull { f -> kind(f)?.let { MediaItem(f.path, title(f), it, listOf(f)) } }
        val folders = files.groupBy { it.parentFile?.path.orEmpty() to kind(it) }
            .filter { (k, list) -> k.second != null && k.first.isNotEmpty() && list.size >= 2 }
            .map { (k, list) -> MediaItem(k.first, File(k.first).name, k.second!!, list.sortedWith(NATURAL)) }
        return folders + singles
    }

    /** "2.mp3" перед "10.mp3": числа порівнюємо як числа. */
    val NATURAL = Comparator<File> { a, b ->
        val pa = parts(a.name.lowercase())
        val pb = parts(b.name.lowercase())
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) compareValuesBy(x, y, { it.length }, { it }) else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        pa.size - pb.size
    }

    private fun parts(s: String) = Regex("\\d+|\\D+").findAll(s)
        .map { m -> if (m.value[0].isDigit()) m.value.trimStart('0').ifEmpty { "0" } else m.value }.toList()
}

/**
 * Аудіокниги, фільми, музика — з того самого обходу сховища, що й книги. Місце — у files/media.json:
 * для кожного запису (файлу чи теки) — файл і позиція; а ще — що вмикали останнім.
 */
class MediaShelf(private val storage: StorageScan, private val progressFile: File?) {
    private val places = mutableMapOf<String, MediaPlace>()
    private val titles = mutableMapOf<String, String>()
    private var last: String? = null

    init {
        progressFile?.takeIf { it.exists() }?.let {
            try {
                val o = JSONObject(it.readText())
                o.optJSONObject("items")?.let { items ->
                    for (key in items.keys()) {
                        val p = items.getJSONObject(key)
                        places[key] = MediaPlace(p.optInt("track"), p.optLong("position"), p.optBoolean("finished"))
                        p.optString("title").takeIf { t -> t.isNotBlank() }?.let { t -> titles[key] = t }
                    }
                }
                last = o.optString("last").ifBlank { null }
            } catch (e: Exception) {
                places.clear(); titles.clear(); last = null
            }
        }
    }

    fun all(now: Long = System.currentTimeMillis()): List<MediaItem> = MediaFiles.group(storage.files(now).filter(MediaFiles::isMedia))

    /**
     * За сказаною назвою: спершу серед того, що вже вмикали (повний збіг, без обходу), потім усе.
     * [kind] — "фільм" / "аудіокнигу": лише цього виду.
     */
    fun find(query: String, kind: MediaKind? = null): MediaItem? {
        known(kind).let { items -> pick(query, items, 1.0)?.let { return it } }
        return pick(query, all().filter { kind == null || it.kind == kind }, 0.5)
    }

    private fun known(kind: MediaKind?): List<MediaItem> = synchronized(this) { places.keys.toList() }.mapNotNull(::item)
        .filter { kind == null || it.kind == kind }

    /** Запис за ключем (файл чи тека) — як він є зараз на диску; null — зник. */
    fun item(key: String): MediaItem? {
        val f = File(key)
        return when {
            f.isFile -> MediaFiles.kind(f)?.let { MediaItem(key, title(key, MediaFiles.title(f)), it, listOf(f)) }
            f.isDirectory -> MediaFiles.group(f.listFiles().orEmpty().filter(MediaFiles::isMedia))
                .firstOrNull { it.key == key }?.let { it.copy(title = title(key, it.title)) }
            else -> null
        }
    }

    private fun pick(query: String, items: List<MediaItem>, minScore: Double): MediaItem? {
        val byFile = items.associateBy { File(it.key) }
        val best = Bookshelf.best(query, byFile.keys.toList(), minScore) { byFile[it]?.title.orEmpty() } ?: return null
        return byFile[best]
    }

    @Synchronized
    fun place(item: MediaItem): MediaPlace? = places[item.key]

    @Synchronized
    fun save(item: MediaItem, place: MediaPlace) {
        places[item.key] = place
        titles[item.key] = item.title
        last = item.key
        write()
    }

    @Synchronized
    fun last(): MediaItem? = last?.let(::item)

    @Synchronized
    private fun title(key: String, default: String) = titles[key] ?: default

    private fun write() {
        val f = progressFile ?: return
        val items = JSONObject()
        places.forEach { (key, p) ->
            items.put(key, JSONObject().put("track", p.track).put("position", p.positionMs)
                .put("finished", p.finished).put("title", titles[key].orEmpty()))
        }
        f.writeText(JSONObject().put("items", items).put("last", last.orEmpty()).toString())
    }
}
