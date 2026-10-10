# MxL TV

Android app for watching over-the-air ATSC TV with Geniatech/MyGica sticks built on the
MaxLinear MxL692 tuner/demodulator (USB `1f4d:692f` A681B, `1f4d:e691` PT682C, `1f4d:692a`).
Works on phones, tablets and Android TV boxes with USB host (OTG). No root needed.

The driver is a userspace port of the Linux kernel's `mxl692.c` and `dvbsky.c`, configured as in
Forest Crossman's patch series
"[RFC PATCH 0/2] Add support for Geniatech/MyGica A681B and PT682C"
(https://lore.kernel.org/linux-media/20251001051534.925714-1-cyrozap@gmail.com/),
plus the PT682C warm-reset fix from that thread.

## Features
- Channel scan (US RF 2–36), channel names and numbers from the broadcast (PSIP VCT)
- Live playback via libVLC (software MPEG-2 decoding, so it works on phones without an MPEG-2 decoder)
- Program guide from the broadcast (EIT/ETT, about 12 hours), now/next in the channel list and player
- Signal quality (SNR) display, audio track and closed-caption switching
- Remote/D-pad: Up/Down or CH+/CH− change channel, OK shows info, digits (e.g. `24.3`) tune directly,
  GUIDE and CC keys work. Touch: swipe up/down to change channel, tap for info.

## Firmware
The MxL692 firmware is not included. In the app, tap **Firmware** and pick any of:
`dvb-demod-mxl692.fw`, the Windows driver's `cyDtv.Sys`, or the Geniatech Windows driver `.zip`.
The app finds the firmware image inside and verifies its built-in checksum.
The firmware can be extracted from Geniatech's Windows driver, for example with the patch author's
script: <https://gist.github.com/cyrozap/f07a8b9803d2e9e3d0ee7acf54843ab0>.

Prebuilt APKs are on the [Releases](../../releases) page.

## Build
Requires JDK 17 and the Android SDK (platform 36). Gradle 8.11 does not run on newer JDKs, so
point `JAVA_HOME` at a JDK 17 if it is not your default, and set `sdk.dir` in `local.properties`
(or `ANDROID_HOME`).

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleRelease
    # APKs in app/build/outputs/apk/release/

One APK per CPU type is produced (`arm64-v8a` for most current devices, `armeabi-v7a` for many
TV boxes) plus a universal APK. Release builds are signed with the debug key; use your own key to
distribute.

See [`CHANGELOG.md`](CHANGELOG.md) for how the app was built and tested, step by step.

## Layout
- `core/`   – pure Kotlin: USB bridge protocol, MxL692 driver, TS/PSIP parsing, scanner, guide store
- `app/`    – Android UI, USB host transport, libVLC player, loopback stream server
- `desktop/`– test harness running `core/` against a stick on Linux via libusb:
  `./gradlew :desktop:installDist && desktop/build/install/desktop/bin/desktop <fw> scan`
  (needs read/write access to the stick's `/dev/bus/usb/...` node)

## Limitations
- Guide text sent with ATSC Huffman compression (A/65 Annex C) is not decoded (shows blank).
- Single tuner: refreshing the guide for other frequencies interrupts playback.
- Cable (clear QAM, US Annex B, cable channels 2–134) is implemented following the kernel driver's
  QAM path, but only works on sticks whose chip reports MxL692 or MxL248. The tested 1f4d:692a stick
  (and the PT682C in the patch thread) report MxL691, which is ATSC-only: its firmware rejects the
  QAM demodulator mode, so the app disables the Cable option for it. Encrypted cable channels are
  detected and skipped.

## License
GPL-2.0 (see [`COPYING`](COPYING)), as it is derived from GPL-2.0 Linux kernel drivers.
libVLC is LGPL-2.1.
