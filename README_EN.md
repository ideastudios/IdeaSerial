<p align="center">
  <img src="docs/images/banner.svg" alt="IdeaSerial" width="720"/>
</p>

# IdeaSerial

[![Release](https://img.shields.io/github/v/release/ideastudios/IdeaSerial?display_name=tag)](https://github.com/ideastudios/IdeaSerial/releases)
[![Build](https://github.com/ideastudios/IdeaSerial/actions/workflows/build.yml/badge.svg)](https://github.com/ideastudios/IdeaSerial/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-3DDC84)

[中文](README.md) | [English](README_EN.md)

> Android multi-port serial debugging tool: on-board serial ports (root) and USB OTG serial adapters (root-free), 4 ports with tabbed UI and color-coded TX/RX logs.

## ✨ Features

| Feature | Description |
|---|---|
| 🔌 Dual serial drivers | On-board `/dev/ttyS*` (root) + USB OTG (CH340/CH341, CP210x, FTDI, PL2303, CDC/ACM — no root) |
| 🗂 Tabbed 4-port UI | COM A–D pages, ports keep receiving in background, unread-frame badges |
| ⚙️ Full port settings | Baud 50–4000000, data bits 5–8, stop bits 1/2, parity (none/odd/even/mark/space), flow control (RTS/CTS, XON/XOFF) |
| 🎨 Color-coded logs | TX in teal, RX default, millisecond timestamps, long-press to copy/share |
| ⚡ Quick commands | Named command library, tap to send, long-press to edit, Hex mode & auto newline |
| 🔁 Auto send | Per-port toggle and interval (ms), live content updates |
| 📈 Byte counters | Live RX/TX byte statistics per port |
| 📄 Send files | Chunked file sending (chunk size + interval), cancellable progress |
| 💾 Log export | Save current page log to txt |
| ⚡ High-baudrate ready | Blocking reads + batched UI refresh, no frame loss at 921600+ |
| 🌓 Dark mode | Material 3 DayNight, Android 13+ themed (monochrome) icon |

## 📥 Download

Grab `IdeaSerial-vX.Y.Z.apk` (with SHA256) from [Releases](https://github.com/ideastudios/IdeaSerial/releases), or pick up CI builds from [Actions](https://github.com/ideastudios/IdeaSerial/actions).

## 🖼 Screenshots

> Device screenshots coming soon (PRs welcome).

## 🛠 Building from source

Requirements: Android Studio Chipmunk+ (AGP 7.1), JDK 17, NDK & CMake 3.18.1, MinSdk 28 / TargetSdk 34.

```bash
git clone https://github.com/ideastudios/IdeaSerial.git
cd IdeaSerial
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

For signed release builds, create `keystore.properties` in the project root (git-ignored, never commit it):

```properties
storeFile=../your-keystore.jks
storePassword=xxx
keyAlias=your-alias
keyPassword=xxx
```

## 🔐 Permissions

- **USB serial**: root-free. Android prompts for USB permission when the adapter is plugged in.
- **On-board serial**: requires root (`chmod 666` via `su`). Use USB mode on non-rooted devices.

## 🙏 Credits

- [android-serialport-api](https://code.google.com/archive/p/android-serialport-api/) (Cedric Priscal, Apache License 2.0) — serial JNI foundation
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) — USB serial drivers
- ComAssistant by benjaminwan — UI interaction reference

## 🗺 Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md).

## 📄 License

[MIT](LICENSE) © IdeaSerial Contributors
