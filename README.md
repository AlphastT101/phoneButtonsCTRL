# Volume Buttons

**Your volume keys died? This brings them back.**

Volume Buttons is a floating edge control for Android that replaces broken or
unresponsive hardware volume keys. A slim tab sits against the left or right
screen edge; tap it and a sound panel opens above every app.

## Features

- **Five volume streams** — Media, Call, Ring, Notifications and Alarm, each
  with a labelled horizontal slider and a live percentage
- **Smooth sliders** — the thumb follows your finger continuously, glides to the
  tapped position instead of jumping, and gives a small haptic tick per step
- **Ring or vibrate** — switch ringer mode from the panel in one tap (Silent is
  not offered because Android 16 routes it through Do Not Disturb, where it
  still vibrates on this ROM)
- **Call mute link** — muting Call also mutes Notifications and keeps them muted
  after the call stream is restored; Notifications stays locked until unmuted
- **Tap an icon to mute** — tap a stream icon to mute or unmute that stream
- **Either edge** — drag the tab across the screen; it snaps to the nearest edge
  and flips its flat side and chevron automatically
- **Readable everywhere** — the card is fully opaque when cross-window blur is
  unavailable, so no app text shows through; on devices with blur it stays
  slightly translucent for a native Pixel feel
- **Small, quiet idle tab** — 24 × 76 dp with a low idle opacity so it stays out
  of the way until you need it
- **Works everywhere** — `TYPE_APPLICATION_OVERLAY` + foreground service,
  no OEM-specific hooks; Android 8+ (API 26+)
- **Restores on boot** — with the enabled state, it comes back after a reboot
- **Zero tracking** — 100% offline, no accounts, no analytics, no network

## How it works

The app runs a small foreground service that keeps a transparent `FrameLayout`
overlay window pinned to the screen edge. The overlay hosts two states: a
collapsed chevron tab and an expanded sound card with five custom canvas-drawn
`HorizontalVolumeSlider` views. Each slider drives its own `AudioManager` stream,
so it behaves like the hardware keys for that stream.

The card background is chosen at runtime: fully opaque `#1E1F22` when
`WindowManager.isCrossWindowBlurEnabled` is false, and 95% opaque with
`FLAG_BLUR_BEHIND` when the device supports cross-window blur.

## Permissions

| Permission | Why it's needed |
|---|---|
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`) | The overlay can't exist without it |
| **Foreground service** (`SPECIAL_USE`) | Keeps the button alive against ROM battery savers |
| **Notifications** (`POST_NOTIFICATIONS`) | Required to show "Floating sound controls are active" |
| **Modify audio settings** (`MODIFY_AUDIO_SETTINGS`) | Changes stream volumes and ringer mode |
| **Vibrate** (`VIBRATE`) | Haptic ticks on the sliders |
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
lib/main.dart                                      — Pixel-blue app UI (toggle + permission flow)
android/app/src/main/kotlin/.../MainActivity.kt    — MethodChannel bridge
.../VolumeOverlayService.kt                        — foreground service, overlay window, drag/snap
.../HorizontalVolumeSlider.kt                      — canvas-drawn smooth slider
.../BootReceiver.kt                                — restart on boot
```

## License

MIT — do whatever you want with it.
