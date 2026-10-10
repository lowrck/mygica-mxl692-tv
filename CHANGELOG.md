# Changelog

All major steps, in the order they were done.

## 1. Driver port to userspace (`core/`)

Android apps cannot load kernel modules, so the Linux driver logic was ported to Kotlin and talks to
the stick through the Android USB host API. No root is needed. The sources are the kernel's
`mxl692.c` and `dvbsky.c`, configured as in the RFC series "Add support for Geniatech/MyGica A681B
and PT682C".

- **`Dvbsky.kt`, the bridge protocol.**
  - Bulk command `0x08` writes I²C, `0x09` reads it, and `0x0e` sets a GPIO.
  - `0x37` turns streaming off; `0x37` followed by `0x36 03` turns it on.
  - One I²C transfer carries at most 60 bytes.
- **Reset before init.** The MxL69x is reset with the GPIO `0x80` sequence before every
  initialization. This also covers the PT682C warm-restart problem reported in the patch thread:
  the generic `gpio(0x04, 1)` path does not reset a chip that is already running firmware, which
  leaves its mailbox replies off by one command.
- **USB IDs.** `1f4d:692f` (A681B), `1f4d:e691` (PT682C) and `1f4d:692a` are supported. The last
  uses the same endpoints (control `0x01`/`0x81`, stream `0x82`) and the same configuration.
- **`Mxl692.kt`, a line-by-line port of `mxl692.c`** with the configuration from the patch series:
  - crystal: calibration on, sharing off
  - MPEG output: parallel, byte sync, 3-wire, 13.5 MHz clock, 4X pad drive
- **Wire format details reproduced exactly:**
  - "Mem" accesses (`0xFC`/`0xFB`) carry the address and value little-endian.
  - "Op" messages are an 8-byte header plus a payload whose multi-byte fields are big-endian. On the
    wire the whole message is byte-reversed in 4-byte groups.
  - The checksum is the sum of big-endian words XOR `0xDEADBEEF`.
  - Firmware is written in chunks of at most 52 bytes.
- **Two deliberate differences from the kernel:**
  - The sequence number wraps within 1–255. A response sequence number of 0 means "not ready", so 0
    is never used.
  - A response that arrives on the 40th poll is accepted. The kernel's loop reports it as a timeout.

## 2. Stream handling (`core/`)

- **TS layer.** `TsSync` realigns the USB byte stream to 188-byte packets. `SectionAssembler`
  rebuilds PSI sections and checks their CRC-32/MPEG-2.
- **PSIP parsing.** `PsipCollector` reads:
  - the PAT and PMT
  - the ATSC MGT, TVCT/CVCT (channel numbers and names), EIT (schedule) and ETT (descriptions)
  - the STT (GPS-UTC offset for event times)

  Multiple-string structures are decoded; HTML entities that some stations embed are unescaped.
- **`ProgramFilter`.** Extracts one program from the 19.4 Mbit/s multiplex (its PMT, PCR and
  elementary streams) and writes a new single-program PAT with a correct CRC.
- **Scanner.** `Scanner` tunes each RF channel, waits for lock, collects the tables and guide data,
  and builds the channel list (from the VCT, falling back to the PAT). `EpgStore` merges and prunes
  guide events.

## 3. Hardware verification on Linux (`desktop/`)

- A desktop harness runs the same `core/` code against the stick through libusb (usb4java). This
  verified the port on real hardware before any Android code was written:
  - firmware upload: chip MxL691, firmware 1.1.1.3_RC6, about 1.8 s
  - lock at about 25 dB SNR
  - the full multiplex at about 19.4 Mbit/s
  - channel names and 12-hour guides with descriptions
  - a filtered single-program recording that ffmpeg decodes as 720p MPEG-2 with two AC-3 tracks

## 4. Android app (`app/`)

- **USB access.** Control commands use `bulkTransfer`. The TS stream keeps 16 asynchronous
  `UsbRequest`s of 16 KiB queued on endpoint `0x82` so the stick's FIFO never overflows.
