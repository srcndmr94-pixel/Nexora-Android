package com.couchsubs.phone

import android.Manifest
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
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File

class MainActivity : ComponentActivity() {
    private val notificationPermissionCode = 1001
    private var overlayPermissionRequested = false
    private lateinit var statusLabel: TextView
    private lateinit var modelState: TextView
    private lateinit var preview: TextView
    private val modelManager by lazy { RemoteModelManager.getInstance() }
    private val enTrModel by lazy { TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build() }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            LogStore.append(this, "DURUM", "Ekran yakalama izni verildi, servis başlatılıyor")
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
                toast("NEXORA başlatıldı")
                statusLabel.text = "Çalışıyor: oyun/uygulama altyazıları algılanıp Türkçeye çevrilecek."
            } catch (e: Exception) {
                val msg = "Servis başlatılamadı: ${e.localizedMessage ?: "bilinmeyen hata"}"
                LogStore.append(this, "HATA", msg)
                statusLabel.text = msg
                toast(msg)
            }
        } else {
            LogStore.append(this, "HATA", "Ekran yakalama izni reddedildi/iptal edildi")
            statusLabel.text = "Ekran yakalama izni verilmedi. Altyazı okuyabilmek için bu izin gerekli."
            toast("Ekran yakalama izni verilmedi")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusLabel.isInitialized) updatePermissionStatus()
        if (::modelState.isInitialized) refreshModelState()
        // Auto-request overlay permission once after returning from Settings (don't loop).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this) && !overlayPermissionRequested) {
            overlayPermissionRequested = true
            requestOverlayPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.append(this, "DURUM", "Uygulama açıldı")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0C0C0B"))
            val pad = dp(20)
            setPadding(pad, dp(28), pad, pad)
        }

        val title = TextView(this).apply {
            text = "NEXORA"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val subtitle = TextView(this).apply {
            text = "Ekrandaki İngilizce altyazıları Türkçeye çevirir."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 15f
            setPadding(0, dp(4), 0, dp(18))
        }

        statusLabel = TextView(this).apply {
            text = "Hazır"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            setLineSpacing(4f, 1f)
            background = rounded(Color.parseColor("#141413"), 14f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val permCard = card()
        permCard.addView(sectionLabel("Gerekli izinler"))
        permCard.addView(row(
            secondaryButton("Bildirim izni") { requestNotificationPermission() },
            secondaryButton("Üstte gösterme") { requestOverlayPermission() }
        ))
        permCard.addView(space(10))
        permCard.addView(statusLabel)

        val actionCard = card()
        actionCard.addView(sectionLabel("Başlat"))
        val start = primaryButton("NEXORA'yı Başlat") { startWithPermissions() }
        val stop = secondaryButton("Durdur") {
            stopService(Intent(this, ScreenCaptureService::class.java))
            toast("NEXORA durduruldu")
            statusLabel.text = "Durduruldu."
            LogStore.append(this, "DURUM", "Kullanıcı yakalamayı durdurdu")
        }
        actionCard.addView(row(start, stop))

        val styleCard = card()
        styleCard.addView(sectionLabel("Altyazı görünümü"))
        val prefs = getSharedPreferences("nexora_overlay", MODE_PRIVATE)
        preview = TextView(this).apply {
            text = "Örnek: What's up, man? → Naber, dostum?"
            setTextColor(prefs.getInt("text_color", Color.WHITE))
            textSize = prefs.getFloat("text_size_sp", 22f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply { setColor(Color.argb(225, 12, 12, 11)); cornerRadius = dp(14).toFloat() }
        }
        styleCard.addView(preview)
        styleCard.addView(space(10))
        styleCard.addView(label("Yazı boyutu"))
        val sizeSeek = SeekBar(this).apply {
            max = 26 // 14..40
            progress = (prefs.getFloat("text_size_sp", 22f) - 14f).toInt().coerceIn(0, 26)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sp = 14f + progress
                    prefs.edit().putFloat("text_size_sp", sp).apply()
                    preview.textSize = sp
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { toast("Yazı boyutu kaydedildi") }
            })
        }
        styleCard.addView(sizeSeek)
        styleCard.addView(space(8))
        styleCard.addView(label("Yazı rengi"))
        val colors = listOf(
            "Beyaz" to Color.WHITE,
            "Sarı" to Color.parseColor("#FDE047"),
            "Camgöbeği" to Color.parseColor("#67E8F9"),
            "Yeşil" to Color.parseColor("#86EFAC")
        )
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, c) in colors) {
            colorRow.addView(smallChoice(name) {
                prefs.edit().putInt("text_color", c).apply()
                preview.setTextColor(c)
                toast("Renk: $name")
            })
        }
        styleCard.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(colorRow) })
        styleCard.addView(space(8))
        styleCard.addView(label("Altyazı alanı (ekrandaki konum)"))
        val posRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val positions = listOf("Alt" to "bottom", "Orta" to "middle", "Üst" to "top")
        for ((name, key) in positions) {
            posRow.addView(smallChoice(name) {
                prefs.edit().putString("position", key).apply()
                toast("Konum: $name (sonraki altyazıda uygulanır)")
            })
        }
        styleCard.addView(posRow)
        styleCard.addView(space(8))
        styleCard.addView(label("Dikey konum ince ayarı (kenardan uzaklık)"))
        val offsetSeek = SeekBar(this).apply {
            max = 500
            progress = prefs.getInt("offset_dp", 180).coerceIn(0, 500)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    prefs.edit().putInt("offset_dp", progress.coerceAtLeast(24)).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { toast("Konum ince ayarı kaydedildi") }
            })
        }
        styleCard.addView(offsetSeek)
        styleCard.addView(space(8))
        styleCard.addView(label("Seslendirme"))
        val ttsState = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8")); textSize = 13f
            text = if (prefs.getBoolean("tts_enabled", false)) "Açık — çevrilen altyazı sesli okunur." else "Kapalı"
            setPadding(0, 0, 0, dp(6))
        }
        styleCard.addView(ttsState)
        val ttsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ttsRow.addView(smallChoice("Açık") {
            prefs.edit().putBoolean("tts_enabled", true).apply()
            ttsState.text = "Açık — çevrilen altyazı sesli okunur."
            toast("Seslendirme açık")
        })
        ttsRow.addView(smallChoice("Kapalı") {
            prefs.edit().putBoolean("tts_enabled", false).apply()
            ttsState.text = "Kapalı"
            toast("Seslendirme kapalı")
        })
        styleCard.addView(ttsRow)
        styleCard.addView(TextView(this).apply {
            text = "Not: Seslendirme, telefonundaki Türkçe metin okuma (TTS) özelliğini kullanır. Cihazında Türkçe ses verisi yoksa ses çıkmayabilir."
            setTextColor(Color.parseColor("#64748B")); textSize = 12f; setLineSpacing(2f, 1f)
            setPadding(0, dp(6), 0, 0)
        })

        val modelCard = card()
        modelCard.addView(sectionLabel("Dil paketi (İngilizce → Türkçe)"))
        modelState = TextView(this).apply {
            text = "Durum kontrol ediliyor… (çeviri için bu paket gerekir; ilk indirme internet ister)"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            setLineSpacing(3f, 1f)
            setPadding(0, 0, 0, dp(10))
        }
        modelCard.addView(modelState)
        modelCard.addView(row(
            secondaryButton("Paketi indir") { downloadModel() },
            secondaryButton("Paketi sil") { deleteModel() }
        ))
        modelCard.addView(space(8))
        modelCard.addView(secondaryButton("Durumu yenile") { refreshModelState() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        root.addView(styleCard)
        root.addView(space(14))
        root.addView(modelCard)
        root.addView(space(14))
        root.addView(logCard)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        refreshModelState()
    }

    private fun updatePermissionStatus() {
        val notif = if (Build.VERSION.SDK_INT < 33) "gerekli değil"
            else if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) "verildi" else "eksik"
        val overlay = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) "verildi" else "eksik"
        statusLabel.text = "Bildirim izni: $notif · Üstte gösterme: $overlay"
    }

    private fun startWithPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            requestNotificationPermission()
            toast("Önce bildirim iznini ver, sonra tekrar Başlat'a dokun.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            toast("Önce 'Üstte gösterme' iznini aç, sonra tekrar Başlat'a dokun.")
            return
        }
        requestCapture()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionCode)
        } else toast("Bildirim izni zaten tamam")
        updatePermissionStatus()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else toast("Üstte gösterme izni zaten açık")
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refreshModelState() {
        modelState.text = "Dil paketi durumu kontrol ediliyor…"
        modelManager.isModelDownloaded(enTrModel)
            .addOnSuccessListener { downloaded ->
                modelState.text = if (downloaded) "EN→TR dil paketi hazır. Çeviri bu paketle yapılır (sansür eklenmez; metin olduğu gibi çevrilir)."
                else "EN→TR dil paketi yüklü değil. \"Paketi indir\" ile indir (ilk indirme internet ve biraz alan gerektirir)."
            }
            .addOnFailureListener { e ->
                modelState.text = "Dil paketi durumu alınamadı: ${e.localizedMessage ?: "hata"}"
            }
    }

    private fun downloadModel() {
        modelState.text = "EN→TR paketi indiriliyor… (internete ve biraz zamana ihtiyaç var)"
        LogStore.append(this, "DURUM", "EN→TR dil paketi indirme başlatıldı")
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val client = Translation.getClient(options)
        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelState.text = "EN→TR dil paketi indirildi ve hazır."
                LogStore.append(this, "DURUM", "EN→TR dil paketi indirildi")
                toast("Dil paketi hazır")
                client.close()
                refreshModelState()
            }
            .addOnFailureListener { e ->
                val msg = e.localizedMessage ?: "bilinmeyen hata"
                modelState.text = "Paket indirilemedi: $msg. İnterneti kontrol edip tekrar dene."
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

    private fun showLogs() {
        val text = LogStore.read(this).ifBlank { "Henüz kayıt yok." }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val sc = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0C0C0B")); addView(tv) }
        AlertDialog.Builder(this).setTitle("NEXORA kayıtları").setView(sc)
            .setPositiveButton("Kapat", null).show()
    }

    private fun shareLogs() {
        val file = LogStore.file(this)
        if (!file.exists() || file.length() == 0L) {
            toast("Paylaşılacak kayıt yok")
            return
        }
        // Copy to cache for FileProvider.
        val out = File(cacheDir, "nexora_log.txt")
        file.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NEXORA çalışma/hata kaydı")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Kayıtları paylaş"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun space(h: Int) = android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#141413"), 18f)
        val p = dp(16); setPadding(p, p, p, p)
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 13f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.06f
        setPadding(0, 0, 0, dp(10))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#94A3B8")); textSize = 13f; setPadding(0, dp(2), 0, dp(4))
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.parseColor("#CD6B47"), 14f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.parseColor("#E2E8F0"))
        background = rounded(Color.parseColor("#1F1F1D"), 14f).also { it.setStroke(dp(1), Color.parseColor("#2E2E2B")) }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
    }

    private fun smallChoice(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.parseColor("#E2E8F0"))
        background = rounded(Color.parseColor("#1F1F1D"), 12f).also { it.setStroke(dp(1), Color.parseColor("#2E2E2B")) }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun row(left: Button, right: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        weightSum = 2f
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }
}
