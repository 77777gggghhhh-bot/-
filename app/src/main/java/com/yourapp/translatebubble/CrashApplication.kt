package com.yourapp.translatebubble

import android.app.Application
import android.content.Context

class CrashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                prefs.edit().putString("last_crash", sw.toString()).commit()
            } catch (e: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
