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
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

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
    fun type(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        if (field == null) walk(root) { n -> if (field == null && n.isEditable) field = n }
        val f = field ?: return false
        Log.i(TAG, "вводжу текст у ${currentApp()}")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
