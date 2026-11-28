package com.rider.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 지도·도감 데이터.
 * - 150m 칸 격자(강남·서초 행정동 경계 포함)는 assets/atlas/atlas.json
 * - cellvisits: 칸별 방문 일수·처음/마지막 방문 (GPS 점을 조금씩 이어서 처리)
 * - trips: 배달 건별 (가게, 배달료, 거리, 대기 포함 소요 시간, 픽업·전달 칸)
 * - 보너스 경험치: 탐험·모험·안전은 하루 상한, 업적은 마일스톤마다
 */
object Atlas {

    private const val PREFS = "atlas"
    const val CYCLE_CAP = 60 * 60_000L      // 배달 한 건에 붙는 대기 포함 시간 상한 (긴 휴식 제외)
    const val LONG_TRIP_M = 4000            // 장거리 배달 기준
    const val DAILY_BONUS_CAP = 12_000L     // 탐험·모험·안전 경험치 하루 상한
    const val XP_NEW_STORE = 2_000L
    const val XP_NEW_CELL = 50L
    const val XP_LONG_TRIP = 1_000L
    const val XP_SAFE = 200L
    const val XP_MILESTONE = 10_000L

    class Grid(
        val lat0: Double, val lng0: Double, val dlat: Double, val dlng: Double, val rows: Int, val cols: Int,
        val grid: Array<String>, val names: List<String>, val gus: List<String>, val cellsIn: IntArray, val targets: DoubleArray
    ) {
        fun cell(lat: Double, lng: Double): Int? {
            val r = Math.floor((lat - lat0) / dlat).toInt()
            val c = Math.floor((lng - lng0) / dlng).toInt()
            if (r < 0 || r >= rows || c < 0 || c >= cols) return null
            return r * cols + c
        }
        fun dong(cell: Int): Int {
            val r = cell / cols
            val c = cell % cols
            if (r < 0 || r >= rows) return -1
            val ch = grid[r][c]
            return if (ch == '.') -1 else ch.code - 48
        }
        val total: Int get() = cellsIn.sum()
    }

    @Volatile
    private var gridCache: Grid? = null

