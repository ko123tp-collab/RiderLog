package com.rider.log

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 운행 중에 돌아가는 서비스.
 * - GPS 경로를 5초마다 저장
 * - 화면 위에 오버레이 버튼(수락/거절/가게 도착/픽업 완료/배달 완료/되돌리기)을 띄움
 */
class TrackingService : Service(), LocationListener, Recognizer.Sink {

    companion object {
        const val ACTION_START = "com.rider.log.START"
        const val ACTION_STOP = "com.rider.log.STOP"
        const val ACTION_OVERLAY = "com.rider.log.OVERLAY"
        const val ACTION_CAMERAS = "com.rider.log.CAMERAS"

        fun camAlertEnabled(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).getBoolean("camAlert", true)

        fun overlayEnabled(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).getBoolean("overlay", false)
        private const val CHANNEL = "tracking"

        @Volatile
        var running = false

        @Volatile
        var instance: TrackingService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val db by lazy { Db.get(this) }

    private var wm: WindowManager? = null
    private var bar: View? = null
    private var handleView: TextView? = null
    private var panel: View? = null
    private var panelTimeout: Runnable? = null
    private var pendingSkip: (() -> Unit)? = null

    private var shiftId = 0L
    private var lastLoc: Location? = null
    private var lastPointTs = 0L

    private val buttons = listOf(
        Triple("수락", "ACCEPT", 0xFF16A34A.toInt()),
        Triple("거절", "REJECT", 0xFFDC2626.toInt()),
        Triple("도착", "ARRIVE", 0xFF2563EB.toInt()),
        Triple("픽업", "PICKUP", 0xFF7C3AED.toInt()),
        Triple("완료", "DELIVER", 0xFFF2611D.toInt()),
        Triple("↶ 취소", "UNDO", 0xFF374151.toInt())
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishShift()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CAMERAS) {
            if (running) reloadCameras()
            return START_STICKY
        }
        if (intent?.action == ACTION_OVERLAY) {
            // 설정에서 오버레이를 켜고 끌 때
            if (running) {
                if (overlayEnabled(this)) {
                    if (bar == null) showBar()
                } else {
                    removeBar()
                }
            }
            return START_STICKY
        }
        val open = db.openShiftId()
        // 시스템이 서비스를 재시작했는데 진행 중인 운행이 없으면 그냥 종료
        if (intent == null && open == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        shiftId = open ?: db.startShift(System.currentTimeMillis())
        if (!running) {
            running = true
            instance = this
            alerter = Alerter(this)
            reloadCameras()
            startLocation()
            main.postDelayed(syncTick, 15 * 60_000L)
            if (overlayEnabled(this)) showBar()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacks(syncTick)
        alerter?.shutdown()
        alerter = null
        cleanup()
        running = false
        super.onDestroy()
    }

    /** 운행 중 15분마다 서버(GitHub)로 자동 업로드 */
    private val syncTick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            Sync.runAsync(this@TrackingService)
            main.postDelayed(this, 15 * 60_000L)
        }
    }

    private fun finishShift() {
        instance = null
        main.removeCallbacks(syncTick)
        Sync.runAsync(this) // 운행 종료 시 마지막 업로드
        alerter?.shutdown()
        alerter = null
        db.endShift(System.currentTimeMillis())
        cleanup()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try {
            getSystemService(LocationManager::class.java)?.removeUpdates(this)
        } catch (e: Exception) {
        }
        pendingSkip?.invoke()
        removePanel()
        removeBar()
    }

