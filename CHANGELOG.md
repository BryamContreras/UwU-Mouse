# UwU Mouse changelog

The complete historical notes in Spanish are preserved in [CHANGELOG.es.md](CHANGELOG.es.md).

## 0.3.9+47 — unreleased

- Recognize Android 16/17 idle mouse hover and retained empty display entries when confirming global contact release. Pressed contacts, pilfered pointers, incomplete inventories and unknown formats still block closure.
- Keep repeated auxiliary WindowManager summaries out of the primary window inventory so newer firmware does not invalidate otherwise complete game context.
- Preserve game-exit tracking after external display removal. Repeated fresh global task absence can close a session even when window metadata is unavailable; minimizing or moving a live game continues to pause it.
- Exercise the observed firmware formats with JVM regressions and an Android framework regression on every supported API.
- Expose bounded on-request lifecycle diagnostics to distinguish unknown task queries, preparation, stale samples and window vetoes without adding a per-input task or background process.

Validation on a physical Samsung S25 Ultra running Android 17 covered active and paused game exit in mirroring and DeX, and session closure from Home on the phone and external display. The phone laboratory also reproduced blocked-contact recovery. Original profiles were preserved. The standalone production-parser probe passed 15 checks on Android's regex implementation; local validation passed 104 Flutter and 984 JVM tests. These checks do not certify every firmware or multi-hour gaming session.

## 0.3.8+46 — 2026-11-10

- Configure custom resolution directly in the System panel: the switch unlocks the width and height fields, and the check and reset icons sit on the same row. Turning the switch off or resetting restores the previous display setting.
- Keep the System panel available while editing or after applying a display change, without authorizing game input or retaining exclusive capture while the session is stopped.
- Separate the wireless debugging and overlay permission cards in landscape.
- Add icons to System, appearance, editor, profile and activation controls; correct the close, refresh and settings glyphs. Center icons with their labels in editor and profile buttons.
- Remove the separate resolution activity and release physical input capture while editing resolution fields.

## 0.3.7+45 — 2026-11-10

- Add custom logical resolution for the game's display, including mirrored HDMI, with orientation conversion, verification and restoration of the previous override.
- Stop mapping and release capture before display changes. Preserve profiles, check the result and journal recovery after backend restart without a permanent polling process.
- Add framework coverage for changing and restoring one virtual display without changing another. Mirroring can also change the phone image; results depend on device and adapter capabilities.

## 0.3.6+44 — 2026-11-10

- Integrate Free Fire camera compensation reversibly, consume the first sample after contact confirmation and renew only the camera contact when its finite range is exhausted.
- Route individual motion samples directly and batch consecutive samples under load while preserving sample times and control barriers. Discard physical camera samples older than 100 ms and expose bounded diagnostics.
- Add synthetic InputLab checks for reversals, batches, stale samples and camera renewal with joystick input. Large turns may feel different; equal touch coordinates do not guarantee equal in-game orientation.

## 0.3.5+43 — 2026-11-10

- Fix session closure in Samsung DeX when Android reports an idle hybrid touchscreen/mouse cursor, retaining the checks for actual contacts and stopped producers.
- Allow confirmed automatic closure without a focused desktop app. Minimizing, changing focus or moving a game preserves the paused session.
- Obtain geometry from the requested display and expand main/external display and Android 14–17 framework coverage.

## 0.3.4+42 — 2026-11-09

- Change the Android application ID to `dev.uwumouse.app`. Android treats it as a new installation: export profiles first, import them into the new app and grant permissions again. See the [migration guide](docs/MIGRATION_0_3_4.md).
- Add toggleable repeat loops and an optional 0–86400 second timer while retaining the previous hold-to-repeat default and 30–5000 ms interval.
- Make session closure available from Home during active, blocked or pending sessions; automatically close after the game task and window are confirmed gone.
- Improve permission status, connection settings, cursor styles, Do Not Disturb, editor and bubble presentation.
- Reduce unnecessary capture, drawing and background work; bound latency buffers and monitor polling. These code changes do not by themselves establish a measured improvement in end-to-end latency or long-session stability.
