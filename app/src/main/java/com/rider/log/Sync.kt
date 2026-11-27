package com.rider.log

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * 비공개 GitHub 저장소로 기록을 자동 업로드.
 * - days/2026-11-28.json : 그날(새벽 6시 기준) 운행·배달 기록 + GPS 경로
 * - capture/20261128.jsonl.gz : 그날 화면 기록
 * - cameras.json : 후면 단속 카메라 목록
 * 바뀐 파일만 올리고, 토큰은 폰 안에만 저장한다.
 */
object Sync {

    private const val PREFS = "sync"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun configured(ctx: Context): Boolean {
        val p = prefs(ctx)
        return !p.getString("repo", "").isNullOrBlank() && !p.getString("token", "").isNullOrBlank()
    }

    fun statusJson(ctx: Context): String {
        val p = prefs(ctx)
        return JSONObject()
            .put("repo", p.getString("repo", "ko123tp-collab/riderlog-data"))
            .put("hasToken", !p.getString("token", "").isNullOrBlank())
            .put("last", p.getLong("last", 0L))
            .put("status", p.getString("status", ""))
            .put("ok", p.getBoolean("ok", false))
            .toString()
    }

    fun setConfig(ctx: Context, repo: String, token: String?) {
        val e = prefs(ctx).edit().putString("repo", repo.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/'))
        if (token != null) e.putString("token", token.trim())
        e.remove("hashes").apply()
    }

    @Volatile
    private var busy = false

    /** 백그라운드에서 동기화. 이미 진행 중이면 건너뜀 */
    fun runAsync(ctx: Context, done: ((Boolean, String) -> Unit)? = null) {
        if (!configured(ctx) || busy) {
            done?.invoke(false, if (busy) "이미 동기화 중" else "설정 필요")
            return
        }
        val app = ctx.applicationContext
        Thread {
            busy = true
            val (ok, msg) = try {
                run(app)
            } catch (e: Exception) {
                Pair(false, "실패: ${e.message}")
            }
            busy = false
            prefs(app).edit().putLong("last", System.currentTimeMillis()).putString("status", msg).putBoolean("ok", ok).apply()
            done?.invoke(ok, msg)
        }.start()
    }

    private fun run(ctx: Context): Pair<Boolean, String> {
        val p = prefs(ctx)
        val repo = p.getString("repo", "") ?: ""
        val token = p.getString("token", "") ?: ""
        val db = Db.get(ctx)
        var uploaded = 0

        // 오늘과 어제 근무일 (새벽 6시 기준)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        for (back in 0..1) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = System.currentTimeMillis() - 6 * 3600_000L
            cal.add(Calendar.DAY_OF_MONTH, -back)
            cal.set(Calendar.HOUR_OF_DAY, 6)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val from = cal.timeInMillis
            val to = from + 24 * 3600_000L
            val o = JSONObject(db.dataJson(from, to))
            if (o.getJSONArray("events").length() == 0 && o.getJSONArray("shifts").length() == 0) continue
            o.put("points", org.json.JSONArray(db.trackJson(from, to)))
            if (put(ctx, repo, token, "days/" + dayFmt.format(Date(from)) + ".json", o.toString(1).toByteArray())) uploaded++
        }

        // 화면 기록 (오늘, 어제 파일) — 압축해서
        val capFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
        for (back in 0..1) {
            val name = capFmt.format(Date(System.currentTimeMillis() - back * 24 * 3600_000L))
            val f = File(ScreenReaderService.captureDir(ctx), "$name.jsonl")
            if (!f.exists() || f.length() == 0L) continue
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { gz -> f.inputStream().use { it.copyTo(gz) } }
            if (put(ctx, repo, token, "capture/$name.jsonl.gz", bos.toByteArray(), hashOf = f.readBytes())) uploaded++
        }

        // 후면 카메라 목록
        if (put(ctx, repo, token, "cameras.json", db.camerasJson().toByteArray())) uploaded++

        return Pair(true, if (uploaded == 0) "변경 없음" else "${uploaded}개 파일 업로드")
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** 내용이 바뀐 경우에만 업로드. 업로드했으면 true */
    private fun put(ctx: Context, repo: String, token: String, path: String, bytes: ByteArray, hashOf: ByteArray = bytes): Boolean {
        val p = prefs(ctx)
        val hashes = JSONObject(p.getString("hashes", "{}") ?: "{}")
        val h = sha256(hashOf)
        if (hashes.optString(path) == h) return false

        val api = "https://api.github.com/repos/$repo/contents/$path"
        // 기존 파일의 sha (있으면 덮어쓰기에 필요)
        var sha: String? = null
        val g = (URL(api).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15000
            readTimeout = 30000
        }
        when (g.responseCode) {
            200 -> sha = JSONObject(g.inputStream.bufferedReader().use { it.readText() }).optString("sha", "").ifEmpty { null }
            404 -> {}
            401, 403 -> throw Exception("권한 오류(${g.responseCode}) — 토큰을 확인해 주세요")
            else -> throw Exception("GitHub 응답 ${g.responseCode}")
        }
        g.disconnect()

        val body = JSONObject()
            .put("message", "sync $path")
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)
        val c = (URL(api).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000
            readTimeout = 60000
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..201) throw Exception("업로드 실패($code): $path")
        hashes.put(path, h)
        p.edit().putString("hashes", hashes.toString()).apply()
        return true
    }
}
