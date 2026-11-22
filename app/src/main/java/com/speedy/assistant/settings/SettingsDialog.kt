package com.speedy.assistant.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.speedy.assistant.BuildConfig

/**
 * Налаштування (відкриваються довгим натисканням на обличчя, за PIN): на весь екран, список категорій,
 * кожна — окремим екраном зі стрілкою "назад". Зміни категорії зберігаються, коли з неї виходять.
 */
object SettingsDialog {

    /** Діти: імена знайомих і хто з них дитина (для ліміту й дитячого тону). */
    class Family(
        val names: List<String>,
        val isChild: (String) -> Boolean,
        val setChild: (String, Boolean) -> Unit,
        /** Стать (для правильного роду): сказана чи вгадана з імені. */
        val gender: (String) -> com.speedy.assistant.people.Gender,
        val setGender: (String, com.speedy.assistant.people.Gender) -> Unit,
        val usedToday: () -> Map<String, Int>,
        val resetLimit: () -> Unit,
    )

    /** Режим сторожа: пошук chat id Telegram-бота і пробна тривога (обидва — у фоні, відповідь — у [done]). */
    class Guard(
        /** [done]: знайдений chat id або (null і) що не так. */
        val findTelegramChat: (token: String, done: (id: String?, problem: String?) -> Unit) -> Unit,
        val testAlert: (done: (String) -> Unit) -> Unit,
    )

    /** Розумний дім: перевірити ключі Tuya (у фоні, відповідь — у [done]). */
    class Home(val check: (region: String, id: String, secret: String, done: (String) -> Unit) -> Unit)

    /** Голос: українські голоси синтезатора і пробне прослуховування (без збереження). */
    class Voice(val names: List<String>, val preview: (preset: VoicePreset, name: String) -> Unit)

    /** [onSaved] — коли налаштування закрили (усе вже збережено). */
    fun show(
        context: Context, settings: AppSettings, family: Family? = null, guard: Guard? = null, home: Home? = null,
        voice: Voice? = null, onSaved: () -> Unit,
    ) = SettingsScreens(context, settings, family, guard, home, voice, onSaved).open()
}

