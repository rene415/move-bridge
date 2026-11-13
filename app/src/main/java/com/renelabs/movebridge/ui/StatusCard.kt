package com.renelabs.movebridge.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.renelabs.movebridge.BridgeState
import com.renelabs.movebridge.Phase
import com.renelabs.movebridge.R
import kotlin.math.sqrt

/** The status card: phase light, title, detail, level meter, buttons and connection details. */
class StatusCard(private val a: Activity) {
    val primary: Button = a.findViewById(R.id.primary)
    val tone: Button = a.findViewById(R.id.tone)
    private val dot: View = a.findViewById(R.id.dot)
    private val phaseLabel: TextView = a.findViewById(R.id.phaseLabel)
    private val title: TextView = a.findViewById(R.id.title)
    private val detail: TextView = a.findViewById(R.id.detail)
    private val meterRow: View = a.findViewById(R.id.meterRow)
    private val meter: ProgressBar = a.findViewById(R.id.meter)
    private val stats: TextView = a.findViewById(R.id.stats)
    private var pulse: ObjectAnimator? = null
    private var shownLevel = 0f

    init {
        val toggle = a.findViewById<TextView>(R.id.detailsToggle)
        toggle.setOnClickListener {
            val open = stats.visibility != View.VISIBLE
            stats.visibility = if (open) View.VISIBLE else View.GONE
            toggle.setText(if (open) R.string.details_open else R.string.details_closed)
        }
    }

    /**
     * [canGetReady]: no Move plugged in, but "stay ready" lets Start prepare capture anyway.
     * [moveAttached]: a Move is plugged in (the idle texts differ).
     */
    fun render(s: BridgeState, canGetReady: Boolean, moveAttached: Boolean) {
        val live = s.phase == Phase.STREAMING || s.phase == Phase.TONE
        renderPhase(s.phase, moveAttached)
        renderText(s, moveAttached)
        renderMeter(live && !s.direct, s.level)  // Direct mode: Android plays, we see no level
        primary.setText(primaryLabel(s.phase, canGetReady))
        tone.visibility = if (live || s.phase == Phase.ARMED) View.GONE else View.VISIBLE
        stats.text = if (s.stats.isEmpty()) a.getString(R.string.no_connection) else s.stats.replace(" | ", "\n")
        setPulsing(live)
    }

    fun release() = pulse?.cancel()

    private fun renderPhase(p: Phase, moveAttached: Boolean) {
        val color = a.getColor(phaseColor(p))
        dot.backgroundTintList = ColorStateList.valueOf(color)
        phaseLabel.setText(if (p == Phase.IDLE && moveAttached) R.string.phase_plugged else phaseText(p))
        phaseLabel.setTextColor(if (p == Phase.IDLE) a.getColor(R.color.text_faint) else color)
    }

    /**
     * The idle texts are chosen here, not by the service: it doesn't run while idle, so only the
     * screen knows whether a Move is plugged in. An error without its own advice gets the same hint.
     */
    private fun renderText(s: BridgeState, moveAttached: Boolean) {
        val idle = s.phase == Phase.IDLE && s.title.isEmpty()
        val hint = a.getString(if (moveAttached) R.string.state_plugged_detail else R.string.state_idle_detail)
        title.text = if (!idle) s.title
        else a.getString(if (moveAttached) R.string.state_plugged_title else R.string.state_idle_title)
        val needsHint = s.detail.isEmpty() && (idle || s.phase == Phase.ERROR)
        detail.text = if (needsHint) hint else s.detail
        detail.visibility = if (detail.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Fast attack, slow release, square-root scale: reads like a real meter. */
    private fun renderMeter(live: Boolean, level: Float) {
        meterRow.visibility = if (live) View.VISIBLE else View.GONE
        shownLevel = if (level > shownLevel) level else shownLevel * 0.85f + level * 0.15f
        meter.progress = (sqrt(shownLevel.toDouble()) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun setPulsing(on: Boolean) {
        if (on && pulse == null) {
            pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
                duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!on && pulse != null) {
            pulse?.cancel()
            pulse = null
            dot.alpha = 1f
        }
    }

    private companion object {
        fun phaseColor(p: Phase) = when (p) {
            Phase.IDLE -> R.color.text_faint
            Phase.ARMED -> R.color.armed
            Phase.STREAMING -> R.color.accent
            Phase.TONE -> R.color.tone
            Phase.ERROR -> R.color.error
        }

        fun phaseText(p: Phase) = when (p) {
            Phase.IDLE -> R.string.phase_idle
            Phase.ARMED -> R.string.phase_armed
            Phase.STREAMING -> R.string.phase_live
            Phase.TONE -> R.string.phase_tone
            Phase.ERROR -> R.string.phase_error
        }

        fun primaryLabel(p: Phase, canGetReady: Boolean) = when (p) {
            Phase.STREAMING -> R.string.notif_stop_sending
            Phase.TONE -> R.string.notif_stop_tone
            Phase.ARMED -> R.string.btn_stop_ready
            else -> if (canGetReady) R.string.btn_get_ready else R.string.btn_start
        }
    }
}
