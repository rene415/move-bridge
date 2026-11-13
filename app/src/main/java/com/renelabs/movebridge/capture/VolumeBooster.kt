package com.renelabs.movebridge.capture

import android.content.Context
import android.media.AudioManager
import com.renelabs.movebridge.Prefs

/**
 * Android captures audio AFTER the phone's media volume, so a low volume means a quiet sample on
 * the Move (volume 6/15 measured ~13 dB down). While the phone itself is silent (capture through the helper
 * with "Also play on phone" off), raising the media volume costs nothing, so we raise it to max
 * and put it back afterwards. The original level is saved in Prefs so it's restored even if the
 * app was killed mid-stream.
 */
class VolumeBooster(c: Context) {
    private val app = c.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)

    fun boost() {
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (Prefs.savedVolume(app) < 0) Prefs.setSavedVolume(app, current)  // keep the user's level
        if (current < max) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
    }

    /** Puts back the level from before [boost], if one is saved. Safe to call any time. */
    fun restore() {
        val saved = Prefs.savedVolume(app)
        if (saved < 0) return
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        Prefs.setSavedVolume(app, -1)
    }

    /** True when the media volume is below max (used to show a tip when we can't boost). */
    fun isLow(): Boolean =
        am.getStreamVolume(AudioManager.STREAM_MUSIC) < am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
}
