# Resona

**Resona** is a system-wide equalizer for Android. It applies audio processing to the phone's *global audio mix*, so the sound profile follows every app — music, games, video — instead of living inside a single player.

## Goal

Give users a single, always-on audio enhancement layer with:

- A **10-band graphic equalizer** (31 Hz – 16 kHz, ±15 dB, 0.1 dB steps)
- **Bass boost** (80 Hz center, up to +15 dB)
- **Loudness** enhancement (up to +20 dB)
- An optional **limiter** to protect the output
- Separate **speaker / headphone** configurations, each with 3 profile slots
- **Dynamic volume profiles**: automatically switches profiles based on the system volume level
- Portable **`.resona` profiles and full backups**: share as a file, a share-sheet message, or a plain Base64 code (`resona:...`)

## How it works

| Layer | Detail |
| --- | --- |
| Audio engine (`AudioEffectsEngine.kt`) | `android.media.audiofx.DynamicsProcessing` attached to **session 0** (the global mix) as a 10-band pre-EQ, plus a classic `LoudnessEnhancer` and an integrated limiter. Gains are ramped over ~480 ms to avoid clicks. |
| Service (`EqService.kt`) | Foreground service of type `specialUse` that keeps the effects alive, recreates them only when audio actually starts (via `AudioManager.AudioPlaybackCallback`), re-attaches after 30 min of silence, and detects the current output (speaker vs. wired/USB/A2DP/BLE headphones). |
| Persistence (`ProfileStore.kt`, `BootReceiver.kt`) | Profiles and settings live in SharedPreferences; the service restarts automatically after a reboot and after a system-killed restart (`START_STICKY`). |
| UI (`MainActivity.kt`, Compose) | Single scrollable screen: status card, setup checklist, profile/slot pickers, the EQ curve + band rows, bass/loudness, dynamic volume, share/backup, and an advanced section with limiter and debug log. |

> **Runtime requirement:** the effects are built on `DynamicsProcessing`, which is available from **Android 14 (API 34)** onwards. On older devices the app still runs but the effects cannot be created.

## Requirements

| Item | Value |
| --- | --- |
| Minimum SDK | **28** (Android 9) |
| Target / Compile SDK | **37** |
| Runtime for effects | **Android 14+** (API 34) |
| Language | Kotlin, Jetpack Compose (Material 3) |
| Build | Android Studio (or `./gradlew assembleDebug` / `assembleRelease`) |

## Permissions

| Permission | Why |
| --- | --- |
| `MODIFY_AUDIO_SETTINGS` | Apply the equalizer and audio effects system-wide. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the effects alive with a low-importance notification. |
| `POST_NOTIFICATIONS` | Show the "Resona active" notification (Android 13+). |
| `RECEIVE_BOOT_COMPLETED` | Restart the service after a reboot. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent the OS from killing the service on battery-saving devices. |

## Project layout

```text
app/src/main/java/com/jony/resona/
├── AudioEffectsEngine.kt   # DynamicsProcessing pre-EQ + bass + loudness + limiter (session 0)
├── EqService.kt            # specialUse foreground service; lifecycle, callbacks, notifications
├── BootReceiver.kt         # Relaunches the service on BOOT_COMPLETED / QUICKBOOT_POWERON
├── Output.kt               # Speaker vs. headphones detection (wired, USB, A2DP, BLE)
├── Profile.kt              # Profile model + JSON (resona_profile)
├── ProfileStore.kt         # SharedPreferences persistence, slots, dynamic-volume state
├── ProfileIO.kt            # .resona import/export: file (SAF), share sheet, Base64 code
├── Backup.kt               # Full backup format (resona_backup): profiles + settings
├── ImportDialog.kt         # Import UI: choose output + slot; full-backup restore dialog
├── MainActivity.kt         # Single-screen Compose UI
├── SetupCard.kt            # Notifications / battery optimization / MIUI autostart checklist
├── ResonaTheme.kt          # Dark Material 3 theme and palette
├── UiComponents.kt         # Panel, ChoiceRow, StepRow, EqCurve, …
└── DebugLog.kt             # Persistent on-device log (last 40 lines, shown in the UI)
```

## Profile format (`.resona`)

Plain JSON with a format discriminator:

```json
{
  "format": "resona_profile",
  "version": 1,
  "name": "My profile",
  "bands": [0.0, 1.5, -2.0, 0.0, 0.0, 0.5, 1.0, 2.0, 3.0, 2.5],
  "bassOn": true, "bassPct": 40,
  "loudOn": false, "loudPct": 0,
  "limiterOn": true
}
```

A full backup uses `"format": "resona_backup"` and carries both output groups (3 slots each), the active slots, and the dynamic-volume settings. Files can be opened from the Android "Share" sheet (`application/x-resona-profile`, `.resona` extension) or pasted as a `resona:<Base64>` code.

## Setup notes

The **Setup card** in the app walks through the three things that commonly break a background audio service:

1. **Notifications** (Android 13+) — required for the foreground service.
2. **Battery optimization** — excluded so the OS doesn't kill the service; there is a shortcut straight to Xiaomi's power settings.
3. **MIUI autostart** — checked (best-effort) so the service survives reboots on Xiaomi devices.

## License

Proprietary — all rights reserved.
