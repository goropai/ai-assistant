package com.speedy.assistant.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.speedy.assistant.books.Lang
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Переклад між українською, російською й англійською. */
interface Translator {
    /** Чи моделі цих мов уже на телефоні (інакше [prepare] завантажить ~30 МБ на мову). */
    suspend fun ready(from: Lang, to: Lang): Boolean
    suspend fun prepare(from: Lang, to: Lang)
    suspend fun translate(text: String, from: Lang, to: Lang): String
}

/**
 * Google ML Kit, офлайн: моделі мов (~30 МБ кожна) завантажуються один раз, далі — без інтернету й
 * без токенів. Не англійські пари (російська → українська) ML Kit перекладає через англійську.
 */
class MlKitTranslator : Translator {
    private val clients = mutableMapOf<Pair<Lang, Lang>, com.google.mlkit.nl.translate.Translator>()

    private fun client(from: Lang, to: Lang) = synchronized(clients) {
        clients.getOrPut(from to to) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(code(from)).setTargetLanguage(code(to)).build())
        }
    }

    override suspend fun ready(from: Lang, to: Lang): Boolean {
        val manager = RemoteModelManager.getInstance()
        return listOf(from, to).all { manager.isModelDownloaded(TranslateRemoteModel.Builder(code(it)).build()).await() }
    }

    override suspend fun prepare(from: Lang, to: Lang) {
        client(from, to).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(text: String, from: Lang, to: Lang): String =
        if (from == to || text.isBlank()) text else client(from, to).translate(text).await()

    private fun code(lang: Lang) = when (lang) {
        Lang.UK -> TranslateLanguage.UKRAINIAN
        Lang.RU -> TranslateLanguage.RUSSIAN
        Lang.EN -> TranslateLanguage.ENGLISH
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
