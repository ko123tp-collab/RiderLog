package com.rider.log

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
        const val BAEMIN = "com.woowahan.bros"

        /** 계속 번갈아 바뀌는 안내 문구 — 같은 화면으로 취급 */
        private val ROTATING = setOf("새로운 대기배달을 0 배지로 알려드려요!", "현재 위치와 가까운 배차를 찾고 있어요")
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

    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    private val handler = Handler(Looper.getMainLooper())

    /** 화면을 읽은 마지막 시각 (0.5초 간격 제한용) */
    private var lastWalkTs = 0L
    private val recognizer = Recognizer()
    private var lastRemind = 0L

    /** 배민은 운행 중인데 Rider Log 운행이 꺼져 있으면 알림 (30분에 한 번) */
    private fun remindIfWorking() {
        val now = System.currentTimeMillis()
        if (now - lastRemind < 30 * 60_000L) return
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        val working = try {
            root.findAccessibilityNodeInfosByViewId("$BAEMIN:id/신규배차_끄기버튼").isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId("$BAEMIN:id/신규배차_켜기버튼").isNotEmpty()
        } catch (e: Exception) {
            false
        }
        if (!working) return
        lastRemind = now
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel("remind", "운행 알림", android.app.NotificationManager.IMPORTANCE_HIGH))
        val pi = android.app.PendingIntent.getActivity(
            this, 7, android.content.Intent(this, MainActivity::class.java).putExtra("autostart", true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            2, android.app.Notification.Builder(this, "remind")
                .setSmallIcon(R.drawable.ic_stat_r)
                .setContentTitle("배민은 운행 중이에요")
                .setContentText("눌러서 Rider Log 운행도 시작하세요")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
    private var lastSaveTs = 0L
    private var lastExactSig = 0
    private var lastNormSig = 0

    /** 0.5초 안에 또 바뀌면 바로 읽지 않고, 0.5초가 지난 시점에 마지막 화면을 한 번 더 읽음 */
    private var pendingPkg: String? = null
    private val trailing = Runnable {
        val p = pendingPkg
        pendingPkg = null
        if (p != null) snapshot(p, "change")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // 어떤 앱이 쓰였는지 '이름'만 기억해서 설정 화면 목록에 보여 줌
        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) rememberApp(pkg)

        if (!prefs.getBoolean("on", false)) return
        val selected = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        if (pkg !in selected) return
        if (!TrackingService.running) { // 운행 중에만 기록. 대신 배민이 운행 중이면 알려 줌
            if (pkg == BAEMIN) remindIfWorking()
            return
        }

        val now = System.currentTimeMillis()
        when (ev.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val texts = JSONArray()
                for (t in ev.text) if (t != null) texts.put(redact(t.toString()))
                write(JSONObject().put("ts", now).put("pkg", pkg).put("kind", "noti").put("text", texts))
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 사용자가 배민에서 누른 버튼 (수락, 가게 도착, 픽업 완료 등) — 가장 확실한 신호
                val texts = JSONArray()
                for (t in ev.text) if (t != null) texts.put(redact(t.toString()))
                val o = JSONObject().put("ts", now).put("pkg", pkg).put("kind", "click").put("text", texts)
                ev.contentDescription?.let { o.put("cd", redact(it.toString())) }
                try {
                    ev.source?.viewIdResourceName?.let { o.put("id", it.substringAfter(":id/")) }
                } catch (e: Exception) {
                }
                write(o)
                scheduleOrSnapshot(pkg, now) // 누른 직후 바뀐 화면도 잡기
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handler.removeCallbacks(trailing)
                pendingPkg = null
                snapshot(pkg, "window")
            }
            else -> scheduleOrSnapshot(pkg, now) // 내용 변경, 스크롤
        }
    }

    private fun scheduleOrSnapshot(pkg: String, now: Long) {
        val since = now - lastWalkTs
        if (since >= 500) {
            handler.removeCallbacks(trailing)
            pendingPkg = null
            snapshot(pkg, "change")
        } else {
            pendingPkg = pkg
            handler.removeCallbacks(trailing)
            handler.postDelayed(trailing, 500 - since)
        }
    }

    private fun snapshot(pkg: String, kind: String) {
        val now = System.currentTimeMillis()
        lastWalkTs = now
        val root = findRoot(pkg) ?: return
        val nodes = JSONArray()
        val sig = StringBuilder()
        val raw = ArrayList<Recognizer.N>()
        walk(root, 0, nodes, sig, raw)
        if (pkg == BAEMIN) {
            try {
                TrackingService.instance?.let { recognizer.onScreen(raw, now, it) }
            } catch (e: Exception) {
            }
        }
        val exact = sig.toString().hashCode()
        val norm = sig.toString().replace(Regex("\\d"), "#").hashCode()
        if (exact == lastExactSig) return // 완전히 같은 화면
        // 숫자만 바뀐 경우(타이머 등)는 5초에 한 번만 저장
        if (norm == lastNormSig && now - lastSaveTs < 5000) return
        lastExactSig = exact
        lastNormSig = norm
        lastSaveTs = now
        write(
            JSONObject()
                .put("ts", now)
                .put("pkg", pkg)
                .put("kind", kind)
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

    private fun walk(node: AccessibilityNodeInfo, depth: Int, out: JSONArray, sig: StringBuilder, raw: ArrayList<Recognizer.N>) {
        if (out.length() >= MAX_NODES || depth > MAX_DEPTH) return
        val id = node.viewIdResourceName
        val idl = id?.lowercase() ?: ""
        // 로그인 화면(아이디·비밀번호)과 비밀번호 칸은 아예 읽지 않음
        val secret = node.isPassword || idl.contains("login") || idl.contains("password")
        var text = if (secret) null else node.text?.toString()
        val desc = if (secret) null else node.contentDescription?.toString()
        if (text != null && text in ROTATING) text = null
        raw.add(Recognizer.N(id?.substringAfter(":id/"), text ?: desc ?: ""))
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
            walk(child, depth + 1, out, sig, raw)
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
