import java.security.MessageDigest
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

// Ключ підпису: шлях до .properties (storeFile, storePassword, keyAlias, keyPassword) поза репозиторієм.
val signingProps = Properties().apply {
    val path = prop("signing.properties")
    val f = file(path)
    if (path.isNotEmpty() && f.exists()) f.inputStream().use { load(it) }
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.speedy.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.speedy.assistant"
        minSdk = 25 // Android 7.1
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.1"

        // Нативні бібліотеки TensorFlow Lite лише для ARM — менший APK.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

        buildConfigField("String", "CLAUDE_MODEL", quoted(prop("claude.model", "claude-sonnet-5-5")))
        buildConfigField("String", "CLAUDE_EFFORT", quoted(prop("claude.effort", "low")))
        buildConfigField("String", "GEMINI_MODEL", quoted(prop("gemini.model", "gemini-2.5-flash")))
        buildConfigField("float", "TTS_PITCH", prop("tts.pitch", "1.3") + "f")
        buildConfigField("float", "TTS_RATE", prop("tts.rate", "1.05") + "f")
        buildConfigField("String", "TTS_VOICE", quoted(prop("tts.voice")))
        buildConfigField("float", "SCREEN_BRIGHTNESS", prop("screen.brightness", "0.15") + "f")
        // API-ключі сюди НЕ додавати: APK публікується відкрито. Вони вводяться на телефоні.
        buildConfigField(
            "String", "UPDATE_MANIFEST_URL",
            quoted(prop("update.manifestUrl", (findProperty("speedy.updateManifestUrl") as String?).orEmpty())),
        )
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("speedy") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug і release підписані одним ключем, щоб оновлення з GitHub ставилися поверх версії з IDE.
        signingConfigs.findByName("speedy")?.let { speedy ->
            getByName("debug").signingConfig = speedy
            getByName("release").signingConfig = speedy
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        // android.util.Log у JVM-тестах повертає значення за замовчуванням замість винятку.
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        // Модель має читатися з APK напряму (memory-map), без стиснення.
        noCompress += "tflite"
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

    // Локальне розпізнавання присутності (EfficientDet-Lite0 у assets).
    // Лише Interpreter: нативна бібліотека Task Library не завантажується на Android 7.1.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation("junit:junit:4.13.2")
    // Справжній org.json для JVM-тестів (в android.jar лише заглушки).
    testImplementation("org.json:json:20240303")
}

/**
 * Готує реліз для самооновлення: app/build/outputs/update/{speedy-<версія>.apk, update.json}.
 * Обидва файли викласти поруч за адресою update.manifestUrl. Перед релізом збільш versionCode.
 *   gradlew updateManifest -Pnotes="що нового"
 */
tasks.register("updateManifest") {
    dependsOn("assembleRelease")
    val versionCode = android.defaultConfig.versionCode ?: 1
    val versionName = android.defaultConfig.versionName ?: versionCode.toString()
    val notes = (project.findProperty("notes") as String?).orEmpty()
    val apkIn = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val outDir = layout.buildDirectory.dir("outputs/update")
    val signed = signingProps.isNotEmpty()
    doLast {
        check(signed) { "Реліз не підписано: задай signing.properties у local.properties" }
        val dir = outDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val apkName = "speedy-$versionName.apk"
        val apk = apkIn.get().asFile.copyTo(dir.resolve(apkName), overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }
        fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
        dir.resolve("update.json").writeText(
            """{"versionCode": $versionCode, "versionName": "${esc(versionName)}", """ +
                """"apkUrl": "$apkName", "sha256": "$sha", "notes": "${esc(notes)}"}""" + "\n"
        )
        println("Оновлення готове: ${dir.absolutePath}")
    }
}
