package com.renelabs.movebridge.shell

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.system.Os
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Direct mode, the helper's side: makes Android itself play to the Move, like USB-C headphones or
 * an iPhone.
 *
 * The phone's kernel already creates a sound card for the Move, but Android ignores it: the
 * Move's USB descriptor labels its terminals unusually (output terminal 0x0200, input terminal
 * 0x0300), so Android's descriptor check finds "no audio output" and only registers the MIDI
 * port. Here the shell helper tells the audio system about the card directly, the same call
 * Android's own USB code makes for a normal USB DAC. Needs MODIFY_AUDIO_ROUTING (shell has it).
 *
 * While this route is on, Move Bridge must NOT claim the Move's USB interfaces: the kernel
 * driver has to keep them. See docs/latency-and-direct-route.md.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object DirectRoute {
    private const val DEVICE_OUT_USB_DEVICE = 0x4000
    private const val NAME = "USB-Audio - Ableton Move"
    private val INT = Int::class.javaPrimitiveType!!
    private const val POLL_MS = 250L
    /** The announced card, kept across a helper restart so a stale route can still be withdrawn. */
    private val MEMO = File("/data/local/tmp/mb_direct_card")

    private var card = -1  // guarded by this

    /**
     * Announces the Move's card ([hint], or the one we find). False while there is no such card.
     * [alsoPhone]: the phone's speaker plays too. Calling this again while on just updates that.
     */
    @Synchronized
    fun on(hint: Int, alsoPhone: Boolean): Boolean {
        val found = SoundCards.moveCard(hint) ?: return false
        if (found != card) {
            offLocked()
            setConnected(found, true)
            card = found
            runCatching { MEMO.writeText(found.toString()) }
            startWatch(found)
        }
        both(found, alsoPhone)
        return true
    }

    private fun both(c: Int, on: Boolean) {
        runCatching { BothOutputs.set(audioManager(), c, on) }
            .onSuccess { log("phone speaker plays too: $on") }
            .onFailure { log("couldn't set phone-and-Move playback: ${it.cause ?: it}") }
    }

    @Synchronized
    fun off() = offLocked()

    /**
     * After a helper restart: keep watching a route that is still valid, and withdraw one whose
     * card is gone (Android would otherwise keep playing into a device that isn't there).
     */
    @Synchronized
    fun recover() {
        val old = runCatching { MEMO.readText().trim().toInt() }.getOrNull() ?: return
        card = old
        log("found a route for card $old from before; card present: ${SoundCards.present(old)}")
        if (SoundCards.present(old)) startWatch(old) else offLocked()
    }

    private fun offLocked() {
        if (card < 0) return
        both(card, false)  // or media would stay pinned to the speaker after the Move is gone
        runCatching { setConnected(card, false) }.onFailure { log("withdraw failed: $it") }
        card = -1
        MEMO.delete()
    }

    private fun startWatch(c: Int) {
        Thread({ watch(c) }, "direct-watch").apply { isDaemon = true; start() }
    }

    /** Runs while the route is on, by design: withdraws it the moment the card disappears. */
    private fun watch(c: Int) {
        while (current() == c && SoundCards.present(c)) Thread.sleep(POLL_MS)
        synchronized(this) { if (card == c) offLocked() }
    }

    @Synchronized
    private fun current() = card

    /** Announces (or withdraws) ALSA [card] as a USB audio output. Throws if Android refuses. */
    fun setConnected(card: Int, connected: Boolean) {
        Reflect.call(audioManager(), AudioManager::class.java, "setWiredDeviceConnectionState",
            INT to DEVICE_OUT_USB_DEVICE, INT to (if (connected) 1 else 0),
            String::class.java to "card=$card;device=0", String::class.java to NAME)
        log("card $card ${if (connected) "announced" else "withdrawn"}")
    }

    /** An AudioManager that acts as the shell user. */
    fun audioManager(): AudioManager =
        Reflect.newInstance(AudioManager::class.java, Context::class.java to ShellIdentity.context()) as AudioManager

    private fun log(s: String) = System.err.println("MoveBridge shell: direct: $s")
}

/**
 * What the shell user can see of the kernel's sound cards: the names in /sys/class/sound and
 * where their links point. (It may not read the files, so no vendor or product ids.)
 */
internal object SoundCards {
    private const val DIR = "/sys/class/sound"
    private const val MAX_CARDS = 32
    /** The kernel lists a playback device for [card]. */
    fun present(card: Int): Boolean = File(DIR).list()?.contains("pcmC${card}D0p") == true

    /**
     * The Move's card: [hint] (what Android reports for the Move's MIDI port) if that is a USB
     * card with playback. Otherwise (no MIDI entry on this phone, or the card came back under
     * another number) the only USB card with playback, if there is exactly one.
     */
    fun moveCard(hint: Int): Int? {
        if (hint >= 0 && isUsbPlayback(hint)) return hint
        return (0 until MAX_CARDS).filter(::isUsbPlayback).singleOrNull()
    }

    private fun isUsbPlayback(card: Int) = present(card) && link(card).contains("/usb")

    private fun link(card: Int): String = runCatching { Os.readlink("$DIR/card$card") }.getOrDefault("")
}
