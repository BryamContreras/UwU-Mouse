# Privileged backend composition

This Android library is the composition root of the shell-side backend. It owns
the stable `ShellMain` entry point, backend identity, supervised session/recovery
coordination and the native capture broker. Existing Kotlin packages and process
entry-point names are preserved.

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
