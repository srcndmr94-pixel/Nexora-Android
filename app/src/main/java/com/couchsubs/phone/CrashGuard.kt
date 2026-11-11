package com.couchsubs.phone

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