    fun grid(ctx: Context): Grid {
        gridCache?.let { return it }
        val j = JSONObject(ctx.assets.open("atlas/atlas.json").bufferedReader().use { it.readText() })
        val ga = j.getJSONArray("grid")
        val ds = j.getJSONArray("dongs")
        val g = Grid(
            j.getDouble("lat0"), j.getDouble("lng0"), j.getDouble("dlat"), j.getDouble("dlng"), j.getInt("rows"), j.getInt("cols"),
            Array(ga.length()) { ga.getString(it) },
            List(ds.length()) { ds.getJSONObject(it).getString("name") },
            List(ds.length()) { ds.getJSONObject(it).getString("gu") },
            IntArray(ds.length()) { ds.getJSONObject(it).getInt("cells") },
            DoubleArray(ds.length()) { ds.getJSONObject(it).getDouble("target") }
        )
        gridCache = g
        return g
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun db(ctx: Context): SQLiteDatabase {
        val w = Db.get(ctx).writableDatabase
        w.execSQL("CREATE TABLE IF NOT EXISTS cellvisits(cell INTEGER PRIMARY KEY, days INTEGER NOT NULL, first INTEGER NOT NULL, last INTEGER NOT NULL, lastday INTEGER NOT NULL)")
        w.execSQL(
            "CREATE TABLE IF NOT EXISTS trips(deliver_id INTEGER PRIMARY KEY, shift_id INTEGER NOT NULL, ts INTEGER NOT NULL, store_id INTEGER, fare INTEGER, " +
                "accept_ts INTEGER, arrive_ts INTEGER, pickup_ts INTEGER, cycle_ms INTEGER, dist_m INTEGER, pick_cell INTEGER, drop_cell INTEGER)"
        )
        w.execSQL("CREATE INDEX IF NOT EXISTS idx_trips_store ON trips(store_id)")
        return w
    }

    /** 가게 이름 바꾸기·금액 수정·기록 삭제·가게 합치기 뒤에는 배달 건을 처음부터 다시 계산 */
    fun invalidate(ctx: Context) {
        prefs(ctx).edit().putLong("ev", 0L).apply()
    }

    private fun scalar(d: SQLiteDatabase, sql: String, vararg a: String): Long {
        d.rawQuery(sql, arrayOf(*a)).use { c -> return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }

    // ---------------- 갱신 ----------------

    @Synchronized
    fun refresh(ctx: Context) {
        val d = db(ctx)
        val g = grid(ctx)
        val p = prefs(ctx)
        // 1) 칸 방문
        val lastPt = p.getLong("pt", 0L)
        var maxPt = lastPt
        val m = HashMap<Int, LongArray>()
        d.rawQuery("SELECT cell, days, first, last, lastday FROM cellvisits", null).use { c ->
            while (c.moveToNext()) m[c.getInt(0)] = longArrayOf(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4))
        }
        val dirty = HashSet<Int>()
        var dayFrom = 0L
        var dayTo = -1L
        d.rawQuery("SELECT id, ts, lat, lng FROM points WHERE id > ? AND (acc IS NULL OR acc <= 40) ORDER BY id", arrayOf(lastPt.toString())).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val ts = c.getLong(1)
                if (id > maxPt) maxPt = id
                val cell = g.cell(c.getDouble(2), c.getDouble(3)) ?: continue
                if (ts < dayFrom || ts >= dayTo) { dayFrom = Game.dayStart(ts); dayTo = dayFrom + 24 * 3600_000L }
                val v = m.getOrPut(cell) { longArrayOf(0, ts, ts, -1) }
                if (v[3] != dayFrom) { v[0]++; v[3] = dayFrom }
                if (ts < v[1]) v[1] = ts
                if (ts > v[2]) v[2] = ts
                dirty.add(cell)
            }
        }
        if (dirty.isNotEmpty()) {
            d.beginTransaction()
            try {
                for (cell in dirty) {
                    val v = m[cell] ?: continue
                    val cv = ContentValues()
                    cv.put("cell", cell); cv.put("days", v[0]); cv.put("first", v[1]); cv.put("last", v[2]); cv.put("lastday", v[3])
                    d.insertWithOnConflict("cellvisits", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
        p.edit().putLong("pt", maxPt).apply()

        // 2) 배달 건
        val lastEv = p.getLong("ev", 0L)
        val maxEv = scalar(d, "SELECT MAX(id) FROM events")
        if (maxEv != lastEv) {
            val shifts = ArrayList<Long>()
            val q = if (lastEv == 0L) "SELECT id FROM shifts" else "SELECT DISTINCT shift_id FROM events WHERE id > ?"
            d.rawQuery(q, if (lastEv == 0L) null else arrayOf(lastEv.toString())).use { c -> while (c.moveToNext()) shifts.add(c.getLong(0)) }
            d.beginTransaction()
            try {
                if (lastEv == 0L) d.delete("trips", null, null)
                for (s in shifts) rebuildShift(d, g, s)
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
            p.edit().putLong("ev", maxEv).apply()
        }
        // 3) 보너스 경험치 (캐시)
        p.edit().putLong("bonus", computeBonus(ctx, d).getLong("total")).apply()
    }

    private class T(var accept: Long? = null, var arrive: Long? = null, var pickup: Long? = null, var store: Long? = null, var lat: Double? = null, var lng: Double? = null)

    private fun rebuildShift(d: SQLiteDatabase, g: Grid, shift: Long) {
        d.delete("trips", "shift_id=?", arrayOf(shift.toString()))
        var start = 0L
        var end = Long.MAX_VALUE
        d.rawQuery("SELECT start_ts, end_ts FROM shifts WHERE id=?", arrayOf(shift.toString())).use { c ->
            if (c.moveToFirst()) { start = c.getLong(0); end = if (c.isNull(1)) Long.MAX_VALUE else c.getLong(1) }
        }
        val storeLoc = HashMap<Long, DoubleArray>()
        d.rawQuery("SELECT id, lat, lng FROM stores", null).use { c -> while (c.moveToNext()) storeLoc[c.getLong(0)] = doubleArrayOf(c.getDouble(1), c.getDouble(2)) }
        val open = ArrayList<T>()
        val rows = ArrayList<ContentValues>()
        var prevDeliver = start
        d.rawQuery("SELECT id, ts, type, store_id, amount, lat, lng FROM events WHERE shift_id=? ORDER BY ts, id", arrayOf(shift.toString())).use { c ->
            while (c.moveToNext()) {
                val ts = c.getLong(1)
                val type = c.getString(2)
                val store = if (c.isNull(3)) null else c.getLong(3)
                val lat = if (c.isNull(5)) null else c.getDouble(5)
                val lng = if (c.isNull(6)) null else c.getDouble(6)
                val match = { t: T -> store == null || t.store == null || t.store == store }
                when (type) {
                    "ACCEPT" -> open.add(T(accept = ts))
                    "ARRIVE", "PICKUP" -> {
                        val t = open.firstOrNull { (if (type == "ARRIVE") it.arrive == null else it.pickup == null) && match(it) } ?: T().also { open.add(it) }
                        if (type == "ARRIVE") t.arrive = ts else t.pickup = ts
                        if (store != null) t.store = store
                        if (t.lat == null && lat != null) { t.lat = lat; t.lng = lng }
                    }
                    "DELIVER" -> {
                        val t = open.firstOrNull { it.pickup != null } ?: open.firstOrNull() ?: T()
                        open.remove(t)
                        val sl = t.store?.let { storeLoc[it] }
                        val plat = sl?.get(0) ?: t.lat
                        val plng = sl?.get(1) ?: t.lng
                        val cv = ContentValues()
                        cv.put("deliver_id", c.getLong(0)); cv.put("shift_id", shift); cv.put("ts", ts)
                        if (t.store != null) cv.put("store_id", t.store)
                        if (!c.isNull(4)) cv.put("fare", c.getInt(4))
                        t.accept?.let { cv.put("accept_ts", it) }
                        t.arrive?.let { cv.put("arrive_ts", it) }
                        t.pickup?.let { cv.put("pickup_ts", it) }
                        cv.put("cycle_ms", (ts - prevDeliver).coerceIn(0L, CYCLE_CAP))
                        prevDeliver = ts
                        if (plat != null && plng != null) g.cell(plat, plng)?.let { cv.put("pick_cell", it) }
                        if (lat != null && lng != null) g.cell(lat, lng)?.let { cv.put("drop_cell", it) }
                        rows.add(cv)
                    }
                }
            }
        }
        // 거리: 픽업(없으면 수락)부터 전달까지 GPS 경로
        if (rows.isNotEmpty()) {
            val from = rows.minOf { (it.getAsLong("pickup_ts") ?: it.getAsLong("accept_ts") ?: it.getAsLong("ts")) }
            val to = rows.maxOf { it.getAsLong("ts") }
            val dist = DoubleArray(rows.size)
            val last = arrayOfNulls<DoubleArray>(rows.size)
            d.rawQuery(
                "SELECT ts, lat, lng FROM points WHERE ts >= ? AND ts <= ? AND ts >= ? AND ts <= ? AND (acc IS NULL OR acc <= 40) ORDER BY ts",
                arrayOf(from.toString(), to.toString(), start.toString(), end.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val ts = c.getLong(0)
                    val la = c.getDouble(1)
                    val ln = c.getDouble(2)
                    for (i in rows.indices) {
                        val r = rows[i]
                        val a = r.getAsLong("pickup_ts") ?: r.getAsLong("accept_ts") ?: continue
                        if (ts < a || ts > r.getAsLong("ts")) continue
                        val l = last[i]
                        if (l == null) { last[i] = doubleArrayOf(la, ln); continue }
                        val dd = Db.dist(l[0], l[1], la, ln)
                        if (dd >= 20 && dd < 2000) { dist[i] += dd; last[i] = doubleArrayOf(la, ln) }
                    }
                }
            }
            for (i in rows.indices) { rows[i].put("dist_m", dist[i].toInt()); d.insert("trips", null, rows[i]) }
        }
    }

    fun bonusXp(ctx: Context): Long = prefs(ctx).getLong("bonus", 0L)

    // ---------------- 능력치·업적 ----------------

    private val STAT_STEPS = mapOf(
        "explorer" to longArrayOf(20, 50, 100, 170, 260, 370, 500, 650, 820, 1000, 1200, 1420, 1660, 1920, 2200, 2500, 2800, 3100, 3400, 3700),
        "veteran" to longArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66, 80, 95, 112, 130, 150, 175, 200, 230, 260),
        "merchant" to longArrayOf(100_000, 300_000, 600_000, 1_000_000, 1_600_000, 2_400_000, 3_400_000, 4_600_000, 6_000_000, 7_600_000,
            9_500_000, 11_700_000, 14_200_000, 17_000_000, 20_200_000, 23_800_000, 27_800_000, 32_200_000, 37_000_000, 42_500_000),
        "adventurer" to longArrayOf(10, 30, 60, 100, 150, 210, 280, 360, 450, 550, 660, 780, 910, 1050, 1200, 1360, 1530, 1710, 1900, 2100)
    )
    private val MILESTONES = listOf(
        Triple("stores", longArrayOf(10, 30, 50, 100, 200, 300, 500), "가게 %d곳 등록"),
        Triple("done", longArrayOf(1, 3, 5, 10, 20, 30, 40), "동네 %d곳 탐험 완료"),
        Triple("trips", longArrayOf(100, 300, 500, 1000, 2000, 3000, 5000, 7500, 10000), "배달 %d건 완료"),
        Triple("long", longArrayOf(10, 50, 100, 300), "장거리 배달 %d건")
    )

    private fun levelOf(steps: LongArray, v: Long): Int = steps.count { v >= it }

    /** 동네별 탐험 칸 수 */
    private fun visitedByDong(d: SQLiteDatabase, g: Grid): IntArray {
        val a = IntArray(g.names.size)
        d.rawQuery("SELECT cell FROM cellvisits", null).use { c -> while (c.moveToNext()) { val k = g.dong(c.getInt(0)); if (k >= 0) a[k]++ } }
        return a
    }

    private fun doneDongs(d: SQLiteDatabase, g: Grid): Int {
        val v = visitedByDong(d, g)
        return v.indices.count { v[it] >= Math.ceil(g.cellsIn[it] * g.targets[it]) && g.cellsIn[it] > 0 }
    }

    private fun counts(ctx: Context, d: SQLiteDatabase): JSONObject {
        val g = grid(ctx)
        val dongs = HashSet<Int>()
        d.rawQuery("SELECT pick_cell, drop_cell FROM trips", null).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(0)) g.dong(c.getInt(0)).takeIf { it >= 0 }?.let { dongs.add(it) }
                if (!c.isNull(1)) g.dong(c.getInt(1)).takeIf { it >= 0 }?.let { dongs.add(it) }
            }
        }
        val longTrips = scalar(d, "SELECT COUNT(*) FROM trips WHERE dist_m >= $LONG_TRIP_M")
        return JSONObject()
            .put("cells", scalar(d, "SELECT COUNT(*) FROM cellvisits"))
            .put("stores", scalar(d, "SELECT COUNT(DISTINCT store_id) FROM events WHERE store_id IS NOT NULL AND type IN ('ARRIVE','PICKUP')"))
            .put("days", scalar(d, "SELECT COUNT(DISTINCT (ts - 21600000) / 86400000) FROM events WHERE type='DELIVER'"))
            .put("income", scalar(d, "SELECT SUM(amount) FROM events WHERE type='DELIVER' AND amount IS NOT NULL"))
            .put("trips", scalar(d, "SELECT COUNT(*) FROM events WHERE type='DELIVER'"))
            .put("dongs", dongs.size)
            .put("long", longTrips)
            .put("done", doneDongs(d, g))
    }

