package com.renelabs.movebridge.ui

import android.app.Activity
import android.view.View
import android.widget.Switch
import android.widget.TextView
import com.renelabs.movebridge.AudioOnly
import com.renelabs.movebridge.AudioOnly.Status
import com.renelabs.movebridge.BridgeService
import com.renelabs.movebridge.BridgeState
import com.renelabs.movebridge.Phase
import com.renelabs.movebridge.Prefs
import com.renelabs.movebridge.Quiet
import com.renelabs.movebridge.R
import com.renelabs.movebridge.ShellLink
import com.renelabs.movebridge.adb.Pairing

/**
 * The Settings card:
 *   Start when Move connects
 *   Mute notifications while sending
 *   Audio-only mode            (+ status line)
 *     Direct to Move           (audio-only on)
 *     Also play on phone       (audio-only on)
 *     Start automatically      (audio-only on)
 *   Stay ready in background   (audio-only off: only matters for screen-share capture)
 * [onLocalError] shows a message on the status card (title, detail res ids).
 */
class SettingsPanel(private val a: Activity, private val onLocalError: (Int, Int) -> Unit) {

    /** One row from layout/setting_row.xml. */
    private class Row(val root: View, title: Int, detail: Int, sub: Boolean = false, first: Boolean = false) {
        val toggle: Switch = root.findViewById(R.id.rowSwitch)
        val detail: TextView = root.findViewById(R.id.rowDetail)
        val body: View = root.findViewById(R.id.rowBody)

        init {
            // Every row's switch has the same id (one shared layout), so Android's automatic view-state
            // restore would copy one row's state onto all of them. Prefs are the source of truth.
            toggle.isSaveEnabled = false
            root.findViewById<TextView>(R.id.rowTitle).setText(title)
            this.detail.setText(detail)
            if (first) root.findViewById<View>(R.id.rowDivider).visibility = View.GONE
            if (sub) body.setPaddingRelative(root.resources.getDimensionPixelSize(R.dimen.sub_row_indent), 0, 0, 0)
        }

        fun bind(initial: Boolean, onChange: (Boolean) -> Unit) {
            toggle.isChecked = initial
            toggle.setOnCheckedChangeListener { _, v -> onChange(v) }
            body.setOnClickListener { toggle.toggle() }
        }
    }

    private val autoStart = Row(a.findViewById(R.id.rowAuto), R.string.auto_start_title, R.string.auto_start_body, first = true)
    private val mute = Row(a.findViewById(R.id.rowMute), R.string.mute_title, R.string.mute_body)
    private val audioOnly = Row(a.findViewById(R.id.rowAudioOnly), R.string.audio_only_title, R.string.audio_only_off)
    private val direct = Row(a.findViewById(R.id.rowDirect), R.string.direct_title, R.string.direct_body, sub = true)
    private val autoRestart = Row(a.findViewById(R.id.rowAutoRestart), R.string.auto_restart_title, R.string.auto_restart_body, sub = true)
    private val playOnPhone = Row(a.findViewById(R.id.rowPlayOnPhone), R.string.play_on_phone_title, R.string.play_on_phone_body, sub = true)
    private val stayReady = Row(a.findViewById(R.id.rowArmed), R.string.stay_ready_title, R.string.stay_ready_body)

