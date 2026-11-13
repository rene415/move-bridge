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
 * The Settings card. A regular user sees:
 *   Start when Move connects
 *   Mute notifications while sending
 *   Also play on phone
 *   Stay ready in background
 *   Advanced mode
 * Advanced mode is the one switch that reveals everything technical:
 *   a to-do line while the one-time setup needs something (no switch; tap it)
 *   Start automatically        (once the setup is done; replaces Stay ready)
 *   and, outside this card, the connection details and the setup guide link.
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
        private var onChange: ((Boolean) -> Unit)? = null

        init {
            // Every row's switch has the same id (one shared layout), so Android's automatic view-state
            // restore would copy one row's state onto all of them. Prefs are the source of truth.
            toggle.isSaveEnabled = false
            this.title.setText(title)
            this.detail.setText(detail)
            toggle.setOnCheckedChangeListener { _, v -> onChange?.invoke(v) }
            body.setOnClickListener { toggle.toggle() }
        }

        fun bind(initial: Boolean, onChange: (Boolean) -> Unit) {
            show(initial)
            this.onChange = onChange
        }

        /** Sets the switch from code, which must not count as the user changing it. */
        fun show(checked: Boolean) {
            val listener = onChange
            onChange = null
            toggle.isChecked = checked
            onChange = listener
        }
    }

    private val setup = Row(a.findViewById(R.id.rowSetup), R.string.setup_row_title, R.string.setup_row_body)
    private val autoStart = Row(a.findViewById(R.id.rowAuto), R.string.auto_start_title, R.string.auto_start_body)
    private val mute = Row(a.findViewById(R.id.rowMute), R.string.mute_title, R.string.mute_body)
    private val playOnPhone = Row(a.findViewById(R.id.rowPlayOnPhone), R.string.play_on_phone_title, R.string.play_on_phone_body)
    private val autoRestart = Row(a.findViewById(R.id.rowAutoRestart), R.string.auto_restart_title, R.string.auto_restart_body)
    private val stayReady = Row(a.findViewById(R.id.rowArmed), R.string.stay_ready_title, R.string.stay_ready_body)
    private val advanced = Row(a.findViewById(R.id.rowAdvanced), R.string.advanced_title, R.string.advanced_body)

    init {
        bindSetupRow()
        autoStart.bind(Prefs.autoStart(a)) { Prefs.setAutoStart(a, it) }
        mute.bind(Prefs.muteWhileSending(a), ::onMute)
        playOnPhone.bind(Prefs.playOnPhone(a), ::onPlayOnPhone)
        autoRestart.bind(Prefs.autoRestart(a), ::onAutoRestart)
        stayReady.bind(Prefs.stayArmed(a), ::onStayReady)
        advanced.bind(Prefs.advanced(a), ::onAdvanced)
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

    /**
     * Switching Advanced mode changes how audio is sent, so whatever is running stops; the next
     * start uses the new way. Switching it on for the first time opens the setup guide.
     */
    private fun onAdvanced(on: Boolean) {
        Prefs.setAdvanced(a, on)
        if (BridgeService.state.phase in setOf(Phase.STREAMING, Phase.TONE, Phase.ARMED)) sendToService(BridgeService.ACTION_STOP)
        if (on && AudioOnly.status(a) == Status.NOT_SET_UP) Welcome.openSetup(a)
        render()
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

    /** Shows what applies to the phone's current state, and nothing technical unless Advanced mode is on. */
    fun render() {
        val advancedOn = Prefs.advanced(a)
        val status = AudioOnly.status(a)
        // The helper's options apply once Advanced mode is on and the setup has been done.
        val helper = advancedOn && status != Status.NOT_SET_UP && status != Status.UNSUPPORTED
        renderSetupRow(advancedOn, status)
        // How "off" works differs: Android's own capture needs the phone's volume at zero.
        playOnPhone.detail.setText(if (helper) R.string.play_on_phone_body else R.string.play_on_phone_basic)
        autoRestart.root.visibility = if (helper) View.VISIBLE else View.GONE
        stayReady.root.visibility = if (helper) View.GONE else View.VISIBLE
        for (id in ADVANCED_VIEWS) a.findViewById<View>(id).visibility = if (advancedOn) View.VISIBLE else View.GONE
    }

    private fun renderSetupRow(advancedOn: Boolean, status: Status) {
        val needsWireless = status == Status.NEEDS_WIRELESS_DEBUGGING
        val todo = advancedOn && (status == Status.NOT_SET_UP || needsWireless)
        setup.root.visibility = if (todo) View.VISIBLE else View.GONE
        setup.title.setText(if (needsWireless) R.string.setup_row_wd_title else R.string.setup_row_title)
        setup.detail.setText(if (needsWireless) R.string.setup_row_wd_body else R.string.setup_row_body)
        autoStart.divider.visibility = if (todo) View.VISIBLE else View.GONE  // the first row otherwise
    }

    private companion object {
        /** Parts of the main screen, outside this card, that only Advanced mode shows. */
        val ADVANCED_VIEWS = intArrayOf(R.id.detailsCard, R.id.linkSetup)
    }
}
