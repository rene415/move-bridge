# Advanced mode

Everything on this page is optional. Move Bridge works without it; see the [README](../README.md).

## What it gives you
With Advanced mode on and its one-time setup done, Android itself plays to the Move, like USB-C headphones or an iPhone:
- **Much less delay for instrument and game apps:** about 25 ms instead of about 130 ms on our test phone.
- **Music and video apps start sooner:** about 125 ms after pressing play instead of about 220 ms.
- **No capture prompt**, no capture icon, and notifications stay visible.
- The phone's volume buttons set the level, and the Move's MIDI keeps working on the phone.
- Move Bridge itself uses next to no processor time.

All the numbers, and how they were measured: [regular-vs-advanced.md](regular-vs-advanced.md). The app shows its own measurement for your phone under **Connection details**.

## What it costs
- It needs Android 13 or newer and a one-time setup in Android's **Developer options**.
- Developer options have to stay enabled.
- After a phone restart the app's helper has to start again: by itself with **Start automatically**, or when you turn Wireless debugging on once.
- Everything Android plays goes to the Move, notification sounds included (and normally phone calls too). **Mute notifications while sending** keeps pings out.

## The one-time setup (about two minutes)
In Move Bridge, switch on **Settings → Advanced mode**. The setup guide opens and ticks each step off:

1. **Turn on Developer options:** Settings → About phone → Software information → tap **Build number** 7 times.
2. **Turn on Wireless debugging:** Settings → Developer options → **Wireless debugging** → on. The phone must be on Wi-Fi. Tap **Allow**.
3. **Pair:** in Wireless debugging, tap **Pair device with pairing code**. Keep that screen open, pull down your notifications, and type the 6-digit code into the Move Bridge notification.
4. Move Bridge starts its helper. Done.

<img src="screens/setup-guide.png" width="320" alt="The setup guide with all four steps ticked">

Why Developer options? Android only lets its own debugging service start the small helper that Move Bridge needs to route audio. Move Bridge connects only to the phone itself, never to another device.

From then on, plugging in the Move starts sending by itself:

<img src="screens/sending.png" width="320" alt="The app while Android plays straight to the Move">

## What Advanced mode adds to the app
| | |
|---|---|
| **Also play on phone** | Phone and Move play together from the same mixer, and the phone's volume sets the level for both. |
| **Start automatically** | After a phone restart, Move Bridge turns Wireless debugging on for a moment by itself to start its helper again, then off. Needs Wi-Fi, and only acts on the Wi-Fi network where the setup was done. |
| **Connection details** | What route is in use and the delay measured on your phone. |
| **Setup guide** | The four steps above, any time. |
| A line in Settings | Only when something needs doing: the setup isn't finished, or Wireless debugging is needed once after a restart. |

<img src="screens/settings-advanced.png" width="320" alt="The settings card with Advanced mode on">

If the direct connection can't start, Move Bridge falls back by itself: first to capturing through its helper, then to basic sending.

Switching Advanced mode off returns the app to basic sending. The pairing stays on the phone, so switching it on again needs no new setup.

## Build from source (Windows)
Needs JDK 17 and the Android SDK with platform 36, **NDK 28.2.13676358** and CMake 3.22.1. Put the SDK path in `local.properties` (`sdk.dir=...`).

```powershell
$env:JAVA_HOME = "<path to JDK 17>"
.\gradlew.bat lintRelease assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Without a `keystore.properties` file the build is signed with your machine's debug key, which can't update an install made from the official releases (uninstall that first).

The code follows two house rules: no function longer than 25 lines, and zero warnings (C++ `-Werror`, Kotlin `allWarningsAsErrors`, lint `warningsAsErrors`).

Logs: `adb logcat -s MoveBridge`.

## How it works
- [move-usb-descriptor.md](move-usb-descriptor.md): the Move's USB interfaces and the streaming handshake.
- [audio-only-capture.md](audio-only-capture.md): the helper, and working without screen sharing.
- [regular-vs-advanced.md](regular-vs-advanced.md): the measured comparison of the two modes.
- [latency-and-direct-route.md](latency-and-direct-route.md): why Android shows the Move as MIDI only, and how the direct connection works.
- [releasing.md](releasing.md): how a release is made.
- [../CHANGELOG.md](../CHANGELOG.md): what changed in each version.
