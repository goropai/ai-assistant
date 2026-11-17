package com.speedy.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.speedy.assistant.brain.Brain
import com.speedy.assistant.brain.ConfiguredLlm
import com.speedy.assistant.face.FaceView
import com.speedy.assistant.face.Mood
import com.speedy.assistant.robot.AutoRobotPlatform
import com.speedy.assistant.robot.RobotDiscovery
import com.speedy.assistant.settings.AppSettings
import com.speedy.assistant.settings.SettingsDialog
import com.speedy.assistant.update.UpdateManager
import com.speedy.assistant.vision.CameraSnapshot
import com.speedy.assistant.vision.FaceBook
import com.speedy.assistant.vision.People
import com.speedy.assistant.vision.PresenceWatcher
import com.speedy.assistant.voice.Chirp
import com.speedy.assistant.voice.ChirpPlayer
import com.speedy.assistant.voice.Speaker
import com.speedy.assistant.voice.VoiceListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var face: FaceView
    private var speaker: Speaker? = null
    private var assistant: Assistant? = null
    private var presence: PresenceWatcher? = null
    private var assistantBusy = false
    private var lowPower = false
    private var resumed = false
    private var batteryPercent = -1
    private var charging = false
    private var networkLost = false
    private val settings by lazy { AppSettings(this) }
    private val robot by lazy {
        // Короткі таймаути: якщо плата зникла, рух не має "висіти" 15 секунд.
        val robotHttp = http.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        AutoRobotPlatform(
            http = robotHttp,
            discovery = RobotDiscovery(),
            manualUrl = { settings.robotUrl },
            cachedUrl = { settings.robotFoundUrl },
            saveFoundUrl = { settings.robotFoundUrl = it },
        )
    }

    private val llm by lazy { ConfiguredLlm(http, settings) }
    private val offlineEar by lazy { com.speedy.assistant.voice.OfflineEar(this) }
    private val englishCourse by lazy {
        com.speedy.assistant.english.EnglishCourse(
            context = this,
            generateJson = { system, prompt -> llm.generateJson(system, prompt) },
        )
    }
    private val reminders by lazy { com.speedy.assistant.initiative.Reminders(java.io.File(filesDir, "reminders.json")) }
    private val memory by lazy { com.speedy.assistant.brain.Memory(java.io.File(filesDir, "memory.json")) }
    private val wikipedia by lazy { com.speedy.assistant.brain.Wikipedia(http) }

    private var warmedAt = 0L

    /**
     * Хтось заговорив: поки людина договорює й працює розпізнавання, відкриваємо з'єднання
     * з "мозком" (TLS на слабкому телефоні — помітна частина секунди). З'єднання живе ~5 хв.
     */
    private fun warmBrain() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - warmedAt < 60_000) return
        warmedAt = now
        checkBrain()
    }

    /** Індикатор зв'язку з "мозком": перевірка без токенів (список моделей). */
    private fun checkBrain() {
        lifecycleScope.launch { face.setBrainStatus(llm.status()) }
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // довше мовчати голосовій помічниці не можна
            .build()
    }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) initAssistant()
            else {
                face.setMood(Mood.SAD)
                toast("Без мікрофона Спіді не чує команд")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Спіді — "обличчя" робота: показується поверх екрана блокування і вмикає екран,
        // інакше після випадкового блокування (кнопка, чуже вікно поверх) вона на паузі й не чує.
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        if (BuildConfig.SCREEN_BRIGHTNESS >= 0f) {
            window.attributes = window.attributes.apply { screenBrightness = BuildConfig.SCREEN_BRIGHTNESS }
        }

        face = FaceView(this)
        face.setOnClickListener { assistant?.manualWake() }
        face.setOnLongClickListener { openSettings(); true }
        setContentView(face)

        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
            // Не обов'язковий: без нього погода лише за назвою міста.
            Manifest.permission.ACCESS_FINE_LOCATION,
            // Теж не обов'язкові: контакти й календар — лише для питань про них.
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR,
        ) + (
            // Книги в пам'яті телефона; на Android 11+ — окремий "доступ до всіх файлів" (на прохання).
            if (android.os.Build.VERSION.SDK_INT < 30) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) else emptyArray()
        )
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            initAssistant()
        } else {
            permissionRequest.launch(needed)
        }
    }

    private fun initAssistant() {
        if (assistant != null) return
        val voice = Speaker(this, BuildConfig.TTS_PITCH, BuildConfig.TTS_RATE, BuildConfig.TTS_VOICE)
        speaker = voice
        val profiles = com.speedy.assistant.people.Profiles(java.io.File(filesDir, "profiles.json"))
        val people = People(FaceBook(java.io.File(filesDir, "faces.json")), ageOf = { profiles.age(it).name })
        profilesRef = profiles
        peopleRef = people
        val pets = com.speedy.assistant.vision.Pets(java.io.File(filesDir, "pets.json"))
        // PDFBox бере шрифти й таблиці гліфів із ресурсів застосунку (для тексту PDF-книг).
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        presence = PresenceWatcher(this, this, people, pets, profiles) { events -> assistant?.onPresence(events) }

        assistant = Assistant(
            scope = lifecycleScope,
            face = face,
            voice = voice,
            brain = Brain(
                llm,
                presence = { presence?.summary.orEmpty() },
                memory = { memory.all() },
                scene = { presence?.scene.orEmpty() },
                about = {
                    // Довідка — про того, хто говорить (найближче обличчя), а не про будь-кого в кадрі.
                    people.speaker(android.os.SystemClock.uptimeMillis())
                        ?.let { profiles.summary(it, System.currentTimeMillis()) }.orEmpty()
                },
                child = { assistant?.childSpeaking() == true },
            ),
            camera = CameraSnapshot(this, this),
            robot = robot,
            findRobot = { robot.rediscover() },
            updater = UpdateManager(this, http, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.VERSION_CODE),
            chirps = ChirpPlayer(),
            battery = { batteryPercent to charging },
            people = people,
            pets = pets,
            webSearch = { query ->
                llm.search(query) ?: wikipedia.summary(query)?.let { "(Довідка з Вікіпедії — свіжих новин, цін і курсів тут немає.) $it" }
            },
            memory = memory,
            phone = com.speedy.assistant.phone.PhoneActions(this),
            currency = com.speedy.assistant.weather.Currency(http),
            profiles = profiles,
            reminders = reminders,
            english = englishCourse,
            inForeground = { resumed },
            bringToFront = {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            openAccessSettings = { which ->
                // Системні доступи — лише за PIN батьків.
                com.speedy.assistant.settings.PinDialog.require(this, settings) {
                    if (which == "files") return@require openFileAccess()
                    val action = if (which == "notes") "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
                    else android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                    try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("Не вдалося відкрити налаштування") }
                }
            },
            childLimit = childLimit,
            childLimitMinutes = { settings.childLimitMinutes },
            isOnline = { !networkLost && connectivity.activeNetwork != null },
            weather = com.speedy.assistant.weather.WeatherService(http),
            locator = com.speedy.assistant.location.Locator(this),
            books = com.speedy.assistant.books.Bookshelf(
                storage, java.io.File(filesDir, "books.json"),
                scans = com.speedy.assistant.books.Ocr(this, http),
            ),
            media = mediaShelf,
            playback = playback,
            vlc = vlc,
            translator = com.speedy.assistant.translate.MlKitTranslator(),
            hasFileAccess = ::hasFileAccess,
            earsFactory = { onPhrase ->
                VoiceListener(this, onPhrase = onPhrase, onFatalError = { toast(it) }, onVoice = ::warmBrain, offline = offlineEar,
                    useOffline = { networkLost || connectivity.activeNetwork == null }).also {
                    if (!it.isAvailable()) toast("Розпізнавання мовлення недоступне — потрібен застосунок Google")
                }
            },
            onBusyChanged = { busy ->
                assistantBusy = busy
                updatePresence()
            },
        ).also {
            it.start()
            it.checkUpdatesOnLaunch()
        }
        // Тихо оновити адресу платформи: роутер міг видати їй нову IP.
        lifecycleScope.launch { robot.rediscover() }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Лише debug: adb shell am broadcast -a com.speedy.assistant.debug.HEAR --es text "почитай книгу"
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(this, debugReceiver, IntentFilter(DEBUG_HEAR), ContextCompat.RECEIVER_EXPORTED)
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        // Спіді запустилась — усе, що вже на шторці, прочитане; читаємо лише ті, що прийдуть.
        com.speedy.assistant.phone.NotificationStore.notes.markAllRead()
        com.speedy.assistant.phone.NotificationStore.onNew = { runOnUiThread { assistant?.announceNotes() } }
        // Офлайн-слух: модель 78 МБ — завантажуємо лише через Wi-Fi (не мобільний трафік), один раз.
        lifecycleScope.launch {
            // Не вийшло (повільний сайт, немає Wi-Fi) — пробуємо знову кожні 10 хв; до того слухає Google.
            while (isActive && !offlineEar.ready) {
                if (offlineEar.downloaded || !connectivity.isActiveNetworkMetered) {
                    try {
                        offlineEar.prepare(http)
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "офлайн-слух недоступний: ${e.message}")
                    }
                }
                if (!offlineEar.ready) delay(10 * 60_000)
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                face.setBrainStatus(llm.status())
                delay(10 * 60_000)
            }
        }
        // Нагадування: пропущені, поки застосунок був вимкнений (до 6 год), — одразу; далі — щохвилини 20 с.
        lifecycleScope.launch {
            reminders.takeDue(System.currentTimeMillis()).forEach { r ->
                if (System.currentTimeMillis() - r.at < 6 * 3600_000L) assistant?.remind(r, late = true)
            }
            while (isActive) {
                delay(20_000)
                reminders.takeDue(System.currentTimeMillis()).forEach { assistant?.remind(it) }
                assistant?.announceNotes() // відкладені (Спіді була зайнята / вночі)
            }
        }
        // VLC: місце в аудіокнизі, наступний файл, слух вимкнено, поки грає.
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                assistant?.mediaTick()
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000)
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val near = presence?.summary.orEmpty().isNotBlank()
                assistant?.checkDesires(hour, near, batteryPercent, charging)
            }
        }
        updatePresence()
        if (!settings.hasActiveKey) {
            face.setMood(Mood.SAD)
            toast("Додай API-ключ у налаштуваннях")
            openSettings() // без PIN — спершу задати його
        } else if (!settings.hasPin) {
            // Перший запуск (або оновлення до версії з PIN): батьки задають PIN.
            com.speedy.assistant.settings.PinDialog.setup(this, settings) {}
        }
    }

    private val childLimit by lazy {
        com.speedy.assistant.settings.ChildLimit(load = { settings.childUsage }, save = { settings.childUsage = it })
    }

    /** Налаштування — лише за PIN батьків. */
    private fun openSettings() {
        com.speedy.assistant.settings.PinDialog.require(this, settings) {
            val profiles = profilesRef
            val people = peopleRef
            val family = if (profiles != null && people != null) SettingsDialog.Family(
                names = people.names(),
                isChild = { profiles.age(it) == com.speedy.assistant.people.Profiles.Age.CHILD },
                setChild = { name, child ->
                    val now = profiles.age(name)
                    val wanted = if (child) com.speedy.assistant.people.Profiles.Age.CHILD else com.speedy.assistant.people.Profiles.Age.ADULT
                    if (now != wanted) profiles.setAge(name, wanted)
                },
                usedToday = { childLimit.usedMinutes(System.currentTimeMillis()) },
                resetLimit = { childLimit.reset() },
            ) else null
            SettingsDialog.show(this, settings, family) {
                face.setMood(if (settings.hasActiveKey) Mood.HAPPY else Mood.SAD)
                checkBrain()
                updatePresence()
            }
        }
    }

    /** Один обхід сховища для книг і медіа (коли попросили; результат живе кілька хвилин). */
    private val storage by lazy {
        com.speedy.assistant.books.StorageScan(::storageRoots) {
            com.speedy.assistant.books.Bookshelf.isBookFile(it) || com.speedy.assistant.media.MediaFiles.isMedia(it)
        }
    }
    private val mediaShelf by lazy { com.speedy.assistant.media.MediaShelf(storage, java.io.File(filesDir, "media.json")) }
    private val vlc: com.speedy.assistant.media.Vlc by lazy {
        com.speedy.assistant.media.Vlc(this, launchForResult = { videoPlayer.launch(it) },
            notesListener = android.content.ComponentName(this, com.speedy.assistant.phone.SpeedyNotifications::class.java))
    }
    private val playback: com.speedy.assistant.media.MediaPlayback by lazy {
        com.speedy.assistant.media.MediaPlayback(vlc, mediaShelf, onEvent = { assistant?.onMediaEvent(it) })
    }

    /** Плеєр відео VLC закрився: позиція й тривалість — щоб продовжити з того місця. */
    private val videoPlayer: androidx.activity.result.ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        playback.onVideoResult(
            ok = r.resultCode == RESULT_OK,
            positionMs = r.data?.getLongExtra("extra_position", -1L) ?: -1L,
            durationMs = r.data?.getLongExtra("extra_duration", -1L) ?: -1L,
        )
    }

    /** Внутрішня пам'ять і картки пам'яті (корінь — до "/Android/" у теці застосунку на кожному носії). */
    private fun storageRoots(): List<java.io.File> {
        @Suppress("DEPRECATION")
        val roots = mutableListOf(android.os.Environment.getExternalStorageDirectory())
        getExternalFilesDirs(null).filterNotNull().mapTo(roots) { java.io.File(it.path.substringBefore("/Android/")) }
        return roots.distinctBy { it.path }
    }

    private fun hasFileAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private val filesPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("Без доступу до файлів Спіді не бачить книг")
    }

    private fun openFileAccess() {
        if (android.os.Build.VERSION.SDK_INT < 30) return filesPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e: Exception) { toast("Не вдалося відкрити налаштування") }
        }
    }

    private var profilesRef: com.speedy.assistant.people.Profiles? = null
    private var peopleRef: People? = null

    /** Камера для присутності — лише коли Спіді вільна, екран активний і батарея не сідає. */
    private fun updatePresence() {
        val watcher = presence ?: return
        val cameraAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (settings.presenceEnabled && cameraAllowed && resumed && !assistantBusy && !lowPower) watcher.start()
        else watcher.stop()
    }

    /** Спіді не на екрані, але керує застосунками — її міні-копія в кутку. */
    private fun showMiniSpeedy() {
        val hands = com.speedy.assistant.phone.SpeedyHands.instance ?: return
        hands.showMini(
            create = { ctx -> FaceView(ctx).also { face.mirror = it } },
            onTap = {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
    }

    private fun hideMiniSpeedy() {
        face.mirror = null
        com.speedy.assistant.phone.SpeedyHands.instance?.hideMini()
    }

    override fun onResume() {
        super.onResume()
        hideMiniSpeedy()
        hideSystemUi()
        assistant?.start()
        resumed = true
        updatePresence()
    }

    override fun onPause() {
        resumed = false
        updatePresence()
        // Керування застосунками увімкнено — слухаємо й у фоні ("натисни…", "повернись").
        if (com.speedy.assistant.phone.SpeedyHands.instance == null) assistant?.stop() else showMiniSpeedy()
        super.onPause()
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { assistant?.debugHear(it) }
        }
    }

    private val batteryAlerts = BatteryAlerts()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val percent = level * 100 / scale
            this@MainActivity.batteryPercent = percent
            this@MainActivity.charging = charging
            batteryAlerts.update(percent, charging)?.let { assistant?.announceLowBattery(percent) }
            // Нижче 15% без зарядки — камеру для присутності вимикаємо.
            val low = percent < 15 && !charging
            if (low != lowPower) {
                lowPower = low
                updatePresence()
            }
        }
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    /** Пропала / повернулась мережа — сигнал звуком (колбеки приходять не в головному потоці). */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            runOnUiThread {
                if (connectivity.activeNetwork != null || networkLost) return@runOnUiThread
                networkLost = true
                face.setBrainStatus(com.speedy.assistant.brain.BrainStatus.DOWN)
                assistant?.chirp(Chirp.NO_NETWORK, Mood.SAD)
            }
        }

        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (!networkLost) return@runOnUiThread
                networkLost = false
                assistant?.chirp(Chirp.HAPPY, Mood.HAPPY)
                checkBrain()
            }
        }
    }

    override fun onDestroy() {
        if (assistant != null) {
            unregisterReceiver(batteryReceiver)
            if (BuildConfig.DEBUG) unregisterReceiver(debugReceiver)
            connectivity.unregisterNetworkCallback(networkCallback)
        }
        assistant?.release()
        presence?.release()
        speaker?.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION") // WindowInsetsController з'явився лише в API 30
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val DEBUG_HEAR = "com.speedy.assistant.debug.HEAR"
    }
}
