package com.dnd.blocker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Filtered(
    val id: Long, val address: String, val body: String,
    val date: Long, val verdict: String, val reason: String,
    /** Db.SOURCE_SMS (we received it as the SMS app) or Db.SOURCE_NOTIFICATION (gatekeeper). */
    val source: String = Db.SOURCE_SMS,
)

data class DismissedNotification(
    val id: Long, val pkg: String, val app: String, val title: String,
    val text: String, val date: Long, val reason: String,
)

data class BlockedCall(val id: Long, val number: String, val date: Long, val reason: String)

/** Local database for filtered messages, blocked calls and the user's allow/deny lists. */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "dnd.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE filtered(id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, body TEXT, date INTEGER, verdict TEXT, reason TEXT, source TEXT NOT NULL DEFAULT 'sms')")
        db.execSQL("CREATE TABLE blocked_calls(id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT, date INTEGER, reason TEXT)")
        db.execSQL("CREATE TABLE whitelist(number TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE blacklist(number TEXT PRIMARY KEY)")
        createModelTables(db)
        createNotificationTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createModelTables(db)
        if (oldVersion < 3) createNotificationTables(db)
        if (oldVersion < 4) db.execSQL("ALTER TABLE filtered ADD COLUMN source TEXT NOT NULL DEFAULT 'sms'")
    }

    private fun createNotificationTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE app_rules(pkg TEXT PRIMARY KEY, mode TEXT NOT NULL)")
        db.execSQL("CREATE TABLE dismissed(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, app TEXT, title TEXT, text TEXT, date INTEGER, reason TEXT)")
    }

    fun appRules(): Map<String, AppMode> =
        readableDatabase.rawQuery("SELECT pkg, mode FROM app_rules", null).use { c ->
            buildMap { while (c.moveToNext()) runCatching { put(c.getString(0), AppMode.valueOf(c.getString(1))) } }
        }

    fun setAppRule(pkg: String, mode: AppMode?) {
        if (mode == null) writableDatabase.delete("app_rules", "pkg=?", arrayOf(pkg))
        else writableDatabase.insertWithOnConflict(
            "app_rules", null,
            ContentValues().apply { put("pkg", pkg); put("mode", mode.name) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun addDismissed(pkg: String, app: String, title: String, text: String, reason: String) {
        writableDatabase.insert("dismissed", null, ContentValues().apply {
            put("pkg", pkg); put("app", app); put("title", title); put("text", text)
            put("date", System.currentTimeMillis()); put("reason", reason)
        })
        // Keep the log small.
        writableDatabase.execSQL("DELETE FROM dismissed WHERE id NOT IN (SELECT id FROM dismissed ORDER BY date DESC LIMIT 300)")
    }

    fun dismissed(): List<DismissedNotification> =
        readableDatabase.rawQuery("SELECT id, pkg, app, title, text, date, reason FROM dismissed ORDER BY date DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    DismissedNotification(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getString(6))
                )
            }
        }

    fun deleteDismissed(id: Long) { writableDatabase.delete("dismissed", "id=?", arrayOf(id.toString())) }

    fun clearDismissed() { writableDatabase.delete("dismissed", null, null) }

    private fun createModelTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE nb_tokens(token TEXT PRIMARY KEY, spam INTEGER NOT NULL DEFAULT 0, ham INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE nb_docs(id INTEGER PRIMARY KEY CHECK (id = 1), spam INTEGER NOT NULL, ham INTEGER NOT NULL)")
        db.execSQL("INSERT INTO nb_docs(id, spam, ham) VALUES (1, 0, 0)")
    }

    /** Adds the user's saved learning on top of a (seeded) model. */
    fun loadModelInto(m: SpamModel) {
        readableDatabase.rawQuery("SELECT token, spam, ham FROM nb_tokens", null).use { c ->
            while (c.moveToNext()) m.addCount(c.getString(0), c.getInt(1), c.getInt(2))
        }
        readableDatabase.rawQuery("SELECT spam, ham FROM nb_docs WHERE id=1", null).use { c ->
            if (c.moveToFirst()) m.addDocs(c.getInt(0), c.getInt(1), fromUser = true)
        }
    }

    fun saveTraining(tokens: Set<String>, isSpam: Boolean) {
        val col = if (isSpam) "spam" else "ham"
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in tokens) {
                db.execSQL("INSERT OR IGNORE INTO nb_tokens(token) VALUES (?)", arrayOf(t))
                db.execSQL("UPDATE nb_tokens SET $col = $col + 1 WHERE token = ?", arrayOf(t))
            }
            db.execSQL("UPDATE nb_docs SET $col = $col + 1 WHERE id = 1")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun resetModel() {
        writableDatabase.delete("nb_tokens", null, null)
        writableDatabase.execSQL("UPDATE nb_docs SET spam = 0, ham = 0 WHERE id = 1")
    }

    fun addFiltered(address: String, body: String, date: Long, result: FilterResult, source: String = SOURCE_SMS) {
        writableDatabase.insert("filtered", null, ContentValues().apply {
            put("address", address); put("body", body); put("date", date)
            put("verdict", result.verdict.name); put("reason", result.reason); put("source", source)
        })
    }

    fun filtered(): List<Filtered> =
        readableDatabase.rawQuery(
            "SELECT id,address,body,date,verdict,reason,source FROM filtered ORDER BY date DESC", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Filtered(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getString(6))
                )
            }
        }

    fun filteredCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM filtered", null).use { it.moveToFirst(); it.getInt(0) }

    fun deleteFiltered(id: Long) { writableDatabase.delete("filtered", "id=?", arrayOf(id.toString())) }

    fun clearFiltered() { writableDatabase.delete("filtered", null, null) }

    fun addBlockedCall(number: String, reason: String) {
        writableDatabase.insert("blocked_calls", null, ContentValues().apply {
            put("number", number); put("date", System.currentTimeMillis()); put("reason", reason)
        })
    }

    fun blockedCalls(): List<BlockedCall> =
        readableDatabase.rawQuery("SELECT id,number,date,reason FROM blocked_calls ORDER BY date DESC LIMIT 500", null).use { c ->
            buildList { while (c.moveToNext()) add(BlockedCall(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3))) }
        }

    fun clearBlockedCalls() { writableDatabase.delete("blocked_calls", null, null) }

    private fun inList(table: String, number: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM $table WHERE number=?", arrayOf(number)).use { it.moveToFirst() }

    private fun addToList(table: String, number: String) {
        writableDatabase.insertWithOnConflict(
            table, null, ContentValues().apply { put("number", number) }, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun isWhitelisted(number: String) = inList("whitelist", number)
    fun isBlacklisted(number: String) = inList("blacklist", number)
    fun whitelist(number: String) { addToList("whitelist", number); writableDatabase.delete("blacklist", "number=?", arrayOf(number)) }
    fun blacklist(number: String) { addToList("blacklist", number); writableDatabase.delete("whitelist", "number=?", arrayOf(number)) }

    companion object {
        const val SOURCE_SMS = "sms"
        const val SOURCE_NOTIFICATION = "notification"
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db = instance ?: synchronized(this) { instance ?: Db(ctx).also { instance = it } }
    }
}
