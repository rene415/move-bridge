package com.renelabs.movebridge.shell

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * "Also play on phone" in Direct mode: asks Android to play media on the phone's own speaker and
 * on the Move at the same time. Both come out of the same Android mixer, so they stay together
 * (unlike Bridge mode, where the captured copy reaches the Move later than the phone plays it).
 *
 * Uses Android's "preferred devices for a strategy" setting, which takes a list of devices. It
 * needs MODIFY_AUDIO_ROUTING (the shell user has it). The setting is not tied to this process, so
 * it must be removed again when Direct mode ends: [set] with on = false.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object BothOutputs {
    private val STRATEGY = Reflect.cls("android.media.audiopolicy.AudioProductStrategy")
    private val DEVICE = Reflect.cls("android.media.AudioDeviceAttributes")
    private val INT = Int::class.javaPrimitiveType!!
    /** AudioDeviceAttributes.ROLE_OUTPUT */
    private const val ROLE_OUTPUT = 2

    /** [on]: media goes to the speaker and to USB card [card]. Off: back to Android's own choice. */
    fun set(am: AudioManager, card: Int, on: Boolean) {
        val media = mediaStrategy() ?: return
        Reflect.callPublic(am, "removePreferredDeviceForStrategy", STRATEGY to media)
        if (!on) return
        val devices = listOf(
            device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""),
            device(AudioDeviceInfo.TYPE_USB_DEVICE, "card=$card;device=0"))
        Reflect.callPublic(am, "setPreferredDevicesForStrategy", STRATEGY to media, List::class.java to devices)
    }

    /** The routing strategy that music, video and game sound follow. */
    private fun mediaStrategy(): Any? {
        val all = Reflect.call(null, AudioManager::class.java, "getAudioProductStrategies") as List<*>
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        return all.firstOrNull {
            it != null && Reflect.callPublic(it, "supportsAudioAttributes", AudioAttributes::class.java to media) == true
        }
    }

    private fun device(type: Int, address: String): Any =
        Reflect.newInstance(DEVICE, INT to ROLE_OUTPUT, INT to type, String::class.java to address)
}
