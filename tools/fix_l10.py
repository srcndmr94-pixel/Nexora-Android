#!/usr/bin/env python3
"""Add an AI (Groq) translation mode with a user-supplied free API key."""
import io, sys

SVC = "app/src/main/java/com/couchsubs/phone/ScreenCaptureService.kt"
MAIN = "app/src/main/java/com/couchsubs/phone/MainActivity.kt"

def fail(m):
    print("ERROR: " + m); sys.exit(1)

def one(t, old, new, label):
    if t.count(old) != 1:
        fail("target %s count=%d" % (label, t.count(old)))
    return t.replace(old, new, 1)

# ================= Service =================
s = io.open(SVC, encoding="utf-8").read()

s = one(
    s,
    "    private val translationCache = ConcurrentHashMap<String, String>()\n",
    '    private val translationCache = ConcurrentHashMap<String, String>()\n'
    '    private val AI_DEFAULT_MODEL = "openai/gpt-oss-120b"\n'
    '    private val AI_URL = "https://api.groq.com/openai/v1/chat/completions"\n',
    "ai constants",
)

s = one(
    s,
    '        if (mode == "online") {\n'
    '            translatorReady = true\n'
    '            LogStore.append(this, "DURUM", "Ä°nternet Ã§eviri modu seÃ§ildi")\n',
    '        if (mode == "online" || mode == "ai") {\n'
    '            translatorReady = true\n'
    '            LogStore.append(this, "DURUM", if (mode == "ai") "Yapay zekÃ¢ Ã§eviri modu seÃ§ildi" else "Ä°nternet Ã§eviri modu seÃ§ildi")\n',
    "ensureTranslator",
)

s = one(
    s,
    '        if (mode == "online") {\n            translationBusy = true\n',
    '        if (mode == "online" || mode == "ai") {\n            translationBusy = true\n',
    "drainPending condition",
)

s = one(
    s,
    "                    val out = translateOnline(toTranslate)\n",
    '                    val out = if (mode == "ai") {\n'
    '                        val ai = translateWithAi(toTranslate)\n'
    '                        if (ai == null) {\n'
    '                            LogStore.append(this, "HATA", "Yapay zekÃ¢ Ã§evirisi alÄ±namadÄ±, otomatik Ã§eviriye geÃ§ildi")\n'
    '                            translateOnline(toTranslate)\n'
    '                        } else ai\n'
    '                    } else translateOnline(toTranslate)\n',
    "ai dispatch",
)

s = one(
    s,
    '                            val k = "online|${src.lowercase(Locale.ROOT)}"\n',
    '                            val k = "$mode|${src.lowercase(Locale.ROOT)}"\n',
    "ai cache key",
)

s = one(
    s,
    'LogStore.append(this, "CEVIRI", "EN: $src || TR(internet): $finalOut")\n',
    'LogStore.append(this, "CEVIRI", "EN: $src || TR(" + (if (mode == "ai") "yapay zekÃ¢" else "internet") + "): $finalOut")\n',
    "ai log label",
)

AI_FUNCS = '''    // ---- Yapay zeka cevirisi (Groq, ucretsiz plan; anahtar Ayarlar'dan girilir) ----
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
            if (ctx.isNotBlank()) append("\\nOnceki replikler (sadece baglam icin, onlari cevirme):\\n").append(ctx)
            val terms = (prefs.getString("glossary_keep", "") ?: "").trim()
            if (terms.isNotBlank()) append("\\nSu isim/terimleri aynen koru: ").append(terms.replace("\\n", ", "))
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
        var t = raw.replace("\\r", "").replace("**", "").trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").removeSuffix("```").trim()
        }
        val first = t.split("\\n").firstOrNull { it.isNotBlank() }?.trim() ?: ""
        var out = if (first.isNotBlank()) first else t
        for (p in listOf("Turkce:", "TÃ¼rkÃ§e:", "Ceviri:", "Ã‡eviri:", "Translation:")) {
            if (out.startsWith(p, ignoreCase = true)) out = out.substring(p.length).trim()
        }
        while (out.startsWith("\\"") || out.startsWith("'") || out.startsWith("â€œ")) out = out.substring(1).trim()
        while (out.endsWith("\\"") || out.endsWith("'") || out.endsWith("â€")) out = out.dropLast(1).trim()
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

'''
s = one(s, "    private fun httpGet(urlStr: String, timeoutMs: Int): String? {",
        AI_FUNCS + "    private fun httpGet(urlStr: String, timeoutMs: Int): String? {", "ai funcs")
io.open(SVC, "w", encoding="utf-8").write(s)

# ================= MainActivity =================
m = io.open(MAIN, encoding="utf-8").read()

m = one(
    m,
    '        val online = prefs.getString("translation_mode", "device") == "online"\n',
    '        val mode = prefs.getString("translation_mode", "device") ?: "device"\n'
    '        val online = mode == "online" || mode == "ai"\n',
    "status mode",
)

