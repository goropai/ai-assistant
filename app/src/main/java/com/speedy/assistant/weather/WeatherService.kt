package com.speedy.assistant.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Погода з Open-Meteo: безкоштовно, без ключа, відповідь ~1 КБ, жодних токенів моделі.
 * Прогноз кешується на [CACHE_MS] для кожного місця — повторне питання без трафіку.
 */
class WeatherService(private val http: OkHttpClient) {
    private var cached: Triple<String, Long, Forecast>? = null
    private val places = HashMap<String, Place?>()

    /** Місто за назвою (у будь-якому відмінку, див. [WeatherText.nominativeCandidates]). */
    suspend fun findPlace(name: String): Place? {
        places[name.lowercase()]?.let { return it }
        var best: Pair<Place, Long>? = null
        for (candidate in WeatherText.nominativeCandidates(name)) {
            val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", candidate)
                .addQueryParameter("count", "5")
                .addQueryParameter("language", "uk")
                .build()
            val results = get(url.toString()).optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                if (!r.optString("name").equals(candidate, ignoreCase = true)) continue
                // Українські міста — пріоритет, далі — більше населення.
                val score = r.optLong("population") + if (r.optString("country_code") == "UA") 1_000_000_000L else 0
                if (best == null || score > best.second) {
                    best = Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude")) to score
                }
            }
            if (best != null) break
        }
        return best?.first.also { places[name.lowercase()] = it }
    }

    suspend fun forecast(latitude: Double, longitude: Double, now: Long = System.currentTimeMillis()): Forecast {
        val key = "%.2f,%.2f".format(java.util.Locale.ROOT, latitude, longitude)
        cached?.let { (k, at, f) -> if (k == key && now - at < CACHE_MS) return f }
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "2")
            .build()
        val forecast = parseForecast(get(url.toString()))
        cached = Triple(key, now, forecast)
        return forecast
    }

    private var cachedAir: Triple<String, Long, AirQuality>? = null

    /** Якість повітря зараз (Open-Meteo, без ключа); null — немає даних для цього місця. */
    suspend fun airQuality(latitude: Double, longitude: Double, now: Long = System.currentTimeMillis()): AirQuality? {
        val key = "%.2f,%.2f".format(java.util.Locale.ROOT, latitude, longitude)
        cachedAir?.let { (k, at, a) -> if (k == key && now - at < CACHE_MS) return a }
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "european_aqi,pm2_5,pm10")
            .addQueryParameter("timezone", "auto")
            .build()
        val air = parseAir(get(url.toString())) ?: return null
        cachedAir = Triple(key, now, air)
        return air
    }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) throw IOException("Open-Meteo HTTP ${it.code}")
            JSONObject(it.body?.string().orEmpty())
        }
    }

    companion object {
        const val CACHE_MS = 30 * 60_000L

        fun parseAir(json: JSONObject): AirQuality? {
            val c = json.optJSONObject("current") ?: return null
            if (c.isNull("european_aqi")) return null
            fun num(k: String) = if (c.isNull(k)) null else c.optDouble(k).takeIf { !it.isNaN() }
            return AirQuality(c.optDouble("european_aqi").toInt(), num("pm2_5"), num("pm10"))
        }

        fun parseForecast(json: JSONObject): Forecast {
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            fun day(i: Int): Day? {
                val codes = daily.getJSONArray("weather_code")
                if (i >= codes.length()) return null
                val rain = daily.optJSONArray("precipitation_probability_max")
                return Day(
                    code = codes.getInt(i),
                    maxC = daily.getJSONArray("temperature_2m_max").getDouble(i),
                    minC = daily.getJSONArray("temperature_2m_min").getDouble(i),
                    rainChance = rain?.takeIf { !it.isNull(i) }?.getInt(i),
                )
            }
            return Forecast(
                nowC = current.getDouble("temperature_2m"),
                nowCode = current.getInt("weather_code"),
                windKmh = current.optDouble("wind_speed_10m", 0.0),
                today = day(0) ?: throw IOException("Open-Meteo: немає прогнозу"),
                tomorrow = day(1),
            )
        }
    }
}
