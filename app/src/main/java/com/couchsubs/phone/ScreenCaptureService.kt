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
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
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
    @Volatile private var lastRejectLogAt = 0L
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var lastSpoken = ""

    // Player/UI chrome that is not dialogue.
    private val uiWords = setOf(
        "exit", "shop", "map", "inventory", "settings", "options", "menu", "save", "load",
        "quit", "resume", "retry", "respawn", "waypoint", "objective", "quest", "craft",
        "upgrade", "purchase", "sell", "buy", "confirm", "cancel", "apply", "reset",
        "swim", "dive", "lunge", "jump", "dodge", "sprint", "crouch", "interact",
        "attack", "block", "parry", "aim", "reload", "climb", "vault", "grapple",
        "subscribe", "share", "like", "dislike", "comments", "comment", "next", "previous",
        "play", "pause", "replay", "skip", "quality", "autoplay", "captions", "subtitle",
        "subtitles", "fullscreen", "live", "views", "ago", "remix", "download", "report"
    )
    private val turkishUiHints = listOf(
        "abone ol", "yorum yaz", "yorumlar", "izlendi", "paylaş", "kaydet", "beğendiğim",
        "gün önce", "saat önce", "dakika önce", "diğer videolar", "ürünleri göster",
        "kitaplık", "daha sonra izle", "gizli", "rozetler", "canlı", "kısaca"
    )
    private val englishDialogueMarkers = setOf(
        "i", "you", "he", "she", "we", "they", "me", "my", "your", "his", "her", "our", "their",
        "the", "a", "an", "to", "for", "and", "but", "or", "so", "if", "is", "are", "was", "were",
        "do", "don't", "dont", "did", "didn't", "didnt", "can", "can't", "cant", "will", "won't",
        "wont", "have", "has", "had", "it", "it's", "its", "this", "that", "what", "where", "why",
        "who", "how", "all", "just", "here", "there", "now", "right", "man", "no", "yeah", "let"
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlay = SubtitleOverlay(this)
        worker = HandlerThread("NEXORA-OCR").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        translator = Translation.getClient(options)
        // Model download is asynchronous. Never attempt translation before it is ready.
        updateStatus("Çeviri modeli hazırlanıyor (ilk seferde indirme gerekir)…")
        LogStore.append(this, "DURUM", "Servis oluşturuldu, çeviri modeli kontrol ediliyor")
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener { translatorReady = true; updateStatus("Hazır — altyazı bekleniyor"); LogStore.append(this, "DURUM", "Çeviri modeli hazır") }
            ?.addOnFailureListener { e -> updateStatus("Çeviri modeli indirilemedi. İnternet bağlantısını kontrol et."); LogStore.append(this, "HATA", "Çeviri modeli indirilemedi: ${e.localizedMessage}") }
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("tr", "TR"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) LogStore.append(this, "HATA", "TTS Türkçe dili kullanılamıyor (cihazda Türkçe ses verisi olmayabilir)")
                else LogStore.append(this, "DURUM", "TTS hazır")
            } else {
                LogStore.append(this, "HATA", "TTS başlatılamadı")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Başlatılıyor…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = projectionDataFrom(intent)
        if (data == null || resultCode == 0) {
            updateStatus("Ekran yakalama izni alınamadı. NEXORA'yı yeniden başlat.")
            LogStore.append(this, "HATA", "Ekran yakalama verisi alınamadı")
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
                        processFrame(bitmap)
                    }
                } ?: run { image.close(); processing.set(false) }
            }, workerHandler)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { updateStatus("Ekran yakalama durduruldu."); LogStore.append(this@ScreenCaptureService, "DURUM", "MediaProjection durdu"); stopSelf() }
            }, workerHandler)
            virtualDisplay = projection?.createVirtualDisplay(
                "NEXORA-Capture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, workerHandler
            )
            LogStore.append(this, "DURUM", "Ekran yakalama başladı ${screenWidth}x${screenHeight}")
            if (translatorReady) updateStatus("Hazır — altyazı bekleniyor")
            else updateStatus("Yakalama açık — çeviri modeli hazırlanıyor…")
        }.onFailure {
            updateStatus("Ekran yakalama başlatılamadı: ${it.localizedMessage ?: "bilinmeyen hata"}")
            LogStore.append(this, "HATA", "Ekran yakalama başlatılamadı: ${it.localizedMessage}")
            stopSelf()
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        // Scan the lower half to catch subtitles in different positions.
        val cropTop = (bitmap.height * 0.45f).toInt()
        val crop = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop)
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block -> block.lines }.map { it.text.trim() }
                val joined = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                val text = pickBestSubtitleCandidate(lines, joined)
                if (text != null) {
                    handleSubtitle(text)
                } else if (joined.isNotBlank()) {
                    val sample = joined.take(120)
                    val now = System.currentTimeMillis()
                    if (now - lastRejectLogAt > 1500L && sample.length > 3) {
                        lastRejectLogAt = now
                        LogStore.append(this, "OCR_ATLANDI", sample)
                    }
                    lastSource = ""
                } else {
                    lastSource = ""
                }
            }
            .addOnFailureListener { e -> LogStore.append(this, "HATA", "OCR hatası: ${e.localizedMessage}") }
            .addOnCompleteListener {
                crop.recycle()
                bitmap.recycle()
                processing.set(false)
            }
    }

    private fun cleanupCandidate(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        // Strip leading HUD/OCR noise: ALL-CAPS gibberish tokens, weapon labels, short junk before real text.
        t = t.replace(Regex("^(?:[A-Z0-9]{3,}(?:\\s+[A-Z0-9]{2,}){0,3}\\s+)(?=[A-Za-z'\"-])"), "") .trim()
        t = t.replace(Regex("^(?:[mM]\\s+)?[A-Z]{2,}[A-Z0-9]*\\s+R\\d+[A-Z]*\\s+[A-Z]+\\s+(?=[A-Za-z])"), "").trim()
        t = t.replace(Regex("^[^A-Za-zÇĞİÖŞÜçğıöşü'\"-]{0,3}"), "").trim()
        // Strip trailing isolated OCR noise tokens/suffixes seen in logs.
        t = t.replace(Regex("(?:\\s+[,.:;|]?\\s*(?:[RrD]|Ri|ri|[A-Z])\\s*[,.:;|]?)+$"), "").trim()
        t = t.replace(Regex("\\s+[|]$"), "").trim()
        t = t.replace(Regex("\\s+\\d{1,2}$"), "").trim() // stray small OCR numbers at end
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    private fun pickBestSubtitleCandidate(lines: List<String>, joined: String): String? {
        val cleanJoined = cleanupCandidate(joined)
        if (isLikelySubtitle(cleanJoined)) return cleanJoined
        // Try individual/combined lines in case joined text includes player chrome.
        val cleaned = lines.map { cleanupCandidate(it) }.filter { it.isNotBlank() }
        for (line in cleaned.sortedByDescending { it.length }) {
            if (isLikelySubtitle(line)) return line
        }
        // Try adjacent line pairs (subtitles sometimes split across OCR lines).
        for (i in 0 until (cleaned.size - 1)) {
            val pair = cleanupCandidate(cleaned[i] + " " + cleaned[i + 1])
            if (isLikelySubtitle(pair)) return pair
        }
        return null
    }

    private fun isLikelySubtitle(raw: String): Boolean {
        val text = cleanupCandidate(raw)
        if (text.length < 6 || text.length > 280) return false
        // Ignore timecodes/progress like 0:12, 1:23:45, 12:34 / 56:78.
        if (Regex("\\d{1,2}:\\d{2}").containsMatchIn(text) && text.count { it.isLetter() } < 12) return false
        if (text.matches(Regex("^[\\d:\\s/.,-]+$"))) return false
        val lower = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val compact = lower.replace(Regex("\\s+"), " ")
        // Skip obvious app/chrome/UI overlays.
        if (turkishUiHints.any { compact.contains(it) }) return false
        if (Regex("\\b\\d[\\d.,]*\\s*(b|mn|bin|milyon|k)?\\b.*\\b(izlendi|gün|saat|dakika)\\b").containsMatchIn(compact)) return false
        if (Regex("\\b(sorting|challenge|challengeg|show products|other videos)\\b").containsMatchIn(compact)) return false
        if (Regex("^[A-Z0-9\\s]{6,}$").matches(text) && text.count { it.isLetter() } < 10) return false
        if (compact.startsWith("#") && compact.count { it == '#' } >= 1 && compact.count { it.isLetter() && it.code < 128 } < 8) return false
        val hasTurkishChars = Regex("[çğıöşü]").containsMatchIn(lower)
        val wordsAscii = lower.split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        val uiCount = wordsAscii.count { it in uiWords }
        val buttonPrefix = Regex("^(?:[xyoab]|l[123]|r[123]|lt|rt)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (buttonPrefix && uiCount > 0) return false
        if (wordsAscii.size in 1..6 && uiCount >= 2) return false
        if (lower.matches(Regex("^[xyob a\\d,.:;]+$"))) return false
        val letters = text.count { it.isLetter() }
        if (letters < 8) return false
        val letterRatio = letters.toFloat() / text.length.coerceAtLeast(1)
        if (letterRatio < 0.45f) return false
        val markerCount = wordsAscii.count { it in englishDialogueMarkers }
        // If it looks Turkish-heavy UI/text and lacks clear English dialogue markers, skip.
        if (hasTurkishChars && markerCount < 2) return false
        // Require subtitle/dialogue-like English: enough words and some common markers,
        // or a clear sentence shape.
        val hasSentencePunct = Regex("[.?!]$").containsMatchIn(text) || text.contains('?') || text.contains('!')
        val looksLikeDialogue = markerCount >= 2 || (wordsAscii.size >= 5 && markerCount >= 1) || hasSentencePunct
        if (!looksLikeDialogue) return false
        // Avoid hashtag/label soup.
        if (text.count { it == '#' } >= 2) return false
        return true
    }

    private fun dedupKey(text: String): String {
        var n = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        n = n.replace(Regex("[^a-z0-9çğıöşü' ]+"), " ")
        n = n.replace(Regex("\\s+"), " ").trim()
        // drop very short leading/trailing OCR noise tokens
        n = n.replace(Regex("^[a-z]{1,2}\\s+"), "").trim()
        return n
    }

    private fun isNearDuplicate(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isBlank() || b.isBlank()) return false
        if (a.startsWith(b) || b.startsWith(a)) return kotlin.math.abs(a.length - b.length) <= 4
        return false
    }

    private fun handleSubtitle(text: String) {
        val normalized = dedupKey(text)
        if (isNearDuplicate(normalized, lastSource) || isNearDuplicate(normalized, lastShownSource)) return
        lastSource = normalized
        LogStore.append(this, "KAYNAK", text)
        if (!translatorReady) {
            pendingSource = text
            updateStatus("Çeviri modeli hazırlanıyor…")
            return
        }
        if (translating) {
            pendingSource = text
            return
        }
        translate(text)
    }

    private fun translate(source: String) {
        val activeTranslator = translator ?: run { updateStatus(source); LogStore.append(this, "HATA", "Çevirici yok, kaynak gösterildi: $source"); return }
        translating = true
        activeTranslator.translate(source)
            .addOnSuccessListener { translated ->
                if (translated.isNotBlank()) {
                    lastShownSource = dedupKey(source)
                    val out = translated.trim()
                    updateStatus(out)
                    LogStore.append(this, "CEVIRI", "EN: $source || TR: $out")
                } else {
                    updateStatus(source)
                    LogStore.append(this, "HATA", "Boş çeviri sonucu, kaynak: $source")
                }
            }
            .addOnFailureListener { e ->
                updateStatus("Çeviri başarısız — kaynak: $source")
                LogStore.append(this, "HATA", "Çeviri başarısız (${e.localizedMessage}): $source")
            }
            .addOnCompleteListener {
                translating = false
                val next = pendingSource
                pendingSource = null
                if (!next.isNullOrBlank() && next != source) translate(next)
            }
    }

    private fun maybeSpeak(text: String) {
        val enabled = getSharedPreferences("nexora_overlay", MODE_PRIVATE).getBoolean("tts_enabled", false)
        if (!enabled || !ttsReady || text.isBlank() || text == lastSpoken) return
        if (text.startsWith("Hazır") || text.startsWith("Çeviri") || text.startsWith("Ekran") || text.startsWith("Kaynak") || text.startsWith("OCR")) return
        lastSpoken = text
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nexora_sub") } catch (_: Exception) {}
    }

    private fun updateStatus(text: String) {
        overlay?.show(text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
        maybeSpeak(text)
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
    } catch (e: Exception) { LogStore.append(this, "HATA", "Kare dönüştürülemedi: ${e.localizedMessage}"); null }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "NEXORA ekran yakalama", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String = "Oyun altyazıları algılanıyor ve çevriliyor"): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("NEXORA çalışıyor")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        LogStore.append(this, "DURUM", "Servis durduruluyor")
        virtualDisplay?.release()
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        projection?.stop()
        overlay?.hide()
        recognizer.close()
        translator?.close()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null; ttsReady = false
        worker?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
