package com.couchsubs.phone

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class SubtitleOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
        setBackgroundColor(0x99000000.toInt())
        gravity = Gravity.CENTER
        setPadding(20, 12, 20, 12)
        text = "NEXORA"
    }
    private var attached = false

    @Suppress("DEPRECATION")
    fun show(text: String) {
        view.post {
            view.text = text
            if (!attached) {
                val type = if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = 120
                }
                try {
                    windowManager.addView(view, params)
                    attached = true
                } catch (_: Exception) { }
            }
        }
    }

    fun hide() {
        if (attached) {
            try { windowManager.removeView(view) } catch (_: Exception) { }
            attached = false
        }
    }
}
