# Volume Buttons

**Your volume keys died? This brings them back.**

Volume Buttons is a floating edge control for Android that replaces broken or
unresponsive hardware volume keys. A slim tab sits against the left or right
screen edge; tap it and a compact media panel opens, with a second tap for all
streams.

## Interface

- **Edge tab** — small blue half-pill, flush with the screen border, low opacity when idle
- **Compact modal (1st)** — a tall teal pill with a Ring/Vibrate button, a vertical
  Media bar, and a tune button for "more". Exactly the shape, spacing and colours
  of Android's own sound panel.
- **Full modal (2nd)** — compact rounded teal card with five labelled horizontal
  bars: Media, Call, Ring, Notification and Alarm. Each bar is two rounded pills
  with a thin handle between them and its stream icon at the right.
- **Animated transitions** — the window itself resizes from tab → compact → full
  and back with an ease-out animation; the contents fade in quickly once the
  resize completes. Slider motion uses ease-in-out.
- **Smooth sliders** — dragging follows the finger continuously; tapping a track
  glides the handle to the tapped position instead of jumping. Haptic tick per step.
- **Call mute link** — muting Call also mutes Notification and keeps it muted
  after the call stream is restored.
- **Ring / Vibrate** — switch the ringer mode from the compact modal's bell button.
  Silent is intentionally not offered (Android 16 routes it through Do Not
  Disturb and it still vibrates on this ROM).
- **No visible notification** — the foreground service channel is set to
  `IMPORTANCE_NONE`, so the app stays alive without a notification in the shade
  or status bar. (Android's system-level "displaying over other apps" notice is
  controlled by the OS and cannot be suppressed by an app.)

## Permissions

| Permission | Why it's needed |
|---|---|
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`) | The overlay can't exist without it |
| **Foreground service** (`SPECIAL_USE`) | Keeps the button alive against ROM battery savers |
| **Modify audio settings** (`MODIFY_AUDIO_SETTINGS`) | Changes stream volumes and ringer mode |
| **Vibrate** (`VIBRATE`) | Haptic ticks on the sliders |
| **Request ignored battery optimizations** | Optional — prevents aggressive ROMs killing the service |

If a permission is missing when you tap the toggle, the app deep-links straight
to this app's **Display over other apps** page.

### Sideloading note (Android 14+)

Apps installed by an **untrusted installer** (file managers, browsers) can't
enable *Display over other apps* until you tap **⋮ → Allow restricted settings**
on the App info page. Installing via `adb install` or from Google Play is
treated as trusted and skips this entirely.

## Setup

```bash
git clone <repo-url>
cd phoneButtonsCTRL
flutter pub get
flutter run            # debug, on a connected device
flutter build apk --release
```

**Toolchain:** Flutter 3.50+ (stable), Dart 3.14+, Android SDK (cmdline-tools),
JDK 21. `ANDROID_HOME` and `JAVA_HOME` must point at your SDK and JDK.

**Release signing** is expected through `android/key.properties` (see
`android/key.properties.example`). The real file is git-ignored — never commit
your keystore password.

## Install the release APK

```bash
adb install build/app/outputs/flutter-apk/app-release.apk
```

## Project layout

```
lib/main.dart                                      — app UI (toggle + permission flow)
android/app/src/main/kotlin/.../MainActivity.kt    — MethodChannel bridge
.../VolumeOverlayService.kt                        — foreground service, three-state overlay, resize animation
.../HorizontalVolumeSlider.kt                      — system-style horizontal bar
.../VerticalVolumeSlider.kt                        — system-style vertical bar (compact modal)
.../BootReceiver.kt                                — restart on boot
```

## License

MIT — do whatever you want with it.
