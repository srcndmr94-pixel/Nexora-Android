package com.couchsubs.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {
    private val captureRequest = 4102
    private val notificationRequest = 4103

    private lateinit var status: TextView
    private lateinit var notifState: TextView
    private lateinit var overlayState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B1220")
        window.navigationBarColor = Color.parseColor("#0B1220")

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B1220")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        val title = TextView(this).apply {
            text = "NEXORA"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Oyun ve video altyazılarını ekranda Türkçeye çevirir"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 15f
            setPadding(0, dp(4), 0, dp(18))
        }

        val permCard = card()
        permCard.addView(sectionLabel("İzinler"))
        notifState = stateRow("Bildirim izni")
        overlayState = stateRow("Ekran üstü gösterme izni")
        permCard.addView(notifState)
        permCard.addView(overlayState)
        permCard.addView(space(8))
        permCard.addView(row(
            secondaryButton("Bildirim izni") {
                if (Build.VERSION.SDK_INT >= 33) {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        toast("Bildirim izni zaten açık")
                    } else {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
                    }
                } else toast("Bu sürümde ayrıca bildirim izni gerekmez")
                refreshStates()
            },
            secondaryButton("Pencere izni") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        ))

        val actionCard = card()
        actionCard.addView(sectionLabel("Yakalama"))
        status = TextView(this).apply {
            text = "Hazır. İzinleri verdikten sonra yakalamayı başlat."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 15f
            setLineSpacing(4f, 1f)
            setPadding(0, 0, 0, dp(12))
        }
        actionCard.addView(status)
        actionCard.addView(primaryButton("Ekran yakalamayı başlat") { startCapture() })
        actionCard.addView(space(10))
        actionCard.addView(dangerButton("Yakalamayı durdur") {
            stopService(Intent(this, ScreenCaptureService::class.java))
            status.text = "Yakalama durduruldu."
            LogStore.append(this, "DURUM", "Kullanıcı yakalamayı durdurdu")
        })

        val logCard = card()
        logCard.addView(sectionLabel("Çalışma ve hata kaydı"))
        val logHint = TextView(this).apply {
            text = "Çevrilen metinler ve hatalar burada tutulur. Bir sorun olursa kaydı paylaşabilirsin."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(0, 0, 0, dp(10))
        }
        logCard.addView(logHint)
        logCard.addView(row(
            secondaryButton("Kayıtları göster") { showLogs() },
            secondaryButton("Kayıtları paylaş") { shareLogs() }
        ))
        logCard.addView(space(8))
        logCard.addView(secondaryButton("Kayıtları temizle") {
            LogStore.clear(this)
            toast("Kayıtlar temizlendi")
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })

        root.addView(title)
        root.addView(subtitle)
        root.addView(permCard)
        root.addView(space(14))
        root.addView(actionCard)
        root.addView(space(14))
        root.addView(logCard)

        scroll.addView(root)
        setContentView(scroll)
        refreshStates()
        LogStore.append(this, "DURUM", "Uygulama açıldı")
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun startCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            toast("Önce bildirim iznini ver (durumu görmek için önerilir).")
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Önce ekran üstü gösterme iznini ver.")
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Deprecated by Android, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, service)
            status.text = "Yakalama başladı. Videoya/oyuna geç.\n\nÇeviri ekranda altta bant olarak görünür; bildirimde de durum yazar."
            LogStore.append(this, "DURUM", "Ekran yakalama başlatıldı")
        } else if (requestCode == captureRequest) {
            status.text = "Ekran yakalama izni verilmedi."
            LogStore.append(this, "HATA", "Ekran yakalama izni verilmedi")
        }
    }

    private fun refreshStates() {
        val notifOk = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        setState(notifState, "Bildirim izni", notifOk, if (Build.VERSION.SDK_INT < 33) "gerekli değil" else if (notifOk) "açık" else "kapalı")
        setState(overlayState, "Ekran üstü gösterme izni", overlayOk, if (overlayOk) "açık" else "kapalı")
    }

    private fun setState(view: TextView, label: String, ok: Boolean, detail: String) {
        val dot = if (ok) "● " else "○ "
        view.text = "$dot$label: $detail"
        view.setTextColor(if (ok) Color.parseColor("#86EFAC") else Color.parseColor("#FCA5A5"))
    }

    private fun showLogs() {
        val text = LogStore.read(this)
        val tail = if (text.length > 12000) "…(son kısım)\n" + text.takeLast(12000) else text
        val tv = TextView(this).apply {
            this.text = tail
            setTextColor(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.parseColor("#111827"))
            textSize = 12f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Çalışma ve hata kaydı")
            .setView(sv)
            .setPositiveButton("Kapat", null)
            .show()
    }

    private fun shareLogs() {
        val body = LogStore.read(this)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NEXORA çalışma kaydı")
            putExtra(Intent.EXTRA_TEXT, body.takeLast(100000))
        }
        startActivity(Intent.createChooser(send, "Kayıtları paylaş"))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#111827"))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.parseColor("#1F2937"))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#67E8F9"))
        textSize = 13f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.05f
        setPadding(0, 0, 0, dp(8))
    }

    private fun stateRow(label: String) = TextView(this).apply {
        text = "○ $label"
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 14f
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setTextColor(Color.parseColor("#082F49"))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#22D3EE"))
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1F2937"))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.parseColor("#334155"))
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun dangerButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.parseColor("#FEE2E2"))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#3F1D1D"))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#7F1D1D"))
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        weightSum = 2f
        addView(a)
        addView(b.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0 })
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
