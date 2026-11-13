package com.renelabs.movebridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack

/**
 * Measures the delay an app sees on the current output route: how long after write() a frame is
 * presented, from AudioTrack timestamps. Plays silence, so nothing is heard. Blocks for
 * [RUN_MS]: call it off the main thread.
 */
object OutputLatency {
    private const val RUN_MS = 2000L
    private const val RATE = 44100
    /** Low-latency ("fast") tracks only exist at the output's own rate, 48 kHz on most phones. */
    private const val FAST_RATE = 48000

    /**
     * [firstMs]: from an app's first write() to that audio coming out (tap-to-hear delay).
     * [steadyMs]: how far behind write() the output runs once the app's buffer is full.
     */
    data class Result(val route: String, val routeType: Int, val firstMs: Long, val steadyMs: Long, val bufferMs: Int)

    /** Null when the route gives no timestamps. [lowLatency]: measure like an instrument app or game. */
    fun measure(lowLatency: Boolean): Result? {
        val track = build(lowLatency)
        try {
            val rate = track.sampleRate
            track.play()
            val t0 = System.nanoTime()
            val written = writeSilence(track)
            val ts = AudioTimestamp()
            if (!track.getTimestamp(ts)) return null
            val presented = ts.framePosition + (System.nanoTime() - ts.nanoTime) * rate / 1_000_000_000L
            val firstFrameAt = ts.nanoTime - ts.framePosition * 1_000_000_000L / rate
            val route = track.routedDevice
            return Result(route?.productName?.toString() ?: "unknown", route?.type ?: 0,
                (firstFrameAt - t0) / 1_000_000, (written - presented) * 1000 / rate,
                track.bufferSizeInFrames * 1000 / rate)
        } finally {
            track.release()
        }
    }

    /** Writes silence for [RUN_MS] like a playing app; returns frames written. */
    private fun writeSilence(track: AudioTrack): Long {
        val chunk = ShortArray(track.sampleRate / 200 * 2)  // 5 ms
        var frames = 0L
        val end = System.nanoTime() + RUN_MS * 1_000_000
        while (System.nanoTime() < end) {
            val n = track.write(chunk, 0, chunk.size)
            if (n <= 0) break
            frames += n / 2
        }
        return frames
    }

    private fun build(lowLatency: Boolean): AudioTrack {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(if (lowLatency) FAST_RATE else RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val mode = if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(fmt)
            .setPerformanceMode(mode)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
