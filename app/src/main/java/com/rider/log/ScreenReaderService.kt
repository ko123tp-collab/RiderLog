package com.rider.log

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1단계: 화면 구조 기록 모드.
 * 사용자가 고른 배달 앱의 화면 구조(글자, 버튼, 위치)를 운행 중에만 파일로 저장한다.
 * 이 파일을 보고 2단계에서 자동 인식 규칙을 만든다.
 * - 고르지 않은 앱은 읽지 않는다 (앱 이름 목록만 기억)
 * - 다른 앱을 대신 누르거나 조작하지 않는다
 * - 전화번호, 주소로 보이는 글자는 저장 전에 가린다
 */
class ScreenReaderService : AccessibilityService() {

    companion object {
        const val PREFS = "capture"
        private const val MAX_NODES = 700
        private const val MAX_DEPTH = 45

        private val PHONE = Regex("0\\d{1,2}[-. ]?\\d{3,4}[-. ]?\\d{4}")
        private val ADDRESS = Regex(
            "(\\S+(로|길)\\s?\\d+)|(\\d+\\s?동\\s?\\d+\\s?호)|(\\d+\\s?층)|(\\d+\\s?호(?!점))|(아파트|오피스텔|빌라|맨션|타워|빌딩)|(\\S+(시|구)\\s\\S+(동|읍|면))"
        )

        fun redact(s: String): String {
            val x = PHONE.replace(s, "[전화]")
            return if (ADDRESS.containsMatchIn(x)) "[주소]" else x
        }

        fun captureDir(ctx: Context): File = File(ctx.filesDir, "capture").also { it.mkdirs() }

        fun isEnabled(ctx: Context): Boolean {
            val list = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    private var lastTs = 0L
    private var lastSig = 0
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // 어떤 앱이 쓰였는지 '이름'만 기억해서 설정 화면 목록에 보여 줌
        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) rememberApp(pkg)

        if (!prefs.getBoolean("on", false)) return
        if (!TrackingService.running) return // 운행 중에만 기록
        val selected = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        if (pkg !in selected) return

        val now = System.currentTimeMillis()

        if (ev.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val texts = JSONArray()
            for (t in ev.text) if (t != null) texts.put(redact(t.toString()))
            write(JSONObject().put("ts", now).put("pkg", pkg).put("kind", "noti").put("text", texts))
            return
        }

        val isState = ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isState && now - lastTs < 2500) return

        val root = findRoot(pkg) ?: return
        val nodes = JSONArray()
        val sig = StringBuilder()
        walk(root, 0, nodes, sig)
        // 숫자만 바뀐 화면(타이머 등)은 같은 화면으로 보고 중복 저장 안 함
        val h = sig.toString().replace(Regex("\\d"), "#").hashCode()
        if (h == lastSig) return
        lastSig = h
        lastTs = now
        write(
            JSONObject()
                .put("ts", now)
                .put("pkg", pkg)
                .put("kind", if (isState) "window" else "change")
                .put("cls", ev.className?.toString() ?: "")
                .put("nodes", nodes)
        )
    }

    private fun findRoot(pkg: String): AccessibilityNodeInfo? {
        val active = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        if (active != null && active.packageName?.toString() == pkg) return active
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg) return r
            }
        } catch (e: Exception) {
        }
        return null
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, out: JSONArray, sig: StringBuilder) {
        if (out.length() >= MAX_NODES || depth > MAX_DEPTH) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val id = node.viewIdResourceName
        val clickable = node.isClickable
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || id != null || clickable) {
            val j = JSONObject()
            j.put("d", depth)
            j.put("c", node.className?.toString()?.substringAfterLast('.') ?: "")
            if (id != null) j.put("id", id.substringAfter(":id/"))
            if (!text.isNullOrBlank()) {
                val t = redact(text)
                j.put("t", t)
                sig.append(t).append('|')
            }
            if (!desc.isNullOrBlank()) {
                val d = redact(desc)
                j.put("cd", d)
                sig.append(d).append('|')
            }
            if (clickable) j.put("k", 1)
            if (!node.isEnabled) j.put("off", 1)
            val r = Rect()
            node.getBoundsInScreen(r)
            j.put("b", "${r.left},${r.top},${r.right},${r.bottom}")
            out.put(j)
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            walk(child, depth + 1, out, sig)
        }
    }

    private fun rememberApp(pkg: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val raw = prefs.getString("seen", "") ?: ""
        val list = raw.split(',').filter { it.isNotBlank() && it != pkg }.toMutableList()
        list.add(0, pkg)
        prefs.edit().putString("seen", list.take(25).joinToString(",")).apply()
    }

    @Synchronized
    private fun write(o: JSONObject) {
        try {
            val f = File(captureDir(this), dayFmt.format(Date()) + ".jsonl")
            FileWriter(f, true).use { it.write(o.toString() + "\n") }
        } catch (e: Exception) {
        }
    }
}
