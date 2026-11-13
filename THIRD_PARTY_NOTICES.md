# Third-party notices

Move Bridge itself is licensed under the Apache License 2.0 (see `LICENSE`). It builds on the following open-source work. Thank you to their authors.

| Component | Used for | License |
|---|---|---|
| [libusb](https://libusb.info) 1.0.27 (source in `app/src/main/cpp/libusb`) | Talking to the Move's USB audio interfaces in Bridge mode | LGPL-2.1-or-later (`app/src/main/cpp/libusb/COPYING`) |
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) 3.1.1 | Pairing with, and connecting to, the phone's own Wireless debugging | GPL-3.0-or-later or Apache-2.0; used here under Apache-2.0 |
| [Conscrypt](https://github.com/google/conscrypt) 2.5.3 | TLS for that connection | Apache-2.0 |
| [Bouncy Castle](https://www.bouncycastle.org) 1.81 | Creating the app's own self-signed certificate for that connection | Bouncy Castle License (MIT-style) |
| [AndroidX Annotation](https://developer.android.com/jetpack/androidx/releases/annotation) 1.9.1 | `@RequiresApi` | Apache-2.0 |
| [Kotlin standard library](https://kotlinlang.org) | The app's language runtime | Apache-2.0 |

## Bouncy Castle License
```
Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

## Code adapted from other projects
- `shell/ShellIdentity.kt` and `shell/ShellAudio.kt` follow the approach of [scrcpy](https://github.com/Genymobile/scrcpy) (its `Workarounds` and `AudioPlaybackCapture` classes), which is licensed under Apache-2.0 (copyright Genymobile and Romain Vimont).

## libusb and the LGPL
libusb is compiled from the source in this repository and linked into the app's native library. Because the complete source of Move Bridge is published here, you can rebuild the app with a changed or newer libusb: replace the folder `app/src/main/cpp/libusb` and build as described in the README.
