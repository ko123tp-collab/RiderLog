package com.rider.log

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 가계부: 새마을금고 알림 인식, 금고(카카오페이=고정비, 네이버페이=생활비, 당근페이=휴무 용돈, 현금=저축),
 * 고정비·휴무일·옮기기 계획 저장. 계산(하루 목표, 오늘의 보스, 옮길 금액)은 화면(index.html)에서 한다.
 */
object Money {
    const val PREFS = "money"
    private const val CHANNEL = "money"
    val POTS = listOf("fixed", "living", "off", "cash")
    const val FEE_PER_CALL = 77

    // 알림 예: [출금] 5,900원 9003-2361-****-5 잔액 33,359원 11/28 23:56 씨유서초청동점
    private val RX = Regex("""\[(입금|출금)\]\s*([\d,]+)원\s+(\S+)\s+잔액\s*(-?[\d,]+)원\s+(\d{1,2})/(\d{1,2})\s+(\d{1,2}):(\d{2})\s*(.*)""")
    private val INCOME_RX = Regex("디비|익일|주급|배민|우아한")
    private val POT_HINT_RX = Regex("카카오페이|네이버페이|당근|ATM|CD|현금|자동화기기", RegexOption.IGNORE_CASE)

    /** 가맹점 이름으로 분류 (위에서부터 먼저 맞는 것) */
    private val CATS = listOf(
        "배달음식" to Regex("배달의민족|우아한형제|쿠팡이츠|요기요|땡겨요"),
        "편의점" to Regex("씨유|CU|GS25|지에스|세븐일레븐|코리아세븐|이마트24|미니스톱", RegexOption.IGNORE_CASE),
        "카페" to Regex("스타벅스|커피|카페|메가엠지씨|컴포즈|빽다방|이디야|투썸|폴바셋|할리스|공차"),
        "식당" to Regex("김밥|국밥|식당|분식|치킨|버거|맥도날드|롯데리아|맘스터치|서브웨이|반점|짬뽕|돈까스|떡볶이|순대|감자탕|냉면|한솥|본죽|도시락|국수|우동|라멘|초밥|고기|족발|보쌈|피자"),
        "마트" to Regex("이마트|홈플러스|롯데마트|다이소|코스트코|노브랜드|하나로|마트|슈퍼"),
        "쇼핑" to Regex("쿠팡|11번가|지마켓|G마켓|옥션|무신사|에이블리|올리브영|네이버쇼핑"),
        "교통·충전" to Regex("택시|카카오T|티머니|주유|충전|고고로"),
        "게임·구독" to Regex("구글|Google|애플|Apple|넷플릭스|스팀|넥슨|넷마블|엔씨|유튜브", RegexOption.IGNORE_CASE)
    )

    @Volatile
    private var ready = false

