package com.speedy.assistant

import com.speedy.assistant.weather.Day
import com.speedy.assistant.weather.Forecast
import com.speedy.assistant.weather.WeatherQuery
import com.speedy.assistant.weather.WeatherService
import com.speedy.assistant.weather.WeatherText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {

    @Test
    fun recognizesWeatherQuestions() {
        listOf("яка сьогодні погода в Ужгороді", "Яка погода?", "чи буде дощ завтра", "прогноз на завтра").forEach {
            assertEquals(it, LocalCommand.WEATHER, LocalCommands.match(it))
        }
        assertEquals(LocalCommand.WHERE, LocalCommands.match("де ми зараз"))
    }

    @Test
    fun parsesPlaceAndDay() {
        assertEquals(WeatherQuery(false, "Ужгороді"), WeatherText.parse("яка сьогодні погода в Ужгороді"))
        assertEquals(WeatherQuery(true, "Львові"), WeatherText.parse("яка погода завтра у Львові"))
        assertEquals(WeatherQuery(true, null), WeatherText.parse("яка погода на завтра"))
        assertEquals(WeatherQuery(false, null), WeatherText.parse("яка погода надворі"))
    }

    @Test
    fun locativeToNominative() {
        assertTrue("Ужгород" in WeatherText.nominativeCandidates("Ужгороді"))
        assertTrue("Львів" in WeatherText.nominativeCandidates("Львові"))
        assertTrue("Київ" in WeatherText.nominativeCandidates("Києві"))
        assertTrue("Одеса" in WeatherText.nominativeCandidates("Одесі"))
        assertTrue("Мукачево" in WeatherText.nominativeCandidates("Мукачеві"))
        assertTrue("Дніпро" in WeatherText.nominativeCandidates("Дніпрі"))
        assertTrue("Черкаси" in WeatherText.nominativeCandidates("Черкасах"))
        assertTrue("Івано-Франківськ" in WeatherText.nominativeCandidates("Івано-Франківську"))
    }

    @Test
    fun degreesInUkrainian() {
        assertEquals("плюс 1 градус", WeatherText.degrees(0.6))
        assertEquals("плюс 3 градуси", WeatherText.degrees(3.2))
        assertEquals("мінус 5 градусів", WeatherText.degrees(-5.0))
        assertEquals("плюс 12 градусів", WeatherText.degrees(12.0))
        assertEquals("0 градусів", WeatherText.degrees(0.2))
    }

    @Test
    fun parsesOpenMeteoAndAnswers() {
        // Справжня відповідь Open-Meteo (Ужгород, 16.11.2026).
        val json = JSONObject(
            """{"current":{"temperature_2m":5.0,"weather_code":3,"wind_speed_10m":13.7},
               "daily":{"time":["2026-11-16","2026-11-17"],"weather_code":[63,63],
               "temperature_2m_max":[7.0,8.9],"temperature_2m_min":[4.2,3.5],
               "precipitation_probability_max":[100,98]}}"""
        )
        val f = WeatherService.parseForecast(json)
        assertEquals(Forecast(5.0, 3, 13.7, Day(63, 7.0, 4.2, 100), Day(63, 8.9, 3.5, 98)), f)
        assertEquals(
            "Зараз (Ужгород) плюс 5 градусів, хмарно. Сьогодні від плюс 4 градуси до плюс 7 градусів, ймовірність опадів 100%.",
            WeatherText.answer(f, WeatherQuery(false, "Ужгороді"), "Ужгород"),
        )
        assertEquals(
            "Завтра дощ, від плюс 4 градуси до плюс 9 градусів, ймовірність опадів 98%.",
            WeatherText.answer(f, WeatherQuery(true, null), null),
        )
    }
}
