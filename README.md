# UwU Mouse

UwU Mouse maps a physical keyboard and mouse to touch controls on **Android 14 and later**. It runs locally on the device, with a Flutter interface and a native Kotlin/C++ mapping engine. The current version is defined in [pubspec.yaml](pubspec.yaml).

[Español](README.es.md) · [Changelog](CHANGELOG.md) · [Releases](https://github.com/BryamContreras/UwU-Mouse/releases)

## Get started

The app currently displays its controls in Spanish. The names in parentheses below are the labels shown in the app.

1. In Home (**Inicio**), open Wireless debugging (**Depuración inalámbrica**) and Display over other apps (**Mostrar sobre otras apps**) to configure the Android permissions. Green checks and red crosses show their current status. Wireless debugging can also be opened from **Ajustes → Conexión**.
2. If the engine is unavailable, open **Ajustes → Conexión → Activar motor**. Pair using Android's wireless debugging code and enter the code through the notification. Once authorized, **Reactivar sin código** can reuse the pairing. See the [on-device activation guide](docs/ACTIVATE_PHONE.md) or the [Windows/ADB activation guide](docs/ACTIVATE_PC.md).
3. Add a game and tap it to open it. Use **Mapear** to place controls and save a profile. The floating bubble provides profile selection, mapping and the control/cursor toggle.
4. While a session exists, **Cerrar sesión** appears in its Home card. The session also closes after the game's task and window are confirmed gone. Minimizing or moving the game pauses the session instead of treating it as closed.

The engine runs on the device under an authorized ADB shell identity. External input capture is managed automatically during a session; there is no separate native-capture switch. The application does not need root or an accessibility service. A USB hub alone cannot start ADB: USB activation requires a computer running ADB. Local wireless activation is an alternative. After an APK update or engine restart, close the old backend and reactivate the engine.

## Mapping and display

- Map WASD, camera movement, touch actions, M1/M2, point macros, repeat and a map/backpack cursor, with up to 24 actions and macros combined. Profiles can be exported and imported as `.uwumouse.json` files.
- **Repetir** supports intervals from 30 to 5000 ms. **Mantener en bucle** starts and stops repetition with successive presses; its optional timer accepts 0–86400 seconds, with 0 meaning no timer. Pausing, closing or leaving control mode cancels the loop and releases its touches.
- The camera uses a smooth region and preserves fractional movement. Its physical input path discards samples older than 100 ms to avoid replaying stale motion. Capture limits range from 100 to 1000 Hz or unlimited; they do not change the mouse's hardware polling rate. Internal timing metrics do not measure mouse-to-image latency.
- In the bubble, **Sistema → Resolución personalizada** exposes a switch and width/height fields. Enable it, enter the monitor dimensions and use the check icon to apply. The reset icon or turning the switch off restores the previous setting. The mapping stops before changing geometry; check calibration before playing again.
- The resolution change affects the game's logical display. In mirrored mode it can also change the phone image. Supported dimensions and results depend on the device and video adapter. In DeX the operation targets the game's display and is not redirected to the phone if unsupported.
- Phone and external-display profiles use separate calibration. Changing a DeX window's size, position or orientation may require recalibration.

## Compatibility and validation

The minimum supported version is Android 14 (API 34). CI checks Android 14–17 framework behavior and builds the same application for **arm64-v8a**, **armeabi-v7a** and **x86_64**. Automated tests do not certify every manufacturer, accessory or game. The custom resolution feature was checked on a Samsung Android 14 phone with HDMI mirroring; physical DeX testing of the latest inline controls remains outstanding. Long gaming sessions and end-to-end latency require separate physical testing.

See the English [architecture overview](docs/ARCHITECTURE_EN.md), [CI guide](docs/CI_EN.md) and [module READMEs](android/). Detailed historical measurements and compatibility contracts remain available in Spanish in the [architecture](ARCHITECTURE.md), [performance review](PERFORMANCE_REVIEW.md), [Android compatibility](docs/ANDROID_COMPATIBILITY.md) and [CI guide](docs/CI_ANDROID.md).

## Build and install

GitHub Actions builds development APKs and SHA-256 checksums. Download a matching APK from [Releases](https://github.com/BryamContreras/UwU-Mouse/releases) or a successful **Android APKs** workflow. Main-branch builds use the repository's persistent development certificate; pull requests without the signing secret use an ephemeral certificate.

The shared toolchain is specified in [android/toolchain.properties](android/toolchain.properties). On Windows, run:

```powershell
.\scripts\Build.ps1 -Release
```

This runs the architecture checks, Flutter analysis and tests, Android tests and lint, then builds and verifies the APK. To build separate APKs manually:

```sh
flutter pub get --enforce-lockfile
flutter build apk --release --split-per-abi --android-project-arg=force-version-code-ignoring-abi=true
```

For updates with the same application ID and certificate, stop the mapping session and backend, install over the existing application, and reactivate the engine. Version 0.3.4 changed the Android application ID to `dev.uwumouse.app`: Android treats it as a new app, so export profiles from 0.3.3 or earlier before switching, then import them and grant permissions again. See the [migration guide](docs/MIGRATION_0_3_4.md).

No general project license has been defined. Dependencies retain their respective licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
