package com.rider.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 게임 시스템: 레벨·경험치, 일일 랜덤 퀘스트, 보상 금고, 조기 퇴근권, 동네 정복 지도, 가게 도감.
 * 타임어택(빨리 배달하기) 퀘스트는 일부러 넣지 않는다.
 */
object Game {

    // ---------------- 레벨 ----------------
    // 다음 레벨까지 필요한 경험치 = 20,000 + 8,000 × (현재 레벨 - 1)  → 두 배씩 늘지 않고 완만하게 증가, 만렙 없음
    // 대략: Lv10 ≈ 47만, Lv30 ≈ 406만, Lv50 ≈ 1,078만, Lv100 ≈ 4,158만 경험치
    const val QUEST_XP = 3000
    const val QUEST_REWARD = 3000

    fun reqFor(level: Int): Long = 20_000L + 8_000L * (level - 1)

    /** [레벨, 현재 레벨에서 쌓은 경험치, 다음 레벨까지 필요한 경험치] */
    fun levelOf(xp: Long): LongArray {
        var lv = 1
        var rest = xp
        while (rest >= reqFor(lv)) {
            rest -= reqFor(lv); lv++
        }
        return longArrayOf(lv.toLong(), rest, reqFor(lv))
    }

    fun title(lv: Int): String = when {
        lv >= 100 -> "신화의 라이더"
        lv >= 75 -> "전설의 라이더"
        lv >= 50 -> "배달 마스터"
        lv >= 30 -> "도로의 기사"
        lv >= 20 -> "강남 베테랑"
        lv >= 10 -> "골목대장"
        lv >= 5 -> "동네 라이더"
        else -> "견습 라이더"
    }

    // ---------------- 동네 정복 지도 (약 300m 칸) ----------------
    const val LAT0 = 37.445
    const val LAT1 = 37.535
    const val LNG0 = 126.975
    const val LNG1 = 127.115
    const val DLAT = 0.002695
    const val DLNG = 0.003396
    val ROWS = Math.ceil((LAT1 - LAT0) / DLAT).toInt()
    val COLS = Math.ceil((LNG1 - LNG0) / DLNG).toInt()

    // 동네 탐방 퀘스트용 동 중심(대략)
    private val AREAS = listOf(
        Triple("역삼동", 37.5006, 127.0365), Triple("논현동", 37.5110, 127.0285), Triple("신사동", 37.5205, 127.0225),
        Triple("압구정동", 37.5272, 127.0285), Triple("청담동", 37.5245, 127.0470), Triple("삼성동", 37.5140, 127.0560),
        Triple("대치동", 37.4995, 127.0600), Triple("도곡동", 37.4880, 127.0465), Triple("개포동", 37.4810, 127.0620),
        Triple("일원동", 37.4890, 127.0840), Triple("반포동", 37.5050, 127.0050), Triple("잠원동", 37.5130, 127.0130),
        Triple("서초동", 37.4900, 127.0150), Triple("방배동", 37.4810, 126.9930), Triple("양재동", 37.4700, 127.0380),
        Triple("우면동", 37.4680, 127.0240), Triple("수서동", 37.4875, 127.1020)
    )

    // ---------------- 저장소 ----------------

    private fun db(ctx: Context): SQLiteDatabase {
        val w = Db.get(ctx).writableDatabase
        w.execSQL("CREATE TABLE IF NOT EXISTS quests(day TEXT NOT NULL, idx INTEGER NOT NULL, kind TEXT NOT NULL, target INTEGER NOT NULL, param TEXT, title TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 0, done_ts INTEGER, PRIMARY KEY(day, idx))")
        w.execSQL("CREATE TABLE IF NOT EXISTS vault(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, amount INTEGER NOT NULL, note TEXT)")
        w.execSQL("CREATE TABLE IF NOT EXISTS cellvisits(cell INTEGER PRIMARY KEY, days INTEGER NOT NULL, first INTEGER NOT NULL, last INTEGER NOT NULL, lastday INTEGER NOT NULL)")
        w.execSQL("CREATE TABLE IF NOT EXISTS tickets(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, source TEXT NOT NULL, used_ts INTEGER)")
        return w
    }

