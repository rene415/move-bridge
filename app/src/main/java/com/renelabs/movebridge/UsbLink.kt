package com.renelabs.movebridge

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Bridge mode's USB side: the Move connection and the native engine, on their own thread
 * (opening, configuring and stopping the Move can block for seconds). Results come back on the
 * main thread.
 */
class UsbLink(private val c: Context) {
    sealed class Result {
        object Ok : Result()
        data class Failed(val title: String, val detail: String) : Result()
    }

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("usb").apply { start() }
    private val usb = Handler(thread.looper)
    private var conn: UsbDeviceConnection? = null  // usb thread only
    private var device: UsbDevice? = null          // usb thread only
    private var seq = 0                            // main thread only

    /**
     * Connects and starts streaming. A later [attach] or [detach] makes this result stale: it's
     * then dropped, and the stop queued behind it cleans up.
     */
    fun attach(dev: UsbDevice, tone: Boolean, done: (Result) -> Unit) {
        val s = ++seq
        usb.post {
            val r = open(dev, tone)
            main.post { if (s == seq) done(r) }
        }
    }

    fun detach() {
        seq++
        usb.post { stopNow() }
    }

    /**
     * Brings back the kernel's sound card for the Move when it is missing: that happens when
     * Bridge mode ended without a clean stop (the app was killed or updated mid-stream). Opens
     * the Move just to hand its interfaces back, then calls [done] on the main thread.
     */
    fun revive(dev: UsbDevice, done: () -> Unit) {
        val s = ++seq
        usb.post {
            stopNow()
            runCatching { c.getSystemService(UsbManager::class.java).openDevice(dev) }.getOrNull()?.let { cn ->
                handBack(cn, dev)
                cn.close()
            }
            main.post { if (s == seq) done() }
        }
    }

    /** Stops streaming and ends the thread (the stop runs first). */
    fun quit() {
        detach()
        thread.quitSafely()
    }

    private fun open(dev: UsbDevice, tone: Boolean): Result {
        stopNow()
        val cn = c.getSystemService(UsbManager::class.java).openDevice(dev)
            ?: return Result.Failed(c.getString(R.string.svc_open_failed), "")
        claimAudioInterfaces(cn, dev)
        armDebugDump()
        val result = NativeBridge.start(cn.fileDescriptor, tone)
        if (result.startsWith("ERR")) {
            cn.close()
            Diag.e(result)
            return failure(result.lineSequence().first())
        }
        Diag.i(result)
        conn = cn
        device = dev
        return Result.Ok
    }

    /** Debug: if <external files>/mb_dump exists, record the first 15 s sent to the Move. */
    private fun armDebugDump() {
        val dir = c.getExternalFilesDir(null) ?: return
        if (java.io.File(dir, "mb_dump").exists()) NativeBridge.setDumpPath(java.io.File(dir, "mb_out.raw").path)
    }

    /** Takes interfaces 1-3 (audio control and both streams) away from Android's driver. */
    private fun claimAudioInterfaces(cn: UsbDeviceConnection, dev: UsbDevice) {
        val claimed = mutableSetOf<Int>()
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.id in 1..3 && claimed.add(itf.id) && !cn.claimInterface(itf, true))
                Diag.w("couldn't claim interface ${itf.id}")
        }
    }

    private fun failure(firstLine: String): Result.Failed =
        if (firstLine.contains("not high-speed"))
            Result.Failed(c.getString(R.string.svc_not_high_speed), c.getString(R.string.svc_not_high_speed_detail))
        else Result.Failed(c.getString(R.string.svc_rejected), firstLine)

    private fun stopNow() {
        NativeBridge.stop()
        val cn = conn ?: return
        device?.let { handBack(cn, it) }
        cn.close()
        conn = null
        device = null
    }

    /**
     * Gives the Move's audio and MIDI interfaces back to Android's kernel driver, so the kernel's
     * sound card for the Move (which Direct mode and the MIDI port need) comes back without
     * unplugging. Taking interface 1 killed that card; the driver only builds a new one once
     * nothing of the old one is left, so first take everything (MIDI included), then release:
     * Android re-attaches the kernel driver on release. Streams go first, because the control
     * interfaces (1: audio, 4: MIDI) pick up their streams when they attach.
     */
    private fun handBack(cn: UsbDeviceConnection, dev: UsbDevice) {
        val byId = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .filter { it.id in KERNEL_INTERFACES }.associateBy { it.id }
        for (itf in byId.values) cn.claimInterface(itf, true)
        for (id in RELEASE_ORDER) {
            val ok = byId[id]?.let { cn.releaseInterface(it) }
            if (ok != true) Diag.w("couldn't hand back interface $id")
        }
    }

    private companion object {
        /** Everything Android's sound driver uses: audio control, both streams, MIDI control, MIDI. */
        val KERNEL_INTERFACES = 1..5
        val RELEASE_ORDER = intArrayOf(2, 3, 5, 1, 4)
    }
}
