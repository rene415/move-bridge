package com.renelabs.movebridge

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.renelabs.movebridge.capture.CaptureSource
import com.renelabs.movebridge.capture.PlaybackWatcher
import com.renelabs.movebridge.capture.VolumeHold
import com.renelabs.movebridge.capture.ProjectionCapture
import com.renelabs.movebridge.capture.ShellCapture

/**
 * Foreground service that gets the phone's audio to the Move, one of two ways:
 *  - Direct mode ([DirectLink]): Android itself plays to the Move, like USB-C headphones. Needs
 *    the shell helper (the one-time setup). Nothing is captured. Always used when possible.
 *  - Bridge mode: capture ([CaptureSource], via MediaProjection or the shell helper) plus our own
 *    USB streaming ([UsbLink]). Works on any phone, and is the fallback when Direct can't start.
 * Which one runs is not a setting: the service picks the best one the phone can do right now.
 * With "stay ready", MediaProjection capture stays open between plug-ins so a plug-in only needs
 * the USB part.
 */
class BridgeService : Service() {

    companion object {
        const val ACTION_ARM = "com.renelabs.movebridge.ARM"        // + projection result, optional device
        const val ACTION_ATTACH = "com.renelabs.movebridge.ATTACH"  // + device
        const val ACTION_TONE = "com.renelabs.movebridge.TONE"      // + device
        const val ACTION_STOP = "com.renelabs.movebridge.STOP"
        /** Restart audio-only capture (e.g. "Also play on phone" changed); the Move stays connected. */
        const val ACTION_RECAPTURE = "com.renelabs.movebridge.RECAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val TICK_MS = 100L
        private const val STATS_EVERY_TICKS = 20  // stats() resets its averages: read it every 2 s

        @Volatile var state = BridgeState(Phase.IDLE, "", "")
            private set
        @Volatile var listener: ((BridgeState) -> Unit)? = null
        /** Called when the phase changes (the Quick Settings tile shows it). */
        @Volatile var tileListener: (() -> Unit)? = null
        /** The service has run in this process, so [state] is what it last reported. */
        @Volatile var hasRun = false
            private set
        /** True while capture is open, so a plug-in can skip the permission step. */
        @Volatile var captureOpen = false
            private set

        /** Publishes a new state to the app screen (also used by the activities for local errors). */
        fun publish(s: BridgeState) {
            val phaseChanged = s.phase != state.phase
            state = s
            listener?.invoke(s)
            if (phaseChanged) tileListener?.invoke()
        }

        fun intent(c: Context, action: String, device: UsbDevice? = null) =
            Intent(c, BridgeService::class.java).setAction(action).apply {
                if (device != null) putExtra(UsbManager.EXTRA_DEVICE, device)
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var usb: UsbLink
    private lateinit var direct: DirectLink
    private lateinit var notifier: BridgeNotifier
    private lateinit var volume: VolumeHold
    private lateinit var quiet: Quiet
    private val tonePlayer = TonePlayer()
    /** Re-routes apps that start playing mid-stream (Bridge mode with audio-only capture). */
    private var watcher: PlaybackWatcher? = null

    private var capture: CaptureSource? = null
    /** Bumped when capture closes, so a shell capture that opens afterwards is discarded. */
    private var captureSeq = 0
    /** Bumped when a connection attempt starts or is cancelled, so late results are dropped. */
    private var connectSeq = 0
    private var connecting = false
    /** Bridge mode is streaming: we hold the Move's USB audio interfaces. */
    private var usbActive = false
    /** Direct mode is on: Android itself plays to the Move. */
    private var directActive = false
    /** Direct mode was wanted but couldn't start, so this connection uses Bridge mode. */
    private var directFellBack = false
    private var toneActive = false
    private var attached: UsbDevice? = null
    private var receiverOn = false
    private var ticks = 0
    private var lastStats = ""

    private val ticker = object : Runnable {
        override fun run() = tick()
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = usbDevice(i) ?: return
            if (isLive() && d.deviceName == attached?.deviceName) onUsbLost(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        hasRun = true
        usb = UsbLink(this)
        direct = DirectLink(this)
        notifier = BridgeNotifier(this)
        volume = VolumeHold(this)
        volume.restore()   // in case we were killed mid-stream with the volume held
        quiet = Quiet(this)
        quiet.restore()    // same, for the ringer mode
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dev = intent?.let { usbDevice(it) }
        when (intent?.action) {
            ACTION_ARM -> onArm(intent, dev)
            ACTION_ATTACH -> onConnect(dev, tone = false)
            ACTION_TONE -> onConnect(dev, tone = true)
            ACTION_STOP -> shutdown()
            ACTION_RECAPTURE -> recapture()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        closeCapture()
        unregisterDetach()
        tonePlayer.stop()
        usb.quit()
        direct.quit()
        super.onDestroy()
    }

    // ---------- commands ----------

    /** MediaProjection consent came back: open capture, then connect (or just get ready). */
    private fun onArm(intent: Intent, dev: UsbDevice?) {
        notifier.goForeground(projection = true, usb = dev != null)
        if (!openProjection(intent)) return
        if (dev != null) attach(dev, toneMode = false) else showArmed()
    }

    /** Start sending (or the test tone) to [dev], by the best route available. */
    private fun onConnect(dev: UsbDevice?, tone: Boolean) {
        notifier.goForeground(projection = capture?.isProjection == true, usb = true)
        directFellBack = false
        when {
            dev == null -> fail(getString(R.string.err_not_found_title))
            AudioOnly.usable(this) -> startDirect(dev, tone)  // the helper is there: let Android play to the Move
            else -> startBridge(dev, tone)
        }
    }

    /** Bridge mode: capture the phone's audio (the test tone needs none) and send it ourselves. */
    private fun startBridge(dev: UsbDevice, tone: Boolean) {
        when {
            tone || capture != null -> attach(dev, tone)
            AudioOnly.usable(this) -> openShell { ok -> if (ok) attach(dev, toneMode = false) else shellFailed() }
            else -> fail(getString(R.string.svc_capture_not_ready))
        }
    }

    /** A command arrived with nothing to act on: don't linger as a started service. */
    private fun stopIfIdle() {
        if (!isLive() && !connecting && capture == null) stopSelf()
    }

    private fun isLive() = usbActive || directActive

    // ---------- direct mode ----------

    /**
     * Direct mode: Android plays to the Move itself. A Bridge capture that is still open stays
     * open until Android has taken over, so playing apps never drop to the phone speaker in between.
     */
    private fun startDirect(dev: UsbDevice, tone: Boolean, retry: Boolean = true) {
        val s = beginConnect()
        stopCaptureExtras()  // normal volume again before Android switches outputs
        detachUsb()        // Bridge mode must let go of the Move: that brings its sound card back
        AudioOnly.withHelper(this, main) { svc ->
            when {
                s != connectSeq -> Unit  // stopped or replaced meanwhile
                // No helper after all. The test tone can still go out over USB; sending needs capture.
                svc == null -> if (tone) attach(dev, toneMode = true) else shellFailed()
                else -> direct.start(dev, svc) { ok -> onDirectResult(ok, dev, tone, retry) }
            }
        }
    }

    private fun onDirectResult(ok: Boolean, dev: UsbDevice, tone: Boolean, retry: Boolean) {
        when {
            ok -> directStarted(dev, tone)
            // The Move's sound card is missing when Bridge mode ended uncleanly (app killed or
            // updated mid-stream): bring it back and try once more.
            retry -> usb.revive(dev) { startDirect(dev, tone, retry = false) }
            else -> directFailed(dev, tone)
        }
    }

    private fun directStarted(dev: UsbDevice, tone: Boolean) {
        closeCapture()  // Android has taken over: a Bridge capture kept open until now can go
        directActive = true
        if (tone) tonePlayer.start()
        connected(dev, tone)
        direct.describe { text -> if (directActive) showStats(text) }
    }

    /** The Move's sound card didn't show up for Android: capture the audio and send it ourselves instead. */
    private fun directFailed(dev: UsbDevice, tone: Boolean) {
        Diag.w("direct mode not available: using bridge mode")
        directFellBack = true
        startBridge(dev, tone)
    }

    private fun detachDirect() {
        tonePlayer.stop()
        directActive = false
        direct.stop()
    }

    // ---------- capture ----------

    private fun openProjection(intent: Intent): Boolean {
        if (capture != null) return true
        val data = projectionData(intent) ?: run { fail(getString(R.string.svc_not_granted)); return false }
        return try {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            setCapture(ProjectionCapture.open(this, code, data, main, ::onCaptureEnded))
            true
        } catch (e: Exception) {
            Diag.e("capture failed", e)
            fail(getString(R.string.svc_capture_failed), e.message ?: "")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)

    /** Capture through the shell helper, with no MediaProjection. [done] runs on the main thread. */
    private fun openShell(announce: Boolean = true, done: (Boolean) -> Unit) {
        val seq = captureSeq
        if (announce) publish(BridgeState(Phase.IDLE, getString(R.string.state_connecting), ""))
        ShellCapture.open(this, main, { seq == captureSeq }, ::onCaptureEnded) { cap ->
            if (cap != null) setCapture(cap)
            done(cap != null)
        }
    }

    /**
     * "Also play on phone" changed while sending. With the helper's capture: reopens it with the
     * new setting, keeping the USB stream running (the helper registers the new routing before
     * dropping the old one, so there's no gap or blip).
     */
    private fun recapture() {
        if (directActive) return direct.refresh()  // Direct mode: the helper switches the phone speaker on or off
        if (usbActive && capture?.isProjection == true) return applyVolume()  // Android's capture: only the volume changes
        val old = capture as? ShellCapture ?: return stopIfIdle()  // nothing open
        old.close(stopHelper = false)
        capture = null
        openShell(announce = false) { ok -> if (ok) applyVolume() else shellFailed() }
    }

    /** "Also play on phone" off: hold the phone's volume where the capture in use needs it (see [VolumeHold]). */
    private fun applyVolume() {
        val silent = !Prefs.playOnPhone(this)
        when {
            silent && capture is ShellCapture -> volume.max()
            silent && capture?.isProjection == true -> volume.zero()
            else -> volume.restore()
        }
    }

    /** While captured audio is being sent: the phone's volume, and (helper capture) re-routing of apps that start later. */
    private fun startCaptureExtras() {
        applyVolume()
        if (capture !is ShellCapture || watcher != null) return
        watcher = PlaybackWatcher(this, { Prefs.playOnPhone(this) }) { if (usbActive && !toneActive) recapture() }
            .also { it.start() }
    }

    private fun stopCaptureExtras() {
        watcher?.stop()
        watcher = null
        volume.restore()
    }

    private fun shellFailed() = fail(getString(R.string.svc_shell_failed), getString(R.string.svc_shell_failed_detail))

    private fun setCapture(c: CaptureSource) {
        capture = c
        captureOpen = true
    }

    private fun closeCapture() {
        stopCaptureExtras()
        captureSeq++
        capture?.close()
        capture = null
        captureOpen = false
    }

    /** Capture ended: Android stopped it (reason null) or the recorder died. */
    private fun onCaptureEnded(reason: String?) {
        if (capture == null) return
        closeCapture()
        if (usbActive && toneActive) {
            notifier.goForeground(projection = false, usb = true)  // the test tone doesn't need capture
            return
        }
        val msg = if (reason == ProjectionCapture.DIED) getString(R.string.svc_capture_stopped) else reason
        shutdown(msg, error = msg != null)
    }

    // ---------- bridge mode: usb ----------

    private fun attach(dev: UsbDevice, toneMode: Boolean) {
        beginConnect()
        main.removeCallbacks(ticker)
        usbActive = false
        detachDirect()  // the two routes never run together: Direct needs the interfaces we take here
        usb.attach(dev, toneMode) { r ->
            when (r) {
                is UsbLink.Result.Ok -> attached(dev, toneMode)
                is UsbLink.Result.Failed -> attachFailed(r.title, r.detail)
            }
        }
    }

    private fun attached(dev: UsbDevice, toneMode: Boolean) {
        usbActive = true
        connected(dev, toneMode)
        if (!toneMode) startCaptureExtras()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun attachFailed(title: String, detail: String) {
        usbActive = false
        attached = null
        fail(title, detail)
    }

    private fun detachUsb() {
        main.removeCallbacks(ticker)
        usbActive = false
        usb.detach()
    }

    /** Lets go of the Move on both routes. */
    private fun detachLinks() {
        detachUsb()
        detachDirect()
        stopCaptureExtras()  // "stay ready" keeps the capture open, but nothing is being sent now
        quiet.restore()
        toneActive = false
        attached = null
    }

    /** The Move was unplugged or the stream died. */
    private fun onUsbLost(reason: String?) {
        detachLinks()
        // "Stay ready" only matters for MediaProjection; the other routes restart instantly.
        if (capture?.isProjection == true && Prefs.stayArmed(this)) showArmed(reason)
        else shutdown(reason, error = reason != null)
    }

    private fun tick() {
        if (!usbActive) return
        if (!NativeBridge.isRunning()) return onUsbLost(getString(R.string.svc_stream_stopped))
        if (++ticks % STATS_EVERY_TICKS == 0) {
            lastStats = NativeBridge.stats()
            Diag.stats(lastStats)
        }
        publish(state.copy(stats = lastStats, level = NativeBridge.level()))
        main.postDelayed(ticker, TICK_MS)
    }

    private fun registerDetach() {
        if (receiverOn) return
        val f = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerNotExported(detachReceiver, f)
        receiverOn = true
    }

    private fun unregisterDetach() {
        if (receiverOn) runCatching { unregisterReceiver(detachReceiver) }
        receiverOn = false
    }

    // ---------- state ----------

    /** Shows "Connecting" and returns the number of this attempt (see [connectSeq]). */
    private fun beginConnect(): Int {
        connecting = true
        val phase = state.phase.takeIf { it != Phase.ERROR } ?: Phase.IDLE
        publish(BridgeState(phase, getString(R.string.state_connecting), ""))
        return ++connectSeq
    }

    /** Audio now reaches the Move, by either route. */
    private fun connected(dev: UsbDevice, tone: Boolean) {
        connecting = false
        toneActive = tone
        attached = dev
        lastStats = ""
        registerDetach()
        publish(liveState(tone))
        val text = when {
            tone -> R.string.svc_notif_tone
            directActive -> R.string.svc_notif_direct
            else -> R.string.svc_notif_stream
        }
        notifier.show(getString(text), if (tone) R.string.notif_stop_tone else R.string.notif_stop_sending)
        quiet.on()
        AudioOnly.claimMoveByDefault()  // from now on Android opens Move Bridge for the Move without asking
        Diag.i("connected: direct=$directActive tone=$tone")
    }

    private fun liveState(tone: Boolean): BridgeState = when {
        tone -> BridgeState(Phase.TONE, getString(R.string.svc_tone_title),
            getString(if (directActive) R.string.svc_tone_detail_direct else R.string.svc_tone_detail),
            direct = directActive)
        directActive -> BridgeState(Phase.STREAMING, getString(R.string.svc_direct_title),
            getString(R.string.svc_direct_detail), direct = true)
        else -> BridgeState(Phase.STREAMING, getString(R.string.svc_stream_title), streamDetail())
    }

    /** Bridge mode's detail text, with a note when Direct mode was wanted but couldn't start. */
    private fun streamDetail(): String {
        val note = if (directFellBack) "\n" + getString(R.string.svc_direct_fallback) else ""
        return getString(R.string.svc_stream_detail) + note
    }

    private fun showStats(text: String) {
        lastStats = text
        Diag.lastStats = text
        publish(state.copy(stats = text))
    }

    private fun showArmed(reason: String? = null) {
        connecting = false
        notifier.goForeground(projection = true, usb = false)
        publish(BridgeState(Phase.ARMED, getString(R.string.svc_armed_title), reason ?: getString(R.string.svc_armed_detail)))
        notifier.show(getString(R.string.svc_armed_title), R.string.btn_stop_ready)
    }

    private fun fail(title: String, detail: String = "") {
        Diag.w("fail: $title / $detail")
        connecting = false
        // Don't keep capturing (and showing Android's capture icon) for nothing unless the user
        // asked to stay ready.
        val keepArmed = capture?.isProjection == true && Prefs.stayArmed(this)
        if (capture != null && !isLive() && !keepArmed) closeCapture()
        publish(BridgeState(Phase.ERROR, title, detail))
        if (capture != null && !isLive()) notifier.show(getString(R.string.svc_armed_title), R.string.btn_stop_ready)
        if (!isLive() && capture == null) {
            notifier.remove()
            stopSelf()
        }
    }

    private fun shutdown(reason: String? = null, error: Boolean = false) {
        connectSeq++
        connecting = false
        detachLinks()
        closeCapture()
        unregisterDetach()
        // No title when idle: the screen picks one, depending on whether a Move is plugged in.
        publish(if (error && reason != null) BridgeState(Phase.ERROR, reason, "") else BridgeState(Phase.IDLE, "", reason ?: ""))
        notifier.remove()
        stopSelf()
    }
}
