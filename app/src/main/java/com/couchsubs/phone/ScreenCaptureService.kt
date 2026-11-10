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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "nexora_capture"
        private const val NOTIFICATION_ID = 71
        private const val PREFS = "nexora_overlay"
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
    @Volatile private var lastRawSource = ""
    @Volatile private var lastSourceAt = 0L
    @Volatile private var lastSubtitleAt = 0L
    @Volatile private var showingSubtitle = false
    private val CLEAR_MS = 2800L

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
                applyTtsSettings()
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
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
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
        // Scan the lower half to catch subtitles in different positions; scale down for speed.
        val cropTop = (bitmap.height * 0.45f).toInt()
        val rawCrop = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop)
        val targetW = 960
        val crop = if (rawCrop.width > targetW) {
            val targetH = (rawCrop.height * (targetW.toFloat() / rawCrop.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rawCrop, targetW, targetH, true).also { if (it !== rawCrop) rawCrop.recycle() }
        } else rawCrop
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block -> block.lines }.map { it.text.trim() }
                val joined = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                val text = pickBestSubtitleCandidate(lines, joined)
                if (text != null) {
                    handleSubtitle(text)
                } else {
                    val now = System.currentTimeMillis()
                    if (joined.isNotBlank()) {
                        val sample = joined.take(120)
                        if (now - lastRejectLogAt > 1500L && sample.length > 3) {
                            lastRejectLogAt = now
                            LogStore.append(this, "OCR_ATLANDI", sample)
                        }
                    }
                    lastSource = ""
                    // Clear stuck subtitle a short time after speech/subtitle disappears.
                    if (showingSubtitle && now - lastSubtitleAt > CLEAR_MS) {
                        showingSubtitle = false
                        overlay?.show("")
                    }
                }
            }
            .addOnFailureListener { e -> LogStore.append(this, "HATA", "OCR hatası: ${e.localizedMessage}") }
            .addOnCompleteListener {
                crop.recycle()
                bitmap.recycle()
                processing.set(false)
            }
    }

    private val buttonPromptRegex = Regex(
        """(?ix)
        \b(?:L[123]|R[123]|LB|RB|LT|RT|LS|RS|L\s*STICK|R\s*STICK)\b
        | \b(?:STUDY|PRAISE|SCOLD|PET|KNOCK|OPEN|TAKE|TALK|SEARCH|HOLD|PRESS|PICK\s*UP|DROP\s*OFF|CALL|PHONE|PALM|RETURN|MOUNT|DISMOUNT|SNEAK|EXIT|ENTER)\b \s* (?:\(?\s*(?:L[123]|R[123]|[XYOAB△□○×]|R|L)\s*\)? | \b[A-Z]{1,3}\d?\b)?
        | \bDOG\s*\(?\s*L?\d*\s*\)?
        | (?:^|[ \t])\(?(?:[XYOAB△□○×]|R|L)\)?(?:[ \t]|$)
        """
    )
    private val ratingChromeRegex = Regex("(?i)\\b(mature\\s*17\\+|may\\s*contain\\s*content|inappropriate\\s*for\\s*children|esrb\\.org|likely\\s*mature)\\b")
    private val hudPromptTokens = setOf(
        "l1","l2","l3","r1","r2","r3","lb","rb","lt","rt","ls","rs","x","y","a","b","o","r","l",
        "study","praise","scold","pet","knock","open","take","talk","search","hold","press",
        "pickup","drop","call","phone","palm","return","dog","mount","dismount","sneak","enter","exit"
    )

    private fun cleanupCandidate(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        // Strip leading HUD/OCR noise: ALL-CAPS gibberish tokens, weapon labels, short junk before real text.
        t = t.replace(Regex("^(?:[A-Z0-9]{3,}(?:\\s+[A-Z0-9]{2,}){0,3}\\s+)(?=[A-Za-z'\"-])"), "").trim()
        t = t.replace(Regex("^(?:[mM]\\s+)?[A-Z]{2,}[A-Z0-9]*\\s+R\\d+[A-Z]*\\s+[A-Z]+\\s+(?=[A-Za-z])"), "").trim()
        // Strip embedded/trailing key-prompt chrome (R1, L2, STUDY R, DOG(L2), isolated R, etc.).
        t = buttonPromptRegex.replace(t, " ")
        t = t.replace(Regex("(?i)\\b(?:STUDY|PRAISE|SCOLD|PET|KNOCK|CALL|PHONE|PALM|RETURN|DOG)\\b(?:\\s*\\(?\\s*[A-Z0-9]{1,3}\\s*\\)?)*"), " ")
        t = t.replace(Regex("\\b[LR][123]\\b"), " ")
        t = t.replace(Regex("(?<=\\s)[RrLl](?=\\s|$)"), " ")
        t = t.replace(Regex("\\s*[()\\[\\]]+\\s*"), " ")
        t = t.replace(Regex("^[^A-Za-zÇĞİÖŞÜçğıöşü'\"-]{0,3}"), "").trim()
        // Common OCR typo: isolated "mny" -> "my"
        t = t.replace(Regex("\\bmny\\b", RegexOption.IGNORE_CASE), "my")
        t = t.replace(Regex("\\bm\\s+(?=[aeiou])", RegexOption.IGNORE_CASE), "I'm ")
        // Strip trailing isolated OCR noise tokens/suffixes seen in logs.
        t = t.replace(Regex("(?:\\s+[,.:;|]?\\s*(?:[RrD]|Ri|ri|[A-Z])\\s*[,.:;|]?)+$"), "").trim()
        t = t.replace(Regex("\\s+[|]$"), "").trim()
        t = t.replace(Regex("\\s+\\d{1,2}$"), "").trim() // stray small OCR numbers at end
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    private fun looksLikeHudPrompt(text: String): Boolean {
        val lower = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (ratingChromeRegex.containsMatchIn(lower)) return true
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val promptCount = tokens.count { it in hudPromptTokens }
        val dialogueCount = tokens.count { it in englishDialogueMarkers }
        if (promptCount >= 1 && tokens.size <= 5 && dialogueCount < 2) return true
        if (promptCount >= 2 && dialogueCount < 3) return true
        // Mostly ALL CAPS HUD labels / fragmented OCR.
        val letters = text.count { it.isLetter() }
        val upper = text.count { it.isUpperCase() }
        if (letters >= 6 && upper > letters * 0.72 && dialogueCount < 3 && tokens.size <= 8) return true
        return false
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
        // Fallback: remove prompt chrome from the joined text and re-check.
        val stripped = cleanupCandidate(buttonPromptRegex.replace(joined, " "))
        if (stripped != cleanJoined && isLikelySubtitle(stripped)) return stripped
        return null
    }

    private fun isLikelySubtitle(raw: String): Boolean {
        val text = cleanupCandidate(raw)
        if (text.length < 2 || text.length > 280) return false
        // Ignore timecodes/progress like 0:12, 1:23:45, 12:34 / 56:78.
        if (Regex("\\d{1,2}:\\d{2}").containsMatchIn(text) && text.count { it.isLetter() } < 12) return false
        if (text.matches(Regex("^[\\d:\\s/.,-]+$"))) return false
        if (looksLikeHudPrompt(text)) return false
        val lower = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val compact = lower.replace(Regex("\\s+"), " ")
        // Skip obvious app/chrome/UI overlays.
        if (turkishUiHints.any { compact.contains(it) }) return false
        if (Regex("\\b\\d[\\d.,]*\\s*(b|mn|bin|milyon|k)?\\b.*\\b(izlendi|gün|saat|dakika)\\b").containsMatchIn(compact)) return false
        if (Regex("\\b(sorting|challenge|challengeg|show products|other videos|view\\s*products)\\b").containsMatchIn(compact)) return false
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
        if (letters < 2) return false
        val letterRatio = letters.toFloat() / text.length.coerceAtLeast(1)
        if (letterRatio < 0.4f) return false
        val markerCount = wordsAscii.count { it in englishDialogueMarkers }
        // If it looks Turkish-heavy UI/text and lacks clear English dialogue markers, skip.
        if (hasTurkishChars && markerCount < 1 && text.length > 12) return false
        // Short exclamations / replies (e.g. "No.", "Yo!", "Yeah.", "What?") are valid dialogue.
        if (wordsAscii.size <= 3) {
            val shortOk = Regex("^[A-Za-z' .!?,-]{1,24}$").matches(text)
            return shortOk && !looksLikeHudPrompt(text)
        }
        // Require subtitle/dialogue-like English: enough words and some common markers,
        // or a clear sentence shape.
        val hasSentencePunct = Regex("[.?!]$").containsMatchIn(text) || text.contains('?') || text.contains('!')
        val looksLikeDialogue = markerCount >= 1 || hasSentencePunct || wordsAscii.size >= 4
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

    private fun mergeContextIfUseful(text: String): String {
        val now = System.currentTimeMillis()
        val prev = lastRawSource
        val dt = now - lastSourceAt
        lastRawSource = text
        lastSourceAt = now
        if (prev.isBlank() || dt > 1800L) return text
        // If OCR produced a short fragment that looks like a continuation, prefer the longer/combined form.
        if (text.length < 12 && prev.length >= text.length && prev.lowercase(Locale.ROOT).contains(text.lowercase(Locale.ROOT).take(6))) return prev
        return text
    }

    private fun handleSubtitle(text: String) {
        val merged = mergeContextIfUseful(text)
        val normalized = dedupKey(merged)
        if (isNearDuplicate(normalized, lastSource) || isNearDuplicate(normalized, lastShownSource)) return
        lastSource = normalized
        LogStore.append(this, "KAYNAK", merged)
        if (translating) {
            pendingSource = merged
            return
        }
        translate(merged)
    }

    private fun useOnlineTranslation(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString("translation_mode", "device") == "online"

    private fun translate(source: String) {
        translating = true
        if (useOnlineTranslation()) {
            workerHandler?.post {
                val out = translateOnline(source)
                if (out != null) {
                    onTranslated(source, out, online = true)
                } else {
                    LogStore.append(this, "HATA", "İnternet çevirisi başarısız, cihaz üstü deneniyor: $source")
                    translateOnDevice(source)
                }
            } ?: run { translateOnDevice(source) }
        } else {
            translateOnDevice(source)
        }
    }

    private fun translateOnDevice(source: String) {
        val activeTranslator = translator ?: run {
            translating = false
            updateStatus(source)
            LogStore.append(this, "HATA", "Çevirici yok, kaynak gösterildi: $source")
            return
        }
        if (!translatorReady) {
            translating = false
            pendingSource = source
            updateStatus("Çeviri modeli hazırlanıyor…")
            return
        }
        activeTranslator.translate(source)
            .addOnSuccessListener { translated ->
                if (translated.isNotBlank()) onTranslated(source, translated.trim(), online = false)
                else {
                    translating = false
                    updateStatus(source)
                    LogStore.append(this, "HATA", "Boş çeviri sonucu, kaynak: $source")
                    drainPending(source)
                }
            }
            .addOnFailureListener { e ->
                translating = false
                updateStatus("Çeviri başarısız — kaynak: $source")
                LogStore.append(this, "HATA", "Çeviri başarısız (${e.localizedMessage}): $source")
                drainPending(source)
            }
    }

    private fun onTranslated(source: String, out: String, online: Boolean) {
        lastShownSource = dedupKey(source)
        lastSubtitleAt = System.currentTimeMillis()
        showingSubtitle = true
        updateStatus(out)
        LogStore.append(this, "CEVIRI", "EN: $source || TR(${if (online) "internet" else "cihaz"}): $out")
        translating = false
        drainPending(source)
    }

    private fun drainPending(done: String) {
        val next = pendingSource
        pendingSource = null
        if (!next.isNullOrBlank() && next != done) translate(next)
    }

    private fun translateOnline(source: String): String? {
        // Optional online mode: MyMemory (no key). Text is sent to the service over HTTPS.
        // No masking/censorship is applied; the translation is shown as returned (decoded).
        return try {
            val q = URLEncoder.encode(source, "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$q&langpair=en|tr")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 7000
                setRequestProperty("User-Agent", "NEXORA/1.0")
            }
            conn.inputStream.bufferedReader().use { br ->
                val body = br.readText()
                val json = JSONObject(body)
                val status = json.optInt("responseStatus", 0)
                val translated = json.optJSONObject("responseData")?.optString("translatedText", "") ?: ""
                if (status == 200 && translated.isNotBlank() && !translated.equals(source, ignoreCase = true)) {
                    translated
                        .replace("&#10;", "\n").replace("&#13;", "")
                        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                        .replace("&amp;", "&").trim()
                } else null
            }
        } catch (e: Exception) {
            LogStore.append(this, "HATA", "İnternet çeviri hatası: ${e.localizedMessage}")
            null
        }
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
