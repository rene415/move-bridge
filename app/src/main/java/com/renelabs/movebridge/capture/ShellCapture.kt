package com.renelabs.movebridge.capture

import android.content.Context
import android.os.Handler
import android.os.ParcelFileDescriptor
import com.renelabs.movebridge.AudioOnly
import com.renelabs.movebridge.Diag
import com.renelabs.movebridge.Prefs
import com.renelabs.movebridge.shell.IShellCapture

/**
 * Audio-only capture: Move Bridge's shell-user helper records the phone's playback with no
 * MediaProjection and streams it to us over a pipe.
 */
class ShellCapture private constructor(
    private val svc: IShellCapture,
    pipe: ParcelFileDescriptor,
    onEnded: (String?) -> Unit,
    main: Handler,
) : CaptureSource {
    override val isProjection = false
    private val input = PcmPipe(pipe)
    private val thread = CaptureThread("capture", input::read) { main.post { onEnded(ProjectionCapture.DIED) } }
    @Volatile private var closed = false

    override fun close() = close(stopHelper = true)

    /**
     * Stops reading. [stopHelper] false leaves the helper's capture running: used when a new
     * capture is about to replace this one (the helper swaps them without a gap).
     */
    fun close(stopHelper: Boolean) {
        if (closed) return
        closed = true
        // Stopping the helper closes its end of the pipe, which ends our read().
        thread.stop {
            if (stopHelper) runCatching { svc.stop() }
            input.close()
        }
    }

    companion object {
        /**
         * Opens audio-only capture, starting our helper through Wireless debugging if needed.
         * [onReady] runs on [main] with the capture, or null if no shell helper could be used.
         * [isCurrent] returning false (the request was cancelled meanwhile) discards the result.
         */
        fun open(c: Context, main: Handler, isCurrent: () -> Boolean, onEnded: (String?) -> Unit,
                 onReady: (ShellCapture?) -> Unit) {
            AudioOnly.withHelper(c, main) { svc ->
                if (svc == null) onReady(null)
                else if (isCurrent()) onReady(create(c, svc, main, onEnded))
            }
        }

        private fun create(c: Context, svc: IShellCapture?, main: Handler, onEnded: (String?) -> Unit): ShellCapture? {
            val pipe = try { svc?.start(Prefs.playOnPhone(c)) } catch (e: Exception) {
                Diag.e("shell capture start failed", e)
                null
            }
            if (svc == null || pipe == null) return null
            return ShellCapture(svc, pipe, onEnded, main)
        }
    }
}
