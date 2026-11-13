package com.renelabs.movebridge.shell

import com.renelabs.movebridge.BuildConfig

/** Constants shared by the app and its shell helper (no API-level requirements). */
object ShellProtocol {
    /** ShellLinkProvider authority the helper calls to hand over its Binder. */
    const val AUTHORITY = BuildConfig.APPLICATION_ID + ".shell"
    const val METHOD_BINDER = "binder"
    const val KEY_BINDER = "binder"
    /** The helper's output, overwritten on each start (readable with adb for troubleshooting). */
    const val HELPER_LOG = "/data/local/tmp/mbshell.log"
    /** Capture format: 44.1 kHz stereo 16-bit. */
    const val RATE = 44100
}