m = one(
    m,
    'row.addView(statusCell(CYAN, if (online) "Ä°nternet Modu" else "Ã‡evrimdÄ±ÅŸÄ± Mod", if (online) "Daha doÄŸal Ã§eviri" else "KotasÄ±z ve kesintisiz"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))\n',
    'row.addView(statusCell(CYAN, if (mode == "ai") "Yapay ZekÃ¢" else if (online) "Ä°nternet Modu" else "Ã‡evrimdÄ±ÅŸÄ± Mod", if (mode == "ai") "En doÄŸal Ã§eviri" else if (online) "Daha doÄŸal Ã§eviri" else "KotasÄ±z ve kesintisiz"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))\n',
    "status text",
)

m = one(
    m,
    "        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))\n        cardView.addView(row)\n        return cardView\n    }\n\n    private fun modeOption(",
    "        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))\n"
    "        cardView.addView(row)\n"
    "        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }\n"
    "        aiRow.addView(modeOption(\n"
    '            "Yapay ZekÃ¢",\n'
    '            "En doÄŸal Ã§eviri. Ãœcretsiz anahtar gerekir, gÃ¼nlÃ¼k sÄ±nÄ±rÄ± vardÄ±r.",\n'
    "            true,\n"
    '            current == "ai"\n'
    "        ) {\n"
    '            prefs.edit().putString("translation_mode", "ai").apply()\n'
    '            toast(if ((prefs.getString("ai_key", "") ?: "").isBlank()) "Ã–nce aÅŸaÄŸÄ±ya anahtarÄ± gir" else "Yapay zekÃ¢ Ã§evirisi")\n'
    "            renderTab()\n"
    "        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))\n"
    "        aiRow.addView(View(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))\n"
    "        cardView.addView(aiRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })\n"
    "        return cardView\n"
    "    }\n\n"
    "    private fun modeOption(",
    "ai mode option",
)

m = one(
    m,
    "        col.addView(modeCard())\n",
    "        col.addView(modeCard())\n        col.addView(aiCard())\n",
    "insert ai card",
)

AI_CARD = '''    private fun aiCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_translate, "Yapay ZekÃ¢ AnahtarÄ±", "Ãœcretsiz anahtarla daha doÄŸal Ã§eviri."))
        cardView.addView(bodyText("1) console.groq.com/keys adresinden Ã¼cretsiz anahtar oluÅŸtur (kart gerekmez).\\n2) AnahtarÄ± aÅŸaÄŸÄ±ya yapÄ±ÅŸtÄ±r ve kaydet.\\n3) Ã‡eviri Modu'ndan â€œYapay ZekÃ¢â€ya seÃ§."))
        val keyInput = EditText(this).apply {
            setText(prefs.getString("ai_key", "") ?: "")
            hint = "gsk_..."
            textSize = 12.5f
            setSingleLine(true)
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bgRound(SURFACE, 12, CARD_LINE)
        }
        cardView.addView(keyInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        val modelInput = EditText(this).apply {
            setText(prefs.getString("ai_model", "") ?: "")
            hint = "Model (boÅŸ bÄ±rak: openai/gpt-oss-120b)"
            textSize = 12f
            setSingleLine(true)
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bgRound(SURFACE, 12, CARD_LINE)
        }
        cardView.addView(modelInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val saved = (prefs.getString("ai_key", "") ?: "").isNotBlank()
        cardView.addView(TextView(this).apply {
            text = if (saved) "Anahtar kayÄ±tlÄ±. Yapay zekÃ¢ Ã§evirisi hazÄ±r." else "HenÃ¼z anahtar yok; Ã§eviri eski yÃ¶ntemle yapÄ±lÄ±r."
            textSize = 12f
            setTextColor(color(if (saved) GREEN else MUTED))
            setPadding(0, dp(8), 0, dp(6))
        })
        cardView.addView(rowOf(
            ghostButton("AnahtarÄ± Kaydet") {
                prefs.edit()
                    .putString("ai_key", keyInput.text.toString().trim())
                    .putString("ai_model", modelInput.text.toString().trim())
                    .apply()
                toast("Kaydedildi")
                renderTab()
            },
            ghostButton("AnahtarÄ± Sil") {
                prefs.edit().remove("ai_key").apply()
                toast("Anahtar silindi")
                renderTab()
            }
        ))
        return cardView
    }

'''
m = one(m, "    private fun glossaryCard(): LinearLayout {", AI_CARD + "    private fun glossaryCard(): LinearLayout {", "ai card func")

m = one(
    m,
    "NEXORA, ekrandaki Ä°ngilizce altyazÄ±larÄ± okur ve TÃ¼rkÃ§eye Ã§evirir. Ã‡eviri tamamen cihaz Ã¼zerinde yapÄ±labilir; internet modunu seÃ§ersen metin Ã§eviri servisine gÃ¶nderilir.",
    "NEXORA, ekrandaki Ä°ngilizce altyazÄ±larÄ± okur ve TÃ¼rkÃ§eye Ã§evirir. Ã‡eviri tamamen cihaz Ã¼zerinde yapÄ±labilir; internet ya da yapay zekÃ¢ modunu seÃ§ersen altyazÄ± satÄ±rÄ± Ã§eviri servisine gÃ¶nderilir.",
    "about text",
)
io.open(MAIN, "w", encoding="utf-8").write(m)
print("ok", len(s), len(m))
