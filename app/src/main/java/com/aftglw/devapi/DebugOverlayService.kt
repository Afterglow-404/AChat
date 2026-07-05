package com.aftglw.devapi

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class DebugOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var overlay: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var tvApi: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvTokens: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvGlass: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCtx: TextView
    private lateinit var tvEmo: TextView
    private lateinit var tvOnnx: TextView
    private lateinit var tvEmoResult: TextView
    private lateinit var tvChatId: TextView
    private lateinit var tvAffinity: TextView
    private lateinit var tvProactive: TextView
    private var touchX = 0f; private var touchY = 0f
    private var initX = 0; private var initY = 0
    private var isUpdating = false
    private var tapCount = 0
    private val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tapRunnable = Runnable {
        when (tapCount) {
            3 -> { ProactiveScheduler.triggerNow(this@DebugOverlayService); android.widget.Toast.makeText(this@DebugOverlayService, "Worker 已触发", android.widget.Toast.LENGTH_SHORT).show() }
            5 -> {
                val chat = getSharedPreferences("wechat_settings", MODE_PRIVATE).getString("last_active_chat", "") ?: ""
                if (chat.isNotEmpty()) { ProactiveScheduler.forceSend(this@DebugOverlayService, chat); android.widget.Toast.makeText(this@DebugOverlayService, "测试消息已发送", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
        tapCount = 0
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }
        fun addLabel(label: String): TextView {
            val tv = TextView(this).apply {
                text = label
                textSize = 9f
                setTextColor(Color.argb(180, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            layout.addView(tv)
            return tv
        }
        fun addValue(): TextView {
            val tv = TextView(this).apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6 }
            }
            layout.addView(tv)
            return tv
        }
        tvApi = addValue().also { addLabel("API") }
        tvModel = addValue().also { addLabel("Model") }
        tvStatus = addValue().also { addLabel("Status") }
        tvTokens = addValue().also { addLabel("Tokens (last)") }
        tvGlass = addValue().also { addLabel("Glass") }
        tvEmo = addValue().also { addLabel("Emo") }
        tvOnnx = addValue().also { addLabel("Model") }
        tvEmoResult = addValue().also { addLabel("Emo Result") }
        tvAffinity = addValue().also { addLabel("Affinity") }
        tvProactive = addValue().also { addLabel("Proactive") }
        tvChatId = addValue().also { addLabel("Chat Key") }
        tvTime = addValue().also { addLabel("Time") }
        tvCtx = addValue().also { addLabel("Ctx") }

        overlay = layout
        updateInfo()

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            flag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 80; y = 200 }

        overlay.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { touchX = e.rawX; touchY = e.rawY; initX = params.x; initY = params.y; true }
                MotionEvent.ACTION_MOVE -> { params.x = (initX + e.rawX - touchX).toInt(); params.y = (initY + e.rawY - touchY).toInt(); wm.updateViewLayout(overlay, params); true }
                MotionEvent.ACTION_UP -> {
                    tapCount++
                    tapHandler.removeCallbacks(tapRunnable)
                    tapHandler.postDelayed(tapRunnable, 500)
                    updateInfo(); true
                }
                else -> false
            }
        }
        wm.addView(overlay, params)
        startUpdater()
    }

    private fun startUpdater() {
        isUpdating = true
        Thread {
            while (isUpdating) {
                Thread.sleep(2000)
                runOnUiThread { updateInfo() }
            }
        }.start()
    }

    private fun updateInfo() {
        val prefs = getSharedPreferences("wechat_settings", MODE_PRIVATE)
        val url = prefs.getString("ai_api_url", "")?.takeIf { it.isNotEmpty() }?.let { u ->
            if (u.length > 28) u.take(28) + "..." else u
        } ?: "N/A"
        val model = prefs.getString("ai_model", "")?.takeIf { it.isNotEmpty() } ?: "N/A"
        val configured = url != "N/A"
        val lastTokens = prefs.getInt("last_tokens_in", 0) + prefs.getInt("last_tokens_out", 0)
        val glass = if (prefs.getBoolean("glass_transparent", true)) "backdrop_captured" else "blurred"

        tvApi.text = url
        tvModel.text = model
        tvStatus.text = if (configured) "ready" else "not configured"
        tvTokens.text = if (lastTokens > 0) "$lastTokens (in/out)" else "N/A"
        tvGlass.text = glass
        val emoEnabled = prefs.getBoolean("mood_enabled", false)
        tvEmo.text = if (emoEnabled) "true" else "false"
        val modelStatus = if (MoodModel.isDownloaded(this)) "ready" else "not ready"
        tvOnnx.text = modelStatus + if (MoodModel.lastError.isNotEmpty()) "|${MoodModel.lastError}" else ""
        val last = MoodDetector.lastMood
        val hint = MoodDetector.lastHint
        val src = MoodDetector.lastSource
        val modelErr = MoodDetector.lastModelError
        val conf = MoodModel.lastConfidence
        tvEmoResult.text = when {
            !emoEnabled -> "off"
            src == "model_unavailable" -> "no model${if (modelErr.isNotEmpty()) "|$modelErr" else ""}"
            src == "model" && hint != null -> "model:$last(${hint.take(20)}) (${(conf*100).toInt()}%)"
            src == "model" -> "model:$last (${(conf*100).toInt()}%)"
            MoodDetector.feedCount == 0 -> "not triggered"
            last == null -> "waiting..."
            hint != null -> "$last(${hint.take(20)})"
            else -> last
        }
        val affPrefs = getSharedPreferences("wechat_settings", MODE_PRIVATE)
        val chatName = affPrefs.getString("last_active_chat", "") ?: ""
        tvChatId.text = if (chatName.isNotEmpty()) chatName else "(none)"
        val affEnabled = affPrefs.getBoolean("affinity_enabled", false)
        tvAffinity.text = if (affEnabled && chatName.isNotEmpty()) {
            val isAuto = AffinityManager.isAutoMode(affPrefs, chatName)
            if (isAuto) {
                val v = AffinityManager.getAffinity(affPrefs, chatName)
                val l = AffinityManager.getLevel(v)
                "$chatName ${l.name}(${v.toInt()})"
            } else {
                val idx = AffinityManager.getLockedLevel(affPrefs, chatName)
                val l = AffinityManager.levels[idx]
                "$chatName ${l.name}(锁)"
            }
        } else if (affEnabled) {
            "ok(no chat)"
        } else "off"
        // 主动关怀
        val todayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val proCount = if (chatName.isNotEmpty()) prefs.getInt("proactive_count_${chatName}_$todayKey", 0) else 0
        val proLast = prefs.getLong("proactive_last_$chatName", 0L)
        val silenced = prefs.getLong("proactive_silence_$chatName", 0L)
        val needCare = prefs.getBoolean("proactive_need_care_$chatName", false)
        val proEnabled = if (chatName.isNotEmpty()) prefs.getBoolean("proactive_enabled_$chatName", false) else false
        val idleH = prefs.getInt("proactive_idle_hours_$chatName", 0)
        val limit = prefs.getInt("proactive_daily_limit_$chatName", 3)
        val intMin = prefs.getInt("proactive_interval_min_$chatName", 30)
        // 守卫状态
        val guards = buildString {
            append(if (proEnabled) "开" else "关")
            append("|上限${limit}")
            append("|已用$proCount")
            if (silenced > System.currentTimeMillis()) append("|静默${(silenced - System.currentTimeMillis())/1000/60}m")
            if (proEnabled && chatName.isNotEmpty()) {
                val aff = AffinityManager.getAffinity(prefs, chatName)
                val lv = AffinityManager.getLevel(aff)
                val affIdx = AffinityManager.levels.indexOf(lv)
                val affMin = prefs.getInt("proactive_affinity_min_$chatName", 0)
                append("|好感${lv.name}(${aff.toInt()})≥$affMin=${if (affIdx >= affMin) "√" else "✗"}")
            }
        }
        // Worker 心跳
        val workerLast = prefs.getLong("worker_last_run", 0L)
        val workerAgo = if (workerLast > 0) "${(System.currentTimeMillis() - workerLast) / 1000}s前" else "never"
        // 倒计时
        val countdown = buildString {
            if (proLast > 0) {
                val elapsed = (System.currentTimeMillis() - proLast) / 60000
                val nextAvail = intMin - elapsed
                append("下次回复 ${if (nextAvail > 0) "${nextAvail}m后" else "now"}")
            } else {
                append("等待首次触发")
            }
        }
        val debugLog = if (chatName.isNotEmpty()) prefs.getString("proactive_last_debug_$chatName", "") ?: "" else ""
        val crashLog = if (chatName.isNotEmpty()) prefs.getString("proactive_crash_$chatName", "") ?: "" else ""
        val trace = if (chatName.isNotEmpty()) prefs.getString("proactive_trace_$chatName", "") ?: "" else ""
        val proLastCheck = if (chatName.isNotEmpty()) prefs.getLong("proactive_last_$chatName", 0L) else 0L
        tvProactive.text = "Worker:$workerAgo│守卫:$guards│$countdown${if(proLastCheck>0)"│已送达" else ""}${if(needCare)"│需关怀" else ""}"
        tvTime.text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val ctxVal = prefs.getInt("context_window", 0)
        tvCtx.text = if (ctxVal > 0) "${ctxVal}条" else "自动"
    }

    private fun runOnUiThread(action: () -> Unit) {
        overlay.post(action)
    }

    override fun onDestroy() {
        isUpdating = false
        if (overlay.isAttachedToWindow) wm.removeView(overlay)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