    /** 능력치 4개: [값, 레벨, 다음 단계 값] */
    fun statsOf(ctx: Context, d: SQLiteDatabase = db(ctx)): JSONObject {
        val k = counts(ctx, d)
        val vals = mapOf(
            "explorer" to k.getLong("cells") + k.getLong("stores") * 3,
            "veteran" to k.getLong("days"),
            "merchant" to k.getLong("income"),
            "adventurer" to k.getLong("dongs") * 10 + k.getLong("long")
        )
        val o = JSONObject()
        for ((key, v) in vals) {
            val steps = STAT_STEPS.getValue(key)
            val lv = levelOf(steps, v)
            o.put(key, JSONObject().put("value", v).put("level", lv).put("next", if (lv < steps.size) steps[lv] else JSONObject.NULL).put("prev", if (lv > 0) steps[lv - 1] else 0))
        }
        return o
    }

    private fun computeBonus(ctx: Context, d: SQLiteDatabase): JSONObject {
        // 날짜별로 탐험·모험·안전 경험치를 모은 뒤 하루 상한 적용
        val perDay = HashMap<Long, LongArray>() // [탐험, 모험, 안전]
        fun add(ts: Long, i: Int, v: Long) { perDay.getOrPut(Game.dayStart(ts)) { LongArray(3) }[i] += v }
        d.rawQuery("SELECT MIN(ts) FROM events WHERE store_id IS NOT NULL AND type IN ('ARRIVE','PICKUP') GROUP BY store_id", null).use { c -> while (c.moveToNext()) add(c.getLong(0), 0, XP_NEW_STORE) }
        d.rawQuery("SELECT first FROM cellvisits", null).use { c -> while (c.moveToNext()) add(c.getLong(0), 0, XP_NEW_CELL) }
        d.rawQuery("SELECT ts FROM trips WHERE dist_m >= $LONG_TRIP_M", null).use { c -> while (c.moveToNext()) add(c.getLong(0), 1, XP_LONG_TRIP) }
        d.rawQuery("SELECT ts FROM events WHERE type='CAMPASS' AND note='safe'", null).use { c -> while (c.moveToNext()) add(c.getLong(0), 2, XP_SAFE) }
        var ex = 0L
        var ad = 0L
        var sa = 0L
        var today = 0L
        val todayKey = Game.dayStart(System.currentTimeMillis())
        for ((day, v) in perDay) {
            val sum = v[0] + v[1] + v[2]
            val f = if (sum > DAILY_BONUS_CAP) DAILY_BONUS_CAP.toDouble() / sum else 1.0
            ex += (v[0] * f).toLong(); ad += (v[1] * f).toLong(); sa += (v[2] * f).toLong()
            if (day == todayKey) today = minOf(sum, DAILY_BONUS_CAP)
        }
        val k = counts(ctx, d)
        var ach = 0L
        val ms = JSONArray()
        for ((key, steps, label) in MILESTONES) {
            val v = k.getLong(key)
            for (s in steps) {
                val ok = v >= s
                if (ok) ach += XP_MILESTONE
                ms.put(JSONObject().put("title", label.format(s)).put("done", ok).put("value", v).put("goal", s))
            }
        }
        return JSONObject().put("explore", ex).put("adventure", ad).put("safety", sa).put("achieve", ach).put("total", ex + ad + sa + ach)
            .put("today", today).put("cap", DAILY_BONUS_CAP).put("milestones", ms)
    }

