package dev.chenwuyi.leaf

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash on disk so 设置 › 关于 can copy it, the way PiliPaw keeps its crash log: when
 * something breaks on the device, the stack trace is one tap away instead of needing adb.
 */
object CrashLog {
    private const val FileName = "last-crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FileName)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file.writeText("Leaf ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})\nthread: ${thread.name}\ntime: ${System.currentTimeMillis()}\n\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, or null when there has been none since it was last cleared. */
    fun read(context: Context): String? = File(context.filesDir, FileName).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FileName).delete()
    }
}
