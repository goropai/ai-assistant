package com.speedy.assistant.phone

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Одне сповіщення: ключ системи, застосунок, від кого (заголовок), текст, коли, чи прочитане. */
data class Note(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val at: Long,
    val read: Boolean = false,
)

/**
 * Сповіщення інших застосунків (доступ вмикає людина: "Доступ до сповіщень").
 * Ті, що вже висіли на шторці, коли Спіді запустилась, — прочитані; нові — непрочитані.
 * Лише в пам'яті, на диск не пишемо.
 */
class SpeedyNotifications : NotificationListenerService() {

    override fun onListenerConnected() {
        // Усе, що вже на шторці на момент запуску, — прочитане.
        try {
            activeNotifications?.forEach { sbn -> toNote(sbn)?.let { NotificationStore.add(it.copy(read = true), announce = false) } }
        } catch (_: SecurityException) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        toNote(sbn)?.let { NotificationStore.add(it, announce = true) }
    }

    /** Змахнули зі шторки — і з нашого списку. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.remove(sbn.key)
    }

    private fun toNote(sbn: StatusBarNotification): Note? {
        if (sbn.packageName == packageName || sbn.isOngoing) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null // підсумок групи — дубль
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        return Note(sbn.key, app, title.take(80), text.take(300), sbn.postTime)
    }
}

/** Список сповіщень із позначками "прочитано" (одна копія на процес Спіді). */
object NotificationStore {
    val notes = NoteList()

    /** Хто чекає на нові сповіщення (Спіді коротко повідомляє). */
    @Volatile var onNew: (() -> Unit)? = null

    fun add(note: Note, announce: Boolean) {
        if (notes.add(note) && announce) onNew?.invoke()
    }

    fun remove(key: String) = notes.remove(key)

    fun allowed(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
    }
}

/** Чиста логіка списку (перевіряється тестами). */
class NoteList(private val max: Int = 30) {
    private val items = LinkedHashMap<String, Note>()

    /** @return true — з'явилось щось нове (непрочитане). Той самий ключ із новим текстом — знову новий. */
    @Synchronized
    fun add(note: Note): Boolean {
        val old = items[note.key]
        if (old != null && old.title == note.title && old.text == note.text) return false // оновлення без змін
        items.remove(note.key)
        items[note.key] = note
        while (items.size > max) items.remove(items.keys.first())
        return !note.read
    }

    @Synchronized
    fun remove(key: String) { items.remove(key) }

    @Synchronized
    fun unread(): List<Note> = items.values.filter { !it.read }.sortedByDescending { it.at }

    @Synchronized
    fun recent(n: Int): List<Note> = items.values.sortedByDescending { it.at }.take(n)

    @Synchronized
    fun markRead(keys: Collection<String>) {
        keys.forEach { k -> items[k]?.let { items[k] = it.copy(read = true) } }
    }

    @Synchronized
    fun markAllRead() = markRead(items.keys.toList())
}
