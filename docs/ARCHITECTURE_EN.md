# Architecture and scope

This guide describes the current application. The detailed [module inventory and historical results](../ARCHITECTURE.md) are available in Spanish. Older references to accessibility describe earlier versions: current keyboard and mouse capture and mapper windows do not require an accessibility service.

```mermaid
flowchart LR
    UI[Flutter library and settings] --> Coordinator[Kotlin coordinator]
    Editor[Native editor] --> Coordinator
    Input[External keyboard and mouse] --> Capture[Authorized evdev capture]
    Capture --> Engine[Engine and bounded queue]
    Coordinator --> Engine
    Engine --> Composer[Multitouch composer]
    Composer --> Guardian[UID 2000 guardian]
    Guardian --> Worker[Injection worker]
    Worker --> Android[Authorized Android package and display]
```

## Input, windows and processes

Flutter is the control plane. Native modules handle physical samples and touch contacts without passing each event through Dart. Capture selects external devices by capability and acquires them using `EVIOCGRAB`; a lease and independent watchdog allow devices to be released if the consumer is lost. The injection worker runs separately from the guardian.

An evdev node is acquired as a whole. Optional keyboard-only capture skips combined mouse/keyboard nodes while Android owns the mouse, and uses independent keyboard nodes when available. If none are safe, the paused session and bubble remain usable without keyboard shortcuts. That capability failure is remembered until the external input inventory changes, preventing repeated unsupported capture attempts. Full mouse-and-keyboard capture remains available when mapping requires it.

Injection requires a verified session, target, display and focus. Context loss pauses actions, and injector recovery does not replay held keys or buttons. Presentation windows use Android's standard overlay permission on both the phone and external displays.

An active session interrupted by focus loss or a window change can return to the same game in neutral cursor mode after its admitted window stays stable for 600 ms and contact release is verified. Existing context observations drive at most three fresh-start attempts; this adds no polling thread or per-input recovery work. A system-cursor handoff first requires an actual context departure or window-frame change, allowing Android to manage the window. Manual pause, closure, editing, menu changes, locking, display loss or backend replacement cancel the return. A manual-pause epoch is checked atomically when arming, so a delayed return cannot undo a user's pause. Automatic return does not launch the game or restore held keys, buttons, repeat loops or camera contacts.

Calibration retains separate phone and external-display scope. At execution, the saved calibrated area is projected from its original window frame into the currently verified game window. This adaptation exists only in memory, always starts from the saved source and still passes the normal profile compiler checks. It supports position and size changes without progressively modifying the saved profile. Window metadata cannot detect a game's internal HUD rearrangement, which may require editing or recalibration. A DeX test does not establish support for every DeX mode or game.

If native acquisition detects a held physical key or button, Android keeps the devices while capture waits for release. Existing observations retry acquisition at most once a second for up to 30 seconds, without starting another engine or replaying the release. A successful return requires confirmed native mouse capture. Expiry leaves the session paused for an explicit retry; pause, stop, context loss and backend changes cancel the acquisition wait.

## Profiles, repeat and session closure

Schema 9 stores profiles, calibration, controls, macros and shortcuts. The repository validates documents and writes them atomically; the engine uses prepared configuration and does not read profile files for every movement. The editor uses a draft: Save validates and applies it, while Cancel keeps the previous profile.

Repeat can run while held or as a toggleable loop. Successive presses start and stop the loop; its timer accepts 0–86400 seconds, with zero meaning no limit. Each activation starts a new deadline. A bounded callback controls both pulses and expiry without an additional thread. Pausing, closing or entering cursor mode cancels its tasks and releases contacts. Missed periods are skipped rather than replayed as a burst.

Home offers session closure while a session exists, including blocked or pending closure. Release must be confirmed before another mapping starts. The session closes automatically once both the game task and window are confirmed gone; minimizing or losing focus preserves the session while input is paused. Automatic return is limited to the transient interruptions described above; an explicit pause remains paused. If Android cannot confirm exit, manual closure remains available.

## Activation and updates

The engine can be activated locally through Android wireless debugging, or from a computer using authorized ADB over USB or Wi-Fi. The PC need not remain connected after activation. A hub, keyboard and mouse do not run ADB themselves. See the [phone](ACTIVATE_PHONE.md) and [PC](ACTIVATE_PC.md) activation guides.

Version 0.3.4 changed the Android application ID to `dev.uwumouse.app`, which Android treats as a new app. Profiles must be exported from the old installation and imported into the new one, and permissions granted again. A compatible profile format does not transfer private data between apps. See [migration](MIGRATION_0_3_4.md).

## Performance and validation

The implementation reduces unnecessary wakeups, drawing and layout work, bounds queues and buffers, and cancels work when no longer needed. The optional FPS and battery monitor samples only while visible. The detailed [performance review](../PERFORMANCE_REVIEW.md) remains available in Spanish.

Automated tests, InputLab and observations in games have distinct scopes. InputLab checks touch sequences in its own window; an ACK does not prove an action occurred in a game or a frame was presented. Internal timing percentiles and CPU completion of `onDraw` do not measure physical mouse-to-image latency. The optional `latencyprobe` accessibility experiment is a comparison tool and is not part of the mapper.

The phone repeat test confirmed loop, timer and cancellation with 47 valid events and no pending contacts; it does not certify multi-hour stability. CI results must be checked for each release commit and its published APKs. Profiles and device logs under `evidence/` are excluded from the public repository.