/** Екрани налаштувань: стек (список категорій → категорія), "назад" — зберегти й повернутись. */
private class SettingsScreens(
    host: Context,
    private val settings: AppSettings,
    private val family: SettingsDialog.Family?,
    private val guard: SettingsDialog.Guard?,
    private val home: SettingsDialog.Home?,
    private val voice: SettingsDialog.Voice?,
    private val onSaved: () -> Unit,
) {
    /** Екран: [rebuild] — будувати заново щоразу (список категорій з підсумками). */
    private class Screen(val title: String, val build: () -> View, val save: () -> Unit = {}, val rebuild: Boolean = false) {
        var view: View? = null
    }

    /** Світла тема для всіх полів: головний екран Спіді темний — у ньому текст полів білий (на білому не видно). */
    private val context: Context = androidx.appcompat.view.ContextThemeWrapper(host, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
    private val dialog = Dialog(context, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
    private val body = FrameLayout(context)
    private val titleView = TextView(context)
    private val stack = ArrayDeque<Screen>()
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun open() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ACCENT)
            elevation = dp(4).toFloat()
            addView(TextView(context).apply {
                text = "←"
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setOnClickListener { back() }
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(titleView.apply {
                textSize = 20f
                setTextColor(Color.WHITE)
                setSingleLine()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        dialog.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) back()
            code == KeyEvent.KEYCODE_BACK
        }
        dialog.setOnDismissListener {
            // Закрили інакше (не стрілкою) — зберегти все відкрите.
            while (stack.isNotEmpty()) stack.removeLast().save()
            onSaved()
        }
        push(Screen("Налаштування", ::categories, rebuild = true))
        dialog.show()
    }

    private fun push(screen: Screen) {
        stack.addLast(screen)
        display(screen)
    }

    private fun back() {
        val top = stack.removeLastOrNull() ?: return
        top.save()
        if (stack.isEmpty()) dialog.dismiss() else display(stack.last())
    }

    private fun display(screen: Screen) {
        val view = screen.view?.takeUnless { screen.rebuild } ?: screen.build()
        screen.view = view
        titleView.text = screen.title
        body.removeAllViews()
        body.addView(view)
        // Клавіатура з попереднього екрана — сховати.
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(body.windowToken, 0)
    }

    // --- список категорій ---

    private fun categories(): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun row(title: String, summary: String, open: () -> Unit) {
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { open() }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply { text = title; textSize = 17f; setTextColor(Color.BLACK) })
                    addView(TextView(context).apply { text = summary; textSize = 13f; setTextColor(GRAY) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply { text = "›"; textSize = 24f; setTextColor(GRAY) })
            })
            list.addView(View(context).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        fun onOff(on: Boolean) = if (on) "увімкнено" else "вимкнено"

        row("Голос і розмова", VoicePreset.of(settings.voicePreset).ua) { category("Голос і розмова", ::voiceScreen) }
        row("Мозок (ШІ)", (if (settings.provider == "gemini") "Gemini" else "Claude") + if (settings.hasActiveKey) "" else " — немає ключа") {
            category("Мозок (ШІ)", ::brainScreen)
        }
        row("Діти й уроки", settings.childLimitMinutes.let { if (it > 0) "розмова до $it хв на день" else "без обмеження часу" }) {
            category("Діти й уроки", ::childrenScreen)
        }
        row("Камера й пам'ять", "помічати людей: ${onOff(settings.presenceEnabled)}, що де лежить: ${onOff(settings.thingsEnabled)}") {
            category("Камера й пам'ять", ::cameraScreen)
        }
        row("Вібрація", "на дотик: ${onOff(settings.vibrateTouch)}") { category("Вібрація", ::vibrationScreen) }
        row("Розумний дім", "Tuya: ${onOff(settings.tuyaEnabled)}") { category("Розумний дім", ::homeScreen) }
        row("Режим сторожа", guardChannels().let { if (it.isEmpty()) "тривогу нікуди не надсилати" else "тривога: ${it.joinToString(", ")}" }) {
            category("Режим сторожа", ::guardScreen)
        }
        row("Колісна платформа", settings.robotUrl.ifBlank { "автопошук" }) { category("Колісна платформа", ::robotScreen) }
        row("PIN батьків", "змінити PIN") {
            PinDialog.setup(context, settings, title = "Новий PIN", message = "Новий 4-значний PIN батьків.", cancelable = true) {}
        }
        list.addView(TextView(context).apply {
            text = "Спіді ${BuildConfig.VERSION_NAME}"
            textSize = 13f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        })
        return ScrollView(context).apply { addView(list) }
    }

    private fun guardChannels() = listOfNotNull(
        "Telegram".takeIf { settings.guardTelegramToken.isNotBlank() && settings.guardTelegramChat.isNotBlank() },
        "пошта".takeIf { settings.guardSmtpHost.isNotBlank() && settings.guardEmailTo.isNotBlank() },
        "SMS".takeIf { settings.guardSmsNumber.isNotBlank() },
        "дзвінок".takeIf { settings.guardCallNumber.isNotBlank() },
    )

    /** Категорія: [fill] наповнює форму й повертає, як зберегти. */
    private fun category(title: String, fill: (Form) -> () -> Unit) {
        var save: () -> Unit = {}
        push(Screen(title, build = {
            val form = Form()
            save = fill(form)
            ScrollView(context).apply { addView(form.root) }
        }, save = { save() }))
    }

    /** Поля форми: розділ, підпис, текст, прапорець, перемикач, кнопка, повзунок, пояснення. */
    private inner class Form {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        fun section(text: String) = root.addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(ACCENT)
            setPadding(0, dp(16), 0, dp(4))
        })

        fun label(text: String) = root.addView(TextView(context).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setPadding(0, dp(10), 0, 0)
        })

        fun note(text: String) = root.addView(TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(GRAY)
            setPadding(0, dp(4), 0, 0)
        })

        fun field(value: String, hint: String, secret: Boolean = false, type: Int? = null) = EditText(context).apply {
            setText(value)
            this.hint = hint
            isSingleLine = true
            inputType = type ?: (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI))
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun check(text: String, on: Boolean) = CheckBox(context).apply {
            this.text = text
            isChecked = on
            setPadding(0, dp(6), 0, dp(6))
            root.addView(this)
        }

        /** Перемикач з варіантів; повертає "що вибрано". */
        fun <T> choice(options: List<T>, selected: T, caption: (T) -> String): () -> T {
            val buttons = options.associateWith { o -> RadioButton(context).apply { text = caption(o); id = View.generateViewId() } }
            val group = RadioGroup(context).apply {
                buttons.values.forEach { addView(it) }
                check((buttons[selected] ?: buttons.values.first()).id)
            }
            root.addView(group)
            return { buttons.entries.first { it.value.id == group.checkedRadioButtonId }.key }
        }

        fun button(text: String, onClick: (Button) -> Unit) = Button(context).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick(this) }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        fun slider(caption: (Int) -> String, value: Int, max: Int, onChange: (Int) -> Unit) {
            val title = TextView(context).apply { text = caption(value); setTextColor(Color.BLACK); setPadding(0, dp(8), 0, 0) }
            root.addView(title)
            root.addView(SeekBar(context).apply {
                this.max = max
                progress = value
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, v: Int, fromUser: Boolean) { onChange(v); title.text = caption(v) }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
        }

        fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    // --- категорії ---

    private fun voiceScreen(f: Form): () -> Unit {
        f.section("Голос")
        val preset = f.choice(VoicePreset.entries, VoicePreset.of(settings.voicePreset)) { it.ua }
        // Голос синтезатора — лише якщо українських кілька ("" — як у збірці).
        val names = listOf("") + voice?.names.orEmpty()
        val name: () -> String = if (names.size > 2) {
            f.section("Голос синтезатора")
            f.choice(names, settings.voiceName.takeIf { it in names } ?: "") { if (it.isEmpty()) "Голос за замовчуванням" else VoicePreset.voiceLabel(it) }
        } else { { settings.voiceName } }
        if (voice != null) f.button("Послухати") { voice.preview(preset(), name()) }
        f.section("Розмова")
        val facts = f.check("Інколи розповідати цікаві факти (під інтереси, не частіше ніж раз на 3 год)", settings.factsEnabled)
        return {
            settings.voicePreset = preset().code
            settings.voiceName = name()
            settings.factsEnabled = facts.isChecked
        }
    }

    private fun brainScreen(f: Form): () -> Unit {
        f.section("Хто відповідає")
        val provider = f.choice(listOf("claude", "gemini"), if (settings.provider == "gemini") "gemini" else "claude") {
            if (it == "gemini") "Gemini" else "Claude"
        }
        f.section("Claude")
        f.label("Ключ Anthropic (встав із буфера обміну)")
        val anthropicKey = f.field(settings.anthropicKey, "sk-ant-…", secret = true)
        f.label("Модель Claude")
        val claudeModel = f.field(settings.claudeModel, BuildConfig.CLAUDE_MODEL)
        f.section("Gemini")
        f.label("Ключ з оплатою (білінг у Google Cloud)")
        val geminiKey = f.field(settings.geminiKey, "AIza…", secret = true)
        f.label("Ключ безкоштовний (Google може вчити на цих запитах моделі)")
        val geminiFreeKey = f.field(settings.geminiFreeKey, "AIza…", secret = true)
        f.label("Як використовувати ключі")
        val mode = f.choice(com.speedy.assistant.brain.GeminiKeyMode.entries, com.speedy.assistant.brain.GeminiKeyMode.of(settings.geminiKeyMode)) { it.ua }
        val freeNoMedia = f.check("Фото й звук — лише через ключ з оплатою (безкоштовному — лише текст)", settings.geminiFreeNoMedia)
        f.label("Модель Gemini")
        val geminiModel = f.field(settings.geminiModel, BuildConfig.GEMINI_MODEL)
        return {
            settings.provider = provider()
            settings.anthropicKey = anthropicKey.text.toString()
            settings.claudeModel = claudeModel.text.toString()
            settings.geminiKey = geminiKey.text.toString()
            settings.geminiFreeKey = geminiFreeKey.text.toString()
            settings.geminiKeyMode = mode().code
            settings.geminiFreeNoMedia = freeNoMedia.isChecked
            settings.geminiModel = geminiModel.text.toString()
        }
    }

    private fun childrenScreen(f: Form): () -> Unit {
        f.section("Час розмови")
        f.label("Діти: хвилин розмови на день (0 — без обмеження)")
        val limit = f.field(settings.childLimitMinutes.toString(), "15", type = InputType.TYPE_CLASS_NUMBER)
        val usage = TextView(context).apply { setPadding(0, dp(8), 0, 0); setTextColor(GRAY) }
        fun refreshUsage() {
            val used = family?.usedToday().orEmpty()
            usage.text = if (used.isEmpty()) "Сьогодні діти ще не розмовляли." else "Сьогодні: " + used.entries.joinToString(", ") { "${it.key} — ${it.value} хв" }
        }
        refreshUsage()
        f.root.addView(usage)
        if (family != null) f.button("Скинути ліміт на сьогодні") {
            family.resetLimit()
            refreshUsage()
            Toast.makeText(context, "Денний ліміт скинуто", Toast.LENGTH_SHORT).show()
        }
        val names = family?.names.orEmpty()
        if (names.isNotEmpty()) f.section("Хто дитина")
        val childBoxes = names.associateWith { f.check("$it — дитина", family!!.isChild(it)) }
        // Стать — кнопка перемикає "жіноча / чоловіча" (спершу — вгадана з імені).
        val genders = names.associateWith { family!!.gender(it) }.toMutableMap()
        if (names.isNotEmpty()) f.section("Стать (для правильного роду)")
        names.forEach { name ->
            fun caption() = "$name — " + when (genders[name]) {
                com.speedy.assistant.people.Gender.FEMALE -> "жіноча"
                com.speedy.assistant.people.Gender.MALE -> "чоловіча"
                else -> "невідомо"
            }
            f.button(caption()) { b ->
                genders[name] = if (genders[name] == com.speedy.assistant.people.Gender.FEMALE) com.speedy.assistant.people.Gender.MALE
                    else com.speedy.assistant.people.Gender.FEMALE
                b.text = caption()
            }
        }
        f.section("Уроки")
        val offers = f.check("Пропонувати дітям уроки (не частіше ніж раз на 2 год)", settings.lessonOffers)
        f.note("Які уроки пропонувати частіше (0 — ніколи, 5 — найчастіше)")
        val weights = com.speedy.assistant.school.LessonKind.weights(settings.lessonWeights).toMutableMap()
        weights.keys.toList().forEach { kind ->
            f.slider({ "${kind.ua.replaceFirstChar { it.uppercase() }}: $it" }, weights[kind] ?: kind.defaultWeight, 5) { weights[kind] = it }
        }
        return {
            settings.childLimitMinutes = limit.text.toString().toIntOrNull() ?: settings.childLimitMinutes
            family?.let { fam ->
                childBoxes.forEach { (name, box) -> fam.setChild(name, box.isChecked) }
                genders.forEach { (name, g) -> if (g != com.speedy.assistant.people.Gender.UNKNOWN && g != fam.gender(name)) fam.setGender(name, g) }
            }
            settings.lessonOffers = offers.isChecked
            settings.lessonWeights = com.speedy.assistant.school.LessonKind.save(weights)
        }
    }

    private fun cameraScreen(f: Form): () -> Unit {
        f.section("Камера")
        val presence = f.check("Помічати людей і тварин камерою (працює на телефоні)", settings.presenceEnabled)
        val appearance = f.check("Запам'ятовувати зовнішність знайомих (фото моделі раз на 2 тижні)", settings.appearanceEnabled)
        f.section("Що де лежить")
        val things = f.check("Пам'ятати, що де лежить (фото кімнати моделі — до 4 разів на годину, коли сцена змінилась і нікого немає)", settings.thingsEnabled)
        val thingsPaid = f.check("Фото кімнати — лише ключем з оплатою", settings.thingsPaidOnly)
        thingsPaid.setOnCheckedChangeListener { box, checked ->
            if (checked) return@setOnCheckedChangeListener
            AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Фото безкоштовним ключем?")
                .setMessage(
                    "Фото кімнати оброблятиме безкоштовний ключ Gemini. Google може використовувати дані безкоштовного " +
                        "рівня для покращення своїх продуктів, і їх можуть переглядати люди — тобто фото стануть " +
                        "доступні третім особам. Без ключа з оплатою Спіді тоді надсилатиме фото кімнати саме так."
                )
                .setPositiveButton("Так, дозволити") { _, _ -> }
                .setNegativeButton("Ні, лише з оплатою") { _, _ -> box.isChecked = true }
                .setOnCancelListener { box.isChecked = true }
                .show()
        }
        return {
            settings.presenceEnabled = presence.isChecked
            settings.appearanceEnabled = appearance.isChecked
            settings.thingsEnabled = things.isChecked
            settings.thingsPaidOnly = thingsPaid.isChecked
        }
    }

    private fun vibrationScreen(f: Form): () -> Unit {
        val quiet = f.check("Нагадування вночі — вібрацією замість голосу", settings.vibrateQuiet)
        val find = f.check("«Спіді, де ти?» — вібрація й звук", settings.vibrateFind)
        val touch = f.check("На дотик і на звертання «Спіді»", settings.vibrateTouch)
        val cheer = f.check("На правильну відповідь в уроці", settings.vibrateCheer)
        return {
            settings.vibrateQuiet = quiet.isChecked
            settings.vibrateFind = find.isChecked
            settings.vibrateTouch = touch.isChecked
            settings.vibrateCheer = cheer.isChecked
        }
    }

    private fun homeScreen(f: Form): () -> Unit {
        val on = f.check("Підтримка Tuya: керувати світлом, розетками, датчиками голосом", settings.tuyaEnabled)
        f.note(
            "iot.tuya.com → Cloud → проєкт (Smart Home) → Devices → Link App Account (QR у Smart Life). " +
                "Access ID і Secret — на вкладці Overview проєкту. Дата-центр — той, що в проєкті."
        )
        f.section("Дата-центр")
        val region = f.choice(com.speedy.assistant.home.TuyaRegion.entries, com.speedy.assistant.home.TuyaRegion.of(settings.tuyaRegion)) { it.ua }
        f.label("Access ID (Client ID)")
        val id = f.field(settings.tuyaId, "…")
        f.label("Access Secret (Client Secret)")
        val secret = f.field(settings.tuyaSecret, "…", secret = true)
        if (home != null) f.button("Перевірити") { b ->
            b.text = "Перевіряю…"
            home.check(region().code, id.text.toString(), secret.text.toString()) { result ->
                b.text = "Перевірити"
                f.toast(result)
            }
        }
        return {
            settings.tuyaEnabled = on.isChecked
            settings.tuyaRegion = region().code
            settings.tuyaId = id.text.toString()
            settings.tuyaSecret = secret.text.toString()
        }
    }

    private fun guardScreen(f: Form): () -> Unit {
        f.note(
            "«Спіді, вартуй» — режим сторожа. Тривогу надсилаю всіма заповненими каналами. Telegram: створи бота в @BotFather, " +
                "встав токен, напиши боту будь-що й натисни «Знайти chat id». Пошта: для Gmail — smtp.gmail.com, 465 і «пароль застосунку»."
        )
        f.section("Telegram")
        f.label("Токен бота")
        val tgToken = f.field(settings.guardTelegramToken, "123456:ABC…", secret = true)
        f.label("Chat id — число (знайде кнопка нижче; ім'я бота сюди не пиши)")
        val tgChat = f.field(settings.guardTelegramChat, "напиши боту — і «Знайти»")
        f.button("Знайти chat id") { b ->
            b.text = "Шукаю…"
            guard?.findTelegramChat?.invoke(tgToken.text.toString()) { found, problem ->
                b.text = "Знайти chat id"
                if (found != null) { tgChat.setText(found); f.toast("Знайшла: $found") } else f.toast(problem ?: "Не знайшла")
            }
        }
        f.section("Пошта")
        f.label("Сервер SMTP і порт (465 — SSL, 587 — STARTTLS)")
        val smtpHost = f.field(settings.guardSmtpHost, "smtp.gmail.com")
        val smtpPort = f.field(settings.guardSmtpPort.toString(), "465", type = InputType.TYPE_CLASS_NUMBER)
        f.label("Логін (з якої) і пароль застосунку")
        val smtpUser = f.field(settings.guardSmtpUser, "you@gmail.com")
        val smtpPass = f.field(settings.guardSmtpPassword, "пароль застосунку", secret = true)
        f.label("Кому")
        val emailTo = f.field(settings.guardEmailTo, "you@gmail.com")
        f.section("Телефон")
        f.label("SMS на номер (лише текст)")
        val sms = f.field(settings.guardSmsNumber, "+380…", type = InputType.TYPE_CLASS_PHONE)
        f.label("Дзвінок на номер (почуєш кімнату)")
        val call = f.field(settings.guardCallNumber, "+380…", type = InputType.TYPE_CLASS_PHONE)
        f.section("Коли тривожити")
        val sounds = f.check("Тривога на звуки: чужий голос, двері, стук, скло, сирена", settings.guardSounds)
        val motion = f.check("Тривога на рух, у якому нікого не видно", settings.guardMotion)
        f.label("Хвилин між тривогами")
        val cooldown = f.field(settings.guardCooldownMinutes.toString(), "5", type = InputType.TYPE_CLASS_NUMBER)
        val save = {
            settings.guardTelegramToken = tgToken.text.toString()
            settings.guardTelegramChat = tgChat.text.toString()
            settings.guardSmtpHost = smtpHost.text.toString()
            settings.guardSmtpPort = smtpPort.text.toString().toIntOrNull() ?: settings.guardSmtpPort
            settings.guardSmtpUser = smtpUser.text.toString()
            settings.guardSmtpPassword = smtpPass.text.toString()
            settings.guardEmailTo = emailTo.text.toString()
            settings.guardSmsNumber = sms.text.toString()
            settings.guardCallNumber = call.text.toString()
            settings.guardSounds = sounds.isChecked
            settings.guardMotion = motion.isChecked
            settings.guardCooldownMinutes = cooldown.text.toString().toIntOrNull() ?: settings.guardCooldownMinutes
        }
        if (guard != null) f.button("Надіслати пробну тривогу") { b ->
            save() // пробуємо саме те, що введено
            b.text = "Надсилаю…"
            guard.testAlert { result ->
                b.text = "Надіслати пробну тривогу"
                f.toast(result)
            }
        }
        return save
    }

    private fun robotScreen(f: Form): () -> Unit {
        f.label("Адреса платформи (порожньо — автопошук у Wi-Fi)")
        val url = f.field(settings.robotUrl, settings.robotFoundUrl.ifBlank { "автопошук" })
        if (settings.robotFoundUrl.isNotBlank()) f.note("Знайдена: ${settings.robotFoundUrl}")
        return { settings.robotUrl = url.text.toString() }
    }

    companion object {
        val ACCENT = Color.rgb(0x2E, 0x5E, 0xAA)
        val GRAY = Color.rgb(0x75, 0x75, 0x75)
        val DIVIDER = Color.rgb(0xE0, 0xE0, 0xE0)
    }
}