    // ---------------- 화면용 JSON ----------------

    /** 업적 탭: 능력치, 보너스 경험치 내역, 마일스톤 */
    fun profileJson(ctx: Context): String {
        refresh(ctx)
        val d = db(ctx)
        return JSONObject().put("stats", statsOf(ctx, d)).put("bonus", computeBonus(ctx, d)).put("counts", counts(ctx, d)).toString()
    }

    /** 지도: 방문한 칸 [칸, 방문 일수, 마지막 방문, 픽업 횟수] */
    fun mapJson(ctx: Context): String {
        refresh(ctx)
        val d = db(ctx)
        val pick = HashMap<Int, Int>()
        d.rawQuery("SELECT pick_cell, COUNT(*) FROM trips WHERE pick_cell IS NOT NULL GROUP BY pick_cell", null).use { c -> while (c.moveToNext()) pick[c.getInt(0)] = c.getInt(1) }
        val arr = JSONArray()
        d.rawQuery("SELECT cell, days, last FROM cellvisits", null).use { c ->
            while (c.moveToNext()) arr.put(JSONArray().put(c.getInt(0)).put(c.getInt(1)).put(c.getLong(2)).put(pick[c.getInt(0)] ?: 0))
        }
        return JSONObject().put("cells", arr).put("now", System.currentTimeMillis()).toString()
    }

