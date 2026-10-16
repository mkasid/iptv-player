package com.kasidwala.kasidtv

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs the crash catcher before anything else runs, so even a crash
 * during activity startup is recorded. The next launch shows what happened.
 */
class KasidTVApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val app = this
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val trace = sw.toString().lineSequence().take(25).joinToString("\n")
                app.getSharedPreferences("kasidtv", Context.MODE_PRIVATE).edit()
                    .putString(
                        "last_crash",
                        "${e.javaClass.name}: ${e.message}\nThread: ${t.name}\n\n$trace"
                    )
                    .apply()
            } catch (_: Throwable) {
            }
            // Chain to the system handler so the crash still terminates
            // visibly instead of leaving a dead/frozen app behind.
            try {
                defaultHandler?.uncaughtException(t, e)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        /** Returns a saved crash report, clearing it. Null when none. */
        fun takeCrash(context: Context): String? {
            val p = context.getSharedPreferences("kasidtv", Context.MODE_PRIVATE)
            val c = p.getString("last_crash", null)
            if (c != null) p.edit().remove("last_crash").apply()
            return c
        }
    }
}
