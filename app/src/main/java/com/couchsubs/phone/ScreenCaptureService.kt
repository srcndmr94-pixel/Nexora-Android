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
import android.speech.tts.Voice
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ScreenCaptureService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "gamelingopro_capture"
        const val NOTIFICATION_ID = 1001
        const val PREFS = "nexora_overlay"
        const val APP_NAME = "GameLingoPro"
        @Volatile var running: Boolean = false
        private val englishDialogueMarkers = setOf(
            "i","me","my","we","us","our","you","your","he","him","his","she","her","they","them","their",
            "a","an","the","and","but","or","so","because","if","when","then","than","to","of","for","on","in","at","with","from","by","as","is","am","are","was","were","be","been","being",
            "do","does","did","have","has","had","will","would","can","could","should","may","might","must","not","no","yes",
            "what","who","where","why","how","this","that","these","those","it","there","here","let","get","got","go","come","see","look","know","think","want","need","say","said","tell","told",
            "hey","hi","hello","please","thanks","sorry","okay","ok","yeah","yep","nope","ain","ain't","don't","dont","can't","cant","won't","wont","i'm","im","it's","its","you're","youre"
        )
        private val uiWords = setOf(
            "ok","yes","no","back","next","skip","menu","start","pause","resume","continue","options",
            "settings","inventory","map","journal","quests","quest","save","load","exit","close","open",
            "select","confirm","cancel","apply","equip","unequip","craft","buy","sell","talk","interact",
            "press","hold","release","enter","search","mount","call","return","take","study","read","inspect",
            "use","pick","drop","accept","decline","new","game","level","chapter","objective","checkpoint",
            "tutorial","controls","controller","keyboard","mouse","online","offline","login","logout",
            "profile","store","shop","party","guild","rank","score","health","ammo","weapon","armor",
            "damage","attack","defend","dog","palm","praise","pet","scold","knock"
        )
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: SubtitleOverlay? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var translator: Translator? = null
    @Volatile private var translatorReady = false
    @Volatile private var ttsReady = false
    private var tts: TextToSpeech? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val processing = AtomicBoolean(false)
    @Volatile private var lastSubtitleAt = 0L
    @Volatile private var showingSubtitle = false
    @Volatile private var lastShownText = ""
    @Volatile private var lastShownAt = 0L
    @Volatile private var lastSource = ""
    @Volatile private var lastPartialKey = ""
    @Volatile private var lastPartialAt = 0L
    @Volatile private var lastSpoken = ""
    @Volatile private var lastFrameHash = 0L
    @Volatile private var lastFrameAt = 0L
    @Volatile private var processingSince = 0L
    @Volatile private var translationBusy = false
    @Volatile private var lastNotified = ""
    @Volatile private var lastNotifyAt = 0L
    @Volatile private var cachedBand: IntArray? = null
    @Volatile private var cachedBandAt = 0L
    @Volatile private var cachedBandWidth = 0
    private val pendingQueue = LinkedBlockingQueue<String>(4)
    private val translationCache = ConcurrentHashMap<String, String>()
    private val AI_DEFAULT_MODEL = "openai/gpt-oss-120b"
    private val AI_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val cacheLock = Any()
    private val buttonPromptRegex = Regex("\\b(?:[LR][123SB]|RS|LS|LT|RT|LB|RB|TAB|ESC|ENTER|SHIFT|CTRL|ALT|SPACE)\\b|\\([A-Z0-9]{1,3}\\)|\\[[A-Z0-9]{1,3}\\]", RegexOption.IGNORE_CASE)
    private val ratingChromeRegex = Regex("\\b\\d+[\\.,]?\\d*\\s*[KMBkmb]?\\s*(views?|likes?|subscribers?|abone|izlenme)\\b|@\\w{2,}", RegexOption.IGNORE_CASE)
    // Baglam: son diyaloglar ve kararlilik (ayni satiri bagimsiz cevirmemek icin)
    private val recentSources = ArrayDeque<String>()
    @Volatile private var stableText: String = ""
    @Volatile private var stableCount: Int = 0
    @Volatile private var stableAt: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            LogStore.append(this@ScreenCaptureService, "DURUM", "Ekran yakalama durduruldu")
            stopSelf()
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            try {
                tts?.language = Locale("tr", "TR")
            } catch (_: Exception) {
            }
        }
        LogStore.append(this, "DURUM", "Sesli okuma ${if (ttsReady) "hazır" else "kullanılamıyor"}")
    }

    override fun onCreate() {
        super.onCreate()
        LogStore.append(this, "DURUM", "Servis oluşturuluyor")
        CrashGuard.install(this)
        running = true
        createNotificationChannel()
        overlay = try {
            SubtitleOverlay(applicationContext)
        } catch (e: Exception) {
            LogStore.append(this, "HATA", "Balon olusturulamadi: " + (e.localizedMessage ?: ""))
            null
        }
        worker = HandlerThread("nexora-capture").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        tts = TextToSpeech(applicationContext, this)
        ensureTranslator()
    }

    private fun ensureTranslator() {
        val mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("translation_mode", "device") ?: "device"
        if (mode == "online" || mode == "ai") {
            translatorReady = true
            LogStore.append(this, "DURUM", if (mode == "ai") "Yapay zekâ çeviri modu seçildi" else "İnternet çeviri modu seçildi")
            updateStatus("Hazır. Oyun altyazıları algılanıyor…")
            return
        }
        if (translator != null) return
        LogStore.append(this, "DURUM", "Çeviri modeli hazırlanıyor")
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val tr = Translation.getClient(options)
        translator = tr
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translatorReady = true
                LogStore.append(this, "DURUM", "Çeviri modeli hazır")
                updateStatus("Hazır. Oyun altyazıları algılanıyor…")
            }
            .addOnFailureListener { e ->
                val msg = "Çeviri modeli indirilemedi: ${e.localizedMessage ?: "hata"}"
                LogStore.append(this, "HATA", msg)
                updateStatus("$msg. Ayarlar'dan modeli indirip tekrar deneyin.")
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogStore.append(this, "DURUM", "Servis baslatma istegi alindi")
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        if (intent == null || resultCode == -1 || data == null) {
            LogStore.append(this, "HATA", "Başlatma verisi alınamadı")
            updateStatus("Başlatma verisi alınamadı. Uygulamadan tekrar başlatın.")
            stopSelf()
            return START_NOT_STICKY
        }
        val projectionData = Intent(data)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("$APP_NAME hazırlanıyor…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("$APP_NAME hazırlanıyor…"))
            }
        } catch (e: Exception) {
            running = false
            LogStore.append(this, "HATA", "Bildirim baslatilamadi: " + e.javaClass.simpleName + ": " + (e.localizedMessage ?: ""))
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        try {
            ensureTranslator()
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionCallback, workerHandler)
            createVirtualDisplay()
            LogStore.append(this, "DURUM", "Ekran yakalama başlatıldı")
            updateStatus("Hazır. Oyun altyazıları algılanıyor…")
        } catch (e: Exception) {
            running = false
            val msg = "Yakalama başlatılamadı: ${e.localizedMessage ?: "bilinmeyen hata"}"
            LogStore.append(this, "HATA", msg)
            updateStatus(msg)
            stopSelf()
        }
        return START_STICKY
    }

    private fun createVirtualDisplay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.let {
                DisplayMetrics().apply {
                    widthPixels = it.width()
                    heightPixels = it.height()
                    densityDpi = resources.displayMetrics.densityDpi
                }
            }
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        }
        val scale = (prefInt("capture_scale", 100).coerceIn(50, 100)) / 100f
        val qWidth = max(360, (metrics.widthPixels * scale).toInt())
        val qHeight = max(640, (metrics.heightPixels * scale).toInt())
        val density = metrics.densityDpi
        reader = ImageReader.newInstance(qWidth, qHeight, PixelFormat.RGBA_8888, 2).also { ir ->
            ir.setOnImageAvailableListener({ imageReader ->
                val now = System.currentTimeMillis()
                val idle = prefInt("capture_interval_ms", 350).coerceIn(120, 2000).toLong()
                if (now - lastFrameAt < idle) {
                    imageReader.acquireLatestImage()?.close()
                    // Oncekinde takili kaldiysa bekci serbest birakmali.
                    val stuck = processingSince
                    if (stuck != 0L && now - stuck > 8000L) {
                        processing.set(false)
                        processingSince = 0L
                    }
                    return@setOnImageAvailableListener
                }
                val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!processing.compareAndSet(false, true)) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                processingSince = System.currentTimeMillis()
                lastFrameAt = processingSince
                try {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    if (bitmap == null) {
                        processing.set(false); processingSince = 0L
                        return@setOnImageAvailableListener
                    }
                    // Skip identical frames quickly by sampling a few pixels.
                    val hash = sampleHash(bitmap)
                    if (hash != 0L && hash == lastFrameHash) {
                        bitmap.recycle()
                        val clearMs = prefInt("clear_ms", 2200).coerceIn(800, 8000)
                        if (showingSubtitle && now - lastSubtitleAt > clearMs) {
                            overlay?.show("")
                            lastShownText = ""
                            showingSubtitle = false
                        }
                        processing.set(false); processingSince = 0L
                        return@setOnImageAvailableListener
                    }
                    lastFrameHash = hash
                    processFrame(bitmap)
                } catch (e: Exception) {
                    try { image.close() } catch (_: Exception) {}
                    processing.set(false); processingSince = 0L
                    LogStore.append(this, "HATA", "Kare işlenemedi: ${e.localizedMessage}")
                }
            }, workerHandler)
        }
        virtualDisplay = projection?.createVirtualDisplay(
            "nexora-screen",
            qWidth, qHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, workerHandler
        )
        LogStore.append(this, "DURUM", "Yakalama ${qWidth}x${qHeight} ölçekte hazır")
    }

    private fun sampleHash(b: Bitmap): Long {
        try {
            val w = b.width; val h = b.height
            if (w < 16 || h < 16) return 0L
            var acc = 1469598103934665603UL
            val xs = intArrayOf(w/8, w/4, w/2, 3*w/4, 7*w/8)
            val ys = intArrayOf(h/4, h/3, h/2, 2*h/3, 3*h/4)
            for (y in ys) for (x in xs) {
                acc = acc xor b.getPixel(x.coerceIn(0,w-1), y.coerceIn(0,h-1)).toULong()
                acc *= 1099511628211UL
            }
            return acc.toLong()
        } catch (_: Exception) { return 0L }
    }

    private fun processFrame(bitmap: Bitmap) {
        try {
            val w = bitmap.width
            val h = bitmap.height
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val position = prefs.getString("position", "bottom") ?: "bottom"
            // Custom alan ayarlandiysa (mobilde "ozel" etiketiyle kaydedilir) secili kutuyu kullan.
            val useCustom = position == "custom" || position == "ozel" || prefs.getString("region", "") == "custom"
            // Otomatik mod: her karede altyazi bandini kendimiz buluruz.
            val autoBand = if (prefBool("auto_region", true)) cachedSubtitleBand(bitmap) else null
            val left: Int; val top: Int; val right: Int; val bottom: Int
            if (autoBand != null) {
                left = (w * 0.02f).toInt().coerceIn(0, w - 2)
                right = (w * 0.98f).toInt().coerceIn(left + 2, w)
                top = autoBand[0].coerceIn(0, h - 8)
                bottom = autoBand[1].coerceIn(top + 8, h)
            } else if (useCustom) {
                left = (w * prefs.getFloat("region_l", 0.06f)).toInt().coerceIn(0, w - 2)
                top = (h * prefs.getFloat("region_t", 0.62f)).toInt().coerceIn(0, h - 2)
                right = (w * prefs.getFloat("region_r", 0.94f)).toInt().coerceIn(left + 2, w)
                bottom = (h * prefs.getFloat("region_b", 0.94f)).toInt().coerceIn(top + 2, h)
            } else {
                val rect = when (position) {
                    "top" -> floatArrayOf(0.05f, 0.03f, 0.95f, 0.28f)
                    "middle", "center", "orta" -> floatArrayOf(0.05f, 0.32f, 0.95f, 0.68f)
                    "full" -> floatArrayOf(0.02f, 0.02f, 0.98f, 0.98f)
                    else -> floatArrayOf(0.06f, 0.62f, 0.94f, 0.94f) // bottom
                }
                left = (w * rect[0]).toInt().coerceIn(0, w - 2)
                top = (h * rect[1]).toInt().coerceIn(0, h - 2)
                right = (w * rect[2]).toInt().coerceIn(left + 2, w)
                bottom = (h * rect[3]).toInt().coerceIn(top + 2, h)
            }
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val ocrCrop = upscaleForOcr(crop)
            val image = InputImage.fromBitmap(ocrCrop, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val handled = try { handleResult(result) } catch (_: Exception) { false }
                    if (handled) {
                        if (ocrCrop !== crop) ocrCrop.recycle()
                        crop.recycle()
                        bitmap.recycle()
                        processing.set(false)
                        return@addOnSuccessListener
                    }
                    // Secilen alan altyaziyi kacirdiysa alt bandi bir kez daha dene.
                    val bandTop = (bitmap.height * 0.72f).toInt().coerceIn(0, bitmap.height - 2)
                    val bandLeft = (bitmap.width * 0.02f).toInt().coerceIn(0, bitmap.width - 2)
                    val bandRight = (bitmap.width * 0.98f).toInt().coerceIn(bandLeft + 2, bitmap.width)
                    val band = try {
                        Bitmap.createBitmap(bitmap, bandLeft, bandTop, bandRight - bandLeft, bitmap.height - bandTop)
                    } catch (_: Exception) { null }
                    if (band == null) {
                        if (ocrCrop !== crop) ocrCrop.recycle()
                        crop.recycle()
                        bitmap.recycle()
                        processing.set(false)
                        return@addOnSuccessListener
                    }
                    val bandOcr = upscaleForOcr(band)
                    recognizer.process(InputImage.fromBitmap(bandOcr, 0))
                        .addOnCompleteListener { t ->
                            try {
                                if (t.isSuccessful) t.result?.let { r -> handleResult(r) }
                            } catch (_: Exception) {}
                            if (bandOcr !== band) bandOcr.recycle()
                            band.recycle()
                            if (ocrCrop !== crop) ocrCrop.recycle()
                            crop.recycle()
                            bitmap.recycle()
                            processing.set(false)
                        }
                }
                .addOnFailureListener { e ->
                    LogStore.append(this, "HATA", "OCR hatası: ${e.localizedMessage}")
                    if (ocrCrop !== crop) ocrCrop.recycle()
                    crop.recycle()
                    bitmap.recycle()
                    processing.set(false)
                }
        } catch (e: Exception) {
            bitmap.recycle()
            processing.set(false)
            LogStore.append(this, "HATA", "Kare işlenemedi: ${e.localizedMessage}")
        }
    }

    private fun cleanupCandidate(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        // Key-cap tags and their brackets only; dialogue words stay intact.
        t = buttonPromptRegex.replace(t, " ")
        t = t.replace(Regex("\\s*[|\\u00b7\\u2022]\\s*"), " ")
        t = t.replace(Regex("\\s*[()\\[\\]]+\\s*"), " ").trim()
        // Common OCR slips on thin game fonts.
        t = t.replace(Regex("\\bmny\\b", RegexOption.IGNORE_CASE), "my")
        t = t.replace(Regex("\\bl'm\\b"), "I'm")
        t = t.replace(Regex("\\blt's\\b"), "It's")
        t = t.replace(Regex("(?<=\\s)l(?=\\s)"), "I")
        t = t.replace(Regex("^[\\s,.;:!\\-\\u2013\\u2014/\\\\]+"), "")
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    private fun looksLikeHudPrompt(raw: String): Boolean {
        if (!prefBool("hud_filter", true)) return false
        val lower = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (ratingChromeRegex.containsMatchIn(lower)) return true
        // Video/app chrome and titles (YouTube etc.) that should not be translated as subtitles.
        if (Regex("\\b(diger videolar|more videos|recommended|subscribe|abone ol|izlenme|live chat|comments?|yorumlar)\\b").containsMatchIn(lower)) return true
        if (lower.contains("extended look") || lower.contains("official trailer") || lower.contains("official teaser")) return true
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val dialogueCount = tokens.count { it in englishDialogueMarkers }
        val endsSentence = Regex("[.!?\\u2026]\\s*$").containsMatchIn(raw)
        // "OPEN (L2)", "STUDY RS", "DOG (L2)": a key cap glued to a short label is chrome.
        if (buttonPromptRegex.containsMatchIn(raw) && tokens.size <= 5) return true
        // Pure menu/button chrome made only of UI words.
        val uiCount = tokens.count { it in uiWords }
        if (tokens.size <= 4 && uiCount >= 2 && dialogueCount == 0 && !endsSentence) return true
        // Shouty HUD labels or title-like short lines; CAPS lines that read as a real sentence are kept.
        val letters = raw.count { it.isLetter() }
        val upper = raw.count { it.isUpperCase() }
        if (letters >= 6 && upper > letters * 0.8 && tokens.size <= 8 && dialogueCount < 2 && !endsSentence) return true
        // Channel/metadata blobs with handles.
        if (lower.contains("@") && tokens.size <= 12 && dialogueCount < 2) return true
        return false
    }

    private fun pickBestSubtitleCandidate(lines: List<String>, joined: String): String? {
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        val all = lines + joined
        for ((i, raw) in all.withIndex()) {
            val c = cleanupCandidate(raw)
            if (c.isBlank() || !isLikelySubtitle(c)) continue
            var score = scoreSubtitle(c)
            // Altyazi kirpilan alanin altinda durur; alttaki satirlar daha degerli.
            if (i < lines.size) score += i * 6 else score -= 10
            // Uzun baslik/afis yazilari altyazi sayilmaz.
            if (c.length > 100) score -= 25
            if (c.length > 140) score -= 25
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (bestScore < 12) return null
        return best
    }

    private fun isLikelySubtitle(raw: String): Boolean {
        val t = raw.trim()
        if (t.length < 2) return false
        if (looksLikeHudPrompt(raw)) return false
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val letters = t.count { it.isLetter() }
        val digits = t.count { it.isDigit() }
        if (letters < 2) return false
        if (digits > letters) return false
        if (words.size == 1 && t.length < 4) return false
        if (!Regex("[A-Za-z]").containsMatchIn(t)) return false
        val lower = Normalizer.normalize(t, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val dialogue = lower.split(Regex("[^a-z0-9']+")).count { it in englishDialogueMarkers }
        return dialogue > 0 || t.any { it in ".!?,'\"" } || t.count { it == ' ' } >= 1
    }

    private fun scoreSubtitle(text: String): Int {
        val t = text.trim()
        val lower = Normalizer.normalize(t, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val tokens = lower.split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val dialogue = tokens.count { it in englishDialogueMarkers }
        val uiHits = tokens.count { it in uiWords }
        val punct = t.count { it in ".!?,;:'\"-" }
        val letters = t.count { it.isLetter() }
        val digits = t.count { it.isDigit() }
        val wordCount = words.size
        var score = 0
        score += dialogue * 14
        score += min(wordCount, 14) * 5
        score += punct * 5
        score += min(t.length, 120) / 2
        score += if (t.any { it.isLowerCase() }) 6 else 0
        score += if (t.any { it in ".!?" }) 8 else 0
        score += if (letters >= digits) 6 else -10
        score -= uiHits * 6
        score -= if (wordCount == 1) 12 else 0
        score -= if (t == t.uppercase(Locale.ROOT) && wordCount <= 4) 12 else 0
        if (wordCount >= 3 && dialogue >= 1) score += 10
        if (wordCount >= 5) score += 8
        // Title/caption-chrome penalties: mixed metadata, handles.
        if (lower.contains("@") || lower.contains("diger video") || lower.contains("more videos")) score -= 18
        if (lower.contains("extended look") || lower.contains("trailer") || lower.contains("teaser")) score -= 10
        // Prefer sentence-like lines over title blobs.
        if (wordCount >= 4 && punct == 0 && dialogue == 0) score -= 8
        // Very noisy OCR (many 1-char tokens / symbols) is unreliable.
        val tiny = words.count { it.length <= 1 }
        if (tiny >= 3) score -= 12
        return score
    }

    // Altyazi bandini kendisi bulur: yazi satirlari yuksek kontrastli gecisler uretir.
    private fun detectSubtitleBand(bmp: Bitmap): IntArray? {
        val w = bmp.width
        val h = bmp.height
        if (w < 80 || h < 120) return null
        val stepX = max(1, w / 160)
        val cols = (w - 1) / stepX
        if (cols < 24) return null
        val stepY = max(1, h / 300)
        val scores = IntArray(h)
        val lum = IntArray(cols + 1)
        var y = 0
        while (y < h) {
            var x = 0
            var i = 0
            while (i <= cols) {
                val px = bmp.getPixel(if (x >= w) w - 1 else x, y)
                lum[i] = (((px shr 16) and 0xFF) * 299 + ((px shr 8) and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
                i++
                x += stepX
            }
            var t = 0
            for (k in 1..cols) if (abs(lum[k] - lum[k - 1]) > 45) t++
            scores[y] = t
            y += stepY
        }
        // Altyazi genelde ekranin alt yarisinda; baslik/menu karisikligini azaltmak icin ust kismi atla.
        val window = (h * 0.09f).toInt().coerceIn(28, 160)
        val rowsInWindow = max(1, window / stepY)
        val minScore = max(8, (cols * 0.10f * rowsInWindow).toInt())
        val from = (h * 0.25f).toInt()
        val to = h - window - 1
        if (to <= from) return null
        var bestStart = -1
        var bestScore = 0
        var yy = from
        while (yy <= to) {
            var sum = 0
            var k = 0
            while (k < window) {
                sum += scores[yy + k]
                k += stepY
            }
            // En alt kenardaki arayuz cubugu altyazidan daha parlak olabilir: hafif ceza.
            val center = yy + window / 2
            val adjusted = if (center > (h * 0.93f).toInt()) (sum * 3) / 4 else sum
            if (adjusted > bestScore) {
                bestScore = adjusted
                bestStart = yy
            }
            yy += stepY
        }
        if (bestStart < 0 || bestScore < minScore) return null
        val pad = (window * 0.55f).toInt()
        val top = (bestStart - pad).coerceIn(0, h - 8)
        val bottom = (bestStart + window + pad).coerceIn(top + 8, h)
        return intArrayOf(top, bottom)
    }

    // Ayni bandi kisa sure tekrar kullan; bos sonuc gelirse bir sonraki karede yeniden arar.
    private fun cachedSubtitleBand(bmp: Bitmap): IntArray? {
        val now = System.currentTimeMillis()
        val cached = cachedBand
        if (cached != null && cachedBandWidth == bmp.width && now - cachedBandAt < 1200L) return cached
        val band = detectSubtitleBand(bmp)
        cachedBand = band
        cachedBandAt = now
        cachedBandWidth = bmp.width
        return band
    }

    // Kucuk kirpintilari OCR icin buyut; oyun altyazisi aksi halde minicik kaliyor.
    private fun upscaleForOcr(src: Bitmap): Bitmap {
        val factor = when {
            src.height < 200 -> 3f
            src.height < 380 -> 2f
            else -> 1f
        }
        if (factor <= 1f) return src
        return try {
            Bitmap.createScaledBitmap(
                src,
                max(16, (src.width * factor).toInt()),
                max(16, (src.height * factor).toInt()),
                true
            )
        } catch (_: Exception) { src }
    }

    private fun prefString(key: String, def: String): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, def) ?: def

    private fun keepTerms(): List<String> {
        val raw = prefString("glossary_keep", "")
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim().trim(',', ';') }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedByDescending { it.length }
            .take(80)
            .toList()
    }

    private fun protectTerms(text: String): Pair<String, List<Pair<String, String>>> {
        val terms = keepTerms()
        if (terms.isEmpty()) return text to emptyList()
        var out = text
        val map = ArrayList<Pair<String, String>>(terms.size)
        for ((i, term) in terms.withIndex()) {
            val token = "XNX${i}ZX"
            val pattern = try { Regex("\\b" + Regex.escape(term) + "\\b", RegexOption.IGNORE_CASE) } catch (_: Exception) { null }
            if (pattern == null) continue
            if (pattern.containsMatchIn(out)) {
                map.add(token to term)
                out = pattern.replace(out) { token }
            }
        }
        return out to map
    }

    private fun restoreTerms(text: String, map: List<Pair<String, String>>): String {
        var out = text
        for ((token, term) in map) out = out.replace(token, term, ignoreCase = true)
        return out
    }

    private fun rememberSource(src: String) {
        if (!prefBool("context_memory", true)) return
        val key = Normalizer.normalize(src, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
        if (key.length < 2) return
        synchronized(recentSources) {
            if (recentSources.lastOrNull()?.let { Normalizer.normalize(it, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim() } == key) return
            recentSources.addLast(src)
            while (recentSources.size > 8) recentSources.removeFirst()
        }
    }

    private fun normalizeCmp(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun mergeCandidate(raw: String): String {
        val c = cleanupCandidate(raw).replace(Regex("\\s+"), " ").trim()
        if (c.isBlank()) return c
        val prev = lastSource.trim()
        if (prev.length < 4) return c
        val cn = normalizeCmp(c)
        val pn = normalizeCmp(prev)
        if (cn == pn) return c
        // OCR bazen cumleyi parca parca veya uzatarak okur: daha tam halini sec.
        if (cn.startsWith(pn) || pn.startsWith(cn)) return if (c.length >= prev.length) c else prev
        if (cn.length >= 10 && pn.length >= 10) {
            val tail = pn.takeLast(min(18, pn.length))
            val head = cn.take(min(18, cn.length))
            if (cn.contains(tail) || pn.contains(head)) return if (c.length >= prev.length) c else prev
        }
        return c
    }

    private fun handleResult(result: Text): Boolean {
        val now = System.currentTimeMillis()
        val lines = result.textBlocks.flatMap { b -> b.lines.mapNotNull { it.text?.trim() } }
            .filter { it.isNotBlank() }
        val joined = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        var candidate = pickBestSubtitleCandidate(lines, joined)
        if (candidate != null) candidate = mergeCandidate(candidate)
        if (candidate.isNullOrBlank()) {
            // keep showing the current subtitle until clear_ms expires; do not spam "not found"
            if (now - lastNotifyAt > 8000L && !showingSubtitle) {
                LogStore.append(this, "OKU", if (joined.isBlank()) "Karede metin yok" else "Okunamadi: ${joined.take(80)}")
                // Kutuyu ekranda tutup altyazi bandini kapatmayalim: gizle, bildirimden haber ver.
                overlay?.hide()
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification("Altyazı bekleniyor… Oyun altyazı çıkınca çeviri görünecek."))
                } catch (_: Exception) {}
                lastNotifyAt = now
            }
            return false
        }
        val cmp = normalizeCmp(candidate)
        val shownNorm = Normalizer.normalize(lastShownText, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
        if (shownNorm.length >= 4 && cmp == shownNorm) return false
        if (cmp.any { it in "\u015f\u011f\u0131\u015e\u011e\u0130" }) return false
        val lastCmp = normalizeCmp(lastSource)
        val isSame = lastCmp.isNotEmpty() && (cmp == lastCmp)
        if (isSame && showingSubtitle && now - lastShownAt < 4500L) return false
        val isExtension = lastCmp.length >= 6 && cmp.length > lastCmp.length && (cmp.startsWith(lastCmp) || lastCmp.startsWith(cmp))
        // Yeni ve farkli bir satirsa bir kare daha gorup titremeyi azalt (uzayan cumle hariç).
        if (!isSame && !isExtension && prefBool("stabilize", true)) {
            if (normalizeCmp(stableText) == cmp) {
                stableCount += 1
            } else {
                stableText = candidate
                stableCount = 1
                stableAt = now
            }
            val stableEnough = stableCount >= 2 || (now - stableAt >= 700L && candidate.length >= 8)
            if (!stableEnough) return false
        }
        stableText = ""; stableCount = 0
        lastPartialKey = if (candidate.length >= 8) cmp.take(min(24, cmp.length)) else cmp
        lastPartialAt = now
        // Mark presence immediately so clear_ms is measured from recognition, not translation finish.
        lastSubtitleAt = now
        showingSubtitle = true
        LogStore.append(this, "OKU", "EN: $candidate")
        handleSubtitle(candidate)
        return true
    }

    private fun handleSubtitle(raw: String) {
        val cleaned = cleanupCandidate(raw)
        if (cleaned.isBlank()) return
        var src = cleaned.replace(Regex("\\s+"), " ").trim()
        val maxChars = prefInt("max_chars", 120).coerceIn(30, 300)
        if (src.length > maxChars) {
            val cut = src.take(maxChars)
            val ws = cut.lastIndexOf(' ')
            src = (if (ws > 24) cut.substring(0, ws) else cut).trimEnd() + "…"
        }
        val (protectedSrc, termMap) = protectTerms(src)
        val mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("translation_mode", "device") ?: "device"
        val key = "$mode|${src.lowercase(Locale.ROOT)}"
        cacheGet(key)?.let { cached ->
            if (cached.isNotBlank() && cached != lastShownText) {
                lastSource = src
                lastShownText = cached
                lastShownAt = System.currentTimeMillis()
                lastSubtitleAt = lastShownAt
                showingSubtitle = true
                rememberSource(src)
                overlay?.show(cached, src)
                maybeSpeak(cached)
                LogStore.append(this, "CEVIRI", "EN: $src || TR(${if (mode == "online") "internet" else "cihaz"}): $cached")
            }
            return
        }
        // Kuyruga korunmus metni ve kaynak metni birlikte koy: "src\n--NX--\nprotected"
        val payload = if (termMap.isEmpty()) src else (src + "\n--NX--\n" + protectedSrc)
        if (!pendingQueue.offer(payload)) {
            pendingQueue.poll()
            pendingQueue.offer(payload)
        }
        drainPending(mode)
    }

    private fun cacheGet(key: String): String? = synchronized(cacheLock) { translationCache[key] }

    private fun cachePut(key: String, value: String) = synchronized(cacheLock) {
        if (translationCache.size > 400) {
            val it = translationCache.keys.iterator()
            var n = 0
            while (it.hasNext() && n < 80) { it.next(); it.remove(); n++ }
        }
        translationCache[key] = value
    }

    private fun drainPending(mode: String) {
        if (translationBusy) return
        val payload = pendingQueue.poll() ?: return
        val parts = payload.split("\n--NX--\n", limit = 2)
        val src = parts[0]
        val toTranslate = if (parts.size == 2) parts[1] else src
        val (_, termMap) = protectTerms(src)
        val remember = { text: String ->
            restoreTerms(text, termMap)
        }
        if (mode == "online" || mode == "ai") {
            translationBusy = true
            Thread {
                try {
                    val out = if (mode == "ai") {
                        val ai = translateWithAi(toTranslate)
                        if (ai == null) {
                            LogStore.append(this, "HATA", "Yapay zekâ çevirisi alınamadı, otomatik çeviriye geçildi")
                            translateOnline(toTranslate)
                        } else ai
                    } else translateOnline(toTranslate)
                    workerHandler?.post {
                        if (!out.isNullOrBlank()) {
                            val finalOut = remember(out)
                            val k = "$mode|${src.lowercase(Locale.ROOT)}"
                            cachePut(k, finalOut)
                            if (finalOut != lastShownText) {
                                lastSource = src
                                lastShownText = finalOut
                                lastShownAt = System.currentTimeMillis()
                                lastSubtitleAt = lastShownAt
                                showingSubtitle = true
                                rememberSource(src)
                                overlay?.show(finalOut, src)
                                maybeSpeak(finalOut)
                                LogStore.append(this, "CEVIRI", "EN: $src || TR(" + (if (mode == "ai") "yapay zekâ" else "internet") + "): $finalOut")
                            }
                        } else {
                            LogStore.append(this, "HATA", "\u0130nternet \u00e7evirisi bo\u015f d\u00f6nd\u00fc: $src")
                            if (System.currentTimeMillis() - lastNotifyAt > 8000L) {
                                lastNotifyAt = System.currentTimeMillis()
                                updateStatus("\u00c7eviri al\u0131namad\u0131. \u0130nternet ba\u011flant\u0131s\u0131n\u0131 kontrol et ya da Ayarlar'dan \u00e7evrimd\u0131\u015f\u0131 modu se\u00e7.")
                            }
                        }
                        translationBusy = false
                        if (pendingQueue.isNotEmpty()) drainPending(mode)
                    }
                } catch (_: Exception) {
                    workerHandler?.post {
                        translationBusy = false
                        if (pendingQueue.isNotEmpty()) drainPending(mode)
                    }
                }
            }.start()
            return
        }
        val tr = translator
        if (!translatorReady || tr == null) {
            if (System.currentTimeMillis() - lastNotifyAt > 5000L) {
                updateStatus("Çeviri modeli hazırlanıyor… İlk kurulumda indirme gerekir.")
                lastNotifyAt = System.currentTimeMillis()
            }
            // keep the line queued for a later attempt
            pendingQueue.offer(payload)
            return
        }
        translationBusy = true
        tr.translate(toTranslate)
            .addOnSuccessListener { out ->
                if (!out.isNullOrBlank()) {
                    val finalOut = remember(out)
                    val k = "device|${src.lowercase(Locale.ROOT)}"
                    cachePut(k, finalOut)
                    if (finalOut != lastShownText) {
                        lastSource = src
                        lastShownText = finalOut
                        lastShownAt = System.currentTimeMillis()
                        lastSubtitleAt = lastShownAt
                        showingSubtitle = true
                        rememberSource(src)
                        overlay?.show(finalOut, src)
                        maybeSpeak(finalOut)
                        LogStore.append(this, "CEVIRI", "EN: $src || TR(cihaz): $finalOut")
                    }
                }
                translationBusy = false
                if (pendingQueue.isNotEmpty()) drainPending(mode)
            }
            .addOnFailureListener { e ->
                LogStore.append(this, "HATA", "Çeviri hatası: ${e.localizedMessage}")
                translationBusy = false
                if (pendingQueue.isNotEmpty()) drainPending(mode)
            }
    }

    private fun translateOnline(source: String): String? {
        // Primary: Google translate_a/single (gtx) – keyless, direct, subtitle-friendly.
        // Fallback: MyMemory. Only the subtitle line is sent; no account, no key.
        requestGoogle(source)?.let { if (isUsableTranslation(source, it)) return it }
        requestMyMemory(source)?.let { if (isUsableTranslation(source, it)) return it }
        return null
    }

    // ---- Yapay zeka cevirisi (Groq, ucretsiz plan; anahtar Ayarlar'dan girilir) ----
    private fun recentContext(): String {
        if (!prefBool("context_memory", true)) return ""
        val lines = synchronized(recentSources) { recentSources.toList() }
        return lines.takeLast(3).joinToString(" | ")
    }

    private fun aiKey(): String =
        (getSharedPreferences(PREFS, MODE_PRIVATE).getString("ai_key", "") ?: "").trim()

    private fun aiModel(): String {
        val m = (getSharedPreferences(PREFS, MODE_PRIVATE).getString("ai_model", "") ?: "").trim()
        return if (m.isBlank()) AI_DEFAULT_MODEL else m
    }

    private fun translateWithAi(source: String): String? {
        val key = aiKey()
        if (key.isBlank()) return null
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val system = buildString {
            append("Sen profesyonel bir altyazi cevirmenisin. Ingilizce replikleri dogal, akici ve konusulan Turkce'ye cevir. ")
            append("Konusma dilini, tonu, argoyu, espriyi ve hakareti oldugu gibi koru; kelimesi kelimesine ceviri yapma, ")
            append("cumleyi Turkce'de nasil soyleniyorsa oyle yaz. Kisa tut, ekranda tek satirda okunmali. ")
            append("Sadece ceviriyi yaz; tirnak, etiket, aciklama veya not ekleme.")
            val ctx = recentContext()
            if (ctx.isNotBlank()) append("\nOnceki replikler (sadece baglam icin, onlari cevirme):\n").append(ctx)
            val terms = (prefs.getString("glossary_keep", "") ?: "").trim()
            if (terms.isNotBlank()) append("\nSu isim/terimleri aynen koru: ").append(terms.replace("\n", ", "))
        }
        val body = JSONObject().apply {
            put("model", aiModel())
            put("temperature", 0.3)
            put("max_tokens", 400)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", source))
            })
        }
        val headers = mapOf("Authorization" to "Bearer $key")
        val resp = httpPostJson(AI_URL, body.toString(), headers, 9000) ?: return null
        return try {
            val json = JSONObject(resp)
            val err = json.optJSONObject("error")?.optString("message", "") ?: ""
            if (err.isNotBlank()) {
                LogStore.append(this, "HATA", "AI hatasi: " + err.take(120))
                return null
            }
            val text = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: ""
            val out = cleanAiOutput(text)
            if (out.isBlank() || !isUsableTranslation(source, out)) null else out
        } catch (e: Exception) {
            LogStore.append(this, "HATA", "AI cevirisi okunamadi: ${e.localizedMessage}")
            null
        }
    }

    private fun cleanAiOutput(raw: String): String {
        var t = raw.replace("\r", "").replace("**", "").trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").removeSuffix("```").trim()
        }
        val first = t.split("\n").firstOrNull { it.isNotBlank() }?.trim() ?: ""
        var out = if (first.isNotBlank()) first else t
        for (p in listOf("Turkce:", "Türkçe:", "Ceviri:", "Çeviri:", "Translation:")) {
            if (out.startsWith(p, ignoreCase = true)) out = out.substring(p.length).trim()
        }
        while (out.startsWith("\"") || out.startsWith("'") || out.startsWith("“")) out = out.substring(1).trim()
        while (out.endsWith("\"") || out.endsWith("'") || out.endsWith("”")) out = out.dropLast(1).trim()
        return out
    }

    private fun httpPostJson(urlStr: String, body: String, headers: Map<String, String>, timeoutMs: Int): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs + 2000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "$APP_NAME/1.0")
            setRequestProperty("Accept", "application/json")
        }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText)
            when {
                code == 429 -> {
                    LogStore.append(this, "HATA", "AI gunluk/dakikalik siniri doldu (429)")
                    null
                }
                code == 401 -> {
                    LogStore.append(this, "HATA", "AI anahtari gecersiz (401)")
                    null
                }
                text.isNullOrBlank() -> null
                else -> text
            }
        } catch (e: Exception) {
            LogStore.append(this, "HATA", "AI baglanti hatasi: ${e.localizedMessage}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs + 1000
            setRequestProperty("User-Agent", "$APP_NAME/1.0")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    private fun requestGoogle(source: String): String? {
        return try {
            val q = URLEncoder.encode(source, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=$q"
            val body = httpGet(url, 3500) ?: return null
            val arr = JSONArray(body)
            val sentences = arr.optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val seg = sentences.optJSONArray(i)?.optString(0, "") ?: ""
                sb.append(seg)
            }
            val out = sb.toString().trim()
            if (out.isBlank()) null else out
        } catch (e: Exception) {
            LogStore.append(this, "HATA", "Google çeviri hatası: ${e.localizedMessage}")
            null
        }
    }

    private fun requestMyMemory(source: String): String? {
        return try {
            val q = URLEncoder.encode(source, "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$q&langpair=en|tr&de=noreply@gamelingopro.app"
            val body = httpGet(url, 4500) ?: return null
            val json = JSONObject(body)
            val status = json.optInt("responseStatus", 0)
            val translated = json.optJSONObject("responseData")?.optString("translatedText", "") ?: ""
            // No masking/censorship: the service answer is shown as returned (decoded).
            val cleaned = translated
                .replace("&#10;", "\n").replace("&#13;", "")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&amp;", "&").trim()
            if (status == 200 && isUsableTranslation(source, cleaned)) cleaned else null
        } catch (e: Exception) {
            LogStore.append(this, "HATA", "İnternet çeviri hatası: ${e.localizedMessage}")
            null
        }
    }

    private fun isUsableTranslation(source: String, out: String): Boolean {
        if (out.isBlank()) return false
        if (out.equals(source, ignoreCase = true)) return false
        val upper = out.uppercase(Locale.ROOT)
        // Quota/limit notices come back looking like translations.
        if (upper.contains("MYMEMORY WARNING") || upper.contains("QUERY LENGTH LIMIT") ||
            upper.contains("PLEASE SELECT TWO DISTINCT") || upper.contains("INVALID LANGUAGE PAIR")) return false
        // An untouched ALL-CAPS echo means nothing was actually translated.
        if (out == upper && source != source.uppercase(Locale.ROOT)) return false
        return true
    }

    private fun applyTtsSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val wanted = prefs.getString("tts_voice", "") ?: ""
        try {
            if (wanted.isNotBlank() && Build.VERSION.SDK_INT >= 21) {
                val v = tts?.voices?.firstOrNull { it.name == wanted }
                if (v != null) tts?.voice = v
            }
            val rate = prefs.getFloat("tts_rate", 1.0f).coerceIn(0.6f, 1.4f)
            tts?.setSpeechRate(rate)
        } catch (_: Exception) {}
    }

    private fun maybeSpeak(text: String) {
        val enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("tts_enabled", false)
        if (!enabled || !ttsReady || text.isBlank() || text == lastSpoken) return
        if (text.startsWith("Hazır") || text.startsWith("Çeviri") || text.startsWith("Ekran") || text.startsWith("Kaynak") || text.startsWith("OCR")) return
        lastSpoken = text
        try {
            applyTtsSettings()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nexora_sub")
        } catch (_: Exception) {}
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
        val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { cropped ->
            if (cropped !== bitmap) bitmap.recycle()
        }
    } catch (e: Exception) { LogStore.append(this, "HATA", "Kare dönüştürülemedi: ${e.localizedMessage}"); null }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "NEXORA altyazı servisi", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String = "Oyun altyazıları algılanıyor ve çevriliyor"): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(APP_NAME)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    override fun onDestroy() {
        running = false
        LogStore.append(this, "DURUM", "Servis durduruluyor")
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
        try { translator?.close() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        worker?.quitSafely()
        overlay?.hide()
        translator = null
        projection = null
        virtualDisplay = null
        reader = null
        overlay = null
        worker = null
        workerHandler = null
        tts = null
        super.onDestroy()
    }

    private fun prefBool(key: String, def: Boolean): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, def)

    private fun prefInt(key: String, def: Int): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(key, def)

    override fun onBind(intent: Intent?): IBinder? = null
}
