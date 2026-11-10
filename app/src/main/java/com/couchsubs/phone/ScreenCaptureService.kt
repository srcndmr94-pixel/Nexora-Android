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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
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
            "the","and","you","your","that","this","with","what","when","where","who","why","how",
            "are","was","were","have","has","had","will","would","can","could","should","dont","can't",
            "won't","didn't","isn't","aren't","there","their","they","them","then","than","from","into",
            "about","just","like","know","want","need","take","give","make","find","look","come","going",
            "lets","let's","i'm","im","it's","its","i","me","my","we","our","he","she","it","his","her",
            "to","of","in","on","at","for","as","be","do","not","but","if","or","so","no","yes","up","out",
            "all","get","got","see","now","here","go","back","open","close","help","wait","stop","run"
        )
        // Menu/chrome words used only as a combined short-label signal, never to strip from a sentence.
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
    @Volatile private var translationBusy = false
    @Volatile private var lastNotified = ""
    @Volatile private var lastNotifyAt = 0L
    private val pendingQueue = LinkedBlockingQueue<String>(4)
    private val translationCache = ConcurrentHashMap<String, String>()
    private val cacheLock = Any()

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
                applyTtsSettings()
            } catch (_: Exception) { ttsReady = false }
        }
        LogStore.append(this, "DURUM", "Sesli okuma ${if (ttsReady) "hazır" else "kullanılamıyor"}")
    }

    override fun onCreate() {
        super.onCreate()
        LogStore.append(this, "DURUM", "Servis oluşturuluyor")
        overlay = SubtitleOverlay(this)
        createNotificationChannel()
        try { tts = TextToSpeech(this, this) } catch (_: Exception) {}
        worker = HandlerThread("$APP_NAME-OCR").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        ensureTranslator()
    }

    private fun ensureTranslator() {
        val mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("translation_mode", "device") ?: "device"
        if (mode == "online") {
            translatorReady = false
            LogStore.append(this, "DURUM", "İnternet çeviri modu seçildi")
            return
        }
        if (translator != null && translatorReady) return
        LogStore.append(this, "DURUM", "Çeviri modeli hazırlanıyor")
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        translator = Translation.getClient(options)
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                translatorReady = true
                LogStore.append(this, "DURUM", "Çeviri modeli hazır")
            }
            ?.addOnFailureListener { e ->
                translatorReady = false
                val msg = "Çeviri modeli indirilemedi: ${e.localizedMessage ?: "hata"}"
                LogStore.append(this, "HATA", msg)
                updateStatus("$msg. Ayarlar'dan modeli indirip tekrar deneyin.")
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            LogStore.append(this, "HATA", "Başlatma verisi alınamadı")
            updateStatus("Başlatma verisi alınamadı. Uygulamadan tekrar başlatın.")
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode == 0 || data == null) {
            LogStore.append(this, "HATA", "Ekran yakalama izni eksik veya iptal")
            updateStatus("Ekran yakalama izni alınamadı.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("$APP_NAME hazırlanıyor…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("$APP_NAME hazırlanıyor…"))
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
            DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        }
        val captureScale = (prefInt("capture_scale", 70).coerceIn(40, 100)) / 100f
        val rawW = max(480, (metrics.widthPixels * captureScale).toInt())
        val rawH = max(270, (metrics.heightPixels * captureScale).toInt())
        val maxDim = when (prefInt("quality", 1)) { 0 -> 720; 2 -> 1280; else -> 960 }
        val longSide = max(rawW, rawH).toFloat()
        val down = if (longSide > maxDim) maxDim / longSide else 1f
        val width = max(480, (rawW * down).toInt())
        val height = max(270, (rawH * down).toInt())
        val density = metrics.densityDpi
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        virtualDisplay?.release()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                if (!processing.compareAndSet(false, true)) { image.close(); return@setOnImageAvailableListener }
                val bmp = imageToBitmap(image)
                image.close()
                if (bmp != null) workerHandler?.post { processFrame(bmp) } else processing.set(false)
            } catch (_: Exception) {
                try { image.close() } catch (_: Exception) {}
                processing.set(false)
            }
        }, workerHandler)
        virtualDisplay = projection?.createVirtualDisplay(
            "$APP_NAME-Capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, workerHandler
        )
        LogStore.append(this, "DURUM", "Yakalama ${width}x${height} ölçekte hazır")
    }

    private fun processFrame(bitmap: Bitmap) {
        try {
            val now = System.currentTimeMillis()
            val idle = prefInt("idle_ms", 1400)
            val clearMs = prefInt("clear_ms", 2200).coerceIn(800, 8000)
            if (showingSubtitle && now - lastSubtitleAt > clearMs) {
                lastShownText = ""
                lastSource = ""
                showingSubtitle = false
                overlay?.show("")
            }
            if (now - lastFrameAt < idle) return
            val region = (getSharedPreferences(PREFS, MODE_PRIVATE).getString("region", "bottom") ?: "bottom").lowercase(Locale.ROOT)
            val (l, t, r, b) = when (region) {
                "top" -> floatArrayOf(0.05f, 0.03f, 0.95f, 0.28f)
                "middle", "center", "orta" -> floatArrayOf(0.05f, 0.32f, 0.95f, 0.68f)
                "full" -> floatArrayOf(0.02f, 0.02f, 0.98f, 0.98f)
                else -> floatArrayOf(0.06f, 0.62f, 0.94f, 0.94f)
            }
            val left = (bitmap.width * l).toInt().coerceIn(0, bitmap.width - 2)
            val top = (bitmap.height * t).toInt().coerceIn(0, bitmap.height - 2)
            val right = (bitmap.width * r).toInt().coerceIn(left + 2, bitmap.width)
            val bottom = (bitmap.height * b).toInt().coerceIn(top + 2, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val sampleW = max(1, crop.width / 24)
            val sampleH = max(1, crop.height / 12)
            var sig = 1469598103934665603UL
            for (y in 0 until crop.height step sampleH) {
                for (x in 0 until crop.width step sampleW) {
                    val p = crop.getPixel(x, y)
                    sig = sig xor (((p shr 16) and 0xff).toULong())
                    sig *= 1099511628211UL
                    sig = sig xor (((p shr 8) and 0xff).toULong())
                    sig *= 1099511628211UL
                    sig = sig xor ((p and 0xff).toULong())
                    sig *= 1099511628211UL
                }
            }
            val frameHash = sig.toLong()
            val forceRefresh = prefBool("force_refresh", true)
            if (!forceRefresh && frameHash == lastFrameHash && now - lastFrameAt < 2500L) {
                crop.recycle()
                bitmap.recycle()
                processing.set(false)
                return
            }
            lastFrameHash = frameHash
            lastFrameAt = now
            val image = InputImage.fromBitmap(crop, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    try { handleResult(result) } finally {
                        crop.recycle()
                        bitmap.recycle()
                        processing.set(false)
                    }
                }
                .addOnFailureListener { e ->
                    LogStore.append(this, "HATA", "OCR hatası: ${e.localizedMessage}")
                    crop.recycle()
                    bitmap.recycle()
                    processing.set(false)
                }
        } catch (e: Exception) {
            LogStore.append(this, "HATA", "Kare işlenemedi: ${e.localizedMessage}")
            try { bitmap.recycle() } catch (_: Exception) {}
            processing.set(false)
        }
    }

    // Only key-cap chrome (e.g. "(L2)", "[X]", "L1", "RB") is treated as UI noise.
    // Ordinary words like "open", "return" or "call" are part of the sentence and
    // must never be stripped, otherwise the translator receives broken input.
    private val buttonPromptRegex = Regex(
        """(?:\(\s*(?:L[123]|R[123]|LB|RB|LT|RT|LS|RS|[XYAB])\s*\)|\[\s*(?:L[123]|R[123]|LB|RB|LT|RT|[XYAB])\s*\]|\b(?:L[123]|R[123])\b|\b(?:LB|RB|LT|RT)\b)"""
    )
    private val ratingChromeRegex = Regex("(?i)\\b(mature\\s*17\\+|may\\s*contain\\s*content|inappropriate\\s*for\\s*children|esrb\\.org|likely\\s*mature)\\b")

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
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val dialogueCount = tokens.count { it in englishDialogueMarkers }
        val endsSentence = Regex("[.!?\\u2026]\\s*$").containsMatchIn(raw)
        // "OPEN (L2)", "STUDY RS", "DOG (L2)": a key cap glued to a short label is chrome.
        if (buttonPromptRegex.containsMatchIn(raw) && tokens.size <= 5) return true
        // Pure menu/button chrome made only of UI words.
        val uiCount = tokens.count { it in uiWords }
        if (tokens.size <= 4 && uiCount >= 2 && dialogueCount == 0 && !endsSentence) return true
        // Shouty HUD labels; CAPS lines that read as a real sentence are kept.
        val letters = raw.count { it.isLetter() }
        val upper = raw.count { it.isUpperCase() }
        if (letters >= 6 && upper > letters * 0.8 && tokens.size <= 8 && dialogueCount < 2 && !endsSentence) return true
        return false
    }

    private fun pickBestSubtitleCandidate(lines: List<String>, joined: String): String? {
        val parts = (lines + joined).map { cleanupCandidate(it) }.filter { it.isNotBlank() }.distinct()
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (c in parts) {
            if (!isLikelySubtitle(c)) continue
            val score = scoreSubtitle(c)
            if (score > bestScore) { bestScore = score; best = c }
        }
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
        return score
    }

    private fun handleResult(result: Text) {
        val now = System.currentTimeMillis()
        val lines = result.textBlocks.flatMap { b -> b.lines.mapNotNull { it.text?.trim() } }
            .filter { it.isNotBlank() }
        val joined = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val candidate = pickBestSubtitleCandidate(lines, joined)
        if (candidate.isNullOrBlank()) {
            // keep showing the current subtitle until clear_ms expires; do not spam "not found"
            if (now - lastNotifyAt > 6000L && !showingSubtitle) {
                updateStatus("Ekranda altyazı bulunamadı. Ayarlardan alanı veya kaliteyi değiştirin.")
                lastNotifyAt = now
            }
            return
        }
        val key = candidate.lowercase(Locale.ROOT)
        if (key == lastSource && showingSubtitle && now - lastShownAt < 4500L) return
        val partialKey = if (candidate.length >= 8) candidate.lowercase(Locale.ROOT).take(min(24, candidate.length)) else candidate.lowercase(Locale.ROOT)
        if (partialKey == lastPartialKey && now - lastPartialAt < 1200L && key != lastSource) return
        lastPartialKey = partialKey
        lastPartialAt = now
        // Mark presence immediately so clear_ms is measured from recognition, not translation finish.
        lastSubtitleAt = now
        showingSubtitle = true
        handleSubtitle(candidate)
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
        val mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("translation_mode", "device") ?: "device"
        val key = "$mode|${src.lowercase(Locale.ROOT)}"
        cacheGet(key)?.let { cached ->
            if (cached.isNotBlank() && cached != lastShownText) {
                lastSource = src
                lastShownText = cached
                lastShownAt = System.currentTimeMillis()
                lastSubtitleAt = lastShownAt
                showingSubtitle = true
                overlay?.show(cached)
                maybeSpeak(cached)
                LogStore.append(this, "CEVIRI", "EN: $src || TR(${if (mode == "online") "internet" else "cihaz"}): $cached")
            }
            return
        }
        if (!pendingQueue.offer(src)) {
            pendingQueue.poll()
            pendingQueue.offer(src)
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
        val src = pendingQueue.poll() ?: return
        if (mode == "online") {
            translationBusy = true
            Thread {
                try {
                    val out = translateOnline(src)
                    workerHandler?.post {
                        if (!out.isNullOrBlank()) {
                            val k = "online|${src.lowercase(Locale.ROOT)}"
                            cachePut(k, out)
                            if (out != lastShownText) {
                                lastSource = src
                                lastShownText = out
                                lastShownAt = System.currentTimeMillis()
                                lastSubtitleAt = lastShownAt
                                showingSubtitle = true
                                overlay?.show(out)
                                maybeSpeak(out)
                                LogStore.append(this, "CEVIRI", "EN: $src || TR(internet): $out")
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
            pendingQueue.offer(src)
            return
        }
        translationBusy = true
        tr.translate(src)
            .addOnSuccessListener { out ->
                if (!out.isNullOrBlank()) {
                    val k = "device|${src.lowercase(Locale.ROOT)}"
                    cachePut(k, out)
                    if (out != lastShownText) {
                        lastSource = src
                        lastShownText = out
                        lastShownAt = System.currentTimeMillis()
                        lastSubtitleAt = lastShownAt
                        showingSubtitle = true
                        overlay?.show(out)
                        maybeSpeak(out)
                        LogStore.append(this, "CEVIRI", "EN: $src || TR(cihaz): $out")
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
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        result
    } catch (e: Exception) { LogStore.append(this, "HATA", "Kare dönüştürülemedi: ${e.localizedMessage}"); null }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "$APP_NAME ekran yakalama", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String = "Oyun altyazıları algılanıyor ve çevriliyor"): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_nexora)
        .setContentTitle("$APP_NAME çalışıyor")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        running = false
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

    private fun prefBool(key: String, def: Boolean): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, def)

    private fun prefInt(key: String, def: Int): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(key, def)

    override fun onBind(intent: Intent?): IBinder? = null
}
