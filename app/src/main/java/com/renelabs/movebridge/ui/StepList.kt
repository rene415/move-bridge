package com.renelabs.movebridge.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.renelabs.movebridge.R

/** Numbered step lists: "On the Move" on the main screen, and the setup guide's checklist. */
object StepList {
    private val MOVE_STEPS = intArrayOf(R.string.step_1, R.string.step_2, R.string.step_3, R.string.step_4)
    private const val DONE = "✓"

    /** The "On the Move" steps of the main screen. */
    fun build(a: Activity) {
        build(a, a.findViewById(R.id.steps), MOVE_STEPS)
    }

    /** Fills [container] with one numbered row per text; returns the number chips, for [mark]. */
    fun build(a: Activity, container: LinearLayout, texts: IntArray): List<TextView> =
        texts.mapIndexed { i, text ->
            val chip = chip(a, i + 1)
            container.addView(row(a, chip, text))
            chip
        }

    /** Shows a tick instead of the number once a step is [done]. */
    fun mark(chip: TextView, number: Int, done: Boolean) {
        chip.text = if (done) DONE else String.format(java.util.Locale.getDefault(), "%d", number)
        chip.alpha = if (done) 0.6f else 1f
    }

    private fun row(a: Activity, chip: TextView, text: Int): LinearLayout {
        val px = { dp: Int -> (dp * a.resources.displayMetrics.density).toInt() }
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(8), 0, px(8))
            addView(chip, LinearLayout.LayoutParams(px(24), px(24)))
            addView(TextView(a).apply {
                setText(text)
                textSize = 15f
                setTextColor(a.getColor(R.color.text))
                setPadding(px(14), 0, 0, 0)
            })
        }
    }

    private fun chip(a: Activity, number: Int) = TextView(a).apply {
        text = String.format(java.util.Locale.getDefault(), "%d", number)
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(a.getColor(R.color.accent_text))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = a.getDrawable(R.drawable.bg_chip)
    }
}
