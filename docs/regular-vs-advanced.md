# Regular or Advanced: what you actually gain

Measured on 13 November 2026 on a Samsung Galaxy S26 Ultra (Android 17) with an Ableton Move. Other phones will give other numbers; the differences should point the same way.

## The short answer
- **You play instrument or game apps into the Move:** Advanced mode is a big step. The delay drops from about 130 ms, which you can clearly feel, to about 25 ms, which most people can't.
- **You sample from music, video or sample-library apps:** Regular mode does the job. Advanced starts about a tenth of a second sooner and needs no prompt, but nothing you record sounds different.

## Delay
Time from the app handing over its sound until that sound leaves the phone for the Move.

| What you're doing | Regular | Advanced | Gain |
|---|---|---|---|
| Instrument or game app, while playing | about 130 ms | about 25 ms | about 105 ms (five times less) |
| Instrument or game app, first note after silence | about 175 ms | about 45 ms | about 130 ms |
| Music or video app, pressing play | about 220 ms | about 125 ms | about 95 ms |
| Music or video app, while playing | about 290 ms | about 210 ms | about 80 ms |

- **Instrument and game apps** ask Android for its low-delay path. Advanced lets them have it all the way to the Move. Regular has to capture the sound first, and Android's capture adds about 100 ms on its own.
- **Music and video apps** use Android's power-saving path, which is slow in both modes (about 210 ms even on the phone's own speaker). Apps know that and line the picture up with the sound.

The Move's own delay comes on top and is the same in both modes.

## Everything else
| | Regular | Advanced |
|---|---|---|
| Setup | None | About two minutes, once. Android's Developer options have to stay on. |
| Starting | Android's capture prompt every time, or once per phone restart with **Stay ready in background** | Nothing to tap. Sound about 2 seconds after plugging in. |
| While sending | Android shows its capture icon. Android 15 and newer hides the contents of notifications. | Nothing changes on the phone. |
| Phone's speaker | On or off (**Also play on phone**) | On or off (**Also play on phone**) |
| Level the Move gets | Always full, whatever the phone's volume | Set by the phone's volume buttons |
| What reaches the Move | Music, video, games. Not notification sounds. | Everything Android plays, notification sounds included. **Mute notifications while sending** keeps them out. |
| The Move's MIDI on the phone | Unavailable while sending | Keeps working |
| Move Bridge's processor use | About 8 % of one core | About 0 % |
| After a phone restart | Nothing to do | The helper has to start again: by itself with **Start automatically**, otherwise by turning Wireless debugging on once. |

Not tested yet: apps that block capture (some streaming services). In Regular mode they come through silent. Advanced mode doesn't capture, so they should play, but that has not been checked.

## How it was measured
- **Advanced:** Android's own timing figures for the output to the Move, read six times each for an ordinary player and a low-delay player.
- **Regular:** a separate test program played a short click, and Move Bridge logged the moment the click arrived in its capture. To that we added the app's own buffer (18 ms, measured) and its USB queue (4 ms). Six to ten clicks per row.
- The table shows the middle value of each series, rounded to 5 ms. The series were tight for low-delay players while playing (within about 20 ms) and wider for first sounds and for ordinary players (within about 80 ms).
- **Level:** a click written at a known level arrived unchanged in Regular mode with the phone's volume at zero, at half and at full.
