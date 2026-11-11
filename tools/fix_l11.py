#!/usr/bin/env python3
# Adds a crash logger to the app so a failure during capture start is written
# to the in-app log instead of vanishing, and makes service start-up defensive.
# ASCII only on purpose: the transport mangles non-ASCII characters.
import io
import sys

BASE = "app/src/main/java/com/couchsubs/phone/"

CRASH_GUARD = '''package com.couchsubs.phone

import android.content.Context
import android.util.Log

object CrashGuard {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = Log.getStackTraceString(error)
                LogStore.append(
                    app,
                    "HATA",
                    "COKME (" + thread.name + ", Android " + android.os.Build.VERSION.SDK_INT + "): " + trace.take(3000)
                )
            } catch (_: Throwable) {
            }
            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Throwable) {
            }
        }
    }
}
'''

edits = []


def add(path, pairs):
    edits.append((path, pairs))


add(BASE + "ScreenCaptureService.kt", [
    (
        '        LogStore.append(this, "DURUM", "Servis olu\u015fturuluyor")',
        '        LogStore.append(this, "DURUM", "Servis olu\u015fturuluyor")\n'
        '        CrashGuard.install(this)'
    ),
    (
        '        overlay = SubtitleOverlay(applicationContext)',
        '        overlay = try {\n'
        '            SubtitleOverlay(applicationContext)\n'
        '        } catch (e: Exception) {\n'
        '            LogStore.append(this, "HATA", "Balon olusturulamadi: " + (e.localizedMessage ?: ""))\n'
        '            null\n'
        '        }'
    ),
    (
        '    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {',
        '    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n'
        '        LogStore.append(this, "DURUM", "Servis baslatma istegi alindi")'
    ),
    (
        '        if (Build.VERSION.SDK_INT >= 34) {\n'
        '            startForeground(\n'
        '                NOTIFICATION_ID,\n'
        '                buildNotification("$APP_NAME haz\u0131rlan\u0131yor\u2026"),\n'
        '                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION\n'
        '            )\n'
        '        } else {\n'
        '            startForeground(NOTIFICATION_ID, buildNotification("$APP_NAME haz\u0131rlan\u0131yor\u2026"))\n'
        '        }',
        '        try {\n'
        '            if (Build.VERSION.SDK_INT >= 34) {\n'
        '                startForeground(\n'
        '                    NOTIFICATION_ID,\n'
        '                    buildNotification("$APP_NAME haz\u0131rlan\u0131yor\u2026"),\n'
        '                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION\n'
        '                )\n'
        '            } else {\n'
        '                startForeground(NOTIFICATION_ID, buildNotification("$APP_NAME haz\u0131rlan\u0131yor\u2026"))\n'
        '            }\n'
        '        } catch (e: Exception) {\n'
        '            running = false\n'
        '            LogStore.append(this, "HATA", "Bildirim baslatilamadi: " + e.javaClass.simpleName + ": " + (e.localizedMessage ?: ""))\n'
        '            stopSelf()\n'
        '            return START_NOT_STICKY\n'
        '        }'
    ),
])

add(BASE + "MainActivity.kt", [
    (
        '        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)',
        '        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)\n'
        '        CrashGuard.install(this)'
    ),
    (
        '        LogStore.append(this, "DURUM", "Uygulama a\u00e7\u0131ld\u0131")',
        '        LogStore.append(this, "DURUM", "Uygulama a\u00e7\u0131ld\u0131")\n'
        '        LogStore.append(\n'
        '            this,\n'
        '            "DURUM",\n'
        '            "Cihaz: " + Build.MANUFACTURER + " " + Build.MODEL + " | Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"\n'
        '        )'
    ),
])

ok = True
for path, pairs in edits:
    text = io.open(path, encoding="utf-8").read()
    for old, new in pairs:
        count = text.count(old)
        if count != 1:
            print("ANCHOR PROBLEM in %s: %d matches for %r" % (path, count, old[:70]))
            ok = False
            continue
        text = text.replace(old, new)
    if ok:
        io.open(path, "w", encoding="utf-8").write(text)

if not ok:
    sys.exit(1)

io.open(BASE + "CrashGuard.kt", "w", encoding="utf-8").write(CRASH_GUARD)
print("patched: crash guard installed, start-up hardened")
