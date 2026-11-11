package com.couchsubs.phone

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.content.Context
import android.view.MotionEvent
import android.view.WindowManager
import kotlin.math.abs
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class MainActivity : ComponentActivity() {

    private companion object {
        const val PREFS = "nexora_overlay"
        const val BG = "#050B1A"
        const val SURFACE = "#071325"
        const val CARD = "#0A1526"
        const val CARD_LINE = "#14304C"
        const val TEXT = "#EAF2FF"
        const val MUTED = "#7F97B3"
        const val CYAN = "#22D3EE"
        const val CYAN_DEEP = "#0EA5C9"
        const val NAVY = "#071022"
        const val GREEN = "#34D399"
        const val AMBER = "#FBBF24"
        const val INK = "#04121C"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var content: FrameLayout
    private val navIcons = mutableListOf<ImageView>()
    private val navLabels = mutableListOf<TextView>()
    private var activeTab = 0
    private var overlayPermissionRequested = false
    private var modelView: TextView? = null
    private val modelManager by lazy { RemoteModelManager.getInstance() }
    private val enTrModel by lazy { TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build() }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            LogStore.append(this, "DURUM", "Ekran yakalama izni verildi, servis başlatılıyor")
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
                toast("NEXORA başlatıldı — oyununa geçebilirsin")
            } catch (e: Exception) {
                val msg = "Servis başlatılamadı: ${e.localizedMessage ?: "bilinmeyen hata"}"
                LogStore.append(this, "HATA", msg)
                toast(msg)
            }
        } else {
            LogStore.append(this, "HATA", "Ekran yakalama izni reddedildi/iptal edildi")
            toast("Ekran yakalama izni verilmedi")
        }
        renderTab()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        CrashGuard.install(this)
        window.statusBarColor = color(BG)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(BG))
        }
        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        root.addView(content)
        root.addView(navBarView())
        setContentView(root)
        renderTab()
        LogStore.append(this, "DURUM", "Uygulama açıldı")
        LogStore.append(
            this,
            "DURUM",
            "Cihaz: " + Build.MANUFACTURER + " " + Build.MODEL + " | Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
        )
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) renderTab()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this) && !overlayPermissionRequested) {
            overlayPermissionRequested = true
            requestOverlayPermission()
        }
    }

    // ---------- shell ----------

    private fun renderTab() {
        content.removeAllViews()
        content.addView(when (activeTab) {
            0 -> homeScreen()
            1 -> settingsScreen()
            2 -> historyScreen()
            else -> aboutScreen()
        })
        refreshNav()
    }

    private fun navBarView(): LinearLayout {
        navIcons.clear()
        navLabels.clear()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(NAVY))
            setPadding(0, dp(6), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val items = listOf(
            Triple(R.drawable.ic_nav_home, "Ana Sayfa", 0),
            Triple(R.drawable.ic_nav_settings, "Ayarlar", 1),
            Triple(R.drawable.ic_nav_history, "Geçmiş", 2),
            Triple(R.drawable.ic_nav_info, "Hakkında", 3)
        )
        for ((iconRes, label, index) in items) {
            val icon = ImageView(this).apply { setImageResource(iconRes) }
            val text = TextView(this).apply {
                text = label
                textSize = 10f
                setPadding(0, dp(4), 0, 0)
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                setOnClickListener {
                    if (activeTab != index) {
                        activeTab = index
                        renderTab()
                    }
                }
                addView(icon, LinearLayout.LayoutParams(dp(21), dp(21)))
                addView(text)
            }
            navIcons.add(icon)
            navLabels.add(text)
            bar.addView(item, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun refreshNav() {
        navIcons.forEachIndexed { i, icon -> icon.setColorFilter(color(if (i == activeTab) CYAN else MUTED)) }
        navLabels.forEachIndexed { i, tv ->
            tv.setTextColor(color(if (i == activeTab) CYAN else MUTED))
            tv.typeface = Typeface.create(Typeface.SANS_SERIF, if (i == activeTab) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(26))
    }

    private fun scroll(child: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(child)
    }

    // ---------- home ----------

    private fun homeScreen(): View {
        val col = page()
        col.addView(brandRow())
        col.addView(startCard())
        col.addView(quickCards())
        col.addView(space(14))
        col.addView(statusStrip())
        col.addView(previewCard())
        return scroll(col)
    }

    private fun brandRow(): LinearLayout {
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_nexora_mark) }
        val name = TextView(this).apply {
            text = "NEXORA"
            textSize = 26f
            setTextColor(color(TEXT))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val tag = TextView(this).apply {
            text = "OYUNLARDA HER SÖZ ANLAM KAZANSIN"
            textSize = 9f
            setTextColor(color(MUTED))
            letterSpacing = 0.14f
            setPadding(0, dp(3), 0, 0)
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(tag)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(20))
            addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun startCard(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(color("#2BE8F5"), color(CYAN_DEEP))
            ).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(18), dp(16), dp(14), dp(16))
            elevation = dp(6).toFloat()
            isClickable = true
            setOnClickListener { startWithPermissions() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val play = TextView(this).apply {
            text = "\u25B6"
            textSize = 20f
            setTextColor(color(INK))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "ÇEVİRİYİ BAŞLAT"
                textSize = 19f
                setTextColor(color(INK))
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Oyun oynarken anlık çeviri"
                textSize = 11.5f
                setTextColor(color("#0A3A47"))
                setPadding(0, dp(2), 0, 0)
            })
        }
        val stop = TextView(this).apply {
            text = "Durdur"
            textSize = 11f
            setTextColor(color(INK))
            background = bgRound("#D2F6FB", 10)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            isClickable = true
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                LogStore.append(this@MainActivity, "DURUM", "Kullanıcı yakalamayı durdurdu")
                toast("NEXORA durduruldu")
                renderTab()
            }
        }
        box.addView(play, LinearLayout.LayoutParams(dp(30), WRAP_CONTENT))
        box.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(stop)
        return box
    }

    private fun quickCards(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        row.addView(quickCard(R.drawable.ic_area_frame, "Altyazı Alanı", "Hangi bölge okunacak", 1))
        row.addView(quickCard(R.drawable.ic_translate, "Çeviri Ayarları", "Çevrimdışı / İnternet", 1))
        row.addView(quickCard(R.drawable.ic_monitor, "Görünüm", "Stil, boyut, konum", 1))
        return row
    }

    private fun quickCard(iconRes: Int, title: String, sub: String, tab: Int): LinearLayout {
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bgRound(CARD, 18, CARD_LINE)
            setPadding(dp(8), dp(16), dp(8), dp(16))
            isClickable = true
            setOnClickListener {
                activeTab = tab
                renderTab()
            }
        }
        cardView.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(color(CYAN))
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        cardView.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(color(TEXT))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        })
        cardView.addView(TextView(this).apply {
            text = sub
            textSize = 9.5f
            setTextColor(color(MUTED))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        cardView.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
            marginStart = dp(6)
        }
        return cardView
    }

    private fun statusStrip(): LinearLayout {
        val running = ScreenCaptureService.running
        val mode = prefs.getString("translation_mode", "device") ?: "device"
        val online = mode == "online" || mode == "ai"
        val cardView = card(14)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(statusCell(if (running) GREEN else AMBER, if (running) "Sistem Aktif" else "Hazır", if (running) "Arka planda çalışıyor" else "Çeviri motoru bekliyor"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(View(this).apply {
            setBackgroundColor(color("#152A42"))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(36)).apply { marginStart = dp(10); marginEnd = dp(10) }
        })
        row.addView(statusCell(CYAN, if (mode == "ai") "Yapay Zekâ" else if (online) "İnternet Modu" else "Çevrimdışı Mod", if (mode == "ai") "En doğal çeviri" else if (online) "Daha doğal çeviri" else "Kotasız ve kesintisiz"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        cardView.addView(row)
        return cardView
    }

    private fun statusCell(dot: String, title: String, sub: String): LinearLayout {
        val dotView = View(this).apply {
            background = bgRound(dot, 6)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(9) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                setTextColor(color(TEXT))
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub
                textSize = 9.5f
                setTextColor(color(MUTED))
                setPadding(0, dp(2), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dotView)
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun previewCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_monitor, "Gerçek Zamanlı Oyun Çevirisi", "Yabancı dildeki replikler anında Türkçeye dönüşür."))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgRound("#071325", 14, "#173755")
            setPadding(dp(14), dp(16), dp(14), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "\"The path ahead won't be easy.\""
            textSize = 12f
            setTextColor(color("#9FB6CE"))
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "\u2193"
            textSize = 13f
            setTextColor(color(CYAN))
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
        })
        box.addView(TextView(this).apply {
            text = "Önümüzdeki yol kolay olmayacak."
            textSize = 13f
            setTextColor(color(TEXT))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        cardView.addView(box)
        return cardView
    }

    // ---------- settings ----------

    private fun settingsScreen(): View {
        val col = page()
        col.addView(titleBlock("Ayarlar", "Uygulamayı kendi oyun deneyimine göre özelleştir.", R.drawable.ic_nav_settings))
        col.addView(resetChip())
        col.addView(areaCard())
        col.addView(modeCard())
        col.addView(aiCard())
        col.addView(appearanceCard())
        col.addView(performanceCard())
        col.addView(glossaryCard())
        col.addView(advancedCard())
        col.addView(modelCard())
        col.addView(voiceCard())
        col.addView(systemCard())
        return scroll(col)
    }

    private fun titleBlock(title: String, sub: String, iconRes: Int): LinearLayout {
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(color(CYAN))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 24f
                setTextColor(color(TEXT))
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub
                textSize = 11.5f
                setTextColor(color(MUTED))
                setPadding(0, dp(4), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(12))
            addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun resetChip(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, 0, 0, dp(14))
        addView(TextView(this@MainActivity).apply {
            text = "\u21BA  Varsayılanlara Dön"
            textSize = 11.5f
            setTextColor(color(TEXT))
            background = bgRound("#101F33", 12, CARD_LINE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                resetDefaults()
            }
        })
    }

    // ---------- alan secici ----------

    private var pickerRoot: View? = null
    private var pickerWm: WindowManager? = null

    private inner class RegionPickerView(context: Context) : View(context) {
        val sel = RectF()
        private var pending: FloatArray? = null
        private val dim = Paint().apply { color = Color.parseColor("#B3050B1A") }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = color(CYAN)
        }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.parseColor("#4D22D3EE")
        }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = color(CYAN)
        }
        private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(CYAN)
            textSize = dp(11).toFloat()
        }
        private var mode = 0
        private var downX = 0f
        private var downY = 0f
        private val start = RectF()

        fun load(l: Float, t: Float, r: Float, b: Float) {
            pending = floatArrayOf(l, t, r, b)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val p = pending ?: floatArrayOf(0.06f, 0.62f, 0.94f, 0.94f)
            sel.set(w * p[0], h * p[1], w * p[2], h * p[3])
            fit()
        }

        private fun minW(): Float = dp(60).toFloat()

        private fun minH(): Float = dp(30).toFloat()

        private fun fit() {
            if (width <= 0 || height <= 0) return
            if (sel.right - sel.left < minW()) sel.right = sel.left + minW()
            if (sel.bottom - sel.top < minH()) sel.bottom = sel.top + minH()
            if (sel.left < 0f) {
                sel.right -= sel.left
                sel.left = 0f
            }
            if (sel.top < 0f) {
                sel.bottom -= sel.top
                sel.top = 0f
            }
            if (sel.right > width) {
                sel.left -= sel.right - width
                sel.right = width.toFloat()
            }
            if (sel.bottom > height) {
                sel.top -= sel.bottom - height
                sel.bottom = height.toFloat()
            }
            if (sel.left < 0f) sel.left = 0f
            if (sel.top < 0f) sel.top = 0f
        }

        private fun cornerIndex(): Int {
            val hs = dp(40).toFloat()
            val pts = arrayOf(
                PointF(sel.left, sel.top), PointF(sel.right, sel.top),
                PointF(sel.right, sel.bottom), PointF(sel.left, sel.bottom)
            )
            for (i in pts.indices) {
                if (abs(downX - pts[i].x) <= hs && abs(downY - pts[i].y) <= hs) return i
            }
            return -1
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    start.set(sel)
                    val c = cornerIndex()
                    mode = when {
                        c >= 0 -> c + 2
                        sel.contains(event.x, event.y) -> 1
                        else -> 0
                    }
                    if (mode == 0) {
                        sel.set(event.x, event.y, event.x + dp(140), event.y + dp(70))
                        fit()
                        start.set(sel)
                        mode = 4
                    }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    when (mode) {
                        1 -> {
                            sel.set(start)
                            sel.offset(dx, dy)
                            if (sel.left < 0f) sel.offset(-sel.left, 0f)
                            if (sel.top < 0f) sel.offset(0f, -sel.top)
                            if (sel.right > width) sel.offset(width - sel.right, 0f)
                            if (sel.bottom > height) sel.offset(0f, height - sel.bottom)
                        }
                        2 -> {
                            sel.set(start)
                            sel.left = (start.left + dx).coerceAtMost(sel.right - minW())
                            sel.top = (start.top + dy).coerceAtMost(sel.bottom - minH())
                            fit()
                        }
                        3 -> {
                            sel.set(start)
                            sel.right = (start.right + dx).coerceAtLeast(sel.left + minW())
                            sel.top = (start.top + dy).coerceAtMost(sel.bottom - minH())
                            fit()
                        }
                        4 -> {
                            sel.set(start)
                            sel.right = (start.right + dx).coerceAtLeast(sel.left + minW())
                            sel.bottom = (start.bottom + dy).coerceAtLeast(sel.top + minH())
                            fit()
                        }
                        5 -> {
                            sel.set(start)
                            sel.left = (start.left + dx).coerceAtMost(sel.right - minW())
                            sel.bottom = (start.bottom + dy).coerceAtLeast(sel.top + minH())
                            fit()
                        }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, sel.top, dim)
            canvas.drawRect(0f, sel.bottom, w, h, dim)
            canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dim)
            canvas.drawRect(sel.right, sel.top, w, sel.bottom, dim)
            canvas.drawRect(sel, border)
            val mx = sel.centerX()
            val my = sel.centerY()
            canvas.drawLine(sel.left, my, sel.right, my, grid)
            canvas.drawLine(mx, sel.top, mx, sel.bottom, grid)
            val r = dp(8).toFloat()
            canvas.drawCircle(sel.left, sel.top, r, knob)
            canvas.drawCircle(sel.right, sel.top, r, knob)
            canvas.drawCircle(sel.right, sel.bottom, r, knob)
            canvas.drawCircle(sel.left, sel.bottom, r, knob)
            canvas.drawText("ALTYAZI ALANI", sel.left + dp(8), sel.top + dp(16), tag)
        }
    }

    private fun areaStatusText(): String {
        val region = prefs.getString("region", "bottom") ?: "bottom"
        return when (region) {
            "custom" -> "Özel alan: yatay %" + (prefs.getFloat("region_l", 0.06f) * 100).toInt() +
                "–%" + (prefs.getFloat("region_r", 0.94f) * 100).toInt() +
                ", dikey %" + (prefs.getFloat("region_t", 0.62f) * 100).toInt() +
                "–%" + (prefs.getFloat("region_b", 0.94f) * 100).toInt()
            "top" -> "Hazır alan: üst bölge"
            "middle" -> "Hazır alan: orta bölge"
            "full" -> "Hazır alan: tam ekran"
            else -> "Hazır alan: alt bölge"
        }
    }

    private fun showRegionPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("Önce ekran üstünde gösterim iznini ver")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            } catch (_: Exception) {
            }
            return
        }
        hideRegionPicker()
        val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val picker = RegionPickerView(this).apply {
            load(
                prefs.getFloat("region_l", 0.06f),
                prefs.getFloat("region_t", 0.62f),
                prefs.getFloat("region_r", 0.94f),
                prefs.getFloat("region_b", 0.94f)
            )
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgRound("#F20A1526", 16, CARD_LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        bar.addView(TextView(this).apply {
            text = "Kutuyu parmağınla taşı, köşelerinden büyüt. Oyununa geç, altyazının üstüne getir ve Kaydet'e bas."
            textSize = 11.5f
            setTextColor(color(TEXT))
        })
        bar.addView(space(8))
        bar.addView(rowOf(
            ghostButton("Kaydet") {
                val w = if (picker.width > 0) picker.width else resources.displayMetrics.widthPixels
                val h = if (picker.height > 0) picker.height else resources.displayMetrics.heightPixels
                val l = (picker.sel.left / w).coerceIn(0f, 0.96f)
                val t = (picker.sel.top / h).coerceIn(0f, 0.96f)
                var r = (picker.sel.right / w).coerceIn(0.03f, 1f)
                var b = (picker.sel.bottom / h).coerceIn(0.03f, 1f)
                if (r < l + 0.02f) r = (l + 0.02f).coerceAtMost(1f)
                if (b < t + 0.02f) b = (t + 0.02f).coerceAtMost(1f)
                prefs.edit()
                    .putFloat("region_l", l)
                    .putFloat("region_t", t)
                    .putFloat("region_r", r)
                    .putFloat("region_b", b)
                    .putString("region", "custom")
                    .putString("position", "custom")
                    .apply()
                LogStore.append(this@MainActivity, "AYAR", "Özel altyazı alanı: x " +
                    (l * 100).toInt() + "-" + (r * 100).toInt() + ", y " +
                    (t * 100).toInt() + "-" + (b * 100).toInt())
                hideRegionPicker()
                toast("Alan kaydedildi")
                renderTab()
            },
            ghostButton("İptal") { hideRegionPicker() }
        ))
        val root = FrameLayout(this).apply {
            addView(picker, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = dp(28)
                leftMargin = dp(14)
                rightMargin = dp(14)
            })
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            MATCH_PARENT,
            MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            wm.addView(root, params)
            pickerRoot = root
            pickerWm = wm
            toast("Seçici açık — oyununa geçip kutuyu altyazının üstüne getir")
        } catch (e: Exception) {
            toast("Seçici açılamadı: " + (e.localizedMessage ?: "izin yok"))
        }
    }

    private fun hideRegionPicker() {
        val v = pickerRoot ?: return
        pickerRoot = null
        try {
            pickerWm?.removeView(v)
        } catch (_: Exception) {
        }
        pickerWm = null
    }

    private fun areaCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_area_frame, "Altyazı Alanı (OCR Bölgesi)", "Oyun ekranında altyazıların bulunduğu alanı seç."))
        val frameBox = FrameLayout(this).apply {
            background = bgRound("#071325", 14, "#16344E")
        }
        frameBox.addView(View(this).apply {
            background = bgRound("#00000000", 10, CYAN)
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(56), Gravity.CENTER).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
        })
        frameBox.addView(TextView(this).apply {
            text = "Bu alandaki metinler okunur"
            textSize = 10.5f
            setTextColor(color(TEXT))
            background = bgRound("#04121C", 8, "#1E5C71")
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        cardView.addView(frameBox, LinearLayout.LayoutParams(MATCH_PARENT, dp(112)).apply { bottomMargin = dp(12) })
        cardView.addView(TextView(this).apply {
            text = areaStatusText()
            textSize = 11f
            setTextColor(color(CYAN))
            setPadding(0, 0, 0, dp(10))
        })
        cardView.addView(rowOf(
            ghostButton("Alanı Ayarla") { showRegionPicker() },
            ghostButton("Tam Ekran") {
                prefs.edit().putString("position", "middle").putString("region", "full").putInt("offset_dp", 56).apply()
                toast("Alan: tam ekran")
                renderTab()
            },
            ghostButton("Sıfırla") {
                prefs.edit().putString("position", "bottom").putString("region", "bottom").putInt("offset_dp", 56).apply()
                toast("Alan sıfırlandı")
                renderTab()
            }
        ))
        return cardView
    }

    private fun modeCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_translate, "Çeviri Modu", "İhtiyacına en uygun çeviri modunu seç."))
        val current = prefs.getString("translation_mode", "device")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(modeOption(
            "Çevrimdışı",
            "Kotasız, internet gerektirmez. Cihazdaki model ile hızlı çeviri.",
            true,
            current != "online"
        ) {
            prefs.edit().putString("translation_mode", "device").apply()
            toast("Çevrimdışı mod")
            renderTab()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(modeOption(
            "AI Destekli",
            "Daha doğal çeviri. İnternet üzerinden çevirir, bağlantı yoksa çevrimdışına döner.",
            false,
            current == "online"
        ) {
            prefs.edit().putString("translation_mode", "online").apply()
            toast("İnternet çevirisi")
            renderTab()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        cardView.addView(row)
        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        aiRow.addView(modeOption(
            "Yapay Zekâ",
            "En doğal çeviri. Ücretsiz anahtar gerekir, günlük sınırı vardır.",
            true,
            current == "ai"
        ) {
            prefs.edit().putString("translation_mode", "ai").apply()
            toast(if ((prefs.getString("ai_key", "") ?: "").isBlank()) "Önce aşağıya anahtarı gir" else "Yapay zekâ çevirisi")
            renderTab()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        aiRow.addView(View(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        cardView.addView(aiRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        return cardView
    }

    private fun modeOption(title: String, sub: String, recommended: Boolean, selected: Boolean, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (selected) bgRound("#0C2036", 14, CYAN) else bgRound(CARD, 14, CARD_LINE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this@MainActivity).apply {
            text = if (selected) "\u25CF" else "\u25CB"
            textSize = 13f
            setTextColor(color(if (selected) CYAN else MUTED))
            setPadding(0, 0, dp(7), 0)
        })
        head.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 13f
            setTextColor(color(TEXT))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (recommended) {
            head.addView(TextView(this@MainActivity).apply {
                text = "Önerilen"
                textSize = 9f
                setTextColor(color(INK))
                background = bgRound(GREEN, 8)
                setPadding(dp(7), dp(3), dp(7), dp(3))
            })
        }
        box.addView(head)
        box.addView(TextView(this).apply {
            text = sub
            textSize = 10.5f
            setTextColor(color(MUTED))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        })
        return box
    }

    private fun appearanceCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_monitor, "Altyazı Görünümü", "Ekrandaki çeviri altyazısını özelleştir."))
        val preview = TextView(this).apply {
            text = "Bu oyunun dünyası\nartık daha anlaşılır."
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        fun refreshPreview() {
            val size = prefs.getFloat("text_size_sp", 22f)
            val opacity = prefs.getInt("bg_opacity", 88).coerceIn(0, 100)
            val strokeWidth = prefs.getInt("stroke_width_dp", 1).coerceIn(0, 6)
            preview.textSize = (size * 0.6f).coerceIn(8f, 22f)
            preview.setTextColor(prefs.getInt("text_color", Color.WHITE))
            preview.maxLines = prefs.getInt("max_lines", 2).coerceIn(1, 4)
            preview.setPadding(dp(10), dp(12), dp(10), dp(12))
            preview.background = GradientDrawable().apply {
                setColor(Color.argb((opacity * 255) / 100, 6, 14, 30))
                cornerRadius = dp(10).toFloat()
                if (strokeWidth > 0) setStroke(dp(strokeWidth), prefs.getInt("stroke_color", Color.BLACK))
            }
        }
        refreshPreview()

        val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sliders.addView(sliderRow(
            "Yazı Boyutu",
            260,
            ((prefs.getFloat("text_size_sp", 22f) - 14f) * 10f).toInt().coerceIn(0, 260),
            { "${((14f + it / 10f) / 22f * 100).toInt()}%" }
        ) {
            prefs.edit().putFloat("text_size_sp", 14f + it / 10f).apply()
            refreshPreview()
        })
        sliders.addView(sliderRow(
            "Arka Plan Opaklığı",
            100,
            prefs.getInt("bg_opacity", 88),
            { "$it%" }
        ) {
            prefs.edit().putInt("bg_opacity", it).apply()
            refreshPreview()
        })
        sliders.addView(sliderRow(
            "Kenar Kalınlığı",
            6,
            prefs.getInt("stroke_width_dp", 1),
            { "$it px" }
        ) {
            prefs.edit().putInt("stroke_width_dp", it).apply()
            refreshPreview()
        })

        val previewBox = FrameLayout(this).apply {
            background = bgRound("#071325", 12, "#16344E")
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        previewBox.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        val previewCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Önizleme"
                textSize = 10.5f
                setTextColor(color(MUTED))
                setPadding(0, 0, 0, dp(6))
            })
            addView(previewBox, LinearLayout.LayoutParams(MATCH_PARENT, dp(132)))
        }

        val mainRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mainRow.addView(sliders, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.45f))
        mainRow.addView(previewCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        cardView.addView(mainRow)

        cardView.addView(space(10))
        cardView.addView(TextView(this).apply {
            text = "Satır Sayısı"
            textSize = 11.5f
            setTextColor(color(MUTED))
            setPadding(0, 0, 0, dp(6))
        })
        cardView.addView(chipRow(listOf("1", "2", "3"), prefs.getInt("max_lines", 2) - 1) { i ->
            prefs.edit().putInt("max_lines", i + 1).apply()
            refreshPreview()
        })

        val position = (prefs.getString("position", "bottom") ?: "bottom")
        val positionIndex = when (position) {
            "top" -> 0
            "middle" -> 1
            "bottom" -> 2
            else -> -1
        }
        cardView.addView(space(10))
        cardView.addView(TextView(this).apply {
            text = "Konum"
            textSize = 11.5f
            setTextColor(color(MUTED))
            setPadding(0, 0, 0, dp(6))
        })
        cardView.addView(chipRow(listOf("Üst", "Orta", "Alt"), positionIndex) { i ->
            val chipRegion = listOf("top", "middle", "bottom")[i]
            prefs.edit().putString("position", chipRegion).putString("region", chipRegion).apply()
            toast("Konum: ${listOf("Üst", "Orta", "Alt")[i]}")
        })

        cardView.addView(space(10))
        cardView.addView(TextView(this).apply {
            text = "Yazı Rengi"
            textSize = 11.5f
            setTextColor(color(MUTED))
            setPadding(0, 0, 0, dp(8))
        })
        cardView.addView(colorRow("text_color", Color.WHITE) { refreshPreview() })

        cardView.addView(space(10))
        cardView.addView(TextView(this).apply {
            text = "Kenar Rengi"
            textSize = 11.5f
            setTextColor(color(MUTED))
            setPadding(0, 0, 0, dp(8))
        })
        cardView.addView(colorRow("stroke_color", Color.BLACK) { refreshPreview() })
        return cardView
    }

    private fun performanceCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_sliders, "Performans", "Cihazına uygun performans ayarlarını yap."))
        cardView.addView(sliderRow(
            "OCR Sıklığı",
            500,
            prefs.getInt("ocr_interval_ms", 250) - 100,
            { "${it + 100} ms" }
        ) { prefs.edit().putInt("ocr_interval_ms", it + 100).apply() })
        cardView.addView(sliderRow(
            "Kararlılık Süresi",
            5000,
            prefs.getInt("clear_ms", 2800) - 1000,
            { "${it + 1000} ms" }
        ) { prefs.edit().putInt("clear_ms", it + 1000).apply() })
        cardView.addView(sliderRow(
            "Maks. Çeviri Uzunluğu",
            400,
            prefs.getInt("max_chars", 250) - 100,
            { "${it + 100} karakter" }
        ) { prefs.edit().putInt("max_chars", it + 100).apply() })
        return cardView
    }

    private fun aiCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_translate, "Yapay Zekâ Anahtarı", "Ücretsiz anahtarla daha doğal çeviri."))
        cardView.addView(bodyText("1) console.groq.com/keys adresinden ücretsiz anahtar oluştur (kart gerekmez).\n2) Anahtarı aşağıya yapıştır ve kaydet.\n3) Çeviri Modu'ndan “Yapay Zekâ”yı seç."))
        val keyInput = EditText(this).apply {
            setText(prefs.getString("ai_key", "") ?: "")
            hint = "gsk_..."
            textSize = 12.5f
            setSingleLine(true)
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bgRound(SURFACE, 12, CARD_LINE)
        }
        cardView.addView(keyInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        val modelInput = EditText(this).apply {
            setText(prefs.getString("ai_model", "") ?: "")
            hint = "Model (boş bırak: openai/gpt-oss-120b)"
            textSize = 12f
            setSingleLine(true)
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bgRound(SURFACE, 12, CARD_LINE)
        }
        cardView.addView(modelInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val saved = (prefs.getString("ai_key", "") ?: "").isNotBlank()
        cardView.addView(TextView(this).apply {
            text = if (saved) "Anahtar kayıtlı. Yapay zekâ çevirisi hazır." else "Henüz anahtar yok; çeviri eski yöntemle yapılır."
            textSize = 12f
            setTextColor(color(if (saved) GREEN else MUTED))
            setPadding(0, dp(8), 0, dp(6))
        })
        cardView.addView(rowOf(
            ghostButton("Anahtarı Kaydet") {
                prefs.edit()
                    .putString("ai_key", keyInput.text.toString().trim())
                    .putString("ai_model", modelInput.text.toString().trim())
                    .apply()
                toast("Kaydedildi")
                renderTab()
            },
            ghostButton("Anahtarı Sil") {
                prefs.edit().remove("ai_key").apply()
                toast("Anahtar silindi")
                renderTab()
            }
        ))
        return cardView
    }

    private fun glossaryCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_translate, "Bağlam ve Sözlük", "İsimleri korur, konuşmayı hatırlar."))
        cardView.addView(toggleRow("Konuşma Bağlamını Hatırla", "context_memory", true))
        cardView.addView(toggleRow("Satırları Sabitle (daha az titreme)", "stabilize", true))
        val input = EditText(this).apply {
            setText(prefs.getString("glossary_keep", "") ?: "")
            hint = "Korunacak isim / terim, her satıra bir tane"
            textSize = 12.5f
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bgRound(SURFACE, 12, CARD_LINE)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit().putString("glossary_keep", s?.toString() ?: "").apply()
                }
            })
        }
        cardView.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        return cardView
    }

    private fun advancedCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_tune, "Gelişmiş", "İleri düzey ayarlar."))
        cardView.addView(toggleRow("Oyun Arayüzü Metinlerini Filtrele", "hud_filter", true))
        cardView.addView(toggleRow("Altyazı Alanını Otomatik Bul", "auto_region", true))
        cardView.addView(toggleRow("Tekrarlayan Metinleri Birleştir", "merge_repeat", true))
        cardView.addView(toggleRow("Yüksek Çözünürlüklü OCR", "ocr_hires", true))
        cardView.addView(toggleRow("OCR Atlama Kayıtları (teşhis)", "debug_log", false))
        return cardView
    }

    private fun modelCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_translate, "Dil Paketi", "İngilizce → Türkçe çeviri modeli."))
        val state = TextView(this).apply {
            text = "Durum kontrol ediliyor…"
            textSize = 12f
            setTextColor(color(TEXT))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(10))
        }
        modelView = state
        cardView.addView(state)
        cardView.addView(rowOf(
            ghostButton("Paketi indir") { downloadModel() },
            ghostButton("Paketi sil") { deleteModel() }
        ))
        refreshModelState()
        return cardView
    }

    private fun voiceCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_monitor, "Seslendirme", "Çevrilen altyazı sesli okunsun."))
        cardView.addView(toggleRow("Seslendirmeyi aç", "tts_enabled", false))
        cardView.addView(TextView(this).apply {
            text = "Konuşma hızı"
            textSize = 11.5f
            setTextColor(color(MUTED))
            setPadding(0, dp(2), 0, dp(6))
        })
        val rate = prefs.getFloat("tts_rate", 1.0f)
        val rateIndex = when {
            rate < 0.95f -> 0
            rate > 1.05f -> 2
            else -> 1
        }
        cardView.addView(chipRow(listOf("Yavaş", "Normal", "Hızlı"), rateIndex) { i ->
            prefs.edit().putFloat("tts_rate", listOf(0.85f, 1.0f, 1.15f)[i]).apply()
            toast("Hız: ${listOf("Yavaş", "Normal", "Hızlı")[i]}")
        })
        cardView.addView(space(10))
        cardView.addView(ghostButton("Ses seç") { showVoiceDialog() })
        return cardView
    }

    private fun systemCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_tune, "Sistem", "İzinler, kayıtlar ve teşhis."))
        cardView.addView(permissionRow("Bildirim izni", notificationGranted()) { requestNotificationPermission() })
        cardView.addView(permissionRow("Üstte gösterme izni", overlayGranted()) { requestOverlayPermission() })
        cardView.addView(space(10))
        cardView.addView(rowOf(
            ghostButton("Günlük") { showLogs() },
            ghostButton("Paylaş") { shareLogs() },
            ghostButton("Temizle") {
                LogStore.clear(this)
                toast("Kayıtlar temizlendi")
            }
        ))
        return cardView
    }

    private fun permissionRow(label: String, granted: Boolean, onClick: () -> Unit): LinearLayout {
        val pill = TextView(this).apply {
            text = if (granted) "Açık" else "Eksik"
            textSize = 10f
            setTextColor(color(INK))
            background = bgRound(if (granted) GREEN else AMBER, 9)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12.5f
                setTextColor(color(TEXT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill)
        }
    }

    // ---------- history ----------

    private fun historyScreen(): View {
        val col = page()
        col.addView(titleBlock("Çeviri Geçmişi", "Son çevrilen altyazılar.", R.drawable.ic_nav_history))
        val lines = LogStore.read(this).lineSequence()
            .filter { it.contains("[CEVIRI]") }
            .toList()
            .asReversed()
            .take(40)
        if (lines.isEmpty()) {
            col.addView(emptyNote("Henüz çeviri yok. Çeviriyi başlatıp bir oyun açtığında son çeviriler burada listelenir."))
        } else {
            for (line in lines) {
                val body = line.substringAfter("[CEVIRI]", "").trim()
                val en = body.substringAfter("EN:", "").substringBefore("||").trim()
                val tr = body.substringAfter("||", "").substringAfter("):", "").trim()
                col.addView(historyItem(en, tr))
            }
        }
        return scroll(col)
    }

    private fun historyItem(en: String, tr: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bgRound(CARD, 14, CARD_LINE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(TextView(this@MainActivity).apply {
            text = en
            textSize = 11.5f
            setTextColor(color(MUTED))
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        addView(TextView(this@MainActivity).apply {
            text = tr
            textSize = 13.5f
            setTextColor(color(TEXT))
            setPadding(0, dp(5), 0, 0)
        })
    }

    private fun emptyNote(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(color(MUTED))
        setLineSpacing(dp(3).toFloat(), 1f)
        background = bgRound(CARD, 14, CARD_LINE)
        setPadding(dp(14), dp(16), dp(14), dp(16))
    }

    // ---------- about ----------

    private fun aboutScreen(): View {
        val col = page()
        col.addView(titleBlock("Hakkında", "NEXORA — oyunlar kendi dilinde.", R.drawable.ic_nav_info))
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_nexora_mark) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(16))
            addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "NEXORA"
                    textSize = 22f
                    setTextColor(color(TEXT))
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    letterSpacing = 0.08f
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Sürüm 0.8.0"
                    textSize = 11.5f
                    setTextColor(color(MUTED))
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        val info = card()
        info.addView(bodyText("NEXORA, ekrandaki İngilizce altyazıları okur ve Türkçeye çevirir. Çeviri tamamen cihaz üzerinde yapılabilir; internet ya da yapay zekâ modunu seçersen altyazı satırı çeviri servisine gönderilir."))
        info.addView(space(10))
        info.addView(bodyText("• Sansür uygulanmaz: kaynak metin neyse o çevrilir.\n• Oyun arayüzü metinleri filtrelenir.\n• Altyazı konumu, boyutu, rengi ve opaklığı ayarlanabilir."))
        col.addView(info)
        val privacy = card()
        privacy.addView(bodyText("Çevrimdışı modda altyazı metni cihazdan çıkmaz. İnternet modunda yalnızca altyazı metni MyMemory servisine gönderilir; hesap gerekmez."))
        col.addView(privacy)
        return scroll(col)
    }

    private fun bodyText(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12.5f
        setTextColor(color("#C5D6E8"))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    // ---------- reusable widgets ----------

    private fun card(padding: Int = 16): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bgRound(CARD, 20, CARD_LINE)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun cardHeader(iconRes: Int, title: String, sub: String): LinearLayout {
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(color(CYAN))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(color(TEXT))
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub
                textSize = 11f
                setTextColor(color(MUTED))
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(3), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 0, 0, dp(14))
            addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(3); marginEnd = dp(10) })
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun sliderRow(label: String, maxValue: Int, value: Int, valueText: (Int) -> String, onChange: (Int) -> Unit): LinearLayout {
        val safeValue = value.coerceIn(0, maxValue)
        val valueLabel = TextView(this).apply {
            text = valueText(safeValue)
            textSize = 12f
            setTextColor(color(CYAN))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12.5f
                setTextColor(color(TEXT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(valueLabel)
        }
        val seek = SeekBar(this).apply {
            this.max = maxValue
            progress = safeValue
            progressTintList = ColorStateList.valueOf(color(CYAN))
            thumbTintList = ColorStateList.valueOf(color(TEXT))
            progressBackgroundTintList = ColorStateList.valueOf(color("#16263C"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueLabel.text = valueText(progress)
                    onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        row.addView(top)
        row.addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return row
    }

    private fun chipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<TextView>()
        var sel = selected
        fun paint() {
            views.forEachIndexed { i, tv ->
                val isSel = i == sel
                tv.background = if (isSel) bgRound(CYAN, 10) else bgRound("#0D1B2D", 10, CARD_LINE)
                tv.setTextColor(color(if (isSel) INK else TEXT))
            }
        }
        labels.forEachIndexed { i, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                isClickable = true
                setOnClickListener {
                    sel = i
                    paint()
                    onSelect(i)
                }
            }
            views.add(tv)
            row.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        paint()
        return row
    }

    private fun colorRow(key: String, def: Int, onChange: () -> Unit): LinearLayout {
        val palette = listOf(
            Color.WHITE,
            Color.parseColor("#FDE047"),
            Color.parseColor("#22D3EE"),
            Color.parseColor("#86EFAC"),
            Color.parseColor("#FDBA74"),
            Color.parseColor("#F472B6")
        )
        var current = prefs.getInt(key, def)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dots = mutableListOf<View>()
        fun paint() {
            dots.forEachIndexed { i, dot ->
                val c = palette[i]
                dot.background = bgRound(hex(c), 14, if (c == current) CYAN else "#2A3E56", 2)
            }
        }
        palette.forEach { c ->
            val dot = View(this).apply {
                isClickable = true
                setOnClickListener {
                    current = c
                    prefs.edit().putInt(key, c).apply()
                    paint()
                    onChange()
                }
            }
            dots.add(dot)
            row.addView(dot, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        }
        paint()
        return row
    }

    private fun toggleRow(label: String, key: String, def: Boolean): LinearLayout {
        var on = prefs.getBoolean(key, def)
        val pill = View(this)
        val knob = View(this)
        val track = FrameLayout(this).apply {
            addView(pill, FrameLayout.LayoutParams(dp(46), dp(26)))
            addView(knob, FrameLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(3) })
        }
        fun paint() {
            pill.background = bgRound(if (on) CYAN else "#16263C", 13)
            knob.background = bgRound(if (on) INK else "#7F97B3", 10)
            val lp = knob.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = if (on) dp(23) else dp(3)
            knob.layoutParams = lp
        }
        paint()
        track.setOnClickListener {
            on = !on
            prefs.edit().putBoolean(key, on).apply()
            paint()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12.5f
                setTextColor(color(TEXT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(track)
        }
    }

    private fun ghostButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12.5f
        setTextColor(color(TEXT))
        background = bgRound("#0D1B2D", 12, CARD_LINE)
        setPadding(dp(6), dp(10), dp(6), dp(10))
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun rowOf(vararg views: View): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v ->
            row.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        return row
    }

    private fun space(h: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h))
    }

    private fun bgRound(bg: String, radiusDp: Int, stroke: String? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(bg))
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(strokeDp), color(stroke))
        }

    private fun color(v: String): Int = Color.parseColor(v)

    private fun hex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- permissions ----------

    private fun notificationGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun overlayGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun startWithPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationGranted()) {
            requestNotificationPermission()
            toast("Önce bildirim iznini ver, sonra tekrar Başlat'a dokun.")
            return
        }
        if (!overlayGranted()) {
            requestOverlayPermission()
            toast("Önce 'Üstte gösterme' iznini aç, sonra tekrar Başlat'a dokun.")
            return
        }
        requestCapture()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        } else toast("Bildirim izni zaten tamam")
        renderTab()
    }

    private fun requestOverlayPermission() {
        if (!overlayGranted()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else toast("Üstte gösterme izni zaten açık")
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    // ---------- model ----------

    private fun refreshModelState() {
        val view = modelView ?: return
        view.text = "Dil paketi durumu kontrol ediliyor…"
        modelManager.isModelDownloaded(enTrModel)
            .addOnSuccessListener { downloaded ->
                view.text = if (downloaded) "EN→TR dil paketi hazır. Çeviri bu paketle yapılır."
                else "EN→TR dil paketi yüklü değil. \"Paketi indir\" ile indirebilirsin (internet gerekir)."
            }
            .addOnFailureListener { e ->
                view.text = "Dil paketi durumu alınamadı: ${e.localizedMessage ?: "hata"}"
            }
    }

    private fun downloadModel() {
        modelView?.text = "EN→TR paketi indiriliyor…"
        LogStore.append(this, "DURUM", "EN→TR dil paketi indirme başlatıldı")
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val client = Translation.getClient(options)
        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelView?.text = "EN→TR dil paketi indirildi ve hazır."
                LogStore.append(this, "DURUM", "EN→TR dil paketi indirildi")
                toast("Dil paketi hazır")
                client.close()
                refreshModelState()
            }
            .addOnFailureListener { e ->
                val msg = e.localizedMessage ?: "bilinmeyen hata"
                modelView?.text = "Paket indirilemedi: $msg. İnterneti kontrol edip tekrar dene."
                LogStore.append(this, "HATA", "EN→TR dil paketi indirilemedi: $msg")
                toast("İndirme başarısız")
                client.close()
            }
    }

    private fun deleteModel() {
        modelManager.deleteDownloadedModel(enTrModel)
            .addOnSuccessListener {
                LogStore.append(this, "DURUM", "EN→TR dil paketi silindi")
                toast("Dil paketi silindi")
                refreshModelState()
            }
            .addOnFailureListener { e ->
                val msg = e.localizedMessage ?: "hata"
                LogStore.append(this, "HATA", "EN→TR dil paketi silinemedi: $msg")
                toast("Silinemedi: $msg")
                refreshModelState()
            }
    }

    // ---------- voice ----------

    private fun showVoiceDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        val loading = TextView(this).apply {
            text = "Türkçe sesler yükleniyor…"
            textSize = 12.5f
            setTextColor(color(MUTED))
            setPadding(0, dp(10), 0, dp(10))
        }
        view.addView(loading)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Ses seçimi")
            .setView(ScrollView(this).apply { addView(view) })
            .setNegativeButton("Kapat", null)
            .create()
        dialog.show()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(applicationContext) { status ->
            view.removeAllViews()
            if (status != TextToSpeech.SUCCESS) {
                view.addView(TextView(this).apply {
                    text = "Ses motoru başlatılamadı."
                    textSize = 12.5f
                    setTextColor(color(MUTED))
                    setPadding(0, dp(10), 0, dp(10))
                })
                return@TextToSpeech
            }
            view.addView(voiceOption("Varsayılan ses", { prefs.edit().putString("tts_voice", "").apply() }, dialog))
            val voices: List<Voice> = try {
                engine?.voices?.filter { it.locale?.language?.lowercase(Locale.ROOT) == "tr" }?.sortedBy { it.name } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (voices.isEmpty()) {
                view.addView(TextView(this).apply {
                    text = "Türkçe ses bulunamadı. Cihaz ayarlarından Türkçe konuşma verisini indirebilirsin."
                    textSize = 12f
                    setTextColor(color(MUTED))
                    setPadding(0, dp(10), 0, dp(10))
                })
            } else {
                for (v in voices.take(20)) {
                    view.addView(voiceOption(v.name, { prefs.edit().putString("tts_voice", v.name).apply() }, dialog))
                }
            }
        }
    }

    private fun voiceOption(label: String, onPick: () -> Unit, dialog: AlertDialog): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(color(TEXT))
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        setOnClickListener {
            onPick()
            toast("Ses: $label")
            dialog.dismiss()
        }
    }

    // ---------- logs ----------

    private fun showLogs() {
        val tv = TextView(this).apply {
            setTextColor(color("#C5D6E8"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        fun render(filter: String) {
            val all = LogStore.read(this).lineSequence().filter { it.isNotBlank() }
            val picked = when (filter) {
                "OKUNAN" -> all.filter { it.contains("[OKU]") || it.contains("[CEVIRI]") }
                "HATA" -> all.filter { it.contains("[HATA]") }
                else -> all
            }.toList().takeLast(300)
            tv.text = if (picked.isEmpty()) "Bu bölümde kayıt yok." else picked.joinToString("\n")
            tv.scrollTo(0, 0)
        }
        render("TUMU")
        val sc = ScrollView(this).apply {
            setBackgroundColor(color(BG))
            addView(tv)
        }
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(ghostButton("Tümü") { render("TUMU") }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(ghostButton("Okunan") { render("OKUNAN") }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(ghostButton("Hatalar") { render("HATA") }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(filters)
            addView(sc, LinearLayout.LayoutParams(MATCH_PARENT, dp(430)))
        }
        AlertDialog.Builder(this)
            .setTitle("Günlük")
            .setView(root)
            .setPositiveButton("Kapat", null)
            .setNegativeButton("Temizle") { _, _ ->
                LogStore.clear(this)
                render("TUMU")
                toast("Günlük temizlendi")
            }
            .show()
    }

    private fun shareLogs() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NEXORA çalışma kaydı")
            putExtra(Intent.EXTRA_TEXT, LogStore.read(this@MainActivity).takeLast(100000))
        }
        startActivity(Intent.createChooser(send, "Kayıtları paylaş"))
    }

    override fun onDestroy() {
        hideRegionPicker()
        super.onDestroy()
    }

    // ---------- defaults ----------

    private fun resetDefaults() {
        prefs.edit()
            .putFloat("text_size_sp", 22f)
            .putInt("bg_opacity", 88)
            .putInt("stroke_width_dp", 1)
            .putInt("stroke_color", Color.BLACK)
            .putInt("text_color", Color.WHITE)
            .putInt("max_lines", 2)
            .putString("position", "bottom")
            .putString("region", "bottom")
            .putInt("offset_dp", 56)
            .putInt("ocr_interval_ms", 250)
            .putInt("clear_ms", 2800)
            .putInt("max_chars", 250)
            .putString("translation_mode", "device")
            .putBoolean("hud_filter", true)
            .putBoolean("auto_region", true)
            .putBoolean("context_memory", true)
            .putBoolean("stabilize", true)
            .putString("glossary_keep", "")
            .putBoolean("merge_repeat", true)
            .putBoolean("ocr_hires", true)
            .putBoolean("debug_log", false)
            .apply()
        toast("Varsayılanlara dönüldü")
        renderTab()
    }
}