    private fun scalarLong(d: SQLiteDatabase, sql: String, vararg args: String): Long {
        d.rawQuery(sql, arrayOf(*args)).use { c -> return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }

    /** 근무일(새벽 6시 기준) 시작 시각 */
    fun dayStart(ts: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts - 6 * 3600_000L
        cal.set(Calendar.HOUR_OF_DAY, 6); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun dayKey(ts: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(dayStart(ts)))

    // ---------------- 경험치 ----------------

    fun totalXp(ctx: Context): Long {
        val d = db(ctx)
        val fares = scalarLong(d, "SELECT SUM(amount) FROM events WHERE type='DELIVER' AND amount IS NOT NULL")
        val quests = scalarLong(d, "SELECT COUNT(*) FROM quests WHERE done_ts IS NOT NULL")
        return fares + quests * QUEST_XP + Atlas.bonusXp(ctx)
    }

    // ---------------- 퀘스트 ----------------

    private data class Q(val kind: String, val target: Int, val param: String?, val title: String)

    /** 지난 14일 평균 (없으면 기본값) */
    private fun averages(ctx: Context, now: Long): DoubleArray {
        val d = db(ctx)
        val from = dayStart(now) - 14 * 24 * 3600_000L
        val to = dayStart(now)
        val days = scalarLong(d, "SELECT COUNT(DISTINCT (ts - 21600000) / 86400000) FROM events WHERE type='DELIVER' AND ts >= ? AND ts < ?", from.toString(), to.toString()).coerceAtLeast(0)
        if (days < 2) return doubleArrayOf(25.0, 120_000.0)
        val cnt = scalarLong(d, "SELECT COUNT(*) FROM events WHERE type='DELIVER' AND ts >= ? AND ts < ?", from.toString(), to.toString())
        val inc = scalarLong(d, "SELECT SUM(amount) FROM events WHERE type='DELIVER' AND ts >= ? AND ts < ?", from.toString(), to.toString())
        return doubleArrayOf(cnt.toDouble() / days, inc.toDouble() / days)
    }

    private fun round(v: Double, step: Int): Int = (Math.round(v / step) * step).toInt().coerceAtLeast(step)

    private fun pool(ctx: Context, now: Long, rnd: Random): List<Q> {
        val avg = averages(ctx, now)
        val avgCnt = avg[0]
        val avgInc = avg[1]
        val scale = listOf(0.8, 0.9, 1.0, 1.1)[rnd.nextInt(4)]
        val area = AREAS[rnd.nextInt(AREAS.size)]
        val n = { a: Int, b: Int -> a + rnd.nextInt(b - a + 1) }
        val dCnt = round(avgCnt * scale, 1).coerceIn(5, 60)
        val dInc = round(avgInc * scale, 10_000).coerceIn(30_000, 400_000)
        val peak = n(4, 8)
        val late = n(3, 6)
        val newStores = n(2, 4)
        val distinct = n(6, 12)
        val streak = n(8, 15)
        val km = n(4, 8) * 10
        val cells = n(8, 25)
        val ready = n(2, 4)
        val rare = n(1, 3)
        val fareTh = listOf(6000, 7000, 8000)[rnd.nextInt(3)]
        val cam = n(2, 5)
        return listOf(
            Q("DELIVERIES", dCnt, null, "오늘 배달 ${dCnt}건 완료"),
            Q("INCOME", dInc, null, "오늘 수입 ${"%,d".format(dInc)}원 달성"),
            Q("NEW_STORES", newStores, null, "처음 가 보는 가게 ${newStores}곳 방문"),
            Q("DISTINCT", distinct, null, "서로 다른 가게 ${distinct}곳에서 픽업"),
            Q("REPEAT", 2, null, "같은 가게에서 두 번 픽업 (단골 만들기)"),
            Q("STREAK", streak, null, "거절 없이 ${streak}건 연속 수락"),
            Q("PEAK", peak, null, "저녁 피크(18~21시)에 ${peak}건 완료"),
            Q("LATE", late, null, "밤 11시 이후 ${late}건 완료"),
            Q("KM", km, null, "오늘 ${km}km 달리기"),
            Q("CELLS", cells, null, "지도에서 새 칸 ${cells}개 밝히기"),
            Q("READY", ready, null, "음식 조리완료 상태로 도착 ${ready}번"),
            Q("RARE", rare, fareTh.toString(), "배달료 ${"%,d".format(fareTh)}원 이상 콜 ${rare}건 완료"),
            Q("AREA", 1, "${area.first}|${area.second}|${area.third}", "오늘의 탐방: ${area.first} 들르기"),
            Q("CAMSAFE", cam, null, "후면 카메라 구간 제한속도 지키며 ${cam}번 통과")
        )
    }

    /** 오늘 퀘스트 3개 (날짜 기준 랜덤, 하루 동안 고정) */
    fun ensureQuests(ctx: Context, now: Long): String {
        val d = db(ctx)
        val day = dayKey(now)
        if (scalarLong(d, "SELECT COUNT(*) FROM quests WHERE day=?", day) > 0) return day
        val rnd = Random(day.hashCode() * 31 + 7)
        val all = pool(ctx, now, rnd).shuffled(rnd)
        // 같은 종류 계열이 겹치지 않게 (건수/수입 중 하나만)
        val picked = ArrayList<Q>()
        for (q in all) {
            if (picked.size == 3) break
            if (q.kind == "INCOME" && picked.any { it.kind == "DELIVERIES" }) continue
            if (q.kind == "DELIVERIES" && picked.any { it.kind == "INCOME" }) continue
            picked.add(q)
        }
        picked.forEachIndexed { i, q ->
            val cv = ContentValues()
            cv.put("day", day); cv.put("idx", i); cv.put("kind", q.kind); cv.put("target", q.target)
            cv.put("param", q.param); cv.put("title", q.title); cv.put("progress", 0)
            d.insertWithOnConflict("quests", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
        return day
    }

    private fun cellOf(lat: Double, lng: Double): String? {
        if (lat < LAT0 || lat >= LAT1 || lng < LNG0 || lng >= LNG1) return null
        return "${((lat - LAT0) / DLAT).toInt()},${((lng - LNG0) / DLNG).toInt()}"
    }

    private fun cellsBetween(d: SQLiteDatabase, from: Long, to: Long): HashSet<String> {
        val out = HashSet<String>()
        d.rawQuery("SELECT lat, lng FROM points WHERE ts >= ? AND ts < ? AND (acc IS NULL OR acc <= 40)", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) cellOf(c.getDouble(0), c.getDouble(1))?.let { out.add(it) }
        }
        return out
    }

    private var cacheDay = -1L
    private var cacheBefore: Set<String> = emptySet()

    /** 오늘 이전에 밝힌 칸 (하루 동안 캐시) */
    private fun beforeCells(d: SQLiteDatabase, dayFrom: Long): Set<String> {
        if (cacheDay != dayFrom) {
            cacheBefore = cellsBetween(d, 0, dayFrom)
            cacheDay = dayFrom
        }
        return cacheBefore
    }

    /** 퀘스트 진행도 계산 */
    private fun progressOf(ctx: Context, kind: String, param: String?, from: Long, to: Long): Int {
        val d = db(ctx)
        val f = from.toString()
        val t = to.toString()
        return when (kind) {
            "DELIVERIES" -> scalarLong(d, "SELECT COUNT(*) FROM events WHERE type='DELIVER' AND ts>=? AND ts<?", f, t).toInt()
            "INCOME" -> scalarLong(d, "SELECT SUM(amount) FROM events WHERE type='DELIVER' AND ts>=? AND ts<?", f, t).toInt()
            "NEW_STORES" -> scalarLong(
                d, "SELECT COUNT(*) FROM (SELECT store_id, MIN(ts) m FROM events WHERE store_id IS NOT NULL AND type IN ('ARRIVE','PICKUP') GROUP BY store_id) WHERE m>=? AND m<?", f, t
            ).toInt()
            "DISTINCT" -> scalarLong(d, "SELECT COUNT(DISTINCT store_id) FROM events WHERE type='PICKUP' AND store_id IS NOT NULL AND ts>=? AND ts<?", f, t).toInt()
            "REPEAT" -> scalarLong(d, "SELECT MAX(c) FROM (SELECT COUNT(*) c FROM events WHERE type='PICKUP' AND store_id IS NOT NULL AND ts>=? AND ts<? GROUP BY store_id)", f, t).toInt()
            "STREAK" -> {
                var best = 0
                var cur = 0
                d.rawQuery("SELECT type FROM events WHERE type IN ('ACCEPT','REJECT') AND ts>=? AND ts<? ORDER BY ts", arrayOf(f, t)).use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(0) == "ACCEPT") { cur++; if (cur > best) best = cur } else cur = 0
                    }
                }
                best
            }
            "PEAK", "LATE" -> {
                var n = 0
                val cal = Calendar.getInstance()
                d.rawQuery("SELECT ts FROM events WHERE type='DELIVER' AND ts>=? AND ts<?", arrayOf(f, t)).use { c ->
                    while (c.moveToNext()) {
                        cal.timeInMillis = c.getLong(0)
                        val h = cal.get(Calendar.HOUR_OF_DAY)
                        if (kind == "PEAK" && h in 18..20) n++
                        if (kind == "LATE" && (h >= 23 || h < 6)) n++
                    }
                }
                n
            }
            "KM" -> {
                val km = Db.get(ctx).kmJson(from, to)
                var m = 0.0
                for (k in km.keys()) m += km.getDouble(k)
                (m / 1000).toInt()
            }
            "CELLS" -> scalarLong(d, "SELECT COUNT(*) FROM cellvisits WHERE first>=? AND first<?", f, t).toInt()
            "READY" -> scalarLong(d, "SELECT COUNT(*) FROM events WHERE type='READY' AND ts>=? AND ts<?", f, t).toInt()
            "RARE" -> scalarLong(d, "SELECT COUNT(*) FROM events WHERE type='DELIVER' AND amount>=? AND ts>=? AND ts<?", param ?: "7000", f, t).toInt()
            "AREA" -> {
                val p = (param ?: "").split("|")
                if (p.size < 3) 0 else {
                    val lat = p[1].toDouble()
                    val lng = p[2].toDouble()
                    var hit = 0
                    d.rawQuery("SELECT lat, lng FROM points WHERE ts>=? AND ts<?", arrayOf(f, t)).use { c ->
                        while (c.moveToNext() && hit == 0) if (Db.dist(lat, lng, c.getDouble(0), c.getDouble(1)) < 700) hit = 1
                    }
                    hit
                }
            }
            "CAMSAFE" -> scalarLong(d, "SELECT COUNT(*) FROM events WHERE type='CAMPASS' AND note='safe' AND ts>=? AND ts<?", f, t).toInt()
            else -> 0
        }
    }

