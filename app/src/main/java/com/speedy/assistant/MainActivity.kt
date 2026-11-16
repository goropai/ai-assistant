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
        if (BuildConfig.SCREEN_BRIGHTNESS >= 0f) {
            window.attributes = window.attributes.apply { screenBrightness = BuildConfig.SCREEN_BRIGHTNESS }
        }

        face = FaceView(this)
        face.setOnClickListener { assistant?.manualWake() }
        face.setOnLongClickListener { openSettings(); true }
        setContentView(face)

        val needed = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
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
        val people = People(FaceBook(java.io.File(filesDir, "faces.json")))
        presence = PresenceWatcher(this, this, people) { events -> assistant?.onPresence(events) }

        assistant = Assistant(
            scope = lifecycleScope,
            face = face,
            voice = voice,
            brain = Brain(ConfiguredLlm(http, settings), presence = { presence?.summary.orEmpty() }),
            camera = CameraSnapshot(this, this),
            robot = robot,
            findRobot = { robot.rediscover() },
            updater = UpdateManager(this, http, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.VERSION_CODE),
            chirps = ChirpPlayer(),
            battery = { batteryPercent to charging },
            people = people,
            earsFactory = { onPhrase ->
                VoiceListener(this, onPhrase = onPhrase, onFatalError = { toast(it) }).also {
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
        connectivity.registerDefaultNetworkCallback(networkCallback)
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
            openSettings()
        }
    }

    private fun openSettings() {
        SettingsDialog.show(this, settings) {
            face.setMood(if (settings.hasActiveKey) Mood.HAPPY else Mood.SAD)
            updatePresence()
        }
    }

    /** Камера для присутності — лише коли Спіді вільна, екран активний і батарея не сідає. */
    private fun updatePresence() {
        val watcher = presence ?: return
        val cameraAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (settings.presenceEnabled && cameraAllowed && resumed && !assistantBusy && !lowPower) watcher.start()
        else watcher.stop()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        assistant?.start()
        resumed = true
        updatePresence()
    }

    override fun onPause() {
        resumed = false
        updatePresence()
        assistant?.stop()
        super.onPause()
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
                assistant?.chirp(Chirp.NO_NETWORK, Mood.SAD)
            }
        }

        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (!networkLost) return@runOnUiThread
                networkLost = false
                assistant?.chirp(Chirp.HAPPY, Mood.HAPPY)
            }
        }
    }

    override fun onDestroy() {
        if (assistant != null) {
            unregisterReceiver(batteryReceiver)
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
}
