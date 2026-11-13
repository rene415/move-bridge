package com.renelabs.movebridge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.util.UUID

/**
 * Invisible activity that Android launches when the Move is plugged in.
 * It connects with no screens in between:
 *  - capture already open ("stay ready")  -> attach the Move straight away
 *  - first time ever                      -> show the one-time "audio only" explanation
 *  - otherwise                            -> ask Android for capture (instant if pre-approved), then stream
 * and then closes, so you stay in whatever app you were using.
 *
 * This activity is exported (Android has to be able to start it), so it only acts on a real
 * USB_DEVICE_ATTACHED intent for a Move that is actually plugged in.
 */
class ConnectActivity : Activity() {

    private var device: UsbDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = intent?.takeIf { it.action == UsbManager.ACTION_USB_DEVICE_ATTACHED }?.let { usbDevice(it) }
        if (d == null || !getSystemService(UsbManager::class.java).isAttachedMove(d)) { finish(); return }
        device = d
        if (savedInstanceState != null) return  // recreated while the consent screen is up: wait for its result

        // Give the helper's binder a moment to arrive (cold start), then pick the capture path.
        AudioOnly.whenKnown(this) { audioOnly -> if (!isFinishing) route(d, audioOnly) }
    }

    private fun route(d: UsbDevice, audioOnly: Boolean) {
        when {
            !Prefs.autoStart(this) -> openApp(d)
            // Audio-only mode, or capture already open: the service needs nothing else from us.
            audioOnly || BridgeService.captureOpen -> {
                startForegroundService(BridgeService.intent(this, BridgeService.ACTION_ATTACH, d))
                finish()
            }
            !Prefs.introSeen(this) ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> openApp(d)
            else -> {
                @Suppress("DEPRECATION")
                startActivityForResult(captureConsentIntent(this), REQ_CAPTURE)
            }
        }
    }

    private fun openApp(d: UsbDevice) {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONNECT_DEVICE, d)
            .putExtra(MainActivity.EXTRA_CONNECT_TOKEN, CONNECT_TOKEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Deprecated("Activity result API kept simple on purpose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val d = device
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null && d != null) {
                startForegroundService(armIntent(this, resultCode, data, d))
            } else {
                // The user said no. Tell them, since this activity has no screen of its own.
                Toast.makeText(this, R.string.toast_not_allowed, Toast.LENGTH_LONG).show()
                BridgeService.publish(BridgeState(Phase.ERROR,
                    getString(R.string.err_not_started_title), getString(R.string.err_not_started_detail)))
            }
        }
        finish()
    }

    companion object {
        const val REQ_CAPTURE = 7
        /**
         * Per-process secret passed to MainActivity with a device. MainActivity is exported (it's
         * the launcher screen), so this proves the device came from us and not another app.
         */
        val CONNECT_TOKEN: String = UUID.randomUUID().toString()
    }
}

/** Android's capture-consent intent. Returns instantly when the app is pre-approved. */
fun captureConsentIntent(c: Context): Intent {
    val mpm = c.getSystemService(MediaProjectionManager::class.java)
    return if (Build.VERSION.SDK_INT >= 34)
        mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    else mpm.createScreenCaptureIntent()
}

fun armIntent(c: Context, code: Int, data: Intent, device: UsbDevice?): Intent =
    BridgeService.intent(c, BridgeService.ACTION_ARM, device)
        .putExtra(BridgeService.EXTRA_RESULT_CODE, code)
        .putExtra(BridgeService.EXTRA_RESULT_DATA, data)
