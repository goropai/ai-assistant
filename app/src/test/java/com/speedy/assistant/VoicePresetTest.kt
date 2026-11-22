package com.speedy.assistant

import com.speedy.assistant.settings.VoicePreset
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePresetTest {
    @Test
    fun presets() {
        // Невідомий чи порожній код — звичайна Спіді з голосом зі збірки.
        assertEquals(VoicePreset.SPEEDY, VoicePreset.of(""))
        assertEquals(VoicePreset.SPEEDY, VoicePreset.of("щось"))
        assertEquals(1.3f, VoicePreset.SPEEDY.pitch(1.3f))
        assertEquals(VoicePreset.CALM, VoicePreset.of("calm"))
        assertEquals(0.85f, VoicePreset.CALM.rate(1.05f))
        // Коди не повторюються (зберігаються в налаштуваннях).
        assertEquals(VoicePreset.entries.size, VoicePreset.entries.map { it.code }.toSet().size)
    }

    @Test
    fun voiceLabel() {
        assertEquals("hfd (без інтернету)", VoicePreset.voiceLabel("uk-ua-x-hfd-local"))
        assertEquals("hfd (через інтернет)", VoicePreset.voiceLabel("uk-ua-x-hfd-network"))
    }
}
