package com.speedy.assistant

import android.os.SystemClock
import android.util.Log
import com.speedy.assistant.brain.AgentReply
import com.speedy.assistant.brain.Brain
import com.speedy.assistant.brain.LlmException
import com.speedy.assistant.face.FaceView
import com.speedy.assistant.face.Mood
import com.speedy.assistant.robot.Direction
import com.speedy.assistant.robot.RobotPlatform
import com.speedy.assistant.vision.CameraSnapshot
import com.speedy.assistant.voice.Speaker
import com.speedy.assistant.voice.VoiceListener
import com.speedy.assistant.voice.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
                say(if (e is LlmException || e is java.io.IOException) "Ой, не можу зв'язатися зі своїм мозком. Перевір інтернет." else "Ой, щось пішло не так.")
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
        if (isStopCommand(command)) {
            robot.stop()
            face.setMood(Mood.NEUTRAL)
            say("Зупиняюсь.")
            return
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
                val moving = direction?.let { scope.launch { robot.move(it, reply.action.durationMs) } }
                speak(reply)
                moving?.join()
            }
            "stop" -> {
                robot.stop()
                speak(reply)
            }
            else -> speak(reply)
        }
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

    private fun isStopCommand(command: String): Boolean =
        command.lowercase().trim(' ', '.', '!', ',') in setOf("стоп", "стій", "зупинись", "зупинися", "stop")

    private companion object {
        const val TAG = "Assistant"
        const val FOLLOW_UP_WINDOW_MS = 8_000L
        const val SLEEP_AFTER_MS = 5 * 60_000L
        const val MAX_LOOKS = 2
    }
}
