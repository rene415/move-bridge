package com.renelabs.movebridge

/** JNI wrapper around the libusb engine in move_bridge.cpp. */
object NativeBridge {
    init { System.loadLibrary("movebridge") }

    /** Starts streaming on an open USB fd. Returns "OK\n<log>" or "ERR ...". */
    external fun start(fd: Int, tone: Boolean): String
    /** Queues interleaved stereo 16-bit frames for the Move. */
    external fun write(data: ShortArray, frames: Int)
    external fun stop()
    external fun isRunning(): Boolean
    external fun stats(): String
    /** Peak level (0..1) of audio sent since the last call. */
    external fun level(): Float
    /** Debug: record the next 15 s of outgoing audio to [path]. */
    external fun setDumpPath(path: String)

    const val MOVE_VID = 0x2982
    const val MOVE_PID = 0x1958
}
