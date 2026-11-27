package com.rider.log

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.zip.GZIPOutputStream
import java.util.Date
import java.util.Locale

/** 앱 화면은 assets/index.html (WebView)로 그리고, 데이터는 Bridge로 주고받는다. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private val db by lazy { Db.get(this) }

    companion object {
        private const val REQ_PERM = 10
        private const val REQ_EXPORT = 20
        private const val REQ_EXPORT_CAPTURE = 30
        private const val REPO = "ko123tp-collab/RiderLog"
        private const val ACTION_INSTALL_STATUS = "com.rider.log.INSTALL_STATUS"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        applyTheme(isDark())
        Thread { try { db.purgeDropAddresses() } catch (e: Exception) {} }.start()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        // 네이버 지도 인증이 https://ko123tp-collab.github.io 주소로 등록되어 있어서, 그 주소에서 연 화면으로 띄움
        val html = assets.open("index.html").bufferedReader().use { it.readText() }
        web.loadDataWithBaseURL("https://ko123tp-collab.github.io/", html, "text/html", "utf-8", null)
        if (intent?.getBooleanExtra("autostart", false) == true) main.postDelayed({ requestStart() }, 500)
    }

    // ---------------- 자동 업데이트 ----------------

    @Volatile
    private var latestApkUrl: String? = null

    @Suppress("DEPRECATION")
    private fun myVersion(): Long {
        val pi = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    private fun sendToWeb(fn: String, json: JSONObject) {
        runOnUiThread {
            if (::web.isInitialized) web.evaluateJavascript("window.$fn && window.$fn($json)", null)
        }
    }

    /** GitHub 최신 릴리스를 확인해서 화면(JS)의 onUpdateInfo로 알려 줌 */
    private fun checkUpdate() {
        Thread {
            val res = JSONObject().put("current", myVersion())
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(body)
                val latest = j.optString("tag_name").substringAfter("build-").toLongOrNull() ?: 0L
                var url: String? = null
                val assets = j.optJSONArray("assets") ?: JSONArray()
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                latestApkUrl = url
                res.put("latest", latest).put("available", url != null && latest > myVersion())
            } catch (e: Exception) {
                res.put("error", e.message ?: "확인 실패")
            }
            sendToWeb("onUpdateInfo", res)
        }.start()
    }

    /** 새 APK를 받아서 안드로이드 설치 확인 화면을 띄움 (기록은 그대로 유지) */
    private fun installUpdate() {
        val url = latestApkUrl
        if (url == null) {
            toast("먼저 업데이트 확인을 해 주세요")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            toast("'이 출처 허용'을 켠 뒤 다시 업데이트를 눌러 주세요")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        toast("새 버전 받는 중…")
        Thread {
            try {
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sid = installer.createSession(params)
                installer.openSession(sid).use { session ->
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.inputStream.use { input ->
                        session.openWrite("update.apk", 0, -1).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val intent = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS)
                    val flags = if (Build.VERSION.SDK_INT >= 31) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pending = PendingIntent.getActivity(this, 99, intent, flags)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("업데이트 실패: ${e.message}") }
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("autostart", false)) {
            requestStart()
            return
        }
        if (intent.action != ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> toast("업데이트 완료")
            else -> toast("업데이트 실패: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""))
        }
    }

    // ---------------- 테마 ----------------

    private fun themeMode(): String = getSharedPreferences("app", MODE_PRIVATE).getString("theme", "system") ?: "system"

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun isDark(): Boolean = when (themeMode()) {
        "dark" -> true
        "light" -> false
        else -> systemDark()
    }

    /** 상태바·내비게이션바·배경색을 화면 테마에 맞춤 */
    @Suppress("DEPRECATION")
    private fun applyTheme(dark: Boolean) {
        val bg = if (dark) 0xFF0B0D12.toInt() else 0xFFF4F5F7.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        var flags = window.decorView.systemUiVisibility
        flags = if (dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
        if (::web.isInitialized) web.setBackgroundColor(bg)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme(isDark())
        refreshWeb()
    }

    /** 배달 앱만 목록에 표시 (배민커넥트, 쿠팡이츠 배달 파트너) */
    private fun isDeliveryApp(pkg: String, label: String): Boolean {
        if (pkg == "com.woowahan.bros") return true
        if (label.contains("배민커넥트") || label.contains("배달 파트너") || label.contains("배달파트너")) return true
        val p = pkg.lowercase()
        if (p.contains("coupang") && p != "com.coupang.mobile.eats" &&
            listOf("courier", "partner", "rider", "delivery", "driver").any { p.contains(it) }
        ) return true
        return false
    }

    override fun onResume() {
        super.onResume()
        refreshWeb()
    }

    private fun refreshWeb() {
        if (::web.isInitialized) web.evaluateJavascript("window.refresh && window.refresh()", null)
    }

    private var preview: Alerter? = null

    private fun previewAlerter(): Alerter = preview ?: Alerter(this).also { preview = it }

    override fun onDestroy() {
        preview?.shutdown()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun hasLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 운행 시작: 오버레이 권한 → 위치/알림 권한 → 서비스 시작 */
    private fun requestStart() {
        if (TrackingService.overlayEnabled(this) && !Settings.canDrawOverlays(this)) {
            toast("'다른 앱 위에 표시'를 허용한 뒤 다시 운행 시작을 눌러 주세요")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val need = ArrayList<String>()
        if (!hasLocation()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("app", MODE_PRIVATE).getBoolean("askedNotif", false)
        ) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply()
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERM)
            return
        }
        startTracking()
    }

    private fun startTracking() {
        if (!hasLocation()) {
            toast("위치 권한이 있어야 기록할 수 있어요")
            return
        }
        startForegroundService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START))
        main.postDelayed({ refreshWeb() }, 700)
    }

    private fun stopTracking() {
        if (TrackingService.running) {
            startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        }
        db.endShift(System.currentTimeMillis())
        main.postDelayed({ refreshWeb() }, 500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) startTracking()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_EXPORT_CAPTURE && resultCode == RESULT_OK && uri != null) {
            Thread {
                try {
                    contentResolver.openOutputStream(uri)?.use { raw ->
                        GZIPOutputStream(raw).use { gz ->
                            // 맨 앞에 버튼 기록(정답 시각)을 넣고, 이어서 화면 기록
                            gz.write(("{\"kind\":\"buttons\",\"data\":" + db.dataJson(0, Long.MAX_VALUE) + "}\n").toByteArray(Charsets.UTF_8))
                            val files = ScreenReaderService.captureDir(this).listFiles()?.sortedBy { it.name } ?: emptyList()
                            for (f in files) f.inputStream().use { it.copyTo(gz) }
                        }
                    }
                    runOnUiThread { toast("화면 기록 내보내기 완료") }
                } catch (e: Exception) {
                    runOnUiThread { toast("내보내기 실패: ${e.message}") }
                }
            }.start()
            return
        }
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && uri != null) {
            Thread {
                try {
                    val json = db.exportJson()
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    runOnUiThread { toast("내보내기 완료") }
                } catch (e: Exception) {
                    runOnUiThread { toast("내보내기 실패: ${e.message}") }
                }
            }.start()
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun isRunning(): Boolean = db.openShiftId() != null

        @JavascriptInterface
        fun shiftStart(): String = db.openShiftStart()?.toString() ?: ""

        @JavascriptInterface
        fun startShift() {
            runOnUiThread { requestStart() }
        }

        @JavascriptInterface
        fun endShift() {
            runOnUiThread { stopTracking() }
        }

        @JavascriptInterface
        fun getData(from: String, to: String): String = db.dataJson(from.toLong(), to.toLong())

        @JavascriptInterface
        fun getTrack(from: String, to: String): String = db.trackJson(from.toLong(), to.toLong())

        @JavascriptInterface
        fun renameStore(id: String, name: String) {
            if (name.isNotBlank()) db.renameStore(id.toLong(), name.trim())
        }

        @JavascriptInterface
        fun setAmount(id: String, amount: String) {
            db.setAmount(id.toLong(), amount.filter { it.isDigit() }.toIntOrNull())
        }

        @JavascriptInterface
        fun deleteEvent(id: String) {
            db.deleteEvent(id.toLong())
        }

        @JavascriptInterface
        fun exportAll() {
            runOnUiThread {
                val name = "배달기록_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date()) + ".json"
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, name)
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_EXPORT)
            }
        }

        // ---------- 서버(GitHub) 동기화 ----------

        @JavascriptInterface
        fun getSync(): String = Sync.statusJson(this@MainActivity)

        @JavascriptInterface
        fun setSync(repo: String, token: String) {
            Sync.setConfig(this@MainActivity, repo, token.ifBlank { null })
        }

        @JavascriptInterface
        fun syncNow() {
            Sync.runAsync(this@MainActivity) { ok, msg ->
                runOnUiThread {
                    toast(if (ok) "동기화 완료 · $msg" else "동기화 $msg")
                    refreshWeb()
                }
            }
        }

        // ---------- 후면 단속 카메라 ----------

        private fun notifyCams() {
            Sync.runAsync(this@MainActivity)
            if (TrackingService.running) {
                startService(Intent(this@MainActivity, TrackingService::class.java).setAction(TrackingService.ACTION_CAMERAS))
            }
        }

        @JavascriptInterface
        fun getCameras(): String = db.camerasJson()

        @JavascriptInterface
        fun addCamera(lat: String, lng: String, speed: String): String {
            val id = db.addCamera("내가 추가한 카메라", lat.toDouble(), lng.toDouble(), speed.toIntOrNull() ?: 50)
            notifyCams()
            return id.toString()
        }

        @JavascriptInterface
        fun updateCamera(id: String, json: String) {
            db.updateCamera(id.toLong(), json)
            notifyCams()
        }

        @JavascriptInterface
        fun deleteCamera(id: String) {
            db.deleteCamera(id.toLong())
            notifyCams()
        }

        @JavascriptInterface
        fun getCamAlert(): Boolean = TrackingService.camAlertEnabled(this@MainActivity)

        @JavascriptInterface
        fun setCamAlert(on: Boolean) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("camAlert", on).apply()
        }

        @JavascriptInterface
        fun testVoice() {
            runOnUiThread { previewAlerter().speak("전방에 후면단속 카메라가 있습니다.") }
        }

        @JavascriptInterface
        fun testChime() {
            runOnUiThread { previewAlerter().chime() }
        }

        /** 마지막으로 알려진 내 위치 [위도, 경도] (없으면 빈 문자열) */
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun lastLocation(): String {
            if (!hasLocation()) return ""
            val lm = getSystemService(android.location.LocationManager::class.java) ?: return ""
            var best: android.location.Location? = null
            for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || l.time > best.time) best = l
                } catch (e: Exception) {
                }
            }
            val b = best ?: return ""
            return "[${b.latitude},${b.longitude}]"
        }

        // ---------- 테마 · 오버레이 ----------

        @JavascriptInterface
        fun getTheme(): String = themeMode()

        @JavascriptInterface
        fun systemDark(): Boolean = this@MainActivity.systemDark()

        @JavascriptInterface
        fun setTheme(mode: String) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putString("theme", mode).apply()
            runOnUiThread { applyTheme(isDark()) }
        }

        @JavascriptInterface
        fun getOverlay(): Boolean = TrackingService.overlayEnabled(this@MainActivity)

        @JavascriptInterface
        fun setOverlay(on: Boolean) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("overlay", on).apply()
            if (on && !Settings.canDrawOverlays(this@MainActivity)) {
                runOnUiThread {
                    toast("'다른 앱 위에 표시'를 허용해 주세요")
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
            if (TrackingService.running) {
                startService(Intent(this@MainActivity, TrackingService::class.java).setAction(TrackingService.ACTION_OVERLAY))
            }
        }

        // ---------- 업데이트 ----------

        @JavascriptInterface
        fun version(): String = myVersion().toString()

        @JavascriptInterface
        fun checkUpdate() {
            this@MainActivity.checkUpdate()
        }

        @JavascriptInterface
        fun installUpdate() {
            runOnUiThread { this@MainActivity.installUpdate() }
        }

        // ---------- 화면 구조 기록 모드 ----------

        @JavascriptInterface
        fun captureStatus(): String {
            val prefs = getSharedPreferences(ScreenReaderService.PREFS, MODE_PRIVATE)
            val selected = prefs.getStringSet("apps", emptySet()) ?: emptySet()
            val seen = (prefs.getString("seen", "") ?: "").split(',').filter { it.isNotBlank() }
            val all = LinkedHashSet<String>()
            all.addAll(selected)
            all.addAll(seen)
            val apps = JSONArray()
            for (p in all) {
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
                } catch (e: Exception) {
                    ""
                }
                if (!isDeliveryApp(p, label)) continue
                apps.put(JSONObject().put("pkg", p).put("label", label).put("on", p in selected))
            }
            val files = ScreenReaderService.captureDir(this@MainActivity).listFiles() ?: emptyArray<File>()
            var lines = 0L
            var bytes = 0L
            for (f in files) {
                bytes += f.length()
                f.forEachLine { lines++ }
            }
            return JSONObject()
                .put("enabled", ScreenReaderService.isEnabled(this@MainActivity))
                .put("on", prefs.getBoolean("on", false))
                .put("apps", apps)
                .put("count", lines)
                .put("bytes", bytes)
                .toString()
        }

        @JavascriptInterface
        fun setCaptureOn(on: Boolean) {
            getSharedPreferences(ScreenReaderService.PREFS, MODE_PRIVATE).edit().putBoolean("on", on).apply()
        }

        @JavascriptInterface
        fun setCaptureApp(pkg: String, on: Boolean) {
            val prefs = getSharedPreferences(ScreenReaderService.PREFS, MODE_PRIVATE)
            val set = HashSet(prefs.getStringSet("apps", emptySet()) ?: emptySet())
            if (on) set.add(pkg) else set.remove(pkg)
            prefs.edit().putStringSet("apps", set).apply()
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        @JavascriptInterface
        fun openAppInfo() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        @JavascriptInterface
        fun exportCapture() {
            runOnUiThread {
                val name = "화면기록_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date()) + ".jsonl.gz"
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/gzip")
                    .putExtra(Intent.EXTRA_TITLE, name)
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_EXPORT_CAPTURE)
            }
        }

        @JavascriptInterface
        fun clearCapture() {
            ScreenReaderService.captureDir(this@MainActivity).listFiles()?.forEach { it.delete() }
        }

        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    toast("설정 화면을 열 수 없어요")
                }
            }
        }
    }
}
