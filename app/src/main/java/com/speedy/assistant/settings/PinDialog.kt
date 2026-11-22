package com.speedy.assistant.settings

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * PIN батьків: задати при першому запуску, перевірити перед налаштуваннями, змінити. Поки віконце
 * відкрите, PIN можна сказати вголос ("два нуль чотири сім") — Спіді впише його сама.
 */
object PinDialog {

    /** Поки відкрите віконце PIN — фраза з 4 цифрами йде сюди (а не в команди й не в журнал). */
    @Volatile private var voiceInput: ((String) -> Unit)? = null

    val awaitingVoice: Boolean get() = voiceInput != null

    /** Почута фраза: PIN — вписати; true — фразу забрали (це PIN, не команда). */
    fun hear(candidates: List<String>): Boolean {
        val input = voiceInput ?: return false
        val pin = candidates.firstNotNullOfOrNull { SpokenPin.parse(it) } ?: return false
        input(pin)
        return true
    }

    /**
     * Голосовий вхід для цього віконця; закриваючись, воно прибирає лише свій вхід (наступне
     * віконце — "новий PIN" — могло вже показатися раніше, ніж прийшло закриття попереднього).
     */
    private fun listen(dialog: AlertDialog, input: (String) -> Unit) {
        voiceInput = input
        dialog.setOnDismissListener { if (voiceInput === input) voiceInput = null }
    }

    private fun pinField(context: Context, hint: String) = EditText(context).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        gravity = Gravity.CENTER
        textSize = 24f
    }

    /** Фокус у поле й одразу цифрова клавіатура (сама AlertDialog її не відкриває). */
    private fun keyboard(dialog: AlertDialog, field: EditText) {
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.requestFocus()
        // Після того, як вікно отримало фокус, — інакше J5 клавіатуру ігнорує.
        field.postDelayed({
            val imm = field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun voiceHint(context: Context) = TextView(context).apply {
        text = "…або скажіть його вголос, по цифрі"
        textSize = 13f
        alpha = 0.7f
        gravity = Gravity.CENTER
    }

    /** PIN двічі (перший запуск — без нього далі не йдемо; зміна — [cancelable]). */
    fun setup(
        context: Context, settings: AppSettings,
        title: String = "PIN батьків",
        message: String = "Придумайте 4-значний PIN для налаштувань Спіді (щоб дитина не змінювала їх).",
        cancelable: Boolean = false,
        onDone: () -> Unit,
    ) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val first = pinField(context, "PIN")
        val second = pinField(context, "ще раз")
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = message })
            addView(first)
            addView(second)
            addView(voiceHint(context))
        }
        val builder = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(title)
            .setView(form)
            .setCancelable(cancelable)
            .setPositiveButton("Зберегти", null)
        if (cancelable) builder.setNegativeButton("Скасувати", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    !Pin.valid(a) -> Toast.makeText(context, "Потрібно 4 цифри", Toast.LENGTH_SHORT).show()
                    a != b -> {
                        second.text.clear()
                        Toast.makeText(context, "PIN не збігається", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        settings.setPin(a)
                        dialog.dismiss()
                        Toast.makeText(context, "PIN збережено", Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
            }
            keyboard(dialog, first)
            // Голосом: перший раз — у перше поле, другий — підтвердження й зберегти.
            listen(dialog) { pin ->
                if (!Pin.valid(first.text.toString())) first.setText(pin)
                else { second.setText(pin); save.performClick() }
            }
        }
        dialog.show()
    }

    /**
     * Перевірка PIN перед захищеною дією; немає PIN — спершу задати. [timeoutMs] > 0 — віконце саме
     * закривається (у заголовку — відлік), тоді [onTimeout]; [message] — що саме підтверджують.
     */
    fun require(
        context: Context, settings: AppSettings,
        title: String = "Введіть PIN", message: String? = null, timeoutMs: Long = 0, onTimeout: () -> Unit = {},
        onOk: () -> Unit,
    ) {
        if (!settings.hasPin) return setup(context, settings, onDone = onOk)
        val field = pinField(context, "PIN")
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val dialog = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(title)
            .setView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                message?.let { addView(TextView(context).apply { text = it }) }
                addView(field)
                addView(voiceHint(context))
            })
            .setPositiveButton("OK", null)
            .setNegativeButton("Скасувати", null)
            .create()
        if (timeoutMs > 0) {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val until = android.os.SystemClock.uptimeMillis() + timeoutMs
            val tick = object : Runnable {
                override fun run() {
                    // Закрили (правильний PIN чи "Скасувати") — відлік зупиняється сам.
                    if (!dialog.isShowing) return
                    val left = (until - android.os.SystemClock.uptimeMillis() + 999) / 1000
                    if (left <= 0) {
                        dialog.dismiss()
                        onTimeout()
                        return
                    }
                    dialog.setTitle("$title ($left с)")
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(tick) // після show() — віконце вже відкрите
        }
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                if (settings.checkPin(field.text.toString())) {
                    dialog.dismiss()
                    onOk()
                } else {
                    field.text.clear()
                    Toast.makeText(context, "Неправильний PIN", Toast.LENGTH_SHORT).show()
                }
            }
            keyboard(dialog, field)
            listen(dialog) { pin -> field.setText(pin); ok.performClick() }
        }
        dialog.show()
    }

    /** Змінити PIN: спершу поточний, потім новий двічі. */
    fun change(context: Context, settings: AppSettings) = require(context, settings) {
        setup(context, settings, title = "Новий PIN", message = "Новий 4-значний PIN батьків.", cancelable = true) {}
    }
}
