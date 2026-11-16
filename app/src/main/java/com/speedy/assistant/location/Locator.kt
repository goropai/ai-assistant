package com.speedy.assistant.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Де Спіді: GPS і мережеве визначення (Wi-Fi / вежі) через системний LocationManager —
 * без Google Play-сервісів. У приміщенні GPS зазвичай не ловить, тож спершу беремо
 * свіжу відому позицію, потім просимо одне оновлення від мережі й GPS.
 */
class Locator(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val allowed: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Позиція не старша за [maxAgeMs], або нова (чекаємо до [timeoutMs]); null — невідомо. */
    @SuppressLint("MissingPermission")
    suspend fun locate(maxAgeMs: Long = 30 * 60_000, timeoutMs: Long = 15_000): Location? {
        if (!allowed) return null
        val providers = manager.getProviders(true)
        val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time <= maxAgeMs }
            .minByOrNull { it.accuracy }
        if (known != null) return known
        val live = providers.filter { it == LocationManager.NETWORK_PROVIDER || it == LocationManager.GPS_PROVIDER }
        if (live.isEmpty()) return null
        return withTimeoutOrNull(timeoutMs) { firstFix(live) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun firstFix(providers: List<String>): Location = suspendCancellableCoroutine { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (cont.isActive) cont.resume(location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
        cont.invokeOnCancellation { manager.removeUpdates(listener) }
    }

    /** Назва місця за координатами (системний геокодер; може бути недоступний — тоді null). */
    suspend fun placeName(location: Location): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            @Suppress("DEPRECATION")
            val address = Geocoder(context, Locale("uk", "UA"))
                .getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                ?: return@withContext null
            listOfNotNull(address.locality ?: address.subAdminArea, address.thoroughfare)
                .joinToString(", ").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
