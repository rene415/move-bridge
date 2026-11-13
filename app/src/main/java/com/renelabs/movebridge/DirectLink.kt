package com.renelabs.movebridge

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.renelabs.movebridge.shell.IShellCapture

/**
 * Direct mode, the app's side: has the shell helper tell Android that the Move's sound card is a
 * USB audio output. Android then plays to the Move by itself, like USB-C headphones or an
 * iPhone: nothing is captured, and Move Bridge leaves the Move's USB interfaces to Android's
 * kernel driver. See docs/latency-and-direct-route.md.
 *
 * Helper calls and waiting happen on this class's own thread; results come back on the main thread.
 */
class DirectLink(private val c: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("direct").apply { start() }
    private val worker = Handler(thread.looper)
    /** Bumped by every [start] and [stop]: results of an older request are dropped. */
    @Volatile private var seq = 0
    /** The card number last announced (worker thread only), for [refresh]. */
    private var lastHint = -1

    /**
     * Announces the Move's sound card to Android. [done] gets true once Android lists the Move as
     * an audio output, false if that didn't happen within [FIND_MS].
     */
    fun start(dev: UsbDevice, svc: IShellCapture, done: (Boolean) -> Unit) {
        val s = ++seq
        worker.post {
            val ok = announce(dev, svc) { s == seq }
            main.post { if (s == seq) done(ok) }
        }
    }

    /** Withdraws the route. [done], if given, runs on the main thread once Android has it. */
    fun stop(done: (() -> Unit)? = null) {
        seq++
        worker.post {
            runCatching { ShellLink.service?.directOff() }
            if (done != null) main.post(done)
        }
    }

    /** "Also play on phone" changed while Direct mode is on: tell the helper. */
    fun refresh() {
        worker.post { runCatching { ShellLink.service?.directOn(lastHint, Prefs.playOnPhone(c)) } }
    }

    /** Stops and ends the thread (the withdraw runs first). */
    fun quit() {
        stop()
        thread.quitSafely()
    }

    /**
     * Measures the delay on the new route (plays silence for a few seconds, on its own thread so a
     * [stop] is never held up) and reports a text for the connection details.
     */
    fun describe(done: (String) -> Unit) {
        val s = seq
        Thread({
            Thread.sleep(SETTLE_MS)
            val normal = runCatching { OutputLatency.measure(lowLatency = false) }.getOrNull()
            val fast = runCatching { OutputLatency.measure(lowLatency = true) }.getOrNull()
            main.post { if (s == seq) done(detailsText(normal, fast)) }
        }, "direct-measure").start()
    }

    /**
     * The card shows up a moment after plug-in (and after Bridge mode lets go of the Move), and
     * Android takes a moment to accept it: retry until [FIND_MS] have passed.
     */
    private fun announce(dev: UsbDevice, svc: IShellCapture, wanted: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + FIND_MS
        while (wanted() && SystemClock.elapsedRealtime() < deadline) {
            val hint = MoveCard.find(c, dev) ?: -1
            lastHint = hint
            if (runCatching { svc.directOn(hint, Prefs.playOnPhone(c)) }.getOrDefault(false)) {
                if (waitUntilRouted()) return true
                Diag.w("direct: card $hint announced but Android doesn't list it; retrying")
                runCatching { svc.directOff() }
            }
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** Android applies the announcement a moment later: wait until it lists the Move. */
    private fun waitUntilRouted(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ROUTE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isRouted(c)) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun detailsText(normal: OutputLatency.Result?, fast: OutputLatency.Result?): String {
        val route = if (Prefs.playOnPhone(c)) c.getString(R.string.direct_route_both) else normal?.route ?: fast?.route ?: "?"
        val head = c.getString(R.string.direct_details, route)
        if (normal == null || fast == null) return head
        return head + c.getString(R.string.direct_details_delay, normal.firstMs, fast.steadyMs)
    }

    companion object {
        /** Android lists the Move among its audio outputs: Direct mode is on. */
        fun isRouted(c: Context): Boolean =
            c.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::isMove)

        private fun isMove(d: AudioDeviceInfo) =
            d.type == AudioDeviceInfo.TYPE_USB_DEVICE && d.productName.contains("Move", ignoreCase = true)

        private const val FIND_MS = 2500L
        private const val ROUTE_MS = 1500L
        private const val POLL_MS = 100L
        /** Let playback settle on the new route before measuring it. */
        private const val SETTLE_MS = 1500L
    }
}

/**
 * The ALSA sound-card number Android reports for the plugged-in Move. Android does register the
 * Move's MIDI port, and that entry names the card the kernel created for the whole device.
 */
object MoveCard {
    /** MidiDeviceInfo.PROPERTY_ALSA_CARD (a hidden constant; the key itself is plain data). */
    private const val PROPERTY_ALSA_CARD = "alsa_card"

    /** Null if Android has no such entry (yet): the helper then looks for the card itself. */
    fun find(c: Context, dev: UsbDevice): Int? {
        if (Build.VERSION.SDK_INT < 33) return null
        val midi = c.getSystemService(MidiManager::class.java) ?: return null
        val info = midi.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
            .firstOrNull { usbName(it) == dev.deviceName }
        return info?.properties?.getInt(PROPERTY_ALSA_CARD, -1)?.takeIf { it >= 0 }
    }

    @RequiresApi(33)
    private fun usbName(info: MidiDeviceInfo): String? =
        info.properties.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java)?.deviceName
}