- **Playback.** A loopback HTTP server hands the filtered stream to libVLC 3.7.7. libVLC
  software-decodes MPEG-2 and AC-3, which matters on phones that have no MPEG-2 hardware decoder;
  hardware decoding is used where available. Interlaced material is deinterlaced automatically.
- **Threading.** `TvEngine` runs all tuner I/O on a single thread. It handles USB permission,
  firmware loading, scanning, playback, signal polling, and live guide updates while watching. If a
  channel has no signal it retries the tune.
- **Screens.**
  - **Channel list:** now/next with progress.
  - **Scan:** with an option to collect the guide while scanning.
  - **Player:** overlay with channel, current and next program, progress and signal, plus audio
    track and caption switching.
  - **Guide:** two panes, channels on one side and the selected channel's schedule with
    descriptions on the other.
- **Input.**
  - **Remote:** Up/Down and CH± change channel, OK shows the overlay, digits such as `24.3` tune
    directly, and the GUIDE, CAPTIONS and AUDIO keys work.
  - **Touch:** swipe up or down to change channel, tap to show the overlay. In the guide, the first
    tap selects a channel and the second plays it.
- **Firmware import.** Accepts `dvb-demod-mxl692.fw`, `cyDtv.Sys`, or the driver `.zip`. Instead of
  relying on a fixed offset, it searches the file for the firmware header and validates the
  embedded length and checksum, so other driver versions work too.
- **Android TV.** Leanback launcher entry and banner, D-pad focus states. The USB device filter for
  all three IDs lets Android offer to open the app when a stick is plugged in.
- **APKs.** One per ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) plus a universal APK.

## 5. Fixes found while testing on a phone (Samsung, Android 17, arm64)

- **Crash on launch.** Buttons using the `Btn` style had no layout width or height; the style now
  supplies them.
- **No playback.** `InetAddress.getLoopbackAddress()` is `::1` on Android, while libVLC connected to
  `127.0.0.1`. The server now binds to `127.0.0.1` explicitly.
- **Content under the status bar.** Android 15+ draws apps edge-to-edge, so the screens now pad
  their content by the system-bar and display-cutout insets.
- **Black picture with a weak signal.** Diagnostic counters were added: transport-error and
  continuity-error counts for the whole multiplex, plus server drop counts. They showed the losses
  matched packets the demodulator itself had flagged as corrupt. Nothing was dropped in the app.
  With a better antenna position (SNR about 26 dB) playback was clean: 720p video, 48 kHz stereo,
  about 32% CPU with software decoding, and channel changes in under 4 s.
- **Build.** libVLC 3.7.7 pulls in the Kotlin 2.2 standard library, which the Kotlin 2.0 compiler
  cannot read, so the project moved to Kotlin 2.2.21 and the `compilerOptions` DSL.
- **Smaller fixes.**
  - Picking a channel in the guide reuses an existing player instead of stacking a new one.
  - The empty signal-bar glyph is replaced by a dimmed filled bar, for fonts that lack `▯`.

## 6. Cable (clear QAM) support

- **Ported** the kernel's QAM path:
  - Annex B, automatic 64/256-QAM detection, symbol rates 5.057 and 5.361 MSym/s
  - `QAM_PARAMS_SET`, `QAM_RESTART_SET`, and QAM status and error counters
- **Cable channel plan.** Added the US cable EIA/CEA-542 "STD" plan, channels 2–134; channels above
  134 are beyond the chip's 858 MHz limit.
- **Encrypted channels.** They are detected from the `transport_scrambling_control` bits and
  skipped.
- **Mixed sources.** Antenna and cable channels can coexist in the list; scanning one source keeps
  the other's channels.
- **Hardware finding.** The tested stick's chip reports **MxL691**, the ATSC-only variant. Its
  firmware rejects `DEVICE_DEMODULATOR_TYPE_SET` for QAM with status 5, and the PT682C in the patch
  thread reports MxL691 as well. Antenna reception is unaffected by the rejected QAM attempts.
- **Handling.** The driver raises `UnsupportedModulationException`, the scan stops at the first
  refusal, and the Cable option is disabled when the chip is not an MxL692 or MxL248.
