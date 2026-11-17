package com.speedy.assistant.media

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * VLC — зовнішній плеєр (Speedy не грає сама). Відео — у плеєрі VLC з поверненням позиції
 * після закриття (документовані інтенти VLC); аудіо VLC грає у фоні, а позицію й керування
 * беремо з його медіасесії — для цього потрібен доступ до сповіщень (як для "що нового?").
 */
class Vlc(
    private val context: Context,
    /** Запустити плеєр відео з результатом (позиція, тривалість) — ActivityResult у MainActivity. */
    private val launchForResult: (Intent) -> Unit,
    /** Служба доступу до сповіщень — через неї система дає медіасесії інших застосунків. */
    private val notesListener: ComponentName,
) {
    val installed: Boolean
        get() = context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    fun playVideo(file: File, title: String, positionMs: Long) {
        val uri = uri(file)
        fun base(intent: Intent) = intent.setDataAndType(uri, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("title", title)
            .putExtra("from_start", positionMs <= 0)
            .putExtra("position", positionMs)
        try {
            launchForResult(base(Intent(ACTION_RESULT).setComponent(ComponentName(PACKAGE, VIDEO_PLAYER))))
        } catch (e: ActivityNotFoundException) {
            // Інша версія VLC (плеєр перейменували) — звичайне відкриття, без позиції в результаті.
            launchForResult(base(Intent(Intent.ACTION_VIEW).setPackage(PACKAGE)))
        }
    }

    /** VLC вмикає аудіо у фоні (свій плеєр і сповіщення), Спіді лишається на екрані. */
    fun playAudio(file: File) {
        val uri = uri(file)
        val controller = session()
        if (controller != null) {
            // VLC уже працює — наступний розділ без перемикання екранів.
            context.grantUriPermission(PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            controller.transportControls.playFromUri(uri, null)
            return
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setPackage(PACKAGE).setDataAndType(uri, "audio/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Медіасесія VLC; null — VLC не грає або немає доступу до сповіщень. */
    fun session(): MediaController? = try {
        (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
            .getActiveSessions(notesListener).firstOrNull { it.packageName == PACKAGE }
    } catch (e: SecurityException) {
        null
    }

    /** Чи можемо бачити медіасесію (доступ до сповіщень увімкнено). */
    val canTrack: Boolean
        get() = try {
            (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(notesListener)
            true
        } catch (e: SecurityException) {
            false
        }

    /**
     * VLC немає — будь-який інший плеєр у телефоні (система спитає, яким, якщо їх кілька).
     * Без позиції й без "наступної серії": це вміє лише VLC. false — плеєрів немає взагалі.
     */
    fun playWithAny(file: File, kind: MediaKind): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri(file), if (kind == MediaKind.VIDEO) "video/*" else "audio/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun openStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.videolan.org/vlc/download-android.html")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)

    companion object {
        const val PACKAGE = "org.videolan.vlc"
        private const val VIDEO_PLAYER = "org.videolan.vlc.gui.video.VideoPlayerActivity"
        /** З цією дією VLC повертає позицію й тривалість, коли плеєр закрили. */
        private const val ACTION_RESULT = "org.videolan.vlc.player.result"
    }
}
