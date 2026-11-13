package com.renelabs.movebridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.renelabs.movebridge.shell.IShellCapture
import com.renelabs.movebridge.shell.ShellProtocol
import java.util.concurrent.CopyOnWriteArrayList

/** The app's handle on its own shell helper (see [ShellMain]). */
object ShellLink {
    @Volatile var service: IShellCapture? = null
        private set
    private val waiters = CopyOnWriteArrayList<() -> Unit>()
    /** Called (on a binder thread) when the helper connects or goes away. */
    @Volatile var onChange: (() -> Unit)? = null

    fun alive(): Boolean = service?.asBinder()?.pingBinder() == true

    internal fun onBinder(b: IBinder) {
        val svc = IShellCapture.Stub.asInterface(b)
        // A helper left over from before an app update speaks an older protocol: ignore it, so the
        // app starts a fresh one (the starter replaces the old process).
        val v = runCatching { svc.version() }.getOrDefault(-1)
        if (v != BuildConfig.VERSION_CODE) { Diag.i("ignoring helper version $v"); return }
        runCatching { b.linkToDeath({ if (service?.asBinder() == b) { service = null; onChange?.invoke() } }, 0) }
        service = svc
        waiters.forEach { it() }
        onChange?.invoke()
    }

    /** Calls [done] on the main thread once the helper's binder is here, or after [timeoutMs]. */
    fun await(timeoutMs: Long, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (alive()) { main.post { done(true) }; return }
        var finished = false
        lateinit var waiter: () -> Unit
        val finish = {
            if (!finished) { finished = true; waiters.remove(waiter); done(alive()) }
        }
        waiter = { main.post(finish) }
        waiters += waiter
        main.postDelayed(finish, timeoutMs)
    }
}

/**
 * Receives the shell helper's Binder. Exported, but guarded by INTERACT_ACROSS_USERS_FULL (only
 * shell and system have it) and by a uid check, so ordinary apps can't reach it.
 */
class ShellLinkProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) return null  // shell or root only
        if (method == ShellProtocol.METHOD_BINDER) extras?.getBinder(ShellProtocol.KEY_BINDER)?.let { ShellLink.onBinder(it) }
        return Bundle()
    }

    override fun onCreate() = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
