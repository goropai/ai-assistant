package com.speedy.assistant.home

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Протокол Tuya BLE (як у відкритій інтеграції tuya_ble для Home Assistant): пакети по 20 байт, AES-128-CBC,
 * ключ входу — MD5 перших 6 символів local_key, сеансовий — MD5(ті 6 + srand пристрою), CRC16 (Modbus).
 */
object TuyaBleProtocol {
    const val MTU = 20
    const val DEVICE_INFO = 0x0000
    const val PAIR = 0x0001
    const val DEVICE_STATUS = 0x0003
    const val RECEIVE_DP = 0x8001
    const val RECEIVE_TIME_DP = 0x8003
    const val RECEIVE_SIGN_DP = 0x8004
    const val RECEIVE_SIGN_TIME_DP = 0x8005
    const val RECEIVE_DP_V4 = 0x8006
    const val RECEIVE_TIME_DP_V4 = 0x8007
    const val TIME1_REQ = 0x8011
    const val TIME2_REQ = 0x8012
    /** Привітання для каналу FD50: оголошене MTU (як у форку tuya_ble для TuyaOS-пристроїв). */
    val FD50_DEVICE_INFO = byteArrayOf(0x00, 0xF3.toByte())
    /** FD50: привітання — одним записом до стількох байт (після узгодження MTU). */
    const val FD50_CHUNK = 244
    const val FLAG_LOGIN = 4
    const val FLAG_SESSION = 5

    /** Варіант привітання: вміст, версія в заголовку, розмір запису, запис із підтвердженням. */
    data class Hello(val data: ByteArray, val version: Int, val chunk: Int, val withResponse: Boolean = false) {
        override fun toString() = "дані ${data.joinToString("") { "%02x".format(it) }.ifEmpty { "—" }}, версія $version, " +
            "по $chunk Б" + if (withResponse) ", з підтвердженням" else ""
    }

    /**
     * FD50-пристрої вітаються по-різному (замок Raykube — 00 f3 одним записом; HL Knob — по 20 байт;
     * деякі — версія з реклами, часто 0): пробуємо по черзі, поки не відповість.
     */
    val FD50_HELLOS = listOf(
        Hello(FD50_DEVICE_INFO, 2, FD50_CHUNK),
        Hello(FD50_DEVICE_INFO, 2, MTU),
        Hello(ByteArray(0), 2, MTU),
        Hello(FD50_DEVICE_INFO, 0, FD50_CHUNK),
        Hello(ByteArray(0), 0, MTU),
        Hello(FD50_DEVICE_INFO, 2, MTU, withResponse = true),
    )

    fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** Число змінної довжини (по 7 біт, старший біт — "ще є"). */
    fun packInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
        return out.toByteArray()
    }

    /** @return значення і нова позиція. */
    fun unpackInt(data: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = start
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to pos
    }

    private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)

    /** Повідомлення → пакети для запису в характеристику (кожен ≤ [MTU] байт). */
    fun buildPackets(
        seq: Int, code: Int, data: ByteArray, responseTo: Int, flag: Int, key: ByteArray, iv: ByteArray, protocolVersion: Int,
        chunk: Int = MTU,
    ): List<ByteArray> {
        val raw = ByteArrayOutputStream()
        raw.write(int32(seq)); raw.write(int32(responseTo)); raw.write(int16(code)); raw.write(int16(data.size)); raw.write(data)
        val body = raw.toByteArray()
        val crc = crc16(body)
        val plain = (body + int16(crc)).let { it + ByteArray((16 - it.size % 16) % 16) }
        val encrypted = byteArrayOf(flag.toByte()) + iv + aes(Cipher.ENCRYPT_MODE, key, iv, plain)
        val packets = mutableListOf<ByteArray>()
        var pos = 0
        var num = 0
        while (pos < encrypted.size) {
            val head = ByteArrayOutputStream()
            head.write(packInt(num))
            if (num == 0) {
                head.write(packInt(encrypted.size))
                head.write(protocolVersion shl 4)
            }
            val h = head.toByteArray()
            val part = encrypted.copyOfRange(pos, minOf(encrypted.size, pos + chunk - h.size))
            packets += h + part
            pos += part.size
            num++
        }
        return packets
    }

    class Message(val seq: Int, val responseTo: Int, val code: Int, val data: ByteArray)

    /** Розшифрувати зібране повідомлення; [keyFor] — ключ за прапорцем безпеки; null — не той ключ / пошкоджене. */
    fun decrypt(buffer: ByteArray, keyFor: (Int) -> ByteArray?): Message? {
        if (buffer.size < 33) return null
        val key = keyFor(buffer[0].toInt()) ?: return null
        val iv = buffer.copyOfRange(1, 17)
        val enc = buffer.copyOfRange(17, buffer.size)
        if (enc.size % 16 != 0) return null
        val raw = aes(Cipher.DECRYPT_MODE, key, iv, enc)
        val len = u16(raw, 10)
        if (12 + len + 2 > raw.size) return null
        if (crc16(raw, 12 + len) != u16(raw, 12 + len)) return null
        return Message(s32(raw, 0), s32(raw, 4), u16(raw, 8), raw.copyOfRange(12, 12 + len))
    }

    /** Збирає повідомлення з нотифікацій (перший пакет: номер 0, загальна довжина, версія протоколу). */
    class Reassembler {
        private val buf = ByteArrayOutputStream()
        private var expected = -1

        /** Повне повідомлення або null — ще не все. */
        fun feed(packet: ByteArray): ByteArray? {
            var (num, pos) = unpackInt(packet, 0)
            if (num == 0) {
                buf.reset()
                val (len, p) = unpackInt(packet, pos)
                expected = len
                pos = p + 1 // версія протоколу
            }
            if (expected < 0 || pos > packet.size) return null
            buf.write(packet, pos, packet.size - pos)
            if (buf.size() < expected) return null
            val out = buf.toByteArray().copyOf(expected)
            buf.reset()
            expected = -1
            return out
        }
    }

    /**
     * Показники з повідомлення пристрою: dp id → значення (Boolean, Int, String чи ByteArray).
     * 0x8001 — id, тип, 1 байт довжини; 0x8003 — спершу час; 0x8004/0x8005 — спершу 2 байти номера й
     * прапорці (і час); 0x8006/0x8007 (V4) — з 4-го байта: id, 3 байти прапорців, тип, 2 байти довжини;
     * у каналі FD50 ([fd50]) V4 — інший формат (див. [fd50V4]).
     */
    fun datapoints(code: Int, data: ByteArray, fd50: Boolean = false): Map<Int, Any> = try {
        when (code) {
            RECEIVE_DP -> v3(data, 0)
            RECEIVE_TIME_DP -> v3(data, afterTime(data, 0))
            RECEIVE_SIGN_DP -> v3(data, 3)
            RECEIVE_SIGN_TIME_DP -> v3(data, afterTime(data, 3))
            RECEIVE_DP_V4, RECEIVE_TIME_DP_V4 -> if (fd50) fd50V4(data) ?: v4(data) else v4(data)
            else -> emptyMap()
        }
    } catch (e: IndexOutOfBoundsException) {
        emptyMap()
    }

    private fun v3(data: ByteArray, start: Int): Map<Int, Any> {
        val out = LinkedHashMap<Int, Any>()
        var pos = start
        while (data.size - pos >= 4) {
            val id = data[pos].toInt() and 0xFF
            val type = data[pos + 1].toInt() and 0xFF
            val len = data[pos + 2].toInt() and 0xFF
            pos += 3
            if (type > 5 || pos + len > data.size) break
            out[id] = value(type, data.copyOfRange(pos, pos + len))
            pos += len
        }
        return out
    }

    /**
     * TuyaOS FD50 (датчик TH05, замки Raykube): 4 байти (у замка нулі, у TH05 — лічильник), номер (1),
     * прапорці (2), далі id, тип, 2 байти довжини. null — кадр не розбирається повністю (тоді інший формат).
     */
    private fun fd50V4(data: ByteArray): Map<Int, Any>? {
        if (data.size < 11) return null
        val out = LinkedHashMap<Int, Any>()
        var pos = 7
        while (pos < data.size) {
            if (pos + 4 > data.size) return null
            val id = data[pos].toInt() and 0xFF
            val type = data[pos + 1].toInt() and 0xFF
            val len = u16(data, pos + 2)
            if (type > 5 || pos + 4 + len > data.size) return null
            out[id] = value(type, data.copyOfRange(pos + 4, pos + 4 + len))
            pos += 4 + len
        }
        return out
    }

    private fun v4(data: ByteArray): Map<Int, Any> {
        val out = LinkedHashMap<Int, Any>()
        var pos = if (data.size >= 4) 4 else 0
        while (data.size - pos >= 7) {
            val id = data[pos].toInt() and 0xFF
            val type = data[pos + 4].toInt() and 0xFF
            val len = u16(data, pos + 5)
            pos += 7
            if (type > 5 || pos + len > data.size) break
            out[id] = value(type, data.copyOfRange(pos, pos + len))
            pos += len
        }
        return out
    }

    /** Значення за типом Tuya: 1 — так/ні, 2/4 — число зі знаком, 3 — рядок, 0/5 — байти. */
    private fun value(type: Int, v: ByteArray): Any = when (type) {
        1 -> v.any { it.toInt() != 0 }
        2, 4 -> if (v.isEmpty()) 0 else v.drop(1).fold(v[0].toInt()) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        3 -> String(v)
        else -> v
    }

    /** Час: байт типу (0 — 13 цифр мс, 1 — 4 байти секунд), далі показники. */
    private fun afterTime(data: ByteArray, start: Int): Int = when (data[start].toInt()) {
        0 -> start + 1 + 13
        1 -> start + 1 + 4
        else -> data.size
    }

    /**
     * Що відповісти пристрою (null — нічого): запит часу — час (інакше деякі розривають з'єднання),
     * показники — підтвердження. [nowMs] і [tzMinutes] — для перевірки в тестах.
     */
    fun responseFor(code: Int, data: ByteArray, nowMs: Long, tzMinutes: Int): ByteArray? = when (code) {
        TIME1_REQ -> nowMs.toString().toByteArray() + int16(tzMinutes * 100 / 60)
        TIME2_REQ -> {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            byteArrayOf(
                (c.get(java.util.Calendar.YEAR) % 100).toByte(), (c.get(java.util.Calendar.MONTH) + 1).toByte(),
                c.get(java.util.Calendar.DAY_OF_MONTH).toByte(), c.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
                c.get(java.util.Calendar.MINUTE).toByte(), c.get(java.util.Calendar.SECOND).toByte(),
                ((c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7).toByte(),
            ) + int16(tzMinutes * 100 / 60)
        }
        RECEIVE_DP, RECEIVE_TIME_DP, RECEIVE_DP_V4, RECEIVE_TIME_DP_V4 -> ByteArray(0)
        RECEIVE_SIGN_DP, RECEIVE_SIGN_TIME_DP -> if (data.size >= 3) byteArrayOf(data[0], data[1], data[2], 0) else null
        else -> null
    }

    /** Запит прив'язки: uuid + 6 символів ключа + id пристрою, доповнено нулями до 44 байт. */
    fun pairRequest(uuid: String, localKey6: ByteArray, deviceId: String): ByteArray =
        (uuid.toByteArray() + localKey6 + deviceId.toByteArray()).let { it + ByteArray(maxOf(0, 44 - it.size)) }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun s32(b: ByteArray, at: Int) = (u16(b, at) shl 16) or u16(b, at + 2)
}

