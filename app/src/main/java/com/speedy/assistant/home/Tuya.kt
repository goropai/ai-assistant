package com.speedy.assistant.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Дата-центр Tuya — той, де обліковий запис Smart Life (для України зазвичай Центральна Європа). */
enum class TuyaRegion(val code: String, val ua: String, val base: String) {
    CENTRAL_EU("eu", "Центральна Європа", "https://openapi.tuyaeu.com"),
    WESTERN_EU("weu", "Західна Європа", "https://openapi-weaz.tuyaeu.com"),
    US("us", "США", "https://openapi.tuyaus.com"),
    CHINA("cn", "Китай", "https://openapi.tuyacn.com"),
    INDIA("in", "Індія", "https://openapi.tuyain.com");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: CENTRAL_EU
    }
}

/**
 * Підпис запитів Tuya Cloud (HMAC-SHA256): рядок = client_id + [access_token] + t + nonce + stringToSign,
 * де stringToSign = метод \n SHA256(тіла) \n (підписані заголовки — немає) \n шлях?відсортовані_параметри.
 */
object TuyaSign {
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun url(path: String, query: Map<String, String>): String =
        if (query.isEmpty()) path else path + "?" + query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    fun stringToSign(method: String, body: String, url: String): String = "$method\n${sha256(body)}\n\n$url"

    fun sign(clientId: String, secret: String, t: Long, accessToken: String?, nonce: String, stringToSign: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return mac.doFinal((clientId + accessToken.orEmpty() + t + nonce + stringToSign).toByteArray())
            .joinToString("") { "%02X".format(it) }
    }
}

/** Відмова Tuya ("sign invalid", "No permissions" тощо): [code] — код Tuya. */
class TuyaException(val code: Int, message: String) : IOException(message)

/**
 * Tuya Cloud OpenAPI для проєкту з прив'язаним застосунком Smart Life: список пристроїв, стан, команди.
 * Ключі — з налаштувань (вводить людина), у журнал не пишемо ні їх, ні токен.
 */
class TuyaCloud(
    private val http: OkHttpClient,
    private val region: TuyaRegion,
    private val clientId: String,
    private val secret: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var token: String? = null
    private var tokenUntil = 0L

    private suspend fun token(): String {
        token?.takeIf { now() < tokenUntil }?.let { return it }
        val result = call("GET", "/v1.0/token", mapOf("grant_type" to "1"), null, withToken = false) as JSONObject
        val t = result.getString("access_token")
        token = t
        tokenUntil = now() + result.optLong("expire_time", 7200) * 1000 - 60_000
        return t
    }

    /** Запит із токеном; токен прострочився (1010) — новий і ще раз. */
    suspend fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): Any? = try {
        call(method, path, query, body, withToken = true)
    } catch (e: TuyaException) {
        if (e.code != TOKEN_INVALID) throw e
        token = null
        call(method, path, query, body, withToken = true)
    }

    private suspend fun call(method: String, path: String, query: Map<String, String>, body: JSONObject?, withToken: Boolean): Any? {
        val access = if (withToken) token() else null
        return withContext(Dispatchers.IO) {
            val url = TuyaSign.url(path, query)
            val text = body?.toString().orEmpty()
            val t = now()
            val sign = TuyaSign.sign(clientId, secret, t, access, "", TuyaSign.stringToSign(method, text, url))
            val request = Request.Builder().url(region.base + url)
                .header("client_id", clientId).header("sign", sign).header("t", t.toString())
                .header("sign_method", "HMAC-SHA256")
                .apply { if (access != null) header("access_token", access) }
                .method(method, if (method == "GET") null else text.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                if (!json.optBoolean("success")) throw TuyaException(json.optInt("code"), json.optString("msg", "HTTP ${response.code}"))
                json.opt("result")
            }
        }
    }

    /** Усі пристрої прив'язаних застосунків (Smart Life / Tuya Smart) разом зі станом. */
    suspend fun devices(): List<HomeDevice> {
        val out = mutableListOf<HomeDevice>()
        var lastRow = ""
        repeat(20) {
            val query = mutableMapOf("size" to "100").apply { if (lastRow.isNotEmpty()) put("last_row_key", lastRow) }
            val result = request("GET", "/v1.0/iot-01/associated-users/devices", query) as? JSONObject ?: return out
            val list = result.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until list.length()) out += device(list.getJSONObject(i))
            lastRow = result.optString("last_row_key")
            if (!result.optBoolean("has_more") || lastRow.isEmpty()) return out
        }
        return out
    }

    suspend fun status(id: String): Map<String, Any?> = statusMap(request("GET", "/v1.0/devices/$id/status") as? JSONArray)

    /** Команда пристрою: "switch_led" → true тощо. */
    suspend fun command(id: String, code: String, value: Any): Boolean {
        val body = JSONObject().put("commands", JSONArray().put(JSONObject().put("code", code).put("value", value)))
        return request("POST", "/v1.0/devices/$id/commands", body = body) == true
    }

    /** Можливі значення кодів (діапазон яскравості, масштаб температури): код → {min, max, scale…}. */
    suspend fun specs(id: String): Map<String, JSONObject> {
        val result = request("GET", "/v1.0/devices/$id/specifications") as? JSONObject ?: return emptyMap()
        val out = HashMap<String, JSONObject>()
        for (key in listOf("status", "functions")) {
            val arr = result.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val values = try { JSONObject(item.optString("values", "{}")) } catch (e: Exception) { JSONObject() }
                out[item.getString("code")] = values
            }
        }
        return out
    }

    companion object {
        const val TOKEN_INVALID = 1010
        private val JSON = "application/json".toMediaType()

        fun device(o: JSONObject) = HomeDevice(
            id = o.getString("id"),
            name = o.optString("name").ifBlank { o.optString("product_name") }.trim(),
            category = o.optString("category"),
            online = o.optBoolean("online", true),
            status = statusMap(o.optJSONArray("status")),
        )

        fun statusMap(arr: JSONArray?): Map<String, Any?> {
            arr ?: return emptyMap()
            return (0 until arr.length()).associate { i -> arr.getJSONObject(i).let { it.getString("code") to it.opt("value") } }
        }
    }
}
