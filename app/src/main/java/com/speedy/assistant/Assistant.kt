package com.speedy.assistant

import android.os.SystemClock
import android.util.Log
import com.speedy.assistant.brain.AgentReply
import com.speedy.assistant.brain.Brain
import com.speedy.assistant.brain.LlmException
import com.speedy.assistant.brain.MissingKeyException
import com.speedy.assistant.face.FaceView
import com.speedy.assistant.face.Mood
import com.speedy.assistant.initiative.PauseCheck
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import com.speedy.assistant.books.BookCommand
import com.speedy.assistant.books.Books
import com.speedy.assistant.books.Lang
import com.speedy.assistant.books.Place
import java.io.File

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
    private val pets: com.speedy.assistant.vision.Pets? = null,
    /** Пошук в інтернеті: модель (Google / Claude web search), запасний — Вікіпедія. */
    private val webSearch: (suspend (String) -> String?)? = null,
    private val memory: com.speedy.assistant.brain.Memory? = null,
    private val phone: com.speedy.assistant.phone.PhoneActions? = null,
    private val currency: com.speedy.assistant.weather.Currency? = null,
    private val profiles: com.speedy.assistant.people.Profiles? = null,
    private val reminders: com.speedy.assistant.initiative.Reminders? = null,
    private val english: com.speedy.assistant.english.EnglishCourse? = null,
    /** Чи є інтернет (англійський розпізнавач — онлайн). */
    private val isOnline: () -> Boolean = { true },
    /** Чи Спіді на екрані (інакше відкритий інший застосунок). */
    private val inForeground: () -> Boolean = { true },
    /** Повернути Спіді на екран. */
    private val bringToFront: () -> Unit = {},
    /** Відкрити налаштування доступу: "notes" — сповіщення, "hands" — спеціальні можливості. */
    private val openAccessSettings: (String) -> Unit = {},
    /** Денний ліміт розмов дитини і скільки хвилин дозволено (0 — без обмеження). */
    private val childLimit: com.speedy.assistant.settings.ChildLimit? = null,
    private val childLimitMinutes: () -> Int = { 0 },
    private val weather: com.speedy.assistant.weather.WeatherService? = null,
    private val locator: com.speedy.assistant.location.Locator? = null,
    private val books: com.speedy.assistant.books.Bookshelf? = null,
    /** Чи є доступ до файлів (книги шукаємо по всьому сховищу). */
    private val hasFileAccess: () -> Boolean = { true },
    /** Фільми, аудіокниги, музика — у VLC. */
    private val media: com.speedy.assistant.media.MediaShelf? = null,
    private val playback: com.speedy.assistant.media.MediaPlayback? = null,
    private val vlc: com.speedy.assistant.media.Vlc? = null,
    /** Відео, якого немає в телефоні: YouTube / NewPipe / браузер. */
    private val onlineVideo: com.speedy.assistant.media.OnlineVideo? = null,
    /** Переклад uk/ru/en (книги й фрази). */
    private val translator: com.speedy.assistant.translate.Translator? = null,
    /** Нічний режим: слухати хропіння до ранку. */
    private val night: com.speedy.assistant.night.NightWatch? = null,
    /** Відбиток голосу: модель мовця і знайомі голоси. */
    private val voicePrints: com.speedy.assistant.voice.VoicePrints? = null,
    private val voiceBook: com.speedy.assistant.voice.VoiceBook? = null,
    /** Налаштування (зміни голосом — лише після PIN) і вібрація. */
    private val settings: com.speedy.assistant.settings.AppSettings? = null,
    private val buzzer: com.speedy.assistant.voice.Buzzer? = null,
    /** Показати віконце PIN; після правильного — [onOk]. */
    private val confirmWithPin: (onOk: () -> Unit) -> Unit = {},
    /** Налаштування змінилися — оновити камеру, "мозок" тощо. */
    private val onSettingsChanged: () -> Unit = {},
    private val nightLog: com.speedy.assistant.night.NightLog? = null,
    /** Увімкнули / вимкнули нічний режим — приглушити екран, вимкнути камеру. */
    private val onNight: (Boolean) -> Unit = {},
    /** Режим сторожа: камери й мікрофон стежать, тривога — у Telegram, пошту, SMS, дзвінком. */
    private val guard: com.speedy.assistant.guard.GuardMode? = null,
    private val guardAlerts: com.speedy.assistant.guard.Alerts? = null,
    /** Сторож увімкнено / вимкнено (екран, камера присутності). */
    private val onGuard: (Boolean) -> Unit = {},
    /** Іде урок: камера присутності працює й під час "зайнятості" (хто відповідає). */
    private val onLesson: (Boolean) -> Unit = {},
    /** Записати кілька секунд звуку навколо ("що це за пісня?"); null — мікрофон зайнятий. */
    private val recordSound: suspend (seconds: Int) -> com.speedy.assistant.voice.SoundClip.Clip? = { null },
    /** Модель приймає звук (Gemini) — у паузі розмови сказане передаємо їй. */
    private val hearsAudio: () -> Boolean = { false },
    /** Людину щойно видно передньою камерою (камера присутності). */
    private val personInFront: () -> Boolean = { false },
    /** Чи є людина на знімку (задня камера); null — не розібрати. */
    private val personInPhoto: suspend (ByteArray) -> Boolean? = { null },
    /** Екран для уроку письма (обвести пальцем обрис); null — немає. */
    private val tracer: com.speedy.assistant.school.Tracer? = null,
    /** Запит до "мозку" за JSON (уроки, цікаві факти); null — не вдалось. */
    private val generateJson: suspend (system: String, prompt: String) -> String? = { _, _ -> null },
    /** Прогрес уроків математики, логіки тощо. */
    private val school: com.speedy.assistant.school.SchoolProgress? = null,
    earsFactory: (onPhrase: (List<String>) -> Unit) -> VoiceListener,
    /** true — Спіді зайнята (камера потрібна для фото), false — вільна. */
    private val onBusyChanged: (Boolean) -> Unit = {},
) {
    private val ears = earsFactory(::onHeard)
    private var busy = false
    private var awaitingCommandUntil = 0L
    /** Розмова триває: "Спіді" потрібне лише на початку, далі — просто говорити. */
    private var inConversation = false
    /** Спитала "як тебе звати?" — наступна фраза, ймовірно, ім'я. */
    private var awaitingName = false
    /** Спитала "як його звати?" про тварину — наступна фраза, ймовірно, кличка. */
    private var awaitingPetName = false
    private var idleJob: Job? = null
    private var commandJob: Job? = null
    private val desires = Desires()
    /** Коли з нею востаннє взаємодіяли (для "нудьги"). */
    private var lastInteraction = SystemClock.uptimeMillis()
    /** Коли людина востаннє щось сказала Спіді (не з моменту запуску, як [lastInteraction]). */
    private var lastTalkAt = Long.MIN_VALUE / 2
    /** Пауза в розмові: перевірка, чи співрозмовник ще тут (див. PauseCheck). */
    private var pauseJob: Job? = null
    /** Остання дія — відповідь моделі в розмові (не команда, урок чи книга). */
    private var dialogue = false
    /** У цій паузі Спіді вже заговорила сама — вдруге не заговорює, доки їй не відповідять. */
    private var pausedOnce = false

    fun start() {
        // Поки VLC грає аудіокнигу чи музику, мікрофон не слухає (там теж говорять) — лише дотик.
        if (!busy && micFree()) ears.start()
        scheduleIdle()
    }

    private var mutedForMedia = false

    /** Кожні кілька секунд: стежимо за VLC (місце, наступний файл) і вимикаємо слух, поки грає. */
    fun mediaTick() {
        val p = playback ?: return
        p.tick()
        if (busy) return
        val playing = p.playingAudio
        if (playing && !mutedForMedia) {
            mutedForMedia = true
            ears.stop()
        } else if (!playing && mutedForMedia) {
            mutedForMedia = false
            if (micFree()) ears.start()
        }
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
            awaitTurn()
            runBusy {
                face.setMood(Mood.SAD)
                chirps.play(Chirp.LOW_BATTERY)
                say("Мій заряд $level ${BatteryAlerts.percentWord(level)}. Постав мене, будь ласка, на зарядку.")
            }
        }
    }

    /** Відкладене "як тебе звати?" (скасовується, якщо обличчя впізнали). */
    private var pendingStranger: Job? = null

    /** Скільки подій (нагадування, розряд) чекають паузи: читання книги поступається їм. */
    private var waitingForTurn = 0

    private suspend fun awaitTurn() {
        waitingForTurn++
        try {
            while (busy) delay(500)
        } finally {
            waitingForTurn--
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
        if (busy || quiet()) return
        if (maybeOfferLesson(hour)) return
        if (maybeTellFact(hour)) return
        val wish = desires.check(SystemClock.uptimeMillis(), hour, someoneNear, lastInteraction, batteryPercent, charging)
            ?: return
        chirp(wish, if (wish == Chirp.LOW_BATTERY) Mood.SLEEPY else Mood.HAPPY)
    }

    /** Запропонувала урок: кому, які варіанти, до коли чекаємо відповіді. */
    private var lessonOffer: Triple<String, List<com.speedy.assistant.school.LessonKind>, Long>? = null
    private val lastLessonOffer = HashMap<String, Long>()

    /**
     * Знайома дитина поруч, розмови немає — час від часу (не частіше ніж раз на 2 год) запропонувати урок:
     * англійська (якщо сьогодні не було) або предмет, яким найдовше не займались.
     */
    private fun maybeOfferLesson(hour: Int): Boolean {
        if (settings?.lessonOffers == false || hour !in 9..19 || !isOnline()) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastTalkAt < OFFER_IDLE_MS || Math.random() > OFFER_CHANCE) return false
        val p = profiles ?: return false
        val name = people?.presentNames(now)?.firstOrNull { p.age(it) == com.speedy.assistant.people.Profiles.Age.CHILD } ?: return false
        if (now - (lastLessonOffer[name] ?: Long.MIN_VALUE / 2) < OFFER_GAP_MS) return false
        if (childLimit?.exhausted(name, childLimitMinutes(), System.currentTimeMillis()) == true) return false
        lastLessonOffer[name] = now
        runBusy { offerLesson(name) }
        return true
    }

    private suspend fun offerLesson(name: String) {
        // Письмо — пальцем по екрану: не на колісній платформі (і лише якщо є екран для обведення).
        val onPlatform = kotlinx.coroutines.withTimeoutOrNull(PLATFORM_CHECK_MS) { robot.status() } != null
        val exclude = if (onPlatform || tracer == null) setOf(com.speedy.assistant.school.LessonKind.WRITING) else emptySet()
        val offers = com.speedy.assistant.school.LessonOffers.choose(
            com.speedy.assistant.school.LessonKind.weights(settings?.lessonWeights), exclude,
        )
        if (offers.isEmpty()) return
        Log.i(TAG, "пропоную урок $name: ${offers.map { it.ua }}${if (onPlatform) " (на платформі — без письма)" else ""}")
        face.setMood(Mood.HAPPY)
        say("$name, а давай позаймаємось? Можемо ${offers[0].ua}" + (offers.getOrNull(1)?.let { " — чи, може, ${it.ua}?" } ?: "?"))
        val until = SystemClock.uptimeMillis() + OFFER_REPLY_MS
        lessonOffer = Triple(name, offers, until)
        awaitingCommandUntil = maxOf(awaitingCommandUntil, until)
        inConversation = true
    }

    /** Відповідь на пропозицію уроку; true — оброблено. */
    private fun lessonOfferReaction(candidates: List<String>): Boolean {
        val (name, offers, until) = lessonOffer ?: return false
        lessonOffer = null
        if (SystemClock.uptimeMillis() > until) return false
        val reply = candidates.firstNotNullOfOrNull { com.speedy.assistant.school.LessonOffers.reply(it, offers) } ?: return false
        Log.i(TAG, "відповідь на урок ($name): $reply")
        lastTalkAt = SystemClock.uptimeMillis()
        when (reply) {
            com.speedy.assistant.school.LessonOffers.Reply.Decline -> runBusy { face.setMood(Mood.NEUTRAL); say("Добре, іншим разом!") }
            is com.speedy.assistant.school.LessonOffers.Reply.Take -> {
                val subject = reply.kind.subject
                if (subject == null) runBusy { englishLesson("урок англійської для $name") }
                else runBusy { schoolLesson(subject, "урок для $name") }
            }
        }
        return true
    }

    /** Цікавий факт: розказала — чекаємо реакції (ім'я, тема, до коли). */
    private var factPending: Triple<String, String, Long>? = null

    /**
     * Час від часу (не частіше ніж раз на 3 год для людини) — цікавий факт тому, хто поруч, коли Спіді
     * не розмовляє: під інтереси з профілю, іноді — з нової галузі. Реакцію запам'ятовуємо.
     */
    private fun maybeTellFact(hour: Int): Boolean {
        if (settings?.factsEnabled == false || hour !in 9..20 || !isOnline()) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastInteraction < FACT_IDLE_MS || Math.random() > FACT_CHANCE) return false
        val name = people?.presentNames(now)?.singleOrNull() ?: return false
        val p = profiles ?: return false
        if (!p.factDue(name, System.currentTimeMillis())) return false
        runBusy { tellFact(name) }
        return true
    }

    private suspend fun tellFact(name: String, sameTopic: String? = null) {
        val p = profiles ?: return
        val (liked, disliked, told) = p.interests(name)
        val explore = sameTopic == null && (liked.isEmpty() || Math.random() < FACT_EXPLORE)
        val child = p.age(name) == com.speedy.assistant.people.Profiles.Age.CHILD
        val about = p.summary(name, System.currentTimeMillis()) + (sameTopic?.let { "; хоче ще про: $it" }.orEmpty())
        val fact = generateJson(com.speedy.assistant.initiative.Facts.SYSTEM,
            com.speedy.assistant.initiative.Facts.prompt(about, child, if (sameTopic != null) listOf(sameTopic) else liked, disliked, told, explore))
            ?.let { com.speedy.assistant.initiative.Facts.parse(it) } ?: return
        Log.i(TAG, "цікавий факт для $name (${fact.topic}${if (explore) ", нова галузь" else ""})")
        p.factTold(name, fact.topic, fact.text, System.currentTimeMillis())
        face.setMood(Mood.HAPPY)
        val body = fact.text.replaceFirstChar { it.uppercase() }
        val text = if (sameTopic != null) body else "$name, а знаєш? $body"
        say(text)
        brain.noteSaid(text)
        // Відповідь ("ого!", "нудно", "а чому?") — без "Спіді".
        val until = SystemClock.uptimeMillis() + FACT_REACTION_MS
        factPending = Triple(name, fact.topic, until)
        awaitingCommandUntil = maxOf(awaitingCommandUntil, until)
        inConversation = true
    }

    /** Реакція на щойно розказаний факт; true — оброблено (далі як звичайну репліку не беремо). */
    private fun factReaction(candidates: List<String>): Boolean {
        val (name, topic, until) = factPending ?: return false
        factPending = null
        if (SystemClock.uptimeMillis() > until) return false
        val reaction = candidates.firstOrNull()?.let { com.speedy.assistant.initiative.Facts.reaction(it) } ?: return false
        Log.i(TAG, "реакція на факт ($topic): $reaction")
        when (reaction) {
            com.speedy.assistant.initiative.Facts.Reaction.LIKED -> {
                profiles?.factReaction(name, topic, liked = true)
                return false // "ого, а чому?" — хай модель продовжить розмову
            }
            com.speedy.assistant.initiative.Facts.Reaction.MORE -> {
                profiles?.factReaction(name, topic, liked = true)
                runBusy { tellFact(name, sameTopic = topic) }
            }
            com.speedy.assistant.initiative.Facts.Reaction.DISLIKED -> {
                profiles?.factReaction(name, topic, liked = false)
                awaitingCommandUntil = 0
                inConversation = false
                runBusy { face.setMood(Mood.NEUTRAL); say("Добре, запам'ятаю.") }
            }
        }
        return true
    }

    /** Прибрати спогад із пам'яті й профілю того, хто говорить. */
    private suspend fun forgetThat(what: String) {
        val words = com.speedy.assistant.brain.Memory.keywords(what)
        if (words.isEmpty()) return say("Що саме забути?")
        val n = (memory?.forget(words) ?: 0) + (partner()?.let { profiles?.forgetFacts(it, words) } ?: 0)
        Log.i(TAG, "забути: $words — прибрано $n")
        brain.reset() // і з поточної розмови, щоб модель не повторила
        face.setMood(Mood.NEUTRAL)
        say(if (n > 0) "Добре, забула." else "Я такого й не пам'ятала.")
    }

    /** Тиха перевірка після запуску: говоримо лише тоді, коли знайшлася нова версія. */
    fun checkUpdatesOnLaunch() {
        scope.launch(Dispatchers.IO) { updater.cleanup() } // APK уже встановленої версії — геть
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
            awaitTurn()
            runBusy { announceUpdate(info) }
        }
    }

    /** Події від локальної моделі присутності. */
    fun onPresence(events: List<PresenceEvent>) {
        events.filter { it !is PresenceEvent.Look }.takeIf { it.isNotEmpty() }
            ?.let { Log.i(TAG, "присутність: $it${if (busy) " (зайнята — пропускаю)" else ""}") }
        if (busy || guard?.on == true) return
        // Обличчя важливіші за загальне "хтось прийшов": вітаємо на ім'я або знайомимось.
        events.firstOrNull { it is PresenceEvent.Recognized }?.let { event ->
            pendingStranger?.cancel() // упізнала — "як тебе звати?" вже не питаємо
            // Щойно розмовляли (камера лише на мить втратила обличчя) — "з поверненням" було б дивним.
            if (SystemClock.uptimeMillis() - lastTalkAt < TALKED_RECENTLY_MS) {
                profiles?.greeted((event as PresenceEvent.Recognized).name)
                return
            }
            runBusy { greetKnown((event as PresenceEvent.Recognized).name) }
            return
        }
        if (events.any { it == PresenceEvent.Stranger }) {
            // Здалеку чи збоку знайоме обличчя спершу схоже на чуже, а за кілька секунд упізнається.
            // Тож питаємо ім'я не одразу; якщо когось знайомого бачили щойно — не питаємо зовсім.
            if (profiles?.anyoneSeenWithin(System.currentTimeMillis(), RECENT_KNOWN_MS) == true) {
                Log.i(TAG, "незнайоме обличчя, але знайомих бачила щойно — ім'я не питаю")
                return
            }
            if (pendingStranger?.isActive != true) pendingStranger = scope.launch {
                delay(STRANGER_WAIT_MS)
                if (busy || people?.presentNames(SystemClock.uptimeMillis())?.isNotEmpty() == true) return@launch
                runBusy { askName() }
            }
            return
        }
        for (event in events) {
            when (event) {
                is PresenceEvent.Look -> face.lookAt(event.x, event.y)
                is PresenceEvent.Arrived -> {
                    runBusy { greetArrival(event.kind, event.name) }
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
        val morning = profiles?.takeMorningBriefing(name, System.currentTimeMillis()) == true
        // "Привіт" — лише після довгої перерви; щойно бачились — інакше, без привітання й підказок.
        val longAway = (profiles?.returnGap(name, System.currentTimeMillis()) ?: Long.MAX_VALUE) >= GREET_AGAIN_MS
        profiles?.greeted(name)
        if (!morning && !longAway) {
            say(listOf("О, $name, ти знову тут!", "З поверненням, $name!", "$name, я тебе бачу!", "А ось і $name!").random())
        } else {
            val hint = greetingExtra(name)?.let { " $it" }.orEmpty()
            say((if (morning) "Доброго ранку, $name!" else listOf("Привіт, $name!", "О, $name! Привіт!", "$name, рада тебе бачити!").random()) + hint)
        }
        if (morning) morningBriefing()
        if (morning) reportNight(onlyNew = true)
        refreshAppearance(name)
        face.setMood(Mood.LISTENING)
        awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
    }

    /**
     * Що додати до привітання (щоразу інше): давно не бачились; нагадування на сьогодні; недочитана
     * книга; новий колір одягу (раз на день); побажання за часом доби.
     */
    private suspend fun greetingExtra(name: String): String? {
        val now = System.currentTimeMillis()
        birthdayGreeting(name)?.let { return it } // день народження — найважливіше
        profiles?.greetingHint(name, now)?.let { return it } // "давно не бачились"
        val endOfDay = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59) }.timeInMillis
        reminders?.upcoming()?.firstOrNull { (it.forName == null || it.forName == name) && it.at <= endOfDay }
            ?.let { return "До речі, ${whenText(it.at)} — ${it.text}." }
        val options = mutableListOf<String>()
        books?.last(name)?.takeIf { books.place(it)?.finished == false }?.let { options += "Продовжимо «${books.title(it)}»?" }
        profiles?.clothingHint(name, now)?.let { options += it }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        options += when (hour) {
            in 5..11 -> listOf("Як спалось?", "Гарного ранку!")
            in 12..16 -> listOf("Як минає день?", "Що цікавого сьогодні?")
            in 17..21 -> listOf("Як пройшов день?", "Гарного вечора!")
            else -> listOf("Чого не спиш?")
        }.random()
        // Дня народження не знаємо — інколи спитати (відповідь "21 грудня" — у профіль).
        if (profiles?.birthday(name) == null && Math.random() < ASK_BIRTHDAY_CHANCE) {
            awaitingBirthday = name
            awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS * 2
            return "До речі, коли в тебе день народження?"
        }
        val pick = options.random()
        if (pick.startsWith("Ти сьогодні в")) profiles?.clothingMentioned(name, now)
        return pick
    }

    /** Кому вже казала "з днем народження" сьогодні (день року). */
    private val birthdayWished = HashMap<String, Int>()

    /**
     * День народження сьогодні — з профілю або з календаря телефона ("День народження Тараса" —
     * Google сам додає дні народження контактів; дата тоді — у профіль). Раз на день.
     */
    private suspend fun birthdayGreeting(name: String): String? {
        val p = profiles ?: return null
        val now = System.currentTimeMillis()
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (birthdayWished[name] == today) return null
        var isToday = p.birthdayToday(name, now)
        if (!isToday) {
            val events = phone?.todayEvents().orEmpty()
            if (events.any { com.speedy.assistant.people.PersonalFacts.birthdayOf(it, listOf(name)) != null }) {
                val c = java.util.Calendar.getInstance()
                p.setBirthday(name, "%02d-%02d".format(c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)))
                Log.i(TAG, "день народження $name — з календаря")
                isToday = true
            }
        }
        if (!isToday) return null
        birthdayWished[name] = today
        chirps.play(Chirp.HAPPY)
        return "З днем народження! Бажаю тобі багато радості!"
    }

    /** Спитала "коли в тебе день народження?" — чия наступна відповідь. */
    private var awaitingBirthday: String? = null

    /** Сповіщення "сьогодні день народження …" про знайомого — дата в профіль (якщо ще не знаємо). */
    fun scanNotesForBirthdays() {
        val p = profiles ?: return
        val known = people?.names().orEmpty()
        val c = java.util.Calendar.getInstance()
        val today = "%02d-%02d".format(c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        for (n in com.speedy.assistant.phone.NotificationStore.notes.unread()) {
            if (!n.text.contains("сьогодні", ignoreCase = true) && !n.title.contains("сьогодні", ignoreCase = true)) continue
            val name = com.speedy.assistant.people.PersonalFacts.birthdayOf("${n.title} ${n.text}", known) ?: continue
            if (p.birthday(name) == null) {
                p.setBirthday(name, today)
                Log.i(TAG, "день народження $name — зі сповіщення ${n.app}")
            }
        }
    }

    /** Зовнішність знайомого застаріла (2 тижні) — фото моделі, коротко в профіль. Тихо, без слів. */
    private suspend fun refreshAppearance(name: String) {
        val p = profiles ?: return
        if (settings?.appearanceEnabled == false || !isOnline() || !p.appearanceStale(name, System.currentTimeMillis())) return
        val jpeg = try { camera.take(front = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        scope.launch {
            val text = try { brain.describeAppearance(jpeg) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (text != null) {
                p.setAppearance(name, text, System.currentTimeMillis())
                Log.i(TAG, "зовнішність $name: $text")
            }
        }
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

    /** Скільки разів поспіль "погано роздивилась" — більше одного повтору не просимо. */
    private var unclearIntroductions = 0

    private suspend fun introduce(name: String) {
        awaitingName = false
        val p = people ?: return say("Я поки не вмію запам'ятовувати обличчя.")
        // Уже впізнала її в кадрі — знайомитись нема чого.
        if (p.presentNames(SystemClock.uptimeMillis()).any { it.equals(name, ignoreCase = true) }) {
            unclearIntroductions = 0
            face.setMood(Mood.HAPPY)
            return say("Так, я знаю, ти $name!")
        }
        val knownBefore = p.knows(name)
        val introduced = p.introduce(name, SystemClock.uptimeMillis())
        Log.i(TAG, "знайомство $name: $introduced")
        if (introduced != People.Introduction.Unclear) unclearIntroductions = 0
        if (introduced == People.Introduction.Remembered) {
            face.setMood(Mood.HAPPY)
            chirps.play(Chirp.HAPPY)
            say(if (knownBefore) "$name! Тепер я впізнаватиму тебе краще." else "Приємно познайомитись, $name! Я тебе запам'ятала.")
        } else if (introduced is People.Introduction.LooksLike) {
            face.setMood(Mood.THINKING)
            say("Мені здається, ти — ${introduced.name}. Якщо я помиляюсь, скажи: «це не ${introduced.name}, це $name».")
        } else if (introduced == People.Introduction.Unclear) {
            face.setMood(Mood.THINKING)
            if (++unclearIntroductions > 1) {
                // Удруге не вийшло — не мучимо дитину ("погано роздивилась" звучало без кінця).
                unclearIntroductions = 0
                return say("Нічого, роздивлюсь тебе пізніше. Що будемо робити?")
            }
            say("Я тебе погано роздивилась. Підійди ближче, подивись на мене і скажи своє ім'я ще раз.")
            // Чекаємо ім'я знову: "я Ліна" наступною фразою теж підійде.
            awaitingName = true
            awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS * 2
        } else {
            val now = SystemClock.uptimeMillis()
            val known = p.presentNames(now)
            when {
                known.size == 1 && known.first() == name -> {
                    face.setMood(Mood.HAPPY)
                    say("Так, я знаю, ти $name!")
                }
                // Назвався жартома, а тепер каже справжнє ім'я — переносимо саме це обличчя.
                known.size == 1 && p.reassign(name, now).also { ok -> if (ok) moveProfiles(known, name) } -> {
                    face.setMood(Mood.HAPPY)
                    chirps.play(Chirp.HAPPY)
                    say("Добре, тепер я знаю, що ти $name, а не ${known.first()}.")
                }
                known.size > 1 -> {
                    face.setMood(Mood.THINKING)
                    say("Тут кілька людей, які я знаю. Нехай лишиться в кадрі той, хто представляється, і скажи ще раз.")
                }
                else -> {
                    face.setMood(Mood.THINKING)
                    say("Подивись на мене, щоб я побачила твоє обличчя, і скажи ще раз.")
                }
            }
        }
    }

    private suspend fun namePet(name: String) {
        awaitingPetName = false
        val p = pets ?: return say("Я поки не вмію запам'ятовувати тварин.")
        val named = p.name(name, SystemClock.uptimeMillis())
        Log.i(TAG, "кличка $name: ${if (named) "запам'ятала" else "тварини в кадрі не було"}")
        if (named) {
            face.setMood(Mood.HAPPY)
            chirps.play(Chirp.HAPPY)
            say("$name! Гарне ім'я. Запам'ятала.")
        } else {
            face.setMood(Mood.THINKING)
            say("Я зараз не бачу тварини. Покажи мені її і скажи ще раз.")
        }
    }

    /** Вітання без слів: радісний звук і очі; говорить Спіді лише у відповідь. */
    private suspend fun greetArrival(kind: Kind, petName: String? = null) {
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
                face.setMood(Mood.HAPPY)
                if (petName != null) {
                    say(listOf("Ой, $petName!", "$petName, привіт!", "О, $petName тут!").random())
                } else if (pets != null) {
                    say(if (kind == Kind.CAT) "Ой, котик! Як його звати?" else "Ой, песик! Як його звати?")
                    face.setMood(Mood.LISTENING)
                    awaitingPetName = true
                    awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
                } else {
                    say(if (kind == Kind.CAT) "Ой, котик!" else "Ой, песик!")
                }
            }
        }
    }

    /**
     * Дотик до екрана: у спокої — звертання "Спіді"; поки Спіді зайнята (їде, говорить,
     * думає) — негайна зупинка. Під час руху мікрофон вимкнено, тож це головний "стоп".
     */
    fun manualWake() {
        buzzer?.touch()
        // Сторож: дотик — вимкнення (знайомий у кадрі — одразу, інакше PIN).
        if (guard?.on == true) return requestDisarm()
        // Дзвонить будильник чи таймер — дотик його вимикає (голосом під сигнал на J5 не чути).
        if (ears.ringing && phone?.stopAlarm(snooze = false) == true) {
            if (busy) interrupt()
            face.setMood(Mood.HAPPY)
            return
        }
        if (busy) return interrupt()
        // Нагадування, що прийшли в тиші (лише вібрація), — тепер уголос.
        if (quietReminders.isNotEmpty()) return runBusy { sayQuietReminders() }
        // Нічний режим — дотик його вимикає (мікрофон зайнятий, голосом не вимкнути).
        if (night?.active == true) return runBusy { stopNight() }
        // Грає аудіо у VLC — пауза, і Спіді слухає ("продовжуй" — грає далі).
        if (playback?.pause() == true) mutedForMedia = false
        runBusy { greet() }
    }

    private fun interrupt() {
        pauseJob?.cancel()
        commandJob?.cancel()
        voice.stop()
        scope.launch { robot.stop() }
        face.setSpeaking(false)
        face.setMood(Mood.NEUTRAL)
    }

    private fun onHeard(candidates: List<String>) {
        // Відкрите віконце PIN — сказаний PIN вписуємо туди (і далі як команду не обробляємо).
        if (com.speedy.assistant.settings.PinDialog.hear(candidates)) return
        // Йде урок і Спіді чекає відповіді — це вона.
        lessonAnswer?.let { if (it.complete(candidates)) return }
        if (busy) return
        // Сторож: чуємо лише "досить вартувати" (від знайомого в кадрі — інакше PIN), решту — ні.
        if (guard?.on == true) {
            if (candidates.any { com.speedy.assistant.guard.GuardCommands.parse(it) == false }) requestDisarm()
            return
        }
        if (factReaction(candidates)) return
        if (lessonOfferReaction(candidates)) return
        // Дзвонить будильник: під сигнал фраза розпізнається криво — досить уламка ("будник", "стоп").
        if (ears.ringing) candidates.firstNotNullOfOrNull { LocalCommands.whileRinging(it) }?.let { cmd ->
            lastInteraction = SystemClock.uptimeMillis()
            runBusy { handle(if (cmd == LocalCommand.SNOOZE_ALARM) "відклади будильник" else "вимкни будильник") }
            return
        }
        val now = SystemClock.uptimeMillis()
        // Дитина не повторює "Спіді" щоразу: поки хтось поруч (обличчя близько) і розмова була
        // нещодавно — це продовження розмови.
        val nearContinuation = people?.someoneClose(now) == true && now - lastInteraction < NEAR_CONTINUATION_MS
        val inWindow = now < awaitingCommandUntil || nearContinuation
        if (awaitingPetName && inWindow && WakeWord.extractCommand(candidates) == null) {
            // Лише перший (найімовірніший) варіант: з "так, у мене сіра футболка" інші варіанти дають "футболка".
            val name = candidates.firstOrNull()?.let { LocalCommands.parsePetName(it) ?: LocalCommands.parseName(it, answer = true) }
            if (name != null) {
                awaitingCommandUntil = 0
                awaitingPetName = false
                runBusy { namePet(name) }
                return
            }
        }
        // "Будь ласка, навчи мене англійської" у відповідь на "як тебе звати?" — команда, а не ім'я.
        if (awaitingName && candidates.any { LocalCommands.match(it) != null }) awaitingName = false
        if (awaitingName && inWindow && WakeWord.extractCommand(candidates) == null) {
            // Лише перший варіант: інакше короткий запасний варіант речення ставав іменем ("Футболка").
            val name = candidates.firstOrNull()?.let { LocalCommands.parseName(it, answer = true) }
            if (name != null) {
                awaitingCommandUntil = 0
                lastInteraction = SystemClock.uptimeMillis()
                runBusy { introduce(name) }
                return
            }
        }
        // Спитала "коли в тебе день народження?" — "21 грудня".
        awaitingBirthday?.let { who ->
            awaitingBirthday = null
            val date = candidates.firstOrNull()?.let { com.speedy.assistant.people.PersonalFacts.date(it) ?: com.speedy.assistant.people.PersonalFacts.birthday(it) }
            if (date != null && inWindow) {
                profiles?.setBirthday(who, date)
                awaitingCommandUntil = 0
                lastInteraction = SystemClock.uptimeMillis()
                runBusy { face.setMood(Mood.HAPPY); say("Запам'ятала: ${com.speedy.assistant.people.PersonalFacts.spoken(date)}!") }
                return
            }
        }
        // У відповідь прозвучало не ім'я ("ти ж мене знаєш") — розмова пішла далі; інакше наступне
        // слово ("працюю" на "як твій день?") ставало іменем.
        awaitingName = false
        awaitingPetName = false
        if (awaitingBook && inWindow && WakeWord.extractCommand(candidates) == null) {
            val phrase = candidates.firstOrNull()?.takeIf { it.isNotBlank() }
            if (phrase != null) {
                awaitingCommandUntil = 0
                awaitingBook = false
                lastInteraction = SystemClock.uptimeMillis()
                // "Кобзар" у відповідь на "яку почитати?" — назва; "почитай Кобзар" — теж.
                val cmd = com.speedy.assistant.books.BookCommands.parse(phrase) ?: BookCommand.Read(phrase, explicit = true)
                runBusy { if (!handleBook(cmd)) handle(phrase) }
                return
            }
        }
        if (!inWindow) {
            awaitingName = false
            awaitingPetName = false
            awaitingBook = false
            inConversation = false
        }
        val named = WakeWord.extractCommand(candidates)
        val command = named
            ?: candidates.firstOrNull()?.takeIf { inWindow }
            // Дзвонить будильник — "вимкни будильник" / "стоп" і без "Спіді".
            ?: candidates.firstOrNull()?.takeIf { ears.ringing && LocalCommands.match(it) in ALARM_COMMANDS }
            ?: return
        if (named != null && named.isNotEmpty()) buzzer?.touch() // почула звертання ("Спіді" без команди — у greet)
        awaitingCommandUntil = 0
        lastInteraction = SystemClock.uptimeMillis()
        lastTalkAt = lastInteraction
        inConversation = true
        pausedOnce = false
        noteClaimedName(candidates)
        noteVoice()
        // Розмова — теж контакт: "привіт" наступного разу — лише через 3 години після неї.
        partner()?.let { profiles?.seen(it, System.currentTimeMillis()) }
        // Звук фрази — для відбитка голосу (хто говорить), якщо свіжий.
        val audio = ears.lastUtterance?.takeIf { SystemClock.uptimeMillis() - ears.lastUtteranceAt < 5_000 }
        runBusy {
            identifyVoice(audio)
            if (childTired(command)) return@runBusy
            if (command.isBlank()) greet() else handle(command)
        }
    }

    /**
     * Дитина: денний ліміт розмов. Вичерпано — Спіді "втомилась" (без моделі). Після визначення
     * голосу — щоб знати, хто насправді говорить. @return true — далі не відповідаємо.
     */
    private suspend fun childTired(command: String): Boolean {
        val limit = childLimit ?: return false
        // "Стоп" і "вимкни будильник" — завжди, навіть коли ліміт вичерпано.
        if (!childSpeaking() || LocalCommands.match(command) in ALARM_COMMANDS) return false
        val kid = partner() ?: "дитина"
        val now = System.currentTimeMillis()
        if (!limit.exhausted(kid, childLimitMinutes(), now)) {
            limit.talk(kid, now)
            return false
        }
        Log.i(TAG, "ліміт дитини ($kid) вичерпано")
        limit.seen(kid, now) // поруч — 4 години до скидання рахуємо звідси
        inConversation = false
        face.setMood(Mood.SLEEPY)
        say(listOf(
            "Ой, я так втомилася… Хочу трохи відпочити. Пограємо пізніше!",
            "Мої батарейки втомилися, мені треба поспати. Побачимось пізніше!",
            "Я вже дуже хочу відпочити. Давай трохи згодом, добре?",
        ).random())
        return true
    }

    /** Хто говорить за голосом (ця фраза): ім'я, схожість, коли. */
    private var voiceGuess: Triple<String, Float, Long>? = null

    /**
     * Відбиток голосу цієї фрази: впізнати (сильний збіг — співрозмовник, навіть якщо ближче інше
     * обличчя) і, коли хто говорить відомо напевно, — додати до його голосів. Не довше 0.8 с.
     */
    private suspend fun identifyVoice(audio: ShortArray?) {
        val prints = voicePrints?.takeIf { it.ready } ?: return
        val book = voiceBook ?: return
        val pcm = audio ?: return
        val vector = kotlinx.coroutines.withTimeoutOrNull(800) { withContext(Dispatchers.Default) { prints.embed(pcm) } } ?: return
        val now = SystemClock.uptimeMillis()
        val match = book.match(vector)
        voiceGuess = match?.let { Triple(it.first, it.second, now) }
        Log.i(TAG, "голос: ${match?.let { "схожий на ${it.first} (${"%.2f".format(it.second)})" } ?: "ще нікого не знаю"}")
        // Вчимо голос лише тоді, коли хто говорить відомо напевно: сказала "я Ліна" щойно, або
        // в кадрі одна знайома людина й ніхто з голосів не суперечить.
        val sure = claimed?.takeIf { now - it.second < 10_000 }?.first
            ?: people?.presentNames(now)?.singleOrNull()?.takeIf { it == people.speaker(now) && (match == null || match.first == it || match.second < com.speedy.assistant.voice.VoiceBook.STRONG) }
        if (sure != null) {
            book.enroll(sure, vector)
            Log.i(TAG, "голос $sure запам'ятала")
        }
    }

    /** Лише debug-збірка: фраза з adb так, ніби її почули (перевірка команд без голосу). */
    fun debugHear(text: String) {
        if (com.speedy.assistant.settings.PinDialog.hear(listOf(text))) { Log.i(TAG, "debug: PIN у віконце"); return }
        Log.i(TAG, "debug: \"$text\"")
        // Урок чекає відповіді — це вона (як і з мікрофона).
        lessonAnswer?.let { if (it.complete(listOf(text))) return }
        if (busy) interrupt()
        scope.launch {
            while (busy) delay(100)
            lastInteraction = SystemClock.uptimeMillis()
            noteClaimedName(listOf(text))
            pausedOnce = false
            inConversation = true
            runBusy { handle(text) }
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        busy = true
        onBusyChanged(true)
        ears.stop() // не слухаємо самих себе
        idleJob?.cancel()
        pauseJob?.cancel()
        pauseJob = null
        dialogue = false
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
                // Інтернет є, а модель не відповідає (перевантаження в Google чи Anthropic) — так і кажемо.
                else say(when {
                    (e is LlmException || e is java.io.IOException) && isOnline() -> "Мій мозок зараз не відповідає. Спробуй трохи згодом."
                    e is LlmException || e is java.io.IOException -> "Ой, не можу зв'язатися зі своїм мозком. Перевір інтернет."
                    else -> "Ой, щось пішло не так."
                })
            } finally {
                face.setSpeaking(false)
                busy = false
                onBusyChanged(false)
                // Розмова триває — наступну репліку можна казати без "Спіді".
                if (inConversation) {
                    awaitingCommandUntil = maxOf(awaitingCommandUntil, SystemClock.uptimeMillis() + CONVERSATION_WINDOW_MS)
                    face.setMood(Mood.LISTENING)
                }
                // Спитала й чекає відповіді — слухаємо одразу, щоб не обрізати початок фрази.
                // Увімкнула аудіо у VLC — не слухаємо (mediaTick увімкне слух, коли замовкне).
                if (playback?.playingAudio == true) mutedForMedia = true
                else if (micFree()) ears.start(listenNow = SystemClock.uptimeMillis() < awaitingCommandUntil)
                scheduleIdle()
                // Відповіла в розмові — якщо далі тиша, перевірити, чи до неї ще говорять.
                if (dialogue && inConversation) schedulePauseCheck()
            }
        }
    }

    /** Звернулися без команди: коротке "бі-іп" замість слів — і чекаємо команду. */
    private suspend fun greet() {
        buzzer?.touch()
        lastInteraction = SystemClock.uptimeMillis()
        face.setMood(Mood.LISTENING)
        chirps.play(Chirp.ACK)
        awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
    }

    private suspend fun handle(command: String) {
        when (com.speedy.assistant.guard.GuardCommands.parse(command)) {
            true -> return armGuard()
            false -> return if (guard?.on == true) requestDisarm() else say("Я зараз не вартую.")
            null -> {}
        }
        com.speedy.assistant.school.SchoolCommands.parse(command)?.let { return schoolLesson(it, command) }
        // "Забудь, що я люблю drum and bass" — модель уміє лише додавати спогади, прибирає людина.
        FORGET_THAT.find(command.lowercase().trim())?.let { return forgetThat(it.groupValues[1]) }
        val cmd = LocalCommands.match(command)
        when (cmd) {
            LocalCommand.STOP -> {
                // "Стоп", поки дзвонить будильник, — це про будильник.
                if (ears.ringing && phone?.stopAlarm(snooze = false) == true) return say("Вимкнула.")
                robot.stop()
                playback?.pause()
                face.setMood(Mood.NEUTRAL)
                say("Зупиняюсь.")
                return
            }
            LocalCommand.FIND_ROBOT -> return findPlatform()
            LocalCommand.STOP_ALARM, LocalCommand.SNOOZE_ALARM -> {
                val snooze = cmd == LocalCommand.SNOOZE_ALARM
                // Лише коли справді дзвонить: інакше годинник відкрився б зі списком, а "наступний
                // будильник" міг би вимкнутися.
                if (!ears.ringing) return say("Зараз нічого не дзвонить.")
                if (phone?.stopAlarm(snooze) != true) return say("Не вийшло вимкнути — натисни на екрані.")
                face.setMood(Mood.HAPPY)
                return say(if (snooze) "Відклала будильник." else "Вимкнула.")
            }
            LocalCommand.FIND_PHONE -> {
                // "Де ти?" — знайти телефон: вібрація й звук тричі.
                face.setMood(Mood.HAPPY)
                buzzer?.find()
                repeat(3) { chirps.play(Chirp.ATTENTION); delay(300) }
                return say("Я тут!")
            }
            LocalCommand.CHANGE_PIN -> {
                face.setMood(Mood.THINKING)
                say("Спершу введи або скажи поточний PIN, а потім — новий двічі.")
                return openAccessSettings("pin")
            }
            LocalCommand.ENGLISH_LESSON -> return englishLesson(command)
            LocalCommand.ENGLISH_PLACEMENT -> {
                english?.progress?.resetLevel(learnerFor(command))
                return englishLesson(command)
            }
            LocalCommand.AGE_ADULT -> {
                // Інакше дитина скаже "я дорослий" й обійде ліміт.
                return say("Позначити людину дорослою можна лише в налаштуваннях — це для батьків.")
            }
            LocalCommand.AGE_CHILD -> {
                val name = partner() ?: return say("Подивись на мене, щоб я знала, про кого це.")
                val child = cmd == LocalCommand.AGE_CHILD
                profiles?.setAge(name, if (child) com.speedy.assistant.people.Profiles.Age.CHILD else com.speedy.assistant.people.Profiles.Age.ADULT)
                if (child) english?.progress?.learner(name)?.level?.takeIf { it != com.speedy.assistant.english.Level.KIDS }
                    ?.let { english.progress.setLevel(name, com.speedy.assistant.english.Level.KIDS) }
                face.setMood(Mood.HAPPY)
                val female = profiles?.gender(name) == com.speedy.assistant.people.Gender.FEMALE
                return say(if (child) "Зрозуміла, $name ще ${if (female) "маленька" else "маленький"} — уроки будуть дитячі." else "Зрозуміла, $name ${if (female) "доросла" else "дорослий"}.")
            }
            LocalCommand.ENGLISH_PROGRESS -> {
                val name = partner() ?: "гість"
                return say(english?.describe(name) ?: "Я поки не вмію вчити англійської.")
            }
            LocalCommand.ENGLISH_LEVEL -> {
                val level = com.speedy.assistant.english.Level.fromSpoken(command.substringAfter("англійськ"))
                    ?: return say("Скажи рівень: малюк, початківець, середній чи впевнений.")
                val course = english ?: return say("Я поки не вмію вчити англійської.")
                course.progress.setLevel(partner() ?: "гість", level)
                face.setMood(Mood.HAPPY)
                return say("Добре, тепер твій рівень англійської — ${level.ua}.")
            }
            LocalCommand.REMIND -> {
                val r = com.speedy.assistant.initiative.ReminderParser.parse(command, System.currentTimeMillis())
                    ?: return say("Не зрозуміла, коли нагадати. Скажи, наприклад: «нагадай о шостій вечора» або «через двадцять хвилин».")
                val store = reminders ?: return say("Я поки не вмію нагадувати.")
                store.add(r.copy(forName = partner()))
                Log.i(TAG, "нагадування ${r.at}: ${r.text}")
                face.setMood(Mood.HAPPY)
                return say("Добре, нагадаю ${whenText(r.at)}: ${r.text}.")
            }
            LocalCommand.LIST_REMINDERS -> {
                val list = reminders?.upcoming().orEmpty()
                face.setMood(Mood.HAPPY)
                return say(
                    if (list.isEmpty()) "Нагадувань немає."
                    else list.take(5).joinToString("; ", prefix = "Нагадаю: ", postfix = ".") { "${whenText(it.at)} — ${it.text}" }
                )
            }
            LocalCommand.CANCEL_REMINDERS -> {
                val n = reminders?.clear() ?: 0
                face.setMood(Mood.NEUTRAL)
                return say(if (n == 0) "Нагадувань і так немає." else "Скасувала всі нагадування.")
            }
            LocalCommand.END_CONVERSATION -> {
                inConversation = false
                awaitingCommandUntil = 0
                face.setMood(Mood.HAPPY)
                return say(listOf("Звертайся!", "Гаразд, кличи, якщо що.", "Добре, я тут.").random())
            }
            LocalCommand.CHECK_UPDATE -> return checkUpdates(install = false)
            LocalCommand.INSTALL_UPDATE -> return checkUpdates(install = true)
            LocalCommand.CORRECT_NAME -> {
                val (wrong, right) = LocalCommands.parseCorrection(command) ?: return say("Не розчула. Скажи ще раз?")
                val p = people ?: return say("Я поки не вмію запам'ятовувати обличчя.")
                val before = p.presentNames(SystemClock.uptimeMillis())
                // Запобіжник: "неправильне" ім'я — саме те, яким Спіді щойно назвала людину в кадрі.
                // Інакше це звичайна фраза ("я не в червоному") — хай відповідає модель.
                if (before.none { it.equals(wrong, ignoreCase = true) }) return askModel(command)
                if (!p.reassign(right, SystemClock.uptimeMillis()).also { ok -> if (ok) moveProfiles(before, right) }) {
                    face.setMood(Mood.THINKING)
                    return say("Подивись на мене, щоб я побачила обличчя, і скажи ще раз.")
                }
                Log.i(TAG, "виправлено: не $wrong, а $right")
                face.setMood(Mood.HAPPY)
                chirps.play(Chirp.HAPPY)
                return say("Ой, вибач! Привіт, $right. Тепер не сплутаю.")
            }
            LocalCommand.NAME_PET -> {
                val name = LocalCommands.parsePetName(command) ?: return say("Не розчула кличку. Скажи ще раз?")
                return namePet(name)
            }
            LocalCommand.INTRODUCE -> {
                val name = LocalCommands.parseName(command) ?: return say("Не розчула ім'я. Скажи ще раз?")
                return introduce(name)
            }
            LocalCommand.FORGET_ME -> {
                val name = people?.presentNames(SystemClock.uptimeMillis())?.firstOrNull()
                    ?: return say("Я не бачу, кого забути. Подивись на мене.")
                people.forget(name)
                profiles?.forget(name)
                voiceBook?.forget(name)
                face.setMood(Mood.SAD)
                return say("Добре, я забула тебе, $name.")
            }
            LocalCommand.FORGET_NAME -> {
                val spoken = LocalCommands.parseForgetName(command) ?: return say("Кого саме забути?")
                val p = people ?: return say("Я поки не вмію запам'ятовувати обличчя.")
                val name = p.findName(spoken) ?: return say("Я не знаю нікого на ім'я ${spoken.replaceFirstChar { it.uppercase() }}.")
                p.forget(name)
                profiles?.forget(name)
                voiceBook?.forget(name)
                Log.i(TAG, "забула ім'я $name")
                face.setMood(Mood.NEUTRAL)
                return say("Добре, забула $name.")
            }
            LocalCommand.LIST_PEOPLE -> {
                val names = people?.names().orEmpty()
                face.setMood(Mood.HAPPY)
                return say(if (names.isEmpty()) "Я ще ні з ким не знайома." else "Я знаю: ${names.joinToString(", ")}.")
            }
            LocalCommand.ABOUT_ME -> {
                val name = partner() ?: return askName()
                face.setMood(Mood.HAPPY)
                return say(profiles?.describe(name, System.currentTimeMillis()) ?: "Я знаю, що ти $name!")
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
        // "Я дівчинка" / "мій день народження 21 грудня" — прямо в профіль співрозмовника.
        com.speedy.assistant.people.PersonalFacts.gender(command)?.let { g ->
            val name = partner() ?: return say("Подивись на мене, щоб я знала, про кого це.")
            profiles?.setGender(name, g)
            face.setMood(Mood.HAPPY)
            return say("Зрозуміла, $name!")
        }
        com.speedy.assistant.people.PersonalFacts.birthday(command)?.let { date ->
            val name = partner()
            if (name != null) {
                profiles?.setBirthday(name, date)
                face.setMood(Mood.HAPPY)
                return say("Запам'ятала: твій день народження — ${com.speedy.assistant.people.PersonalFacts.spoken(date)}!")
            }
        }
        // "Я люблю каву" — у профіль без моделі; коротке твердження — і відповідаємо самі.
        com.speedy.assistant.people.SelfFacts.extract(command)?.let { fact ->
            val name = partner()
            if (name != null && profiles != null) {
                profiles.addFact(name, fact, System.currentTimeMillis())
                Log.i(TAG, "запам'ятала про $name: $fact")
                if (com.speedy.assistant.people.SelfFacts.isPlainStatement(command)) {
                    face.setMood(Mood.HAPPY)
                    return say(listOf("Запам'ятала!", "Ага, запам'ятаю, $name.", "Цікаво! Запам'ятала.").random())
                }
            }
        }
        // Налаштування голосом — лише після PIN батьків ("вимкни вібрацію на дотик").
        com.speedy.assistant.settings.SettingCommands.parse(command)?.let { return changeSetting(it) }
        // Подія в календар — без моделі ("запиши в календар лікаря на п'ятницю о 10").
        com.speedy.assistant.phone.EventParser.parse(command, System.currentTimeMillis())?.let { return addCalendarEvent(it) }
        // Нічний режим (хропіння) — без моделі.
        com.speedy.assistant.night.NightCommands.parse(command)?.let { return handleNight(it) }
        // Переклад фраз і книг — без моделі (офлайн).
        com.speedy.assistant.translate.TranslateCommands.parse(command)?.let { if (handleTranslate(it)) return }
        // Фільми й аудіокниги у VLC — без моделі ("увімкни Дюну" без такого файлу — далі).
        com.speedy.assistant.media.MediaCommands.parse(command)?.let { if (handleMedia(it)) return }
        // Книги вголос — без моделі ("почитай Кобзаря" без такої книги — далі, до моделі).
        com.speedy.assistant.books.BookCommands.parse(command)?.let { if (handleBook(it)) return }
        // Новини (з пошуком) — до сповіщень: "які новини в світі" — не про шторку телефону.
        if (com.speedy.assistant.news.News.isRequest(command)) return newsDigest()
        // Сповіщення й керування іншими застосунками — без моделі.
        com.speedy.assistant.phone.HandsCommands.parse(command, otherAppOpen = !inForeground())?.let { return handleHands(it) }
        // Дії на телефоні — без моделі; якщо не вийшло (нема такого застосунку) — далі до моделі.
        com.speedy.assistant.phone.PhoneCommands.parse(command)?.let { if (handlePhone(it)) return }
        com.speedy.assistant.weather.Currency.parse(command)?.let { code -> if (tellRate(code)) return }

        askModel(command)
    }

    /** Агентний цикл із моделлю: відповісти, подивитись, пошукати, поїхати — поки не досягне мети. */
    private suspend fun askModel(command: String) {
        face.setMood(Mood.THINKING)
        runAgent(ask(command))
    }

    /** Агентний цикл від першої відповіді моделі. */
    private suspend fun runAgent(first: AgentReply) {
        dialogue = true
        var reply = first
        var looks = 0
        var steps = 0
        var searches = 0

        // Агентний цикл: модель може подивитись, поїхати й знову подивитись, поки не досягне мети.
        while (true) {
            val action = reply.action
            if (reply.remember.isNotBlank()) rememberFact(reply.remember)
            when (action.type) {
                "search" -> {
                    if (searches++ >= Brain.MAX_SEARCHES || action.query.isBlank() || webSearch == null) return speak(reply)
                    if (reply.say.isNotBlank()) speak(reply)
                    face.setMood(Mood.THINKING)
                    val started = SystemClock.uptimeMillis()
                    val found = try {
                        webSearch.invoke(action.query)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "search failed", e)
                        null
                    }
                    Log.i(TAG, "пошук \"${action.query}\": ${if (found == null) "нічого" else "${found.length} симв."} за ${SystemClock.uptimeMillis() - started} мс")
                    reply = ask(Brain.searchFollowUp(action.query, found))
                }
                "listen" -> {
                    if (looks++ >= MAX_LOOKS) return speak(reply)
                    if (reply.say.isNotBlank()) speak(reply)
                    face.setMood(Mood.LISTENING)
                    val seconds = (action.durationMs.takeIf { it > 0 } ?: 8_000L).coerceIn(3_000, 10_000).toInt() / 1000
                    delay(VoiceListener.UNMUTE_DELAY_MS) // розпізнавач має відпустити мікрофон
                    val clip = recordSound(seconds) ?: return say("Не вийшло послухати — мікрофон зайнятий.")
                    Log.i(TAG, "слухаю звук: $seconds с, ${clip.wav.size / 1024} КБ, гучність ${clip.rms.toInt()}, " +
                        "не тло ${clip.soundWindows} вікон, класи ${clip.topClasses}")
                    // Тиша — моделі нічого не надсилаємо.
                    if (!clip.audible) { face.setMood(Mood.NEUTRAL); return say("Я нічого не чую — навколо тихо.") }
                    face.setMood(Mood.THINKING)
                    reply = ask(Brain.listenFollowUp(seconds), audio = clip.wav)
                }
                "look" -> {
                    if (looks++ >= MAX_LOOKS) return speak(reply)
                    if (reply.say.isNotBlank()) speak(reply)
                    face.setMood(Mood.THINKING)
                    if (action.camera == "both") {
                        val front = photo("forward") ?: return
                        val back = photo("backward") ?: return
                        reply = ask(Brain.cameraFollowUp("both"), front, listOf(back))
                    } else {
                        val jpeg = photo(action.camera) ?: return
                        reply = ask(Brain.cameraFollowUp(action.camera), jpeg)
                    }
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
                    reply = ask(Brain.navFollowUp(action.direction, ms, outcome, status?.distanceCm, steps), jpeg)
                }
                "command" -> {
                    dialogue = false // команда (урок, книга, відео) — не розмова
                    if (reply.say.isNotBlank()) speak(reply)
                    if (!runModelCommand(action.query)) {
                        Log.w(TAG, "модель: невідома команда \"${action.query}\"")
                        face.setMood(Mood.THINKING)
                        say("Ой, цього я поки не вмію.")
                    }
                    return
                }
                "stop" -> {
                    robot.stop()
                    return speak(reply)
                }
                else -> return speak(reply)
            }
        }
    }

    /**
     * Команда від моделі (дія "command"): ті самі розбори, що й для голосу, але лише з дозволеного
     * переліку — без налаштувань, PIN, доступів, керування застосунками й знайомств.
     * @return false — не розпізнали (модель не повторюємо, щоб не зациклитись).
     */
    private suspend fun runModelCommand(command: String): Boolean {
        if (command.isBlank()) return false
        Log.i(TAG, "модель просить: \"$command\"")
        when (LocalCommands.match(command)) {
            LocalCommand.ENGLISH_LESSON, LocalCommand.ENGLISH_PROGRESS, LocalCommand.REMIND, LocalCommand.LIST_REMINDERS,
            LocalCommand.CANCEL_REMINDERS, LocalCommand.FIND_PHONE,
            LocalCommand.TIME, LocalCommand.WEATHER, LocalCommand.WHERE, LocalCommand.BATTERY -> {
                handle(command) // кожна з цих гілок завершується сама — до моделі не повертається
                return true
            }
            null -> {}
            else -> return false // налаштування, PIN, вік, знайомства, оновлення — лише людина
        }
        com.speedy.assistant.phone.EventParser.parse(command, System.currentTimeMillis())?.let { addCalendarEvent(it); return true }
        com.speedy.assistant.night.NightCommands.parse(command)?.let { handleNight(it); return true }
        // Сторож: модель може лише ввімкнути (вимкнути — знайоме обличчя чи PIN, тобто людина).
        if (com.speedy.assistant.guard.GuardCommands.parse(command) == true) { armGuard(); return true }
        com.speedy.assistant.school.SchoolCommands.parse(command)?.let { schoolLesson(it, command); return true }
        if (com.speedy.assistant.news.News.isRequest(command)) { newsDigest(); return true }
        com.speedy.assistant.translate.TranslateCommands.parse(command)?.let { if (handleTranslate(it)) return true }
        com.speedy.assistant.media.MediaCommands.parse(command)?.let { if (handleMedia(it)) return true }
        com.speedy.assistant.books.BookCommands.parse(command)?.let { if (handleBook(it)) return true }
        // Телефон: календар, будильник, таймер, дзвінок (лише номеронабирач — "Виклик" тисне людина).
        // Без відкриття застосунків і номерів телефонів уголос.
        when (val phoneCmd = com.speedy.assistant.phone.PhoneCommands.parse(command)) {
            is com.speedy.assistant.phone.PhoneCommand.TodayCalendar, is com.speedy.assistant.phone.PhoneCommand.Alarm,
            is com.speedy.assistant.phone.PhoneCommand.Timer, is com.speedy.assistant.phone.PhoneCommand.Call -> return handlePhone(phoneCmd)
            else -> {}
        }
        return false
    }

    /** Ім'я зникло (жартівливе) — його профіль переходить до правильного імені. */
    private fun moveProfiles(oldNames: List<String>, newName: String) {
        val p = people ?: return
        oldNames.filter { it != newName && !p.knows(it) }.forEach { profiles?.rename(it, newName); voiceBook?.rename(it, newName) }
    }

    /** "о 18:00", "завтра о 9:00", "через 20 хв". */
    private fun whenText(at: Long): String {
        val now = System.currentTimeMillis()
        val minutes = (at - now + 30_000) / 60_000
        if (minutes in 0..59) return "через $minutes хв"
        val time = java.text.SimpleDateFormat("H:mm", java.util.Locale("uk", "UA")).format(java.util.Date(at))
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val day = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.DAY_OF_YEAR)
        return when (day - today) {
            0 -> "о $time"
            1 -> "завтра о $time"
            else -> java.text.SimpleDateFormat("d MMMM о H:mm", java.util.Locale("uk", "UA")).format(java.util.Date(at))
        }
    }

    /** Час нагадування настав — дочекатися паузи в розмові й сказати. */
    fun remind(r: com.speedy.assistant.initiative.Reminder, late: Boolean = false) {
        // Тиша (ніч чи нічний режим) — лише вібрація; уголос — після дотику.
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (settings?.vibrateQuiet == true && (quiet() || hour < 8 || hour >= 22) || guard?.on == true) {
            quietReminders += r
            buzzer?.quietReminder()
            Log.i(TAG, "нагадування в тиші — вібрація: ${r.text}")
            return
        }
        scope.launch {
            awaitTurn()
            runBusy {
                face.setMood(Mood.SURPRISED)
                chirps.play(Chirp.ATTENTION)
                val who = r.forName?.let { "$it, " }.orEmpty()
                val lateText = if (late) " Я мала нагадати ${java.text.SimpleDateFormat("H:mm", java.util.Locale("uk", "UA")).format(java.util.Date(r.at))}." else ""
                say("${who}нагадую: ${r.text}.$lateText")
            }
        }
    }

    /** Ранковий підсумок без моделі: погода тут, календар і нагадування на сьогодні. */
    private suspend fun morningBriefing() {
        val parts = mutableListOf<String>()
        try {
            val here = locator?.locate()
            if (here != null && weather != null) {
                val f = weather.forecast(here.latitude, here.longitude)
                parts += com.speedy.assistant.weather.WeatherText.answer(f, com.speedy.assistant.weather.WeatherQuery(false, null), null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "briefing weather: ${e.message}")
        }
        phone?.todayEvents()?.takeIf { it.isNotEmpty() }?.let { parts += "У календарі: ${it.joinToString("; ")}." }
        val endOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
        }.timeInMillis
        reminders?.upcoming()?.filter { it.at <= endOfDay }?.takeIf { it.isNotEmpty() }
            ?.let { list -> parts += "Нагадування: ${list.joinToString("; ") { "${whenText(it.at)} — ${it.text}" }}." }
        if (parts.isEmpty()) return
        face.setMood(Mood.HAPPY)
        say(parts.joinToString(" "))
    }

    private var lessonAnswer: kotlinx.coroutines.CompletableDeferred<List<String>>? = null

    /** Урок англійської: учень — той, кого Спіді бачить (рівень і прогрес у кожного свої). */
    /**
     * Для кого урок: названий у команді знайомий ("навчи Ліну англійської" — дорослий просить для дитини),
     * інакше — той, хто говорить / кого бачимо.
     */
    private fun learnerFor(command: String): String =
        com.speedy.assistant.school.Learner.named(command) { people?.findName(it) } ?: partner() ?: "гість"

    /** Урок для іншої людини, ніж та, що попросила, — звернутися до неї. */
    private suspend fun greetLearner(name: String) {
        if (name != "гість" && name != partner()) say("$name, починаємо урок!")
    }

    private suspend fun englishLesson(command: String = "") {
        val course = english ?: return say("Я поки не вмію вчити англійської.")
        if (!isOnline()) return say("Для уроку англійської потрібен інтернет: англійську я чую через Google.")
        val name = learnerFor(command)
        greetLearner(name)
        Log.i(TAG, "урок англійської: $name")
        inConversation = false
        val io = lessonIO()
        // Вік: профіль (сказане вголос / голос / явно дитяче обличчя), інакше — голос щойно почутої фрази.
        val child = when (profiles?.age(name)) {
            com.speedy.assistant.people.Profiles.Age.CHILD -> true
            com.speedy.assistant.people.Profiles.Age.ADULT -> false
            else -> voiceSaysChild()
        }
        Log.i(TAG, "вік $name: ${profiles?.age(name)} (очі ${profiles?.eyeLevel(name)}, голос ${profiles?.voiceHz(name)?.toInt()} Гц, щойно ${ears.lastPitchHz?.toInt()} Гц) → дитина=$child")
        val lesson = com.speedy.assistant.english.EnglishLesson(
            course.vocabulary, course.curriculum, course.progress, io, name,
            now = { System.currentTimeMillis() }, extra = course.store, generate = course.generate, child = child,
        )
        val lessonStart = System.currentTimeMillis()
        lessonFor = name to (child == true)
        onLesson(true)
        try {
            lesson.run()
        } finally {
            lessonFor = null
            onLesson(false)
        }
        if (child == true || childSpeaking()) childLimit?.add(name, System.currentTimeMillis() - lessonStart, System.currentTimeMillis())
        Log.i(TAG, "урок англійської завершено ($name, рівень ${course.progress.learner(name).level}): ${lesson.result}")
        face.setMood(Mood.HAPPY)
    }

    private fun lessonIO() = object : com.speedy.assistant.english.LessonIO {
        override suspend fun say(uk: String) = this@Assistant.say(uk)
        override suspend fun sayEnglish(en: String) {
            face.setSpeaking(true)
            try { voice.speak(en, english = true) } finally { face.setSpeaking(false) }
        }
        override suspend fun listen(english: Boolean) = listenLesson(english)
        override suspend fun cheer() { face.setMood(Mood.HAPPY); buzzer?.cheer(); chirps.play(Chirp.HAPPY) }
        override suspend fun oops() { face.setMood(Mood.THINKING) }
        override suspend fun show(picture: List<List<String>>?) = face.showPicture(picture)
    }

    /** Урок математики, логіки тощо: модель складає під учня (вік, рівень, минулі помилки). */
    private suspend fun schoolLesson(subject: com.speedy.assistant.school.Subject, command: String = "") {
        val progress = school ?: return say("Я поки не вмію проводити такі уроки.")
        if (subject == com.speedy.assistant.school.Subject.WRITING) return writingLesson(progress, command)
        if (!isOnline()) return say("Для уроку потрібен інтернет: завдання я складаю разом із моделлю.")
        val name = learnerFor(command)
        greetLearner(name)
        val child = when (profiles?.age(name)) {
            com.speedy.assistant.people.Profiles.Age.CHILD -> true
            com.speedy.assistant.people.Profiles.Age.ADULT -> false
            else -> voiceSaysChild()
        }
        val learner = listOfNotNull(
            name.takeIf { it != "гість" },
            when (child) { true -> "дитина"; false -> "дорослий"; null -> "вік невідомий (найімовірніше дитина)" },
            profiles?.summary(name, System.currentTimeMillis())?.takeIf { it.isNotBlank() },
        ).joinToString("; ")
        Log.i(TAG, "урок ${subject.ua}: $name, рівень ${progress.level(name, subject)}")
        inConversation = false
        val lesson = com.speedy.assistant.school.SchoolLesson(subject, progress, lessonIO(), name, learner, generateJson, now = { System.currentTimeMillis() })
        val start = System.currentTimeMillis()
        lessonFor = name to (child != false)
        onLesson(true)
        try {
            lesson.run()
        } finally {
            face.showPicture(null) // і коли урок перервали дотиком
            lessonFor = null
            onLesson(false)
        }
        if (child == true || childSpeaking()) childLimit?.add(name, System.currentTimeMillis() - start, System.currentTimeMillis())
        Log.i(TAG, "урок ${subject.ua} завершено ($name, рівень ${progress.level(name, subject)}): ${lesson.result}")
        face.setMood(Mood.HAPPY)
    }

    /** Учень поточного уроку і чи він дитина. */
    private var lessonFor: Pair<String, Boolean>? = null

    /**
     * Урок для дитини, а найближче до камери — знайомий дорослий: відповідає, мабуть, він. Голосом
     * не розрізнити (Google звук не віддає), тож за обличчям.
     */
    private fun adultAnswering(): String? {
        val (learner, child) = lessonFor ?: return null
        if (!child) return null
        val speaker = people?.speaker(SystemClock.uptimeMillis(), ANSWER_FACE_MS) ?: return null
        return speaker.takeIf { it != learner && profiles?.age(it) == com.speedy.assistant.people.Profiles.Age.ADULT }
    }

    /** Урок письма: обвести пальцем обриси на екрані (без моделі й інтернету). */
    private suspend fun writingLesson(progress: com.speedy.assistant.school.SchoolProgress, command: String) {
        val t = tracer ?: return say("На цьому екрані я не вмію вчити писати.")
        val name = learnerFor(command)
        greetLearner(name)
        Log.i(TAG, "урок письма: $name, рівень ${progress.level(name, com.speedy.assistant.school.Subject.WRITING)}")
        inConversation = false
        val lesson = com.speedy.assistant.school.WritingLesson(lessonIO(), t, progress, name, now = { System.currentTimeMillis() })
        val start = System.currentTimeMillis()
        try {
            lesson.run()
        } finally {
            t.hide()
        }
        if (profiles?.age(name) == com.speedy.assistant.people.Profiles.Age.CHILD || childSpeaking()) {
            childLimit?.add(name, System.currentTimeMillis() - start, System.currentTimeMillis())
        }
        Log.i(TAG, "урок письма завершено ($name): ${lesson.result}")
        face.setMood(Mood.HAPPY)
    }

    /** Відповідь на уроці: слухаємо одразу (без "Спіді") потрібною мовою, до 9 с. */
    private suspend fun listenLesson(english: Boolean): com.speedy.assistant.english.Heard? {
        var texts = listenIn(if (english) "en-US" else "uk-UA")
        // Дорослий підказав за дитину — не зараховуємо, слухаємо ще раз.
        adultAnswering()?.takeIf { texts != null }?.let { adult ->
            Log.i(TAG, "урок: відповів $adult, а не ${lessonFor?.first} — не зараховую")
            say("${adult}, хай ${lessonFor?.first} відповість сама.".let { if (lessonFor?.first?.let { n -> profiles?.gender(n) } == com.speedy.assistant.people.Gender.MALE) it.replace("сама", "сам") else it })
            texts = listenIn(if (english) "en-US" else "uk-UA")
        }
        Log.d(TAG, "урок почув (${if (english) "en" else "uk"}): $texts, впевненість ${ears.lastConfidence}")
        return texts?.let { com.speedy.assistant.english.Heard(it, ears.lastConfidence) }
    }

    /** Одна фраза одразу (без "Спіді") мовою [language] ("en-US", "ru-RU"); null — мовчали. */
    private suspend fun listenIn(language: String): List<String>? {
        val answer = kotlinx.coroutines.CompletableDeferred<List<String>>()
        lessonAnswer = answer
        ears.language = language
        face.setMood(Mood.LISTENING)
        try {
            ears.start(listenNow = true)
            val texts = kotlinx.coroutines.withTimeoutOrNull(LESSON_LISTEN_MS) { answer.await() }
            ears.stop()
            delay(VoiceListener.UNMUTE_DELAY_MS) // звук ще приглушений після розпізнавача
            return texts
        } finally {
            // І коли урок перервали дотиком: інакше Спіді й далі чула б усе англійською
            // (дитяче «навчи мене англійської» розпізнавалось як «get home now»).
            lessonAnswer = null
            ears.language = "uk-UA"
        }
    }

    /** @return false — не впоралась (хай відповідає модель). */
    private suspend fun handleTranslate(cmd: com.speedy.assistant.translate.TranslateCommand): Boolean {
        val tr = translator ?: return false
        Log.i(TAG, "переклад: $cmd")
        when (cmd) {
            is com.speedy.assistant.translate.TranslateCommand.Book -> {
                val shelf = books ?: return false
                val current = shelf.last(partner())
                    ?: return true.also { say("Ми ще нічого не читали. Скажи «почитай книгу» і назву.") }
                readBook(current, intro = if (cmd.translate) "Добре, читаю українською." else "Добре, читаю як написано.", translate = cmd.translate)
            }
            is com.speedy.assistant.translate.TranslateCommand.Phrase -> {
                val text = cmd.text ?: run {
                    // Фразу іншою мовою розпізнає Google — потрібен інтернет.
                    if (cmd.from != Lang.UK && !isOnline()) return true.also { say("Щоб почути фразу іншою мовою, мені потрібен інтернет.") }
                    say(when (cmd.from) { Lang.EN -> "Кажи англійською."; Lang.RU -> "Кажи російською."; Lang.UK -> "Кажи, що перекласти." })
                    listenIn(cmd.from.locale.toLanguageTag())?.firstOrNull()
                        ?: return true.also { face.setMood(Mood.THINKING); say("Не розчула. Спробуй ще раз.") }
                }
                face.setMood(Mood.THINKING)
                val translated = translateWithModels(tr, text, cmd.from, cmd.to) ?: return true
                face.setMood(Mood.HAPPY)
                sayIn(translated, cmd.to, mutableSetOf())
            }
        }
        return true
    }

    /**
     * Переклад; перший раз для мов — завантаження моделей (~30 МБ кожна) з попередженням.
     * null — не вдалося (Спіді вже сказала).
     */
    private suspend fun translateWithModels(tr: com.speedy.assistant.translate.Translator, text: String, from: Lang, to: Lang): String? = try {
        if (!tr.ready(from, to)) {
            say("Завантажую словники для перекладу, це до шістдесяти мегабайтів, один раз. Хвилинку.")
            tr.prepare(from, to)
        }
        tr.translate(text, from, to)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "переклад $from→$to: ${e.message}")
        face.setMood(Mood.SAD)
        say("Не вдалося перекласти. Для першого разу потрібен інтернет, щоб завантажити словники.")
        null
    }

    /** Тон щойно почутої фрази (якщо свіжий) — у профіль того, хто говорить. */
    private fun noteVoice() {
        val hz = ears.lastPitchHz?.takeIf { SystemClock.uptimeMillis() - ears.lastPitchAt < 15_000 } ?: return
        val name = partner() ?: return
        profiles?.voiceSample(name, hz, System.currentTimeMillis())
        Log.d(TAG, "голос $name: ${hz.toInt()} Гц (середнє ${profiles?.voiceHz(name)?.toInt()})")
    }

    /** Свіжий тон голосу каже "дитина"? (для уроку, коли людина ще незнайома) */
    /** Чи говорить зараз дитина: профіль того, хто говорить, інакше — голос щойно почутої фрази. */
    fun childSpeaking(): Boolean {
        val age = partner()?.let { profiles?.age(it) }
        if (age == com.speedy.assistant.people.Profiles.Age.CHILD) return true
        if (age == com.speedy.assistant.people.Profiles.Age.ADULT) return false
        return voiceSaysChild() == true
    }

    private fun voiceSaysChild(): Boolean? {
        val hz = ears.lastPitchHz?.takeIf { SystemClock.uptimeMillis() - ears.lastPitchAt < 15_000 } ?: return null
        return when {
            hz >= com.speedy.assistant.voice.Pitch.CHILD_HZ -> true
            hz <= com.speedy.assistant.voice.Pitch.ADULT_HZ -> false
            else -> null
        }
    }

    private suspend fun handleHands(cmd: com.speedy.assistant.phone.HandsCommand) {
        Log.i(TAG, "руки: $cmd")
        val hands = com.speedy.assistant.phone.SpeedyHands.instance
        when (cmd) {
            com.speedy.assistant.phone.HandsCommand.AllowNotes -> {
                say("Відкриваю налаштування. Увімкни для Спіді доступ до сповіщень.")
                openAccessSettings("notes")
            }
            com.speedy.assistant.phone.HandsCommand.AllowHands -> {
                say("Відкриваю спеціальні можливості. Увімкни службу «Спіді». У банківських застосунках і налаштуваннях я нічого не натискатиму.")
                openAccessSettings("hands")
            }
            com.speedy.assistant.phone.HandsCommand.News -> {
                val unread = com.speedy.assistant.phone.NotificationStore.notes.unread()
                if (unread.isEmpty()) {
                    say("Нового нічого.")
                } else {
                    say("${notesCount(unread.size)}: ${whoFrom(unread)}. Прочитати?")
                    com.speedy.assistant.phone.NotificationStore.notes.markRead(unread.map { it.key })
                }
            }
            com.speedy.assistant.phone.HandsCommand.ReadNotes -> {
                val store = com.speedy.assistant.phone.NotificationStore.notes
                // Непрочитані, а якщо їх немає — останні (пряме прохання).
                val notes = store.unread().take(3).ifEmpty { store.recent(3) }
                if (notes.isEmpty()) say("Сповіщень немає. Якщо я їх не бачу — скажи «дозволь читати сповіщення».")
                else say(notes.joinToString(". ") { "${it.app}, ${it.title}: ${it.text}" })
                store.markRead(notes.map { it.key })
            }
            else -> {
                if (hands == null) return say("Я ще не можу керувати телефоном. Скажи «дозволь керувати телефоном».")
                if (cmd !is com.speedy.assistant.phone.HandsCommand.ComeBack && cmd !is com.speedy.assistant.phone.HandsCommand.Home) {
                    hands.blockedApp()?.let { return say("У $it я нічого не роблю — тут гроші чи паролі. Зроби це сам.") }
                }
                val ok = when (cmd) {
                    is com.speedy.assistant.phone.HandsCommand.Tap -> hands.click(cmd.text)
                    is com.speedy.assistant.phone.HandsCommand.Scroll -> hands.scroll(cmd.down)
                    com.speedy.assistant.phone.HandsCommand.Back -> hands.back()
                    com.speedy.assistant.phone.HandsCommand.Home -> hands.home()
                    is com.speedy.assistant.phone.HandsCommand.Type -> hands.type(cmd.text)
                    com.speedy.assistant.phone.HandsCommand.Screen -> {
                        val text = hands.screenText()
                        say(if (text.isBlank()) "На екрані нічого не можу прочитати." else "${hands.currentApp()}: $text")
                        true
                    }
                    com.speedy.assistant.phone.HandsCommand.ComeBack -> { bringToFront(); true }
                    else -> false
                }
                if (!ok) say(
                    when (cmd) {
                        is com.speedy.assistant.phone.HandsCommand.Tap -> "Не знайшла «${cmd.text}» на екрані."
                        is com.speedy.assistant.phone.HandsCommand.Type -> "Не бачу поля, куди писати. Натисни на нього."
                        else -> "Не вийшло."
                    }
                )
            }
        }
    }

    private suspend fun addCalendarEvent(e: com.speedy.assistant.phone.NewEvent) {
        val p = phone ?: return say("Я поки не вмію записувати в календар.")
        Log.i(TAG, "календар: ${e.title} ${java.util.Date(e.start)}${if (e.allDay) " (весь день)" else ""}")
        when (p.addEvent(e.title, e.start, e.allDay)) {
            null -> {
                face.setMood(Mood.THINKING)
                say("Дозволь мені записувати в календар — і скажи ще раз.")
                openAccessSettings("calendar")
            }
            false -> {
                face.setMood(Mood.SAD)
                say("Не знайшла на телефоні календаря, куди можна записати.")
            }
            true -> {
                val uk = java.util.Locale("uk", "UA")
                val day = java.text.SimpleDateFormat("EEEE, d MMMM", uk).format(java.util.Date(e.start))
                val time = if (e.allDay) ", на весь день" else " о " + java.text.SimpleDateFormat("H:mm", uk).format(java.util.Date(e.start))
                face.setMood(Mood.HAPPY)
                say("Записала в календар: ${e.title} — $day$time.")
            }
        }
    }

    /** Нагадування, що прийшли в тиші (лише вібрація) — скаже після дотику. */
    private val quietReminders = mutableListOf<com.speedy.assistant.initiative.Reminder>()

    private suspend fun sayQuietReminders() {
        val list = quietReminders.toList()
        quietReminders.clear()
        face.setMood(Mood.SURPRISED)
        for (r in list) {
            val who = r.forName?.let { "$it, " }.orEmpty()
            val at = java.text.SimpleDateFormat("H:mm", java.util.Locale("uk", "UA")).format(java.util.Date(r.at))
            say("${who}нагадую (о $at): ${r.text}.")
        }
    }

    /** Налаштування голосом: спершу PIN (пальцем чи голосом), потім змінити й підтвердити. */
    private suspend fun changeSetting(cmd: com.speedy.assistant.settings.SettingCommand) {
        val s = settings ?: return say("Я поки не вмію змінювати налаштування.")
        Log.i(TAG, "налаштування: $cmd — чекаю PIN")
        face.setMood(Mood.THINKING)
        say("Щоб змінити налаштування, введи або скажи PIN.")
        confirmWithPin {
            scope.launch {
                awaitTurn()
                runBusy {
                    val problem = com.speedy.assistant.settings.SettingCommands.apply(cmd, s)
                    if (problem != null) {
                        face.setMood(Mood.SAD)
                        say(problem)
                    } else {
                        onSettingsChanged()
                        face.setMood(Mood.HAPPY)
                        say("Готово: ${com.speedy.assistant.settings.SettingCommands.describe(cmd)}.")
                    }
                }
            }
        }
    }

    /** Мікрофон вільний для слуху: не грає аудіо у VLC і не слухаємо хропіння. */
    private fun micFree() = playback?.playingAudio != true && night?.active != true && (guard?.on != true || guardListening)

    /** Тиша: нічний режим чи сторож — Спіді нічого не каже сама. */
    private fun quiet() = night?.active == true || guard?.on == true

    /** Сторож: знайомий поруч — слух увімкнено, щоб почути "досить вартувати". */
    private var guardListening = false
    private var guardEarsJob: Job? = null

    /** Камера сторожа впізнала знайомого: слухаємо голос [GUARD_LISTEN_MS] (мікрофон сторож віддав). */
    fun guardFriend(name: String) {
        if (guard?.armed != true) return
        Log.i(TAG, "сторож: $name поруч — слухаю")
        guardListening = true
        if (!busy) ears.start()
        guardEarsJob?.cancel()
        guardEarsJob = scope.launch {
            delay(GUARD_LISTEN_MS)
            while (guard?.friendNear == true) delay(10_000)
            guardListening = false
            if (guard?.on == true) {
                ears.stop()
                delay(VoiceListener.UNMUTE_DELAY_MS)
                guard?.reclaimMic()
            }
        }
    }

    private suspend fun armGuard() {
        val g = guard ?: return say("Я поки не вмію вартувати.")
        if (g.on) return say("Я вже вартую.")
        if (night?.active == true) { night?.stop(); onNight(false) }
        val channels = guardAlerts?.describe().orEmpty()
        face.setMood(Mood.SURPRISED)
        say(
            "Добре, вартую за ${GUARD_DELAY_MS / 1000} секунд. " +
                (if (channels.isEmpty()) "Куди надсилати тривогу, ще не налаштовано — записуватиму все в телефон. "
                else "Якщо помічу щось чуже — надішлю в $channels. ") +
                "Вимкнути — торкнись мене або скажи «досить вартувати», коли я тебе бачу."
        )
        inConversation = false
        awaitingCommandUntil = 0
        guardListening = false
        ears.stop()
        delay(VoiceListener.UNMUTE_DELAY_MS)
        onGuard(true)
        g.arm(GUARD_DELAY_MS)
        face.setMood(Mood.SLEEPY)
    }

    private suspend fun disarmGuard() {
        guard?.disarm()
        guardEarsJob?.cancel()
        guardListening = false
        onGuard(false)
        face.setMood(Mood.HAPPY)
        say("Добре, більше не вартую.")
    }

    /** Вимкнути сторожа: знайомий щойно в кадрі — одразу, інакше — PIN батьків. */
    private fun requestDisarm() {
        if (guard?.friendNear == true) return runBusy { disarmGuard() }
        Log.i(TAG, "сторож: вимкнення — за PIN")
        confirmWithPin { runBusy { disarmGuard() } }
    }

    private suspend fun handleNight(cmd: com.speedy.assistant.night.NightCommand) {
        val watch = night ?: return say("Я поки не вмію слухати вночі.")
        Log.i(TAG, "ніч: $cmd")
        when (cmd) {
            is com.speedy.assistant.night.NightCommand.Start -> {
                val now = System.currentTimeMillis()
                val until = com.speedy.assistant.night.NightCommands.nextTime(cmd.untilHour ?: DEFAULT_WAKE_HOUR, now)
                val hour = java.util.Calendar.getInstance().apply { timeInMillis = until }.get(java.util.Calendar.HOUR_OF_DAY)
                face.setMood(Mood.HAPPY)
                say("Добре, слухатиму до $hour:00. Записуватиму лише тоді, коли хтось хропе, і нікуди цього не відправлятиму. Щоб вимкнути — торкнись мене. На добраніч!")
                inConversation = false
                awaitingCommandUntil = 0
                ears.stop()
                delay(VoiceListener.UNMUTE_DELAY_MS) // розпізнавач має відпустити мікрофон
                watch.start(scope, until)
                onNight(true)
                face.setMood(Mood.SLEEPY)
            }
            com.speedy.assistant.night.NightCommand.Report -> reportNight(onlyNew = false)
            com.speedy.assistant.night.NightCommand.Play -> {
                val clips = nightLog?.clips().orEmpty().map(::File).filter { it.exists() }
                if (clips.isEmpty()) return say(if (nightLog?.listened == true) "Записів хропіння немає." else "Я ще не слухала вночі. Скажи «послухай уночі, чи я хроплю».")
                val folder = clips.first().parentFile ?: return
                val item = com.speedy.assistant.media.MediaItem(folder.path, "Хропіння ${folder.name}", com.speedy.assistant.media.MediaKind.AUDIO, clips)
                startMedia(item)
            }
        }
    }

    /** Вимкнути нічний режим дотиком і коротко сказати, що чула. */
    private suspend fun stopNight() {
        night?.stop()
        onNight(false)
        face.setMood(Mood.HAPPY)
        say("Добре, більше не слухаю.")
        reportNight(onlyNew = false)
    }

    /** Ніч скінчилась сама (ранок) — звичайний режим; підсумок — коли привітаємось чи спитають. */
    fun nightFinished() {
        onNight(false)
        face.setMood(Mood.NEUTRAL)
        if (!busy && micFree()) ears.start()
    }

    /** Підсумок ночі; [onlyNew] — лише якщо ще не казали (ранкове привітання). */
    private suspend fun reportNight(onlyNew: Boolean) {
        val log = nightLog ?: return
        if (onlyNew && (log.reported || !log.listened || night?.active == true)) return
        // Ранкове привітання — лише про щойно минулу ніч, а не тижневої давності.
        if (onlyNew && System.currentTimeMillis() - log.startedAt > 18 * 3600_000L) return
        val text = log.summary() ?: return say("Я ще не слухала вночі. Скажи «послухай уночі, чи я хроплю».")
        log.markReported()
        face.setMood(Mood.HAPPY)
        say(text)
    }

    /** @return false — це не про медіа (хай далі вирішують книги / модель). */
    private suspend fun handleMedia(cmd: com.speedy.assistant.media.MediaCommand): Boolean {
        val shelf = media ?: return false
        val player = playback ?: return false
        if (cmd is com.speedy.assistant.media.MediaCommand.Pause) {
            if (!player.pause()) say("Зараз нічого не грає.")
            return true
        }
        Log.i(TAG, "медіа: $cmd")
        if (!hasFileAccess()) {
            face.setMood(Mood.THINKING)
            say("Щоб шукати фільми й аудіокниги, мені потрібен доступ до файлів. Відкриваю налаштування.")
            openAccessSettings("files")
            return true
        }
        when (cmd) {
            is com.speedy.assistant.media.MediaCommand.Play -> {
                val title = cmd.title ?: return true.also { continueMedia(cmd.kind) }
                if (cmd.online) return true.also { openOnline(title) }
                face.setMood(Mood.THINKING)
                val item = withContext(Dispatchers.IO) { shelf.find(title, cmd.kind) }
                if (item == null) {
                    if (cmd.kind == null) return false
                    // Відео немає в телефоні — шукаємо в мережі.
                    if (cmd.kind == com.speedy.assistant.media.MediaKind.VIDEO && onlineVideo != null) return true.also { openOnline(title) }
                    face.setMood(Mood.SAD)
                    say("Не знайшла ${kindWord(cmd.kind)} «$title».")
                } else startMedia(item)
            }
            is com.speedy.assistant.media.MediaCommand.Continue -> continueMedia(cmd.kind)
            is com.speedy.assistant.media.MediaCommand.List -> {
                face.setMood(Mood.THINKING)
                val items = withContext(Dispatchers.IO) { shelf.all() }.filter { cmd.kind == null || it.kind == cmd.kind }
                    // Тека (аудіокнига, серіал) замість кожного її файлу окремо.
                    .let { all -> all.filter { it.tracks.size > 1 || all.none { f -> f.tracks.size > 1 && it.tracks[0] in f.tracks } } }
                face.setMood(Mood.HAPPY)
                say(
                    if (items.isEmpty()) "Не знайшла в телефоні ${if (cmd.kind == com.speedy.assistant.media.MediaKind.VIDEO) "жодного відео" else "жодного аудіо"}."
                    else "У мене є: " + items.take(MAX_LISTED).joinToString(", ") { "«${it.title}»" } +
                        (if (items.size > MAX_LISTED) " і ще ${items.size - MAX_LISTED}." else ".") +
                        " Скажи «увімкни» і назву."
                )
            }
            com.speedy.assistant.media.MediaCommand.Pause -> {}
        }
        return true
    }

    /** Пошук відео в мережі (YouTube, якщо встановлений, інакше NewPipe чи браузер). */
    private suspend fun openOnline(title: String) {
        face.setMood(Mood.HAPPY)
        val opened = onlineVideo?.search(title)
        Log.i(TAG, "відео в мережі: «$title» → ${opened?.name}")
        say(if (opened == null) "Не маю чим відкрити відео з інтернету." else "Шукаю «$title» у ${opened.name}.")
        // NewPipe лише шукає — перше відео вмикаємо самі (якщо дозволено керувати застосунками).
        val pkg = opened?.newPipe ?: return
        val hands = com.speedy.assistant.phone.SpeedyHands.instance ?: run { Log.i(TAG, "NewPipe: керування застосунками вимкнено — лишаю список"); return }
        playNewPipeResult(hands, pkg, title)
    }

    /**
     * NewPipe показав результати — натиснути перше відео (відкривається й грає саме). "Вміст недоступний"
     * (дитячі відео YouTube NewPipe часто не відтворює) — назад і наступне, до [NEWPIPE_TRIES].
     */
    private suspend fun playNewPipeResult(hands: com.speedy.assistant.phone.SpeedyHands, pkg: String, query: String) {
        val ready = kotlinx.coroutines.withTimeoutOrNull(NEWPIPE_RESULTS_MS) {
            while (!hands.newPipeResults(pkg, query)) delay(500)
            true
        }
        if (ready == null) run { Log.i(TAG, "NewPipe: результатів пошуку не дочекалась"); return }
        delay(500) // список домальовується
        repeat(NEWPIPE_TRIES) { i ->
            val video = hands.pickNewPipeResult(pkg, i) ?: run { Log.i(TAG, "NewPipe: більше відео в списку немає"); return }
            // Відкривається кілька секунд: або грає, або панель помилки.
            val failed = kotlinx.coroutines.withTimeoutOrNull(NEWPIPE_OPEN_MS) {
                while (!hands.newPipeError(pkg)) delay(700)
                true
            } == true
            if (!failed) run { Log.i(TAG, "NewPipe: увімкнула «$video»"); return }
            if (i == NEWPIPE_TRIES - 1 && hands.newPipeOpenInBrowser(pkg)) {
                Log.i(TAG, "NewPipe: «$video» недоступне — відкриваю в браузері")
                return say("У NewPipe ці відео не відкриваються, тож вмикаю в браузері.")
            }
            Log.i(TAG, "NewPipe: «$video» недоступне — пробую наступне")
            hands.back()
            delay(1_500)
        }
        say("Ці відео в NewPipe не відкриваються. Вибери інше в списку.")
    }

    /** "Продовж дивитись" / "наступна серія": останнє того виду — з місця, де зупинились. */
    private suspend fun continueMedia(kind: com.speedy.assistant.media.MediaKind?) {
        if (kind != com.speedy.assistant.media.MediaKind.VIDEO && playback?.canResume == true) {
            playback.resume()
            return
        }
        val item = media?.last()?.takeIf { kind == null || it.kind == kind }
            ?: return say("Ми ще нічого не ${if (kind == com.speedy.assistant.media.MediaKind.AUDIO) "слухали" else "дивились"}. Скажи «увімкни фільм» чи «увімкни аудіокнигу» і назву.")
        startMedia(item)
    }

    private fun kindWord(kind: com.speedy.assistant.media.MediaKind?) =
        if (kind == com.speedy.assistant.media.MediaKind.VIDEO) "відео" else "аудіо"

    private var mediaTipsGiven = false

    /** Увімкнути у VLC з місця, де зупинились (немає VLC — відкрити магазин). */
    private suspend fun startMedia(item: com.speedy.assistant.media.MediaItem) {
        val v = vlc ?: return
        val shelf = media ?: return
        val place = shelf.place(item)?.takeIf { !it.finished }
        if (!v.installed) {
            // Немає VLC — інший плеєр телефону (без пам'яті, де зупинились); немає жодного — магазин.
            val file = item.tracks.getOrElse(place?.track ?: 0) { item.tracks.first() }
            face.setMood(Mood.HAPPY)
            say("Вмикаю «${item.title}».")
            if (v.playWithAny(file, item.kind)) return
            face.setMood(Mood.THINKING)
            say("Не маю чим це відтворити. Відкриваю магазин — встанови плеєр VLC і попроси ще раз.")
            return v.openStore()
        }
        val resume = place != null && (place.positionMs > 30_000 || place.track > 0)
        val part = if (item.tracks.size > 1 && place != null) ", ${if (item.kind == com.speedy.assistant.media.MediaKind.VIDEO) "серія" else "частина"} ${place.track + 1}" else ""
        face.setMood(Mood.HAPPY)
        say(if (resume) "Продовжую «${item.title}»$part." else "Вмикаю «${item.title}».")
        if (item.kind == com.speedy.assistant.media.MediaKind.AUDIO && !mediaTipsGiven) {
            mediaTipsGiven = true
            say(
                "Поки грає, я не слухаю — торкнись мене, щоб поставити на паузу й поговорити." +
                    if (!v.canTrack) " А щоб я пам'ятала, де ми зупинились, дозволь мені читати сповіщення." else ""
            )
        }
        inConversation = false
        playback?.start(item, place)
    }

    /** Подія відтворення: закрили фільм, додивились серію, дослухали аудіокнигу. */
    fun onMediaEvent(event: com.speedy.assistant.media.MediaEvent) {
        scope.launch {
            awaitTurn()
            runBusy {
                when (event) {
                    is com.speedy.assistant.media.MediaEvent.Stopped -> {
                        val min = event.positionMs / 60_000
                        face.setMood(Mood.HAPPY)
                        val at = if (min > 0) ": $min ${com.speedy.assistant.english.EnglishLesson.plural(min.toInt(), "хвилина", "хвилини", "хвилин")}" else ""
                        say("Запам'ятала, де ми зупинились$at. Скажи «продовж дивитись», щоб дивитись далі.")
                    }
                    is com.speedy.assistant.media.MediaEvent.NextReady -> {
                        face.setMood(Mood.HAPPY)
                        say("Серію закінчено. Скажи «наступна серія», щоб дивитись далі.")
                    }
                    is com.speedy.assistant.media.MediaEvent.Finished -> {
                        face.setMood(Mood.HAPPY)
                        chirps.play(Chirp.HAPPY)
                        say("«${event.item.title}» — до кінця.")
                    }
                }
            }
        }
    }

    /** Спитала "яку почитати?" — наступна фраза, ймовірно, назва книги. */
    private var awaitingBook = false
    /** Коли востаннє читала книгу: "продовжуй" одразу після — це про книгу. */
    private var lastReadAt = Long.MIN_VALUE / 2

    /** @return false — це не про книги (хай відповідає модель). */
    private suspend fun handleBook(cmd: BookCommand): Boolean {
        val shelf = books ?: return false
        // "Продовжуй" після паузи аудіо у VLC (дотик) — грає далі.
        if (cmd is BookCommand.Continue && cmd.bare && playback?.canResume == true) return true.also { playback.resume() }
        if (cmd is BookCommand.Continue && cmd.bare && SystemClock.uptimeMillis() - lastReadAt > BARE_CONTINUE_MS) return false
        val reader = partner()
        // "почитай мені" без назви — книга, лише якщо є що продовжити.
        if (cmd is BookCommand.Read && cmd.title == null && !cmd.explicit && shelf.last(reader) == null) return false
        Log.i(TAG, "книги: $cmd")
        if (!hasFileAccess()) {
            face.setMood(Mood.THINKING)
            say("Щоб шукати книги, мені потрібен доступ до файлів. Відкриваю налаштування.")
            openAccessSettings("files")
            return true
        }
        val current = shelf.last(reader)
        val place = current?.let { shelf.place(it) }
        when (cmd) {
            is BookCommand.Read -> {
                val title = cmd.title
                if (title == null) {
                    if (current != null && place?.finished != true) readBook(current) else offerBooks()
                    return true
                }
                face.setMood(Mood.THINKING)
                val file = withContext(Dispatchers.IO) { shelf.find(title) }
                if (file == null) {
                    if (!cmd.explicit) return false
                    offerBooks("Не знайшла книгу «$title».")
                } else readBook(file, translate = cmd.translate.takeIf { it })
            }
            is BookCommand.Continue -> if (current == null) offerBooks("Ми ще нічого не читали.") else readBook(current)
            BookCommand.List -> offerBooks()
            BookCommand.Which -> {
                if (current == null) return true.also { say("Ми ще нічого не читали. Скажи «почитай книгу» і назву.") }
                val title = shelf.title(current)
                face.setMood(Mood.HAPPY)
                say(
                    if (place == null || place.finished) "Останньою ми читали «$title» і дочитали до кінця."
                    else "Ми читаємо «$title», ${unitWord(current)} ${place.chapter + 1}" +
                        (if (place.chapters > 0) " з ${place.chapters}." else ".")
                )
            }
            BookCommand.FromStart -> {
                if (current == null) return true.also { offerBooks("Ми ще нічого не читали.") }
                readBook(current, Place(0, 0), "Читаю «${shelf.title(current)}» з початку.")
            }
            BookCommand.ChapterStart, BookCommand.NextChapter, BookCommand.PreviousChapter -> {
                if (current == null || place == null) return true.also { offerBooks("Ми ще нічого не читали.") }
                val chapter = when (cmd) {
                    BookCommand.NextChapter -> place.chapter + 1
                    BookCommand.PreviousChapter -> place.chapter - 1
                    else -> place.chapter
                }
                val unit = unitWord(current)
                if (chapter < 0) return true.also { say(if (unit == "розділ") "Це перший розділ." else "Це перша сторінка.") }
                if (place.chapters in 1..chapter) return true.also { say(if (unit == "розділ") "Це останній розділ." else "Це остання сторінка.") }
                readBook(current, Place(chapter, 0), "${unit.replaceFirstChar { it.uppercase() }} ${chapter + 1}.")
            }
        }
        return true
    }

    /** Що є на полиці: останні прочитані — першими; потім чекаємо назву. */
    private suspend fun offerBooks(prefix: String? = null) {
        val shelf = books ?: return
        face.setMood(Mood.THINKING)
        val all = withContext(Dispatchers.IO) { shelf.all() }
        val start = prefix?.let { "$it " }.orEmpty()
        if (all.isEmpty()) {
            face.setMood(Mood.SAD)
            return say("${start}У телефоні я не знайшла жодної книги. Скопіюй у пам'ять телефона файли ${BOOK_FORMATS}, у будь-яку теку.")
        }
        val titles = all.sortedWith(compareByDescending<File> { shelf.place(it) != null }.thenBy { shelf.title(it) })
            .map { shelf.title(it) }.distinct()
        val more = titles.size - MAX_LISTED
        face.setMood(Mood.HAPPY)
        say(start + (if (titles.size == 1) "У мене є одна книга: «${titles[0]}». " else "У мене є: ") +
            (if (titles.size == 1) "" else titles.take(MAX_LISTED).joinToString(", ") { "«$it»" } + (if (more > 0) " і ще $more. " else ". ")) +
            "Яку почитати?")
        awaitingBook = true
        awaitingCommandUntil = SystemClock.uptimeMillis() + FOLLOW_UP_WINDOW_MS
    }

    /**
     * Відкрити книгу; PDF-скан — через розпізнавання (словник для нього завантажуємо при першому
     * скані). null — не вийшло (Спіді вже пояснила чому).
     */
    private suspend fun openBook(shelf: com.speedy.assistant.books.Bookshelf, file: File): com.speedy.assistant.books.Book? {
        val title = shelf.title(file)
        suspend fun attempt(open: () -> com.speedy.assistant.books.Book): com.speedy.assistant.books.Book? = try {
            withContext(Dispatchers.IO) { open() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.speedy.assistant.books.NoTextException) {
            face.setMood(Mood.SAD)
            say("У книзі «$title» немає тексту, який я можу прочитати.")
            null
        } catch (e: com.speedy.assistant.books.OcrNotReadyException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "книга ${file.path}: ${e.message}")
            face.setMood(Mood.SAD)
            say("Не вдалося відкрити «$title» — файл пошкоджений або незнайомого формату.")
            null
        }
        return try {
            attempt { shelf.open(file) }
        } catch (e: com.speedy.assistant.books.OcrNotReadyException) {
            val scans = shelf.scans ?: return null
            say("«$title» — це скан. Щоб розпізнавати сторінки, мені треба завантажити словники українською, російською й англійською, це ${scans.downloadMb} ${com.speedy.assistant.english.EnglishLesson.plural(scans.downloadMb, "мегабайт", "мегабайти", "мегабайтів")}. Хвилинку.")
            face.setMood(Mood.THINKING)
            try {
                scans.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "словник для розпізнавання: ${e.message}")
                face.setMood(Mood.SAD)
                chirps.play(Chirp.NO_NETWORK)
                say("Не вдалося завантажити словник. Перевір інтернет і попроси ще раз.")
                return null
            }
            try {
                attempt { scans.open(file) }
            } catch (e: com.speedy.assistant.books.OcrNotReadyException) {
                null
            }
        }
    }

    /** Моделі перекладу англійська/російська → українська — заздалегідь (з попередженням). */
    private suspend fun prepareBookTranslation(): Boolean {
        val tr = translator ?: return false
        return try {
            if (!tr.ready(Lang.EN, Lang.UK) || !tr.ready(Lang.RU, Lang.UK)) {
                say("Завантажую словники для перекладу, це до дев'яноста мегабайтів, один раз. Хвилинку.")
                tr.prepare(Lang.EN, Lang.UK)
                tr.prepare(Lang.RU, Lang.UK)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "моделі перекладу: ${e.message}")
            say("Не вдалося завантажити словники перекладу — читатиму як написано. Для першого разу потрібен інтернет.")
            false
        }
    }

    /** Переклад фрагмента книги українською; null — не вийшло (читаємо оригінал). */
    private suspend fun translateOrNull(text: String, from: Lang): String? = try {
        translator?.translate(text, from, Lang.UK)?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "переклад фрагмента: ${e.message}")
        null
    }

    /** Код у технічній книзі не перекладаємо: багато дужок, "=", ";" — читаємо як є. */
    private fun looksLikeCode(text: String): Boolean =
        text.count { it in "{}()[];=<>" } > text.length / 20

    private fun unitWord(file: File) =if (file.name.endsWith(".pdf", ignoreCase = true)) "сторінка" else "розділ"

    /**
     * Читає книгу вголос із [from] (інакше — зі збереженого місця), поки не дочитає, не торкнуться
     * екрана або не настане нагадування. Місце зберігається перед кожним фрагментом, тож після
     * зупинки продовжуємо з перерваного речення.
     */
    private suspend fun readBook(file: File, from: Place? = null, intro: String? = null, translate: Boolean? = null) {
        val shelf = books ?: return
        val reader = partner()
        val saved = shelf.place(file)
        face.setMood(Mood.THINKING)
        val book = openBook(shelf, file) ?: return
        book.use {
            if (book.chapters == 0) return say("У книзі «${shelf.title(file)}» порожньо.")
            val title = book.title ?: Books.titleFromName(file)
            val fresh = from == null && (saved == null || saved.finished)
            val start = from ?: saved?.takeIf { !it.finished } ?: Place(0, 0)
            var chapter = start.chapter.coerceIn(0, book.chapters - 1)
            var sentence = start.sentence
            Log.i(TAG, "читаю ${file.path} з ${chapter + 1}/${book.chapters}, речення $sentence")
            inConversation = false
            val child = childSpeaking()
            val started = System.currentTimeMillis()
            face.setMood(Mood.HAPPY)
            say(intro ?: if (fresh) "Читаю «$title». Щоб зупинити, торкнись мене." else "Продовжую «$title».")
            // Скан: пояснюємо один раз, далі — коротко, коли сторінку доводиться чекати.
            var explained = fresh && book.scanned
            if (explained) say("Це скан: кожну сторінку я спершу розглядаю, тож буде трохи повільніше.")
            var chapterLang: Lang? = null
            /** Про відсутній голос мови кажемо раз на книгу. */
            val warned = mutableSetOf<Lang>()
            // Переклад — властивість книги (не місця): переходи між розділами його не скидають.
            var translating = (translate ?: (saved?.translate == true)) && translator != null
            if (translating && !prepareBookTranslation()) translating = false
            try {
                while (chapter < book.chapters) {
                    if ((book as? com.speedy.assistant.books.ScannedPdfBook)?.isReady(chapter) == false) {
                        face.setMood(Mood.THINKING)
                        if (!explained) say("Хвилинку, розглядаю сторінку.")
                        explained = false
                    }
                    val text = try {
                        // Перериваємо й очікування розпізнавання: дотик до екрана — і одразу стоп.
                        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { book.chapter(chapter).text }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "розділ ${chapter + 1} не прочитався: ${e.message}")
                        ""
                    }
                    // Мова розділу — за всім його текстом; фрагмента — за ним самим (код англійською
                    // в російській книзі), а короткий / неясний — мовою розділу.
                    chapterLang = Lang.detect(text, chapterLang) ?: chapterLang
                    val chunks = com.speedy.assistant.books.Sentences.chunks(com.speedy.assistant.books.Sentences.split(text), sentence)
                    val langs = chunks.map { Lang.detect(it.text, chapterLang) ?: Lang.UK }
                    // Переклад: наступний фрагмент перекладається, поки звучить поточний (дочірня
                    // задача читання — дотик до екрана скасовує й її).
                    val here = CoroutineScope(currentCoroutineContext())
                    fun translation(i: Int) = if (!translating || langs[i] == Lang.UK || looksLikeCode(chunks[i].text)) null
                        else here.async { translateOrNull(chunks[i].text, langs[i]) }
                    var next = chunks.indices.firstOrNull()?.let(::translation)
                    for ((i, chunk) in chunks.withIndex()) {
                        val pending = next
                        next = if (i + 1 < chunks.size) translation(i + 1) else null
                        shelf.save(file, reader, Place(chapter, chunk.first, book.chapters, title, translate = translating))
                        lastReadAt = SystemClock.uptimeMillis()
                        if (waitingForTurn > 0) {
                            Log.i(TAG, "читання на паузі: чекає нагадування")
                            pending?.cancel(); next?.cancel()
                            return
                        }
                        face.setMood(Mood.NEUTRAL)
                        val translated = pending?.await()
                        if (translated != null) say(translated) else sayIn(chunk.text, langs[i], warned)
                    }
                    chapter++
                    sentence = 0
                }
                shelf.save(file, reader, Place(0, 0, book.chapters, title, finished = true, translate = translating))
                face.setMood(Mood.HAPPY)
                chirps.play(Chirp.HAPPY)
                say("Кінець книги «$title».")
            } finally {
                lastReadAt = SystemClock.uptimeMillis()
                if (child) childLimit?.add(reader ?: "дитина", System.currentTimeMillis() - started, System.currentTimeMillis())
            }
        }
    }

    private var lastNoteAnnounced = Long.MIN_VALUE / 2

    /**
     * Нові сповіщення — коротко, хто й звідки (без тексту): "Нове повідомлення у Viber від мами"
     * або разом, якщо їх кілька. Після цього — прочитані. Поки Спіді зайнята, вночі чи щойно
     * повідомляла — чекають (виклик повторюється). Банківські вголос не озвучуємо.
     */
    fun announceNotes() {
        val now = SystemClock.uptimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (busy || quiet() || now - lastNoteAnnounced < 60_000 || hour < 8 || hour >= 22) return
        val fresh = com.speedy.assistant.phone.NotificationStore.notes.unread()
            .filterNot { com.speedy.assistant.phone.Safety.blocked("", it.app) }
        if (fresh.isEmpty()) return
        lastNoteAnnounced = now
        com.speedy.assistant.phone.NotificationStore.notes.markRead(fresh.map { it.key })
        runBusy {
            chirps.play(Chirp.ATTENTION)
            say(
                if (fresh.size == 1) "Нове повідомлення у ${whoFrom(fresh)}."
                else "${notesCount(fresh.size)}: ${whoFrom(fresh)}."
            )
        }
    }

    /** "Viber від мами, Telegram від Кості" (до 4, решта — "і ще N"). */
    private fun whoFrom(notes: List<com.speedy.assistant.phone.Note>): String {
        val parts = notes.take(4).map { n -> n.app + (n.title.takeIf { it.isNotBlank() }?.let { " від $it" } ?: "") }
        return parts.joinToString(", ") + if (notes.size > 4) " і ще ${notes.size - 4}" else ""
    }

    private fun notesCount(n: Int) = "У тебе $n ${com.speedy.assistant.english.EnglishLesson.plural(n, "нове повідомлення", "нові повідомлення", "нових повідомлень")}"

    /** Хто зараз співрозмовник: одна знайома людина в кадрі (інакше — не знаємо напевно). */
    /** "Я Ліна" у репліці — хто говорить, сказано прямо (сильніше за найближче обличчя в кадрі). */
    private var claimed: Pair<String, Long>? = null

    /** Знайоме ім'я після "я" будь-де у фразі ("привіт, я Ліна, а ти що робиш") — співрозмовник. */
    private fun noteClaimedName(candidates: List<String>) {
        val p = people ?: return
        val now = SystemClock.uptimeMillis()
        for (c in candidates) {
            for (m in CLAIM.findAll(c.lowercase())) {
                // Лише точний збіг за звучанням ("ліна" ~ "Ліна"): "я знаю", "я тут" не мають стати іменем.
                val key = People.soundKey(m.groupValues[1])
                val name = p.names().firstOrNull { People.soundKey(it) == key } ?: continue
                claimed = name to now
                val refreshed = p.refresh(name, now)
                Log.i(TAG, "говорить $name (сказала сама)${if (refreshed) " — образ обличчя уточнено" else ""}")
                return
            }
        }
    }

    private fun partner(): String? {
        val now = SystemClock.uptimeMillis()
        // Сказала "я Ліна" — вона, поки розмова свіжа; але свіжий дорослий голос дитиною не робимо.
        claimed?.takeIf { now - it.second < CLAIM_MS }?.first?.let { name ->
            val child = profiles?.age(name) == com.speedy.assistant.people.Profiles.Age.CHILD
            if (!(child && voiceSaysChild() == false)) return name
        }
        // Голос цієї фрази впевнено чийсь — це він, навіть якщо ближче до камери інше обличчя.
        voiceGuess?.takeIf { now - it.third < VOICE_FRESH_MS && it.second >= com.speedy.assistant.voice.VoiceBook.STRONG }?.first?.let { return it }
        val closest = people?.speaker(now) ?: return null
        // Говорить дитина (тон голосу), а найближче — дорослий (тримає телефон, сидить поруч):
        // співрозмовник — дитина в кадрі, якщо її впізнали, інакше не знаємо. Не приписуємо дорослому
        // (і дитячий ліміт не обходиться).
        if (voiceSaysChild() == true && profiles?.age(closest) == com.speedy.assistant.people.Profiles.Age.ADULT) {
            return people.presentNames(now).firstOrNull { profiles.age(it) == com.speedy.assistant.people.Profiles.Age.CHILD }
        }
        return closest
    }

    /** Хто зараз говорить зі Спіді (для довідки моделі про співрозмовника). */
    fun speakerName(): String? = partner()

    /** Факт — у профіль співрозмовника, а якщо його не знаємо — у загальну пам'ять. */
    private fun rememberFact(fact: String) {
        val name = partner()
        val stored = if (name != null && profiles != null) profiles.addFact(name, fact, System.currentTimeMillis())
        else memory?.add(fact) == true
        if (stored) Log.i(TAG, "запам'ятала${name?.let { " про $it" }.orEmpty()}: $fact")
    }

    /** @return false — не впоралась локально, хай відповідає модель. */
    private suspend fun handlePhone(cmd: com.speedy.assistant.phone.PhoneCommand): Boolean {
        val p = phone ?: return false
        Log.i(TAG, "телефон: $cmd")
        when (cmd) {
            is com.speedy.assistant.phone.PhoneCommand.OpenApp -> {
                val label = p.openApp(cmd.name) ?: return false
                face.setMood(Mood.HAPPY)
                say("Відкриваю $label.")
            }
            is com.speedy.assistant.phone.PhoneCommand.Alarm -> {
                val time = "%d:%02d".format(cmd.hour, cmd.minute)
                if (p.setAlarm(cmd.hour, cmd.minute)) { face.setMood(Mood.HAPPY); say("Будильник на $time поставила.") }
                else { face.setMood(Mood.SAD); say("Не вийшло поставити будильник.") }
            }
            is com.speedy.assistant.phone.PhoneCommand.Timer -> {
                val text = when {
                    cmd.seconds % 3600 == 0 -> "${cmd.seconds / 3600} год"
                    cmd.seconds % 60 == 0 -> "${cmd.seconds / 60} хв"
                    else -> "${cmd.seconds} с"
                }
                if (p.setTimer(cmd.seconds)) { face.setMood(Mood.HAPPY); say("Таймер на $text запустила.") }
                else { face.setMood(Mood.SAD); say("Не вийшло запустити таймер.") }
            }
            is com.speedy.assistant.phone.PhoneCommand.ContactNumber -> {
                val found = p.findContacts(cmd.name)
                when {
                    found == null -> say("Дозволь мені читати контакти в налаштуваннях телефона.")
                    found.isEmpty() -> say("Не знайшла ${cmd.name} у контактах.")
                    else -> {
                        face.setMood(Mood.HAPPY)
                        say(found.take(2).joinToString(". ") { "${it.name}: ${spellNumber(it.number)}" } + ".")
                    }
                }
            }
            is com.speedy.assistant.phone.PhoneCommand.Call -> {
                val contact = p.findContacts(cmd.name)
                when {
                    contact == null -> say("Дозволь мені читати контакти в налаштуваннях телефона.")
                    contact.isEmpty() -> say("Не знайшла ${cmd.name} у контактах.")
                    else -> {
                        face.setMood(Mood.HAPPY)
                        say("Набираю ${contact.first().name}. Натисни виклик.")
                        p.dial(contact.first().number)
                    }
                }
            }
            com.speedy.assistant.phone.PhoneCommand.TodayCalendar -> {
                val events = p.todayEvents()
                when {
                    events == null -> say("Дозволь мені читати календар у налаштуваннях телефона.")
                    events.isEmpty() -> say("Сьогодні в календарі нічого немає.")
                    else -> { face.setMood(Mood.HAPPY); say("Сьогодні: ${events.joinToString("; ")}.") }
                }
            }
        }
        return true
    }

    /** Курс НБУ без моделі. false — не вдалось (хай відповідає модель із пошуком). */
    private suspend fun tellRate(code: String): Boolean {
        val c = currency ?: return false
        val (rate, date) = try {
            c.rate(code) ?: return false
        } catch (e: java.io.IOException) {
            Log.w(TAG, "НБУ: ${e.message}")
            return false
        }
        val name = mapOf("USD" to "долар", "EUR" to "євро", "PLN" to "злотий", "GBP" to "фунт").getOrDefault(code, code)
        face.setMood(Mood.HAPPY)
        say("Курс НБУ на ${date.substringBeforeLast('.')}: $name — ${com.speedy.assistant.weather.Currency.spoken(rate)}.")
        return true
    }

    /** "+380671234567" → "плюс 380 671 234 567" — щоб синтезатор читав групами, а не мільярдами. */
    private fun spellNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        val plus = if (number.trim().startsWith("+")) "плюс " else ""
        return plus + digits.chunked(if (digits.length > 10) 3 else 2).joinToString(" ")
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
            // Повітря — для "зараз" і окремого питання (на завтра прогнозу повітря не даємо); не вдалось — без нього.
            val air = if (query.tomorrow) null else try {
                service.airQuality(lat, lon)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "air quality failed", e)
                null
            }
            face.setMood(if ((air?.aqi ?: 0) >= 60) Mood.SAD else Mood.HAPPY)
            say(com.speedy.assistant.weather.WeatherText.answer(forecast, query, name, air))
        } catch (e: java.io.IOException) {
            Log.w(TAG, "weather failed", e)
            face.setMood(Mood.SAD)
            chirps.play(Chirp.NO_NETWORK)
            say("Не вдалося дізнатися погоду. Перевір інтернет.")
        }
    }

    /**
     * Огляд новин: де ми (місто), що цікавить співрозмовника — пошук свіжих новин, далі модель складає
     * короткий огляд за важливістю (і може відповісти на "розкажи детальніше про…").
     */
    private suspend fun newsDigest() {
        val search = webSearch ?: return say("Я поки не вмію шукати новини.")
        if (!isOnline()) return say("Для новин потрібен інтернет.")
        face.setMood(Mood.THINKING)
        say("Зараз гляну новини…")
        val place = locator?.let { loc ->
            kotlinx.coroutines.withTimeoutOrNull(NEWS_LOCATE_MS) { loc.locate()?.let { loc.placeName(it) } }
        }?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } // лише місто: вулицю в пошук не віддаємо
        val name = partner()
        val interests = name?.let { profiles?.interests(it)?.first }.orEmpty()
        val query = com.speedy.assistant.news.News.query(place, interests, System.currentTimeMillis())
        val found = try {
            search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "новини: пошук не вдався", e)
            null
        }
        Log.i(TAG, "новини: місце ${place ?: "—"}, інтереси $interests, знайдено ${found?.length ?: 0} симв.")
        inConversation = true
        askModel(com.speedy.assistant.news.News.digestPrompt(found, place, interests))
    }

    private suspend fun tellWhere() {
        val loc = locator ?: return say("Я не вмію визначати місцезнаходження.")
        face.setMood(Mood.THINKING)
        val here = loc.locate() ?: return say("Не можу визначити, де я. Перевір, чи увімкнене місцезнаходження.").also { face.setMood(Mood.SAD) }
        val name = loc.placeName(here)
        face.setMood(Mood.HAPPY)
        say(name?.let { "Ми тут: $it." } ?: "Мої координати: %.3f, %.3f.".format(here.latitude, here.longitude))
    }

    /**
     * Потоковий запит: кожне готове речення відповіді одразу йде в мовлення, поки модель
     * дописує решту; настрій — щойно відомий. У лог — затримка до першого слова й загальна.
     */
    private suspend fun ask(text: String, image: ByteArray? = null, extraImages: List<ByteArray> = emptyList(), audio: ByteArray? = null): AgentReply = coroutineScope {
        val started = SystemClock.uptimeMillis()
        var firstWord = 0L
        val sentences = Channel<String>(Channel.UNLIMITED)
        val speaking = launch {
            for (sentence in sentences) {
                if (firstWord == 0L) firstWord = SystemClock.uptimeMillis()
                say(sentence)
            }
        }
        val reply = try {
            askBrain {
                brain.ask(
                    text, image, extraImages, audio,
                    onMood = { mood -> launch { face.setMood(Mood.parse(mood)) } },
                    onSentence = { sentences.trySend(it) },
                )
            }
        } finally {
            sentences.close()
        }
        val answered = SystemClock.uptimeMillis()
        speaking.join()
        Log.i(TAG, "затримка: перше слово ${if (firstWord > 0) firstWord - started else -1} мс, відповідь ${answered - started} мс")
        reply
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

    /**
     * Текст книги голосом її мови. Немає такого голосу в синтезаторі — українським, і раз на книгу
     * про це скажемо ([warned] — про які мови вже сказали).
     */
    private suspend fun sayIn(text: String, lang: Lang, warned: MutableSet<Lang>) {
        if (lang == Lang.UK) return say(text)
        if (!voice.supports(lang.locale)) {
            if (warned.add(lang)) {
                Log.w(TAG, "немає голосу $lang")
                say("У мене немає ${lang.spoken} голосу, тож читатиму українським. Його можна додати в синтезаторі мовлення, наприклад у RHVoice.")
            }
            return say(text)
        }
        Log.d(TAG, "каже ($lang): $text")
        face.setSpeaking(true)
        try {
            voice.speak(text, locale = lang.locale)
        } finally {
            face.setSpeaking(false)
        }
    }

    private suspend fun say(text: String) {
        Log.d(TAG, "каже: $text")
        face.setSpeaking(true)
        try {
            voice.speak(text)
        } finally {
            face.setSpeaking(false)
        }
    }

    /**
     * Розмова з моделлю, а далі тиша (фразу загубив розпізнавач, пропав звук): за [PauseCheck.PAUSE_MS]
     * перевіряти, чи співрозмовник ще тут — камери й короткі записи звуку, — і продовжити розмову або
     * тихо її завершити. За паузу — щонайбільше одна репліка Спіді.
     */
    private fun schedulePauseCheck() {
        pauseJob?.cancel()
        pauseJob = null
        if (pausedOnce || quiet() || !micFree() || lessonAnswer != null) return
        val repliedAt = SystemClock.uptimeMillis()
        pauseJob = scope.launch {
            delay(PauseCheck.PAUSE_MS)
            val until = SystemClock.uptimeMillis() + PauseCheck.CHECK_MS
            // Що за цей час розбере розпізнавач — теж продовження розмови (без "Спіді").
            awaitingCommandUntil = maxOf(awaitingCommandUntil, until)
            inConversation = true
            idleJob?.cancel()
            Log.i(TAG, "пауза в розмові — перевіряю, чи співрозмовник ще тут")
            var present = 0
            var tick = 0
            while (true) {
                val started = SystemClock.uptimeMillis()
                if (busy || quiet() || !micFree()) return@launch
                val back = tick++ % 2 == 1 // задня камера — через раз (знімок і детектор — не безкоштовні)
                val (clip, seen) = presenceTick(back)
                val speech = clip?.speech == true
                if (speech || seen) present++
                val timeLeft = SystemClock.uptimeMillis() + PauseCheck.TICK_MS < until
                val next = PauseCheck.next(speech, hearsAudio(), present, timeLeft)
                Log.i(TAG, "пауза: ${if (back) "обидві камери" else "передня камера"} — ${if (seen) "є людина" else "нікого"}, " +
                    "мовлення ${clip?.speechWindows ?: "—"} вікон (класи ${clip?.topClasses ?: "—"}) → $next")
                when (next) {
                    PauseCheck.Next.WAIT -> delay((PauseCheck.TICK_MS - (SystemClock.uptimeMillis() - started)).coerceAtLeast(500))
                    PauseCheck.Next.END -> {
                        pauseJob = null
                        Log.i(TAG, "пауза: співрозмовника немає — тихо завершую розмову")
                        awaitingCommandUntil = 0
                        inConversation = false
                        scheduleIdle()
                        return@launch
                    }
                    PauseCheck.Next.ANSWER_SPEECH, PauseCheck.Next.CONTINUE -> {
                        pauseJob = null
                        pausedOnce = true
                        val silent = ((SystemClock.uptimeMillis() - repliedAt) / 1000).toInt()
                        val wav = clip?.wav?.takeIf { next == PauseCheck.Next.ANSWER_SPEECH }
                        runBusy { pauseFollowUp(silent, wav) }
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Одна перевірка паузи: кілька секунд звуку (розпізнавач на цей час відпускає мікрофон), передня
     * камера — з камери присутності, задня ([back]) — знімком одночасно із записом.
     * @return запис (null — мікрофон зайнятий) і чи видно людину
     */
    private suspend fun presenceTick(back: Boolean): Pair<com.speedy.assistant.voice.SoundClip.Clip?, Boolean> {
        ears.stop()
        try {
            val front = personInFront()
            delay(VoiceListener.UNMUTE_DELAY_MS) // розпізнавач має відпустити мікрофон
            return coroutineScope {
                val behind = if (back) async { personBehind() } else null
                val clip = recordSound(PauseCheck.RECORD_SECONDS)
                clip to (front || behind?.await() == true)
            }
        } finally {
            // Перервали (заговорили, торкнулись) — слух уже вмикає runBusy.
            if (!busy && micFree()) ears.start(listenNow = true)
        }
    }

    /** Знімок задньою камерою і локальний детектор: чи є там людина (без моделі). */
    private suspend fun personBehind(): Boolean {
        onBusyChanged(true) // камера присутності відпускає камеру
        return try {
            delay(SETTLE_MS)
            kotlinx.coroutines.withTimeoutOrNull(PERSON_PHOTO_MS) {
                personInPhoto(camera.take(front = false, maxSide = 640, quick = true))
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "пауза: задня камера не вдалась", e)
            false
        } finally {
            if (!busy) onBusyChanged(false)
        }
    }

    /** Співрозмовник тут: відповісти на почуте ([wav] — звук із мовленням) або підхопити розмову. */
    private suspend fun pauseFollowUp(silentSec: Int, wav: ByteArray?) {
        Log.i(TAG, if (wav != null) "пауза: чути мовлення — передаю звук моделі" else "пауза: співрозмовник тут — продовжую розмову")
        face.setMood(Mood.THINKING)
        lastInteraction = SystemClock.uptimeMillis()
        val reply = ask(Brain.pauseFollowUp(silentSec, wav != null), audio = wav)
        if (reply.say.isBlank() && reply.action.type == "none") {
            Log.i(TAG, "пауза: модель вирішила не втручатися — мовчу")
            if (reply.remember.isNotBlank()) rememberFact(reply.remember)
            awaitingCommandUntil = 0
            inConversation = false
            face.setMood(Mood.NEUTRAL)
            return
        }
        runAgent(reply)
    }

    /** Після розмови — повернутись до спокою, а згодом "задрімати". */
    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            val listening = (awaitingCommandUntil - SystemClock.uptimeMillis()).coerceAtLeast(0)
            delay(if (face.mood == Mood.LISTENING) maxOf(listening, 1_000) else 5_000)
            inConversation = false
            face.setMood(if (night?.active == true) Mood.SLEEPY else Mood.NEUTRAL)
            delay(SLEEP_AFTER_MS)
            face.setMood(Mood.SLEEPY)
        }
    }
        /** "Що змінилось у кімнаті?" — за стільки часу. */
        const val THING_CHANGES_MS = 24 * 3600_000L
        /** NewPipe: скільки чекати результатів пошуку, відкриття відео і скільки відео пробувати. */
        const val NEWPIPE_RESULTS_MS = 15_000L
        const val NEWPIPE_OPEN_MS = 10_000L
        const val NEWPIPE_TRIES = 3

    private companion object {
        const val TAG = "Assistant"
        const val FOLLOW_UP_WINDOW_MS = 8_000L
        /** Скільки після відповіді чекати наступної репліки без "Спіді". */
        const val CONVERSATION_WINDOW_MS = 45_000L
        /** Хтось сидить перед Спіді — фраза протягом цього часу після розмови теж до неї. */
        const val NEAR_CONTINUATION_MS = 120_000L
        /** Дитина й розпізнавач повільні — 9 с не вистачало, відповідь приходила вже після. */
        const val LESSON_LISTEN_MS = 14_000L
        const val SLEEP_AFTER_MS = 5 * 60_000L
        const val MAX_LOOKS = 3
        const val SETTLE_MS = 300L
        /** Знімок задньою камерою для паузи в розмові — не довше (інакше перевірка розтягується). */
        const val PERSON_PHOTO_MS = 6_000L
        const val LAUNCH_CHECK_DELAY_MS = 5_000L
        /** Просте "продовжуй" — про книгу, якщо читали протягом цього часу. */
        const val BARE_CONTINUE_MS = 30 * 60_000L
        const val MAX_LISTED = 6
        /** "Я Ліна" діє стільки після останньої такої репліки. */
        const val CLAIM_MS = 3 * 60_000L
        /** Незнайоме обличчя — питати ім'я лише якщо за стільки не впізнала. */
        const val STRANGER_WAIT_MS = 8_000L
        /** Як часто під час привітання питати день народження, якщо його не знаємо. */
        const val ASK_BIRTHDAY_CHANCE = 0.25
        val ALARM_COMMANDS = setOf(LocalCommand.STOP_ALARM, LocalCommand.SNOOZE_ALARM, LocalCommand.STOP)
        /** Знайомих бачила недавно — незнайоме обличчя, мабуть, той самий під іншим кутом. */
        const val RECENT_KNOWN_MS = 5 * 60_000L
        /** "Привіт" знову — лише якщо не було контакту довше. */
        const val GREET_AGAIN_MS = 3 * 3600_000L
        private val FORGET_THAT = Regex("^(?:спіді[,\\s]+)?(?:забудь|не\\s+пам'ятай|видали\\s+з\\s+пам'яті)[,\\s]+(?:що|про\\s+те\\s+що|про\\s+те,\\s+що|про)\\s+(.{3,})$")
        /** Розмова була щойно — знайоме обличчя знову в кадрі не вітаємо. */
        const val TALKED_RECENTLY_MS = 5 * 60_000L
        /** Сторож вмикається не одразу — встигнути вийти з кімнати. */
        const val GUARD_DELAY_MS = 30_000L
        /** Пропозиція уроку: без розмови стільки, ймовірність на хвилину, не частіше ніж, скільки чекати відповіді. */
        const val OFFER_IDLE_MS = 3 * 60_000L
        const val OFFER_CHANCE = 0.2
        const val OFFER_GAP_MS = 2 * 3600_000L
        const val OFFER_REPLY_MS = 25_000L
        /** Чи стоїть на колісній платформі — відповідь не довше (немає платформи — мовчить). */
        const val PLATFORM_CHECK_MS = 2_000L
        /** Місце для новин — не довше (GPS може думати довго; тоді — новини України). */
        const val NEWS_LOCATE_MS = 6_000L
        /** Обличчя біля камери не старше — щоб вирішити, хто відповів на уроці. */
        const val ANSWER_FACE_MS = 4_000L
        /** Цікавий факт: лише коли розмови не було стільки, з такою ймовірністю на хвилину. */
        const val FACT_IDLE_MS = 5 * 60_000L
        const val FACT_CHANCE = 0.15
        /** Частка фактів з нової галузі (коли інтереси вже відомі). */
        const val FACT_EXPLORE = 0.3
        const val FACT_REACTION_MS = 25_000L
        /** Скільки слухаємо голос, коли сторож побачив знайомого. */
        const val GUARD_LISTEN_MS = 60_000L
        /** Відбиток голосу діє для фрази й відповіді на неї. */
        const val VOICE_FRESH_MS = 30_000L
        private val CLAIM = Regex("(?:^|[\\s,])я\\s+(\\p{L}[\\p{L}'’-]+)")
        /** Нічний режим — до цієї години, якщо не сказали інакше. */
        const val DEFAULT_WAKE_HOUR = 7
        const val BOOK_FORMATS = "EPUB, FB2, PDF чи TXT"
    }
}
