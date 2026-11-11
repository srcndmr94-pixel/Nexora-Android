package com.couchsubs.phone

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val captureRequest = 4102
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        status = TextView(this).apply {
            text = "NEXORA — altyazı çevirisi\n\n1. Diğer uygulamaların üzerinde gösterme izni ver.\n2. Ekran yakalamayı başlat.\n3. PS Remote Play'e geç ve oyun altyazısını aç."
            textSize = 17f
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
            setOnClickListener { stopService(Intent(this@MainActivity, ScreenCaptureService::class.java)) }
        }
        root.addView(status)
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
            status.text = "Ekran yakalama başladı. PS Remote Play'e geç.\nOCR sonucu altyazı penceresinde görünecek.\n\nNot: Remote Play görüntüsü Android tarafından engellenirse siyah ekran görebiliriz."
        }
    }
}
