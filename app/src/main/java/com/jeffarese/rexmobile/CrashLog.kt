package com.jeffarese.rexmobile

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Keeps the last crash so the next launch can show it, since the phone has no logcat attached. */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(app).writeText(
                    "Chompy ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.RELEASE}, " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n${Log.getStackTraceString(error)}",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A crash not yet shown, for the crash screen at launch. */
    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    /** The crash screen was seen: keep its report for Settings, which shows the most recent one. */
    fun clear(context: Context) {
        val f = file(context)
        if (f.exists() && !f.renameTo(seen(context))) f.delete()
    }

    /** The most recent crash report, shown or not. */
    fun last(context: Context): String? = read(context) ?: seen(context).takeIf { it.exists() }?.readText()

    private fun seen(context: Context) = File(context.filesDir, "previous-crash.txt")
}
