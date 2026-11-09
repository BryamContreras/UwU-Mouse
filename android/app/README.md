# App composition

The Android application owns the ordinary-UID control plane and composes the
platform modules; the pure mapping algorithms remain in `mapper-core`.

- `MapperApplication.attachBaseContext` binds `MapperRuntimeComposition` before
  providers, activities or services run. Binding neither opens input devices nor
  starts a mapping session. Reentry with the same composition is idempotent;
  replacing its identity within a process is rejected.
- `session/SessionController` owns the session lifecycle, the engine worker and
  its queue. It consumes `SessionEnvironment` for backend/context/capture
  capabilities and `SessionEvents` for presentation notifications. It does not
  look up concrete capture hosts, coordinators or backend registries.
- `runtime/MapperRuntimeComposition` provides the Android adapters. Device and
  presentation diagnostics are assembled on the cold control plane, not per
  movement. Injection admission continues to use the capture host's cached guard.
- `runtime/NativeSessionHost` adapts physical events and manages window/cursor
  interaction. `game/GameCoordinator` coordinates explicit profile and editor
  actions. Their rendering belongs to `presentation` and their policies to core.
- `profile/ProfileStore` and `editor/BubbleSettingsStore` supply Android storage;
  `activation` owns Android entry points over `activation-transport`.

`python tools/check_architecture.py` guards both the Gradle module boundaries and
the session controller's source dependencies. The application remains an Android
runtime adapter; the ports do not claim that it is a pure-JVM engine.

Steady smooth-camera batches can enter the engine queue directly from the native
Binder callback. `NativeMotionDispatchGate` blocks this route whenever an earlier
callback is still pending on main, so movement cannot overtake keys or button
edges. Cursor/menu/wheel input keeps its main-thread interaction path. Routes
retain capture identity, session generation and pointer-mode epoch; the worker
rejects movement from an earlier camera/cursor transition. The gate lock never
encloses a queue offer, lifecycle callback or IPC.

Original samples still reach precision filtering and nonlinear integration in
order, before MOVE delivery coalesces. Fresh privileged focus checks, per-delivery
ACKs and the recovery journal remain in the injection path. Focus reflection
accessors are reused without caching the focus result. Diagnostics expose
`motionTransport.cameraDirectBatches` and `cameraMainBatches`; these counters
confirm routing, not end-to-end game latency.
