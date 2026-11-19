package com.speedy.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * "Руки" Спіді в інших застосунках (службу спеціальних можливостей вмикає людина).
 * Лише за прямою голосовою командою: натиснути, прокрутити, назад/додому, ввести текст,
 * прочитати екран. У чутливих застосунках ([Safety]) — нічого не робить.
 */
class SpeedyHands : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "керування застосунками увімкнено")
        registerReceiver(screenReceiver, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onDestroy() {
        hideMini()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Спіді змахнули з недавніх — міні-копія не лишається висіти сама. */
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        Log.i(TAG, "Спіді закрили — ховаю міні-копію")
        hideMini()
        super.onTaskRemoved(rootIntent)
    }

    /** Вікна служби видно й поверх екрана блокування — там міні-Спіді не показуємо. */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            overlay?.visibility = if (intent.action == android.content.Intent.ACTION_SCREEN_OFF) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun locked() = (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.takeIf { event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
            ?.let { foreground = it }
    }

    override fun onInterrupt() {}

    /** Пакет застосунку на екрані. */
    @Volatile var foreground: String = ""
        private set

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Чутливий застосунок на екрані — назва, інакше null. */
    fun blockedApp(): String? {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: foreground
        return label(pkg).takeIf { Safety.blocked(pkg, it) }
    }

    fun currentApp(): String = label(rootInActiveWindow?.packageName?.toString() ?: foreground)

    /** Видимий текст екрана (для "що на екрані?"). */
    fun screenText(max: Int = 400): String {
        val root = rootInActiveWindow ?: return ""
        val out = mutableListOf<String>()
        walk(root) { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && t !in out) out += t
        }
        return out.joinToString(". ").take(max)
    }

    /** Натиснути елемент із таким текстом (або підписом); false — не знайдено. */
    fun click(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = text.lowercase()
        var target: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (target != null) return@walk
            val t = (n.text ?: n.contentDescription)?.toString()?.lowercase() ?: return@walk
            if (t == wanted || t.contains(wanted)) target = n
        }
        var node = target ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        Log.i(TAG, "натискаю «$text» у ${currentApp()}")
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Будильник / таймер дзвонить у спливному вікні годинника (Samsung не дає ні кнопки в сповіщенні,
     * ні реакції на DISMISS_ALARM) — натискаємо там "Відхилити" ("Відкласти").
     */
    fun dismissClockPopup(snooze: Boolean): Boolean {
        val wanted = if (snooze) Regex("(?i)відклад|повтор|snooze") else Regex("(?i)відхил|зупин|вимк|стоп|закри|dismiss|stop")
        for (w in windows) {
            val root = w.root ?: continue
            if (!root.packageName.toString().contains("clock", ignoreCase = true)) continue
            var target: AccessibilityNodeInfo? = null
            val seen = mutableListOf<String>()
            walk(root) { n ->
                val t = (n.text ?: n.contentDescription)?.toString() ?: return@walk
                seen += t
                if (target == null && wanted.containsMatchIn(t)) target = n
            }
            Log.i(TAG, "вікно годинника: $seen")
            var node = target ?: continue
            while (!node.isClickable) node = node.parent ?: break
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = null
        walk(root) { n -> if (target == null && n.isScrollable) target = n }
        Log.i(TAG, "прокручую ${if (forward) "вниз" else "вгору"} у ${currentApp()}")
        return target?.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ) ?: false
    }

    /** Ввести текст у поле, де курсор (або перше поле вводу). */
    /** Видимі елементи з ідентифікатором [id] в активному вікні застосунку [pkg]. */
    private fun byId(pkg: String, id: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg } ?: return emptyList()
        return root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").filter { it.isVisibleToUser }
    }

    /** NewPipe (чи форк) показує результати саме цього пошуку (а не головну сторінку). */
    fun newPipeResults(pkg: String, query: String): Boolean =
        byId(pkg, "toolbar_search_edit_text").any { it.text?.toString()?.trim().equals(query.trim(), ignoreCase = true) } &&
            byId(pkg, "itemRoot").isNotEmpty()

    /**
     * NewPipe: натиснути [index]-те відео в результатах (без прямих трансляцій — у них немає тривалості
     * на кшталт "11:19"). Повертає назву відео, null — такого немає.
     */
    fun pickNewPipeResult(pkg: String, index: Int): String? {
        val videos = byId(pkg, "itemRoot").filter { item ->
            item.findAccessibilityNodeInfosByViewId("$pkg:id/itemDurationView")
                .any { d -> d.text?.toString()?.contains(':') == true }
        }
        val item = videos.getOrNull(index) ?: return null
        val title = item.findAccessibilityNodeInfosByViewId("$pkg:id/itemVideoTitleView").firstOrNull()?.text?.toString().orEmpty()
        Log.i(TAG, "NewPipe: натискаю результат ${index + 1}: «$title»")
        return title.takeIf { item.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    /** NewPipe не зміг відкрити відео ("Вміст недоступний" — часто дитячі відео YouTube). */
    fun newPipeError(pkg: String): Boolean = byId(pkg, "error_panel").isNotEmpty()

    /** На панелі помилки NewPipe — "Відкрити в браузері" (мобільний YouTube такі відео показує). */
    fun newPipeOpenInBrowser(pkg: String): Boolean =
        byId(pkg, "error_open_in_browser").firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    fun type(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        if (field == null) walk(root) { n -> if (field == null && n.isEditable) field = n }
        val f = field ?: return false
        Log.i(TAG, "вводжу текст у ${currentApp()}")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private var overlay: android.view.View? = null
    private var overlayParams: android.view.WindowManager.LayoutParams? = null

    /**
     * Міні-копія Спіді в кутку поверх будь-якого застосунку. Дотик — повернути Спіді,
     * перетягування — посунути. [create] отримує контекст служби (вікно мусить бути її).
     */
    fun showMini(create: (Context) -> android.view.View, onTap: () -> Unit) {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val density = resources.displayMetrics.density
        val params = android.view.WindowManager.LayoutParams(
            (140 * density).toInt(), (90 * density).toInt(),
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            x = (12 * density).toInt()
            y = (60 * density).toInt()
        }
        val view = create(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.BLACK)
                cornerRadius = 18 * density
                setStroke((1 * density).toInt(), 0xFF333333.toInt())
            }
            clipToOutline = true
            elevation = 8 * density
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12 * density) moved = true
                    if (moved) {
                        params.x = (startX - dx).toInt() // gravity END: x рахується від правого краю
                        params.y = (startY + dy).toInt()
                        wm.updateViewLayout(view, params)
                    }
                }
                android.view.MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        if (locked()) view.visibility = android.view.View.GONE // з'явиться після розблокування
        try {
            wm.addView(view, params)
            overlay = view
            overlayParams = params
        } catch (e: Exception) {
            Log.w(TAG, "міні-Спіді не показується: ${e.message}")
        }
    }

    fun hideMini() {
        val view = overlay ?: return
        overlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(view)
        } catch (_: Exception) {}
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun walk(node: AccessibilityNodeInfo, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 40) return
        visit(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1, visit) }
    }

    companion object {
        private const val TAG = "SpeedyHands"
        @Volatile var instance: SpeedyHands? = null
            private set

        fun enabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .contains(context.packageName + "/")
    }
}

/** Де Спіді нічого не натискає й не вводить: гроші, паролі, налаштування, встановлення програм. */
object Safety {
    private val packages = listOf(
        "com.android.settings", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.vending", "com.google.android.gms",
    )
    private val words = listOf(
        "bank", "банк", "privat", "приват", "mono", "моно", "pumb", "пумб", "oschad", "ощад", "raif", "райф",
        "ukrsib", "sense", "abank", "izibank", "pay", "wallet", "гаманець", "binance", "crypto", "крипто",
        "trust", "metamask", "exodus", "password", "пароль", "1password", "lastpass", "bitwarden", "keepass",
        "authenticator", "дія", "diia", "nova pay", "portmone", "easypay", "kyivstar pay",
    )

    fun blocked(pkg: String, label: String): Boolean {
        val p = pkg.lowercase()
        val l = label.lowercase()
        return pkg in packages || words.any { it in p || it in l }
    }
}