    fun db(ctx: Context): SQLiteDatabase {
        val w = Db.get(ctx).writableDatabase
        if (!ready) synchronized(this) {
            if (!ready) {
                w.execSQL("CREATE TABLE IF NOT EXISTS bills(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, amount INTEGER NOT NULL, cycle TEXT NOT NULL, day INTEGER, date TEXT, paused INTEGER NOT NULL DEFAULT 0)")
                w.execSQL("CREATE TABLE IF NOT EXISTS bill_paid(bill_id INTEGER NOT NULL, due TEXT NOT NULL, ts INTEGER NOT NULL, tx_id INTEGER, log_id INTEGER, PRIMARY KEY(bill_id, due))")
                w.execSQL("CREATE TABLE IF NOT EXISTS bank_tx(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL, amount INTEGER NOT NULL, balance INTEGER, party TEXT, raw TEXT UNIQUE, target TEXT, status TEXT)")
                w.execSQL("CREATE TABLE IF NOT EXISTS pot_log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, pot TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT, tx_id INTEGER)")
                w.execSQL("CREATE TABLE IF NOT EXISTS offdays(day TEXT PRIMARY KEY, off INTEGER NOT NULL)")
                w.execSQL("CREATE TABLE IF NOT EXISTS plans(tx_id INTEGER PRIMARY KEY, json TEXT NOT NULL)")
                w.execSQL("CREATE TABLE IF NOT EXISTS party_map(party TEXT PRIMARY KEY, target TEXT NOT NULL, always INTEGER NOT NULL DEFAULT 0)")
                w.execSQL("CREATE TABLE IF NOT EXISTS bill_over(bill_id INTEGER NOT NULL, due TEXT NOT NULL, amount INTEGER, new_due TEXT, PRIMARY KEY(bill_id, due))")
                w.execSQL("CREATE TABLE IF NOT EXISTS loans(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, from_pot TEXT NOT NULL, to_pot TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT)")
                try { w.execSQL("ALTER TABLE bills ADD COLUMN variable INTEGER NOT NULL DEFAULT 0") } catch (e: Exception) {}
                try { w.execSQL("ALTER TABLE bill_paid ADD COLUMN amount INTEGER") } catch (e: Exception) {}
                try { w.execSQL("ALTER TABLE bill_paid ADD COLUMN source TEXT") } catch (e: Exception) {}
                w.execSQL("CREATE INDEX IF NOT EXISTS idx_bank_ts ON bank_tx(ts)")
                w.execSQL("CREATE INDEX IF NOT EXISTS idx_pot_ts ON pot_log(ts)")
                ready = true
            }
        }
        seed(ctx, w)
        return w
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 처음 한 번: 알려 준 고정비 목록을 넣어 둠 (앱에서 고치고 지울 수 있음) */
    private fun seed(ctx: Context, w: SQLiteDatabase) {
        val p = prefs(ctx)
        if (p.getBoolean("seeded", false)) return
        p.edit().putBoolean("seeded", true).putString("start", dayKey(System.currentTimeMillis())).apply()
        // day: 매월=날짜, 매주=요일(0 일요일 ~ 6 토요일)
        val list = listOf(
            arrayOf("월세", 400000, "month", 5, 0), arrayOf("쿠팡 멤버십", 8000, "month", 6, 0),
            arrayOf("케어원", 60000, "month", 20, 0), arrayOf("관리비", 100000, "month", 25, 0),
            arrayOf("운전자보험", 31000, "month", 26, 0), arrayOf("유튜브 프리미엄", 14900, "month", 26, 0),
            arrayOf("고고로", 130900, "week", 4, 0), arrayOf("핸드폰 요금", 10000, "week", 5, 0),
            arrayOf("마운자로", 430000, "month", 1, 1)
        )
        for (b in list) {
            val cv = ContentValues()
            cv.put("name", b[0] as String); cv.put("amount", b[1] as Int); cv.put("cycle", b[2] as String)
            cv.put("day", b[3] as Int); cv.put("paused", b[4] as Int)
            w.insert("bills", null, cv)
        }
    }

    // ---------------- 날짜 ----------------

    fun dayKey(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(Game.dayStart(ts)))

    private fun keyToCal(k: String): Calendar {
        val p = k.split("-").map { it.toInt() }
        val c = Calendar.getInstance()
        c.set(p[0], p[1] - 1, p[2], 6, 0, 0); c.set(Calendar.MILLISECOND, 0)
        return c
    }

    private fun calKey(c: Calendar): String = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(c.time)

    // ---------------- 설정 ----------------

    fun settings(ctx: Context): JSONObject {
        val d = JSONObject()
            .put("living", 20000).put("workSpend", 20000)
            .put("offBase", 30000).put("offBonus", 50000)
            .put("offWeekdays", JSONArray())
            .put("saveSteps", JSONArray(listOf(5000, 10000, 15000, 20000, 30000)))
            .put("streakUp", 5)
        val s = prefs(ctx).getString("settings", null) ?: return d
        try {
            val j = JSONObject(s)
            for (k in j.keys()) d.put(k, j.get(k))
        } catch (e: Exception) {
        }
        return d
    }

    fun setSettings(ctx: Context, json: String) {
        val cur = settings(ctx)
        val j = JSONObject(json)
        for (k in j.keys()) cur.put(k, j.get(k))
        prefs(ctx).edit().putString("settings", cur.toString()).apply()
    }

    fun isOff(ctx: Context, day: String, s: JSONObject = settings(ctx)): Boolean {
        db(ctx).rawQuery("SELECT off FROM offdays WHERE day=?", arrayOf(day)).use { c -> if (c.moveToFirst()) return c.getInt(0) == 1 }
        val dow = keyToCal(day).get(Calendar.DAY_OF_WEEK) - 1
        val arr = s.optJSONArray("offWeekdays") ?: return false
        for (i in 0 until arr.length()) if (arr.optInt(i, -1) == dow) return true
        return false
    }

    /** state: off(휴무) / work(근무) / auto(요일 설정대로) */
    fun setOff(ctx: Context, day: String, state: String) {
        val d = db(ctx)
        if (state == "auto") d.delete("offdays", "day=?", arrayOf(day))
        else {
            val cv = ContentValues()
            cv.put("day", day); cv.put("off", if (state == "off") 1 else 0)
            d.insertWithOnConflict("offdays", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // ---------------- 금고 ----------------

    private fun scalar(d: SQLiteDatabase, sql: String, vararg a: String): Long {
        d.rawQuery(sql, arrayOf(*a)).use { c -> return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }

    fun potBalance(ctx: Context, pot: String): Long = scalar(db(ctx), "SELECT SUM(amount) FROM pot_log WHERE pot=?", pot)

    fun addPot(ctx: Context, pot: String, amount: Int, note: String, txId: Long? = null, ts: Long = System.currentTimeMillis()): Long {
        if (pot !in POTS || amount == 0) return -1
        val cv = ContentValues()
        cv.put("ts", ts); cv.put("pot", pot); cv.put("amount", amount); cv.put("note", note.take(40))
        if (txId != null) cv.put("tx_id", txId)
        return db(ctx).insert("pot_log", null, cv)
    }

    /** 금고 잔액을 실제 잔액에 맞춤 */
    fun setPotBalance(ctx: Context, pot: String, balance: Int) {
        val diff = balance - potBalance(ctx, pot)
        if (diff != 0L) addPot(ctx, pot, diff.toInt(), "잔액 맞춤")
    }

    fun deletePotLog(ctx: Context, id: Long) {
        db(ctx).delete("pot_log", "id=? AND tx_id IS NULL", arrayOf(id.toString()))
    }

    /**
     * 지난 날의 생활비·휴무 용돈을 금고에서 빼 둠 (그날 쓴 것으로 봄).
     * 생활비는 매일, 휴무 용돈은 휴무일에만. 금고에 있는 만큼만 뺀다.
     */
    @Synchronized
    fun consumeDaily(ctx: Context) {
        val p = prefs(ctx)
        val today = dayKey(System.currentTimeMillis())
        val from = p.getString("consumed", null)
        if (from == null) {
            p.edit().putString("consumed", today).apply(); return
        }
        if (from >= today) return
        val s = settings(ctx)
        val living = s.optInt("living", 20000)
        val offMax = s.optInt("offBonus", 50000)
        val c = keyToCal(from)
        var guard = 0
        while (calKey(c) < today && guard++ < 62) {
            val k = calKey(c)
            val md = "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}"
            val end = c.timeInMillis + 24 * 3600_000L - 60_000L
            val lb = potBalance(ctx, "living")
            val la = minOf(lb, living.toLong()).toInt()
            if (la > 0) addPot(ctx, "living", -la, "$md 생활비", ts = end)
            if (isOff(ctx, k, s)) {
                val ob = potBalance(ctx, "off")
                val oa = minOf(ob, offMax.toLong()).toInt()
                if (oa > 0) addPot(ctx, "off", -oa, "$md 휴무 용돈", ts = end)
            }
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        p.edit().putString("consumed", today).apply()
    }

    // ---------------- 고정비 ----------------

    fun saveBill(ctx: Context, json: String): Long {
        val j = JSONObject(json)
        val cv = ContentValues()
        cv.put("name", j.optString("name", "고정비").take(30))
        cv.put("amount", j.optInt("amount", 0))
        cv.put("cycle", j.optString("cycle", "month"))
        if (j.has("day") && !j.isNull("day")) cv.put("day", j.optInt("day")) else cv.putNull("day")
        if (j.has("date") && !j.isNull("date")) cv.put("date", j.optString("date")) else cv.putNull("date")
        cv.put("paused", if (j.optBoolean("paused", false)) 1 else 0)
        cv.put("variable", if (j.optBoolean("variable", false)) 1 else 0)
        val id = j.optLong("id", 0L)
        val d = db(ctx)
        return if (id > 0) {
            d.update("bills", cv, "id=?", arrayOf(id.toString())); id
        } else d.insert("bills", null, cv)
    }

    fun deleteBill(ctx: Context, id: Long) {
        val d = db(ctx)
        d.delete("bills", "id=?", arrayOf(id.toString()))
        d.delete("bill_over", "bill_id=?", arrayOf(id.toString()))
    }

    /** 이번 회차만 금액·날짜 바꾸기 (due = 원래 날짜) */
    fun setOver(ctx: Context, id: Long, due: String, amount: Int?, newDue: String?) {
        val cv = ContentValues()
        cv.put("bill_id", id); cv.put("due", due)
        if (amount != null) cv.put("amount", amount) else cv.putNull("amount")
        if (!newDue.isNullOrBlank() && newDue != due) cv.put("new_due", newDue) else cv.putNull("new_due")
        db(ctx).insertWithOnConflict("bill_over", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearOver(ctx: Context, id: Long, due: String) {
        db(ctx).delete("bill_over", "bill_id=? AND due=?", arrayOf(id.toString(), due))
    }

    fun setPaused(ctx: Context, id: Long, paused: Boolean) {
        val cv = ContentValues()
        cv.put("paused", if (paused) 1 else 0)
        db(ctx).update("bills", cv, "id=?", arrayOf(id.toString()))
    }

    private fun billInfo(d: SQLiteDatabase, id: Long): Pair<String, Int>? {
        d.rawQuery("SELECT name, amount FROM bills WHERE id=?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) Pair(c.getString(0), c.getInt(1)) else null
        }
    }

    /** 냈어요. fromPot 이면 고정비 금고(카카오페이)에서 빠진 것으로 기록 */
    fun billPaid(ctx: Context, id: Long, due: String, fromPot: Boolean, txId: Long? = null) =
        billPay(ctx, id, due, if (fromPot) "fixed" else "mg", null, txId)

    /** 납부: source 는 돈이 실제로 나간 곳 (fixed/living/off/cash 금고, 또는 mg=새마을금고) */
    fun billPay(ctx: Context, id: Long, due: String, source: String, amount: Int?, txId: Long? = null) {
        val d = db(ctx)
        val b = billInfo(d, id) ?: return
        billUnpaid(ctx, id, due)
        val amt = amount ?: b.second
        var logId: Long? = null
        if (source in POTS) logId = addPot(ctx, source, -amt, "${b.first} 납부")
        val cv = ContentValues()
        cv.put("bill_id", id); cv.put("due", due); cv.put("ts", System.currentTimeMillis())
        cv.put("amount", amt); cv.put("source", source)
        if (txId != null) cv.put("tx_id", txId)
        if (logId != null && logId > 0) cv.put("log_id", logId)
        d.insertWithOnConflict("bill_paid", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun billUnpaid(ctx: Context, id: Long, due: String) {
        val d = db(ctx)
        d.rawQuery("SELECT log_id FROM bill_paid WHERE bill_id=? AND due=?", arrayOf(id.toString(), due)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) d.delete("pot_log", "id=?", arrayOf(c.getLong(0).toString()))
        }
        d.delete("bill_paid", "bill_id=? AND due=?", arrayOf(id.toString(), due))
    }

    // ---------------- 금고끼리 옮기기 · 내부 대출 ----------------

    fun transfer(ctx: Context, from: String, to: String, amount: Int, note: String) {
        if (from !in POTS || to !in POTS || from == to || amount <= 0) return
        val now = System.currentTimeMillis()
        addPot(ctx, from, -amount, note, ts = now)
        addPot(ctx, to, amount, note, ts = now)
    }

    /** from 금고에서 to 금고로 빌림. 나중에 from 금고로 들어오는 돈이 먼저 갚는 데 쓰인 걸로 계산됨 */
    fun loan(ctx: Context, from: String, to: String, amount: Int, note: String) {
        if (from !in POTS || to !in POTS || from == to || amount <= 0) return
        val cv = ContentValues()
        cv.put("ts", System.currentTimeMillis()); cv.put("from_pot", from); cv.put("to_pot", to); cv.put("amount", amount); cv.put("note", note.take(40))
        db(ctx).insert("loans", null, cv)
        transfer(ctx, from, to, amount, "대출 · " + note.take(30))
    }

    fun deleteLoan(ctx: Context, id: Long) {
        db(ctx).delete("loans", "id=?", arrayOf(id.toString()))
    }

    /** 주간 잔액 점검 완료 (경험치 보상용 횟수) */
    fun checkDone(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putInt("checks", p.getInt("checks", 0) + 1).putString("lastCheck", dayKey(System.currentTimeMillis())).apply()
    }

    fun checkXp(ctx: Context): Long = prefs(ctx).getInt("checks", 0) * 2000L

    // ---------------- 은행 알림 ----------------

    class Parsed(val kind: String, val amount: Int, val balance: Int, val ts: Long, val party: String, val raw: String)

    fun parse(text: String, now: Long = System.currentTimeMillis()): Parsed? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        val m = RX.find(t) ?: return null
        val g = m.groupValues
        val kind = if (g[1] == "입금") "in" else "out"
        val amount = g[2].replace(",", "").toIntOrNull() ?: return null
        val balance = g[4].replace(",", "").toIntOrNull() ?: 0
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val mon = g[5].toInt()
        val year = if (mon > cal.get(Calendar.MONTH) + 2) cal.get(Calendar.YEAR) - 1 else cal.get(Calendar.YEAR)
        cal.set(year, mon - 1, g[6].toInt(), g[7].toInt(), g[8].toInt(), 0)
        cal.set(Calendar.MILLISECOND, 0)
        return Parsed(kind, amount, balance, cal.timeInMillis, g[9].trim(), m.value)
    }

    /** 알림 한 줄을 받아 저장·분류. 새로 저장했으면 id, 이미 있던 거면 -1 */
    @Synchronized
    fun ingest(ctx: Context, text: String): Long {
        val p = parse(text) ?: return -1
        val d = db(ctx)
        val cv = ContentValues()
        cv.put("ts", p.ts); cv.put("kind", p.kind); cv.put("amount", p.amount); cv.put("balance", p.balance)
        cv.put("party", p.party); cv.put("raw", p.raw)
        val id = d.insertWithOnConflict("bank_tx", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (id <= 0) return -1
        classify(ctx, id, p)
        return id
    }

    private fun mapOf(d: SQLiteDatabase, party: String): Pair<String, Boolean>? {
        if (party.isBlank()) return null
        d.rawQuery("SELECT target, always FROM party_map WHERE party=?", arrayOf(party)).use { c ->
            return if (c.moveToFirst()) Pair(c.getString(0), c.getInt(1) == 1) else null
        }
    }

    private fun learn(d: SQLiteDatabase, party: String, target: String, always: Boolean) {
        if (party.isBlank()) return
        val cv = ContentValues()
        cv.put("party", party); cv.put("target", target); cv.put("always", if (always) 1 else 0)
        d.insertWithOnConflict("party_map", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun categoryOf(party: String): String? = CATS.firstOrNull { it.second.containsMatchIn(party) }?.first

    private fun classify(ctx: Context, id: Long, p: Parsed) {
        val d = db(ctx)
        var target: String
        var status: String
        val mapped = mapOf(d, p.party)
        if (p.kind == "in") {
            when {
                INCOME_RX.containsMatchIn(p.party) -> { target = "income"; status = "auto" }
                mapped != null && mapped.second -> { target = mapped.first; status = "auto" }
                mapped != null && mapped.first.startsWith("potback:") -> { target = mapped.first; status = "ask" }
                POT_HINT_RX.containsMatchIn(p.party) -> { target = "etcin"; status = "ask" }
                else -> { target = "etcin"; status = "auto" }
            }
        } else {
            val pot = matchPendingMove(ctx, p.amount)
            when {
                pot != null -> {
                    target = "pot:$pot"; status = "auto"
                    if (mapped == null || !mapped.second) learn(d, p.party, "pot:$pot", false)
                }
                mapped != null && mapped.second -> { target = mapped.first; status = "auto" }
                mapped != null && mapped.first.startsWith("pot:") -> { target = mapped.first; status = "ask" }
                mapped != null && mapped.first.startsWith("cat:") -> { target = mapped.first; status = "auto" }
                else -> {
                    val cat = categoryOf(p.party)
                    if (cat != null && !POT_HINT_RX.containsMatchIn(p.party)) { target = "cat:$cat"; status = "auto" }
                    else { target = "cat:기타"; status = "ask" }
                }
            }
        }
        val cv = ContentValues()
        cv.put("target", target); cv.put("status", status)
        d.update("bank_tx", cv, "id=?", arrayOf(id.toString()))
        applyTarget(ctx, id, target, status)
        if (p.kind == "in" && target == "income") notifyIncome(ctx, p.amount)
    }

    /** 분류에 따라 금고 기록을 맞춤 (기존 기록은 지우고 다시) */
    private fun applyTarget(ctx: Context, id: Long, target: String, status: String) {
        val d = db(ctx)
        d.delete("pot_log", "tx_id=?", arrayOf(id.toString()))
        d.rawQuery("SELECT bill_id, due FROM bill_paid WHERE tx_id=?", arrayOf(id.toString())).use { c ->
            val rows = ArrayList<Pair<Long, String>>()
            while (c.moveToNext()) rows.add(Pair(c.getLong(0), c.getString(1)))
            for (r in rows) billUnpaid(ctx, r.first, r.second)
        }
        if (status == "ask") return // 확인 전에는 금고에 반영하지 않음
        var amount = 0
        var ts = System.currentTimeMillis()
        d.rawQuery("SELECT amount, ts FROM bank_tx WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) { amount = c.getInt(0); ts = c.getLong(1) }
        }
        when {
            target.startsWith("pot:") -> addPot(ctx, target.substring(4), amount, "새마을금고에서 옮김", id, ts)
            target.startsWith("potback:") -> addPot(ctx, target.substring(8), -amount, "새마을금고로 되돌림", id, ts)
            target.startsWith("bill:") -> {
                val p = target.split(":")
                if (p.size >= 3) billPay(ctx, p[1].toLong(), p[2], "mg", amount, id)
            }
        }
    }

    /** 화면에서 분류를 고르거나 확인했을 때 */
    fun assign(ctx: Context, id: Long, target: String, remember: Boolean) {
        val d = db(ctx)
        var party = ""
        d.rawQuery("SELECT party FROM bank_tx WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) party = c.getString(0) ?: "" }
        val cv = ContentValues()
        cv.put("target", target); cv.put("status", "ok")
        d.update("bank_tx", cv, "id=?", arrayOf(id.toString()))
        applyTarget(ctx, id, target, "ok")
        if (target.startsWith("pot:")) linkMove(ctx, id, target.substring(4))
        if (remember) {
            val t = if (target.startsWith("bill:")) target.split(":").take(2).joinToString(":") else target
            learn(d, party, t, !t.startsWith("bill:"))
        } else if (target.startsWith("pot:") || target.startsWith("potback:")) {
            learn(d, party, target, false)
        }
    }

    // ---------------- 옮기기 계획 ----------------

    fun savePlan(ctx: Context, txId: Long, json: String) {
        val cv = ContentValues()
        cv.put("tx_id", txId); cv.put("json", json)
        db(ctx).insertWithOnConflict("plans", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun recentPlans(d: SQLiteDatabase): List<Pair<Long, JSONArray>> {
        val out = ArrayList<Pair<Long, JSONArray>>()
        val since = System.currentTimeMillis() - 3 * 24 * 3600_000L
        d.rawQuery("SELECT p.tx_id, p.json FROM plans p JOIN bank_tx t ON t.id = p.tx_id WHERE t.ts >= ? ORDER BY t.ts", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) try { out.add(Pair(c.getLong(0), JSONArray(c.getString(1)))) } catch (e: Exception) {}
        }
        return out
    }

    private fun writePlan(d: SQLiteDatabase, txId: Long, arr: JSONArray) {
        val cv = ContentValues()
        cv.put("json", arr.toString())
        d.update("plans", cv, "tx_id=?", arrayOf(txId.toString()))
    }

    /** 안 옮긴(또는 직접 체크만 한) 계획 중 금액이 딱 맞는 게 있으면 그 금고 */
    private fun matchPendingMove(ctx: Context, amount: Int): String? {
        val d = db(ctx)
        for ((txId, arr) in recentPlans(d)) {
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val done = m.optInt("done", 0)
                if (done == 1 || m.optInt("amount") != amount) continue
                if (done == 2 && m.has("log")) d.delete("pot_log", "id=?", arrayOf(m.getLong("log").toString()))
                m.put("done", 1); m.remove("log")
                writePlan(d, txId, arr)
                return m.optString("pot")
            }
        }
        return null
    }

    /** 직접 분류한 이체를 그 금고의 남은 계획에 연결 */
    private fun linkMove(ctx: Context, id: Long, pot: String) {
        val d = db(ctx)
        for ((txId, arr) in recentPlans(d)) {
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optInt("done", 0) == 1 || m.optString("pot") != pot) continue
                if (m.optInt("done", 0) == 2 && m.has("log")) d.delete("pot_log", "id=?", arrayOf(m.getLong("log").toString()))
                m.put("done", 1); m.remove("log")
                writePlan(d, txId, arr)
                return
            }
        }
    }

    /** 알림을 못 받았을 때 '옮겼어요'로 직접 체크 (나중에 알림이 오면 자동으로 합쳐짐) */
    fun moveDone(ctx: Context, txId: Long, idx: Int) {
        val d = db(ctx)
        for ((tid, arr) in recentPlans(d)) {
            if (tid != txId || idx >= arr.length()) continue
            val m = arr.getJSONObject(idx)
            if (m.optInt("done", 0) != 0) return
            val log = addPot(ctx, m.optString("pot"), m.optInt("amount"), "직접 옮김 체크")
            m.put("done", 2); m.put("log", log)
            writePlan(d, tid, arr)
        }
    }

    // ---------------- 오늘의 보스 (운행 중 음성) ----------------

    fun setBoss(ctx: Context, json: String) {
        prefs(ctx).edit().putString("boss", json).apply()
    }

    /** 운행 중 수입이 본전선·저축선을 넘으면 한 번씩 알려 줌 */
    fun bossCheck(ctx: Context): String? {
        val p = prefs(ctx)
        val j = try { JSONObject(p.getString("boss", null) ?: return null) } catch (e: Exception) { return null }
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        if (j.optString("day") != today) return null
        val boss = j.optInt("boss", 0)
        val goal = j.optInt("goal", 0)
        val rate = j.optDouble("rate", 0.04)
        if (boss <= 0) return null
        val from = Game.dayStart(now)
        var gross = 0L
        var n = 0
        Db.get(ctx).readableDatabase.rawQuery(
            "SELECT amount FROM events WHERE type='DELIVER' AND ts>=? AND ts<?", arrayOf(from.toString(), (from + 24 * 3600_000L).toString())
        ).use { c -> while (c.moveToNext()) { n++; if (!c.isNull(0)) gross += c.getInt(0) } }
        val net = gross * (1 - rate) - FEE_PER_CALL * n
        val said = p.getString("bossSaid", "") ?: ""
        if (goal > boss && net >= goal && said != "$today:goal") {
            p.edit().putString("bossSaid", "$today:goal").apply()
            return "저축 목표까지 벌었어요! 오늘은 완벽한 날이에요."
        }
        if (net >= boss && said != "$today:boss" && said != "$today:goal") {
            p.edit().putString("bossSaid", "$today:boss").apply()
            return "오늘의 보스를 잡았어요! 지금부터 버는 돈은 저축이에요."
        }
        return null
    }

    // ---------------- 알림 ----------------

    private fun notifyIncome(ctx: Context, amount: Int) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "가계부", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val i = Intent(ctx, MainActivity::class.java).putExtra("tab", "money").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_r)
                .setContentTitle("정산 ${"%,d".format(amount)}원 들어왔어요")
                .setContentText("눌러서 금고별로 나눠 담을 금액을 확인하세요")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(7701, n)
        } catch (e: Exception) {
        }
    }

    // ---------------- 화면용 ----------------

    fun stateJson(ctx: Context): String {
        try { consumeDaily(ctx) } catch (e: Exception) {}
        val d = db(ctx)
        val o = JSONObject()
        o.put("settings", settings(ctx))
        o.put("start", prefs(ctx).getString("start", dayKey(System.currentTimeMillis())))
        val bills = JSONArray()
        d.rawQuery("SELECT id, name, amount, cycle, day, date, paused, variable FROM bills ORDER BY id", null).use { c ->
            while (c.moveToNext()) bills.put(
                JSONObject().put("id", c.getLong(0)).put("name", c.getString(1)).put("amount", c.getInt(2)).put("cycle", c.getString(3))
                    .put("day", if (c.isNull(4)) JSONObject.NULL else c.getInt(4)).put("date", if (c.isNull(5)) JSONObject.NULL else c.getString(5))
                    .put("paused", c.getInt(6) == 1).put("variable", c.getInt(7) == 1)
            )
        }
        val overs = JSONArray()
        d.rawQuery("SELECT bill_id, due, amount, new_due FROM bill_over", null).use { c ->
            while (c.moveToNext()) overs.put(
                JSONObject().put("bill", c.getLong(0)).put("due", c.getString(1)).put("amount", if (c.isNull(2)) JSONObject.NULL else c.getInt(2))
                    .put("newDue", if (c.isNull(3)) JSONObject.NULL else c.getString(3))
            )
        }
        o.put("overs", overs)
        val loans = JSONArray()
        d.rawQuery("SELECT id, ts, from_pot, to_pot, amount, note FROM loans ORDER BY ts", null).use { c ->
            while (c.moveToNext()) loans.put(
                JSONObject().put("id", c.getLong(0)).put("ts", c.getLong(1)).put("from", c.getString(2)).put("to", c.getString(3))
                    .put("amount", c.getInt(4)).put("note", c.getString(5) ?: "")
            )
        }
        o.put("loans", loans)
        // 대출 상환 계산용: 빌려준 금고로 들어온 돈 (가장 오래된 대출 이후 전부)
        val inflow = JSONArray()
        var oldest = Long.MAX_VALUE
        for (i in 0 until loans.length()) oldest = minOf(oldest, loans.getJSONObject(i).getLong("ts"))
        if (oldest != Long.MAX_VALUE) d.rawQuery("SELECT id, ts, pot, amount, note FROM pot_log WHERE ts >= ? AND amount > 0 ORDER BY ts, id", arrayOf(oldest.toString())).use { c ->
            while (c.moveToNext()) inflow.put(JSONObject().put("id", c.getLong(0)).put("ts", c.getLong(1)).put("pot", c.getString(2)).put("amount", c.getInt(3)).put("note", c.getString(4) ?: ""))
        }
        o.put("inflow", inflow)
        o.put("lastCheck", prefs(ctx).getString("lastCheck", "") ?: "")
        o.put("bills", bills)
        val paid = JSONArray()
        d.rawQuery("SELECT bill_id, due, ts, tx_id, amount, source FROM bill_paid WHERE due >= ?", arrayOf(dayKey(System.currentTimeMillis() - 70 * 24 * 3600_000L))).use { c ->
            while (c.moveToNext()) paid.put(
                JSONObject().put("bill", c.getLong(0)).put("due", c.getString(1)).put("ts", c.getLong(2)).put("tx", if (c.isNull(3)) JSONObject.NULL else c.getLong(3))
                    .put("amount", if (c.isNull(4)) JSONObject.NULL else c.getInt(4)).put("source", c.getString(5) ?: "")
            )
        }
        o.put("paid", paid)
        val since = (System.currentTimeMillis() - 62 * 24 * 3600_000L).toString()
        val txs = JSONArray()
        d.rawQuery("SELECT id, ts, kind, amount, balance, party, target, status FROM bank_tx WHERE ts >= ? ORDER BY ts DESC, id DESC", arrayOf(since)).use { c ->
            while (c.moveToNext()) txs.put(
                JSONObject().put("id", c.getLong(0)).put("ts", c.getLong(1)).put("kind", c.getString(2)).put("amount", c.getInt(3))
                    .put("balance", if (c.isNull(4)) JSONObject.NULL else c.getInt(4)).put("party", c.getString(5) ?: "")
                    .put("target", c.getString(6) ?: "").put("status", c.getString(7) ?: "")
            )
        }
        o.put("txs", txs)
        // 가장 최근 알림의 잔액 = 새마을금고 잔액
        d.rawQuery("SELECT balance, ts FROM bank_tx ORDER BY ts DESC, id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) o.put("bank", JSONObject().put("balance", c.getInt(0)).put("ts", c.getLong(1)))
        }
        val pots = JSONObject()
        for (p in POTS) pots.put(p, potBalance(ctx, p))
        o.put("pots", pots)
        val log = JSONArray()
        d.rawQuery("SELECT id, ts, pot, amount, note, tx_id FROM pot_log WHERE ts >= ? ORDER BY ts DESC, id DESC", arrayOf((System.currentTimeMillis() - 120 * 24 * 3600_000L).toString())).use { c ->
            while (c.moveToNext()) log.put(
                JSONObject().put("id", c.getLong(0)).put("ts", c.getLong(1)).put("pot", c.getString(2)).put("amount", c.getInt(3))
                    .put("note", c.getString(4) ?: "").put("tx", if (c.isNull(5)) JSONObject.NULL else c.getLong(5))
            )
        }
        o.put("potLog", log)
        val off = JSONObject()
        d.rawQuery("SELECT day, off FROM offdays", null).use { c -> while (c.moveToNext()) off.put(c.getString(0), c.getInt(1)) }
        o.put("off", off)
        val plans = JSONArray()
        for ((tx, arr) in recentPlans(d)) plans.put(JSONObject().put("tx", tx).put("moves", arr))
        o.put("plans", plans)
        val allPlans = JSONArray()
        d.rawQuery("SELECT tx_id FROM plans", null).use { c -> while (c.moveToNext()) allPlans.put(c.getLong(0)) }
        o.put("planned", allPlans)
        return o.toString()
    }

    /** 서버 저장용 (알림 원문·계좌번호는 빼고) */
    fun exportJson(ctx: Context): String {
        val j = JSONObject(stateJson(ctx))
        j.remove("plans")
        return j.toString(1)
    }
}
