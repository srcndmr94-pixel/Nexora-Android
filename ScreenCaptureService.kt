package com.couchsubs.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "NEXORA_DEBUG"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "nexora_capture"
        private const val NOTIFICATION_ID = 71
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: SubtitleOverlay? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val processing = AtomicBoolean(false)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var translator: Translator? = null
    @Volatile private var translatorReady = false
    @Volatile private var lastSource = ""
    @Volatile private var lastShownSource = ""
    @Volatile private var translating = false
    private var pendingSource: String? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var frameCounter = 0

    private val uiWords = setOf(
        "exit", "shop", "map", "inventory", "settings", "options", "menu", "save", "load",
        "quit", "resume", "retry", "respawn", "waypoint", "objective", "quest", "craft",
        "upgrade", "purchase", "sell", "buy", "confirm", "cancel", "apply", "reset",
        "swim", "dive", "lunge", "jump", "dodge", "sprint", "crouch", "interact",
        "attack", "block", "parry", "aim", "reload", "climb", "vault", "grapple"
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servis başlatıldı (onCreate).")
        createNotificationChannel()
        overlay = SubtitleOverlay(this)
        worker = HandlerThread("NEXORA-OCR").also { it.start() }
        workerHandler = Handler(worker!!.looper)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        translator = Translation.getClient(options)
        
        Log.d(TAG, "ML Kit Çeviri modeli indirme isteği gönderiliyor...")
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                translatorReady = true
                Log.d(TAG, "ML Kit Türkçe çeviri modeli BAŞARIYLA yüklendi.")
                overlay?.show("NEXORA hazır — altyazı bekleniyor")
            }
            ?.addOnFailureListener { error ->
                Log.e(TAG, "ML Kit çeviri modeli indirilemedi: ${error.message}", error)
                overlay?.show("Çeviri modeli indirilemedi. İnterneti kontrol et.")
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = projectionDataFrom(intent)
        if (data == null || resultCode == 0) {
            Log.e(TAG, "Ekran yakalama izni alınamadı.")
            overlay?.show("Ekran yakalama izni alınamadı. NEXORA'yı yeniden başlat.")
            stopSelf()
            return START_NOT_STICKY
        }
        startProjection(resultCode, data)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun projectionDataFrom(intent: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA) as? Intent
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        runCatching {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            Log.d(TAG, "Ekran çözünürlüğü algılandı: ${screenWidth}x${screenHeight}")

            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
            reader?.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!processing.compareAndSet(false, true)) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                workerHandler?.post {
                    val bitmap = try { imageToBitmap(image) } finally { image.close() }
                    if (bitmap == null) {
                        processing.set(false)
                    } else {
                        frameCounter++
                        if (frameCounter % 60 == 0) {
                            Log.d(TAG, "Kareler yakalanmaya devam ediyor... ($frameCounter. kare)")
                        }
                        processFrame(bitmap)
                    }
                } ?: run { image.close(); processing.set(false) }
            }, workerHandler)

            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection durduruldu.")
                    stopSelf()
                }
            }, workerHandler)

            virtualDisplay = projection?.createVirtualDisplay(
                "NEXORA-Capture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, workerHandler
            )
            overlay?.show("NEXORA hazır — oyun altyazısı bekleniyor")
            Log.d(TAG, "VirtualDisplay ve MediaProjection başarıyla başlatıldı.")
        }.onFailure {
            Log.e(TAG, "Ekran yakalama başlatılamadı!", it)
            overlay?.show("Ekran yakalama başlatılamadı: ${it.localizedMessage ?: "bilinmeyen hata"}")
            stopSelf()
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        val cropTop = (bitmap.height * 0.58f).toInt()
        val crop = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop)
        
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block -> block.lines }.map { it.text.trim() }
                val text = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                if (text.isNotBlank()) {
                    Log.d(TAG, "OCR Metin Okudu: -> \"$text\"")
                }
                if (isLikelySubtitle(text)) {
                    handleSubtitle(text)
                } else if (text.isBlank()) {
                    lastSource = ""
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR Hatası: ${e.message}")
            }
            .addOnCompleteListener {
                crop.recycle()
                bitmap.recycle()
                processing.set(false)
            }
    }

    private fun isLikelySubtitle(raw: String): Boolean {
        val text = raw.trim()
        if (text.length < 3 || text.length > 260) return false
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ENGLISH)
        val words = normalized.split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false

        val uiCount = words.count { it in uiWords }
        val buttonPrefix = Regex("^(?:[xyoab]|l[123]|r[123]|lt|rt)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (buttonPrefix && uiCount > 0) return false
        if (words.size <= 5 && uiCount >= 2) return false
        if (normalized.matches(Regex("^[xyob a]+$"))) return false
        return words.any { it.length >= 3 }
    }

    private fun handleSubtitle(text: String) {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (normalized == lastSource || normalized == lastShownSource) return
        lastSource = normalized

        if (!translatorReady) {
            pendingSource = text
            Log.d(TAG, "Çeviri modeli henüz hazır değil, bekleniyor: \"$text\"")
            overlay?.show("Çeviri modeli hazırlanıyor…")
            return
        }
        if (translating) {
            pendingSource = text
            return
        }
        translate(text)
    }

    private fun translate(source: String) {
        val activeTranslator = translator ?: run { overlay?.show(source); return }
        translating = true
        activeTranslator.translate(source)
            .addOnSuccessListener { translated ->
                if (translated.isNotBlank()) {
                    Log.d(TAG, "Çeviri Sonucu: -> \"$translated\"")
                    lastShownSource = Normalizer.normalize(source, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                    overlay?.show(translated.trim())
                } else overlay?.show(source)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Çeviri Başarısız: ${e.message}")
                overlay?.show("Çeviri başarısız — kaynak: $source")
            }
            .addOnCompleteListener {
                translating = false
                val next = pendingSource
                pendingSource = null
                if (!next.isNullOrBlank() && next != source) translate(next)
            }
    }

    private fun imageToBitmap(image: Image): Bitmap? = try {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        result
    } catch (e: Exception) {
        Log.e(TAG, "Image to Bitmap hatası: ${e.message}")
        null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "NEXORA ekran yakalama", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("NEXORA çalışıyor")
        .setContentText("Oyun altyazıları algılanıyor ve çevriliyor")
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        Log.d(TAG, "Servis kapatıldı (onDestroy).")
        virtualDisplay?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        projection?.stop()
        overlay?.hide()
        recognizer.close()
        translator?.close()
        worker?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
