package com.speedy.assistant.books

import java.io.File

/**
 * Обхід пам'яті телефона й карток: книги й медіа шукаємо, коли попросили (не заздалегідь), одним
 * проходом, крім службових тек. Результат живе кілька хвилин. Викликати не в головному потоці.
 */
class StorageScan(
    private val roots: () -> List<File>,
    private val accept: (File) -> Boolean,
) {
    private var cache: List<File> = emptyList()
    private var cachedAt = Long.MIN_VALUE / 2

    @Synchronized
    fun files(now: Long = System.currentTimeMillis()): List<File> {
        if (now - cachedAt < CACHE_MS) return cache
        val found = mutableListOf<File>()
        val seen = mutableSetOf<String>()
        val started = System.currentTimeMillis()
        val queue = ArrayDeque(roots().filter { it.isDirectory }.map { it to 0 })
        while (queue.isNotEmpty() && System.currentTimeMillis() - started < SCAN_BUDGET_MS) {
            val (dir, depth) = queue.removeFirst()
            val real = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
            if (!seen.add(real)) continue // та сама тека іншим шляхом (/sdcard, /storage/emulated/0)
            for (f in dir.listFiles().orEmpty()) {
                if (f.name.startsWith('.')) continue
                if (f.isDirectory) {
                    if (depth < MAX_DEPTH && !skipDir(f)) queue.addLast(f to depth + 1)
                } else if (accept(f)) {
                    found += f
                }
            }
        }
        cache = found.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
        cachedAt = now
        return cache
    }

    private fun skipDir(dir: File): Boolean {
        val name = dir.name.lowercase()
        if (name in SKIP) return true
        // Android/data, Android/obb — дані застосунків (і закриті на новіших Android).
        return dir.parentFile?.name.equals("Android", ignoreCase = true) && name in setOf("data", "obb", "media")
    }

    private companion object {
        const val CACHE_MS = 5 * 60_000L
        const val SCAN_BUDGET_MS = 15_000L
        const val MAX_DEPTH = 12
        /** Кеші, журнали, фото з камери, системні звуки й голосові повідомлення месенджерів. */
        val SKIP = setOf(
            "cache", "thumbnails", "log", "logs", "lost.dir", "dcim",
            "ringtones", "notifications", "alarms", "whatsapp",
        )
    }
}
