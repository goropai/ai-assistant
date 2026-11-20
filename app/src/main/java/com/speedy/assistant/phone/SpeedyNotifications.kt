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
    /** Від людини (месенджер, SMS, пошта): заголовок — відправник. Інакше заголовок — сам зміст ("Зараз: 18° · Ужгород"). */
    val fromPerson: Boolean = true,
)

/** Як сказати про нові сповіщення вголос (без тексту повідомлень від людей). */
object NoteText {
    /** "Viber від мами" / "Google: зараз 18°, Ужгород". */
    fun part(n: Note, single: Boolean): String {
        val title = n.title.trim()
        if (title.isBlank()) return n.app
        if (n.fromPerson) return "${n.app} від $title"
        return (if (single) "від ${n.app}: " else "${n.app} — ") + spoken(title)
    }

    fun announce(notes: List<Note>, count: (Int) -> String): String {
        val one = notes.singleOrNull()
        if (one != null) return "Нове повідомлення " + part(one, single = true).let { if (one.fromPerson) "у $it" else it } + "."
        return "${count(notes.size)}: " + notes.take(4).joinToString(", ") { part(it, single = false) } +
            (if (notes.size > 4) " і ще ${notes.size - 4}" else "") + "."
    }

    /** Заголовок-зміст природно: "Зараз: 18° · Ужгород" → "зараз 18°, Ужгород". */
    fun spoken(title: String): String = title
        .replace(Regex("\\s*[·•|]\\s*"), ", ")
        .replace(Regex(":\\s*(?=[-+−]?\\d)"), " ")
        .trim().trimEnd('.')
        .replaceFirstChar { it.lowercase() }

    /** Месенджери, SMS, пошта — там заголовок сповіщення — це хто написав. */
    fun fromPerson(category: String?, pkg: String, hasMessages: Boolean): Boolean =
        hasMessages || category == android.app.Notification.CATEGORY_MESSAGE || category == android.app.Notification.CATEGORY_EMAIL ||
            category == android.app.Notification.CATEGORY_CALL || pkg in MESSENGERS

    private val MESSENGERS = setOf(
        "com.viber.voip", "org.telegram.messenger", "org.telegram.plus", "com.whatsapp", "com.facebook.orca",
        "org.thoughtcrime.securesms", "com.skype.raider", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.google.android.gm", "com.samsung.android.email.provider",
    )
}

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
        // Будильник / таймер дзвонить: його кнопки ("Відхилити", "Відкласти") — для "вимкни будильник".
        if (isClockAlert(sbn)) NotificationStore.clockAlert = sbn
        toNote(sbn)?.let { NotificationStore.add(it, announce = true) }
    }

    /** Змахнули зі шторки — і з нашого списку. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (NotificationStore.clockAlert?.key == sbn.key) NotificationStore.clockAlert = null
        NotificationStore.remove(sbn.key)
    }

    /**
     * Сповіщення будильника / таймера, що саме ДЗВОНИТЬ (повноекранне чи наполегливе), — не
     * "наступний будильник о 7:00" (у того теж кнопка "Відхилити", і її натискати не можна).
     */
    private fun isClockAlert(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.actions.isNullOrEmpty()) return false
        val ringing = n.fullScreenIntent != null || n.flags and Notification.FLAG_INSISTENT != 0
        return ringing && (n.category == Notification.CATEGORY_ALARM || sbn.packageName.contains("clock", ignoreCase = true))
    }

    private fun toNote(sbn: StatusBarNotification): Note? {
        if (sbn.packageName == packageName || sbn.isOngoing) return null
        // Системний UI — "Заряд батареї: 15%", "14%", "13%"… щоразу; про заряд Спіді скаже на запит.
        if (sbn.packageName == "com.android.systemui") return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null // підсумок групи — дубль
        val extras = n.extras ?: return null
        // Плеєр (VLC на паузі — сповіщення вже не "ongoing"): це не повідомлення, не озвучуємо.
        if (n.category == Notification.CATEGORY_TRANSPORT || extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val person = NoteText.fromPerson(n.category, sbn.packageName, extras.containsKey(Notification.EXTRA_MESSAGES))
        return Note(sbn.key, app, title.take(80), text.take(300), sbn.postTime, fromPerson = person)
    }
}

/** Список сповіщень із позначками "прочитано" (одна копія на процес Спіді). */
object NotificationStore {
    val notes = NoteList()

    /** Сповіщення будильника / таймера з кнопками (поки дзвонить) — щоб вимкнути голосом. */
    @Volatile var clockAlert: android.service.notification.StatusBarNotification? = null

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