    /**
     * 진행도 갱신. 새로 깬 퀘스트, 레벨업, 조기 퇴근권 획득을 안내 문장으로 돌려준다.
     */
    @Synchronized
    fun update(ctx: Context, now: Long = System.currentTimeMillis()): List<String> {
        val d = db(ctx)
        try { Atlas.refresh(ctx) } catch (e: Exception) {}
        val day = ensureQuests(ctx, now)
        val from = dayStart(now)
        val to = from + 24 * 3600_000L
        val msgs = ArrayList<String>()
        val xpBefore = totalXp(ctx)
        data class Row(val idx: Int, val kind: String, val target: Int, val param: String?, val title: String, val done: Boolean)
        val rows = ArrayList<Row>()
        d.rawQuery("SELECT idx, kind, target, param, title, done_ts FROM quests WHERE day=? ORDER BY idx", arrayOf(day)).use { c ->
            while (c.moveToNext()) rows.add(Row(c.getInt(0), c.getString(1), c.getInt(2), if (c.isNull(3)) null else c.getString(3), c.getString(4), !c.isNull(5)))
        }
        for (r in rows) {
            if (r.done) continue
            val p = progressOf(ctx, r.kind, r.param, from, to)
            val cv = ContentValues()
            cv.put("progress", p)
            if (p >= r.target) {
                cv.put("done_ts", now)
                val v = ContentValues()
                v.put("ts", now); v.put("amount", QUEST_REWARD); v.put("note", r.title)
                d.insert("vault", null, v)
                msgs.add("퀘스트 완료! 보상 금고에 3천원이 적립됐어요.")
            }
            d.update("quests", cv, "day=? AND idx=?", arrayOf(day, r.idx.toString()))
        }
        // 레벨업 → 10레벨마다 조기 퇴근권
        val lvBefore = levelOf(xpBefore)[0].toInt()
        val lvAfter = levelOf(totalXp(ctx))[0].toInt()
        if (lvAfter > lvBefore) {
            msgs.add("레벨 업! 레벨 ${lvAfter}이 되었어요.")
            for (lv in (lvBefore + 1)..lvAfter) if (lv % 10 == 0) {
                addTicket(d, now, "레벨 $lv 달성")
                msgs.add("조기 퇴근권을 얻었어요.")
            }
        }
        // 한 달 퀘스트 60개 → 조기 퇴근권 (한 달에 한 번)
        val month = day.substring(0, 7)
        val monthDone = scalarLong(d, "SELECT COUNT(*) FROM quests WHERE done_ts IS NOT NULL AND day LIKE ?", "$month%")
        val src = "$month 월간 퀘스트 60개"
        if (monthDone >= 60 && scalarLong(d, "SELECT COUNT(*) FROM tickets WHERE source=?", src) == 0L) {
            addTicket(d, now, src)
            msgs.add("이번 달 퀘스트 60개 달성! 조기 퇴근권을 얻었어요.")
        }
        return msgs
    }

