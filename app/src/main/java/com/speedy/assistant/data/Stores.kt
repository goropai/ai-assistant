package com.speedy.assistant.data

import com.speedy.assistant.util.writeTextAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Сховища даних про людей і тварин. На телефоні — одна база SQLite ([SpeedyDb]): людина — рядок,
 * її обличчя, голоси й профіль посилаються на неї (перейменування — один рядок). JSON-реалізації —
 * для тестів без Android і для перенесення старих файлів у базу.
 *
 * Зберігаємо лише змінене: `save*(змінені)`, порожній список / null — видалити.
 */
interface FaceStore {
    fun loadFaces(): Map<String, List<FloatArray>>
    fun saveFaces(changed: Map<String, List<FloatArray>>)
}

interface ProfileStore {
    fun loadProfiles(): Map<String, JSONObject>
    fun saveProfiles(changed: Map<String, JSONObject?>)
}

/** Відбитки голосу (тембр) — як обличчя: ім'я → кілька векторів. */
interface VoiceStore {
    fun loadVoices(): Map<String, List<FloatArray>>
    fun saveVoices(changed: Map<String, List<FloatArray>>)
}

/** Тварини: ім'я, вид, колірний відбиток. Список короткий — зберігаємо цілком. */
data class PetRecord(val name: String, val kind: String, val signature: FloatArray)

interface PetStore {
    fun loadPets(): List<PetRecord>
    fun savePets(all: List<PetRecord>)
}

internal fun JSONArray.toFloats() = FloatArray(length()) { getDouble(it).toFloat() }
internal fun FloatArray.toJson() = JSONArray(map { it.toDouble() })

/** Обличчя чи голоси у JSON-файлі: {ім'я: [[...], [...]]}. */
class JsonVectorFile(private val file: File?) : FaceStore, VoiceStore {
    private val all = LinkedHashMap<String, List<FloatArray>>()

    init {
        file?.takeIf { it.exists() }?.let {
            try {
                val json = JSONObject(it.readText())
                for (name in json.keys()) {
                    val arr = json.getJSONArray(name)
                    all[name] = List(arr.length()) { i -> arr.getJSONArray(i).toFloats() }
                }
            } catch (e: Exception) {
                all.clear() // зіпсований файл — краще забути, ніж впасти
            }
        }
    }

    private fun load(): Map<String, List<FloatArray>> = LinkedHashMap(all)

    private fun save(changed: Map<String, List<FloatArray>>) {
        changed.forEach { (name, v) -> if (v.isEmpty()) all.remove(name) else all[name] = v }
        val f = file ?: return
        val json = JSONObject()
        all.forEach { (name, v) -> json.put(name, JSONArray(v.map { it.toJson() })) }
        f.writeTextAtomic(json.toString())
    }

    override fun loadFaces() = load()
    override fun saveFaces(changed: Map<String, List<FloatArray>>) = save(changed)
    override fun loadVoices() = load()
    override fun saveVoices(changed: Map<String, List<FloatArray>>) = save(changed)
}

/** Профілі у JSON-файлі: {ім'я: {...}}. */
class JsonProfileFile(private val file: File?) : ProfileStore {
    private val all = LinkedHashMap<String, JSONObject>()

    init {
        file?.takeIf { it.exists() }?.let {
            try {
                val json = JSONObject(it.readText())
                for (name in json.keys()) all[name] = json.getJSONObject(name)
            } catch (e: Exception) {
                all.clear()
            }
        }
    }

    override fun loadProfiles(): Map<String, JSONObject> = LinkedHashMap(all)

    override fun saveProfiles(changed: Map<String, JSONObject?>) {
        changed.forEach { (name, o) -> if (o == null) all.remove(name) else all[name] = o }
        val f = file ?: return
        val json = JSONObject()
        all.forEach { (name, o) -> json.put(name, o) }
        f.writeTextAtomic(json.toString())
    }
}

/** Тварини у JSON-файлі: [{name, kind, signature}]. */
class JsonPetFile(private val file: File?) : PetStore {
    override fun loadPets(): List<PetRecord> {
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PetRecord(o.getString("name"), o.getString("kind"), o.getJSONArray("signature").toFloats())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun savePets(all: List<PetRecord>) {
        val f = file ?: return
        f.writeTextAtomic(JSONArray(all.map { JSONObject().put("name", it.name).put("kind", it.kind).put("signature", it.signature.toJson()) }).toString())
    }
}
