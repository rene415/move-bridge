package com.renelabs.movebridge.shell

import android.os.Build
import androidx.annotation.RequiresApi
import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.Process

/**
 * Makes the helper process identify as com.android.shell. AudioRecord takes its package name from
 * the process's ActivityThread, and AudioFlinger rejects uid 2000 with any other package
 * ("invalid attr ... packageName: android"). Mirrors scrcpy's Workarounds (Apache-2.0).
 * Call [context] first on the process's main thread: creating the ActivityThread needs a Looper.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)  // the helper needs Android 13+
@SuppressLint("StaticFieldLeak")  // a system context in the helper process, kept for its lifetime
internal object ShellIdentity {
    const val SHELL_PACKAGE = "com.android.shell"
    private val AT = Reflect.cls("android.app.ActivityThread")
    private var context: Context? = null

    /** A Context that presents itself as the shell package, so AppOps checks match uid 2000. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        override fun getApplicationContext(): Context = this
    }

    @Synchronized
    fun context(): Context {
        context?.let { return it }
        val at = currentOrNewActivityThread()
        runCatching { fillAppInfo(at) }.onFailure { log("fillAppInfo failed: $it") }
        val ctx = ShellContext(Reflect.call(at, AT, "getSystemContext") as Context)
        runCatching { fillAppContext(at, ctx) }.onFailure { log("fillAppContext failed: $it") }
        context = ctx
        return ctx
    }

    /**
     * Runs [block] with this process not marked as a system process. Needed to read a UsbDevice:
     * its constructor refuses a remote serial-number reader in a "system" process, which this
     * helper claims to be (see [currentOrNewActivityThread]).
     */
    @Synchronized
    fun <T> asOrdinaryProcess(block: () -> T): T {
        val at = AT.getMethod("currentActivityThread").invoke(null)
        val flag = AT.getDeclaredField("mSystemThread").apply { isAccessible = true }
        flag.setBoolean(at, false)
        try {
            return block()
        } finally {
            flag.setBoolean(at, true)
        }
    }

    private fun currentOrNewActivityThread(): Any {
        AT.getMethod("currentActivityThread").invoke(null)?.let { return it }
        @Suppress("DEPRECATION")
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val at = Reflect.newInstance(AT)
        Reflect.set(null, AT, "sCurrentActivityThread", at)
        AT.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(at, true)
        runCatching { fillConfigurationController(at) }
        return at
    }

    private fun fillConfigurationController(at: Any) {
        val internal = Reflect.cls("android.app.ActivityThreadInternal")
        val cc = Reflect.newInstance(Reflect.cls("android.app.ConfigurationController"), internal to at)
        Reflect.set(at, AT, "mConfigurationController", cc)
    }

    /** mBoundApplication.appInfo.packageName = "com.android.shell" */
    private fun fillAppInfo(at: Any) {
        val bindCls = Reflect.cls("android.app.ActivityThread\$AppBindData")
        val bind = Reflect.newInstance(bindCls)
        Reflect.set(bind, bindCls, "appInfo", ApplicationInfo().apply { packageName = SHELL_PACKAGE })
        Reflect.set(at, AT, "mBoundApplication", bind)
    }

    /** mInitialApplication = an Application wrapping the shell context */
    private fun fillAppContext(at: Any, ctx: Context) {
        val app = Instrumentation.newApplication(Application::class.java, ctx)
        Reflect.set(at, AT, "mInitialApplication", app)
    }

    private fun log(s: String) = System.err.println("MoveBridge shell: $s")
}
