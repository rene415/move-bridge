package com.renelabs.movebridge.capture

import android.os.ParcelFileDescriptor
import android.os.Process
import com.renelabs.movebridge.NativeBridge
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Where the phone's audio comes from. Both kinds feed [NativeBridge.write] from a [CaptureThread]. */
interface CaptureSource {
    /** True for the MediaProjection kind (needs the mediaProjection foreground-service type). */
    val isProjection: Boolean

    /** Stops capturing and releases everything. Safe to call more than once. */
    fun close()
}

/** 5 ms of 44.1 kHz stereo per read: small reads keep the delay low. */
const val CHUNK_SHORTS = 221 * 2

/**
 * Reads PCM chunks with [read] and queues them for the Move. [read] returns the number of shorts
 * read, 0 for none yet, or a negative number when the source died (then [onDied] is called).
 */
class CaptureThread(name: String, private val read: (ShortArray) -> Int, private val onDied: () -> Unit) {
    @Volatile private var running = true
    private val thread = Thread({ loop() }, name).apply { start() }

    // Runs for the whole capture session by design; stop() ends it.
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(CHUNK_SHORTS)
        while (running) {
            val n = runCatching { read(buf) }.getOrDefault(-1)
            if (n > 0) NativeBridge.write(buf, n / 2)  // no-op while the Move isn't streaming
            if (n < 0) {
                if (running) onDied()
                return
            }
        }
    }

    /** [unblock] must make a pending read() return (stop the recorder, close the pipe). */
    fun stop(unblock: () -> Unit) {
        running = false
        unblock()
        thread.join(500)
    }
}

/** Reads whole stereo frames of little-endian 16-bit PCM from a pipe. */
class PcmPipe(pipe: ParcelFileDescriptor) {
    private val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pipe)
    private val bytes = ByteArray(CHUNK_SHORTS * 2)
    private var have = 0

    /** Same contract as AudioRecord.read: shorts read, or negative at end of stream. */
    fun read(out: ShortArray): Int {
        val n = input.read(bytes, have, minOf(bytes.size, out.size * 2) - have)
        if (n < 0) return -1
        have += n
        val usable = have / 4 * 4  // whole stereo frames only
        if (usable == 0) return 0
        ByteBuffer.wrap(bytes, 0, usable).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, usable / 2)
        System.arraycopy(bytes, usable, bytes, 0, have - usable)
        have -= usable
        return usable / 2
    }

    fun close() {
        runCatching { input.close() }
    }
}
