package com.speedy.assistant.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.speedy.assistant.MainActivity

/**
 * Оновлення встановилось (самооновлення чи з комп'ютера) — Android зупиняє застосунок і сам не
 * запускає: виглядало як падіння. Спіді — "обличчя" робота, тож одразу повертається на екран.
 */
class RestartAfterUpdate : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Samsung вимикає службу спеціальних можливостей після оновлення — увімкнути назад (якщо є дозвіл).
        com.speedy.assistant.phone.SpeedyHands.ensureEnabled(context)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
