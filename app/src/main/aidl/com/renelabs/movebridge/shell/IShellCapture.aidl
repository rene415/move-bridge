package com.renelabs.movebridge.shell;

/**
 * Runs in Move Bridge's shell-uid helper (started through Wireless debugging).
 * Does the things only the shell user may do: capture playback without MediaProjection, tell
 * Android about the Move's sound card (Direct mode), and set Move Bridge as the Move's default app.
 */
interface IShellCapture {
    /**
     * Starts capture. Returns the read end of a pipe carrying 44.1 kHz stereo 16-bit little-endian PCM.
     * playOnPhone: also keep playing on the phone's own speaker/headphones.
     */
    ParcelFileDescriptor start(boolean playOnPhone) = 1;

    /** Stops capture and closes the pipe. */
    void stop() = 2;

    /** Grants Move Bridge WRITE_SECURE_SETTINGS, so it can restart this helper by itself after a reboot. */
    boolean grantSecureSettings() = 3;

    /** Version code of the APK this helper was started from (an old helper is replaced after an update). */
    int version() = 4;

    /**
     * Direct mode on: tells Android that the Move's sound card is a USB audio output, so Android
     * plays to the Move by itself. cardHint: the ALSA card number Android reports for the Move,
     * or -1 to let the helper look for it. alsoPhone: play on the phone's speaker as well.
     * Returns false while the card isn't there. Calling it again while on only updates alsoPhone.
     * The helper withdraws the route by itself when the card disappears (Move unplugged).
     */
    boolean directOn(int cardHint, boolean alsoPhone) = 5;

    /** Direct mode off: Android goes back to its normal output. */
    void directOff() = 6;

    /** Makes Move Bridge the app Android opens for the plugged-in Move, so it stops asking. */
    boolean makeDefault() = 7;

    /** The last lines of the helper's own log, for a report the user chooses to send. */
    String recentLog(int maxLines) = 8;
}
