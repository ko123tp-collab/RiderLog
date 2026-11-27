package com.rider.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 모든 기록은 폰 안의 SQLite 파일 하나에 저장된다. */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "rider.db", null, 1) {

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }

        /** 이 거리 안이면 같은 가게로 인식 (미터) */
        const val STORE_RADIUS_M = 60.0

        fun dist(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE shifts(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER NOT NULL, end_ts INTEGER)")
        db.execSQL("CREATE TABLE stores(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, shift_id INTEGER NOT NULL, ts INTEGER NOT NULL, type TEXT NOT NULL, lat REAL, lng REAL, store_id INTEGER, amount INTEGER)")
        db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT, shift_id INTEGER NOT NULL, ts INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, speed REAL)")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
        db.execSQL("CREATE INDEX idx_points_ts ON points(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- 운행 ----------

    fun openShiftId(): Long? {
        readableDatabase.rawQuery("SELECT id FROM shifts WHERE end_ts IS NULL ORDER BY id DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    fun openShiftStart(): Long? {
        readableDatabase.rawQuery("SELECT start_ts FROM shifts WHERE end_ts IS NULL ORDER BY id DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    fun startShift(ts: Long): Long {
        openShiftId()?.let { return it }
        val cv = ContentValues()
        cv.put("start_ts", ts)
        return writableDatabase.insert("shifts", null, cv)
    }

    fun endShift(ts: Long) {
        val cv = ContentValues()
        cv.put("end_ts", ts)
        writableDatabase.update("shifts", cv, "end_ts IS NULL", null)
    }

    // ---------- GPS ----------

    fun addPoint(shift: Long, ts: Long, lat: Double, lng: Double, speed: Float?) {
        val cv = ContentValues()
        cv.put("shift_id", shift)
        cv.put("ts", ts)
        cv.put("lat", lat)
        cv.put("lng", lng)
        if (speed != null) cv.put("speed", speed.toDouble())
        writableDatabase.insert("points", null, cv)
    }

    // ---------- 버튼 기록 ----------

    fun addEvent(shift: Long, ts: Long, type: String, lat: Double?, lng: Double?): Long {
        val cv = ContentValues()
        cv.put("shift_id", shift)
        cv.put("ts", ts)
        cv.put("type", type)
        if (lat != null) cv.put("lat", lat)
        if (lng != null) cv.put("lng", lng)
        return writableDatabase.insert("events", null, cv)
    }

    fun setEventStore(eventId: Long, storeId: Long) {
        val cv = ContentValues()
        cv.put("store_id", storeId)
        writableDatabase.update("events", cv, "id=?", arrayOf(eventId.toString()))
    }

    fun setAmount(eventId: Long, amount: Int?) {
        val cv = ContentValues()
        if (amount == null) cv.putNull("amount") else cv.put("amount", amount)
        writableDatabase.update("events", cv, "id=?", arrayOf(eventId.toString()))
    }

    fun deleteEvent(eventId: Long) {
        writableDatabase.delete("events", "id=?", arrayOf(eventId.toString()))
    }

    /** 이번 운행의 마지막 기록을 지우고 그 종류를 돌려준다 */
    fun deleteLastEvent(shift: Long): String? {
        readableDatabase.rawQuery(
            "SELECT id, type FROM events WHERE shift_id=? ORDER BY ts DESC, id DESC LIMIT 1",
            arrayOf(shift.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val type = c.getString(1)
            deleteEvent(id)
            return type
        }
    }

    fun countEvents(shift: Long, type: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE shift_id=? AND type=?",
            arrayOf(shift.toString(), type)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 최근 '가게 도착'에 붙은 가게 (픽업 위치에서 가게를 못 찾았을 때 사용) */
    fun recentArriveStore(shift: Long, since: Long): Long? {
        readableDatabase.rawQuery(
            "SELECT store_id FROM events WHERE shift_id=? AND type='ARRIVE' AND ts>=? AND store_id IS NOT NULL ORDER BY ts DESC LIMIT 1",
            arrayOf(shift.toString(), since.toString())
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else null }
    }

    // ---------- 가게 ----------

    fun nearestStore(lat: Double, lng: Double): Pair<Long, String>? {
        var best: Pair<Long, String>? = null
        var bestDist = STORE_RADIUS_M
        readableDatabase.rawQuery("SELECT id, name, lat, lng FROM stores", null).use { c ->
            while (c.moveToNext()) {
                val d = dist(lat, lng, c.getDouble(2), c.getDouble(3))
                if (d <= bestDist) {
                    bestDist = d
                    best = Pair(c.getLong(0), c.getString(1))
                }
            }
        }
        return best
    }

    fun addStore(name: String, lat: Double, lng: Double): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("lat", lat)
        cv.put("lng", lng)
        return writableDatabase.insert("stores", null, cv)
    }

    fun renameStore(id: Long, name: String) {
        val cv = ContentValues()
        cv.put("name", name)
        writableDatabase.update("stores", cv, "id=?", arrayOf(id.toString()))
    }

    fun unnamedCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM stores WHERE name LIKE '이름 없음%'", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ---------- 화면용 데이터 ----------

    private fun storesJson(): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery("SELECT id, name, lat, lng FROM stores ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val j = JSONObject()
                j.put("id", c.getLong(0))
                j.put("name", c.getString(1))
                j.put("lat", c.getDouble(2))
                j.put("lng", c.getDouble(3))
                arr.put(j)
            }
        }
        return arr
    }

    private fun shiftsJson(from: Long, to: Long): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, start_ts, end_ts FROM shifts WHERE start_ts < ? AND (end_ts IS NULL OR end_ts > ?) ORDER BY start_ts",
            arrayOf(to.toString(), from.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val j = JSONObject()
                j.put("id", c.getLong(0))
                j.put("start", c.getLong(1))
                j.put("end", if (c.isNull(2)) JSONObject.NULL else c.getLong(2))
                arr.put(j)
            }
        }
        return arr
    }

    private fun eventsJson(from: Long, to: Long): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, shift_id, ts, type, lat, lng, store_id, amount FROM events WHERE ts >= ? AND ts < ? ORDER BY ts, id",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val j = JSONObject()
                j.put("id", c.getLong(0))
                j.put("shift", c.getLong(1))
                j.put("ts", c.getLong(2))
                j.put("type", c.getString(3))
                j.put("lat", if (c.isNull(4)) JSONObject.NULL else c.getDouble(4))
                j.put("lng", if (c.isNull(5)) JSONObject.NULL else c.getDouble(5))
                j.put("store", if (c.isNull(6)) JSONObject.NULL else c.getLong(6))
                j.put("amount", if (c.isNull(7)) JSONObject.NULL else c.getInt(7))
                arr.put(j)
            }
        }
        return arr
    }

    fun dataJson(from: Long, to: Long): String {
        val o = JSONObject()
        o.put("shifts", shiftsJson(from, to))
        o.put("events", eventsJson(from, to))
        o.put("stores", storesJson())
        return o.toString()
    }

    /** 지도용 이동 경로. 너무 많으면 솎아서 최대 약 4000개 */
    fun trackJson(from: Long, to: Long): String {
        val pts = ArrayList<DoubleArray>()
        readableDatabase.rawQuery(
            "SELECT ts, lat, lng FROM points WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) pts.add(doubleArrayOf(c.getLong(0).toDouble(), c.getDouble(1), c.getDouble(2)))
        }
        val step = maxOf(1, pts.size / 4000)
        val arr = JSONArray()
        var i = 0
        while (i < pts.size) {
            val p = pts[i]
            val j = JSONArray()
            j.put(p[0].toLong())
            j.put(p[1])
            j.put(p[2])
            arr.put(j)
            i += step
        }
        return arr.toString()
    }

    /** 전체 백업 / 분석용 내보내기 */
    fun exportJson(): String {
        val o = JSONObject()
        o.put("exportedAt", System.currentTimeMillis())
        o.put("shifts", shiftsJson(0, Long.MAX_VALUE))
        o.put("events", eventsJson(0, Long.MAX_VALUE))
        o.put("stores", storesJson())
        val pts = JSONArray()
        readableDatabase.rawQuery("SELECT shift_id, ts, lat, lng, speed FROM points ORDER BY ts", null).use { c ->
            while (c.moveToNext()) {
                val j = JSONArray()
                j.put(c.getLong(0))
                j.put(c.getLong(1))
                j.put(c.getDouble(2))
                j.put(c.getDouble(3))
                j.put(if (c.isNull(4)) JSONObject.NULL else c.getDouble(4))
                pts.put(j)
            }
        }
        o.put("points", pts)
        return o.toString()
    }
}
