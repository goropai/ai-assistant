package com.speedy.assistant.robot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Пошук платформи в локальній мережі: шлемо UDP-трансляцію "SPEEDY_DISCOVER",
 * плата відповідає "SPEEDY_ROBOT <http-порт>". IP беремо з адреси відповіді.
 */
class RobotDiscovery(
    private val port: Int = DEFAULT_PORT,
) {
    suspend fun discover(
        timeoutMs: Long = 2500,
        targets: List<InetAddress> = broadcastAddresses(),
    ): String? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val request = REQUEST.toByteArray()
                val buffer = ByteArray(256)
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L

                while (System.currentTimeMillis() < deadline) {
                    // Повторюємо запит: UDP може загубитися.
                    if (System.currentTimeMillis() >= nextSend) {
                        targets.forEach { target ->
                            runCatching { socket.send(DatagramPacket(request, request.size, target, port)) }
                                .onFailure { Log.w(TAG, "send to $target failed: ${it.message}") }
                        }
                        nextSend = System.currentTimeMillis() + 700
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    parseReply(String(packet.data, 0, packet.length), packet.address)?.let { return@use it }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed", e)
            null
        }
    }

    companion object {
        const val DEFAULT_PORT = 4210
        const val REQUEST = "SPEEDY_DISCOVER"
        private const val TAG = "RobotDiscovery"

        fun parseReply(text: String, from: InetAddress): String? {
            val parts = text.trim().split(Regex("\\s+"))
            if (parts.firstOrNull() != "SPEEDY_ROBOT") return null
            val httpPort = parts.getOrNull(1)?.toIntOrNull() ?: 80
            val host = from.hostAddress ?: return null
            return if (httpPort == 80) "http://$host" else "http://$host:$httpPort"
        }

        /** Широкомовні адреси всіх активних мереж (Wi-Fi, точка доступу) + 255.255.255.255. */
        fun broadcastAddresses(): List<InetAddress> {
            val result = mutableListOf<InetAddress>()
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }
                    .mapNotNullTo(result) { it.broadcast }
            }
            result += InetAddress.getByName("255.255.255.255")
            return result.distinct()
        }
    }
}
