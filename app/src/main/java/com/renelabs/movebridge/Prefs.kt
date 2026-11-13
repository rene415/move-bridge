package com.renelabs.movebridge

import android.content.Context

/** Small wrapper around the app's saved settings. */
object Prefs {
    private const val FILE = "settings"
    private const val AUTO_START = "autoStart"
    private const val STAY_ARMED = "stayArmed"
    private const val INTRO_SEEN = "introSeen"
    private const val AUDIO_ONLY = "audioOnly"
    private const val ADB_PAIRED = "adbPaired"
    private const val AUTO_RESTART = "autoRestart"
    private const val PLAY_ON_PHONE = "playOnPhone"
    private const val SAVED_VOLUME = "savedVolume"
    private const val DIRECT = "direct"
    private const val MUTE = "muteWhileSending"
    private const val RINGER_CHANGED = "ringerChanged"
    private const val SEEN_VERSION = "seenVersion"
    private const val HOME_WIFI = "homeWifi"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Start streaming by itself when the Move is plugged in. Default on. */
    fun autoStart(c: Context) = sp(c).getBoolean(AUTO_START, true)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean(AUTO_START, v).apply()

    /** Keep the capture permission open between plug-ins (until restart). Default off. */
    fun stayArmed(c: Context) = sp(c).getBoolean(STAY_ARMED, false)
    fun setStayArmed(c: Context, v: Boolean) = sp(c).edit().putBoolean(STAY_ARMED, v).apply()

    /** Capture through the shell helper (no screen-share treatment). Default off. */
    fun audioOnly(c: Context) = sp(c).getBoolean(AUDIO_ONLY, false)
    fun setAudioOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean(AUDIO_ONLY, v).apply()

    /**
     * With audio-only mode on: let Android play straight to the Move (Direct mode) instead of
     * capturing and sending the audio ourselves (Bridge mode). Default on.
     */
    fun direct(c: Context) = sp(c).getBoolean(DIRECT, true)
    fun setDirect(c: Context, v: Boolean) = sp(c).edit().putBoolean(DIRECT, v).apply()

    /** Paired with this phone's Wireless debugging (one-time setup for audio-only mode). */
    fun adbPaired(c: Context) = sp(c).getBoolean(ADB_PAIRED, false)
    fun setAdbPaired(c: Context, v: Boolean) = sp(c).edit().putBoolean(ADB_PAIRED, v).apply()

    /** After a restart, switch Wireless debugging on briefly to restart the audio-only helper. Default off. */
    fun autoRestart(c: Context) = sp(c).getBoolean(AUTO_RESTART, false)
    fun setAutoRestart(c: Context, v: Boolean) = sp(c).edit().putBoolean(AUTO_RESTART, v).apply()

    /** Audio-only mode: keep playing on the phone too. Default off (like an iPhone on the Move). */
    fun playOnPhone(c: Context) = sp(c).getBoolean(PLAY_ON_PHONE, false)
    fun setPlayOnPhone(c: Context, v: Boolean) = sp(c).edit().putBoolean(PLAY_ON_PHONE, v).apply()

    /** Media volume to restore after a boost (-1 = none pending). See VolumeBooster. */
    fun savedVolume(c: Context) = sp(c).getInt(SAVED_VOLUME, -1)
    fun setSavedVolume(c: Context, v: Int) = sp(c).edit().putInt(SAVED_VOLUME, v).apply()

    /** Phone on vibrate while audio goes to the Move, so pings don't land in samples. Default off. */
    fun muteWhileSending(c: Context) = sp(c).getBoolean(MUTE, false)
    fun setMuteWhileSending(c: Context, v: Boolean) = sp(c).edit().putBoolean(MUTE, v).apply()

    /** We switched the phone to vibrate and still have to switch it back. See Quiet. */
    fun ringerChanged(c: Context) = sp(c).getBoolean(RINGER_CHANGED, false)
    fun setRingerChanged(c: Context, v: Boolean) = sp(c).edit().putBoolean(RINGER_CHANGED, v).apply()

    /** The app version whose "what's new" notes were last shown (0 = never opened). */
    fun seenVersion(c: Context) = sp(c).getInt(SEEN_VERSION, 0)
    fun setSeenVersion(c: Context, v: Int) = sp(c).edit().putInt(SEEN_VERSION, v).apply()

    /** Fingerprint of the Wi-Fi network the helper was last started on (see WifiId), or null. */
    fun homeWifi(c: Context): String? = sp(c).getString(HOME_WIFI, null)
    fun setHomeWifi(c: Context, v: String) = sp(c).edit().putString(HOME_WIFI, v).apply()

    /** The "audio only" explanation has been shown once. */
    fun introSeen(c: Context) = sp(c).getBoolean(INTRO_SEEN, false)
    fun setIntroSeen(c: Context) = sp(c).edit().putBoolean(INTRO_SEEN, true).apply()
}
