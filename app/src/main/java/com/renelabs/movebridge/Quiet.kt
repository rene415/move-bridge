package com.renelabs.movebridge

import android.content.Context
import android.media.AudioManager

/**
 * "Mute notifications while sending": puts the phone on vibrate while audio goes to the Move, so
 * rings, pings and touch sounds don't land in a sample, and puts it back afterwards. Music and
 * alarms are not affected.
 *
 * Going between normal and vibrate needs no special permission (only silent / Do Not Disturb
 * does). A phone that is already on vibrate or silent is left alone. Whether we changed it is
 * kept in Prefs, so it's put back even if the app was killed mid-stream.
 */
class Quiet(c: Context) {
    private val app = c.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)

    fun on() {
        if (!Prefs.muteWhileSending(app) || am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        Prefs.setRingerChanged(app, true)
        runCatching { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            .onFailure { Diag.w("couldn't switch to vibrate: $it") }
    }

    /** Back to normal, if [on] changed it and the user hasn't changed it since. Safe to call any time. */
    fun restore() {
        if (!Prefs.ringerChanged(app)) return
        Prefs.setRingerChanged(app, false)
        if (am.ringerMode == AudioManager.RINGER_MODE_VIBRATE) runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
    }
}
