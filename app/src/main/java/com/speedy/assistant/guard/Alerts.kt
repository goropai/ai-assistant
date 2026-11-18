package com.speedy.assistant.guard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.speedy.assistant.settings.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Що сталося і що вдалося зняти. */
data class Alert(val text: String, val photo: File? = null, val video: File? = null, val audio: File? = null)

/**
 * Канали тривоги з налаштувань: Telegram-бот, пошта (SMTP), SMS, дзвінок. Канал діє, якщо заповнений.
 * Усе мережеве — блокуюче, викликати не з головного потоку.
 */
class Alerts(private val context: Context, http: OkHttpClient, private val settings: AppSettings) {
    private val http = http.newBuilder().callTimeout(3, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES).build()

    val telegram: Boolean get() = settings.guardTelegramToken.isNotBlank() && settings.guardTelegramChat.isNotBlank()
    val email: Boolean get() = settings.guardSmtpHost.isNotBlank() && settings.guardSmtpUser.isNotBlank() &&
        settings.guardSmtpPassword.isNotBlank() && settings.guardEmailTo.isNotBlank()
    val sms: Boolean get() = settings.guardSmsNumber.isNotBlank() && granted(android.Manifest.permission.SEND_SMS)
    val call: Boolean get() = settings.guardCallNumber.isNotBlank() && granted(android.Manifest.permission.CALL_PHONE)
    val any: Boolean get() = telegram || email || sms || call

    /** Коротко, куди піде тривога (для голосу й налаштувань). */
    fun describe(): String = listOfNotNull(
        "Telegram".takeIf { telegram }, "пошту".takeIf { email }, "SMS".takeIf { sms }, "дзвінок".takeIf { call },
    ).joinToString(", ")

    /** Одразу — текст (Telegram і SMS), щоб людина знала ще до того, як знімемо відео. */
    fun sendText(text: String) {
        if (telegram) safe("Telegram") { telegramCall("sendMessage", mapOf("text" to text)) }
        if (sms) safe("SMS") {
            val sms = SmsManager.getDefault()
            sms.sendMultipartTextMessage(settings.guardSmsNumber, null, sms.divideMessage(text), null, null)
        }
    }

    /** Знімки й записи: у Telegram окремими повідомленнями, на пошту — одним листом. */
    fun sendMedia(alert: Alert) {
        if (telegram) {
            alert.photo?.let { f -> safe("Telegram фото") { telegramFile("sendPhoto", "photo", f, "image/jpeg") } }
            alert.video?.let { f -> safe("Telegram відео") { telegramFile("sendVideo", "video", f, "video/mp4") } }
            alert.audio?.let { f -> safe("Telegram звук") { telegramFile("sendDocument", "document", f, "audio/wav") } }
        }
        if (email) safe("пошта") { sendEmail(alert) }
    }

    /** Подзвонити: людина почує, що відбувається в кімнаті. Після розмови — назад до Спіді. */
    @SuppressLint("MissingPermission")
    fun callNow(onEnded: () -> Unit) {
        if (!call) return
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            var started = false
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, number: String?) {
                    if (state != TelephonyManager.CALL_STATE_IDLE) started = true
                    else if (started) {
                        tm.listen(this, LISTEN_NONE)
                        onEnded()
                    }
                }
            }
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(settings.guardCallNumber)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "дзвінок не вдався", e)
        }
    }

    /** Пробна тривога (без дзвінка): що вдалося, а що ні — для налаштувань. */
    fun test(): String {
        val text = "Спіді: пробна тривога. Якщо ти це читаєш — канал працює."
        val result = mutableListOf<String>()
        fun attempt(what: String, block: () -> Unit) {
            result += try { block(); "$what — так" } catch (e: Exception) { "$what — ні (${e.message?.take(80)})" }
        }
        if (telegram) attempt("Telegram") { telegramCall("sendMessage", mapOf("text" to text)) }
        if (email) attempt("пошта") { sendEmail(Alert(text)) }
        if (sms) attempt("SMS") { SmsManager.getDefault().sendTextMessage(settings.guardSmsNumber, null, text, null, null) }
        if (settings.guardSmsNumber.isNotBlank() && !sms) result += "SMS — немає дозволу"
        if (settings.guardCallNumber.isNotBlank()) result += if (call) "дзвінок — налаштовано (у пробі не дзвоню)" else "дзвінок — немає дозволу"
        return result.joinToString("\n").ifEmpty { "Жоден канал не заповнено." }
    }

    private fun telegramCall(method: String, fields: Map<String, String>) {
        val body = okhttp3.FormBody.Builder().add("chat_id", settings.guardTelegramChat)
            .apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        execute(Request.Builder().url(telegramUrl(method)).post(body).build())
    }

    private fun telegramFile(method: String, field: String, file: File, type: String) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", settings.guardTelegramChat)
            .addFormDataPart(field, file.name, file.asRequestBody(type.toMediaType()))
            .build()
        execute(Request.Builder().url(telegramUrl(method)).post(body).build())
    }

    private fun telegramUrl(method: String) = "https://api.telegram.org/bot${settings.guardTelegramToken}/$method"

    private fun execute(request: Request) {
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}: ${r.body?.string()?.take(200)}")
        }
    }

    private fun sendEmail(alert: Alert) {
        val port = settings.guardSmtpPort
        val props = Properties().apply {
            put("mail.smtp.host", settings.guardSmtpHost)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            if (port == 465) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "30000")
            put("mail.smtp.timeout", "120000")
        }
        val session = javax.mail.Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() =
                javax.mail.PasswordAuthentication(settings.guardSmtpUser, settings.guardSmtpPassword)
        })
        val message = javax.mail.internet.MimeMessage(session).apply {
            setFrom(javax.mail.internet.InternetAddress(settings.guardSmtpUser))
            setRecipients(javax.mail.Message.RecipientType.TO, javax.mail.internet.InternetAddress.parse(settings.guardEmailTo))
            setSubject(alert.text.take(120), "UTF-8")
            val parts = javax.mail.internet.MimeMultipart()
            parts.addBodyPart(javax.mail.internet.MimeBodyPart().apply { setText(alert.text, "UTF-8") })
            listOfNotNull(alert.photo, alert.video, alert.audio).filter { it.exists() }.forEach { f ->
                parts.addBodyPart(javax.mail.internet.MimeBodyPart().apply { attachFile(f) })
            }
            setContent(parts)
        }
        javax.mail.Transport.send(message)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
            Log.i(TAG, "надіслано: $what")
        } catch (e: Exception) {
            Log.w(TAG, "$what: не вдалося — ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Alerts"

        /**
         * Chat id для бота: людина пише боту будь-що (/start), а ми беремо чат з останнього повідомлення.
         * null — повідомлень немає або токен хибний.
         */
        fun findTelegramChat(http: OkHttpClient, token: String): String? = try {
            http.newCall(Request.Builder().url("https://api.telegram.org/bot${token.trim()}/getUpdates").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val results = JSONObject(r.body!!.string()).optJSONArray("result") ?: return null
                (results.length() - 1 downTo 0).asSequence()
                    .mapNotNull { results.getJSONObject(it).optJSONObject("message")?.optJSONObject("chat")?.optLong("id") }
                    .firstOrNull()?.toString()
            }
        } catch (e: Exception) {
            null
        }
    }
}
