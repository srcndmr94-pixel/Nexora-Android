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
            setColor(Color.argb(225, 12, 12, 12))
            cornerRadius = 24f
        }
        elevation = 24f
        visibility = View.GONE
    }
    @Volatile private var attached = false

    @Synchronized
    fun show(text: String) {
        mainHandler.post {
            view.text = text
            view.visibility = View.VISIBLE
            view.bringToFront()
            if (attached) return@post
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 180
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
