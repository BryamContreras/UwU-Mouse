# Privileged backend composition

This Android library is the composition root of the shell-side backend. It owns
the `ShellMain` entry point, backend identity, supervised session/recovery
coordination and the native capture broker. Version 0.3.4 adopts the UwU Mouse
namespace and process identities throughout the app, activators and diagnostics.

The host assembles independently replaceable capabilities:

- `capture-api` and `capture-native` provide physical input and capture leases.
- `injection-runtime` provides the touch worker and its bounded pipe transport.
- `window-context` provides cached, verified window metadata.
- `mapper-core` provides shared models and session/recovery policy.
- `injection-api` is the app-facing Binder contract and is re-exported publicly.

The ordinary app depends on this composition module and its Binder API, rather
than directly depending on the physical capture or injection implementations.
The injection worker module does not know about mouse capture or WMS discovery.
Keeping orchestration here allows a future provider to be replaced without
putting cross-module coordination inside the injector itself.

Normal worker recovery can replace a failed injector and cancel its original
stream only while the original destination remains authorized. An explicitly
rejected POINTER_UP takes a separate terminal route: stop capture, terminate all
owned workers before closing their pipes, and reconcile the unchanged journal
only after a global no-down observation. Once terminal retirement starts, that
session cannot restart workers or inject more events. Unknown touch state and
unverified key releases retain pending closure.

The focused-application response also supplies optional task-existence metadata
for automatic session closure. Enumeration runs at most once per second outside
the game's focus, requires unrestricted task access and rejects truncated or
opaque results as absence proof. This metadata never authorizes injection.