    private val BANDS = listOf("all", "lunch", "dinner", "night", "late")
    private fun bandOf(hour: Int): String? = when (hour) {
        in 11..13 -> "lunch"; in 17..20 -> "dinner"; in 21..23 -> "night"; in 0..5 -> "late"; else -> null
    }

    /** 지역 도감·수익 지도: 동별 탐험률, 픽업 기준 수익 (시간대별) */
    fun regionJson(ctx: Context): String {
        refresh(ctx)
        val d = db(ctx)
        val g = grid(ctx)
        val n = g.names.size
        val visited = IntArray(n)
        val bases = IntArray(n)
        val lastV = LongArray(n)
        d.rawQuery("SELECT cell, days, last FROM cellvisits", null).use { c ->
            while (c.moveToNext()) {
                val k = g.dong(c.getInt(0)); if (k < 0) continue
                visited[k]++
                if (c.getInt(1) >= 5) bases[k]++
                lastV[k] = maxOf(lastV[k], c.getLong(2))
            }
        }
        // [건수, 배달료 합, 배달료 있는 건수, 소요 ms, 최고 단가]
        val agg = HashMap<String, LongArray>()
        val drops = IntArray(n)
        val cal = java.util.Calendar.getInstance()
        d.rawQuery("SELECT pick_cell, drop_cell, fare, cycle_ms, ts FROM trips", null).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(1)) g.dong(c.getInt(1)).takeIf { it >= 0 }?.let { drops[it]++ }
                if (c.isNull(0)) continue
                val k = g.dong(c.getInt(0)); if (k < 0) continue
                cal.timeInMillis = c.getLong(4)
                val band = bandOf(cal.get(java.util.Calendar.HOUR_OF_DAY))
                for (b in listOf("all", band)) {
                    if (b == null) continue
                    val a = agg.getOrPut("$k|$b") { LongArray(5) }
                    a[0]++
                    if (!c.isNull(2)) { a[1] += c.getLong(2); a[2]++; a[4] = maxOf(a[4], c.getLong(2)) }
                    a[3] += if (c.isNull(3)) 0L else c.getLong(3)
                }
            }
        }
        val arr = JSONArray()
        for (k in 0 until n) {
            val goal = Math.ceil(g.cellsIn[k] * g.targets[k]).coerceAtLeast(1.0)
            val bands = JSONObject()
            for (b in BANDS) {
                val a = agg["$k|$b"] ?: LongArray(5)
                bands.put(b, JSONArray().put(a[0]).put(a[1]).put(a[2]).put(a[3]).put(a[4]))
            }
            arr.put(
                JSONObject().put("i", k).put("name", g.names[k]).put("gu", g.gus[k]).put("cells", g.cellsIn[k]).put("visited", visited[k])
                    .put("pct", minOf(100.0, visited[k] / goal * 100)).put("bases", bases[k]).put("last", lastV[k]).put("drops", drops[k]).put("bands", bands)
            )
        }
        return JSONObject().put("dongs", arr).put("now", System.currentTimeMillis()).toString()
    }

    /** 가게 도감: 첫·마지막 방문, 방문 횟수, 배달료 합·평균·최고, 평균 대기 */
    fun storesJson(ctx: Context): String {
        refresh(ctx)
        val d = db(ctx)
        val g = grid(ctx)
        val o = HashMap<Long, JSONObject>()
        d.rawQuery(
            "SELECT s.id, s.name, s.lat, s.lng, COUNT(DISTINCT e.ts / 600000), MIN(e.ts), MAX(e.ts) FROM stores s JOIN events e ON e.store_id = s.id " +
                "WHERE e.type IN ('ARRIVE','PICKUP') GROUP BY s.id", null
        ).use { c ->
            while (c.moveToNext()) {
                val cell = g.cell(c.getDouble(2), c.getDouble(3))
                val dong = cell?.let { g.dong(it) } ?: -1
                o[c.getLong(0)] = JSONObject().put("id", c.getLong(0)).put("name", c.getString(1)).put("visits", c.getInt(4))
                    .put("first", c.getLong(5)).put("last", c.getLong(6)).put("dong", if (dong >= 0) g.names[dong] else JSONObject.NULL)
            }
        }
        d.rawQuery(
            "SELECT store_id, COUNT(*), SUM(fare), COUNT(fare), MAX(fare), AVG(CASE WHEN pickup_ts > arrive_ts THEN pickup_ts - arrive_ts END), MAX(dist_m) FROM trips WHERE store_id IS NOT NULL GROUP BY store_id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val j = o[c.getLong(0)] ?: continue
                j.put("trips", c.getInt(1)).put("fareSum", c.getLong(2)).put("fareN", c.getInt(3)).put("fareMax", c.getLong(4))
                if (!c.isNull(5)) j.put("wait", c.getLong(5))
                j.put("distMax", c.getLong(6))
            }
        }
        return JSONArray(o.values.toList()).toString()
    }

    /** 기록 도감 */
    fun recordsJson(ctx: Context): String {
        refresh(ctx)
        val d = db(ctx)
        val o = JSONObject()
        fun trip(sql: String): JSONObject? {
            d.rawQuery(sql, null).use { c ->
                if (!c.moveToFirst()) return null
                return JSONObject().put("ts", c.getLong(0)).put("fare", if (c.isNull(1)) JSONObject.NULL else c.getLong(1)).put("dist", c.getLong(2))
                    .put("store", if (c.isNull(3)) JSONObject.NULL else c.getString(3)).put("ms", if (c.isNull(4)) JSONObject.NULL else c.getLong(4))
            }
        }
        val base = "SELECT t.ts, t.fare, t.dist_m, s.name, t.ts - COALESCE(t.accept_ts, t.pickup_ts) FROM trips t LEFT JOIN stores s ON s.id = t.store_id "
        o.put("longest", trip(base + "ORDER BY t.dist_m DESC LIMIT 1") ?: JSONObject.NULL)
        o.put("topFare", trip(base + "WHERE t.fare IS NOT NULL ORDER BY t.fare DESC LIMIT 1") ?: JSONObject.NULL)
        o.put("fastest", trip(base + "WHERE t.accept_ts IS NOT NULL AND t.dist_m >= 500 ORDER BY t.ts - t.accept_ts ASC LIMIT 1") ?: JSONObject.NULL)
        // 근무일별 수입·건수·근무 시간
        val days = HashMap<Long, LongArray>() // [수입, 건수, 근무 ms, 가게 수]
        d.rawQuery("SELECT ts, amount FROM events WHERE type='DELIVER'", null).use { c ->
            while (c.moveToNext()) { val a = days.getOrPut(Game.dayStart(c.getLong(0))) { LongArray(4) }; a[0] += if (c.isNull(1)) 0L else c.getLong(1); a[1]++ }
        }
        val now = System.currentTimeMillis()
        d.rawQuery("SELECT start_ts, end_ts FROM shifts", null).use { c ->
            while (c.moveToNext()) { val s = c.getLong(0); val e = if (c.isNull(1)) now else c.getLong(1); days.getOrPut(Game.dayStart(s)) { LongArray(4) }[2] += (e - s).coerceAtLeast(0) }
        }
        val storesByDay = HashMap<Long, HashSet<Long>>()
        d.rawQuery("SELECT ts, store_id FROM events WHERE store_id IS NOT NULL AND type='PICKUP'", null).use { c ->
            while (c.moveToNext()) storesByDay.getOrPut(Game.dayStart(c.getLong(0))) { HashSet() }.add(c.getLong(1))
        }
        for ((k, v) in storesByDay) days[k]?.let { it[3] = v.size.toLong() }
        fun day(k: Long?, v: Long): Any = if (k == null) JSONObject.NULL else JSONObject().put("day", k).put("value", v).put("income", days[k]!![0]).put("count", days[k]!![1]).put("work", days[k]!![2])
        val bestIncome = days.maxByOrNull { it.value[0] }
        val bestCount = days.maxByOrNull { it.value[1] }
        val eff = days.filter { it.value[2] >= 3 * 3600_000L && it.value[0] > 0 }.maxByOrNull { it.value[0] * 3600_000.0 / it.value[2] }
        val stores = days.maxByOrNull { it.value[3] }
        o.put("bestIncome", day(bestIncome?.key, bestIncome?.value?.get(0) ?: 0))
        o.put("bestCount", day(bestCount?.key, bestCount?.value?.get(1) ?: 0))
        o.put("bestHourly", day(eff?.key, eff?.let { (it.value[0] * 3600_000.0 / it.value[2]).toLong() } ?: 0))
        o.put("mostStores", day(stores?.key, stores?.value?.get(3) ?: 0))
        o.put("longestShift", day(days.maxByOrNull { it.value[2] }?.key, days.values.maxOfOrNull { it[2] } ?: 0))
        return o.toString()
    }

    /** 가게 합치기: from 의 기록을 into 로 옮기고 from 삭제 */
    fun mergeStores(ctx: Context, from: Long, into: Long) {
        if (from == into) return
        val d = db(ctx)
        val cv = ContentValues()
        cv.put("store_id", into)
        d.update("events", cv, "store_id=?", arrayOf(from.toString()))
        d.update("trips", cv, "store_id=?", arrayOf(from.toString()))
        d.delete("stores", "id=?", arrayOf(from.toString()))
        invalidate(ctx)
    }
}
