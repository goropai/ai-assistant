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
import com.speedy.assistant.robot.MoveResult
import com.speedy.assistant.robot.RobotPlatform
import com.speedy.assistant.update.UpdateInfo
import com.speedy.assistant.update.UpdateManager
import com.speedy.assistant.vision.CameraSnapshot
import com.speedy.assistant.vision.Kind
import com.speedy.assistant.vision.PresenceEvent
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
    /** true — Спіді зайнята (камера потрібна для фото), false — вільна. */
    private val onBusyChanged: (Boolean) -> Unit = {},
) {
    private val ears = earsFactory(::onHeard)
    private var busy = false
    private var awaitingCommandUntil = 0L
    private var idleJob: Job? = null
    private var commandJob: Job? = null

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

    /** Події від локальної моделі присутності. */
    fun onPresence(events: List<PresenceEvent>) {
        if (busy) return
        for (event in events) {
            when (event) {
                is PresenceEvent.Look -> face.lookAt(event.x, event.y)
                is PresenceEvent.Arrived -> {
                    runBusy { greetArrival(event.kind) }
                    return
                }
                PresenceEvent.Lost -> face.lookAt(0f, 0f)
            }
        }
    }

    private suspend fun greetArrival(kind: Kind) {
        when (kind) {
            Kind.PERSON -> {
                face.setMood(Mood.HAPPY)
                say(listOf("Привіт!", "О, привіт! Я тут.", "Рада тебе бачити!").random())
                // Можна одразу казати команду, без "Спіді".
                face.setMood(Mood.LISTENING)
                awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
            }
            Kind.CAT -> {
                face.setMood(Mood.SURPRISED)
                say(listOf("Ой, котик!", "Кіця! Привіт, пухнастику.").random())
            }
            Kind.DOG -> {
                face.setMood(Mood.SURPRISED)
                say(listOf("Ой, песик!", "Привіт, собачко!").random())
            }
        }
    }

    /**
     * Дотик до екрана: у спокої — звертання "Спіді"; поки Спіді зайнята (їде, говорить,
     * думає) — негайна зупинка. Під час руху мікрофон вимкнено, тож це головний "стоп".
     */
    fun manualWake() {
        if (busy) interrupt() else runBusy { greet() }
    }

    private fun interrupt() {
        commandJob?.cancel()
        voice.stop()
        scope.launch { robot.stop() }
        face.setSpeaking(false)
        face.setMood(Mood.NEUTRAL)
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
        onBusyChanged(true)
        ears.stop() // не слухаємо самих себе
        idleJob?.cancel()
        commandJob = scope.launch {
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
                onBusyChanged(false)
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
        var looks = 0
        var steps = 0

        // Агентний цикл: модель може подивитись, поїхати й знову подивитись, поки не досягне мети.
        while (true) {
            val action = reply.action
            when (action.type) {
                "look" -> {
                    if (looks++ >= MAX_LOOKS) return speak(reply)
                    if (reply.say.isNotBlank()) speak(reply)
                    face.setMood(Mood.THINKING)
                    val jpeg = photo(action.camera) ?: return
                    reply = brain.ask(Brain.cameraFollowUp(action.camera), jpeg)
                }
                "move" -> {
                    val direction = Direction.parse(action.direction) ?: return speak(reply)
                    val ms = action.durationMs.coerceIn(0, RobotPlatform.MAX_MOVE_MS)
                    // Рух паралельно з мовленням.
                    val moving = scope.async { robot.move(direction, ms) }
                    speak(reply)
                    val result = moving.await()
                    if (result == MoveResult.UNREACHABLE) {
                        face.setMood(Mood.SAD)
                        return say("Ой, я не бачу своїх коліс. Скажи «Спіді, знайди платформу».")
                    }
                    if (!action.thenLook) {
                        if (result == MoveResult.BLOCKED) {
                            face.setMood(Mood.SURPRISED)
                            say("Попереду перешкода, далі не їду.")
                        }
                        return
                    }
                    if (++steps > Brain.MAX_NAV_STEPS) {
                        face.setMood(Mood.NEUTRAL)
                        return say("Я зробила ${Brain.MAX_NAV_STEPS} кроків і зупиняюсь. Скажи, якщо їхати далі.")
                    }
                    // HTTP-відповідь приходить одразу, а платформа їде ще ms — чекаємо, поки зупиниться.
                    if (result == MoveResult.OK) delay(ms + SETTLE_MS)
                    face.setMood(Mood.THINKING)
                    val jpeg = photo("forward") ?: return
                    val status = robot.status()
                    val outcome = when {
                        result == MoveResult.BLOCKED -> "не поїхала: перешкода надто близько"
                        status?.last == "obstacle" -> "зупинилась раніше перед перешкодою"
                        else -> "виконано"
                    }
                    reply = brain.ask(Brain.navFollowUp(action.direction, ms, outcome, status?.distanceCm, steps), jpeg)
                }
                "stop" -> {
                    robot.stop()
                    return speak(reply)
                }
                else -> return speak(reply)
            }
        }
    }

    private suspend fun photo(camera: String): ByteArray? = try {
        this.camera.take(front = camera != "backward")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "camera failed", e)
        face.setMood(Mood.SAD)
        say("Не вдалося увімкнути камеру.")
        null
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
        const val MAX_LOOKS = 3
        const val SETTLE_MS = 300L
        const val LAUNCH_CHECK_DELAY_MS = 5_000L
    }
}
