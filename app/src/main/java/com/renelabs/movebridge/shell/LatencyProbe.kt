package com.renelabs.movebridge.shell

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.abs

/**
 * Measures how long audio takes from an app's AudioTrack.write() to Move Bridge's capture, in two
 * orders (run as shell via ShellProbe, with Move Bridge itself not streaming):
 *   captureFirst = true:  capture starts, then "music" starts (like pressing play after Send)
 *   captureFirst = false: "music" is already playing when capture starts
 * The "music" is silence with a loud click every CLICK_GAP_MS; each click's delay is printed.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LatencyProbe(private val captureFirst: Boolean, private val playOnPhone: Boolean) {
    // Created when the "music" starts, like a real app pressing play.
    private lateinit var track: AudioTrack
    private lateinit var writer: ClickWriter

    fun run() {
        if (!captureFirst) startMusic(firstClick = false)
        if (!captureFirst) Thread.sleep(SETTLE_MS)
        val cap = ShellAudio.start(playOnPhone)
        if (captureFirst) Thread.sleep(SETTLE_MS)
        if (captureFirst) startMusic(firstClick = true)  // the first click is the very start of playback
        val bufMs = track.bufferSizeInFrames * 1000 / ShellProtocol.RATE
        val delays = try { measure(cap.record) } finally { cap.close() }
        writer.stop()
        track.release()
        val order = (if (captureFirst) "capture first, then music" else "music first, then capture") +
            (if (playOnPhone) " [play on phone]" else " [phone silent]")
        println("$order: app buffer $bufMs ms; per click [total write->capture / Android play->capture] ms:")
        println("  " + delays.joinToString { "${it.first}/${it.second}" } + "   (peak seen $peakSeen)")
    }

    private fun startMusic(firstClick: Boolean) {
        track = buildTrack()
        writer = ClickWriter(track)
        if (firstClick) writer.requestClick()
        track.play()
        writer.start()
    }

    /**
     * Reads the capture continuously (pausing would let a backlog build up and skew the next
     * reading) and times each click. Bounded by CLICKS detections or the deadline.
     */
    private fun measure(r: AudioRecord): List<Pair<Long, Long>> {
        val delays = mutableListOf<Pair<Long, Long>>()
        val buf = ShortArray(221 * 2)
        val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000
        var awaiting = captureFirst  // capture-first: the first click was already requested
        var nextClickAt = System.nanoTime()
        while (delays.size < CLICKS && System.nanoTime() < deadline) {
            val n = r.read(buf, 0, buf.size)
            val now = System.nanoTime()
            if (awaiting && writer.clickDone && n > 0 && hasClick(buf, n)) {
                delays += (now - writer.clickWrittenAt) / 1_000_000 to (now - playedAt(writer.clickFrame)) / 1_000_000
                awaiting = false
                nextClickAt = now + CLICK_GAP_MS * 1_000_000
            } else if (!awaiting && now >= nextClickAt) {
                writer.requestClick()
                awaiting = true
            }
        }
        return delays
    }

    /** When the track actually played (presented) [frame], from its timestamp. */
    private fun playedAt(frame: Long): Long {
        val ts = AudioTimestamp()
        if (!track.getTimestamp(ts)) return Long.MAX_VALUE
        return ts.nanoTime + (frame - ts.framePosition) * 1_000_000_000L / ShellProtocol.RATE
    }

    /** Loudest sample seen (diagnostic: shows whether the clicks reach the capture at all). */
    private var peakSeen = 0

    private fun hasClick(buf: ShortArray, n: Int): Boolean {
        var hit = false
        for (i in 0 until n) {
            val v = abs(buf[i].toInt())
            if (v > peakSeen) peakSeen = v
            if (v > THRESHOLD) hit = true
        }
        return hit
    }

    private fun buildTrack(): AudioTrack {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(ShellProtocol.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        return AudioTrack.Builder()
            // MEDIA, like a music app. Nothing else should be playing during the test.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(fmt)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private companion object {
        const val CLICKS = 6
        const val CLICK_GAP_MS = 600L
        const val SETTLE_MS = 1500L
        const val DEADLINE_MS = 15_000L
        // The capture is after the phone's media volume, so keep this low (clicks are written at 30000).
        const val THRESHOLD = 250
    }
}

/** Writes silence continuously (like a playing app) and a click when asked; notes when it wrote it. */
private class ClickWriter(private val track: AudioTrack) {
    @Volatile var clickWrittenAt = 0L
        private set
    /** Track frame position of the last click. */
    @Volatile var clickFrame = 0L
        private set
    private var framesWritten = 0L
    @Volatile private var clickPending = false
    @Volatile private var running = false
    private var thread: Thread? = null

    /** True once the last requested click has been written to the track. */
    @Volatile var clickDone = false
        private set

    fun requestClick() {
        clickDone = false
        clickPending = true
    }

    fun start() {
        running = true
        thread = Thread({ loop() }, "click-writer").apply { start() }
    }

    // Runs until stop(), like a music app's playback thread.
    private fun loop() {
        val chunk = ShortArray(441 * 2)  // 10 ms
        while (running) {
            chunk.fill(0)
            if (clickPending) {
                for (i in 0 until 40) chunk[i] = 30000
                clickPending = false
                clickWrittenAt = System.nanoTime()
                clickFrame = framesWritten
                clickDone = true
            }
            val n = track.write(chunk, 0, chunk.size)
            if (n > 0) framesWritten += n / 2
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
    }
}
