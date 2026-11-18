package com.speedy.assistant

import com.speedy.assistant.news.News
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsTest {
    @Test
    fun requests() {
        listOf("які новини?", "Спіді, розкажи новини", "огляд новин", "що нового в світі", "що відбувається в Україні",
            "що пишуть в новинах").forEach { assertTrue(it, News.isRequest(it)) }
        // "Що нового?" — сповіщення телефону; заперечення — не прохання.
        listOf("що нового?", "не розповідай новини", "без новин, будь ласка", "котра година").forEach { assertFalse(it, News.isRequest(it)) }
    }

    @Test
    fun queryHasPlaceAndInterests() {
        val q = News.query("Львів", listOf("космос", "футбол"), 0)
        assertTrue(q, q.contains("Львів") && q.contains("Україна") && q.contains("космос"))
        assertTrue(News.digestPrompt(null, null, emptyList()).contains("не вдався"))
    }
}
