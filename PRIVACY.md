# Privacy

Move Bridge exists to make the Ableton Move more useful with Android phones. It is not a business and has no interest in your data.

## The short version
- No accounts, no ads, no analytics, no tracking.
- The app has no server. **Nothing leaves your phone unless you send a report yourself.**
- Your audio goes from your phone to your Move over the cable, and nowhere else. It is never recorded or stored by the app.

## What stays on your phone
- Your settings.
- The pairing key for your phone's own Wireless debugging (audio-only mode).
- A scrambled fingerprint of the Wi-Fi network the setup was done on, so "Start automatically" only acts there. It never leaves the phone.
- The app's own recent log, in memory only, and the reason for the last crash if there was one.

Uninstalling the app removes all of it.

## Feedback and crash reports
Reports are optional and always sent by you, never by the app:
- **Send feedback** builds a text and hands it to the app you choose (email, a messenger, a GitHub issue) or copies it.
- After a crash, the app asks once whether you want to look at the crash report and send it. "Delete it" removes it.
- You can read every line before sending, and you can leave the technical details out.

The technical details are: Move Bridge's version, your Android version, your phone's make and model, Move Bridge's own settings, its connection state, its own recent log, and the last crash if any.

They never include: your name or accounts, phone or Move serial numbers, advertising or device IDs, network names or addresses, your location, your other apps, or any audio.

Reports are used to fix and improve Move Bridge, and for nothing else. A GitHub issue is public, so don't write anything private into one.

## Permissions, and why
| Permission | Why |
|---|---|
| USB access to the Move | To send audio to it. |
| Record audio | Android requires it for capturing what the phone plays in Bridge mode. The microphone is never used. |
| Screen-share ("media projection") prompt | Only without audio-only mode: it is Android's only capture permission for other apps' sound. Move Bridge takes the audio and never the screen. |
| Notifications | The "Stop sending audio" notification, and typing the pairing code during setup. |
| Run in the foreground | To keep sending while you use other apps. |
| Internet | Only to talk to your phone's own Wireless debugging service, on the phone itself. Move Bridge contacts no server. |
| Change secure settings | Only if you turn on "Start automatically": to switch Wireless debugging on for a moment after a restart, and off again. |
| Start after a restart, network state | To start the helper again after a restart once the phone is on Wi-Fi. |

## The helper
Audio-only mode starts a small helper through your phone's Wireless debugging. It runs with the rights of Android's debugging shell, which are broader than a normal app's. It is part of this app, its source is in this repository (`app/src/main/java/.../shell`), and it does three things: tells Android to play to the Move, captures playback for Bridge mode, and makes Move Bridge the default app for the Move. It only accepts commands from Move Bridge itself and stops when the phone restarts.