    private fun addTicket(d: SQLiteDatabase, ts: Long, source: String) {
        val cv = ContentValues()
        cv.put("ts", ts); cv.put("source", source)
        d.insert("tickets", null, cv)
    }

    // ---------------- 화면용 ----------------

    fun stateJson(ctx: Context): String {
        val now = System.currentTimeMillis()
        try {
            update(ctx, now)
        } catch (e: Exception) {
        }
        val d = db(ctx)
        val xp = totalXp(ctx)
        val lv = levelOf(xp)
        val o = JSONObject()
        o.put("xp", xp).put("level", lv[0]).put("cur", lv[1]).put("need", lv[2]).put("title", title(lv[0].toInt()))
        val qs = JSONArray()
        d.rawQuery("SELECT title, progress, target, done_ts, kind FROM quests WHERE day=? ORDER BY idx", arrayOf(dayKey(now))).use { c ->
            while (c.moveToNext()) qs.put(
                JSONObject().put("title", c.getString(0)).put("progress", c.getInt(1)).put("target", c.getInt(2))
                    .put("done", !c.isNull(3)).put("kind", c.getString(4))
            )
        }
        o.put("quests", qs)
        val total = scalarLong(d, "SELECT SUM(amount) FROM vault WHERE amount > 0")
        val paid = -scalarLong(d, "SELECT SUM(amount) FROM vault WHERE amount < 0")
        o.put("vault", JSONObject().put("total", total).put("paid", paid).put("pending", total - paid))
        o.put("questsDone", scalarLong(d, "SELECT COUNT(*) FROM quests WHERE done_ts IS NOT NULL"))
        o.put("monthDone", scalarLong(d, "SELECT COUNT(*) FROM quests WHERE done_ts IS NOT NULL AND day LIKE ?", dayKey(now).substring(0, 7) + "%"))
        val tk = JSONArray()
        d.rawQuery("SELECT id, ts, source, used_ts FROM tickets ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) tk.put(
                JSONObject().put("id", c.getLong(0)).put("ts", c.getLong(1)).put("source", c.getString(2))
                    .put("used", if (c.isNull(3)) JSONObject.NULL else c.getLong(3))
            )
        }
        o.put("tickets", tk)
        val visited = scalarLong(d, "SELECT COUNT(*) FROM cellvisits")
        val todayNew = scalarLong(d, "SELECT COUNT(*) FROM cellvisits WHERE first>=?", dayStart(now).toString())
        o.put("explore", JSONObject().put("visited", visited).put("total", Atlas.grid(ctx).total).put("today", todayNew))
        try { o.put("stats", Atlas.statsOf(ctx)) } catch (e: Exception) {}
        val log = JSONArray()
        d.rawQuery("SELECT ts, amount, note FROM vault ORDER BY id DESC LIMIT 40", null).use { c ->
            while (c.moveToNext()) log.put(JSONObject().put("ts", c.getLong(0)).put("amount", c.getLong(1)).put("note", if (c.isNull(2)) "" else c.getString(2)))
        }
        o.put("vaultLog", log)
        o.put("dex", scalarLong(d, "SELECT COUNT(DISTINCT store_id) FROM events WHERE store_id IS NOT NULL AND type IN ('ARRIVE','PICKUP')"))
        return o.toString()
    }

    /** 금고에서 쓰기 (잔액보다 많이는 못 씀). note 가 "초기화"면 잔액 전부 */
    fun withdraw(ctx: Context, amount: Int, note: String = "사용") {
        val d = db(ctx)
        val pending = scalarLong(d, "SELECT SUM(amount) FROM vault")
        val a = if (note == "초기화") pending.toInt() else minOf(amount.toLong(), pending).toInt()
        if (a <= 0) return
        val cv = ContentValues()
        cv.put("ts", System.currentTimeMillis()); cv.put("amount", -a); cv.put("note", note.take(40))
        d.insert("vault", null, cv)
    }

    fun useTicket(ctx: Context, id: Long) {
        val cv = ContentValues()
        cv.put("used_ts", System.currentTimeMillis())
        db(ctx).update("tickets", cv, "id=? AND used_ts IS NULL", arrayOf(id.toString()))
    }

    /** 정복 지도용 방문한 칸 목록 [[행,열,오늘처음여부]] */
    fun cellsJson(ctx: Context): String {
        val d = db(ctx)
        val now = System.currentTimeMillis()
        val before = beforeCells(d, dayStart(now))
        val all = before + cellsBetween(d, dayStart(now), now + 1)
        val arr = JSONArray()
        for (c in all) {
            val p = c.split(",")
            arr.put(JSONArray().put(p[0].toInt()).put(p[1].toInt()).put(if (c in before) 0 else 1))
        }
        return JSONObject().put("lat0", LAT0).put("lng0", LNG0).put("dlat", DLAT).put("dlng", DLNG)
            .put("rows", ROWS).put("cols", COLS).put("cells", arr).toString()
    }

    /** 가게 도감: 가게별 방문 횟수, 처음 방문일 */
    fun dexJson(ctx: Context): String {
        val d = db(ctx)
        val arr = JSONArray()
        d.rawQuery(
            "SELECT s.id, s.name, COUNT(DISTINCT e.ts / 600000), MIN(e.ts), MAX(e.ts) FROM events e JOIN stores s ON s.id = e.store_id " +
                "WHERE e.type IN ('ARRIVE','PICKUP') GROUP BY s.id ORDER BY MIN(e.ts)", null
        ).use { c ->
            while (c.moveToNext()) arr.put(
                JSONObject().put("id", c.getLong(0)).put("name", c.getString(1)).put("visits", c.getInt(2))
                    .put("first", c.getLong(3)).put("last", c.getLong(4))
            )
        }
        return arr.toString()
    }
}
