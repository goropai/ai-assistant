package com.speedy.assistant.guard

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Вартуй" / "досить вартувати" — розбір без моделі. */
object GuardCommands {
    private val arm = Regex(
        "(?:^|\\s)(?:вартуй|стережи|охороняй|посторожи|постережи|повартуй)(?:\\s|$)|" +
            "(?:увімкни|включи|ввімкни|запусти)\\s+(?:режим\\s+)?(?:сторожа|охорони|охоронця|вартового)|^режим\\s+(?:сторожа|охорони)",
    )
    private val disarm = Regex(
        "(?:досить|годі|не\\s+треба|перестань|припини|можна\\s+не)\\s+(?:вартувати|стерегти|охороняти|сторожити)|" +
            "(?:вимкни|виключи|зупини)\\s+(?:режим\\s+)?(?:сторожа|охорони|охоронця|вартового)",
    )

    /** true — увімкнути, false — вимкнути, null — не про це. */
    fun parse(phrase: String): Boolean? {
        val t = phrase.lowercase().replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            disarm.containsMatchIn(t) -> false
            arm.containsMatchIn(t) -> true
            else -> null
        }
    }
}

/**
 * Режим сторожа: камери й мікрофон стежать, Спіді мовчить. Незнайомі люди й тварини, рух, підозрілі
 * звуки — тривога: одразу текст (Telegram, SMS), далі фото, 10 с відео зі звуком і запис звуку
 * (Telegram, пошта), насамкінець — дзвінок. Записи лишаються в [root]. Головний потік.
 */
class GuardMode(
    private val scope: CoroutineScope,
    private val alerts: Alerts,
    private val root: File,
    private val cooldownMs: () -> Long,
    private val soundsOn: () -> Boolean,
    cameraFactory: (onFriend: (String) -> Unit, onDetect: (String) -> Unit) -> GuardCamera,
    soundsFactory: (worth: (GuardSound) -> Boolean, onSound: (GuardSound, File) -> Unit) -> GuardSounds,
    /** Знайомий поруч — можна вимкнути голосом (Assistant вмикає слух). */
    private val onFriend: (String) -> Unit,
    /** Після дзвінка — Спіді знову на екран (камера працює, лише коли вона на екрані). */
    private val bringToFront: () -> Unit,
) {
    private val camera = cameraFactory(::friend, ::detected)
    private val sounds = soundsFactory(::worth) { sound, clip -> scope.launch { raise("чую: ${sound.ua}", clip) } }
    private var armJob: Job? = null
    private var alarmJob: Job? = null
    private var lastAlarm = Long.MIN_VALUE / 2
    @Volatile private var friendSeenAt = Long.MIN_VALUE / 2
    /** Знайомий поруч: мікрофон віддано слуху Спіді (голос "досить вартувати"). */
    private var micYielded = false

    /** Увімкнено (зокрема поки йде відлік перед стартом). */
    var on = false; private set
    /** Справді стежить (відлік минув). */
    var armed = false; private set

    /** Знайоме обличчя бачили щойно — голос знайомого, вимкнення без PIN. */
    val friendNear: Boolean get() = SystemClock.uptimeMillis() - friendSeenAt < FRIEND_MS

    fun arm(delayMs: Long) {
        if (on) return
        on = true
        armJob = scope.launch {
            delay(delayMs)
            armed = true
            lastAlarm = Long.MIN_VALUE / 2
            friendSeenAt = Long.MIN_VALUE / 2
            camera.start()
            startSounds()
            Log.i(TAG, "сторож увімкнено; тривога: ${alerts.describe().ifEmpty { "нікуди (канали не налаштовано)" }}")
        }
    }

    fun disarm() {
        if (!on) return
        on = false
        armed = false
        armJob?.cancel()
        alarmJob?.cancel()
        camera.stop()
        sounds.stop()
        micYielded = false
        Log.i(TAG, "сторож вимкнено")
    }

    /** Спіді знову на екрані (після дзвінка) — камера відновиться сама, але прив'язку оновимо. */
    fun resume() {
        if (armed && !camera.running && alarmJob?.isActive != true) camera.start()
    }

    /** Знайомий пішов (вікно голосу минуло) — мікрофон знову сторожу. */
    fun reclaimMic() {
        if (!armed || !micYielded || friendNear) return
        micYielded = false
        startSounds()
    }

    private fun friend(name: String) {
        if (!armed) return
        friendSeenAt = SystemClock.uptimeMillis()
        if (!micYielded) {
            Log.i(TAG, "знайомий: $name — слухаю голос")
            micYielded = true
            sounds.stop()
            onFriend(name)
        }
    }

    private fun worth(sound: GuardSound): Boolean = soundsOn() && !(sound == GuardSound.VOICE && friendNear)

    private fun detected(what: String) {
        scope.launch { raise(what, null) }
    }

    private fun startSounds() {
        if (soundsOn()) sounds.start(scope)
    }

    private suspend fun raise(what: String, clip: File?) {
        if (!armed) return
        val now = SystemClock.uptimeMillis()
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        if (now - lastAlarm < cooldownMs() || alarmJob?.isActive == true) {
            Log.i(TAG, "тривога ($what) — щойно надсилала, лише записую")
            return
        }
        lastAlarm = now
        val folder = File(root, SimpleDateFormat("yyyy-MM-dd/HH-mm-ss", Locale.US).format(Date())).apply { mkdirs() }
        sounds.folder = folder
        val text = "Спіді: тривога о $time — $what."
        Log.i(TAG, text)
        alarmJob = scope.launch {
            withContext(Dispatchers.IO) { alerts.sendText(text) }
            val photo = camera.takePhoto(File(folder, "photo.jpg"))
            // Відео пише свій звук — мікрофон на цей час звільняємо.
            val listening = sounds.active
            sounds.stop()
            val video = camera.recordVideo(File(folder, "video.mp4"), VIDEO_S)
            if (armed && listening && !micYielded) startSounds()
            withContext(Dispatchers.IO) {
                // Звук записано до тривоги (тека ще не була відома) — переносимо до решти.
                val audio = clip?.takeIf { it.exists() }?.let { c -> File(folder, "sound.wav").also { c.copyTo(it, true); c.delete() } }
                alerts.sendMedia(Alert(text, photo, video, audio))
            }
            if (armed && alerts.call) {
                sounds.stop() // розмова — через мікрофон телефону
                alerts.callNow {
                    scope.launch {
                        bringToFront()
                        delay(2_000)
                        resume()
                        if (armed && !micYielded) startSounds()
                    }
                }
            }
        }
    }

    fun release() {
        disarm()
        camera.release()
    }

    private companion object {
        const val TAG = "Guard"
        const val VIDEO_S = 10
        /** Скільки після знайомого обличчя голос не насторожує й діє "досить вартувати". */
        const val FRIEND_MS = 2 * 60_000L
    }
}