/** Що потрібно для розмови з Bluetooth-пристроєм Tuya (з хмари, один раз). */
data class BleCredentials(val mac: String, val localKey: String, val uuid: String, val deviceId: String)

/**
 * Спіді як Bluetooth-шлюз: підключитися до пристрою Tuya BLE, привітатися ключем із хмари й прочитати
 * показники (dp id → значення). Bluetooth вимкнено — вмикає. Одна розмова за раз.
 */
class TuyaBleClient(private val context: Context) {
    private val mutex = Mutex()
    /** Адреса → варіант привітання, на який пристрій відповів (до перезапуску). */
    private val goodHello = HashMap<String, TuyaBleProtocol.Hello>()

    @SuppressLint("MissingPermission")
    suspend fun read(creds: BleCredentials, timeoutMs: Long = 35_000): Map<Int, Any>? = mutex.withLock {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        if (!adapter.isEnabled) {
            Log.i(TAG, "Bluetooth вимкнено — вмикаю")
            adapter.enable()
            withTimeoutOrNull(8_000) { while (!adapter.isEnabled) delay(200) } ?: return null
            delay(500)
        }
        val mac = creds.mac.uppercase().let { m -> if (':' in m) m else m.chunked(2).joinToString(":") }
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return null
        // Samsung на Android 7 до незнайденого в пошуку пристрою не підключається (помилка 133) — спершу знайти.
        // TH05 приймає з'єднання лише одразу після своєї реклами: не вдалось — знову чекати реклами й одразу
        // підключатися (кілька разів).
        var found: android.bluetooth.BluetoothDevice? = null
        for (attempt in 1..DIRECT_ATTEMPTS) {
            found = scan(adapter, mac, if (attempt == 1) 90_000 else 30_000) ?: break
            delay(300) // після зупинки пошуку стек має звільнитися
            Session(found, creds, autoConnect = false).run(timeoutMs)?.let { return it }
            if (attempt < DIRECT_ATTEMPTS) Log.i(TAG, "спроба $attempt не вдалась — чекаю наступної реклами")
        }
        found ?: return null.also { Log.w(TAG, "$mac не видно поруч (далеко чи сів)") }
        // Датчик на батарейці приймає з'єднання короткими вікнами — хай стек сам чекає.
        Log.i(TAG, "остання спроба: чекаю, доки датчик прийме з'єднання")
        delay(1_000)
        Session(found, creds, autoConnect = true).run(timeoutMs + 10_000)
    }

