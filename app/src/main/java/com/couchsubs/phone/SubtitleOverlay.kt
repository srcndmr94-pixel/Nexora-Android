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
import kotlin.math.max

class SubtitleOverlay(private val context: Context) {
    private val prefs = context.getSharedPreferences("nexora_overlay", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = TextView(context).apply {
        text = "GameLingoPro hazırlanıyor…"
        setTextColor(Color.WHITE)
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setPadding(32, 22, 32, 22)
        setLineSpacing(6f, 1.05f)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
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
        val rawOffset = prefs.getInt("offset_dp", 56)
        val (gravity, y) = when (position) {
            "top" -> (Gravity.TOP or Gravity.CENTER_HORIZONTAL) to dp(rawOffset.coerceIn(16, 320))
            "middle", "center", "orta" -> (Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL) to 0
            else -> {
                // Oyun altyazisi ekranin en altinda; kutu onu kapatirsa OCR kendi yazimizi okur.
                val lift = (context.resources.displayMetrics.heightPixels * 0.26f).toInt()
                (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) to max(dp(rawOffset.coerceIn(24, 320)), lift)
            }
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
        val textColor = prefs.getInt("text_color", Color.WHITE)
        val opacity = prefs.getInt("bg_opacity", 88).coerceIn(0, 100)
        val strokeWidth = prefs.getInt("stroke_width_dp", 1).coerceIn(0, 6)
        val strokeColor = prefs.getInt("stroke_color", Color.BLACK)
        val maxLines = prefs.getInt("max_lines", 2).coerceIn(1, 4)
        view.textSize = size
        view.setTextColor(textColor)
        view.maxLines = maxLines
        view.ellipsize = if (maxLines > 1) android.text.TextUtils.TruncateAt.END else null
        view.background = GradientDrawable().apply {
            setColor(Color.argb(opacity * 255 / 100, 6, 14, 30))
            cornerRadius = dp(14).toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
        }
        view.invalidate()
    }

    @Synchronized
    fun show(text: String, sourceText: String = "") {
        mainHandler.post {
            if (text.isBlank()) {
                view.text = ""
                view.visibility = View.GONE
                return@post
            }
            applyStyle()
            val srcLine = sourceText.trim()
            if (srcLine.isBlank()) {
                view.text = text
            } else {
                view.maxLines = prefs.getInt("max_lines", 2).coerceIn(1, 4) + 1
                val sp = android.text.SpannableString("$text\n$srcLine")
                val start = text.length + 1
                sp.setSpan(
                    android.text.style.RelativeSizeSpan(0.6f), start, sp.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sp.setSpan(
                    android.text.style.ForegroundColorSpan(Color.argb(215, 205, 222, 255)),
                    start, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                view.text = sp
            }
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
