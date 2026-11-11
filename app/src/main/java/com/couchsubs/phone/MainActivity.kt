package com.couchsubs.phone

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 101
        const val REQ_NOTIF = 102
        const val BG = "#050B1A"
        const val CARD = "#0E1A2D"
        const val CARD_LINE = "#1A3047"
        const val SURFACE = "#071325"
        const val CYAN = "#22D3EE"
        const val GREEN = "#34D399"
        const val AMBER = "#FBBF24"
        const val RED = "#FB7185"
        const val TEXT = "#EAF4FF"
        const val MUTED = "#7F98B2"
        const val INK = "#04131E"
    }

    private val prefs by lazy { getSharedPreferences("nexora_overlay", Context.MODE_PRIVATE) }
    private lateinit var mainRoot: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var homeTab: LinearLayout
    private lateinit var settingsTab: LinearLayout
    private lateinit var historyTab: LinearLayout
    private lateinit var centerWrap: FrameLayout
    private lateinit var activeDot: View
    private var currentTab = 0

    private val projectionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            LogStore.append(this, "DURUM", "Ekran yakalama izni verildi, servis başlatılıyor")
            val i = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            } catch (e: Exception) {
                val msg = "Servis başlatılamadı: ${e.localizedMessage ?: "bilinmeyen hata"}"
                LogStore.append(this, "HATA", msg)
                toast(msg)
            }
        } else {
            LogStore.append(this, "HATA", "Ekran yakalama izni reddedildi/iptal edildi")
            toast("Ekran izni gerekli")
        }
        updateStatusUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(BG)
        window.navigationBarColor = color(BG)
        try {
            val c = window.decorView.systemUiVisibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                window.decorView.systemUiVisibility = c and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        } catch (_: Exception) {}
        setContentView(buildUi())
        requestMissingPermissionsOnLaunch()
        renderTab()
        if (prefs.getBoolean("auto_start", false)) maybeStart()
        LogStore.append(this, "DURUM", "Uygulama açıldı")
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
    }

    private fun requestMissingPermissionsOnLaunch() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
    }

    private fun color(hex: String): Int = Color.parseColor(hex)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bgRound(fill: String, radiusDp: Int, stroke: String? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(fill))
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(strokeDp), color(stroke))
        }

    private fun maybeStart() {
        if (!overlayGranted()) {
            requestOverlayPermission()
            return
        }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun stopServiceAction() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        toast("Çeviri durduruldu")
        updateStatusUi()
    }

    private fun overlayGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun notificationGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            toast("Üstte gösterme iznini aç")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else toast("Bildirim izni bu sürümde otomatik")
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        mainRoot = FrameLayout(this).apply { setBackgroundColor(color(BG)) }

        contentHost = FrameLayout(this).apply { id = View.generateViewId() }
        mainRoot.addView(contentHost, LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            bottomMargin = dp(96)
        })

        bottomBar = buildBottomBar()
        mainRoot.addView(bottomBar, LayoutParams(MATCH_PARENT, dp(88), Gravity.BOTTOM))

        return mainRoot
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = FrameLayout(this).apply {
            background = bgRound("#0A1628", 0, "#12243A")
            elevation = dp(12).toFloat()
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }

        homeTab = tabItem(R.drawable.ic_nav_home, "Ana Sayfa") { switchTab(0) }
        settingsTab = tabItem(R.drawable.ic_nav_settings, "Ayarlar") { switchTab(1) }
        historyTab = tabItem(R.drawable.ic_nav_history, "Geçmiş") { switchTab(2) }

        centerWrap = FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(2))
        }
        val centerBtn = FrameLayout(this).apply {
            val sz = dp(58)
            layoutParams = LayoutParams(sz, sz, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(CYAN))
            }
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener {
                if (ScreenCaptureService.running) stopServiceAction() else maybeStart()
                updateStatusUi()
            }
            addView(ImageView(this@MainActivity).apply {
                id = 0x4E01
                setImageResource(R.drawable.ic_play)
                setColorFilter(color(INK))
            }, LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        activeDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(RED))
            }
            visibility = View.GONE
        }
        centerWrap.addView(centerBtn)
        centerWrap.addView(activeDot, LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(2); rightMargin = dp(2)
        })

        row.addView(homeTab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(settingsTab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(centerWrap, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(historyTab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) // balance

        // re-center: put center in middle by weight layout with 5 slots
        row.removeAllViews()
        val l1 = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val l2 = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val l3 = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val l4 = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        val l5 = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        row.addView(homeTab, l1)
        row.addView(settingsTab, l2)
        val centerHolder = LinearLayout(this).apply { gravity = Gravity.CENTER }
        centerHolder.addView(centerWrap)
        row.addView(centerHolder, l3)
        row.addView(historyTab, l4)
        val profileTab = tabItem(R.drawable.ic_nav_settings, "Sistem") { switchTab(1) }
        row.addView(profileTab, l5)

        bar.addView(row, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return LinearLayout(this).apply { addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(88))) }
    }

    private fun tabItem(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(6), dp(4), dp(6))
        isClickable = true
        setOnClickListener { onClick() }
        val iv = ImageView(this@MainActivity).apply {
            tag = "icon"
            setImageResource(iconRes)
            setColorFilter(color(MUTED))
        }
        val tv = TextView(this@MainActivity).apply {
            tag = "label"
            text = label
            textSize = 10f
            setTextColor(color(MUTED))
            gravity = Gravity.CENTER
        }
        addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    private fun styleTab(tab: LinearLayout, active: Boolean) {
        val c = if (active) color(CYAN) else color(MUTED)
        (tab.findViewWithTag<View>("icon") as? ImageView)?.setColorFilter(c)
        (tab.findViewWithTag<View>("label") as? TextView)?.setTextColor(c)
    }

    private fun switchTab(idx: Int) {
        currentTab = idx
        renderTab()
    }

    private fun renderTab() {
        contentHost.removeAllViews()
        val view = when (currentTab) {
            1 -> settingsScreen()
            2 -> historyScreen()
            else -> homeScreen()
        }
        contentHost.addView(view, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        styleTab(homeTab, currentTab == 0)
        styleTab(settingsTab, currentTab == 1)
        styleTab(historyTab, currentTab == 2)
        updateStatusUi()
    }

    private fun updateStatusUi() {
        val running = ScreenCaptureService.running
        val icon = centerWrap.findViewById<ImageView?>(0x4E01)
            ?: (0 until centerWrap.childCount).mapNotNull { (centerWrap.getChildAt(it) as? FrameLayout)?.getChildAt(0) as? ImageView }.firstOrNull()
        icon?.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        activeDot.visibility = if (running) View.VISIBLE else View.GONE
        if (::contentHost.isInitialized) {
            val statusTitle = contentHost.findViewWithTag<TextView>("status_title")
            val statusSub = contentHost.findViewWithTag<TextView>("status_sub")
            val statusPill = contentHost.findViewWithTag<TextView>("status_pill")
            if (running) {
                statusTitle?.text = "Çeviri Aktif"
                statusSub?.text = "Oyun altyazıları algılanıyor ve Türkçeye çevriliyor"
                statusPill?.text = "AKTİF"
                statusPill?.setTextColor(color(INK))
                statusPill?.background = bgRound(GREEN, 10)
            } else {
                statusTitle?.text = "Hazır"
                statusSub?.text = "Altyazı çevirisini başlatmak için ortadaki butona dokun"
                statusPill?.text = "DURDU"
                statusPill?.setTextColor(color(TEXT))
                statusPill?.background = bgRound("#16273D", 10, CARD_LINE)
            }
        }
    }

    // ---------- home ----------

    private fun homeScreen(): View {
        val col = page()
        col.addView(titleBlock("NEXORA", "Oyun altyazılarını anında Türkçeye çevir.", R.drawable.ic_translate))
        col.addView(statusCard())
        col.addView(quickSetupCard())
        col.addView(tipCard())
        return scroll(col)
    }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(24))
    }

    private fun scroll(child: View): ScrollView = ScrollView(this).apply {
        setBackgroundColor(color(BG))
        isFillViewport = true
        addView(child)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bgRound(CARD, 18, CARD_LINE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) }
    }

    private fun statusCard(): LinearLayout {
        val c = card()
        val running = ScreenCaptureService.running
        val pill = TextView(this).apply {
            tag = "status_pill"
            text = if (running) "AKTİF" else "DURDU"
            textSize = 10.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(if (running) color(INK) else color(TEXT))
            background = bgRound(if (running) GREEN else "#16273D", 10, if (running) null else CARD_LINE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val title = TextView(this).apply {
            tag = "status_title"
            text = if (running) "Çeviri Aktif" else "Hazır"
            textSize = 18f
            setTextColor(color(TEXT))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            tag = "status_sub"
            text = if (running) "Oyun altyazıları algılanıyor ve Türkçeye çevriliyor"
            else "Altyazı çevirisini başlatmak için ortadaki butona dokun"
            textSize = 12.5f
            setTextColor(color(MUTED))
            setPadding(0, dp(6), 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill)
        }
        c.addView(row)
        c.addView(sub)
        return c
    }

    private fun quickSetupCard(): LinearLayout {
        val c = card()
        c.addView(sectionTitle("Hızlı Kurulum"))
        c.addView(stepRow("1", "Üstte gösterme iznini aç", overlayGranted()))
        c.addView(stepRow("2", "Bildirim iznini aç", notificationGranted()))
        c.addView(stepRow("3", "Oyunu aç ve altyazıları göster", null))
        c.addView(stepRow("4", "Ortadaki butona dokun", ScreenCaptureService.running))
        if (!overlayGranted() || !notificationGranted()) {
            c.addView(space(8))
            c.addView(rowOf(
                ghostButton("İzinleri ver") {
                    if (!overlayGranted()) requestOverlayPermission() else requestNotificationPermission()
                }
            ))
        }
        return c
    }

    private fun stepRow(n: String, text: String, ok: Boolean?): LinearLayout {
        val dot = TextView(this).apply {
            this.text = if (ok == true) "✓" else n
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(if (ok == true) color(INK) else color(TEXT))
            background = bgRound(if (ok == true) GREEN else "#16273D", 12, if (ok == true) null else CARD_LINE)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = 13f
                setTextColor(if (ok == false) color(AMBER) else color(TEXT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun tipCard(): LinearLayout {
        val c = card()
        c.addView(sectionTitle("İpucu"))
        c.addView(TextView(this).apply {
            text = "Çeviri görünmüyorsa Ayarlar'daki \"Alanı Ayarla\" ile kutuyu oyunun altyazı satırının tam üstüne yerleştir. Günlük'teki \"Okunan\" bölümü uygulamanın ne gördüğünü gösterir."
            textSize = 12.5f
            setTextColor(color(MUTED))
            setLineSpacing(4f, 1.05f)
        })
        return c
    }

    // ---------- settings ----------

    private fun settingsScreen(): View {
        val col = page()
        col.addView(titleBlock("Ayarlar", "Uygulamayı kendi oyun deneyimine göre özelleştir.", R.drawable.ic_nav_settings))
        col.addView(resetChip())
        col.addView(areaCard())
        col.addView(modeCard())
        col.addView(contextCard())
        col.addView(appearanceCard())
        col.addView(performanceCard())
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
        private var startRect = RectF()

        init { setWillNotDraw(false) }

        fun initSelection(fr: FloatArray) { pending = fr; invalidate() }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val f = pending
            if (f != null) {
                sel.set(f[0] * w, f[1] * h, f[2] * w, f[3] * h)
            } else if (sel.isEmpty) {
                sel.set(w * 0.06f, h * 0.62f, w * 0.94f, h * 0.94f)
            }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawRect(0f, 0f, w, sel.top, dim)
            c.drawRect(0f, sel.bottom, w, h, dim)
            c.drawRect(0f, sel.top, sel.left, sel.bottom, dim)
            c.drawRect(sel.right, sel.top, w, sel.bottom, dim)
            c.drawRect(sel, border)
            val y3 = sel.top + sel.height() / 3f
            val y6 = sel.top + 2f * sel.height() / 3f
            c.drawLine(sel.left, y3, sel.right, y3, grid)
            c.drawLine(sel.left, y6, sel.right, y6, grid)
            for ((kx, ky) in listOf(sel.left to sel.top, sel.right to sel.top, sel.left to sel.bottom, sel.right to sel.bottom)) {
                c.drawCircle(kx, ky, dp(8).toFloat(), knob)
            }
            c.drawText("Altyazı alanı", sel.left + dp(8), (sel.top - dp(8)).coerceAtLeast(dp(18).toFloat()), tag)
        }

        private fun hitCorner(x: Float, y: Float): Int {
            val r = dp(22).toFloat()
            if (Math.hypot((x - sel.left).toDouble(), (y - sel.top).toDouble()) < r) return 1
            if (Math.hypot((x - sel.right).toDouble(), (y - sel.top).toDouble()) < r) return 2
            if (Math.hypot((x - sel.left).toDouble(), (y - sel.bottom).toDouble()) < r) return 3
            if (Math.hypot((x - sel.right).toDouble(), (y - sel.bottom).toDouble()) < r) return 4
            return 0
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startRect.set(sel)
                    val c = hitCorner(e.rawX, e.rawY)
                    mode = when {
                        c != 0 -> c
                        sel.contains(e.rawX, e.rawY) -> 5
                        else -> 6
                    }
                    if (mode == 6) {
                        sel.set(e.rawX, e.rawY, e.rawX, e.rawY)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val minW = dp(80).toFloat()
                    val minH = dp(40).toFloat()
                    when (mode) {
                        1 -> { sel.left = (startRect.left + dx).coerceIn(0f, sel.right - minW); sel.top = (startRect.top + dy).coerceIn(0f, sel.bottom - minH) }
                        2 -> { sel.right = (startRect.right + dx).coerceIn(sel.left + minW, width.toFloat()); sel.top = (startRect.top + dy).coerceIn(0f, sel.bottom - minH) }
                        3 -> { sel.left = (startRect.left + dx).coerceIn(0f, sel.right - minW); sel.bottom = (startRect.bottom + dy).coerceIn(sel.top + minH, height.toFloat()) }
                        4 -> { sel.right = (startRect.right + dx).coerceIn(sel.left + minW, width.toFloat()); sel.bottom = (startRect.bottom + dy).coerceIn(sel.top + minH, height.toFloat()) }
                        5 -> {
                            val w = startRect.width(); val h = startRect.height()
                            sel.left = (startRect.left + dx).coerceIn(0f, width - w)
                            sel.top = (startRect.top + dy).coerceIn(0f, height - h)
                            sel.right = sel.left + w; sel.bottom = sel.top + h
                        }
                        6 -> { sel.right = e.rawX; sel.bottom = e.rawY }
                    }
                    if (mode == 6) {
                        // keep rect normalized while drawing
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == 6) {
                        sel.set(min(downX, e.rawX), min(downY, e.rawY), max(downX, e.rawX), max(downY, e.rawY))
                    }
                    sel.sort()
                    sel.left = sel.left.coerceIn(0f, width - dp(40).toFloat())
                    sel.top = sel.top.coerceIn(0f, height - dp(30).toFloat())
                    sel.right = sel.right.coerceIn(sel.left + dp(80).toFloat(), width.toFloat())
                    sel.bottom = sel.bottom.coerceIn(sel.top + dp(40).toFloat(), height.toFloat())
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }

    private fun areaStatusText(): String {
        val p = prefs.getString("position", "bottom") ?: "bottom"
        return when (p) {
            "custom", "ozel" -> "Seçili: Özel alan"
            "top" -> "Seçili: Üst"
            "middle", "center", "orta" -> "Seçili: Orta"
            "full" -> "Seçili: Tam ekran"
            else -> "Seçili: Alt"
        }
    }

    private fun showRegionPicker() {
        if (!overlayGranted()) { requestOverlayPermission(); return }
        hideRegionPicker()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        pickerWm = wm
        val root = FrameLayout(this)
        val picker = RegionPickerView(this)
        val p = prefs.getString("position", "bottom") ?: "bottom"
        val fr = if (p == "custom" || p == "ozel") {
            floatArrayOf(prefs.getFloat("region_l", 0.06f), prefs.getFloat("region_t", 0.62f), prefs.getFloat("region_r", 0.94f), prefs.getFloat("region_b", 0.94f))
        } else {
            when (p) {
                "top" -> floatArrayOf(0.05f, 0.03f, 0.95f, 0.28f)
                "middle", "center", "orta" -> floatArrayOf(0.05f, 0.32f, 0.95f, 0.68f)
                "full" -> floatArrayOf(0.02f, 0.02f, 0.98f, 0.98f)
                else -> floatArrayOf(0.06f, 0.62f, 0.94f, 0.94f)
            }
        }
        picker.initSelection(fr)
        root.addView(picker, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = bgRound("#D90A1628", 16, CARD_LINE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        bar.addView(ghostButton("Kaydet") {
            val w = max(1, picker.width).toFloat()
            val h = max(1, picker.height).toFloat()
            val l = (picker.sel.left / w).coerceIn(0.0f, 0.96f)
            val t = (picker.sel.top / h).coerceIn(0.0f, 0.96f)
            val r = (picker.sel.right / w).coerceIn(l + 0.04f, 1.0f)
            val b = (picker.sel.bottom / h).coerceIn(t + 0.03f, 1.0f)
            prefs.edit()
                .putString("position", "custom")
                .putString("region", "custom")
                .putFloat("region_l", l).putFloat("region_t", t)
                .putFloat("region_r", r).putFloat("region_b", b)
                .apply()
            LogStore.append(this@MainActivity, "AYAR", "Özel altyazı alanı: x " +
                "${(l*100).toInt()}-${(r*100).toInt()}%, y ${(t*100).toInt()}-${(b*100).toInt()}%")
            hideRegionPicker()
            toast("Altyazı alanı kaydedildi")
            renderTab()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        bar.addView(ghostButton("İptal") { hideRegionPicker() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val hint = TextView(this).apply {
            text = "Kutuyu sürükle, köşelerden boyutlandır. Altyazının olduğu yeri seç."
            textSize = 11f
            setTextColor(color(TEXT))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = bgRound("#CC0A1628", 12, CARD_LINE)
        }
        root.addView(hint, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36) })
        root.addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(28); leftMargin = dp(18); rightMargin = dp(18) })
        pickerRoot = root
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(root, lp) } catch (e: Exception) {
            pickerRoot = null
            toast("Alan seçici açılamadı")
        }
    }

    private fun hideRegionPicker() {
        try { pickerRoot?.let { pickerWm?.removeView(it) } } catch (_: Exception) {}
        pickerRoot = null
        pickerWm = null
    }

    private fun areaCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_crop, "Altyazı Alanı", "Oyundaki altyazı konumunu seç."))
        val frameBox = FrameLayout(this).apply {
            background = bgRound("#071325", 12, CARD_LINE)
        }
        frameBox.addView(View(this).apply {
            background = bgRound("#00000000", 10, CYAN)
        }, LayoutParams(MATCH_PARENT, dp(56), Gravity.CENTER).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
        })
        frameBox.addView(TextView(this).apply {
            text = "Bu alandaki metinler okunur"
            textSize = 10.5f
            setTextColor(color(TEXT))
            background = bgRound("#04121C", 8, "#1E5C71")
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
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
        val opts = listOf("Cihazda" to "device", "İnternet" to "online")
        for ((label, value) in opts) {
            val active = current == value
            row.addView(TextView(this).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) color(INK) else color(TEXT))
                background = bgRound(if (active) CYAN else "#16273D", 12, if (active) null else CARD_LINE)
                setPadding(dp(10), dp(12), dp(10), dp(12))
                isClickable = true
                setOnClickListener {
                    prefs.edit().putString("translation_mode", value).apply()
                    if (value == "device") ensureModel(false)
                    toast(if (value == "online") "İnternet çevirisi" else "Cihaz içi çeviri")
                    renderTab()
                }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        }
        cardView.addView(row)
        cardView.addView(TextView(this).apply {
            text = "Cihazda: internet gerekmez, model indirilmeli. İnternet: daha güncel çeviri, bağlantı gerekir."
            textSize = 11f
            setTextColor(color(MUTED))
            setPadding(0, dp(8), 0, 0)
        })
        return cardView
    }

    private fun contextCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_translate, "Bağlam ve Sözlük", "Konuşmayı hatırla ve adları/terimleri doğru tut."))
        cardView.addView(toggleRow("Önceki cümleleri hatırla (daha tutarlı çeviri)", "context_memory", true))
        cardView.addView(toggleRow("Yeni satırı kısa süre sabitle (titremeyi azalt)", "stabilize", true))
        cardView.addView(space(8))
        cardView.addView(TextView(this).apply {
            text = "Korunacak adlar ve terimler (her satıra bir tane). Çeviride olduğu gibi bırakılır, örn. karakter adları."
            textSize = 11f
            setTextColor(color(MUTED))
            setLineSpacing(2f, 1.05f)
        })
        val keep = EditText(this).apply {
            setText(prefs.getString("glossary_keep", "") ?: "")
            hint = "Medusa\nBoobie\nLos Santos"
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            textSize = 13f
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            background = bgRound("#071325", 10, CARD_LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        cardView.addView(keep, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) })
        cardView.addView(rowOf(
            ghostButton("Sözlüğü kaydet") {
                prefs.edit().putString("glossary_keep", keep.text?.toString() ?: "").apply()
                toast("Sözlük kaydedildi")
            },
            ghostButton("Temizle") {
                keep.setText("")
                prefs.edit().putString("glossary_keep", "").apply()
                toast("Sözlük temizlendi")
            }
        ))
        return cardView
    }

    private fun appearanceCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_subtitle, "Görünüm", "Altyazı kutusunun görünümünü ayarla."))

        val preview = TextView(this).apply {
            text = "Altyazı örneği burada görünür"
            gravity = Gravity.CENTER
        }
        fun refreshPreview() {
            val size = prefs.getFloat("text_size_sp", 22f).coerceIn(14f, 40f)
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
        previewBox.addView(preview, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

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
            "Tarama Aralığı",
            1400,
            (prefs.getInt("capture_interval_ms", 350) - 100).coerceIn(0, 1400),
            { "${100 + it} ms" }
        ) { prefs.edit().putInt("capture_interval_ms", 100 + it).apply() })
        cardView.addView(sliderRow(
            "Okuma Kalitesi",
            50,
            (prefs.getInt("capture_scale", 100) - 50).coerceIn(0, 50),
            { "${50 + it}%" }
        ) { prefs.edit().putInt("capture_scale", 50 + it).apply() })
        cardView.addView(sliderRow(
            "Ekranda Kalma",
            5200,
            (prefs.getInt("clear_ms", 2800) - 800).coerceIn(0, 5200),
            { "${(800 + it) / 1000.0}".take(3) + " sn" }
        ) { prefs.edit().putInt("clear_ms", 800 + it).apply() })
        return cardView
    }

    private fun advancedCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_tune, "Gelişmiş", "Ek ayarlar ve ince ayarlar."))
        cardView.addView(toggleRow("HUD/metin gürültüsünü filtrele", "hud_filter", true))
        cardView.addView(toggleRow("Aynı satırı tekrar çevirme", "merge_repeat", true))
        cardView.addView(toggleRow("Yüksek çözünürlükle oku", "ocr_hires", true))
        cardView.addView(toggleRow("Ayrıntılı günlük tut", "debug_log", false))
        return cardView
    }

    private fun modelCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_download, "Çeviri Paketi", "Cihaz içi çeviri için dil paketini yönet."))
        cardView.addView(TextView(this).apply {
            text = "İngilizce → Türkçe çeviri paketi cihazda çalışır; internet gerekmez. İlk kullanımda indirilmesi gerekir."
            textSize = 11.5f
            setTextColor(color(MUTED))
            setLineSpacing(3f, 1.05f)
            setPadding(0, 0, 0, dp(10))
        })
        cardView.addView(rowOf(
            ghostButton("Paketi indir") { downloadModel() },
            ghostButton("Paketi sil") { deleteModel() }
        ))
        return cardView
    }

    private fun voiceCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_sound, "Sesli Okuma", "Çeviriyi sesli dinle (isteğe bağlı)."))
        cardView.addView(toggleRow("Çeviriyi sesli oku", "tts_enabled", false))
        val rate = prefs.getFloat("tts_rate", 1.0f)
        val rateIndex = when {
            rate < 0.93f -> 0
            rate > 1.07f -> 2
            else -> 1
        }
        cardView.addView(TextView(this).apply {
            text = "Hız"
            textSize = 11.5f
            setTextColor(color(MUTED))
            setPadding(0, dp(8), 0, dp(6))
        })
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
            textSize = 11f
            setTextColor(color(MUTED))
            setLineSpacing(2f, 1.05f)
        })
        addView(TextView(this@MainActivity).apply {
            text = tr.ifBlank { "…" }
            textSize = 13.5f
            setTextColor(color(TEXT))
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(2f, 1.05f)
        })
    }

    // ---------- components ----------

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(color(CYAN))
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.02f
        setPadding(0, 0, 0, dp(10))
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
                textSize = 14.5f
                setTextColor(color(TEXT))
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub
                textSize = 11f
                setTextColor(color(MUTED))
                setPadding(0, dp(3), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
            addView(col)
        }
    }

    private fun space(h: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }

    private fun rowOf(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((i, v) in views.withIndex()) {
            addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i < views.lastIndex) marginEnd = dp(8)
            })
        }
    }

    private fun ghostButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(color(TEXT))
        background = bgRound("#16273D", 11, CARD_LINE)
        setPadding(dp(8), dp(11), dp(8), dp(11))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun sliderRow(title: String, max: Int, progress: Int, label: (Int) -> String, onChange: (Int) -> Unit): LinearLayout {
        val valView = TextView(this).apply {
            text = label(progress)
            textSize = 10.5f
            setTextColor(color(CYAN))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.END
        }
        val seek = SeekBar(this).apply {
            this.max = max
            this.progress = progress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { valView.text = label(p); if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 11.5f; setTextColor(color(MUTED))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(valView)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(head)
            addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        }
    }

    private fun chipRow(options: List<String>, selectedIndex: Int, onPick: (Int) -> Unit): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rebuild(sel: Int) {
            wrap.removeAllViews()
            for ((i, opt) in options.withIndex()) {
                val active = i == sel
                wrap.addView(TextView(this).apply {
                    text = opt
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(if (active) color(INK) else color(TEXT))
                    background = bgRound(if (active) CYAN else "#16273D", 10, if (active) null else CARD_LINE)
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    isClickable = true
                    setOnClickListener { rebuild(i); onPick(i) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i < options.lastIndex) marginEnd = dp(6) })
            }
        }
        rebuild(selectedIndex)
        return wrap
    }

    private fun colorRow(prefKey: String, def: Int, onChange: () -> Unit): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val palette = listOf(Color.WHITE, Color.parseColor("#C5D6E8"), Color.parseColor("#FFD166"), Color.parseColor("#6EE7B7"), Color.parseColor("#22D3EE"), Color.parseColor("#FB7185"), Color.BLACK)
        fun rebuild() {
            wrap.removeAllViews()
            val selected = prefs.getInt(prefKey, def)
            for (c in palette) {
                val active = c == selected
                val sw = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke(dp(if (active) 3 else 1), color(if (active) CYAN else "#334155"))
                    }
                    isClickable = true
                    setOnClickListener { prefs.edit().putInt(prefKey, c).apply(); rebuild(); onChange() }
                }
                wrap.addView(sw, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
            }
        }
        rebuild()
        return wrap
    }

    private fun toggleRow(title: String, key: String, def: Boolean): LinearLayout {
        val value = prefs.getBoolean(key, def)
        val pill = TextView(this).apply {
            text = if (value) "Açık" else "Kapalı"
            textSize = 10f
            setTextColor(if (value) color(INK) else color(TEXT))
            background = bgRound(if (value) GREEN else "#16273D", 9, if (value) null else CARD_LINE)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener {
                val nv = !prefs.getBoolean(key, def)
                prefs.edit().putBoolean(key, nv).apply()
                renderTab()
            }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12.5f
                setTextColor(color(TEXT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill)
        }
    }

    private fun emptyNote(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(color(MUTED))
        background = bgRound("#071325", 14, CARD_LINE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setLineSpacing(4f, 1.05f)
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ---------- model + voice ----------

    private fun downloadModel() {
        val opts = TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.TURKISH).build()
        val tr = Translation.getClient(opts)
        toast("Paket indiriliyor…")
        LogStore.append(this, "DURUM", "EN→TR dil paketi indirme başlatıldı")
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                try { tr.close() } catch (_: Exception) {}
                toast("Paket hazır")
                LogStore.append(this, "DURUM", "EN→TR dil paketi indirildi")
            }
            .addOnFailureListener { e ->
                try { tr.close() } catch (_: Exception) {}
                val msg = e.localizedMessage ?: "hata"
                toast("Paket indirilemedi")
                LogStore.append(this, "HATA", "EN→TR dil paketi indirilemedi: $msg")
            }
    }

    private fun deleteModel() {
        val opts = TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.TURKISH).build()
        val tr = Translation.getClient(opts)
        tr.deleteDownloadedModel()
            .addOnSuccessListener {
                try { tr.close() } catch (_: Exception) {}
                toast("Paket silindi")
                LogStore.append(this, "DURUM", "EN→TR dil paketi silindi")
            }
            .addOnFailureListener { e ->
                try { tr.close() } catch (_: Exception) {}
                val msg = e.localizedMessage ?: "hata"
                toast("Paket silinemedi")
                LogStore.append(this, "HATA", "EN→TR dil paketi silinemedi: $msg")
            }
    }

    private fun showVoiceDialog() {
        var localTts: TextToSpeech? = null
        localTts = TextToSpeech(this) { status ->
            val dialog = AlertDialog.Builder(this).setTitle("Ses seç").setNegativeButton("Kapat", null).create()
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            view.addView(voiceOption("Varsayılan ses", { prefs.edit().putString("tts_voice", "").apply() }, dialog))
            if (status == TextToSpeech.SUCCESS) {
                try {
                    val voices = localTts?.voices?.filter { it.locale?.language == "tr" || it.locale == Locale("tr", "TR") }
                        ?: emptyList()
                    for (v in voices.take(12)) {
                        view.addView(voiceOption(v.name, { prefs.edit().putString("tts_voice", v.name).apply() }, dialog))
                    }
                } catch (_: Exception) {}
            }
            dialog.setView(ScrollView(this).apply { addView(view) })
            dialog.setOnDismissListener { try { localTts?.shutdown() } catch (_: Exception) {} }
            dialog.show()
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
            .putBoolean("merge_repeat", true)
            .putBoolean("ocr_hires", true)
            .putBoolean("debug_log", false)
            .putBoolean("context_memory", true)
            .putBoolean("stabilize", true)
            .putString("glossary_keep", "")
            .apply()
        toast("Varsayılanlara dönüldü")
        renderTab()
    }
}
