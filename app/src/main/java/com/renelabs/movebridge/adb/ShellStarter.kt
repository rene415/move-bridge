package com.renelabs.movebridge.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.renelabs.movebridge.Diag
import com.renelabs.movebridge.Prefs
import com.renelabs.movebridge.WifiId
import com.renelabs.movebridge.shell.ShellProtocol
import io.github.muntashirakon.adb.AdbPairingRequiredException

/**
 * Starts Move Bridge's shell helper ([com.renelabs.movebridge.shell.ShellMain]) through the
 * phone's own Wireless debugging, the same way tools like Shizuku start theirs. Everything here blocks:
 * call it off the main thread.
 */
object ShellStarter {
    /** Process name of the helper (kept short: Linux truncates process names to 15 chars). */
    const val HELPER_NAME = "mbshell"
    private const val HELPER_LOG = ShellProtocol.HELPER_LOG
    private const val ADB_WIFI = "adb_wifi_enabled"

    sealed class Result {
        object Started : Result()
        object NotPaired : Result()
        object WirelessDebuggingOff : Result()
        data class Failed(val reason: String) : Result()
    }

    /** True once Move Bridge may switch Wireless debugging on and off by itself (auto-restart). */
    fun canToggleWirelessDebugging(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun wirelessDebuggingOn(c: Context) = Settings.Global.getInt(c.contentResolver, ADB_WIFI, 0) == 1

    private fun setWirelessDebugging(c: Context, on: Boolean) {
        Settings.Global.putInt(c.contentResolver, ADB_WIFI, if (on) 1 else 0)
    }

    /** Pairs with Wireless debugging's "Pair device with pairing code" screen. */
    fun pair(c: Context, host: String, port: Int, code: String): Boolean =
        AdbManager.get(c).pair(host, port, code)

    /**
     * Connects to Wireless debugging and launches the helper.
     * If Wireless debugging is off and we're allowed to, it is switched on just for this and
     * switched off again afterwards. [grantSecureSettings] also grants WRITE_SECURE_SETTINGS so
     * later starts (after a reboot) can do that by themselves.
     */
    @Synchronized  // one start at a time: they share one ADB connection
    fun start(c: Context, grantSecureSettings: Boolean): Result {
        val r = startNow(c, grantSecureSettings)
        // Remember this Wi-Fi: after a restart, only here is the helper brought back unasked (see HelperJob).
        if (r is Result.Started) WifiId.current(c)?.let { Prefs.setHomeWifi(c, it) }
        return r
    }

    private fun startNow(c: Context, grantSecureSettings: Boolean): Result {
        // Another caller may have started it while we waited for the lock.
        if (com.renelabs.movebridge.ShellLink.alive()) return Result.Started
        val wasOn = wirelessDebuggingOn(c)
        if (!wasOn && !canToggleWirelessDebugging(c)) return Result.WirelessDebuggingOff
        if (!wasOn) setWirelessDebugging(c, true)  // just for this start; switched off below
        val mgr = AdbManager.get(c)
        return try {
            if (!mgr.isConnected && !mgr.connectTls(c, CONNECT_TIMEOUT_MS)) Result.Failed("Wireless debugging didn't answer")
            else launch(mgr, startCommand(c, grantSecureSettings))
        } catch (e: AdbPairingRequiredException) {
            Result.NotPaired
        } catch (e: Exception) {
            Diag.e("shell starter failed", e)
            // An unpaired key shows up as a TLS/auth failure on some versions.
            if (e.message?.contains("pair", ignoreCase = true) == true) Result.NotPaired
            else Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { mgr.disconnect() }
            if (!wasOn) runCatching { setWirelessDebugging(c, false) }
        }
    }

    /** The shell command that (re)starts the helper and reports whether it's running. */
    private fun startCommand(c: Context, grantSecureSettings: Boolean) = buildString {
        if (grantSecureSettings) append("pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS; ")
        // The kernel name of an app_process thread is "main", so match the full command line exactly.
        append("pkill -f -x $HELPER_NAME; ")  // replace an old helper (e.g. after an app update)
        // setsid + no stdin: the helper keeps running after this adb connection closes.
        append("CLASSPATH='${c.applicationInfo.sourceDir}' setsid app_process /system/bin --nice-name=$HELPER_NAME ")
        append("com.renelabs.movebridge.shell.ShellMain </dev/null >$HELPER_LOG 2>&1 & ")
        // Don't let this shell (and adb's kill of it) end before the helper has detached:
        // wait until it shows up under its own name (up to ~3 s).
        append("i=0; while [ \$i -lt 30 ] && ! pgrep -f -x $HELPER_NAME >/dev/null; do sleep 0.1; i=\$((i+1)); done; ")
        append("sleep 0.3; pgrep -f -x $HELPER_NAME >/dev/null && echo $STARTED || echo MB_NOT_RUNNING")
    }

    /** Runs [cmd] and reads its output until it reports, or ends (at most [MAX_OUTPUT] bytes). */
    private fun launch(mgr: AdbManager, cmd: String): Result {
        val out = StringBuilder()
        mgr.openStream("shell:$cmd").use { s ->
            val input = s.openInputStream()
            val buf = ByteArray(1024)
            while (out.length < MAX_OUTPUT && !out.contains(STARTED)) {
                val n = input.read(buf)
                if (n < 0) break
                out.append(String(buf, 0, n))
            }
        }
        Diag.i("shell starter: ${out.trim()}")
        return if (out.contains(STARTED)) Result.Started else Result.Failed(out.toString().trim())
    }

    private const val STARTED = "MB_STARTED"
    private const val MAX_OUTPUT = 16 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000L
}
