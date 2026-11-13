package com.renelabs.movebridge.capture

import android.content.Context
import android.media.AudioManager
import com.renelabs.movebridge.Prefs

/**
 * Holds the phone's media volume at one end while audio is being sent with "Also play on phone"
 * off, and puts it back afterwards. Which end depends on where Android taps the audio:
 *
 *  - Android's own capture (Regular mode) is taken BEFORE the media volume. Measured: a click
 *    written at 9000 arrives at 8999 with the volume at 0, 8 and 15 alike. So [zero] silences the
 *    phone while the Move still gets the full level.
 *  - The helper's capture with the phone silent is taken AFTER the media volume (measured:
 *    volume 6 of 15 was about 13 dB down), and the phone's speaker gets nothing anyway. So [max]
 *    gives the Move the full level.
 *
 * The level to put back is saved in Prefs, so it is restored even if the app was killed mid-stream.
 */
class VolumeHold(c: Context) {
    private val app = c.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)

    fun zero() = hold(0)

    fun max() = hold(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

    private fun hold(level: Int) {
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (Prefs.savedVolume(app) < 0) Prefs.setSavedVolume(app, current)  // keep the user's level
        if (current != level) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
    }

    /** Puts back the level from before [zero] or [max], if one is saved. Safe to call any time. */
    fun restore() {
        val saved = Prefs.savedVolume(app)
        if (saved < 0) return
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        Prefs.setSavedVolume(app, -1)
    }
}
