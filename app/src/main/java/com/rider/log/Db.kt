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
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "rider.db", null, 3) {

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
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, shift_id INTEGER NOT NULL, ts INTEGER NOT NULL, type TEXT NOT NULL, lat REAL, lng REAL, store_id INTEGER, amount INTEGER, note TEXT, source TEXT)")
        db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT, shift_id INTEGER NOT NULL, ts INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, speed REAL, acc REAL)")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
        db.execSQL("CREATE INDEX idx_points_ts ON points(ts)")
        createCameras(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE points ADD COLUMN acc REAL")
            createCameras(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE events ADD COLUMN note TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN source TEXT")
        }
    }

    // ---------- 후면 단속 카메라 ----------

    /**
     * 드림라이더 캡처(2026-11-28)를 보고 강남·서초 후면 단속 카메라 위치를 '대략' 잡은 초기 목록.
     * 캡처에는 좌표가 없어서 주변 역·건물 기준 추정값이고, 앱에서 위치를 끌어 옮기면 '확인됨'이 된다.
     * 확인 전(confirmed=0) 카메라는 알림을 울리지 않는다.
     */
    private val seedCameras = listOf(
        Triple("매헌지하차도 (코스트코 양재점 앞)", doubleArrayOf(37.4627, 127.0412), 50),
        Triple("강남대로 양재역 남쪽", doubleArrayOf(37.4800, 127.0357), 50),
        Triple("남부터미널역 사거리", doubleArrayOf(37.4846, 127.0166), 50),
        Triple("효령로 국제전자센터 북쪽", doubleArrayOf(37.4868, 127.0180), 50),
        Triple("남부순환로 서울연구원 앞", doubleArrayOf(37.4832, 127.0238), 60),
        Triple("서초3동 SC제일은행 앞", doubleArrayOf(37.4857, 127.0128), 50),
        Triple("서운로 서운중학교 북쪽", doubleArrayOf(37.4905, 127.0192), 30),
        Triple("서이초등학교 서쪽", doubleArrayOf(37.4879, 127.0182), 30),
        Triple("방배본동 주민센터 남쪽", doubleArrayOf(37.4928, 126.9905), 30),
        Triple("방배초등학교 앞 (7호선 내방역 서쪽)", doubleArrayOf(37.4867, 126.9873), 50),
        Triple("방배1동 주민센터 앞", doubleArrayOf(37.4838, 126.9985), 50),
        Triple("방배역 북쪽", doubleArrayOf(37.4822, 126.9976), 50),
        Triple("이수중학교 앞", doubleArrayOf(37.4816, 126.9891), 30),
        Triple("방배 2호선 남쪽 (동덕여고 서쪽)", doubleArrayOf(37.4768, 126.9902), 50),
        Triple("잠원동 라이프미성2차 앞", doubleArrayOf(37.5196, 127.0213), 50),
        Triple("잠원로 오티에르반포 앞 ①", doubleArrayOf(37.5152, 127.0106), 50),
        Triple("잠원로 오티에르반포 앞 ②", doubleArrayOf(37.5146, 127.0101), 50),
        Triple("잠원역 서쪽", doubleArrayOf(37.5126, 127.0106), 30),
        Triple("반포역 서쪽 ①", doubleArrayOf(37.5079, 127.0085), 50),
        Triple("반포역 ②", doubleArrayOf(37.5081, 127.0104), 50),
        Triple("신반포2차 앞 (반포한강공원 방향)", doubleArrayOf(37.5112, 127.0008), 50),
        Triple("신반포역", doubleArrayOf(37.5037, 126.9958), 50),
        Triple("서울세화초 북쪽", doubleArrayOf(37.5025, 126.9897), 50),
        Triple("서초소방서 앞", doubleArrayOf(37.4993, 126.9903), 50),
        Triple("학동초등학교 앞", doubleArrayOf(37.5153, 127.0447), 50),
        Triple("역삼 아르누보씨티 북쪽", doubleArrayOf(37.5033, 127.0437), 50),
        Triple("대치4동 (선릉역 남쪽)", doubleArrayOf(37.4997, 127.0557), 50),
        Triple("강남세브란스병원 앞", doubleArrayOf(37.4929, 127.0463), 50),
        Triple("개포우성1차 앞 (양재천)", doubleArrayOf(37.4902, 127.0608), 30),
        Triple("개포주공6단지 앞", doubleArrayOf(37.4871, 127.0690), 30)
    )

    private fun createCameras(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cameras(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, speed INTEGER, confirmed INTEGER NOT NULL DEFAULT 0)")
        for ((name, ll, speed) in seedCameras) {
            val cv = ContentValues()
            cv.put("name", name)
            cv.put("lat", ll[0])
            cv.put("lng", ll[1])
            cv.put("speed", speed)
            cv.put("confirmed", 0)
            db.insert("cameras", null, cv)
        }
    }

    data class Cam(val id: Long, val name: String, val lat: Double, val lng: Double, val speed: Int, val confirmed: Boolean)

    fun cameras(): List<Cam> {
        val out = ArrayList<Cam>()
        readableDatabase.rawQuery("SELECT id, name, lat, lng, speed, confirmed FROM cameras ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                out.add(Cam(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3), if (c.isNull(4)) 0 else c.getInt(4), c.getInt(5) == 1))
            }
        }
        return out
    }

    fun camerasJson(): String {
        val arr = JSONArray()
        for (k in cameras()) {
            arr.put(
                JSONObject().put("id", k.id).put("name", k.name).put("lat", k.lat).put("lng", k.lng)
                    .put("speed", k.speed).put("confirmed", k.confirmed)
            )
        }
        return arr.toString()
    }

    fun addCamera(name: String, lat: Double, lng: Double, speed: Int): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("lat", lat)
        cv.put("lng", lng)
        cv.put("speed", speed)
        cv.put("confirmed", 1)
        return writableDatabase.insert("cameras", null, cv)
    }

    fun updateCamera(id: Long, json: String) {
        val j = JSONObject(json)
        val cv = ContentValues()
        if (j.has("name")) cv.put("name", j.getString("name"))
        if (j.has("lat")) cv.put("lat", j.getDouble("lat"))
        if (j.has("lng")) cv.put("lng", j.getDouble("lng"))
        if (j.has("speed")) cv.put("speed", j.getInt("speed"))
        if (j.has("confirmed")) cv.put("confirmed", if (j.getBoolean("confirmed")) 1 else 0)
        if (cv.size() > 0) writableDatabase.update("cameras", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteCamera(id: Long) {
        writableDatabase.delete("cameras", "id=?", arrayOf(id.toString()))
    }

    // ---------- 운행 ----------

    fun openShiftId(): Long? {
        readableDatabase.rawQuery("SELECT id FROM shifts WHERE end_ts IS NULL ORDER BY id DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    fun firstShiftStart(): Long? {
        readableDatabase.rawQuery("SELECT MIN(start_ts) FROM shifts", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
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

    fun addPoint(shift: Long, ts: Long, lat: Double, lng: Double, speed: Float?, acc: Float? = null) {
        val cv = ContentValues()
        if (acc != null) cv.put("acc", acc.toDouble())
        cv.put("shift_id", shift)
        cv.put("ts", ts)
        cv.put("lat", lat)
        cv.put("lng", lng)
        if (speed != null) cv.put("speed", speed.toDouble())
        writableDatabase.insert("points", null, cv)
    }

    // ---------- 버튼 기록 ----------

    /** 같은 종류 기록이 ±window 안에 이미 있으면 true (오버레이 버튼과 자동 인식 중복 방지) */
    fun hasNear(shift: Long, type: String, ts: Long, window: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM events WHERE shift_id=? AND type=? AND ts BETWEEN ? AND ? LIMIT 1",
            arrayOf(shift.toString(), type, (ts - window).toString(), (ts + window).toString())
        ).use { c -> return c.moveToFirst() }
    }

    fun storeByName(name: String): Long? {
        readableDatabase.rawQuery("SELECT id FROM stores WHERE name=? ORDER BY id LIMIT 1", arrayOf(name)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    fun addAutoEvent(shift: Long, ts: Long, type: String, lat: Double?, lng: Double?, storeId: Long?, amount: Int?, note: String?): Long {
        val cv = ContentValues()
        cv.put("shift_id", shift)
        cv.put("ts", ts)
        cv.put("type", type)
        if (lat != null) cv.put("lat", lat)
        if (lng != null) cv.put("lng", lng)
        if (storeId != null) cv.put("store_id", storeId)
        if (amount != null) cv.put("amount", amount)
        if (note != null) cv.put("note", note)
        cv.put("source", "auto")
        return writableDatabase.insert("events", null, cv)
    }

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
            "SELECT id, shift_id, ts, type, lat, lng, store_id, amount, note, source FROM events WHERE ts >= ? AND ts < ? ORDER BY ts, id",
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
                j.put("note", if (c.isNull(8)) JSONObject.NULL else c.getString(8))
                j.put("src", if (c.isNull(9)) "button" else c.getString(9))
                arr.put(j)
            }
        }
        return arr
    }

    /**
     * 주행 거리(m)를 1시간 단위로 묶어서 돌려줌 {"시작시각ms": 미터}.
     * GPS 흔들림을 막으려고 직전 기준점에서 20m 이상 움직였을 때만 더하고,
     * 정확도가 나쁜 점(35m 초과)과 시속 144km를 넘는 튀는 값은 버린다.
     */
    fun kmJson(from: Long, to: Long): JSONObject {
        val o = JSONObject()
        var aLat = 0.0
        var aLng = 0.0
        var aTs = 0L
        var has = false
        var shift = -1L
        readableDatabase.rawQuery(
            "SELECT shift_id, ts, lat, lng, acc FROM points WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(4) && c.getDouble(4) > 35) continue
                val sid = c.getLong(0)
                val ts = c.getLong(1)
                val lat = c.getDouble(2)
                val lng = c.getDouble(3)
                if (!has || sid != shift || ts - aTs > 180000) {
                    aLat = lat; aLng = lng; aTs = ts; has = true; shift = sid
                    continue
                }
                val d = dist(aLat, aLng, lat, lng)
                if (d < 20) {
                    aTs = ts
                    continue
                }
                val dt = (ts - aTs) / 1000.0
                if (dt <= 0 || d / dt > 40) {
                    aLat = lat; aLng = lng; aTs = ts
                    continue
                }
                val bucket = (ts - ts % 3600000L).toString()
                o.put(bucket, o.optDouble(bucket, 0.0) + d)
                aLat = lat; aLng = lng; aTs = ts
            }
        }
        return o
    }

    fun dataJson(from: Long, to: Long): String {
        val o = JSONObject()
        o.put("km", kmJson(from, to))
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
