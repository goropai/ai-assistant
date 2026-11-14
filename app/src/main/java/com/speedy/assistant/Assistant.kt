package com.speedy.assistant

import android.os.SystemClock
import android.util.Log
import com.speedy.assistant.brain.AgentReply
import com.speedy.assistant.brain.Brain
import com.speedy.assistant.brain.LlmException
import com.speedy.assistant.brain.MissingKeyException
import com.speedy.assistant.face.FaceView
import com.speedy.assistant.face.Mood
import com.speedy.assistant.robot.Direction
import com.speedy.assistant.robot.RobotPlatform
import com.speedy.assistant.update.UpdateInfo
import com.speedy.assistant.update.UpdateManager
import com.speedy.assistant.vision.CameraSnapshot
import com.speedy.assistant.voice.Speaker
import com.speedy.assistant.voice.VoiceListener
import com.speedy.assistant.voice.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Оркестратор: почути -> подумати (LLM) -> подивитись/поїхати -> відповісти.
 * Працює в головному потоці (lifecycleScope).
 */
class Assistant(
    private val scope: CoroutineScope,
    private val face: FaceView,
    private val voice: Speaker,
    private val brain: Brain,
    private val camera: CameraSnapshot,
    private val robot: RobotPlatform,
    private val findRobot: suspend () -> String?,
    private val updater: UpdateManager,
    earsFactory: (onPhrase: (List<String>) -> Unit) -> VoiceListener,
) {
    private val ears = earsFactory(::onHeard)
    private var busy = false
    private var awaitingCommandUntil = 0L
    private var idleJob: Job? = null

    fun start() {
        if (!busy) ears.start()
        scheduleIdle()
    }

    fun stop() {
        ears.stop()
        voice.stop()
    }

    fun release() {
        ears.release()
    }

    /** Попередження про розряд: дочекатися паузи в розмові й попросити зарядку. */
    fun announceLowBattery(level: Int) {
        scope.launch {
            while (busy) delay(500)
            runBusy {
                face.setMood(Mood.SAD)
                say("Мій заряд $level ${BatteryAlerts.percentWord(level)}. Постав мене, будь ласка, на зарядку.")
            }
        }
    }

    /** Тиха перевірка після запуску: говоримо лише тоді, коли знайшлася нова версія. */
    fun checkUpdatesOnLaunch() {
        if (!updater.isConfigured) return
        scope.launch {
            delay(LAUNCH_CHECK_DELAY_MS)
            val info = try {
                updater.check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "launch update check failed: ${e.message}")
                null
            } ?: return@launch
            while (busy) delay(1000)
            runBusy { announceUpdate(info) }
        }
    }

    /** Дотик до екрана = звертання "Спіді" (зручно для тестів у шумі). */
    fun manualWake() {
        if (busy) return
        runBusy { greet() }
    }

    private fun onHeard(candidates: List<String>) {
        if (busy) return
        val command = WakeWord.extractCommand(candidates)
            ?: candidates.firstOrNull()?.takeIf { SystemClock.uptimeMillis() < awaitingCommandUntil }
            ?: return
        awaitingCommandUntil = 0
        runBusy { if (command.isBlank()) greet() else handle(command) }
    }

    private fun runBusy(block: suspend () -> Unit) {
        busy = true
        ears.stop() // не слухаємо самих себе
        idleJob?.cancel()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "command failed", e)
                face.setMood(Mood.SAD)
                if (e is MissingKeyException) say("Мені потрібен ключ API. Довго натисни на екран, щоб відкрити налаштування.")
                else say(if (e is LlmException || e is java.io.IOException) "Ой, не можу зв'язатися зі своїм мозком. Перевір інтернет." else "Ой, щось пішло не так.")
            } finally {
                face.setSpeaking(false)
                busy = false
                ears.start()
                scheduleIdle()
            }
        }
    }

    private suspend fun greet() {
        face.setMood(Mood.LISTENING)
        say("Так? Слухаю!")
        face.setMood(Mood.LISTENING)
        awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
    }

    private suspend fun handle(command: String) {
        when (LocalCommands.match(command)) {
            LocalCommand.STOP -> {
                robot.stop()
                face.setMood(Mood.NEUTRAL)
                say("Зупиняюсь.")
                return
            }
            LocalCommand.FIND_ROBOT -> return findPlatform()
            LocalCommand.CHECK_UPDATE -> return checkUpdates(install = false)
            LocalCommand.INSTALL_UPDATE -> return checkUpdates(install = true)
            null -> {}
        }

        face.setMood(Mood.THINKING)
        var reply = brain.ask(command)

        // Модель може попросити подивитись камерою — тоді питаємо ще раз уже з кадром.
        var looks = 0
        while (reply.action.type == "look" && looks++ < MAX_LOOKS) {
            if (reply.say.isNotBlank()) speak(reply)
            face.setMood(Mood.THINKING)
            val front = reply.action.camera == "front"
            val jpeg = try {
                camera.take(front)
            } catch (e: Exception) {
                Log.e(TAG, "camera failed", e)
                face.setMood(Mood.SAD)
                say("Не вдалося увімкнути камеру.")
                return
            }
            reply = brain.ask(Brain.cameraFollowUp(reply.action.camera), jpeg)
        }

        when (reply.action.type) {
            "move" -> {
                val direction = Direction.parse(reply.action.direction)
                // Рух паралельно з мовленням.
                val moving = direction?.let { scope.async { robot.move(it, reply.action.durationMs) } }
                speak(reply)
                if (moving != null && !moving.await()) {
                    face.setMood(Mood.SAD)
                    say("Ой, я не бачу своїх коліс. Скажи «Спіді, знайди платформу».")
                }
            }
            "stop" -> {
                robot.stop()
                speak(reply)
            }
            else -> speak(reply)
        }
    }

    private suspend fun findPlatform() {
        face.setMood(Mood.THINKING)
        val url = findRobot()
        if (url == null) {
            face.setMood(Mood.SAD)
            say("Не знайшла платформу. Перевір, що вона увімкнена і в тій самій Wi-Fi мережі.")
        } else {
            face.setMood(Mood.HAPPY)
            say("Знайшла платформу за адресою ${url.removePrefix("http://")}.")
        }
    }

    private suspend fun checkUpdates(install: Boolean) {
        try {
            runUpdate(install)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "update failed", e)
            face.setMood(Mood.SAD)
            // Текст помилки часто англійською — у лог, а вголос лише суть.
            say("Не вдалося ${if (install) "оновитися" else "перевірити оновлення"}. Перевір інтернет і сервер оновлень.")
        }
    }

    private suspend fun runUpdate(install: Boolean) {
        if (!updater.isConfigured) {
            face.setMood(Mood.SAD)
            say("Сервер оновлень не налаштований. Версія ${BuildConfig.VERSION_NAME}.")
            return
        }
        face.setMood(Mood.THINKING)
        val info = updater.check()
        if (info == null) {
            face.setMood(Mood.HAPPY)
            say("У мене найновіша версія, ${BuildConfig.VERSION_NAME}.")
            return
        }
        if (!install) {
            announceUpdate(info)
            return
        }

        face.setMood(Mood.HAPPY)
        say("Завантажую версію ${info.versionName}.")
        face.setMood(Mood.THINKING)
        val apk = updater.download(info)
        if (!updater.canInstall()) {
            face.setMood(Mood.SAD)
            say("Дозволь встановлення з невідомих джерел, а потім скажи «Спіді, оновись» ще раз.")
            updater.openInstallPermissionSettings()
            return
        }
        face.setMood(Mood.HAPPY)
        say("Готово! Підтверди встановлення на екрані.")
        updater.install(apk)
    }

    private suspend fun announceUpdate(info: UpdateInfo) {
        face.setMood(Mood.SURPRISED)
        val notes = info.notes.takeIf { it.isNotBlank() }?.let { " Що нового: $it." }.orEmpty()
        say("Є нова версія ${info.versionName}!$notes Скажи «Спіді, оновись», і я встановлю її.")
    }

    private suspend fun speak(reply: AgentReply) {
        face.setMood(reply.mood)
        say(reply.say)
    }

    private suspend fun say(text: String) {
        face.setSpeaking(true)
        try {
            voice.speak(text)
        } finally {
            face.setSpeaking(false)
        }
    }

    /** Після розмови — повернутись до спокою, а згодом "задрімати". */
    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(if (face.mood == Mood.LISTENING) FOLLOW_UP_WINDOW_MS else 5_000)
            face.setMood(Mood.NEUTRAL)
            delay(SLEEP_AFTER_MS)
            face.setMood(Mood.SLEEPY)
        }
    }

    private companion object {
        const val TAG = "Assistant"
        const val FOLLOW_UP_WINDOW_MS = 8_000L
        const val SLEEP_AFTER_MS = 5 * 60_000L
        const val MAX_LOOKS = 2
        const val LAUNCH_CHECK_DELAY_MS = 5_000L
    }
}
