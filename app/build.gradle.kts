import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(name: String, default: String = ""): String =
    localProps.getProperty(name)?.trim().takeUnless { it.isNullOrEmpty() } ?: default

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.speedy.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.speedy.assistant"
        minSdk = 25 // Android 7.1
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "LLM_PROVIDER", quoted(prop("llm.provider", "claude")))
        buildConfigField("String", "ANTHROPIC_API_KEY", quoted(prop("anthropic.apiKey")))
        buildConfigField("String", "CLAUDE_MODEL", quoted(prop("claude.model", "claude-opus-5-5")))
        buildConfigField("String", "CLAUDE_EFFORT", quoted(prop("claude.effort", "low")))
        buildConfigField("String", "GEMINI_API_KEY", quoted(prop("gemini.apiKey")))
        buildConfigField("String", "GEMINI_MODEL", quoted(prop("gemini.model", "gemini-2.5-flash")))
        buildConfigField("float", "TTS_PITCH", prop("tts.pitch", "1.3") + "f")
        buildConfigField("float", "TTS_RATE", prop("tts.rate", "1.05") + "f")
        buildConfigField("String", "TTS_VOICE", quoted(prop("tts.voice")))
        buildConfigField("float", "SCREEN_BRIGHTNESS", prop("screen.brightness", "0.15") + "f")
        buildConfigField("String", "ROBOT_WIFI_URL", quoted(prop("robot.wifiUrl")))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
}
