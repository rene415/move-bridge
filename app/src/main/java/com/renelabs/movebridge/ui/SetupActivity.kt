package com.renelabs.movebridge.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.renelabs.movebridge.AudioOnly
import com.renelabs.movebridge.Prefs
import com.renelabs.movebridge.R
import com.renelabs.movebridge.ShellLink
import com.renelabs.movebridge.adb.Pairing
import com.renelabs.movebridge.adb.ShellStarter

/**
 * The guide for the one-time setup (it gives Move Bridge its helper, which Direct mode needs). It shows the four
 * steps, ticks them off as the phone's state changes, and its button always does the next thing.
 * Skipping is fine: the app then uses Android's screen-share permission instead.
 */
class SetupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var chips: List<TextView>
    private lateinit var next: Button
    private lateinit var skip: Button
    private lateinit var hint: TextView

    /** The user leaves this screen for Settings and comes back: re-check once a second while shown. */
    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        chips = StepList.build(this, findViewById<LinearLayout>(R.id.setupSteps), STEPS)
        next = findViewById(R.id.setupNext)
        hint = findViewById(R.id.setupHint)
        next.setOnClickListener { onNext() }
        skip = findViewById(R.id.setupSkip)
        skip.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    /** Which steps are done, in order: developer options, Wireless debugging, paired, helper running. */
    private fun done(): List<Boolean> {
        val paired = Prefs.adbPaired(this)
        val running = ShellLink.alive()
        val dev = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        return listOf(dev || paired, ShellStarter.wirelessDebuggingOn(this) || paired, paired, running)
    }

    private fun render() {
        val done = done()
        done.forEachIndexed { i, ok -> StepList.mark(chips[i], i + 1, ok) }
        val step = done.indexOf(false)
        next.setText(BUTTONS[if (step < 0) BUTTONS.lastIndex else step])
        hint.setText(HINTS[if (step < 0) HINTS.lastIndex else step])
        skip.visibility = if (step < 0) View.GONE else View.VISIBLE
    }

    private fun onNext() {
        when (done().indexOf(false)) {
            0 -> open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            1, 2 -> beginPairing()
            3 -> startHelper()
            else -> finish()
        }
    }

    /** Listens for the pairing screen (the code is typed into our notification) and opens Developer options. */
    private fun beginPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)  // then tap the button again
        }
        Pairing.begin(this)
        open(Pairing.developerSettingsIntent())
    }

    /** Paired, but the helper isn't running (after a restart): start it, or ask for Wireless debugging. */
    private fun startHelper() {
        if (AudioOnly.status(this) == AudioOnly.Status.CAN_START) AudioOnly.warmUp(this)
        else open(Pairing.developerSettingsIntent())
    }

    private fun open(i: Intent) {
        runCatching { startActivity(i) }.onFailure { hint.setText(R.string.err_dev_options_detail) }
    }

    private companion object {
        const val REFRESH_MS = 1000L
        val STEPS = intArrayOf(R.string.setup_step_1, R.string.setup_step_2, R.string.setup_step_3, R.string.setup_step_4)
        /** Button and hint for the first step that isn't done; the last entry is for "all done". */
        val BUTTONS = intArrayOf(R.string.setup_btn_about, R.string.setup_btn_wireless, R.string.setup_btn_wireless,
            R.string.setup_btn_start, R.string.setup_btn_done)
        val HINTS = intArrayOf(R.string.setup_hint_1, R.string.setup_hint_2, R.string.setup_hint_3,
            R.string.setup_hint_4, R.string.setup_hint_done)
    }
}
