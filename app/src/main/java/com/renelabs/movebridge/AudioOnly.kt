package com.renelabs.movebridge

import android.content.Context
import android.os.Build
import android.os.Handler
import com.renelabs.movebridge.adb.ShellStarter
import com.renelabs.movebridge.shell.IShellCapture

/**
 * The shell-user helper, and whether it can be used right now. With the helper, Move Bridge
 * works without MediaProjection, so Android doesn't treat a session as screen sharing: Android
 * plays straight to the Move (Direct mode), or, if that fails, the helper captures the audio and
 * the app sends it (Bridge mode).
 *
 * This is not a setting. Once the one-time setup is done, the helper is always used when it runs
 * or can be started; when it can't, the app falls back to Android's screen-share capture by itself.
 * The helper comes from, in order:
 *  1. Move Bridge's own helper, already running (started earlier through Wireless debugging)
 *  2. Move Bridge's own helper, started now through Wireless debugging (on, or switched on by us)
 */
object AudioOnly {
    enum class Status {
        UNSUPPORTED,               // Android 12 or older
        NOT_SET_UP,                // never paired with Wireless debugging
        NEEDS_WIRELESS_DEBUGGING,  // paired, helper not running, and we can't switch Wireless debugging on
        CAN_START,                 // helper not running, but we can start it on plug-in
        RUNNING,                   // our helper is running
    }

    private const val HELPER_WAIT_MS = 4000L
    private const val BINDER_WAIT_MS = 1500L

    @Volatile private var starting = false
    /** The last attempt to start the helper failed: don't count on it until one succeeds. */
    @Volatile private var startFailed = false

    fun status(c: Context): Status {
        if (Build.VERSION.SDK_INT < 33) return Status.UNSUPPORTED
        if (ShellLink.alive()) return Status.RUNNING
        if (!Prefs.adbPaired(c)) return Status.NOT_SET_UP
        if (ShellStarter.wirelessDebuggingOn(c)) return Status.CAN_START
        if (Prefs.autoRestart(c) && ShellStarter.canToggleWirelessDebugging(c)) return Status.CAN_START
        return Status.NEEDS_WIRELESS_DEBUGGING
    }

    /** A plug-in can use the helper now (possibly after starting it). Otherwise: screen-share capture. */
    fun usable(c: Context): Boolean = when (status(c)) {
        Status.RUNNING -> true
        Status.CAN_START -> !startFailed
        else -> false
    }

    /**
     * Calls [done] on [main] with the running helper, starting it through Wireless debugging first
     * if needed. Null when it can't be started.
     */
    fun withHelper(c: Context, main: Handler, done: (IShellCapture?) -> Unit) {
        Diag.i("helper: alive=${ShellLink.alive()} status=${status(c)}")
        when {
            ShellLink.alive() -> done(ShellLink.service)
            status(c) == Status.CAN_START -> startHelper(c.applicationContext, main, done)
            else -> done(null)
        }
    }

    private fun startHelper(app: Context, main: Handler, done: (IShellCapture?) -> Unit) {
        Thread {
            val started = startHelperBlocking(app)
            main.post {
                if (!started) done(null)
                else ShellLink.await(HELPER_WAIT_MS) { ok -> done(if (ok) ShellLink.service else null) }
            }
        }.start()
    }

    /** Starts the helper now if it isn't running. Blocks: call it off the main thread. True once it runs. */
    fun startHelperBlocking(app: Context): Boolean {
        if (ShellLink.alive()) return true
        val r = ShellStarter.start(app, grantSecureSettings = Prefs.autoRestart(app))
        Diag.i("helper start: ${r.javaClass.simpleName} ${(r as? ShellStarter.Result.Failed)?.reason ?: ""}")
        // Android no longer accepts our pairing key (debugging authorizations were revoked):
        // the one-time setup has to be done again, and Settings says so.
        if (r is ShellStarter.Result.NotPaired) Prefs.setAdbPaired(app, false)
        startFailed = r !is ShellStarter.Result.Started
        return !startFailed
    }

    /**
     * With the helper running and a Move plugged in: make Move Bridge Android's default app for
     * the Move, so plugging in never asks which app to open (Android asks every time when another
     * app, such as a MIDI app, also accepts the Move).
     */
    fun claimMoveByDefault() {
        val svc = ShellLink.service ?: return
        Thread {
            val ok = runCatching { svc.makeDefault() }.getOrDefault(false)
            Diag.i("default app for the Move set: $ok")
        }.start()
    }

    /**
     * Starts our helper in the background if the setup is done and it isn't running yet, so it's
     * ready before the Move is plugged in. Safe to call often.
     */
    fun warmUp(c: Context) {
        val app = c.applicationContext
        if (starting || status(app) != Status.CAN_START) return
        starting = true
        // A helper that's already running hands over its binder within a second of our process
        // starting; wait for that before starting a new one.
        ShellLink.await(BINDER_WAIT_MS) { alive -> if (alive) starting = false else startNow(app) }
    }

    private fun startNow(app: Context) {
        Thread {
            try { startHelperBlocking(app) } finally { starting = false }
        }.start()
    }

    /**
     * Right after our process starts, the helper needs up to a second to hand over its
     * Binder. Waits for that, then reports [usable].
     */
    fun whenKnown(c: Context, done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !Prefs.adbPaired(c)) return done(false)
        if (ShellLink.alive()) return done(true)
        ShellLink.await(BINDER_WAIT_MS) { done(usable(c)) }
    }
}
