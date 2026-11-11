#!/usr/bin/env python3
"""Add automatic subtitle-band detection (no manual region needed)."""
import io, sys

SVC = "app/src/main/java/com/couchsubs/phone/ScreenCaptureService.kt"
MAIN = "app/src/main/java/com/couchsubs/phone/MainActivity.kt"


def fail(msg):
    print("ERROR: " + msg)
    sys.exit(1)


def one(text, old, new, label):
    if text.count(old) != 1:
        fail("patch target not unique/missing: %s (%d)" % (label, text.count(old)))
    return text.replace(old, new, 1)


# ---------------- ScreenCaptureService ----------------
s = io.open(SVC, encoding="utf-8").read()

# 1) cache fields
s = one(
    s,
    "    @Volatile private var lastNotifyAt = 0L\n",
    "    @Volatile private var lastNotifyAt = 0L\n"
    "    @Volatile private var cachedBand: IntArray? = null\n"
    "    @Volatile private var cachedBandAt = 0L\n"
    "    @Volatile private var cachedBandWidth = 0\n",
    "band cache fields",
)

# 2) use the detected band when cropping
s = one(
    s,
    '            val useCustom = position == "custom" || position == "ozel" || prefs.getString("region", "") == "custom"\n'
    "            val left: Int; val top: Int; val right: Int; val bottom: Int\n"
    "            if (useCustom) {\n",
    '            val useCustom = position == "custom" || position == "ozel" || prefs.getString("region", "") == "custom"\n'
    "            // Otomatik mod: her karede altyazi bandini kendimiz buluruz.\n"
    "            val autoBand = if (prefBool(\"auto_region\", true)) cachedSubtitleBand(bitmap) else null\n"
    "            val left: Int; val top: Int; val right: Int; val bottom: Int\n"
    "            if (autoBand != null) {\n"
    "                left = (w * 0.02f).toInt().coerceIn(0, w - 2)\n"
    "                right = (w * 0.98f).toInt().coerceIn(left + 2, w)\n"
    "                top = autoBand[0].coerceIn(0, h - 8)\n"
    "                bottom = autoBand[1].coerceIn(top + 8, h)\n"
    "            } else if (useCustom) {\n",
    "processFrame auto branch",
)

# 3) detection helpers before upscaleForOcr
DETECT = '''    // Altyazi bandini kendisi bulur: yazi satirlari yuksek kontrastli gecisler uretir.
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

'''
s = one(s, "    // Kucuk kirpintilari OCR icin buyut", DETECT + "    // Kucuk kirpintilari OCR icin buyut", "detect helpers")

# 4) abs import
if "import kotlin.math.abs" not in s:
    s = one(s, "import kotlin.math.max\n", "import kotlin.math.abs\nimport kotlin.math.max\n", "abs import")

io.open(SVC, "w", encoding="utf-8").write(s)

# ---------------- MainActivity: toggle ----------------
m = io.open(MAIN, encoding="utf-8").read()
m = one(
    m,
    '        cardView.addView(toggleRow("Oyun Arayüzü Metinlerini Filtrele", "hud_filter", true))\n',
    '        cardView.addView(toggleRow("Oyun Arayüzü Metinlerini Filtrele", "hud_filter", true))\n'
    '        cardView.addView(toggleRow("Altyazı Alanını Otomatik Bul", "auto_region", true))\n',
    "auto region toggle",
)
m = one(
    m,
    '            .putBoolean("hud_filter", true)\n',
    '            .putBoolean("hud_filter", true)\n            .putBoolean("auto_region", true)\n',
    "auto region default",
)
io.open(MAIN, "w", encoding="utf-8").write(m)

print("ok service", len(s), "| main", len(m))
