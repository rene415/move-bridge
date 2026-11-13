package com.renelabs.movebridge.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Calls [onMisrouted] when a media player is playing somewhere other than Move Bridge's capture.
 *
 * Why: a player that starts or resumes while the loopback is registered isn't always routed into
 * it. Seen on a Galaxy S26 Ultra: sample previews sometimes played on the phone speaker instead of the
 * Move. Re-registering the capture re-routes everything.
 *
 * Android 14+ reports each player's actual output device, so we re-route only when a player is
 * really on the wrong device ([checkDevices]). With [phonePlaysToo] (render mode) the phone
 * speaker is expected, and on older Android the device isn't reported: there we fall back to
 * reacting to newly started players, with a quiet period so our own re-routing can't loop.
 */
class PlaybackWatcher(c: Context, private val phonePlaysToo: () -> Boolean, private val onMisrouted: () -> Unit) {
    private val am = c.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var known = emptySet<Int>()
    private var quietUntil = 0L

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = onChange(configs)
    }
    private val fire = Runnable {
        quietUntil = System.currentTimeMillis() + if (checkDevices()) DEVICE_QUIET_MS else QUIET_MS
        onMisrouted()
    }

    fun start() {
        known = ids(am.activePlaybackConfigurations)
        quietUntil = System.currentTimeMillis() + QUIET_MS
        am.registerAudioPlaybackCallback(callback, main)
    }

    fun stop() {
        am.unregisterAudioPlaybackCallback(callback)
        main.removeCallbacks(fire)
    }

    private fun onChange(configs: List<AudioPlaybackConfiguration>) {
        val media = configs.filter { it.audioAttributes.usage in CAPTURED_USAGES }
        val now = media.map { it.hashCode() }.toSet()
        val fresh = now - known
        known = now
        val wrong = if (checkDevices()) media.any(::onWrongDevice) else fresh.isNotEmpty()
        // Both modes keep a quiet period: a player that can't be re-routed (e.g. an app using
        // the low-latency path) must not make us re-route in a tight loop.
        if (!wrong || System.currentTimeMillis() < quietUntil) return
        main.removeCallbacks(fire)
        main.postDelayed(fire, if (checkDevices()) FAST_MS else SETTLE_MS)
    }

    /** Device-based detection: Android 14+ and only while the phone is meant to be silent. */
    private fun checkDevices() = Build.VERSION.SDK_INT >= 34 && !phonePlaysToo()

    /** True when the player is routed to a real output (e.g. the speaker) instead of the loopback. */
    private fun onWrongDevice(cfg: AudioPlaybackConfiguration): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        // Deprecated in the API 36 SDK, with no replacement in it yet; still the way to get the device.
        @Suppress("DEPRECATION")
        val dev = cfg.audioDeviceInfo ?: return false
        return dev.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX
    }

    private fun ids(configs: List<AudioPlaybackConfiguration>): Set<Int> =
        configs.filter { it.audioAttributes.usage in CAPTURED_USAGES }.map { it.hashCode() }.toSet()

    private companion object {
        val CAPTURED_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
        const val FAST_MS = 50L      // device-based: re-route almost at once
        const val SETTLE_MS = 300L   // fallback: let the app finish starting first
        const val QUIET_MS = 1500L
        const val DEVICE_QUIET_MS = 1000L  // at most one device-based re-route per second
    }
}
