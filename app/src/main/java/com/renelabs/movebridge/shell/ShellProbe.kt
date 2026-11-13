package com.renelabs.movebridge.shell

import android.os.Build
import androidx.annotation.RequiresApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Looper
import android.os.Process
import com.renelabs.movebridge.BuildConfig
import com.renelabs.movebridge.OutputLatency
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * Manual check, run as shell from a PC:
 *   adb shell 'CLASSPATH=$(pm path com.renelabs.movebridge | cut -d: -f2) app_process / com.renelabs.movebridge.shell.ShellProbe tone'
 * Records 3 s of whatever is playing (with "tone": a 440 Hz media tone it plays itself) and prints
 * the peak level.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)  // the helper needs Android 13+
object ShellProbe {
    private const val SECONDS = 3

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            ShellAudio.prepare()
            dispatch(args)
        } catch (e: Throwable) {
            println("FAIL ${e.javaClass.name}: ${e.message} / ${e.cause}")
            e.printStackTrace(System.out)
        }
        exitProcess(0)
    }

    private fun dispatch(args: Array<String>) {
        when {
            args.contains("latency-capture-first") -> LatencyProbe(captureFirst = true, args.contains("render")).run()
            args.contains("latency-music-first") -> LatencyProbe(captureFirst = false, args.contains("render")).run()
            // Just play 2 s of tone as media (like an app pressing play), no capture.
            args.contains("play") -> playTone()
            // Direct route by hand: "native-on <card>", "native-off <card>" (no watcher: withdraw it yourself).
            args.contains("native-on") -> nativeRoute(args, connected = true)
            args.contains("native-off") -> nativeRoute(args, connected = false)
            // Direct mode, phone speaker and Move together: "both-on <card>", "both-off <card>".
            args.contains("both-on") -> bothOutputs(args, on = true)
            args.contains("both-off") -> bothOutputs(args, on = false)
            // Which card the helper would pick as the Move's.
            args.contains("cards") -> println("Move card: ${SoundCards.moveCard(-1)}")
            // Make Move Bridge the default app for the plugged-in Move.
            args.contains("usb-default") -> println("default set: ${UsbDefault.set(BuildConfig.APPLICATION_ID, 0)}")
            args.contains("out-latency") -> outLatency()
            else -> levelCheck(args.contains("tone"))
        }
    }

    private fun nativeRoute(args: Array<String>, connected: Boolean) {
        val card = args.last().toIntOrNull() ?: return println("FAIL give the ALSA card number")
        DirectRoute.setConnected(card, connected)
        println("OK card $card ${if (connected) "announced as USB audio output" else "withdrawn"}")
    }

    private fun bothOutputs(args: Array<String>, on: Boolean) {
        val card = args.last().toIntOrNull() ?: return println("FAIL give the ALSA card number")
        BothOutputs.set(DirectRoute.audioManager(), card, on)
        println("OK media on speaker and card $card together: $on")
    }

    /** Delay on the current output route, as an ordinary app and as a low-latency app. */
    private fun outLatency() {
        for (fast in listOf(false, true)) {
            val r = OutputLatency.measure(fast)
            val mode = if (fast) "low-latency track" else "normal track"
            println(if (r == null) "$mode: no timestamp from this route"
            else "$mode -> ${r.route} (type ${r.routeType}): first sound ${r.firstMs} ms, steady ${r.steadyMs} ms (app buffer ${r.bufferMs} ms)")
        }
    }

    private fun levelCheck(tone: Boolean) {
        val cap = ShellAudio.start(playOnPhone = true)
        if (tone) Thread { playTone() }.start()
        val (samples, peak) = measure(cap.record)
        cap.close()
        println("OK uid=${Process.myUid()} samples=$samples peak=$peak (${"%.1f".format(peak / 327.68)}% FS)")
    }

    /** Reads for [SECONDS] and returns (samples read, peak). Bounded by the deadline. */
    private fun measure(r: AudioRecord): Pair<Int, Int> {
        val buf = ShortArray(4410)
        var peak = 0
        var total = 0
        val end = System.currentTimeMillis() + SECONDS * 1000
        while (System.currentTimeMillis() < end) {
            val n = r.read(buf, 0, buf.size)
            if (n < 0) break
            total += n
            for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))
        }
        return total to peak
    }

    /** Plays a quiet 440 Hz tone as ordinary media, to prove it shows up in the capture. */
    private fun playTone() {
        val rate = ShellAudio.RATE
        val pcm = ShortArray(rate * 2) { i -> (sin(2 * PI * 440 * (i / 2) / rate) * 4000).toInt().toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        repeat(2) { t.write(pcm, 0, pcm.size) }
        t.stop()
        t.release()
    }
}
