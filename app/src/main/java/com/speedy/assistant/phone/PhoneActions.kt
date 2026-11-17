package com.speedy.assistant.phone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Дії на самому телефоні: застосунки, будильник, таймер, контакти, календар. */
class PhoneActions(private val context: Context) {

    data class Contact(val name: String, val number: String)

    private fun allowed(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** @return назва запущеного застосунку або null (не знайшла). */
    suspend fun openApp(spoken: String): String? = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val best = pm.queryIntentActivities(launcher, 0)
            .map { it to PhoneCommands.appScore(spoken, it.loadLabel(pm).toString()) }
            .filter { it.first.activityInfo.packageName != context.packageName }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 0.7f } ?: return@withContext null
        val info = best.first.activityInfo
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(info.packageName, info.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { start(intent) }
        best.first.loadLabel(pm).toString()
    }

    fun setAlarm(hour: Int, minute: Int): Boolean = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Спіді")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    fun setTimer(seconds: Int): Boolean = start(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Спіді")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Контакти, чиє ім'я починається з основи сказаного ("у Кості" → "Кост…"). null — немає дозволу. */
    suspend fun findContacts(spoken: String): List<Contact>? = withContext(Dispatchers.IO) {
        if (!allowed(Manifest.permission.READ_CONTACTS)) return@withContext null
        val stem = PhoneCommands.nameStem(spoken)
        val result = mutableListOf<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("$stem%", "% $stem%"),
            null,
        )?.use { c ->
            while (c.moveToNext() && result.size < 5) result += Contact(c.getString(0), c.getString(1))
        }
        result.distinctBy { it.number.filter(Char::isDigit) }
    }

    /** Відкриває номеронабирач із номером — дзвонить людина сама (без випадкових дзвінків). */
    fun dial(number: String): Boolean =
        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Події на сьогодні: "10:00 Стоматолог". null — немає дозволу. */
    suspend fun todayEvents(): List<String>? = withContext(Dispatchers.IO) {
        if (!allowed(Manifest.permission.READ_CALENDAR)) return@withContext null
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val end = start + 24 * 3600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val time = SimpleDateFormat("H:mm", Locale("uk", "UA"))
        val events = mutableListOf<String>()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && events.size < 8) {
                val title = c.getString(1).orEmpty().ifBlank { "подія" }
                events += if (c.getInt(2) == 1) "увесь день: $title" else "${time.format(Date(c.getLong(0)))} $title"
            }
        }
        events
    }

    /**
     * Подія в календарі телефона (Google Календар, якщо він синхронізований — туди ж). Календар —
     * основний із доступних для запису (Google — перевага). null — немає дозволу; false — немає календаря.
     */
    suspend fun addEvent(title: String, start: Long, allDay: Boolean): Boolean? = withContext(Dispatchers.IO) {
        if (!allowed(Manifest.permission.WRITE_CALENDAR) || !allowed(Manifest.permission.READ_CALENDAR)) return@withContext null
        val calendarId = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
            null, null,
        )?.use { c ->
            val found = mutableListOf<Triple<Long, Boolean, String>>()
            while (c.moveToNext()) found += Triple(c.getLong(0), c.getInt(1) == 1, c.getString(2).orEmpty())
            found.maxWithOrNull(compareBy<Triple<Long, Boolean, String>> { it.second }.thenBy { it.third == "com.google" })?.first
        } ?: return@withContext false
        val zone = java.util.TimeZone.getDefault()
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            if (allDay) {
                // Подія на весь день — опівночі UTC того дня, тривалість добу.
                val day = Calendar.getInstance().apply { timeInMillis = start }
                val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    clear(); set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
                }.timeInMillis
                put(CalendarContract.Events.DTSTART, utc)
                put(CalendarContract.Events.DTEND, utc + 24 * 3600_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + 3600_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
    }

    /**
     * Вимкнути / відкласти будильник чи таймер, що дзвонить: кнопка з його сповіщення ("Відхилити",
     * "Відкласти"); немає — стандартний запит до годинника (Android 6+). false — нічого не дзвонить.
     */
    fun stopAlarm(snooze: Boolean): Boolean {
        NotificationStore.clockAlert?.notification?.actions?.let { actions ->
            val wanted = if (snooze) Regex("(?i)відклад|повтор|дрімат|snooze|пізніше")
            else Regex("(?i)відхил|закри|стоп|зупин|вимк|скасув|dismiss|stop|cancel|ок$")
            actions.firstOrNull { wanted.containsMatchIn(it.title?.toString().orEmpty()) }?.let { a ->
                return try { a.actionIntent.send(); NotificationStore.clockAlert = null; true } catch (e: Exception) { false }
            }
        }
        val action = if (snooze) AlarmClock.ACTION_SNOOZE_ALARM else AlarmClock.ACTION_DISMISS_ALARM
        return start(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
