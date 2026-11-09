package com.couchsubs.phone

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class SubtitleOverlay(private val context: Context) {
    private val prefs = context.getSharedPreferences("nexora_overlay", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = TextView(context).apply {
        text = "NEXORA hazırlanıyor…"
        setTextColor(Color.WHITE)
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setPadding(32, 22, 32, 22)
        setLineSpacing(6f, 1.05f)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        background = GradientDrawable().apply {
            setColor(Color.argb(225, 12, 12, 11))
            cornerRadius = 24f
        }
        elevation = 24f
        visibility = View.GONE
    }
    @Volatile private var attached = false
    private var layoutParams: WindowManager.LayoutParams? = null

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val position = (prefs.getString("position", "bottom") ?: "bottom").trim().lowercase()
        val rawOffset = prefs.getInt("offset_dp", 120)
        val (gravity, y) = when (position) {
            "top" -> (Gravity.TOP or Gravity.CENTER_HORIZONTAL) to dp(rawOffset.coerceIn(16, 320))
            "middle", "center", "orta" -> (Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL) to 0
            else -> (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) to dp(rawOffset.coerceIn(40, 320))
        }
        val lp = layoutParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { layoutParams = it }
        lp.gravity = gravity
        lp.x = 0
        lp.y = y
        return lp
    }

    private fun applyStyle() {
        val size = prefs.getFloat("text_size_sp", 22f).coerceIn(14f, 40f)
        val color = prefs.getInt("text_color", Color.WHITE)
        view.textSize = size
        view.setTextColor(color)
        view.invalidate()
    }

    @Synchronized
    fun show(text: String) {
        mainHandler.post {
            if (text.isBlank()) {
                view.text = ""
                view.visibility = View.GONE
                return@post
            }
            applyStyle()
            view.text = text
            view.visibility = View.VISIBLE
            view.bringToFront()
            val params = buildParams()
            if (attached) {
                try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                return@post
            }
            try {
                windowManager.addView(view, params)
                attached = true
            } catch (_: Exception) {
                // Permission may be missing or blocked; caller also reports status via notification.
            }
        }
    }

    @Synchronized
    fun hide() {
        mainHandler.post {
            view.visibility = View.GONE
            if (!attached) return@post
            try { windowManager.removeView(view) } catch (_: Exception) {}
            attached = false
        }
    }
}
