# The helper: working without screen sharing

## Why plain capture looks like screen sharing
- Android's public way to capture other apps' sound is `AudioPlaybackCapture`. It only works with a `MediaProjection`, which is Android's **screen-capture** session. There is no public audio-only capture permission (checked up to Android 17).
- While any MediaProjection is active, Android treats the phone as screen sharing:
  - the "share your screen" consent screen
  - the status-bar capture icon
  - Android 15+ screen-share protections: notification content is hidden, and windows with passwords or other sensitive content are blanked
- The protections key off MediaProjection itself. Move Bridge never creates a virtual display, but the system can't tell that apart from a real screen recording.

This is what Move Bridge uses for regular users (basic sending), because it works on any Android 10+ phone with no preparation. Everything below belongs to Advanced mode.

## The helper
Android's audio system can do much more for a caller that holds `MODIFY_AUDIO_ROUTING`. Normal apps can't get that permission, but the **shell** user (`com.android.shell`, what `adb shell` runs as) has it. So Move Bridge starts a small helper process as the shell user:

**One-time setup (the in-app Setup guide):**
1. Turn on Developer options.
2. Turn on **Wireless debugging** (the phone must be on Wi-Fi).
3. **Pair device with pairing code.** Move Bridge finds the pairing port by itself (NsdManager, `_adb-tls-pairing._tcp`) and shows a notification with a reply box for the 6-digit code. It has to be a notification, because the pairing screen closes when you leave it.

**Each start (once per phone restart):**
1. Move Bridge finds the phone's own debugging service (`_adb-tls-connect._tcp`) and connects with its saved pairing key (`adb/AdbManager.kt`, `adb/ShellStarter.kt`).
2. It runs `app_process` from its own APK, which starts the helper (`shell/ShellMain.kt`) as the shell user. The helper detaches, so it keeps running after that connection closes, until the phone restarts.
3. The helper hands its Binder to the app through a ContentProvider (`ShellLink.kt`). A normal app can't connect to a shell-owned socket under SELinux, but Binder works. The provider is guarded: only the shell or system user can call it, and the helper only accepts calls from Move Bridge's own uid.

This is the same technique apps like Shizuku and LADB use; Move Bridge has it built in, so there is nothing else to install. The helper identifies as `com.android.shell` (see `shell/ShellIdentity.kt`), or the audio system rejects it.

**Start automatically (optional):**
- Once running, the helper can grant the app `WRITE_SECURE_SETTINGS`.
- With that, after a restart Move Bridge switches Wireless debugging on, starts the helper, and switches it off again: in the background once the phone is on the Wi-Fi network where it was set up (`HelperJob.kt`), or at the latest when the Move is plugged in.
- It is a security tradeoff (the app can toggle a developer setting), so it is an explicit opt-in switch.

## What the helper does
1. **Direct mode:** tells Android that the Move's sound card is a USB audio output. See [latency-and-direct-route.md](latency-and-direct-route.md).
2. **Bridge mode capture without MediaProjection** (`shell/ShellAudio.kt`), the method [scrcpy](https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md) uses for `--audio-source=playback`:
   - `AudioMixingRule.Builder().setTargetMixRole(MIX_ROLE_PLAYERS)`, with a usage rule for media, game and untagged audio
   - `AudioMix.Builder(rule).setFormat(44.1 kHz stereo 16-bit).setRouteFlags(ROUTE_FLAG_LOOP_BACK)`; `LOOP_BACK_RENDER` keeps the sound on the phone too ("Also play on phone")
   - `AudioPolicy.Builder(ctx).addMix(mix).build()`, then the hidden `AudioManager.registerAudioPolicyStatic(policy)`
   - `policy.createAudioRecordSink(mix)` returns an `AudioRecord`; its PCM goes to the app over a pipe, and the app sends it to the Move
3. **Default app:** makes Move Bridge the app Android opens for the Move, so plugging in doesn't ask.

## Costs
- Developer options must stay enabled, and the helper has to be started again after every phone restart.
- A one-time pairing step that is fiddlier than a normal install.
- It relies on hidden Android APIs that could change between releases. scrcpy has kept the capture method working since Android 13.
- The pairing library, [libadb-android](https://github.com/MuntashirAkon/libadb-android), has not had a security audit. Move Bridge uses it only to connect to the phone's own debugging service and send the helper's start command.

## What would make all this unnecessary
- **From Ableton:** a small change in the Move's USB description would let every Android phone play to it with no app at all. See [latency-and-direct-route.md](latency-and-direct-route.md).
- **From Android:** an audio-only capture consent, for example a `MediaProjectionConfig` flag that grants playback capture without screen content and without triggering the screen-share protections.

## Sources
- scrcpy audio docs: https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md
- scrcpy `AudioPlaybackCapture.java`: https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioPlaybackCapture.java
- Android 15 screen-share protections: https://www.androidauthority.com/android-15-apps-selectively-hide-sensitive-content-screen-sharing-3436855/
