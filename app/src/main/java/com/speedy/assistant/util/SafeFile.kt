package com.speedy.assistant.util

import java.io.File
import java.io.FileOutputStream

/**
 * Запис "все або нічого": спершу поруч `<ім'я>.tmp` (і на диск — fsync), потім перейменування.
 * Вимкнувся телефон посеред запису — лишається попередній цілий файл, а не обірваний JSON
 * (обличчя, профілі, нагадування… інакше губилися б повністю).
 */
fun File.writeTextAtomic(text: String) {
    parentFile?.mkdirs()
    val tmp = File(path + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    // На Android (Linux) rename замінює файл атомарно; на Windows (JVM-тести) — лише якщо його немає.
    if (!tmp.renameTo(this)) {
        delete()
        check(tmp.renameTo(this)) { "не вдалося зберегти $name" }
    }
}
