#!/usr/bin/env python3
"""Restore the working MainActivity and apply the video-chrome OCR filter fixes."""
import io, subprocess, sys

GOOD_MAIN = "73bfd0e83b992eeb5fc2c338056485597a710a55"
MAIN = "app/src/main/java/com/couchsubs/phone/MainActivity.kt"
SVC = "app/src/main/java/com/couchsubs/phone/ScreenCaptureService.kt"


def fail(msg):
    print("ERROR: " + msg)
    sys.exit(1)


def one(text, old, new, label):
    if old not in text:
        fail("patch target missing: " + label)
    return text.replace(old, new, 1)


# ---------- MainActivity: restore the last good version, then add the glossary card ----------
good = subprocess.run(
    ["git", "show", GOOD_MAIN + ":" + MAIN], capture_output=True, text=True, check=True
).stdout
if "class MainActivity : ComponentActivity()" not in good:
    fail("unexpected base MainActivity")

m = one(good, "import android.widget.Button\n",
        "import android.widget.Button\nimport android.widget.EditText\n", "edittext import")
m = one(m, '        const val CARD = "',
        '        const val SURFACE = "#071325"\n        const val CARD = "', "surface const")
m = one(m, "        col.addView(advancedCard())\n",
        "        col.addView(glossaryCard())\n        col.addView(advancedCard())\n", "settings list")
m = one(m, '            .putBoolean("hud_filter", true)\n',
        '            .putBoolean("hud_filter", true)\n'
        '            .putBoolean("context_memory", true)\n'
        '            .putBoolean("stabilize", true)\n'
        '            .putString("glossary_keep", "")\n', "defaults")

CARD = '''    private fun glossaryCard(): LinearLayout {
        val cardView = card()
        cardView.addView(cardHeader(R.drawable.ic_translate, "Bağlam ve Sözlük", "İsimleri korur, konuşmayı hatırlar."))
        cardView.addView(toggleRow("Konuşma Bağlamını Hatırla", "context_memory", true))
        cardView.addView(toggleRow("Satırları Sabitle (daha az titreme)", "stabilize", true))
        val input = EditText(this).apply {
            setText(prefs.getString("glossary_keep", "") ?: "")
            hint = "Korunacak isim / terim, her satıra bir tane"
            textSize = 12.5f
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bgRound(SURFACE, 12, CARD_LINE)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit().putString("glossary_keep", s?.toString() ?: "").apply()
                }
            })
        }
        cardView.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        return cardView
    }

'''
m = one(m, "    private fun advancedCard(): LinearLayout {\n", CARD + "    private fun advancedCard(): LinearLayout {\n", "card fun")

# ---------- ScreenCaptureService: fix the escapes and strengthen the chrome filters ----------
s = io.open(SVC, encoding="utf-8").read()

NEW_RATING = r'''    private val ratingChromeRegex = Regex("\\b\\d+[\\.,]?\\d*\\s*[KMBkmb]?\\s*(views?|likes?|subscribers?|abone|izlenme)\\b|@\\w{2,}", RegexOption.IGNORE_CASE)
'''
lines = s.splitlines(True)
hits = [i for i, l in enumerate(lines) if "private val ratingChromeRegex" in l]
if len(hits) != 1:
    fail("ratingChromeRegex line not found exactly once")
lines[hits[0]] = NEW_RATING
s = "".join(lines)

start = s.index("    private fun looksLikeHudPrompt(raw: String): Boolean {")
end = s.index("    private fun pickBestSubtitleCandidate(")
HUD = '''    private fun looksLikeHudPrompt(raw: String): Boolean {
        if (!prefBool("hud_filter", true)) return false
        val lower = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (ratingChromeRegex.containsMatchIn(lower)) return true
        // Video/app chrome and titles (YouTube etc.) that should not be translated as subtitles.
        if (Regex("\\\\b(diger videolar|more videos|recommended|subscribe|abone ol|izlenme|live chat|comments?|yorumlar)\\\\b").containsMatchIn(lower)) return true
        if (lower.contains("extended look") || lower.contains("official trailer") || lower.contains("official teaser")) return true
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val dialogueCount = tokens.count { it in englishDialogueMarkers }
        val endsSentence = Regex("[.!?\\\\u2026]\\\\s*$").containsMatchIn(raw)
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

'''
s = s[:start] + HUD + s[end:]

pstart = s.index("        // Title/caption-chrome penalties:")
pend = s.index("        return score", pstart)
PEN = '''        // Title/caption-chrome penalties: mixed metadata, handles.
        if (lower.contains("@") || lower.contains("diger video") || lower.contains("more videos")) score -= 18
        if (lower.contains("extended look") || lower.contains("trailer") || lower.contains("teaser")) score -= 10
        // Prefer sentence-like lines over title blobs.
        if (wordCount >= 4 && punct == 0 && dialogue == 0) score -= 8
        // Very noisy OCR (many 1-char tokens / symbols) is unreliable.
        val tiny = words.count { it.length <= 1 }
        if (tiny >= 3) score -= 12
'''
s = s[:pstart] + PEN + s[pend:]

io.open(MAIN, "w", encoding="utf-8").write(m)
io.open(SVC, "w", encoding="utf-8").write(s)
print("MainActivity", len(m), "| ScreenCaptureService", len(s), "| cacheLock", "cacheLock" in s)
