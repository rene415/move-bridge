# Changelog

The app shows the newest entry once after each update ("What's new").

## 0.18
- **Simpler settings.** The "Audio-only mode" and "Direct to Move" switches are gone. Once the one-time setup is done, Move Bridge always plays straight to the Move; without it, it falls back to screen-share capture by itself.
- When the setup isn't done, or Wireless debugging is needed once after a restart, Settings shows a line that says so.
- If Android forgets the pairing, the app notices and offers the setup again.
- The steps on the Move follow Ableton's manual, with a tip about monitoring and a link to the manual.
- Apache-2.0 license; the licenses of included code are listed in the app.

## 0.17
- **Also play on phone** now works with Direct to Move: phone speaker and Move play together, from the same Android mixer.
- **Mute notifications while sending:** the phone goes on vibrate while sending, so rings and pings don't land in samples.
- **Quick Settings tile:** start and stop from the pull-down panel.
- With **Start automatically** on, the helper comes back by itself after a phone restart, before the Move is plugged in.
- **Setup guide** for the one-time setup, with steps that tick themselves off.
- **Send feedback** screen and an offer to send a crash report after a crash. Nothing is sent unless you send it.
- "What's new" notes after an update.

## 0.16
- **Direct to Move:** Android plays straight to the Move, like USB-C headphones. Less delay, the phone's volume buttons set the level, and the Move's MIDI keeps working.
- After Bridge mode stops, the Move's MIDI port comes back without unplugging.
- No more "which app should open this?" question on plug-in.
- The notification's Stop button is no longer cut off.
- Connection details show the delay measured on your phone.

## 0.10 to 0.15
- Audio-only mode in one app, with no second app to install.
- Apps that start playing later are routed to the Move too.
- Full level while the phone itself is silent.
- A big, labeled Stop button in the notification.

## 0.5 to 0.9
- Audio-only mode: capture without Android's screen sharing.
- Steadier stream with less delay (adaptive buffer).
- Code cleanup: small functions, zero warnings.

## 0.4
- New look that follows the phone's light or dark mode.

## 0.3
- First working version: phone audio to the Move over USB-C, using Android's screen-share permission.
