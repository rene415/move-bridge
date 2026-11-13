package com.renelabs.movebridge.shell

import android.os.Build
import androidx.annotation.RequiresApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord

/**
 * Audio-only capture of what other apps are playing, WITHOUT MediaProjection.
 *
 * Registers a loopback audio policy (the method scrcpy uses for --audio-source=playback,
 * Apache-2.0, github.com/Genymobile/scrcpy). That needs MODIFY_AUDIO_ROUTING, which only the
 * shell user (and system) has, so this only works inside Move Bridge's shell-uid helper
 * process. It never runs in the normal app process.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)  // audio-only mode needs Android 13+
object ShellAudio {
    const val RATE = ShellProtocol.RATE
    private val RULE = Reflect.cls("android.media.audiopolicy.AudioMixingRule")
    private val RULE_BUILDER = Reflect.cls("android.media.audiopolicy.AudioMixingRule\$Builder")
    private val MIX = Reflect.cls("android.media.audiopolicy.AudioMix")
    private val MIX_BUILDER = Reflect.cls("android.media.audiopolicy.AudioMix\$Builder")
    private val POLICY = Reflect.cls("android.media.audiopolicy.AudioPolicy")
    private val POLICY_BUILDER = Reflect.cls("android.media.audiopolicy.AudioPolicy\$Builder")
    private val INT = Int::class.javaPrimitiveType!!

    /** Same usages as the MediaProjection path: music/video, games, untagged. */
    private val USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)

    /** A running capture: the recorder plus the audio policy that feeds it. */
    class Capture(val record: AudioRecord, private val policy: Any) {
        /** Stops recording and removes the routing rule (or the phone could stay silent). */
        fun close() {
            runCatching { record.stop() }
            record.release()
            runCatching { unregister(policy) }.onFailure { System.err.println("MoveBridge shell: unregister failed: $it") }
        }
    }

    /** Sets up the shell identity. Call once on the helper's main thread before any [start]. */
    fun prepare() {
        ShellIdentity.context()
    }

    /**
     * Starts capturing the phone's playback audio. Throws on failure.
     * [playOnPhone] true keeps the sound playing on the phone as well (ROUTE_FLAG_LOOP_BACK_RENDER);
     * false sends it only to the capture, like an iPhone plugged into the Move.
     * [onlyUsage]: capture only this AudioAttributes usage (the latency test uses USAGE_GAME so
     * music playing at the same time can't confuse it); null captures the normal [USAGES].
     */
    fun start(playOnPhone: Boolean, onlyUsage: Int? = null): Capture {
        val usages = if (onlyUsage == null) USAGES else intArrayOf(onlyUsage)
        val mix = buildMix(buildRule(usages), playOnPhone)
        val policy = registerPolicy(ShellIdentity.context(), mix)
        val record = try {
            Reflect.callPublic(policy, "createAudioRecordSink", MIX to mix) as AudioRecord
        } catch (e: Throwable) {
            runCatching { unregister(policy) }
            throw e
        }
        record.startRecording()
        return Capture(record, policy)
    }

    fun format(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    /** Matches what other apps play (MIX_ROLE_PLAYERS) with any of [usages]. */
    private fun buildRule(usages: IntArray): Any {
        val b = Reflect.newInstance(RULE_BUILDER)
        Reflect.callPublic(b, "setTargetMixRole", INT to Reflect.int(RULE, "MIX_ROLE_PLAYERS"))
        val matchUsage = Reflect.int(RULE, "RULE_MATCH_ATTRIBUTE_USAGE")
        for (u in usages) {
            val attrs = AudioAttributes.Builder().setUsage(u).build()
            Reflect.callPublic(b, "addMixRule", INT to matchUsage, Any::class.java to attrs)
        }
        return Reflect.callPublic(b, "build")!!
    }

    private fun buildMix(rule: Any, playOnPhone: Boolean): Any {
        val b = Reflect.newInstance(MIX_BUILDER, RULE to rule)
        Reflect.callPublic(b, "setFormat", AudioFormat::class.java to format())
        val flag = if (playOnPhone) "ROUTE_FLAG_LOOP_BACK_RENDER" else "ROUTE_FLAG_LOOP_BACK"
        Reflect.callPublic(b, "setRouteFlags", INT to Reflect.int(MIX, flag))
        return Reflect.callPublic(b, "build")!!
    }

    private fun registerPolicy(ctx: Context, mix: Any): Any {
        val b = Reflect.newInstance(POLICY_BUILDER, Context::class.java to ctx)
        Reflect.callPublic(b, "addMix", MIX to mix)
        val policy = Reflect.callPublic(b, "build")!!
        val rc = Reflect.call(null, AudioManager::class.java, "registerAudioPolicyStatic", POLICY to policy) as Int
        check(rc == 0) { "registerAudioPolicy returned $rc" }
        return policy
    }

    private fun unregister(policy: Any) {
        Reflect.call(null, AudioManager::class.java, "unregisterAudioPolicyAsyncStatic", POLICY to policy)
    }
}
