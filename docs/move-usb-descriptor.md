# Ableton Move — USB descriptor notes

Read 2026-11-06 from the Move plugged into a Windows PC (hub port read, nothing changed on the device).

## IDs
- Vendor ID `0x2982` (Ableton), Product ID `0x1958` — composite device "Ableton Move"
- A second device `0x2982:0x1959` is "Ableton Move USB Ethernet" (CDC-NCM, the move.local web UI). Ignore it.
- Windows' built-in `usbaudio2` driver fails on the audio function (Device Manager code 10). Ableton ships its own Windows driver.

## Interfaces (config 1, 6 interfaces, self-powered)
| # | Class | What | Endpoints |
|---|-------|------|-----------|
| 0 | Vendor (FF) | Display | bulk OUT 0x01, bulk IN 0x81 (512) |
| 1 | Audio Control, UAC2 (proto 0x20) | Clock / terminals | interrupt IN 0x84 (6 B, interval 8) |
| 2 | Audio Streaming, UAC2 | **Phone → Move (what we sample)** | alt 1: iso OUT **0x02**, attr 0x05 (async), 28 B, bInterval 1 |
| 3 | Audio Streaming, UAC2 | Move → phone (Main Out / Mic) | alt 1: iso IN 0x82, attr 0x25 (async, **implicit feedback**), 28 B, bInterval 1 |
| 4,5 | Audio/MIDI Streaming | MIDI, 4 in + 4 out jacks | bulk 0x03 / 0x83 (512) |

## Audio Control topology (interface 1)
- Clock Source **ID 1**: internal programmable clock, frequency control read/write.
- Playback path: Input Terminal 4 (USB streaming, 2 ch) → Feature Unit 5 → Output Terminal 6.
  - **OT 6 has terminal type 0x0200** ("Input Undefined"), which is unusual for an output terminal. Normally it would be a speaker/line type such as 0x0301 or 0x0603.
- Capture path: Input Terminal 7 (**type 0x0300**, "Output Undefined") → Feature Unit 8 → Output Terminal 9 (USB streaming).
- The two external terminal types look swapped. That's a possible reason Android and Windows' class drivers don't expose a playback device (not proven).

## Stream format (both directions)
- Type I PCM, 2 channels, 2-byte subslot, 16-bit. 44.1 kHz per Ableton's docs (confirm with GET RANGE on clock 1).
- High-speed: bInterval 1 means one packet per 125 µs microframe (8000 packets/s).
  - 44100 / 8000 = 5.5125 frames per packet, so packets alternate 5 and 6 frames (20 or 24 bytes).
  - Max 7 frames (28 bytes).
- The OUT endpoint has **no explicit feedback endpoint**. Instead, IN endpoint 0x82 is flagged implicit-feedback. Correct pacing: run the IN stream on interface 3 as well, and send as many frames per OUT packet as arrived in the matching IN packet.

## Handshake the app must do (libusb via NDK, fd from Android UsbManager)
1. Open the device; claim interfaces 1, 2 and 3 (detach any kernel driver).
2. Set the sample rate:
   - Control transfer: bmRequestType 0x21, bRequest 0x01 (CUR), wValue 0x0100 (SAM_FREQ_CONTROL), wIndex 0x0101 (clock entity 1, interface 1).
   - Data: 44100 as a 4-byte little-endian value (`44 AC 00 00`).
3. SET_INTERFACE: interface 3 to alt 1 (implicit feedback source), then interface 2 to alt 1.
4. Start iso IN on 0x82 and iso OUT on 0x02. Fill each OUT packet with N×4 bytes of PCM, where N is the frame count from the matching IN packet (fallback: a 5/6 pattern).
5. On stop: both interfaces back to alt 0, release.
