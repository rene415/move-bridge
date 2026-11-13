package com.renelabs.movebridge.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler

/**
 * Standard capture: Android's playback capture through a MediaProjection (the "share your screen"
 * consent). Works on any Android 10+ phone with no setup. [onEnded] runs on [main] with a reason
 * when the recorder dies, or null when Android ends the projection.
 */
class ProjectionCapture private constructor(
    private val projection: MediaProjection,
    private val record: AudioRecord,
    onEnded: (String?) -> Unit,
    main: Handler,
) : CaptureSource {
    override val isProjection = true
    private val thread = CaptureThread("capture", { record.read(it, 0, it.size) }) { main.post { onEnded(DIED) } }
    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        thread.stop { runCatching { record.stop() } }  // stop() unblocks read() before release()
        record.release()
        runCatching { projection.stop() }
    }

    companion object {
        /** Marker passed to onEnded when the recorder died; the service maps it to a message. */
        const val DIED = "died"

        /** Throws if Android refuses the projection or the recorder. */
        fun open(c: Context, code: Int, data: Intent, main: Handler, onEnded: (String?) -> Unit): ProjectionCapture {
            val p = c.getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                ?: throw IllegalStateException("no projection")
            // Must be registered before capture starts (Android 14+).
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { onEnded(null) } }
            }, main)
            val r = try { buildRecord(p) } catch (e: Exception) { p.stop(); throw e }
            r.startRecording()
            return ProjectionCapture(p, r, onEnded, main)
        }

        @SuppressLint("MissingPermission")  // RECORD_AUDIO is checked before the service starts
        private fun buildRecord(p: MediaProjection): AudioRecord {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioRecord.Builder().setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min * 2, 44100 * 4 / 5))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        }
    }
}
