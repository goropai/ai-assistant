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

    /** Відкрити пошук; повертає назву застосунку для відповіді або null — нічим відкрити. */
    fun search(query: String): String? {
        if (installed(YOUTUBE)) {
            val intent = Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE)
                .putExtra(SearchManager.QUERY, query).putExtra("query", query)
            if (start(intent)) return "YouTube"
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
                return "NewPipe"
            }
        }
        // Посилання на пошук YouTube: відкриє інший встановлений клієнт, що його приймає, або браузер.
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com/results?search_query=" + Uri.encode(query)))
        return if (start(web)) "інтернеті" else null
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
        private val NEWPIPES = listOf("org.schabi.newpipe", "org.polymorphicshade.tubular", "org.polymorphicshade.newpipe")
        private const val NEWPIPE_RETRY_MS = 2_500L
    }
}