    init {
        autoStart.bind(Prefs.autoStart(a)) { Prefs.setAutoStart(a, it) }
        mute.bind(Prefs.muteWhileSending(a), ::onMute)
        direct.bind(Prefs.direct(a), ::onDirect)
        autoRestart.bind(Prefs.autoRestart(a), ::onAutoRestart)
        playOnPhone.bind(Prefs.playOnPhone(a), ::onPlayOnPhone)
        stayReady.bind(Prefs.stayArmed(a), ::onStayReady)
        bindAudioOnly()
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

    /** Applies straight away: a live connection switches between Direct and Bridge mode. */
    private fun onDirect(on: Boolean) {
        Prefs.setDirect(a, on)
        rerouteIfLive()
        render()
    }

    private fun rerouteIfLive() {
        if (BridgeService.state.phase == Phase.STREAMING) sendToService(BridgeService.ACTION_REROUTE)
    }

    /** Applies straight away: the service switches the phone's speaker in or out of a live connection. */
    private fun onPlayOnPhone(on: Boolean) {
        Prefs.setPlayOnPhone(a, on)
        if (BridgeService.state.phase == Phase.STREAMING) sendToService(BridgeService.ACTION_RECAPTURE)
    }

    private fun sendToService(action: String) = a.startService(BridgeService.intent(a, action))

    private fun bindAudioOnly() {
        audioOnly.toggle.isChecked = Prefs.audioOnly(a)
        audioOnly.toggle.setOnCheckedChangeListener { _, v ->
            Prefs.setAudioOnly(a, v)
            if (v && AudioOnly.status(a) == Status.NOT_SET_UP) Welcome.openSetup(a)
            rerouteIfLive()
            clearIdleNote()
            render()
        }
        audioOnly.body.setOnClickListener { onAudioOnlyRow() }
        // Long-press: pair again (e.g. after Android forgot the pairing).
        audioOnly.body.setOnLongClickListener { audioOnly.toggle.isChecked = true; beginSetup(); true }
    }

    /** A note left on the idle screen ("Audio-only mode is off") is out of date once the setting changes. */
    private fun clearIdleNote() {
        if (BridgeService.state.phase == Phase.IDLE) BridgeService.publish(BridgeState(Phase.IDLE, "", ""))
    }

    private fun onAudioOnlyRow() {
        when (AudioOnly.status(a)) {
            Status.NOT_SET_UP -> if (audioOnly.toggle.isChecked) Welcome.openSetup(a) else audioOnly.toggle.isChecked = true
            // Paired, but Wireless debugging is off and we may not switch it: send them there.
            Status.NEEDS_WIRELESS_DEBUGGING -> openDeveloperSettings()
            // Tapping the row only turns it on: a stray tap next to the sub-options mustn't switch it off.
            else -> audioOnly.toggle.isChecked = true
        }
    }

    /** Pair again without the guide: listen for the pairing dialog and send the user to Wireless debugging. */
    private fun beginSetup() {
        Pairing.begin(a)
        openDeveloperSettings()
    }

    private fun openDeveloperSettings() {
        runCatching { a.startActivity(Pairing.developerSettingsIntent()) }
            .onFailure { onLocalError(R.string.err_dev_options_title, R.string.err_dev_options_detail) }
    }

    /** Refreshes the audio-only status line and which rows are shown. */
    fun render() {
        val status = AudioOnly.status(a)
        val on = Prefs.audioOnly(a) && status != Status.UNSUPPORTED
        audioOnly.detail.setText(statusText(status, Prefs.audioOnly(a)))
        audioOnly.detail.setTextColor(a.getColor(statusColor(status, Prefs.audioOnly(a))))
        direct.root.visibility = if (on) View.VISIBLE else View.GONE
        autoRestart.root.visibility = if (on) View.VISIBLE else View.GONE
        playOnPhone.root.visibility = if (on) View.VISIBLE else View.GONE
        stayReady.root.visibility = if (on) View.GONE else View.VISIBLE
    }

    private companion object {
        fun statusText(s: Status, on: Boolean) = when {
            s == Status.UNSUPPORTED -> R.string.audio_only_unsupported
            !on -> R.string.audio_only_off
            s == Status.NOT_SET_UP -> R.string.audio_only_not_set_up
            s == Status.NEEDS_WIRELESS_DEBUGGING -> R.string.audio_only_needs_wd
            s == Status.CAN_START -> R.string.audio_only_can_start
            else -> R.string.audio_only_running
        }

        fun statusColor(s: Status, on: Boolean) = when {
            !on -> R.color.text_dim
            s == Status.RUNNING || s == Status.CAN_START -> R.color.accent_text
            else -> R.color.error
        }
    }
}
