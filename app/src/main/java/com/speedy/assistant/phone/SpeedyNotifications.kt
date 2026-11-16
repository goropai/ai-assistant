package com.speedy.assistant.phone

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Одне сповіщення: від якого застосунку, від кого (заголовок), текст, коли. */
data class Note(val app: String, val title: String, val text: String, val at: Long)

/**
 * Сповіщення інших застосунків (доступ вмикає людина: "Доступ до сповіщень").
 * Лише в пам'яті — останні [MAX], на диск не пишемо.
 */
class SpeedyNotifications : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return // підсумок групи — дубль
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        NotificationStore.add(Note(app, title.take(80), text.take(300), sbn.postTime))
    }
}

object NotificationStore {
    private const val MAX = 20
    private val notes = ArrayDeque<Note>()

    /** Хто чекає на нові сповіщення (Спіді коротко повідомляє). */
    @Volatile var onNew: ((Note) -> Unit)? = null

    @Synchronized
    fun add(note: Note) {
        // Месенджери оновлюють те саме сповіщення — не дублюємо.
        notes.removeAll { it.app == note.app && it.title == note.title && it.text == note.text }
        notes.addLast(note)
        while (notes.size > MAX) notes.removeFirst()
        onNew?.invoke(note)
    }

    @Synchronized
    fun recent(max: Int = 5): List<Note> = notes.toList().takeLast(max).reversed()

    @Synchronized
    fun clear() = notes.clear()

    fun allowed(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
    }
}
