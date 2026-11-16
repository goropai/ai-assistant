package com.speedy.assistant.settings

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** PIN батьків: задати при першому запуску, перевірити перед налаштуваннями. */
object PinDialog {

    private fun pinField(context: Context, hint: String) = EditText(context).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        gravity = Gravity.CENTER
        textSize = 24f
    }

    /** Перший запуск: PIN двічі; без нього далі не йдемо. */
    fun setup(context: Context, settings: AppSettings, onDone: () -> Unit) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val first = pinField(context, "PIN")
        val second = pinField(context, "ще раз")
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = "Придумайте 4-значний PIN для налаштувань Спіді (щоб дитина не змінювала їх)." })
            addView(first)
            addView(second)
        }
        val dialog = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("PIN батьків")
            .setView(form)
            .setCancelable(false)
            .setPositiveButton("Зберегти", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    !Pin.valid(a) -> Toast.makeText(context, "Потрібно 4 цифри", Toast.LENGTH_SHORT).show()
                    a != b -> Toast.makeText(context, "PIN не збігається", Toast.LENGTH_SHORT).show()
                    else -> {
                        settings.setPin(a)
                        dialog.dismiss()
                        onDone()
                    }
                }
            }
        }
        dialog.show()
    }

    /** Перевірка PIN перед захищеною дією; немає PIN — спершу задати. */
    fun require(context: Context, settings: AppSettings, onOk: () -> Unit) {
        if (!settings.hasPin) return setup(context, settings, onOk)
        val field = pinField(context, "PIN")
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val dialog = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Введіть PIN")
            .setView(LinearLayout(context).apply { setPadding(pad, pad / 2, pad, 0); addView(field) })
            .setPositiveButton("OK", null)
            .setNegativeButton("Скасувати", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (settings.checkPin(field.text.toString())) {
                    dialog.dismiss()
                    onOk()
                } else {
                    field.text.clear()
                    Toast.makeText(context, "Неправильний PIN", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }
}
