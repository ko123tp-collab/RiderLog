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

/**
 * 운행 중에 돌아가는 서비스.
 * - GPS 경로를 5초마다 저장
 * - 화면 위에 오버레이 버튼(수락/거절/가게 도착/픽업 완료/배달 완료/되돌리기)을 띄움
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.rider.log.START"
        const val ACTION_STOP = "com.rider.log.STOP"
        private const val CHANNEL = "tracking"

        @Volatile
        var running = false
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
        Triple("수락", "ACCEPT", 0xFF2E7D32.toInt()),
        Triple("거절", "REJECT", 0xFFC62828.toInt()),
        Triple("가게 도착", "ARRIVE", 0xFF1565C0.toInt()),
        Triple("픽업 완료", "PICKUP", 0xFF6A1B9A.toInt()),
        Triple("배달 완료", "DELIVER", 0xFFEF6C00.toInt()),
        Triple("↶ 되돌리기", "UNDO", 0xFF555555.toInt())
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishShift()
            return START_NOT_STICKY
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
            startLocation()
            showBar()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cleanup()
        running = false
        super.onDestroy()
    }

    private fun finishShift() {
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
            .setContentTitle("운행 기록 중")
            .setContentText("GPS 기록과 오버레이 버튼이 켜져 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, this, Looper.getMainLooper())
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
        if (now - lastPointTs >= 5000) {
            lastPointTs = now
            db.addPoint(shiftId, now, loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed else null)
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
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(0xCC202020.toInt(), 14)
        }
        val handle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(handle, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((text, type, color) in buttons) {
            val b = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = if (type == "UNDO") 13f else 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = rounded(color, 10)
                isClickable = true
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.alpha = 0.4f
                    main.postDelayed({ v.alpha = 1f }, 180)
                    onTap(type)
                }
            }
            val lp = LinearLayout.LayoutParams(dp(96), dp(if (type == "UNDO") 36 else 50))
            lp.topMargin = dp(4)
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
        handleView?.text = "≡ 완료 ${db.countEvents(shiftId, "DELIVER")}건"
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
