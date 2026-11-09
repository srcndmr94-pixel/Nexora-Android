package com.couchsubs.phone

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private const val FILE_NAME = "nexora_log.txt"
    private const val MAX_CHARS = 200_000

    private fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    @Synchronized
    fun append(context: Context, type: String, message: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] [$type] ${message.replace("\n", " ").trim()}\n"
        val f = file(context)
        try {
            f.appendText(line)
            trimIfNeeded(f)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun trimIfNeeded(f: File) {
        try {
            if (!f.exists() || f.length() <= MAX_CHARS) return
            val text = f.readText()
            val keep = text.takeLast((MAX_CHARS * 0.7).toInt())
            val cut = keep.indexOf('\n').let { if (it >= 0) keep.substring(it + 1) else keep }
            f.writeText("[kayıt kırpıldı]\n$cut")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(context: Context): String = try {
        val f = file(context)
        if (f.exists()) f.readText().ifBlank { "Henüz kayıt yok." } else "Henüz kayıt yok."
    } catch (e: Exception) {
        "Kayıt okunamadı: ${e.localizedMessage}"
    }

    @Synchronized
    fun clear(context: Context) {
        try { file(context).writeText("") } catch (_: Exception) {}
    }

    fun path(context: Context): String = file(context).absolutePath
}
