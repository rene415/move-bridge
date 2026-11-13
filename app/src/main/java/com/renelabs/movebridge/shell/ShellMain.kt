package com.renelabs.movebridge.shell

import android.os.Build
import androidx.annotation.RequiresApi
import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.renelabs.movebridge.BuildConfig
import java.io.File

/**
 * Move Bridge's own shell helper. Started by [com.renelabs.movebridge.adb.ShellStarter] through
 * Wireless debugging:
 *   CLASSPATH=<our apk> setsid app_process /system/bin --nice-name=mbshell com.renelabs.movebridge.shell.ShellMain
 * It runs as the shell user until the phone restarts, holding a [ShellCaptureService]. Whenever
 * the Move Bridge app process (re)starts, it hands the app that service's Binder through
 * [com.renelabs.movebridge.ShellLinkProvider]. It never starts the app itself.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)  // audio-only mode needs Android 13+
object ShellMain {
    private const val AUTHORITY = ShellProtocol.AUTHORITY
    private const val METHOD_BINDER = ShellProtocol.METHOD_BINDER
    private const val KEY_BINDER = ShellProtocol.KEY_BINDER
    private const val POLL_MS = 1000L

    @JvmStatic
    fun main(args: Array<String>) {
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        log("started as uid ${Process.myUid()}")
        // Set up the shell identity here on the main thread: capture requests arrive on binder
        // threads, which can't create the ActivityThread (it needs a Looper).
        runCatching { ShellAudio.prepare() }.onFailure { log("identity setup failed: $it") }
        // A Direct route announced by an earlier helper: keep watching it, or withdraw it if stale.
        runCatching { DirectRoute.recover() }.onFailure { log("direct recover failed: $it") }
        Thread(AppWatcher()::run, "watch-app").apply { isDaemon = true; start() }
        Looper.loop()
    }

    /** Hands our binder to each new Move Bridge process. Runs until the helper exits, by design. */
    private class AppWatcher {
        private var service: ShellCaptureService? = null
        private var appUid = -1
        private var lastPid = -1

        fun run() {
            while (true) {
                check(findApp())
                Thread.sleep(POLL_MS)
            }
        }

        private fun check(app: Pair<Int, Int>) {
            val (pid, uid) = app
            if (pid <= 0) { lastPid = -1; return }
            if (pid == lastPid) return
            if (service == null || uid != appUid) {
                appUid = uid
                service = ShellCaptureService(uid)
            }
            if (sendBinder(service!!)) {
                lastPid = pid
                log("handed binder to app pid $pid")
            }
        }
    }

    private fun log(s: String) = System.err.println("MoveBridge shell: $s")

    /** Finds the Move Bridge main process by scanning /proc. Returns (pid, uid), or (-1, -1). */
    private fun findApp(): Pair<Int, Int> {
        val procs = File("/proc").listFiles() ?: return -1 to -1
        for (p in procs) {
            val pid = p.name.toIntOrNull() ?: continue
            if (processName(p) == BuildConfig.APPLICATION_ID) return pid to processUid(p)
        }
        return -1 to -1
    }

    private fun processName(p: File): String? {
        val cmd = runCatching { File(p, "cmdline").readBytes() }.getOrNull() ?: return null
        val end = cmd.indexOf(0).let { if (it < 0) cmd.size else it }
        return String(cmd, 0, end)
    }

    private fun processUid(p: File): Int = runCatching {
        File(p, "status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
    }.getOrDefault(-1)

    /**
     * Calls our app's provider (reachable only with INTERACT_ACROSS_USERS_FULL, i.e. shell and
     * system) with the service Binder, like `content call` does.
     */
    private fun sendBinder(service: IBinder): Boolean = try {
        val am = Reflect.cls("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = Reflect.callPublic(am, "getContentProviderExternal", String::class.java to AUTHORITY,
            Int::class.javaPrimitiveType!! to 0, IBinder::class.java to token, String::class.java to "movebridge")
        if (holder != null) {
            try { callProvider(holder, service) } finally { releaseProvider(am, token) }
        }
        holder != null
    } catch (e: Throwable) {
        log("sendBinder failed: ${e.cause ?: e}")
        false
    }

    private fun callProvider(holder: Any, service: IBinder) {
        val provider = holder.javaClass.getField("provider").get(holder)!!
        val attribution = AttributionSource.Builder(Process.myUid()).setPackageName(ShellIdentity.SHELL_PACKAGE).build()
        val extras = Bundle().apply { putBinder(KEY_BINDER, service) }
        Reflect.callPublic(provider, "call", AttributionSource::class.java to attribution,
            String::class.java to AUTHORITY, String::class.java to METHOD_BINDER,
            String::class.java to null, Bundle::class.java to extras)
    }

    private fun releaseProvider(am: Any, token: IBinder) {
        runCatching {
            Reflect.callPublic(am, "removeContentProviderExternalAsUser", String::class.java to AUTHORITY,
                IBinder::class.java to token, Int::class.javaPrimitiveType!! to 0)
        }
    }
}
