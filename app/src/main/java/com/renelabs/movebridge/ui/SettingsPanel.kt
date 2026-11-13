package com.renelabs.movebridge.ui

import android.app.Activity
import android.view.View
import android.widget.Switch
import android.widget.TextView
import com.renelabs.movebridge.AudioOnly
import com.renelabs.movebridge.AudioOnly.Status
import com.renelabs.movebridge.BridgeService
import com.renelabs.movebridge.Phase
import com.renelabs.movebridge.Prefs
import com.renelabs.movebridge.Quiet
import com.renelabs.movebridge.R
import com.renelabs.movebridge.ShellLink
import com.renelabs.movebridge.adb.Pairing

/**
 * The Settings card:
 *   (Finish the one-time setup / Turn Wireless debugging on)   only while that needs doing; no switch
 *   Start when Move connects
 *   Mute notifications while sending
 *   Also play on phone         (once the setup is done)
 *   Start automatically        (once the setup is done)
 *   Stay ready in background   (without the setup: only matters for screen-share capture)
 *
 * There is no switch for how audio gets to the Move. With the setup done, Android plays straight
 * to it; without, Move Bridge falls back to Android's screen-share capture by itself.
 * [onLocalError] shows a message on the status card (title, detail res ids).
 */
class SettingsPanel(private val a: Activity, private val onLocalError: (Int, Int) -> Unit) {

    /** One row from layout/setting_row.xml. */
    private class Row(val root: View, title: Int, detail: Int) {
        val toggle: Switch = root.findViewById(R.id.rowSwitch)
        val title: TextView = root.findViewById(R.id.rowTitle)
        val detail: TextView = root.findViewById(R.id.rowDetail)
        val body: View = root.findViewById(R.id.rowBody)
        val divider: View = root.findViewById(R.id.rowDivider)

        init {
            // Every row's switch has the same id (one shared layout), so Android's automatic view-state
            // restore would copy one row's state onto all of them. Prefs are the source of truth.
            toggle.isSaveEnabled = false
            this.title.setText(title)
            this.detail.setText(detail)
        }

        fun bind(initial: Boolean, onChange: (Boolean) -> Unit) {
            toggle.isChecked = initial
            toggle.setOnCheckedChangeListener { _, v -> onChange(v) }
            body.setOnClickListener { toggle.toggle() }
        }
    }

    private val setup = Row(a.findViewById(R.id.rowSetup), R.string.setup_row_title, R.string.setup_row_body)
    private val autoStart = Row(a.findViewById(R.id.rowAuto), R.string.auto_start_title, R.string.auto_start_body)
    private val mute = Row(a.findViewById(R.id.rowMute), R.string.mute_title, R.string.mute_body)
    private val playOnPhone = Row(a.findViewById(R.id.rowPlayOnPhone), R.string.play_on_phone_title, R.string.play_on_phone_body)
    private val autoRestart = Row(a.findViewById(R.id.rowAutoRestart), R.string.auto_restart_title, R.string.auto_restart_body)
    private val stayReady = Row(a.findViewById(R.id.rowArmed), R.string.stay_ready_title, R.string.stay_ready_body)

    init {
        bindSetupRow()
        autoStart.bind(Prefs.autoStart(a)) { Prefs.setAutoStart(a, it) }
        mute.bind(Prefs.muteWhileSending(a), ::onMute)
        playOnPhone.bind(Prefs.playOnPhone(a), ::onPlayOnPhone)
        autoRestart.bind(Prefs.autoRestart(a), ::onAutoRestart)
        stayReady.bind(Prefs.stayArmed(a), ::onStayReady)
    }

    /** Not a setting but a to-do: it has no switch, and tapping it opens what needs doing. */
    private fun bindSetupRow() {
        setup.toggle.visibility = View.GONE
        setup.divider.visibility = View.GONE
        setup.title.setTextColor(a.getColor(R.color.accent_text))
        setup.body.setOnClickListener {
            if (AudioOnly.status(a) == Status.NEEDS_WIRELESS_DEBUGGING) openDeveloperSettings() else Welcome.openSetup(a)
        }
    }

    /** Applies straight away while sending: on vibrate now, or back to normal. */
    private fun onMute(on: Boolean) {
        Prefs.setMuteWhileSending(a, on)
        if (BridgeService.state.phase !in setOf(Phase.STREAMING, Phase.TONE)) return
        Quiet(a).let { if (on) it.on() else it.restore() }
    }

    private fun onStayReady(on: Boolean) {
        Prefs.setStayArmed(a, on)
        // Turning it off while only "ready" (nothing plugged in) releases the capture.
        if (!on && BridgeService.state.phase == Phase.ARMED) sendToService(BridgeService.ACTION_STOP)
    }

    private fun onAutoRestart(on: Boolean) {
        Prefs.setAutoRestart(a, on)
        // A running helper can grant the permission now; otherwise it's granted at its next start.
        val svc = ShellLink.service
        if (on && svc != null) Thread { runCatching { svc.grantSecureSettings() } }.start()
        render()
    }

    /** Applies straight away: the service switches the phone's speaker in or out of a live connection. */
    private fun onPlayOnPhone(on: Boolean) {
        Prefs.setPlayOnPhone(a, on)
        if (BridgeService.state.phase == Phase.STREAMING) sendToService(BridgeService.ACTION_RECAPTURE)
    }

    private fun sendToService(action: String) = a.startService(BridgeService.intent(a, action))

    private fun openDeveloperSettings() {
        runCatching { a.startActivity(Pairing.developerSettingsIntent()) }
            .onFailure { onLocalError(R.string.err_dev_options_title, R.string.err_dev_options_detail) }
    }

    /** Shows the rows that apply to the phone's current state. */
    fun render() {
        val status = AudioOnly.status(a)
        val todo = status == Status.NOT_SET_UP || status == Status.NEEDS_WIRELESS_DEBUGGING
        val needsWireless = status == Status.NEEDS_WIRELESS_DEBUGGING
        setup.root.visibility = if (todo) View.VISIBLE else View.GONE
        setup.title.setText(if (needsWireless) R.string.setup_row_wd_title else R.string.setup_row_title)
        setup.detail.setText(if (needsWireless) R.string.setup_row_wd_body else R.string.setup_row_body)
        autoStart.divider.visibility = if (todo) View.VISIBLE else View.GONE  // first row otherwise
        // Paired at some point: the helper's options apply. Otherwise it's screen-share capture.
        val setUp = status != Status.NOT_SET_UP && status != Status.UNSUPPORTED
        playOnPhone.root.visibility = if (setUp) View.VISIBLE else View.GONE
        autoRestart.root.visibility = if (setUp) View.VISIBLE else View.GONE
        stayReady.root.visibility = if (setUp) View.GONE else View.VISIBLE
    }
}
