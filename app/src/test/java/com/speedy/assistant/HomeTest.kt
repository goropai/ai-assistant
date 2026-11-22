package com.speedy.assistant

import com.speedy.assistant.home.HomeCommands
import com.speedy.assistant.home.HomeCommands.Command
import com.speedy.assistant.home.HomeDevice
import com.speedy.assistant.home.HomeText
import com.speedy.assistant.home.TuyaCloud
import com.speedy.assistant.home.TuyaSign
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTest {
    companion object {
        private fun d(id: String, name: String, category: String, vararg status: Pair<String, Any?>, online: Boolean = true) =
            HomeDevice(id, name, category, online, status.toMap())

        val DEVICES = listOf(
            d("l1", "Лампа кухня", "dj", "switch_led" to true, "bright_value_v2" to 1000),
            d("l2", "Лампа спальня", "dj", "switch_led" to false, "bright_value_v2" to 500),
            d("s1", "Розетка чайник", "cz", "switch_1" to false, "cur_power" to 0),
            d("s2", "Світло коридор", "kg", "switch_1" to true), // світло на розумному вимикачі
            d("t1", "Датчик спальня", "wsdcg", "va_temperature" to 215, "va_humidity" to 45, "battery_percentage" to 80),
            d("c1", "Вікно кухня", "mcs", "doorcontact_state" to true),
            d("h1", "Обігрівач", "qn", "switch" to false),
            d("w1", "Штори спальня", "cl", "control" to "stop"),
        )
    }

    private fun names(cmd: Command?): List<String> = when (cmd) {
        is Command.Power -> cmd.targets
        is Command.Brightness -> cmd.targets
        is Command.Cover -> cmd.targets
        is Command.Query -> cmd.targets
        is Command.Which -> cmd.candidates
        else -> emptyList()
    }.map { it.name }

    @Test
    fun powerByRoomAndKind() {
        val kitchen = HomeCommands.parse("увімкни світло на кухні", DEVICES)
        assertTrue(kitchen is Command.Power && kitchen.on)
        assertEquals(listOf("Лампа кухня"), names(kitchen))
        assertEquals(listOf("Розетка чайник"), names(HomeCommands.parse("Спіді, вимкни чайник", DEVICES)))
        assertFalse((HomeCommands.parse("вимкни розетку", DEVICES) as Command.Power).on)
        // "Все світло" — і лампи, і світло на вимикачі.
        assertEquals(listOf("Лампа кухня", "Лампа спальня", "Світло коридор"), names(HomeCommands.parse("вимкни все світло", DEVICES)))
        assertEquals(listOf("Світло коридор"), names(HomeCommands.parse("увімкни світло в коридорі", DEVICES)))
    }

    @Test
    fun asksWhichWhenAmbiguous() {
        val which = HomeCommands.parse("увімкни лампу", DEVICES)
        assertTrue(which is Command.Which)
        assertEquals(listOf("Лампа кухня", "Лампа спальня"), names(which))
        val chosen = HomeCommands.choose("в спальні", (which as Command.Which).candidates)!!
        val then = HomeCommands.retarget(which.then, chosen) as Command.Power
        assertEquals(listOf("Лампа спальня"), then.targets.map { it.name })
        assertTrue(then.on)
    }

    @Test
    fun otherCommandsAreNotStolen() {
        // Медіа, сторож, будильник, відео — не розумний дім (таких пристроїв немає).
        listOf("увімкни фільм Дюна", "увімкни режим сторожа", "вимкни будильник", "закрий відео", "увімкни музику", "яка температура").forEach {
            assertNull(it, HomeCommands.parse(it, DEVICES))
        }
        assertNull(HomeCommands.parse("увімкни світло на кухні", emptyList()))
    }

    @Test
    fun brightnessAndCovers() {
        val b = HomeCommands.parse("яскравість 30 відсотків Лампа кухня", DEVICES) as Command.Brightness
        assertEquals(30, b.percent)
        assertEquals(listOf("Лампа кухня"), names(b))
        assertEquals(HomeCommands.DIM_PERCENT, (HomeCommands.parse("притуши світло в спальні", DEVICES) as Command.Brightness).percent)
        val c = HomeCommands.parse("закрий штори", DEVICES) as Command.Cover
        assertFalse(c.open)
        assertEquals(listOf("Штори спальня"), names(c))
    }

    @Test
    fun queries() {
        assertEquals(listOf("Датчик спальня"), names(HomeCommands.parse("яка температура в спальні", DEVICES)))
        assertEquals(listOf("Датчик спальня"), names(HomeCommands.parse("яка вологість вдома", DEVICES)))
        // Без назви датчика — усі датчики.
        assertEquals(listOf("Датчик спальня"), names(HomeCommands.parse("яка зараз температура в приміщенні", DEVICES)))
        assertEquals(listOf("Датчик спальня"), names(HomeCommands.parse("яка температура у нас", DEVICES)))
        assertEquals(listOf("Вікно кухня"), names(HomeCommands.parse("чи зачинене вікно на кухні", DEVICES)))
        assertEquals(listOf("Обігрівач"), names(HomeCommands.parse("чи увімкнений обігрівач", DEVICES)))
        assertEquals(Command.Overview, HomeCommands.parse("що вдома", DEVICES))
    }

    @Test
    fun dangerousKinds() {
        assertTrue(DEVICES.first { it.name == "Обігрівач" }.dangerous)
        assertFalse(DEVICES.first { it.name == "Розетка чайник" }.dangerous)
    }

    @Test
    fun stateText() {
        val sensor = DEVICES.first { it.name == "Датчик спальня" }
        assertEquals("Датчик спальня: 21,5 °C, вологість 45 відсотків", HomeText.state(sensor))
        assertEquals("Датчик спальня: 21,5 °C, вологість 45 відсотків", HomeText.state(sensor, mapOf("va_temperature" to 1)))
        assertEquals("Лампа кухня: увімкнено, яскравість 100 відсотків", HomeText.state(DEVICES[0]))
        assertEquals("Вікно кухня: відчинено", HomeText.state(DEVICES.first { it.name == "Вікно кухня" }))
        assertEquals("Обігрівач (обігрів, з PIN, вимк.)", HomeText.brief(DEVICES.first { it.name == "Обігрівач" }))
        assertEquals("Розетка: не в мережі", HomeText.state(HomeDevice("x", "Розетка", "cz", false, emptyMap())))
        assertEquals("Малючок: не в мережі, останні показники — 21,5 °C, вологість 45 відсотків",
            HomeText.state(HomeDevice("m", "Малючок", "wsdcg", false, mapOf("va_temperature" to 215, "va_humidity" to 45))))
        // Відмінювання: 41 відсоток, 42 відсотки, 12 відсотків.
        assertEquals("Малючок: 26,6 °C, вологість 41 відсоток, батарейка сідає (12 відсотків)",
            HomeText.state(HomeDevice("m", "Малючок", "wsdcg", true, mapOf("temp_current" to 266, "humidity_value" to 41, "battery_percentage" to 12))))
        assertEquals("Малючок: 26,6 °C, вологість 42 відсотки",
            HomeText.state(HomeDevice("m", "Малючок", "wsdcg", true, mapOf("temp_current" to 266, "humidity_value" to 42))))
    }

    @Test
    fun signing() {
        assertEquals(TuyaSign.EMPTY_SHA256, TuyaSign.sha256(""))
        assertEquals("/v1.0/token?grant_type=1", TuyaSign.url("/v1.0/token", mapOf("grant_type" to "1")))
        assertEquals("/v1.0/x?a=1&b=2", TuyaSign.url("/v1.0/x", mapOf("b" to "2", "a" to "1")))
        assertEquals("GET\n${TuyaSign.EMPTY_SHA256}\n\n/v1.0/token?grant_type=1", TuyaSign.stringToSign("GET", "", "/v1.0/token?grant_type=1"))
        val sign = TuyaSign.sign("id", "secret", 1588925778000, null, "", "GET\n${TuyaSign.EMPTY_SHA256}\n\n/v1.0/token?grant_type=1")
        assertEquals(64, sign.length)
        assertEquals(sign.uppercase(), sign)
        // З токеном — інший підпис.
        assertTrue(sign != TuyaSign.sign("id", "secret", 1588925778000, "tok", "", "GET\n${TuyaSign.EMPTY_SHA256}\n\n/v1.0/token?grant_type=1"))
    }

    @Test
    fun parsesDeviceJson() {
        val o = JSONObject("""{"id":"abc","name":"Лампа кухня","category":"dj","online":true,
            "status":[{"code":"switch_led","value":true},{"code":"bright_value_v2","value":500}]}""")
        val dev = TuyaCloud.device(o)
        assertEquals("Лампа кухня", dev.name)
        assertEquals(true, dev.isOn)
        assertEquals("bright_value_v2", dev.brightCode)
    }
}
