package com.example.wechatclone

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
    private var touchX = 0f; private var touchY = 0f
    private var initX = 0; private var initY = 0
    private var isUpdating = false

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
        tvTime = addValue().also { addLabel("Time") }

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
                MotionEvent.ACTION_UP -> { updateInfo(); true }
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
        tvTime.text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
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
