package com.speedy.assistant.settings

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
        val findTelegramChat: (token: String, done: (String?) -> Unit) -> Unit,
        val testAlert: (done: (String) -> Unit) -> Unit,
    )

    fun show(context: Context, settings: AppSettings, family: Family? = null, guard: Guard? = null, onSaved: () -> Unit) {
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
        label("Ключ Gemini з оплатою (білінг у Google Cloud)")
        val geminiKey = field(settings.geminiKey, "AIza…", secret = true)
        label("Ключ Gemini безкоштовний (Google може вчити на цих запитах моделі)")
        val geminiFreeKey = field(settings.geminiFreeKey, "AIza…", secret = true)
        label("Ключі Gemini")
        val modes = com.speedy.assistant.brain.GeminiKeyMode.entries.associateWith { m ->
            RadioButton(context).apply { text = m.ua; id = View.generateViewId() }
        }
        val keyModes = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            modes.values.forEach { addView(it) }
            check(modes.getValue(com.speedy.assistant.brain.GeminiKeyMode.of(settings.geminiKeyMode)).id)
        }
        form.addView(keyModes)
        val freeNoMedia = CheckBox(context).apply {
            text = "Фото й звук — лише через ключ з оплатою (безкоштовному — лише текст)"
            isChecked = settings.geminiFreeNoMedia
        }
        form.addView(freeNoMedia)
        label("Модель Gemini")
        val geminiModel = field(settings.geminiModel, BuildConfig.GEMINI_MODEL)
        label("Колісна платформа (порожньо = автопошук)")
        val robotUrl = field(settings.robotUrl, settings.robotFoundUrl.ifBlank { "автопошук" })

        val presence = CheckBox(context).apply {
            text = "Помічати людей і тварин камерою (працює на телефоні)"
            isChecked = settings.presenceEnabled
        }
        form.addView(presence)
        val appearance = CheckBox(context).apply {
            text = "Запам'ятовувати зовнішність знайомих (фото моделі раз на 2 тижні)"
            isChecked = settings.appearanceEnabled
        }
        form.addView(appearance)
        val facts = CheckBox(context).apply {
            text = "Інколи розповідати цікаві факти (під інтереси, не частіше ніж раз на 3 год)"
            isChecked = settings.factsEnabled
        }
        form.addView(facts)
        val offers = CheckBox(context).apply {
            text = "Пропонувати дітям уроки (не частіше ніж раз на 2 год)"
            isChecked = settings.lessonOffers
        }
        form.addView(offers)
        label("Які уроки пропонувати частіше (0 — ніколи, 5 — найчастіше)")
        val weights = com.speedy.assistant.school.LessonKind.weights(settings.lessonWeights).toMutableMap()
        weights.keys.toList().forEach { kind ->
            val title = TextView(context).apply { setPadding(0, pad / 3, 0, 0) }
            fun caption() { title.text = "${kind.ua.replaceFirstChar { it.uppercase() }}: ${weights[kind]}" }
            caption()
            form.addView(title)
            form.addView(android.widget.SeekBar(context).apply {
                max = 5
                progress = weights[kind] ?: kind.defaultWeight
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) { weights[kind] = value; caption() }
                    override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
                })
            })
        }

        label("Вібрація")
        fun vibe(text: String, on: Boolean) = CheckBox(context).apply { this.text = text; isChecked = on; form.addView(this) }
        val vibeQuiet = vibe("нагадування вночі — вібрацією замість голосу", settings.vibrateQuiet)
        val vibeFind = vibe("«Спіді, де ти?» — вібрація й звук", settings.vibrateFind)
        val vibeTouch = vibe("на дотик і на звертання «Спіді»", settings.vibrateTouch)
        val vibeCheer = vibe("на правильну відповідь в уроці", settings.vibrateCheer)

        label("Діти: хвилин розмови на день (0 — без обмеження)")
        val limit = field(settings.childLimitMinutes.toString(), "15").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val childBoxes = family?.names.orEmpty().map { name ->
            CheckBox(context).apply {
                text = "$name — дитина"
                isChecked = family!!.isChild(name)
                form.addView(this)
            }
        }
        // Стать — кнопка перемикає "жіноча / чоловіча" (спершу — вгадана з імені).
        val genders = family?.names.orEmpty().associateWith { family!!.gender(it) }.toMutableMap()
        family?.names.orEmpty().forEach { name ->
            form.addView(android.widget.Button(context).apply {
                fun label() = "$name — стать: " + when (genders[name]) {
                    com.speedy.assistant.people.Gender.FEMALE -> "жіноча"
                    com.speedy.assistant.people.Gender.MALE -> "чоловіча"
                    else -> "невідомо"
                }
                text = label()
                isAllCaps = false
                setOnClickListener {
                    genders[name] = if (genders[name] == com.speedy.assistant.people.Gender.FEMALE) com.speedy.assistant.people.Gender.MALE else com.speedy.assistant.people.Gender.FEMALE
                    text = label()
                }
            })
        }
        val usage = TextView(context).apply {
            fun refresh() {
                val used = family?.usedToday().orEmpty()
                text = if (used.isEmpty()) "Сьогодні діти ще не розмовляли."
                else "Сьогодні: " + used.entries.joinToString(", ") { "${it.key} — ${it.value} хв" }
            }
            refresh()
            setPadding(0, pad / 2, 0, 0)
            tag = Runnable { refresh() }
        }
        form.addView(usage)
        label("— Режим сторожа («Спіді, вартуй») —")
        form.addView(TextView(context).apply {
            text = "Тривогу надсилаю всіма заповненими каналами. Telegram: створи бота в @BotFather, встав токен, " +
                "напиши боту будь-що й натисни «Знайти chat id». Пошта: для Gmail — smtp.gmail.com, 465 і «пароль застосунку»."
            textSize = 12f
        })
        label("Telegram: токен бота")
        val tgToken = field(settings.guardTelegramToken, "123456:ABC…", secret = true)
        label("Telegram: chat id")
        val tgChat = field(settings.guardTelegramChat, "напиши боту — і «Знайти»")
        form.addView(android.widget.Button(context).apply {
            text = "Знайти chat id"
            isAllCaps = false
            setOnClickListener {
                text = "Шукаю…"
                guard?.findTelegramChat?.invoke(tgToken.text.toString()) { id ->
                    text = "Знайти chat id"
                    if (id != null) tgChat.setText(id)
                    else android.widget.Toast.makeText(context, "Не знайшла: перевір токен і напиши боту ще раз", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })
        label("Пошта: сервер SMTP і порт (465 — SSL, 587 — STARTTLS)")
        val smtpHost = field(settings.guardSmtpHost, "smtp.gmail.com")
        val smtpPort = field(settings.guardSmtpPort.toString(), "465").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        label("Пошта: логін (з якої) і пароль застосунку")
        val smtpUser = field(settings.guardSmtpUser, "you@gmail.com")
        val smtpPass = field(settings.guardSmtpPassword, "пароль застосунку", secret = true)
        label("Пошта: кому")
        val emailTo = field(settings.guardEmailTo, "you@gmail.com")
        label("SMS на номер (лише текст)")
        val smsNumber = field(settings.guardSmsNumber, "+380…").apply { inputType = InputType.TYPE_CLASS_PHONE }
        label("Дзвінок на номер (почуєш кімнату)")
        val callNumber = field(settings.guardCallNumber, "+380…").apply { inputType = InputType.TYPE_CLASS_PHONE }
        val guardSounds = CheckBox(context).apply { text = "тривога на звуки: чужий голос, двері, стук, скло, сирена"; isChecked = settings.guardSounds; form.addView(this) }
        val guardMotion = CheckBox(context).apply { text = "тривога на рух, у якому нікого не видно"; isChecked = settings.guardMotion; form.addView(this) }
        label("Хвилин між тривогами")
        val cooldown = field(settings.guardCooldownMinutes.toString(), "5").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        fun saveGuard() {
            settings.guardTelegramToken = tgToken.text.toString()
            settings.guardTelegramChat = tgChat.text.toString()
            settings.guardSmtpHost = smtpHost.text.toString()
            settings.guardSmtpPort = smtpPort.text.toString().toIntOrNull() ?: settings.guardSmtpPort
            settings.guardSmtpUser = smtpUser.text.toString()
            settings.guardSmtpPassword = smtpPass.text.toString()
            settings.guardEmailTo = emailTo.text.toString()
            settings.guardSmsNumber = smsNumber.text.toString()
            settings.guardCallNumber = callNumber.text.toString()
            settings.guardSounds = guardSounds.isChecked
            settings.guardMotion = guardMotion.isChecked
            settings.guardCooldownMinutes = cooldown.text.toString().toIntOrNull() ?: settings.guardCooldownMinutes
        }
        if (guard != null) form.addView(android.widget.Button(context).apply {
            text = "Надіслати пробну тривогу"
            isAllCaps = false
            setOnClickListener {
                saveGuard() // пробуємо саме те, що введено
                text = "Надсилаю…"
                guard.testAlert { result ->
                    text = "Надіслати пробну тривогу"
                    android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })

        // Налаштування вже відкрито за PIN — новий одразу (двічі; можна й голосом).
        form.addView(android.widget.Button(context).apply {
            text = "Змінити PIN"
            setOnClickListener {
                PinDialog.setup(context, settings, title = "Новий PIN", message = "Новий 4-значний PIN батьків.", cancelable = true) {}
            }
        })

        AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Спіді ${BuildConfig.VERSION_NAME}")
            .setView(ScrollView(context).apply { addView(form) })
            .setPositiveButton("Зберегти") { _, _ ->
                settings.provider = if (providers.checkedRadioButtonId == gemini.id) "gemini" else "claude"
                settings.anthropicKey = anthropicKey.text.toString()
                settings.claudeModel = claudeModel.text.toString()
                settings.geminiKey = geminiKey.text.toString()
                settings.geminiFreeKey = geminiFreeKey.text.toString()
                settings.geminiKeyMode = modes.entries.first { it.value.id == keyModes.checkedRadioButtonId }.key.code
                settings.geminiFreeNoMedia = freeNoMedia.isChecked
                settings.geminiModel = geminiModel.text.toString()
                settings.robotUrl = robotUrl.text.toString()
                settings.presenceEnabled = presence.isChecked
                settings.vibrateQuiet = vibeQuiet.isChecked
                settings.vibrateFind = vibeFind.isChecked
                settings.vibrateTouch = vibeTouch.isChecked
                settings.vibrateCheer = vibeCheer.isChecked
                settings.childLimitMinutes = limit.text.toString().toIntOrNull() ?: settings.childLimitMinutes
                family?.names?.zip(childBoxes)?.forEach { (name, box) -> family.setChild(name, box.isChecked) }
                family?.let { f -> genders.forEach { (name, g) -> if (g != com.speedy.assistant.people.Gender.UNKNOWN && g != f.gender(name)) f.setGender(name, g) } }
                settings.appearanceEnabled = appearance.isChecked
                settings.factsEnabled = facts.isChecked
                settings.lessonOffers = offers.isChecked
                settings.lessonWeights = com.speedy.assistant.school.LessonKind.save(weights)
                saveGuard()
                onSaved()
            }
            .setNegativeButton("Скасувати", null)
            .apply {
                if (family != null) setNeutralButton("Скинути ліміт", null)
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    // "Скинути ліміт" не закриває діалог — можна скільки завгодно разів.
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        family?.resetLimit?.invoke()
                        (usage.tag as? Runnable)?.run()
                        android.widget.Toast.makeText(context, "Денний ліміт скинуто", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.show()
            }
    }
}
