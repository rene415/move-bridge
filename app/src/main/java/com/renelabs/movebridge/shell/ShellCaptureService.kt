package com.renelabs.movebridge.shell

import android.os.Build
import androidx.annotation.RequiresApi
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import com.renelabs.movebridge.BuildConfig
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The service that runs as the shell user, inside Move Bridge's helper ([ShellMain]).
 * Bridge mode: captured audio goes back to the app over a pipe; the USB side stays in the app.
 * Direct mode: see [DirectRoute].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)  // the helper needs Android 13+
class ShellCaptureService(
    /** Only this uid may call: Move Bridge's. */
    private val allowedUid: Int,
) : IShellCapture.Stub() {

    private var capture: ShellAudio.Capture? = null
    private var streamer: PcmStreamer? = null

    // This service runs with shell privileges, so make sure only Move Bridge can use it.
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (Binder.getCallingUid() != allowedUid) throw SecurityException("not Move Bridge")
        return super.onTransact(code, data, reply, flags)
    }

    /**
     * Starts a new capture. If one is already running, the new routing rule is registered BEFORE
     * the old one is removed, so playing apps never fall back to the phone speaker in between
     * (a loud blip when the phone is meant to be silent).
     */
    @Synchronized
    override fun start(playOnPhone: Boolean): ParcelFileDescriptor {
        // AudioPolicy checks the *calling* identity for MODIFY_AUDIO_ROUTING, which the app
        // doesn't have: act as ourselves (shell) instead.
        val cap = asSelf { ShellAudio.start(playOnPhone) }
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        stopLocked()
        capture = cap
        streamer = PcmStreamer(cap, writeEnd)
        return readEnd
    }

    @Synchronized
    override fun stop() = stopLocked()

    private fun stopLocked() {
        streamer?.stop()
        streamer = null
        capture = null
    }

    override fun grantSecureSettings(): Boolean = runCatching {
        asSelf {
            Runtime.getRuntime().exec(arrayOf("pm", "grant", BuildConfig.APPLICATION_ID,
                "android.permission.WRITE_SECURE_SETTINGS")).waitFor() == 0
        }
    }.getOrDefault(false)

    override fun version(): Int = BuildConfig.VERSION_CODE

    override fun directOn(cardHint: Int, alsoPhone: Boolean): Boolean = runCatching { asSelf { DirectRoute.on(cardHint, alsoPhone) } }
        .onFailure { System.err.println("MoveBridge shell: directOn failed: ${it.cause ?: it}") }
        .getOrDefault(false)

    override fun directOff() {
        runCatching { asSelf { DirectRoute.off() } }
    }

    override fun makeDefault(): Boolean = runCatching {
        asSelf { UsbDefault.set(BuildConfig.APPLICATION_ID, allowedUid / PER_USER_RANGE) }
    }.onFailure { System.err.println("MoveBridge shell: makeDefault failed: ${it.cause ?: it}") }.getOrDefault(false)

    /** Without the every-two-seconds delay lines, which would push everything else out. */
    override fun recentLog(maxLines: Int): String = runCatching {
        java.io.File(ShellProtocol.HELPER_LOG).readLines().filterNot { it.contains("recorder delay") }
            .takeLast(maxLines.coerceIn(1, MAX_LOG_LINES)).joinToString("\n")
    }.getOrDefault("")

    private inline fun <T> asSelf(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private companion object {
        /** Android gives each user 100000 uids: uid / this = the user the app runs as. */
        const val PER_USER_RANGE = 100_000
        const val MAX_LOG_LINES = 100
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
/** Copies the recorder into the pipe in 5 ms chunks until stopped or the app closes its end. */
private class PcmStreamer(private val cap: ShellAudio.Capture, writeEnd: ParcelFileDescriptor) {
    @Volatile private var running = true
    private val delay = DelayMeter(cap.record)
    private val dump = PcmDump.openIfRequested("/data/local/tmp/mb_dump", "/data/local/tmp/mb_capture.raw")
    private val thread = Thread({ run(ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) }, "shell-capture")
        .apply { start() }

    // Runs for the whole capture by design; stop() ends it.
    private fun run(out: OutputStream) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val shorts = ShortArray(221 * 2)
        val bytes = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.use {
            try {
                while (running) {
                    val n = cap.record.read(shorts, 0, shorts.size)
                    if (n < 0) break
                    if (n == 0) continue
                    delay.onRead(n / 2)
                    dump?.write(shorts, n)
                    bytes.clear()
                    bytes.asShortBuffer().put(shorts, 0, n)
                    it.write(bytes.array(), 0, n * 2)
                }
            } catch (_: IOException) {
                // The app closed its end (stopped or died): nothing to do.
            }
        }
        dump?.close()
        cap.close()
    }

    fun stop() {
        running = false
        runCatching { cap.record.stop() }  // unblocks read(); the thread then closes the capture
        thread.join(500)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
/** Every 2 s, logs how far behind "now" the audio just read is (the recorder's delay). */
private class DelayMeter(private val record: AudioRecord) {
    private val ts = AudioTimestamp()
    private var readFrames = 0L
    private var lastLog = 0L

    fun onRead(frames: Int) {
        readFrames += frames
        val now = System.nanoTime()
        if (now - lastLog < 2_000_000_000L) return
        if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return
        lastLog = now
        val capturedNow = ts.framePosition + (now - ts.nanoTime) * ShellProtocol.RATE / 1_000_000_000L
        System.err.println("MoveBridge shell: recorder delay ${(capturedNow - readFrames) * 1000 / ShellProtocol.RATE} ms")
    }
}

/**
 * Debug: records the first 15 s of PCM to [outPath] when the flag file [flagPath] exists
 * (create it with adb to diagnose crackle). Raw 44.1 kHz stereo 16-bit little-endian.
 */
internal class PcmDump private constructor(private val out: java.io.OutputStream) {
    private var left = 15L * ShellProtocol.RATE * 2  // samples
    private val bytes = ByteBuffer.allocate(221 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)

    fun write(shorts: ShortArray, n: Int) {
        if (left <= 0) return
        val k = minOf(n.toLong(), left).toInt()
        bytes.clear()
        bytes.asShortBuffer().put(shorts, 0, k)
        runCatching { out.write(bytes.array(), 0, k * 2) }
        left -= k
        if (left <= 0) close()
    }

    fun close() {
        runCatching { out.close() }
    }

    companion object {
        fun openIfRequested(flagPath: String, outPath: String): PcmDump? =
            if (!java.io.File(flagPath).exists()) null
            else runCatching { PcmDump(java.io.FileOutputStream(outPath)) }.getOrNull()
    }
}
