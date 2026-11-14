package com.speedy.assistant.settings

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.speedy.assistant.BuildConfig

/** Простий діалог налаштувань (відкривається довгим натисканням на обличчя). */
object SettingsDialog {

    fun show(context: Context, settings: AppSettings, onSaved: () -> Unit) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        fun label(text: String) = form.addView(TextView(context).apply {
            this.text = text
            setPadding(0, pad / 2, 0, 0)
        })

        fun field(value: String, hint: String, secret: Boolean = false) = EditText(context).apply {
            setText(value)
            this.hint = hint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (secret) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
            form.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        label("Мозок")
        val claude = RadioButton(context).apply { text = "Claude"; id = View.generateViewId() }
        val gemini = RadioButton(context).apply { text = "Gemini"; id = View.generateViewId() }
        val providers = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(claude)
            addView(gemini)
            check(if (settings.provider == "gemini") gemini.id else claude.id)
        }
        form.addView(providers)

        label("Ключ Anthropic (встав із буфера обміну)")
        val anthropicKey = field(settings.anthropicKey, "sk-ant-…", secret = true)
        label("Модель Claude")
        val claudeModel = field(settings.claudeModel, BuildConfig.CLAUDE_MODEL)
        label("Ключ Gemini")
        val geminiKey = field(settings.geminiKey, "AIza…", secret = true)
        label("Модель Gemini")
        val geminiModel = field(settings.geminiModel, BuildConfig.GEMINI_MODEL)
        label("Колісна платформа (порожньо = автопошук)")
        val robotUrl = field(settings.robotUrl, settings.robotFoundUrl.ifBlank { "автопошук" })

        AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Спіді ${BuildConfig.VERSION_NAME}")
            .setView(ScrollView(context).apply { addView(form) })
            .setPositiveButton("Зберегти") { _, _ ->
                settings.provider = if (providers.checkedRadioButtonId == gemini.id) "gemini" else "claude"
                settings.anthropicKey = anthropicKey.text.toString()
                settings.claudeModel = claudeModel.text.toString()
                settings.geminiKey = geminiKey.text.toString()
                settings.geminiModel = geminiModel.text.toString()
                settings.robotUrl = robotUrl.text.toString()
                onSaved()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }
}
