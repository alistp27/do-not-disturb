package com.dnd.blocker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Filtered(
    val id: Long, val address: String, val body: String,
    val date: Long, val verdict: String, val reason: String,
)

data class BlockedCall(val id: Long, val number: String, val date: Long, val reason: String)

/** Local database for filtered messages, blocked calls and the user's allow/deny lists. */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "dnd.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE filtered(id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, body TEXT, date INTEGER, verdict TEXT, reason TEXT)")
        db.execSQL("CREATE TABLE blocked_calls(id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT, date INTEGER, reason TEXT)")
        db.execSQL("CREATE TABLE whitelist(number TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE blacklist(number TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun addFiltered(address: String, body: String, date: Long, result: FilterResult) {
        writableDatabase.insert("filtered", null, ContentValues().apply {
            put("address", address); put("body", body); put("date", date)
            put("verdict", result.verdict.name); put("reason", result.reason)
        })
    }

    fun filtered(): List<Filtered> =
        readableDatabase.rawQuery(
            "SELECT id,address,body,date,verdict,reason FROM filtered ORDER BY date DESC", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Filtered(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5))
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
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db = instance ?: synchronized(this) { instance ?: Db(ctx).also { instance = it } }
    }
}
