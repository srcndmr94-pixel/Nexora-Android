package com.couchsubs.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val captureRequest = 4102
    private val notificationRequest = 4103
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        status = TextView(this).apply {
            text = "NEXORA — altyazı çevirisi\n\n1. Bildirim iznini ver (Android 13+).\n2. Diğer uygulamaların üzerinde gösterme iznini ver.\n3. Ekran yakalamayı başlat.\n4. Oyuna/uygulamaya geç; İngilizce altyazıyı aç.\n\nDurum bildirimden ve (izin varsa) ekranın altındaki banttan görünür."
            textSize = 17f
        }
        val notificationButton = Button(this).apply {
            text = "Bildirim iznini ver"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33) {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this@MainActivity, "Bildirim izni zaten açık", Toast.LENGTH_SHORT).show()
                    } else {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Bu Android sürümünde ayrıca bildirim izni gerekmez", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val overlayButton = Button(this).apply {
            text = "Altyazı penceresi iznini ver"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else Toast.makeText(this@MainActivity, "İzin zaten açık", Toast.LENGTH_SHORT).show()
            }
        }
        val startButton = Button(this).apply {
            text = "Ekran yakalamayı başlat"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this@MainActivity, "Önce bildirim iznini ver (durumu görmek için).", Toast.LENGTH_LONG).show()
                }
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Önce altyazı penceresi iznini ver.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        }
        val stopButton = Button(this).apply {
            text = "Yakalamayı durdur"
            setOnClickListener { stopService(Intent(this@MainActivity, ScreenCaptureService::class.java)); status.text = "Yakalama durduruldu." }
        }
        root.addView(status)
        root.addView(notificationButton)
        root.addView(overlayButton)
        root.addView(startButton)
        root.addView(stopButton)
        setContentView(root)
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
            status.text = "Ekran yakalama başladı.\n\nDurumu bildirimden takip et: 'Çeviri modeli hazırlanıyor', 'Hazır — altyazı bekleniyor' gibi.\n\nOyuna/uygulamaya geç ve İngilizce altyazıyı aç. PS Remote Play gibi korumalı görüntülerde ekran siyah gelebilir; bu durumda çeviri yapılamaz."
        } else if (requestCode == captureRequest) {
            status.text = "Ekran yakalama izni verilmedi."
        }
    }
}
