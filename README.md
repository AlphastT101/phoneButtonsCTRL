# Volume Buttons

**Your volume keys died? This brings them back.**

Volume Buttons is a floating edge button for Android that replaces broken or
unresponsive hardware volume keys. An always-on overlay tab sits flush against
the left or right screen edge; tap it and a slider pops out above every app.

## Features

- **Always-on edge tab** — half-tab, flush against the screen border, 60% transparent when idle, fades further when untouched
- **Slider on tap** — vertical media-volume slider with a thin line and a dash handle (no bulky circle), haptic tick per step
- **Close without leaving** — tap the ✕ or anywhere outside the card
- **Left or right side** — drag the tab across the screen; it snaps to the nearest edge and flips its flat side and chevron automatically
- **Dark & minimal** — GitHub-dark UI, pure black background, orange accent, no animations, no clutter
- **Works everywhere** — pure `TYPE_APPLICATION_OVERLAY` + foreground service, no OEM-specific hooks; Android 8+ (API 26+)
- **Restores on boot** — with the enabled state, it tries to come back after a reboot (falls back to a tap-to-restore notification when the platform blocks it)
- **Zero tracking** — 100% offline, no accounts, no analytics, no network

## How it works

The app runs a small foreground service that keeps a transparent `FrameLayout`
overlay window pinned to the screen edge. The overlay hosts two states: a
collapsed chevron tab and an expanded volume card with a custom canvas-drawn
`VerticalVolumeSlider`. The volume buttons stream `AudioManager.STREAM_MUSIC`,
so it behaves exactly like the hardware keys for media volume.

## Permissions

| Permission | Why it's needed |
|---|---|
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`) | The overlay can't exist without it |
| **Foreground service** (`SPECIAL_USE`) | Keeps the button alive against ROM battery savers |
| **Notifications** (`POST_NOTIFICATIONS`) | Required to show "Floating volume control is active" |
| **Request ignored battery optimizations** | Optional — prevents aggressive ROMs killing the service |

If a permission is missing when you tap the toggle, the app walks you through
it directly — it deep-links straight to this app's **Display over other apps**
page (not a generic app list).

### Sideloading note (Android 14+)

Apps installed by an **untrusted installer** (file managers, browsers) can't
enable *Display over other apps* until you tap **⋮ → Allow restricted settings**
on the App info page. Installing via `adb install` or from Google Play is
treated as trusted and skips this entirely. This is Android's rule for
sideloaded overlay apps, not a bug.

## Setup

```bash
git clone <repo-url>
cd phoneButtonsCTRL
flutter pub get
flutter run            # debug, on a connected device
flutter build apk --release
```

First build downloads the Gradle distribution automatically.

**Toolchain:** Flutter 3.50+ (stable), Dart 3.14+, Android SDK (cmdline-tools),
JDK 21 (Microsoft Build of OpenJDK / Temurin). `ANDROID_HOME` and `JAVA_HOME`
must point at your SDK and JDK — `flutter doctor -v` should show all green.

**Release signing** is expected through `android/key.properties` (see
`android/key.properties.example`). The real file is git-ignored — never commit
your keystore password.

## Install the release APK

```bash
adb install VolumeButtons-release.apk
```

Or copy the APK to the phone and tap it (then follow the sideloading note
above). The toggle and one-time battery-exemption prompt live in the app.

## Project layout

```
lib/main.dart                              — the app UI (toggle + on-demand permission flow)
android/app/src/main/kotlin/.../MainActivity.kt   — MethodChannel bridge
.../VolumeOverlayService.kt                — foreground service, overlay window, drag/snap logic
.../VerticalVolumeSlider.kt                — canvas-drawn slider (line + dash handle)
.../BootReceiver.kt                        — restart on boot (with fallback notification)
```

## License

MIT — do whatever you want with it.
