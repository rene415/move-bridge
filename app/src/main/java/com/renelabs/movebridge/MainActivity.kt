package com.renelabs.movebridge

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import com.renelabs.movebridge.ui.SettingsPanel
import com.renelabs.movebridge.ui.StatusCard
import com.renelabs.movebridge.ui.StepList
import com.renelabs.movebridge.ui.Welcome

/** The app screen. Layout pieces live in [StatusCard], [SettingsPanel] and [StepList]; this class runs the start flow. */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_CONNECT_DEVICE = "connectDevice"
        const val EXTRA_CONNECT_TOKEN = "connectToken"
        private const val REQ_CAPTURE = 1
        private const val REQ_PERMS = 2
        private const val ACTION_USB_PERMISSION = "com.renelabs.movebridge.USB_PERMISSION"
        private const val KEY_PENDING_DEVICE = "pendingDevice"
        private const val KEY_PENDING_TONE = "pendingTone"
        private const val KEY_AWAITING_PERM = "awaitingPerm"
        private const val KEY_INTRO = "introVisible"
    }

    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private lateinit var status: StatusCard
    private lateinit var settings: SettingsPanel
    private lateinit var intro: View

    /** Device waiting on the intro / permission / capture prompt before we can stream. */
    private var pendingDevice: UsbDevice? = null
    private var pendingTone = false
    /** A start is waiting on the RECORD_AUDIO prompt; continue it when the user answers. */
    private var awaitingPerm = false
    private var resumed = false
    /** A Move is plugged in. Refreshed on resume and when a USB device comes or goes. */
    private var moveAttached = false

    private val refreshSettings = { runOnUiThread { settings.render() } }

    private val usbPermReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = usbDevice(i) ?: return
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) go(d, pendingTone)
            else showLocal(R.string.err_usb_denied_title, R.string.err_usb_denied_detail)
        }
    }

    private val plugReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refreshMove()
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        registerListeners()
        if (savedInstanceState != null) {
            // Recreated (rotation, theme switch): restore what was pending, and don't re-run the
            // plug-in intent, which was already handled the first time.
            restore(savedInstanceState)
            return
        }
        // A plug-in start asks for what it needs itself, and so does the setup guide on a first
        // start; otherwise ask up front.
        if (!handleConnect(intent) && !Welcome.onAppOpened(this)) requestMissingPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConnect(intent)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putParcelable(KEY_PENDING_DEVICE, pendingDevice)
        out.putBoolean(KEY_PENDING_TONE, pendingTone)
        out.putBoolean(KEY_AWAITING_PERM, awaitingPerm)
        out.putBoolean(KEY_INTRO, intro.visibility == View.VISIBLE)
    }

    override fun onResume() {
        super.onResume()
        BridgeService.listener = { s -> runOnUiThread { render(s) } }
        ShellLink.onChange = refreshSettings
        refreshMove()
        settings.render()
        AudioOnly.warmUp(this)  // get the helper running before the Move is plugged in
        resumed = true
        adoptDirect()
    }

    override fun onPause() {
        resumed = false
        BridgeService.listener = null
        ShellLink.onChange = null
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermReceiver) }
        runCatching { unregisterReceiver(plugReceiver) }
        status.release()
        super.onDestroy()
    }

    private fun bindViews() {
        status = StatusCard(this)
        settings = SettingsPanel(this, ::showLocal)
        intro = findViewById(R.id.intro)
        status.primary.setOnClickListener { onPrimary() }
        status.tone.setOnClickListener { onTone() }
        findViewById<Button>(R.id.introContinue).setOnClickListener { onIntroContinue() }
        StepList.build(this)
        bindLinks()
    }

    /** The beta card: feedback, what's new, the setup guide and the Quick Settings tile. */
    private fun bindLinks() {
        findViewById<Button>(R.id.feedback).setOnClickListener { Welcome.openFeedback(this) }
        findViewById<View>(R.id.linkWhatsNew).setOnClickListener { Welcome.showWhatsNew(this) }
        findViewById<View>(R.id.linkSetup).setOnClickListener { Welcome.openSetup(this) }
        findViewById<View>(R.id.linkLicenses).setOnClickListener { Welcome.showLicenses(this) }
        findViewById<View>(R.id.linkManual).setOnClickListener { Welcome.openManual(this) }
        findViewById<View>(R.id.linkTile).apply {
            visibility = if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
            setOnClickListener { Welcome.offerTile(this@MainActivity) }
        }
    }

    private fun registerListeners() {
        val f = IntentFilter(ACTION_USB_PERMISSION)
        registerNotExported(usbPermReceiver, f)
        val plug = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        registerNotExported(plugReceiver, plug)
    }

    private fun refreshMove() {
        moveAttached = findMove() != null
        render(BridgeService.state)
    }

    private fun restore(b: Bundle) {
        @Suppress("DEPRECATION")
        pendingDevice = if (Build.VERSION.SDK_INT >= 33) b.getParcelable(KEY_PENDING_DEVICE, UsbDevice::class.java)
        else b.getParcelable(KEY_PENDING_DEVICE)
        pendingTone = b.getBoolean(KEY_PENDING_TONE)
        awaitingPerm = b.getBoolean(KEY_AWAITING_PERM)
        if (b.getBoolean(KEY_INTRO)) intro.visibility = View.VISIBLE
    }

    private fun render(s: BridgeState) =
        status.render(s, canGetReady = !moveAttached && Prefs.stayArmed(this), moveAttached = moveAttached)

    /** Shows an error that only this screen knows about (no service involved). */
    private fun showLocal(title: Int, detail: Int) =
        BridgeService.publish(BridgeState(Phase.ERROR, getString(title), getString(detail)))

    // ---------- plug-in ----------

    /**
     * Opened by ConnectActivity with the plugged-in Move (intro not seen yet, or auto-start off).
     * Returns true if it started (or is starting) a connection.
     */
    private fun handleConnect(i: Intent?): Boolean {
        if (i == null || i.getStringExtra(EXTRA_CONNECT_TOKEN) != ConnectActivity.CONNECT_TOKEN) return false
        @Suppress("DEPRECATION")
        val d = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_CONNECT_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(EXTRA_CONNECT_DEVICE)) ?: return false
        i.removeExtra(EXTRA_CONNECT_DEVICE)
        i.removeExtra(EXTRA_CONNECT_TOKEN)
        if (!usb.isAttachedMove(d) || !Prefs.autoStart(this)) return false
        go(d, tone = false)
        return true
    }

    /**
     * Direct mode outlives the app: the helper keeps Android playing to the Move while it stays
     * plugged in, even if this app was closed or updated. If that's the case when the app opens,
     * show it (and bring the Stop button back) instead of "not connected".
     */
    private fun adoptDirect() {
        if (BridgeService.hasRun) return  // the service has reported: its state is current
        AudioOnly.whenKnown(this) { usable ->
            val d = findMove()
            val fresh = resumed && !BridgeService.hasRun
            if (fresh && usable && d != null && usb.hasPermission(d) && DirectLink.isRouted(this))
                go(d, tone = false)
        }
    }

    // ---------- permissions ----------

    private fun missingPermissions(): List<String> {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        return needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestMissingPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS || !awaitingPerm) return
        awaitingPerm = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            go(pendingDevice, pendingTone)  // carry on where we left off
        else showLocal(R.string.err_allow_audio_title, R.string.err_allow_audio_detail)
    }

    // ---------- actions ----------

    private fun findMove(): UsbDevice? = usb.deviceList.values.firstOrNull { it.isMove() }

    private fun onPrimary() {
        if (BridgeService.state.phase in setOf(Phase.STREAMING, Phase.TONE, Phase.ARMED)) {
            startService(BridgeService.intent(this, BridgeService.ACTION_STOP))
            return
        }
        val d = findMove()
        when {
            d != null -> withUsbPermission(d, tone = false)
            Prefs.stayArmed(this) -> go(null, tone = false)  // get ready before plugging in
            else -> showLocal(R.string.err_not_found_title, R.string.err_not_found_detail)
        }
    }

    private fun onTone() {
        val d = findMove() ?: return showLocal(R.string.err_not_found_title, R.string.err_not_found_detail)
        withUsbPermission(d, tone = true)
    }

    private fun withUsbPermission(d: UsbDevice, tone: Boolean) {
        if (usb.hasPermission(d)) return go(d, tone)
        pendingTone = tone
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(d, pi)
    }

    /** Start streaming to [d] (or just get ready when d is null). */
    private fun go(d: UsbDevice?, tone: Boolean) {
        if (d != null && (tone || BridgeService.captureOpen || AudioOnly.usable(this))) {
            // Test tone, capture already open, or the helper is there: no prompt needed.
            val action = if (tone) BridgeService.ACTION_TONE else BridgeService.ACTION_ATTACH
            startForegroundService(BridgeService.intent(this, action, d))
            return
        }
        pendingDevice = d
        pendingTone = tone
        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                awaitingPerm = true  // onRequestPermissionsResult continues the start
                requestPermissions(missingPermissions().toTypedArray(), REQ_PERMS)
            }
            !Prefs.introSeen(this) -> intro.visibility = View.VISIBLE
            else -> askCaptureConsent()
        }
    }

    private fun onIntroContinue() {
        Prefs.setIntroSeen(this)
        intro.visibility = View.GONE
        askCaptureConsent()
    }

    private fun askCaptureConsent() {
        @Suppress("DEPRECATION")
        startActivityForResult(captureConsentIntent(this), REQ_CAPTURE)
    }

    @Deprecated("Activity result API kept simple on purpose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null)
            return showLocal(R.string.err_not_started_title, R.string.err_not_started_detail)
        startForegroundService(armIntent(this, resultCode, data, pendingDevice))
        pendingDevice = null
    }
}
