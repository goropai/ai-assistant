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
import com.speedy.assistant.vision.People
import com.speedy.assistant.vision.PresenceEvent
import com.speedy.assistant.voice.Chirp
import com.speedy.assistant.voice.ChirpPlayer
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
    private val chirps: ChirpPlayer,
    /** Заряд у відсотках (-1 — невідомо) і чи заряджається. */
    private val battery: () -> Pair<Int, Boolean> = { -1 to false },
    private val people: People? = null,
    private val weather: com.speedy.assistant.weather.WeatherService? = null,
    private val locator: com.speedy.assistant.location.Locator? = null,
    earsFactory: (onPhrase: (List<String>) -> Unit) -> VoiceListener,
    /** true — Спіді зайнята (камера потрібна для фото), false — вільна. */
    private val onBusyChanged: (Boolean) -> Unit = {},
) {
    private val ears = earsFactory(::onHeard)
    private var busy = false
    private var awaitingCommandUntil = 0L
    /** Спитала "як тебе звати?" — наступна фраза, ймовірно, ім'я. */
    private var awaitingName = false
    private var idleJob: Job? = null
    private var commandJob: Job? = null
    private val desires = Desires()
    /** Коли з нею востаннє взаємодіяли (для "нудьги"). */
    private var lastInteraction = SystemClock.uptimeMillis()

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
                chirps.play(Chirp.LOW_BATTERY)
                say("Мій заряд $level ${BatteryAlerts.percentWord(level)}. Постав мене, будь ласка, на зарядку.")
            }
        }
    }

    /** Сигнал стану без слів (мережа тощо). Якщо Спіді зайнята — пропускаємо. */
    fun chirp(chirp: Chirp, mood: Mood? = null) {
        if (busy) return
        runBusy {
            mood?.let { face.setMood(it) }
            chirps.play(chirp)
        }
    }

    /** Раз на хвилину: чи не хочеться чогось (на зарядку, уваги). */
    fun checkDesires(hour: Int, someoneNear: Boolean, batteryPercent: Int, charging: Boolean) {
        if (busy) return
        val wish = desires.check(SystemClock.uptimeMillis(), hour, someoneNear, lastInteraction, batteryPercent, charging)
            ?: return
        chirp(wish, if (wish == Chirp.LOW_BATTERY) Mood.SLEEPY else Mood.HAPPY)
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
        // Обличчя важливіші за загальне "хтось прийшов": вітаємо на ім'я або знайомимось.
        events.firstOrNull { it is PresenceEvent.Recognized || it is PresenceEvent.Stranger }?.let { event ->
            runBusy {
                if (event is PresenceEvent.Recognized) greetKnown(event.name) else askName()
            }
            return
        }
        for (event in events) {
            when (event) {
                is PresenceEvent.Look -> face.lookAt(event.x, event.y)
                is PresenceEvent.Arrived -> {
                    runBusy { greetArrival(event.kind) }
                    return
                }
                PresenceEvent.Lost -> face.lookAt(0f, 0f)
                is PresenceEvent.Recognized, PresenceEvent.Stranger -> {} // оброблено вище
            }
        }
    }

    private suspend fun greetKnown(name: String) {
        face.setMood(Mood.HAPPY)
        chirps.play(Chirp.HAPPY)
        say(listOf("Привіт, $name!", "О, $name! Привіт!", "$name, рада тебе бачити!").random())
        face.setMood(Mood.LISTENING)
        awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
    }

    private suspend fun askName() {
        face.setMood(Mood.SURPRISED)
        chirps.play(Chirp.CURIOUS)
        face.setMood(Mood.HAPPY)
        say("Привіт! Я Спіді. А як тебе звати?")
        face.setMood(Mood.LISTENING)
        awaitingName = true
        awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
    }

    private suspend fun introduce(name: String) {
        awaitingName = false
        val p = people ?: return say("Я поки не вмію запам'ятовувати обличчя.")
        if (p.introduce(name, SystemClock.uptimeMillis())) {
            face.setMood(Mood.HAPPY)
            chirps.play(Chirp.HAPPY)
            say("Приємно познайомитись, $name! Я тебе запам'ятала.")
        } else {
            val known = p.presentNames(SystemClock.uptimeMillis())
            face.setMood(Mood.THINKING)
            if (known.isNotEmpty()) say("Але ж я тебе знаю — ти ${known.first()}. Якщо помилилась, скажи «забудь мене».")
            else say("Подивись на мене, щоб я побачила твоє обличчя, і скажи ще раз.")
        }
    }

    /** Вітання без слів: радісний звук і очі; говорить Спіді лише у відповідь. */
    private suspend fun greetArrival(kind: Kind) {
        when (kind) {
            Kind.PERSON -> {
                face.setMood(Mood.HAPPY)
                chirps.play(Chirp.HAPPY)
                // Можна одразу казати команду, без "Спіді".
                face.setMood(Mood.LISTENING)
                awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
            }
            Kind.CAT, Kind.DOG -> {
                face.setMood(Mood.SURPRISED)
                chirps.play(Chirp.CURIOUS)
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
        val inWindow = SystemClock.uptimeMillis() < awaitingCommandUntil
        if (awaitingName && inWindow && WakeWord.extractCommand(candidates) == null) {
            val name = candidates.firstNotNullOfOrNull { LocalCommands.parseName(it, answer = true) }
            if (name != null) {
                awaitingCommandUntil = 0
                lastInteraction = SystemClock.uptimeMillis()
                runBusy { introduce(name) }
                return
            }
        }
        if (!inWindow) awaitingName = false
        val command = WakeWord.extractCommand(candidates)
            ?: candidates.firstOrNull()?.takeIf { inWindow }
            ?: return
        awaitingCommandUntil = 0
        lastInteraction = SystemClock.uptimeMillis()
        runBusy { if (command.isBlank()) greet() else handle(command) }
    }

    private fun runBusy(block: suspend () -> Unit) {
        busy = true
        onBusyChanged(true)
        ears.stop() // не слухаємо самих себе
        idleJob?.cancel()
        commandJob = scope.launch {
            try {
                delay(VoiceListener.UNMUTE_DELAY_MS) // поки розпізнавач не замовкне, звук приглушено
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "command failed", e)
                face.setMood(Mood.SAD)
                if (e is LlmException || e is java.io.IOException) chirps.play(Chirp.NO_NETWORK)
                if (e is MissingKeyException) say("Мені потрібен ключ API. Довго натисни на екран, щоб відкрити налаштування.")
                else say(if (e is LlmException || e is java.io.IOException) "Ой, не можу зв'язатися зі своїм мозком. Перевір інтернет." else "Ой, щось пішло не так.")
            } finally {
                face.setSpeaking(false)
                busy = false
                onBusyChanged(false)
                // Спитала й чекає відповіді — слухаємо одразу, щоб не обрізати початок фрази.
                ears.start(listenNow = SystemClock.uptimeMillis() < awaitingCommandUntil)
                scheduleIdle()
            }
        }
    }

    /** Звернулися без команди: коротке "бі-іп" замість слів — і чекаємо команду. */
    private suspend fun greet() {
        lastInteraction = SystemClock.uptimeMillis()
        face.setMood(Mood.LISTENING)
        chirps.play(Chirp.ACK)
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
            LocalCommand.INTRODUCE -> {
                val name = LocalCommands.parseName(command) ?: return say("Не розчула ім'я. Скажи ще раз?")
                return introduce(name)
            }
            LocalCommand.FORGET_ME -> {
                val name = people?.presentNames(SystemClock.uptimeMillis())?.firstOrNull()
                    ?: return say("Я не бачу, кого забути. Подивись на мене.")
                people.forget(name)
                face.setMood(Mood.SAD)
                return say("Добре, я забула тебе, $name.")
            }
            LocalCommand.WHO_AM_I -> {
                val name = people?.presentNames(SystemClock.uptimeMillis())?.firstOrNull()
                if (name != null) {
                    face.setMood(Mood.HAPPY)
                    return say("Ти — $name!")
                }
                return askName()
            }
            LocalCommand.WEATHER -> return tellWeather(command)
            LocalCommand.WHERE -> return tellWhere()
            LocalCommand.TIME -> {
                face.setMood(Mood.HAPPY)
                return say("Зараз ${java.text.SimpleDateFormat("H:mm", java.util.Locale("uk", "UA")).format(java.util.Date())}.")
            }
            LocalCommand.BATTERY -> {
                val (percent, charging) = battery()
                if (percent < 0) return say("Не знаю, скільки в мене заряду.")
                face.setMood(if (percent < Desires.LOW_BATTERY && !charging) Mood.SLEEPY else Mood.HAPPY)
                return say("Мій заряд $percent ${BatteryAlerts.percentWord(percent)}" + if (charging) ", заряджаюсь." else ".")
            }
            null -> {}
        }

        face.setMood(Mood.THINKING)
        var reply = askBrain { brain.ask(command) }
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
                    reply = askBrain { brain.ask(Brain.cameraFollowUp(action.camera), jpeg) }
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
                    reply = askBrain { brain.ask(Brain.navFollowUp(action.direction, ms, outcome, status?.distanceCm, steps), jpeg) }
                }
                "stop" -> {
                    robot.stop()
                    return speak(reply)
                }
                else -> return speak(reply)
            }
        }
    }

    /** Погода без моделі: місто з фрази або там, де Спіді (GPS / мережа), прогноз — Open-Meteo. */
    private suspend fun tellWeather(command: String) {
        val service = weather ?: return say("Я поки не вмію дивитися погоду.")
        val query = com.speedy.assistant.weather.WeatherText.parse(command)
        face.setMood(Mood.THINKING)
        try {
            val (lat, lon, name) = if (query.place != null) {
                val place = service.findPlace(query.place)
                    ?: return say("Не знайшла місто ${query.place}.").also { face.setMood(Mood.SAD) }
                Triple(place.latitude, place.longitude, place.name)
            } else {
                val here = locator?.locate()
                    ?: return say("Не знаю, де я. Дозволь визначати місцезнаходження або назви місто.").also { face.setMood(Mood.SAD) }
                Triple(here.latitude, here.longitude, null)
            }
            val forecast = service.forecast(lat, lon)
            face.setMood(Mood.HAPPY)
            say(com.speedy.assistant.weather.WeatherText.answer(forecast, query, name))
        } catch (e: java.io.IOException) {
            Log.w(TAG, "weather failed", e)
            face.setMood(Mood.SAD)
            chirps.play(Chirp.NO_NETWORK)
            say("Не вдалося дізнатися погоду. Перевір інтернет.")
        }
    }

    private suspend fun tellWhere() {
        val loc = locator ?: return say("Я не вмію визначати місцезнаходження.")
        face.setMood(Mood.THINKING)
        val here = loc.locate() ?: return say("Не можу визначити, де я. Перевір, чи увімкнене місцезнаходження.").also { face.setMood(Mood.SAD) }
        val name = loc.placeName(here)
        face.setMood(Mood.HAPPY)
        say(name?.let { "Ми тут: $it." } ?: "Мої координати: %.3f, %.3f.".format(here.latitude, here.longitude))
    }

    /** Запит до "мозку" з оновленням індикатора зв'язку на екрані. */
    private suspend fun <T> askBrain(block: suspend () -> T): T = try {
        block().also { face.setBrainStatus(com.speedy.assistant.brain.BrainStatus.OK) }
    } catch (e: MissingKeyException) {
        face.setBrainStatus(com.speedy.assistant.brain.BrainStatus.NO_KEY)
        throw e
    } catch (e: java.io.IOException) {
        face.setBrainStatus(com.speedy.assistant.brain.BrainStatus.DOWN)
        throw e
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
