package com.speedy.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * База Спіді (files/../databases/speedy.db) — люди й тварини:
 *   person(id, name)            — людина; решта посилається на неї за id
 *   face(person_id, vector)     — відбитки обличчя (MobileFaceNet)
 *   voice(person_id, vector)    — відбитки голосу (тембр)
 *   profile(person_id, data)    — профіль (факти, звички, вік, зріст, тон, одяг) — JSON
 *   pet(name, kind, signature)  — тварини
 * Людина без облич, голосів і профілю видаляється. Усі зміни одного виклику — одна транзакція.
 * Лише на телефоні, нікуди не надсилається.
 */
class SpeedyDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION), FaceStore, ProfileStore, VoiceStore, PetStore {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE person (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE face (id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL REFERENCES person(id) ON DELETE CASCADE, vector BLOB NOT NULL)")
        db.execSQL("CREATE TABLE voice (id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL REFERENCES person(id) ON DELETE CASCADE, vector BLOB NOT NULL)")
        db.execSQL("CREATE TABLE profile (person_id INTEGER PRIMARY KEY REFERENCES person(id) ON DELETE CASCADE, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE pet (id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, signature BLOB NOT NULL)")
        db.execSQL("CREATE INDEX face_person ON face(person_id)")
        db.execSQL("CREATE INDEX voice_person ON voice(person_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Перша версія — міграцій ще немає.
    }

    // --- обличчя й голоси ---

    override fun loadFaces() = loadVectors("face")
    override fun saveFaces(changed: Map<String, List<FloatArray>>) = saveVectors("face", changed)
    override fun loadVoices() = loadVectors("voice")
    override fun saveVoices(changed: Map<String, List<FloatArray>>) = saveVectors("voice", changed)

    private fun loadVectors(table: String): Map<String, List<FloatArray>> {
        val out = LinkedHashMap<String, MutableList<FloatArray>>()
        readableDatabase.rawQuery("SELECT p.name, v.vector FROM $table v JOIN person p ON p.id = v.person_id ORDER BY v.id", null).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { mutableListOf() } += fromBlob(c.getBlob(1))
        }
        return out
    }

    @Synchronized
    private fun saveVectors(table: String, changed: Map<String, List<FloatArray>>) = transaction { db ->
        for ((name, vectors) in changed) {
            val id = if (vectors.isEmpty()) idOf(db, name) ?: continue else personId(db, name)
            db.delete(table, "person_id = ?", arrayOf(id.toString()))
            for (v in vectors) db.insert(table, null, ContentValues().apply { put("person_id", id); put("vector", toBlob(v)) })
            if (vectors.isEmpty()) dropIfEmpty(db, id)
        }
    }

    // --- профілі ---

    override fun loadProfiles(): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        readableDatabase.rawQuery("SELECT p.name, f.data FROM profile f JOIN person p ON p.id = f.person_id ORDER BY p.id", null).use { c ->
            while (c.moveToNext()) {
                try { out[c.getString(0)] = JSONObject(c.getString(1)) } catch (e: Exception) { Log.w(TAG, "профіль ${c.getString(0)} пошкоджено") }
            }
        }
        return out
    }

    @Synchronized
    override fun saveProfiles(changed: Map<String, JSONObject?>) = transaction { db ->
        for ((name, data) in changed) {
            if (data == null) {
                val id = idOf(db, name) ?: continue
                db.delete("profile", "person_id = ?", arrayOf(id.toString()))
                dropIfEmpty(db, id)
            } else {
                val id = personId(db, name)
                db.insertWithOnConflict("profile", null, ContentValues().apply { put("person_id", id); put("data", data.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    // --- тварини ---

    override fun loadPets(): List<PetRecord> {
        val out = mutableListOf<PetRecord>()
        readableDatabase.rawQuery("SELECT name, kind, signature FROM pet ORDER BY id", null).use { c ->
            while (c.moveToNext()) out += PetRecord(c.getString(0), c.getString(1), fromBlob(c.getBlob(2)))
        }
        return out
    }

    @Synchronized
    override fun savePets(all: List<PetRecord>) = transaction { db ->
        db.delete("pet", null, null)
        for (p in all) db.insert("pet", null, ContentValues().apply { put("name", p.name); put("kind", p.kind); put("signature", toBlob(p.signature)) })
    }

    // --- службове ---

    private fun idOf(db: SQLiteDatabase, name: String): Long? =
        db.rawQuery("SELECT id FROM person WHERE name = ?", arrayOf(name)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun personId(db: SQLiteDatabase, name: String): Long =
        idOf(db, name) ?: db.insertOrThrow("person", null, ContentValues().apply { put("name", name); put("created", System.currentTimeMillis()) })

    /** Нічого про людину не лишилось — і саму людину прибрати. */
    private fun dropIfEmpty(db: SQLiteDatabase, id: Long) {
        val left = db.rawQuery(
            "SELECT (SELECT COUNT(*) FROM face WHERE person_id = ?1) + (SELECT COUNT(*) FROM voice WHERE person_id = ?1) + (SELECT COUNT(*) FROM profile WHERE person_id = ?1)",
            arrayOf(id.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (left == 0) db.delete("person", "id = ?", arrayOf(id.toString()))
    }

    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Перший запуск з базою: дані зі старих JSON (обличчя, профілі, тварини) — у базу, одна
     * транзакція; файли перейменовуються на *.migrated (лишаються як резервна копія).
     */
    fun importJson(faces: File, profiles: File, pets: File) {
        val empty = readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM person) + (SELECT COUNT(*) FROM pet)", null)
            .use { it.moveToFirst() && it.getInt(0) == 0 }
        if (!empty || listOf(faces, profiles, pets).none { it.exists() }) return
        val f = JsonVectorFile(faces).loadFaces()
        val p = JsonProfileFile(profiles).loadProfiles()
        val pt = JsonPetFile(pets).loadPets()
        // Усе або нічого: вимкнулись посередині — база порожня, наступного разу перенесемо знову.
        val db = writableDatabase
        db.beginTransaction()
        try {
            saveFaces(f) // вкладені транзакції — частина цієї
            saveProfiles(p)
            savePets(pt)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "перенесено в базу: ${f.size} облич, ${p.size} профілів, ${pt.size} тварин")
        listOf(faces, profiles, pets).filter { it.exists() }.forEach { it.renameTo(File(it.path + ".migrated")) }
    }

    private companion object {
        const val TAG = "SpeedyDb"
        const val NAME = "speedy.db"
        const val VERSION = 1

        fun toBlob(v: FloatArray): ByteArray =
            ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

        fun fromBlob(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.getFloat() }
        }
    }
}
