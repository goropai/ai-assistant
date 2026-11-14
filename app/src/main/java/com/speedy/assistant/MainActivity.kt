package com.speedy.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.speedy.assistant.brain.Brain
import com.speedy.assistant.brain.ConfiguredLlm
import com.speedy.assistant.face.FaceView
import com.speedy.assistant.face.Mood
import com.speedy.assistant.robot.NoRobot
import com.speedy.assistant.robot.RobotPlatform
import com.speedy.assistant.robot.WifiRobotPlatform
import com.speedy.assistant.settings.AppSettings
import com.speedy.assistant.settings.SettingsDialog
import com.speedy.assistant.update.UpdateManager
import com.speedy.assistant.vision.CameraSnapshot
import com.speedy.assistant.voice.Speaker
import com.speedy.assistant.voice.VoiceListener
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var face: FaceView
    private var speaker: Speaker? = null
    private var assistant: Assistant? = null
    private val settings by lazy { AppSettings(this) }
    private var robot: RobotPlatform = NoRobot

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
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
        robot = createRobot()
        val voice = Speaker(this, BuildConfig.TTS_PITCH, BuildConfig.TTS_RATE, BuildConfig.TTS_VOICE)
        speaker = voice

        assistant = Assistant(
            scope = lifecycleScope,
            face = face,
            voice = voice,
            brain = Brain(ConfiguredLlm(http, settings)),
            camera = CameraSnapshot(this, this),
            robot = { robot },
            updater = UpdateManager(this, http, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.VERSION_CODE),
            earsFactory = { onPhrase ->
                VoiceListener(this, onPhrase = onPhrase, onFatalError = { toast(it) }).also {
                    if (!it.isAvailable()) toast("Розпізнавання мовлення недоступне — потрібен застосунок Google")
                }
            },
        ).also {
            it.start()
            it.checkUpdatesOnLaunch()
        }
        if (!settings.hasActiveKey) {
            face.setMood(Mood.SAD)
            toast("Додай API-ключ у налаштуваннях")
            openSettings()
        }
    }

    private fun openSettings() {
        SettingsDialog.show(this, settings) {
            robot = createRobot()
            face.setMood(if (settings.hasActiveKey) Mood.HAPPY else Mood.SAD)
        }
    }

    private fun createRobot(): RobotPlatform =
        settings.robotUrl.takeIf { it.isNotBlank() }?.let { WifiRobotPlatform(http, it) } ?: NoRobot

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        assistant?.start()
    }

    override fun onPause() {
        assistant?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        assistant?.release()
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
