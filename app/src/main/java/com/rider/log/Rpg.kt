package com.rider.log

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 방치형 사냥 게임(RPG) 지원.
 * - 게임 규칙과 화면은 assets/rpg 폴더의 JS에서 처리하고, 여기서는 저장·입력값·그림/소리 파일만 담당.
 * - 캐릭터·배경·소리 파일은 유료/라이선스 에셋이라 공개 저장소(앱 코드)에 넣지 않는다.
 *   비공개 기록 저장소(riderlog-data)의 rpg-assets/ 폴더에서 동기화 토큰으로 한 번 받아 폰에 보관한다.
 */
object Rpg {

    private const val PREFS = "rpg"
    private const val DIR = "rpg"
    private const val REMOTE = "rpg-assets"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 저장 ----------------

    fun load(ctx: Context): String = prefs(ctx).getString("state", "") ?: ""

    fun save(ctx: Context, json: String) {
        if (json.length > 200_000) return
        try { JSONObject(json) } catch (e: Exception) { return }
        prefs(ctx).edit().putString("state", json).apply()
    }

    fun stateOrNull(ctx: Context): JSONObject? = try { JSONObject(load(ctx)) } catch (e: Exception) { null }

    // ---------------- 계산에 필요한 입력값 ----------------

    /**
     * since 이후 운행 구간, 그 시점의 경험치와 이후 경험치 변화, 배달 포인트 합계.
     * 배달 포인트 = 배달료 100원당 1점 + 완료 퀘스트 1개당 100점
     */
    fun inputsJson(ctx: Context, since: Long): String {
        val d = Db.get(ctx).readableDatabase
        Game.stateJson(ctx) // 퀘스트 테이블 준비 겸 최신화
        val o = JSONObject()
        val shifts = JSONArray()
        d.rawQuery("SELECT start_ts, end_ts FROM shifts WHERE end_ts IS NULL OR end_ts > ? ORDER BY start_ts", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) shifts.put(JSONArray().put(c.getLong(0)).put(if (c.isNull(1)) JSONObject.NULL else c.getLong(1)))
        }
        o.put("shifts", shifts)
        fun scalar(sql: String, vararg a: String): Long {
            d.rawQuery(sql, arrayOf(*a)).use { c -> return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
        }
        val fareBefore = scalar("SELECT SUM(amount) FROM events WHERE type='DELIVER' AND amount IS NOT NULL AND ts < ?", since.toString())
        val questBefore = scalar("SELECT COUNT(*) FROM quests WHERE done_ts IS NOT NULL AND done_ts < ?", since.toString())
        val bonus = Atlas.bonusXp(ctx)
        o.put("xpBefore", fareBefore + questBefore * Game.QUEST_XP + bonus)
        val xs = JSONArray()
        d.rawQuery(
            "SELECT ts, amount FROM events WHERE type='DELIVER' AND amount IS NOT NULL AND ts >= ? " +
                "UNION ALL SELECT done_ts, ${Game.QUEST_XP} FROM quests WHERE done_ts IS NOT NULL AND done_ts >= ? ORDER BY 1",
            arrayOf(since.toString(), since.toString())
        ).use { c -> while (c.moveToNext()) xs.put(JSONArray().put(c.getLong(0)).put(c.getLong(1))) }
        o.put("xpEvents", xs)
        val fares = scalar("SELECT SUM(amount) FROM events WHERE type='DELIVER' AND amount IS NOT NULL")
        val quests = scalar("SELECT COUNT(*) FROM quests WHERE done_ts IS NOT NULL")
        o.put("bp", fares / 100 + quests * 100)
        o.put("xp", fares + quests * Game.QUEST_XP + bonus)
        try { o.put("stats", Atlas.statsOf(ctx)) } catch (e: Exception) {}
        o.put("running", Db.get(ctx).openShiftId() != null)
        o.put("now", System.currentTimeMillis())
        return o.toString()
    }

    // ---------------- 그림·소리 파일 ----------------

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun assetsJson(ctx: Context): String {
        val m = File(dir(ctx), "manifest.json")
        var ok = false
        var ver = ""
        if (m.exists()) try {
            val j = JSONObject(m.readText())
            ver = j.optString("version")
            val files = j.getJSONArray("files")
            ok = (0 until files.length()).all { File(dir(ctx), files.getJSONObject(it).getString("name")).exists() }
        } catch (e: Exception) {}
        return JSONObject().put("ok", ok).put("version", ver).put("sync", Sync.configured(ctx)).toString()
    }

    private fun get(repo: String, token: String, path: String): ByteArray {
        val c = (URL("https://api.github.com/repos/$repo/contents/$path").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github.raw")
            connectTimeout = 15000
            readTimeout = 60000
        }
        val code = c.responseCode
        if (code == 404) throw Exception("기록 저장소에 게임 파일이 없어요 ($path)")
        if (code == 401 || code == 403) throw Exception("권한 오류($code) — 동기화 토큰을 확인해 주세요")
        if (code != 200) throw Exception("GitHub 응답 $code")
        return c.inputStream.use { it.readBytes() }.also { c.disconnect() }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** 비공개 저장소에서 게임 파일을 받는다 (바뀐 파일만). 백그라운드 스레드에서 호출 */
    fun download(ctx: Context): JSONObject {
        val p = Sync.prefs(ctx)
        val repo = p.getString("repo", "") ?: ""
        val token = p.getString("token", "") ?: ""
        if (repo.isBlank() || token.isBlank()) return JSONObject().put("ok", false).put("err", "설정 → 기록 자동 업로드를 먼저 연결해 주세요")
        return try {
            val manifest = get(repo, token, "$REMOTE/manifest.json")
            val files = JSONObject(String(manifest)).getJSONArray("files")
            var got = 0
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val name = f.getString("name")
                if (name.contains("..") || name.startsWith("/")) continue
                val local = File(dir(ctx), name)
                if (local.exists() && sha256(local.readBytes()) == f.optString("sha")) continue
                val bytes = get(repo, token, "$REMOTE/$name")
                if (sha256(bytes) != f.optString("sha")) throw Exception("파일이 손상됐어요 ($name)")
                local.parentFile?.mkdirs()
                val tmp = File(local.path + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(local)
                got++
            }
            File(dir(ctx), "manifest.json").writeBytes(manifest)
            JSONObject().put("ok", true).put("got", got)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("err", e.message ?: "다운로드 실패")
        }
    }

    // ---------------- WebView 로 파일 제공 ----------------

    /** https://ko123tp-collab.github.io/__rpg/<파일> 요청을 폰에 저장된 파일(또는 앱에 들어 있는 코드)로 응답 */
    fun intercept(ctx: Context, path: String): WebResourceResponse? {
        if (!path.startsWith("/__rpg/")) return null
        val name = path.removePrefix("/__rpg/")
        if (name.isEmpty() || name.contains("..")) return notFound()
        val mime = when {
            name.endsWith(".js") -> "application/javascript"
            name.endsWith(".ogg") -> "audio/ogg"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
        val bytes: ByteArray? = File(dir(ctx), name).takeIf { it.isFile }?.readBytes()
            ?: try { ctx.assets.open("rpg/$name").use { it.readBytes() } } catch (e: Exception) { null }
        if (bytes == null) return notFound()
        val r = WebResourceResponse(mime, if (mime.startsWith("application/j")) "utf-8" else null, ByteArrayInputStream(bytes))
        r.responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
        return r
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
}
