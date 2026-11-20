package com.speedy.assistant.media

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Відео з інтернету: YouTube, якщо встановлений (на іншому телефоні він є), інакше NewPipe (чи форк),
 * інакше — посилання на пошук YouTube (інший клієнт, що його приймає, або браузер).
 */
class OnlineVideo(private val context: Context) {

    /** Чим відкрили: назва для відповіді і пакет NewPipe (чи форку), якщо там — тоді результат можна натиснути. */
    class Opened(val name: String, val newPipe: String? = null)

    /** Відкрити пошук; null — нічим відкрити. */
    fun search(query: String): Opened? {
        if (installed(YOUTUBE)) {
            val intent = Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE)
                .putExtra(SearchManager.QUERY, query).putExtra("query", query)
            if (start(intent)) return Opened("YouTube")
        }
        // NewPipe і його форки (Tubular) — той самий головний екран із пошуком.
        for (pkg in NEWPIPES.filter(::installed)) {
            val intent = Intent().setClassName(pkg, "org.schabi.newpipe.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("key_open_search", true)
                .putExtra("key_search_string", query)
                .putExtra("key_service_id", 0) // YouTube
            if (start(intent)) {
                // Холодний старт NewPipe запит ігнорує (бере його лише в onNewIntent) — ще раз, коли вже відкритий.
                Handler(Looper.getMainLooper()).postDelayed({ start(intent) }, NEWPIPE_RETRY_MS)
                return Opened("NewPipe", pkg)
            }
        }
        // Посилання на пошук YouTube: відкриє інший встановлений клієнт, що його приймає, або браузер.
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com/results?search_query=" + Uri.encode(query)))
        return if (start(web)) Opened("інтернеті") else null
    }

    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false // зокрема видалений для користувача (pm uninstall --user 0)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "не відкрилось: $intent"); false
    } catch (e: SecurityException) {
        Log.w(TAG, "не відкрилось: $intent"); false
    }

    companion object {
        private const val TAG = "OnlineVideo"
        const val YOUTUBE = "com.google.android.youtube"
        val NEWPIPES = listOf("org.schabi.newpipe", "org.polymorphicshade.tubular", "org.polymorphicshade.newpipe")
        private const val NEWPIPE_RETRY_MS = 2_500L
    }
}

/** Голосом про відео з інтернету: "увімкни друге відео" (у списку NewPipe), "закрий відео" / "закрий NewPipe". */
object OnlineVideoCommands {
    sealed interface Command {
        /** [index] — з нуля: "перше" → 0. */
        data class Pick(val index: Int) : Command
        data object Close : Command
    }

    private val ordinals = listOf("перш", "друг", "трет", "четверт", "п'ят")
    private val pick = Regex(
        "^(?:увімкни|ввімкни|включи|відкрий|постав|запусти|грай|давай)\\s+(перш\\p{L}*|друг\\p{L}*|трет\\p{L}*|четверт\\p{L}*|п'ят\\p{L}*)\\s+" +
            "(?:відео|відос\\p{L}*|ролик\\p{L}*|кліп\\p{L}*|піс\\p{L}*)(?:\\s.*)?$",
    )
    private val close = Regex(
        "^(?:закрий|вимкни|зупини|прибери|вийди\\s+з)\\s+(?:це\\s+)?(?:відео|ютуб\\p{L}*|youtube|new\\s*pipe|нью\\s*пайп\\p{L}*|пайп\\p{L}*|tubular|плеєр\\p{L}*)$",
    )

    fun parse(phrase: String): Command? {
        val t = phrase.lowercase().replace('ʼ', '\'').replace('’', '\'').replace(Regex("[,!?.]"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^спіді\\s+"), "")
        if (close.matches(t)) return Command.Close
        val m = pick.find(t) ?: return null
        return Command.Pick(ordinals.indexOfFirst { m.groupValues[1].startsWith(it) })
    }
}
