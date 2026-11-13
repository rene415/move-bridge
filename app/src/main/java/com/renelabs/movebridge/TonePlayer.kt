package com.renelabs.movebridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Test tone for Direct mode: played as ordinary media through Android's own output, so it takes
 * the same road to the Move as any app's sound. 440 Hz at about -12 dBFS, like the USB test tone
 * of Bridge mode (the phone's media volume applies on top).
 */
class TonePlayer {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        // One second holds exactly 440 cycles, so looping it is seamless.
        val pcm = ShortArray(RATE * 2) { i -> (sin(2 * PI * HZ * (i / 2) / RATE) * LEVEL).toInt().toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, RATE, -1)
        t.play()
        track = t
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private companion object {
        const val RATE = 44100
        const val HZ = 440
        const val LEVEL = 8000.0
    }
}