    /** Пошук пристрою за адресою (до [ms]); null — не видно. */
    @SuppressLint("MissingPermission")
    private suspend fun scan(adapter: BluetoothAdapter, mac: String, ms: Long = 90_000): android.bluetooth.BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val result = CompletableDeferred<android.bluetooth.BluetoothDevice>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, r: android.bluetooth.le.ScanResult) {
                if (!r.device.address.equals(mac, ignoreCase = true)) return
                val rec = r.scanRecord
                // Дані служби: перший байт — прапорці (спостереження TH05: 0x49 — звичайний, 0x41 — режим спарювання).
                Log.i(TAG, "знайшла $mac, сигнал ${r.rssi} дБм; реклама: служби ${rec?.serviceUuids}, " +
                    "дані служб ${rec?.serviceData?.mapValues { it.value.joinToString("") { b -> "%02x".format(b) } }}, " +
                    "виробник ${rec?.manufacturerSpecificData?.let { m -> (0 until m.size()).associate { m.keyAt(it) to m.valueAt(it).joinToString("") { b -> "%02x".format(b) } } }}")
                result.complete(r.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "пошук не вдався: $errorCode")
            }
        }
        val settings = android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(mac).build())
        scanner.startScan(filters, settings, cb)
        return try {
            withTimeoutOrNull(ms) { result.await() }
        } finally {
            try { scanner.stopScan(cb) } catch (_: Exception) {}
        }
    }

    private inner class Session(
        private val device: android.bluetooth.BluetoothDevice,
        private val creds: BleCredentials,
        private val autoConnect: Boolean,
    ) {
        private val connected = CompletableDeferred<Boolean>()
        private val ready = CompletableDeferred<Boolean>()
        private var written = CompletableDeferred<Boolean>()
        private val mtuDone = CompletableDeferred<Int>()
        private val messages = Channel<TuyaBleProtocol.Message>(Channel.UNLIMITED)
        private val reassembler = TuyaBleProtocol.Reassembler()
        private val localKey6 = creds.localKey.take(6).toByteArray()
        private val loginKey = TuyaBleProtocol.md5(localKey6)
        @Volatile private var sessionKey: ByteArray? = null
        private var protocolVersion = 2
        private var seq = 1
        private var gatt: BluetoothGatt? = null
        private var write: BluetoothGattCharacteristic? = null
        private var notify: BluetoothGattCharacteristic? = null
        /** Новіший канал (служба FD50, характеристики …07d0): інакше привітання. */
        private var fd50 = false

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "з'єднання: стан $newState, статус $status")
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else { connected.complete(false); ready.complete(false); mtuDone.complete(0); messages.close() }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                Log.i(TAG, "служби: " + g.services.joinToString("; ") { s ->
                    "${s.uuid.short()}[" + s.characteristics.joinToString(",") { c -> "${c.uuid.short()}/${c.properties}" } + "]"
                })
                // Старі пристрої — служба 1910 (2b10 / 2b11); новіші (TuyaOS) — FD50 з характеристиками …07d0.
                val service = g.getService(SERVICE) ?: g.services.firstOrNull { s -> s.uuid.short() in TUYA_SERVICES }
                    ?: g.services.firstOrNull { s -> s.uuid.short() !in STANDARD_SERVICES && s.characteristics.any { it.properties and NOTIFY_PROPS != 0 } }
                    ?: return run { Log.w(TAG, "немає служби Tuya BLE"); connected.complete(false) }
                fd50 = service.uuid.short() == "fd50"
                notify = service.getCharacteristic(NOTIFY) ?: service.characteristics.firstOrNull { it.properties and NOTIFY_PROPS != 0 }
                write = service.getCharacteristic(WRITE) ?: service.characteristics.firstOrNull { it.properties and WRITE_PROPS != 0 && it !== notify }
                    ?: service.characteristics.firstOrNull { it.properties and WRITE_PROPS != 0 }
                Log.i(TAG, "служба ${service.uuid.short()}: сповіщення ${notify?.uuid?.short()}, запис ${write?.uuid?.short()}")
                connected.complete(notify != null && write != null)
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "MTU $mtu, статус $status")
                mtuDone.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                Log.d(TAG, "сповіщення ввімкнено: статус $status")
                ready.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "запис: статус $status")
                written.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                Log.d(TAG, "отримала ${c.value?.size} Б")
                val full = reassembler.feed(c.value ?: return) ?: return
                val msg = try {
                    TuyaBleProtocol.decrypt(full) { flag -> if (flag == TuyaBleProtocol.FLAG_LOGIN) loginKey else if (flag == TuyaBleProtocol.FLAG_SESSION) sessionKey else null }
                } catch (e: Exception) { null }
                if (msg == null) Log.w(TAG, "не розшифрувала повідомлення (${full.size} Б, прапорець ${full.firstOrNull()})")
                else {
                    Log.d(TAG, "повідомлення 0x%04x, ${msg.data.size} Б: ${msg.data.joinToString("") { "%02x".format(it) }}".format(msg.code))
                    messages.trySend(msg)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private suspend fun send(code: Int, data: ByteArray, responseTo: Int = 0, hello: TuyaBleProtocol.Hello? = null): Boolean {
            val flag = if (code == TuyaBleProtocol.DEVICE_INFO) TuyaBleProtocol.FLAG_LOGIN else TuyaBleProtocol.FLAG_SESSION
            val key = (if (flag == TuyaBleProtocol.FLAG_LOGIN) loginKey else sessionKey) ?: return false
            val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val c = write ?: return false
            val g = gatt ?: return false
            val packets = TuyaBleProtocol.buildPackets(
                seq++, code, data, responseTo, flag, key, iv,
                protocolVersion = hello?.version ?: protocolVersion,
                chunk = hello?.chunk ?: TuyaBleProtocol.MTU,
            )
            val noResponse = hello?.withResponse != true && c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            for (p in packets) {
                written = CompletableDeferred()
                c.value = p
                c.writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!g.writeCharacteristic(c)) return false
                withTimeoutOrNull(2_000) { written.await() } ?: return false
            }
            return true
        }

        /** Відповісти на запит пристрою (час, підтвердження показників) — інакше він може розірвати зв'язок. */
        private suspend fun answer(m: TuyaBleProtocol.Message) {
            val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
            TuyaBleProtocol.responseFor(m.code, m.data, System.currentTimeMillis(), tz)?.let { send(m.code, it, responseTo = m.seq) }
        }

        /** Дочекатися повідомлення з кодом; дорогою — зібрати показники й відповісти на запити. */
        private suspend fun await(code: Int, dps: MutableMap<Int, Any>, ms: Long): TuyaBleProtocol.Message? = withTimeoutOrNull(ms) {
            for (m in messages) {
                dps += TuyaBleProtocol.datapoints(m.code, m.data, fd50)
                answer(m)
                if (m.code == code) return@withTimeoutOrNull m
            }
            null
        }

        @SuppressLint("MissingPermission")
        suspend fun run(timeoutMs: Long): Map<Int, Any>? {
            val started = android.os.SystemClock.uptimeMillis()
            // Явно LE: без цього Samsung пробує класичний Bluetooth і віддає помилку 133.
            gatt = device.connectGatt(context, autoConnect, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            try {
                return withTimeoutOrNull(timeoutMs) {
                    if (!connected.await()) return@withTimeoutOrNull null.also { Log.w(TAG, "не підключилась до ${device.address}") }
                    val g = gatt ?: return@withTimeoutOrNull null
                    // FD50: спершу більший MTU — привітання йде одним записом.
                    if (fd50 && g.requestMtu(247)) withTimeoutOrNull(3_000) { mtuDone.await() }
                    val n = notify ?: return@withTimeoutOrNull null
                    g.setCharacteristicNotification(n, true)
                    n.getDescriptor(CCCD)?.let { d ->
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                        if (withTimeoutOrNull(3_000) { ready.await() } != true) return@withTimeoutOrNull null.also { Log.w(TAG, "сповіщення не ввімкнулись") }
                    }
                    val dps = LinkedHashMap<Int, Any>()
                    // 1. Відомості про пристрій (ключ входу) → srand → сеансовий ключ.
                    // FD50: спершу варіант, що спрацював минулого разу, далі решта.
                    val hellos = if (!fd50) listOf(TuyaBleProtocol.Hello(ByteArray(0), 2, TuyaBleProtocol.MTU))
                        else listOfNotNull(goodHello[device.address]) + TuyaBleProtocol.FD50_HELLOS.filter { it != goodHello[device.address] }
                    var info: TuyaBleProtocol.Message? = null
                    for (h in hellos) {
                        if (!send(TuyaBleProtocol.DEVICE_INFO, h.data, hello = h)) { Log.w(TAG, "привітання ($h) не надіслалось"); break }
                        info = await(TuyaBleProtocol.DEVICE_INFO, dps, if (hellos.size > 1) 3_000 else 6_000)
                        if (info != null) { goodHello[device.address] = h; Log.i(TAG, "відповів на привітання: $h"); break }
                        Log.w(TAG, "мовчить на привітання: $h")
                    }
                    info ?: return@withTimeoutOrNull null.also { Log.w(TAG, "пристрій не відповів на привітання") }
                    if (info.data.size < 12) return@withTimeoutOrNull null
                    protocolVersion = info.data[2].toInt()
                    sessionKey = TuyaBleProtocol.md5(localKey6 + info.data.copyOfRange(6, 12))
                    Log.i(TAG, "привіталась: версія пристрою ${info.data[0]}.${info.data[1]}, протокол ${info.data[2]}.${info.data[3]}")
                    // 2. Вхід (прив'язка тим самим ключем, що й у Smart Life).
                    send(TuyaBleProtocol.PAIR, TuyaBleProtocol.pairRequest(creds.uuid, localKey6, creds.deviceId))
                    val pair = await(TuyaBleProtocol.PAIR, dps, 5_000)
                    val result = pair?.data?.firstOrNull()?.toInt()
                    if (result != 0 && result != 2) return@withTimeoutOrNull null.also { Log.w(TAG, "вхід не вдався: $result") }
                    // 3. Стан: пристрій надсилає всі показники (і, можливо, просить час).
                    send(TuyaBleProtocol.DEVICE_STATUS, ByteArray(0))
                    withTimeoutOrNull(5_000) {
                        for (m in messages) {
                            dps += TuyaBleProtocol.datapoints(m.code, m.data, fd50)
                            answer(m)
                            if (dps.isNotEmpty()) break
                        }
                    }
                    // Решта показників ідуть слідом.
                    withTimeoutOrNull(1_500) { for (m in messages) { dps += TuyaBleProtocol.datapoints(m.code, m.data, fd50); answer(m) } }
                    Log.i(TAG, "${device.address}: протокол $protocolVersion, показники $dps за ${android.os.SystemClock.uptimeMillis() - started} мс")
                    dps
                }
            } finally {
                gatt?.disconnect()
                gatt?.close()
            }
        }
    }

    companion object {
        private const val TAG = "TuyaBle"
        /** Скільки разів "знайти → одразу підключитися" перед запасним autoConnect. */
        private const val DIRECT_ATTEMPTS = 3
        val SERVICE: UUID = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("00002b10-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("00002b11-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Служби Tuya за коротким номером; стандартні (GAP, GATT, відомості, батарея) — не наші. */
        private val TUYA_SERVICES = setOf("1910", "fd50", "a201")
        private val STANDARD_SERVICES = setOf("1800", "1801", "180a", "180f")
        private const val NOTIFY_PROPS = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        private const val WRITE_PROPS = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        /** 0000fd50-0000-1000-8000-00805f9b34fb → "fd50"; нестандартні — повністю. */
        private fun UUID.short(): String = toString().let { if (it.endsWith("-0000-1000-8000-00805f9b34fb")) it.substring(4, 8) else it }
    }
}
