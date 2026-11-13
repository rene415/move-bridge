package com.renelabs.movebridge.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import com.renelabs.movebridge.AudioOnly
import com.renelabs.movebridge.BridgeTile
import com.renelabs.movebridge.BuildConfig
import com.renelabs.movebridge.Crash
import com.renelabs.movebridge.Prefs
import com.renelabs.movebridge.R

/**
 * What the app shows by itself when it opens:
 *  - the very first time: the setup guide
 *  - the first time after an update: what's new in this version
 *  - after a crash: an offer to send the crash report
 * At most one of them per start.
 */
object Welcome {
    private const val MANUAL_SAMPLING_URL = "https://www.ableton.com/en/move/manual/#sampling-audio-via-usb-c"

    /** Returns true if the setup guide was opened (the caller then leaves the permission prompts to it). */
    fun onAppOpened(a: Activity): Boolean {
        val seen = Prefs.seenVersion(a)
        Prefs.setSeenVersion(a, BuildConfig.VERSION_CODE)
        val firstRun = seen == 0 && freshInstall(a)
        val setup = firstRun && AudioOnly.status(a) == AudioOnly.Status.NOT_SET_UP
        when {
            setup -> openSetup(a)
            seen < BuildConfig.VERSION_CODE && !firstRun -> showWhatsNew(a)
            Crash.pending(a) != null -> offerCrashReport(a)
        }
        return setup
    }

    fun openSetup(a: Activity) = a.startActivity(Intent(a, SetupActivity::class.java))

    fun openFeedback(a: Activity, kind: Int = 0) =
        a.startActivity(Intent(a, FeedbackActivity::class.java).putExtra(FeedbackActivity.EXTRA_KIND, kind))

    fun showWhatsNew(a: Activity) {
        AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.whats_new_title, BuildConfig.VERSION_NAME))
            .setMessage(R.string.whats_new_body)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    /** Ableton's manual has the pictures of the Move's screen for these steps: open that section. */
    fun openManual(a: Activity) {
        runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MANUAL_SAMPLING_URL))) }
    }

    /** Move Bridge's own license, the notices of the code it includes, and both license texts. */
    fun showLicenses(a: Activity) {
        val text = listOf(R.raw.notices, R.raw.license_apache, R.raw.license_lgpl).joinToString("\n\n") { id ->
            a.resources.openRawResource(id).bufferedReader().use { it.readText() }
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.link_licenses)
            .setMessage(text)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun offerCrashReport(a: Activity) {
        AlertDialog.Builder(a)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_body)
            .setPositiveButton(R.string.crash_review) { _, _ -> openFeedback(a, FeedbackActivity.KIND_CRASH) }
            .setNegativeButton(R.string.crash_dismiss) { _, _ -> Crash.clear(a) }
            .show()
    }

    /** Asks Android to offer adding the Quick Settings tile (Android 13+; before that it's added by hand). */
    fun offerTile(a: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        a.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(a, BridgeTile::class.java), a.getString(R.string.app_name),
            Icon.createWithResource(a, R.drawable.ic_notification), a.mainExecutor) { }
    }

    /** Installed and never updated: a first start, not an update from a version without these notes. */
    private fun freshInstall(a: Activity): Boolean {
        val info = a.packageManager.getPackageInfo(a.packageName, 0)
        return info.firstInstallTime == info.lastUpdateTime
    }
}