    private fun removeBar() {
        bar?.let {
            try {
                wm?.removeView(it)
            } catch (e: Exception) {
            }
        }
        bar = null
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "운행 기록", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Rider Log · 운행 중")
            .setContentText("이동 경로와 배달 기록을 저장하고 있어요")
            .setSmallIcon(R.drawable.ic_stat_r)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------- GPS ----------------

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLoc = it }
        } catch (e: Exception) {
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
        }
    }

    override fun onLocationChanged(loc: Location) {
        val prev = lastLoc
        // GPS 값이 최근에 있으면 부정확한 네트워크 위치는 무시
        if (loc.provider == LocationManager.NETWORK_PROVIDER && prev != null &&
            prev.provider == LocationManager.GPS_PROVIDER && loc.time - prev.time < 15000
        ) return
        lastLoc = loc
        val now = System.currentTimeMillis()
        if (now - lastPointTs >= 4000) {
            lastPointTs = now
            db.addPoint(
                shiftId, now, loc.latitude, loc.longitude,
                if (loc.hasSpeed()) loc.speed else null,
                if (loc.hasAccuracy()) loc.accuracy else null
            )
        }
        if (loc.provider == LocationManager.GPS_PROVIDER) checkCameras(loc)
    }

    // ---------------- 배민 화면 자동 인식 결과 기록 ----------------

    override fun autoEvent(type: String, ts: Long, store: String?, amount: Int?, note: String?) {
        main.post {
            if (!running) return@post
            // 오버레이 버튼으로 이미 누른 기록이 있으면 중복 저장 안 함
            val window = if (type == "OFFER" || type == "ACCEPT" || type == "REJECT") 8_000L else 60_000L
            if (type != "OFFER" && type != "READY" && db.hasNear(shiftId, type, ts, window)) return@post
            val loc = lastLoc
            var storeId: Long? = null
            if (store != null && (type == "ARRIVE" || type == "PICKUP" || type == "READY" || type == "DELIVER")) {
                storeId = db.storeByName(store)
                if (storeId == null && loc != null && (type == "ARRIVE" || type == "PICKUP")) {
                    storeId = db.addStore(store, loc.latitude, loc.longitude)
                }
            }
            val n = if (type == "OFFER" || type == "ACCEPT" || type == "REJECT") listOfNotNull(store, note).joinToString(" · ").ifEmpty { null } else note
            db.addAutoEvent(shiftId, ts, type, loc?.latitude, loc?.longitude, storeId, amount, n)
            updateHandle()
        }
    }

    override fun autoEndShift() {
        main.post {
            if (running) {
                Toast.makeText(applicationContext, "배민 운행 종료 · Rider Log 운행도 종료했어요", Toast.LENGTH_LONG).show()
                finishShift()
            }
        }
    }

    // ---------------- 후면 단속 카메라 알림 ----------------

    private var alerter: Alerter? = null
    private var cams: List<Db.Cam> = emptyList()

    private class CamState {
        var alerted = false
        var minDist = Double.MAX_VALUE
        var lastExit = 0L
    }

    private val camStates = HashMap<Long, CamState>()
    private var headingLoc: Location? = null
    private var heading: Float? = null
    private var headingTs = 0L
    private var lastSpeakTs = 0L
    private var lastChimeTs = 0L

    private fun reloadCameras() {
        cams = db.cameras().filter { it.confirmed }
        camStates.keys.retainAll(cams.map { it.id }.toSet())
    }

    /** 진행 방향: GPS가 주는 방향을 우선, 없으면 최근 이동으로 계산 */
    private fun updateHeading(loc: Location) {
        val now = System.currentTimeMillis()
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed >= 2.5f) {
            heading = loc.bearing
            headingTs = now
            headingLoc = loc
            return
        }
        val prev = headingLoc
        if (prev == null) {
            headingLoc = loc
            return
        }
        if (prev.distanceTo(loc) >= 12f) {
            heading = prev.bearingTo(loc)
            headingTs = now
            headingLoc = loc
        }
        if (now - headingTs > 15000) heading = null
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    /**
     * 진행 방향 앞쪽(좌우 ±35도) 300m 안, 이동선에서 옆으로 45m 이내에 카메라가 있으면 음성 안내.
     * 카메라를 지나치거나(뒤쪽으로 넘어감) 멀어지면 '띠링'.
     */
    private fun checkCameras(loc: Location) {
        if (cams.isEmpty() || !camAlertEnabled(this)) return
        updateHeading(loc)
        val h = heading
        val moving = loc.hasSpeed() && loc.speed >= 2.0f || h != null
        val now = System.currentTimeMillis()
        for (c in cams) {
            val target = Location("cam").apply { latitude = c.lat; longitude = c.lng }
            val d = loc.distanceTo(target).toDouble()
            val st = camStates.getOrPut(c.id) { CamState() }
            if (!st.alerted) {
                if (d > 300 || h == null || !moving || now - st.lastExit < 60000) continue
                val diff = angleDiff(loc.bearingTo(target), h)
                if (diff > 35f) continue
                val rad = Math.toRadians(diff.toDouble())
                val along = d * cos(rad)
                val lateral = d * sin(rad)
                if (along < 25 || lateral > 45) continue
                st.alerted = true
                st.minDist = d
                if (now - lastSpeakTs > 8000) {
                    lastSpeakTs = now
                    alerter?.speak("전방에 후면단속 카메라가 있습니다.")
                }
            } else {
                if (d < st.minDist) st.minDist = d
                val behind = h != null && angleDiff(loc.bearingTo(target), h) > 100f
                if ((behind && d > 20) || d > st.minDist + 80 || d > 400) {
                    st.alerted = false
                    st.lastExit = now
                    st.minDist = Double.MAX_VALUE
                    if (now - lastChimeTs > 3000) {
                        lastChimeTs = now
                        alerter?.chime()
                    }
                }
            }
        }
    }

    // 구형 안드로이드에서 필요한 메서드들
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------------- 오버레이 버튼 ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun label(t: String): String = when (t) {
        "ACCEPT" -> "수락"
        "REJECT" -> "거절"
        "ARRIVE" -> "가게 도착"
        "PICKUP" -> "픽업 완료"
        "DELIVER" -> "배달 완료"
        else -> t
    }

    private fun showBar() {
        if (!Settings.canDrawOverlays(this)) {
            toast("오버레이 권한이 없어 버튼을 띄우지 못했어요")
            return
        }
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(0xD90B0F17.toInt(), 12)
        }
        val handle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(handle, LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.WRAP_CONTENT))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((text, type, color) in buttons) {
            val b = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = if (type == "UNDO") 11f else 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = rounded(color, 8)
                isClickable = true
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.alpha = 0.4f
                    main.postDelayed({ v.alpha = 1f }, 180)
                    onTap(type)
                }
            }
            val lp = LinearLayout.LayoutParams(dp(68), dp(if (type == "UNDO") 28 else 36))
            lp.topMargin = dp(3)
            box.addView(b, lp)
        }
        root.addView(box)

        var collapsed = prefs.getBoolean("collapsed", false)
        box.visibility = if (collapsed) View.GONE else View.VISIBLE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(8))
            y = prefs.getInt("y", dp(160))
        }

        // 상단 손잡이: 끌면 이동, 탭하면 접기/펴기
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            var moved = false

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt()
                        val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = ox + dx
                            lp.y = oy + dy
                            try {
                                w.updateViewLayout(root, lp)
                            } catch (ex: Exception) {
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                        } else {
                            collapsed = !collapsed
                            box.visibility = if (collapsed) View.GONE else View.VISIBLE
                            prefs.edit().putBoolean("collapsed", collapsed).apply()
                        }
                    }
                }
                return true
            }
        })

        w.addView(root, lp)
        bar = root
        handleView = handle
        updateHandle()
    }

    private fun updateHandle() {
        handleView?.text = "≡ ${db.countEvents(shiftId, "DELIVER")}건"
    }

    private fun onTap(type: String) {
        if (type == "UNDO") {
            val t = db.deleteLastEvent(shiftId)
            toast(if (t == null) "되돌릴 기록이 없어요" else "'${label(t)}' 기록을 지웠어요")
            updateHandle()
            return
        }
        val loc = lastLoc
        val id = db.addEvent(shiftId, System.currentTimeMillis(), type, loc?.latitude, loc?.longitude)
        when (type) {
            "ARRIVE", "PICKUP" -> assignStore(id, type, loc)
            "DELIVER" -> {
                toast("배달 완료")
                askAmount(id)
            }
            else -> toast(label(type))
        }
        updateHandle()
    }

    /** 가게 자동 인식: 저장된 가게 반경 60m 안이면 자동, 처음 온 곳이면 이름을 한 번 물어봄 */
    private fun assignStore(eventId: Long, type: String, loc: Location?) {
        if (loc != null) {
            val near = db.nearestStore(loc.latitude, loc.longitude)
            if (near != null) {
                db.setEventStore(eventId, near.first)
                toast("${label(type)} · ${near.second}")
                return
            }
        }
        if (type == "PICKUP") {
            // 가게 도착을 이미 눌렀다면 그 가게로 연결
            val s = db.recentArriveStore(shiftId, System.currentTimeMillis() - 3600_000L)
            if (s != null) {
                db.setEventStore(eventId, s)
                toast("픽업 완료")
                return
            }
        }
        if (loc == null) {
            toast("${label(type)} (GPS를 아직 못 잡아서 가게는 저장하지 못했어요)")
            return
        }
        askText(
            title = "처음 온 가게예요\n가게 이름을 적어 주세요",
            hint = "예: 교촌치킨 역삼점",
            numeric = false,
            skipLabel = "나중에",
            autoMs = null,
            onSave = { name ->
                val sid = db.addStore(name, loc.latitude, loc.longitude)
                db.setEventStore(eventId, sid)
                toast("'$name' 저장. 다음부터는 자동으로 인식해요")
            },
            onSkip = {
                val sid = db.addStore("이름 없음 ${db.unnamedCount() + 1}", loc.latitude, loc.longitude)
                db.setEventStore(eventId, sid)
            }
        )
    }

    private fun askAmount(eventId: Long) {
        askText(
            title = "이번 배달 금액 (원)",
            hint = "예: 3500  · 안 누르면 15초 뒤 닫혀요",
            numeric = true,
            skipLabel = "건너뛰기",
            autoMs = 15000L,
            onSave = { v ->
                val n = v.filter { it.isDigit() }.toIntOrNull()
                if (n != null) {
                    db.setAmount(eventId, n)
                    toast(String.format("%,d원 저장", n))
                }
            },
            onSkip = {}
        )
    }

    /** 화면 가운데 작은 입력창 (바깥 화면은 그대로 터치 가능) */
    private fun askText(
        title: String,
        hint: String,
        numeric: Boolean,
        skipLabel: String,
        autoMs: Long?,
        onSave: (String) -> Unit,
        onSkip: () -> Unit
    ) {
        val w = wm ?: return
        pendingSkip?.invoke()
        removePanel()

        var done = false
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = rounded(Color.WHITE, 16)
            elevation = dp(8).toFloat()
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF222222.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        val edit = EditText(this).apply {
            this.hint = hint
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            textSize = 18f
            setTextColor(0xFF111111.toInt())
        }
        box.addView(edit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val skip = Button(this).apply { text = skipLabel }
        val save = Button(this).apply { text = "저장" }
        row.addView(skip)
        row.addView(save)
        box.addView(row)

        val timeout = Runnable {
            if (!done) {
                done = true
                onSkip()
            }
            removePanel()
        }
        pendingSkip = {
            if (!done) {
                done = true
                onSkip()
            }
        }
        skip.setOnClickListener {
            if (!done) {
                done = true
                onSkip()
            }
            removePanel()
        }
        save.setOnClickListener {
            val t = edit.text.toString().trim()
            if (t.isNotEmpty()) {
                if (!done) {
                    done = true
                    onSave(t)
                }
                removePanel()
            }
        }
        edit.setOnEditorActionListener { _, _, _ ->
            save.performClick()
            true
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(timeout) // 입력을 시작하면 자동 닫힘 취소
            }
        })

        val lp = WindowManager.LayoutParams(
            dp(290),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        try {
            w.addView(box, lp)
        } catch (e: Exception) {
            pendingSkip?.invoke()
            pendingSkip = null
            return
        }
        panel = box
        panelTimeout = timeout
        edit.requestFocus()
        main.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
        if (autoMs != null) main.postDelayed(timeout, autoMs)
    }

    private fun removePanel() {
        panelTimeout?.let { main.removeCallbacks(it) }
        panelTimeout = null
        pendingSkip = null
        val p = panel ?: return
        panel = null
        try {
            wm?.removeView(p)
        } catch (e: Exception) {
        }
    }
}
