package com.renelabs.movebridge

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The app's log. Besides logcat, it keeps the last lines in memory so a tester can choose to
 * include them in a report ([Report]). Nothing here leaves the phone by itself.
 */
object Diag {
    private const val TAG = "MoveBridge"
    private const val MAX_LINES = 120
    private val lines = ArrayDeque<String>()  // guarded by this
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** The newest connection details (Bridge mode's counters or Direct mode's measured delay). */
    @Volatile var lastStats = ""

    fun i(msg: String) {
        Log.i(TAG, msg)
        keep("I", msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        keep("W", msg)
    }

    fun e(msg: String, error: Throwable? = null) {
        Log.e(TAG, msg, error)
        keep("E", if (error == null) msg else "$msg: $error")
    }

    /** Bridge mode's counters, every two seconds: to logcat only, and remembered as [lastStats]. */
    fun stats(line: String) {
        Log.i(TAG, "stats: $line")
        lastStats = line
    }

    @Synchronized
    fun recent(): List<String> = lines.toList()

    @Synchronized
    private fun keep(level: String, msg: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast("${clock.format(Date())} $level $msg")
    }
}

/** The process-wide hook that lets [Crash] note why the app closed. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}

/**
 * Keeps the reason of the last crash in the app's private storage, so the next start can offer to
 * send it. It stays on the phone unless the user sends it.
 */
object Crash {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 4000

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(app: Context, thread: Thread, error: Throwable) {
        val head = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), thread ${thread.name}\n"
        File(app.filesDir, FILE).writeText(head + Log.getStackTraceString(error).take(MAX_CHARS))
    }

    /** The saved crash, or null if the app last closed normally. */
    fun pending(c: Context): String? = File(c.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(c: Context) {
        File(c.filesDir, FILE).delete()
    }
}

/**
 * Builds the text a tester can choose to send. Technical details only: app and Android version,
 * phone model, the app's settings and its own recent log. No names, accounts, serial numbers,
 * network names or addresses, location, list of other apps, or audio.
 */
object Report {
    private val ADDRESS = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
    private const val HELPER_LINES = 25

    fun build(c: Context, kind: String, note: String, withDetails: Boolean): String = buildString {
        appendLine("Move Bridge feedback: $kind")
        if (note.isNotBlank()) appendLine(note.trim())
        if (withDetails) {
            appendLine()
            append(details(c))
        }
    }

    fun details(c: Context): String = scrub(buildString {
        appendLine("--- Technical details")
        appendLine("Move Bridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Settings: ${settings(c)}")
        appendLine("Helper: ${AudioOnly.status(c)}")
        appendLine("State: ${BridgeService.state.phase} ${BridgeService.state.title}")
        if (Diag.lastStats.isNotEmpty()) appendLine("Connection: ${Diag.lastStats.replace("\n", " | ")}")
        Crash.pending(c)?.let { appendLine("--- Last crash\n$it") }
        appendLine("--- App log")
        Diag.recent().forEach(::appendLine)
        helperLog()?.let { appendLine("--- Helper log\n$it") }
    })

    private fun settings(c: Context): String = listOf(
        "start on connect" to Prefs.autoStart(c), "audio-only" to Prefs.audioOnly(c),
        "direct" to Prefs.direct(c), "also on phone" to Prefs.playOnPhone(c),
        "start automatically" to Prefs.autoRestart(c), "mute notifications" to Prefs.muteWhileSending(c),
        "stay ready" to Prefs.stayArmed(c),
    ).joinToString(", ") { (name, on) -> "$name=${if (on) "on" else "off"}" }

    /** The last lines the shell helper wrote, if it is running. */
    private fun helperLog(): String? =
        runCatching { ShellLink.service?.recentLog(HELPER_LINES) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Network addresses have no business in a report. */
    private fun scrub(text: String) = text.replace(ADDRESS, "[address]")
}
