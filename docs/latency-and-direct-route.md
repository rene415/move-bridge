# Latency, and the direct (native) route

Measured 2026-11-12 on a Galaxy S26 Ultra (Android 17) and an Ableton Move (AbletonOS v4.1).

## Why Android only shows the Move as MIDI
- The phone's **kernel** accepts the Move as a normal USB sound card: with Move Bridge out of the way, `/sys/class/sound` lists `card1`, `pcmC1D0p` (playback), `pcmC1D0c` (capture) and `midiC1D0`.
- Android's **framework** then ignores the audio part. `UsbDescriptorParser.hasOutput()` / `hasInput()` skip terminals whose type is in the "wrong" category, and the Move labels its analog terminals the other way round (output terminal type `0x0200`, input terminal type `0x0300`; see [move-usb-descriptor.md](move-usb-descriptor.md)). Result: "no audio output, no audio input", so only the MIDI port is registered (`dumpsys usb` → `alsa_midi_devices`, card 1).
- iOS, macOS and Linux don't apply that check, which is why an iPhone "just works" and the USB-C port behaves like a headphone jack.
- **Fix on Ableton's side:** report the terminals as, for example, `0x0301` (speaker) / `0x0603` (line connector) for the output terminal and `0x0201` (microphone) / `0x0603` for the input terminal. Android phones would then play to the Move with no app at all.

## Direct mode (in the app since v0.16)
The shell helper tells Android about the kernel's card itself, with the same call Android's own USB code uses for a DAC:

`AudioManager.setWiredDeviceConnectionState(DEVICE_OUT_USB_DEVICE (0x4000), 1, "card=1;device=0", name)` (needs `MODIFY_AUDIO_ROUTING`, which the shell user has).

Android then lists "USB-Audio - Ableton Move" as an output with the right profile (16-bit, 44.1 kHz, stereo) and routes media to it. No capture, no MediaProjection, no USB streaming by Move Bridge. The phone's volume buttons set the level, and the Move's MIDI port keeps working on the phone.

How the pieces fit:
- **Not a setting:** Direct mode is used whenever the helper runs or can be started (the one-time setup is done). Bridge mode (capture + our own USB streaming) is only the fallback.
- **App side** (`DirectLink`): finds the card number from `MidiManager` (device properties `alsa_card` + `usb_device`), asks the helper to announce it (`IShellCapture.directOn`), and waits until Android lists the Move among its outputs. It then measures the delay on the route (silent `AudioTrack`s) for the Connection details.
- **Helper side** (`DirectRoute`): announces the card, then watches `/sys/class/sound` and withdraws the route the moment the card disappears (Move unplugged), so Android is never left playing into a device that is gone. The card number is kept in `/data/local/tmp/mb_direct_card`: a restarted helper picks a live route back up, or withdraws a stale one.
- **Move Bridge must not claim the Move's USB interfaces in this mode.** Claiming interface 1 makes the kernel drop the whole card, MIDI included (that's what Bridge mode does).
- **Getting the card back after Bridge mode** (`UsbLink.handBack`): claim interfaces 1-5 (this also unbinds what is left of the dead card), then release 2, 3, 5, 1, 4. Android's `releaseInterface` re-attaches the kernel driver, and the driver builds a new card with PCM and MIDI. Measured: about 0.5 s from Bridge to Direct, no unplugging. If Bridge mode ended uncleanly (app killed mid-stream), Direct's first try finds no card; the app then opens the Move just to do this hand-back (`UsbLink.revive`) and tries once more.
- **Fallback:** if Direct can't start (no helper, no card), the connection uses Bridge mode and says so on the status card.
- **Test tone** in Direct mode is an ordinary media `AudioTrack` (`TonePlayer`), so it takes the same road as any app's sound.
- **Outliving the app:** the route stays while the Move is plugged in, even if the app is closed or updated. When the app opens and finds Android already playing to the Move, it adopts that state.

Things to know:
- Everything Android plays goes to the Move in this mode, notification sounds included (Bridge mode only captures media, games and untagged audio).
- Unplugging or pressing Stop works like pulling out headphones: Android pauses playing apps.
- If the helper is killed while the route is on (for example USB debugging switched off), nothing withdraws the route at unplug until the helper runs again.
- By hand, from a PC: `ShellProbe native-on 1`, `native-off 1`, `cards`, `out-latency`.

## No "open with" prompt (v0.16)
Android asks which app to open on every plug-in when more than one app accepts the Move (any MIDI app with a generic USB-MIDI filter does). The helper now sets Move Bridge as the default app for the plugged-in Move: `IUsbManager.setDevicePackage(device, package, user)`, which needs `MANAGE_USB` (the shell user has it). It is the same as ticking "Always" in Android's dialog, is stored per device (serial number included), and survives restarts. Visible in `dumpsys usb` under `device_preferences`.

One wrinkle: `UsbDevice` refuses to be read in a process that claims to be a system process, which the helper does (`ShellIdentity`). `ShellIdentity.asOrdinaryProcess` drops that claim just while the device list is read.

## Measurements (phone side: until audio leaves the phone)

| Route | App type | First sound (tap-to-hear) | Continuous playback |
|---|---|---|---|
| Bridge (capture, today) | any app | ~80 ms (60 capture + 18 buffer + 4 USB) | ~150-170 ms |
| Direct | ordinary media app (default track, "deep buffer") | ~120-185 ms | ~210 ms, but apps know it, so video stays in sync |
| Direct | low-latency app (48 kHz fast track) | ~40-45 ms | **~27 ms** |
| Phone speaker, for reference | ordinary media app | not measured | ~213 ms |

- Direct numbers are from `AudioTrack` timestamps (`ShellProbe out-latency`; the app shows the same measurement under Connection details); Bridge numbers from click tests (`ShellProbe latency-*`) plus the engine's own buffer and USB queue.
- **Android's floor on this phone is ~27 ms**, and only for apps that ask for low-latency audio. Ordinary media apps get Android's deep-buffer path (~170 ms inside the audio HAL) on every output, including the phone's own speaker.
- Bridge mode is faster than Direct for ordinary media apps because the loopback takes tracks off the deep-buffer path. Its floor is set by Android's loopback device: 21 ms mixer period + a pipe that regulates to ~45-60 ms + 21 ms record period. None of that is tunable from outside.
- 3-4 ms end to end isn't reachable from any phone: one USB audio hop plus the Move's own block size is already more than that.

## The Move's side
- Hardware: Raspberry Pi CM4. There is no ALSA on it. USB audio and MIDI are handled by an **XMOS** chip (USB device `2982:1958`), which talks to the `Move` process over SPI (`/dev/ablspi0.0`). The CM4's own USB gadget only provides the network link (`ncm.usb0`, device `2982:1959`).
- So the Move's logs can't show per-packet USB audio problems, and nothing about its input latency can be read out. The `audio-dropouts:N` lines appear constantly, even with nothing plugged in.
- To measure the Move's own delay without changing it: send a click on the OUT endpoint and time its return on the IN endpoint (Move set to USB-C Audio = Main Out, input monitored). Not built yet.

## How the Move's side was looked at
Read-only, over SSH: the Move's web page (Move Manager → Development → SSH Keys) accepts a public key, after which `ssh ableton@<move ip>` works. Nothing on the Move was changed.
