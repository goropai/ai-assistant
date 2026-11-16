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

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
