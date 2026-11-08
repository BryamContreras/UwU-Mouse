# Shell window context

This Android library replaces window discovery through accessibility with a
bounded metadata reader in an already authorized UID 2000 shell process.
`WindowContextProvider.snapshot()` returns a detached Android `Bundle` and performs
no IPC, device access, parsing or disk IO. `ShellWindowContextProvider` refreshes a
process-local cache on a worker every 250 ms after the preceding refresh finishes,
while consumers are requesting snapshots. Each `snapshot()` renews a three-second
demand lease using only an atomic clock value. When that lease expires, the worker
invalidates the cache and keeps its inexpensive timer without performing WMS IO.
The first request after an idle period can therefore return `valid=false` until
the next worker refresh; callers must await a fresh context before starting input.

Each refresh requests WMS `windows` and `displays` dumps through `IBinder.dumpAsync`
and pipes. Both dumps share a 500 ms reading deadline and a 4 MiB byte allowance.
Only two reusable daemon IO threads are used. A timed-out operation is closed;
while it still owns a thread, no new operation is queued. Closing the provider
invalidates the cache, closes active pipes and requests executor shutdown without
waiting for a blocked Binder transaction.
An operation publishes its result only after both the reader and Binder writer
finish and its active slot is released. Writer acceptance is checked after that
barrier, so an early EOF cannot discard an otherwise accepted result or admit the
next dump before cleanup. Unavailable reads report `dump_windows_unavailable` or
`dump_displays_unavailable` without exposing dump content.

The returned wire contains `valid`, `capturedUptimeMs`, `focusedPackage`,
`focusedDisplayId`, and `windows` (an `ArrayList<Bundle>`). Each window includes its
stable local WMS hash as `id`, category `type` (1 application, 2 IME, 3 system),
original `layoutType`, package, UID, display, derived top-first layer, frame bounds,
focus and visibility. Content bounds equal frame bounds and are explicitly marked
unverified: WMS frame metadata does not establish view-content geometry.

Only windows explicitly marked `mHasSurface=true`, `isOnScreen=true` and
`isVisible=true` are returned. Missing visibility fields, malformed bounds,
duplicate IDs, truncated recognized sections, unknown headers, inconsistent focus,
timeouts and read/decoding limits invalidate the whole snapshot. Unknown OEM
layout labels remain system windows. A focused window must join the visible
metadata by hash and display; an empty list never grants authorization. A cache
older than 750 ms returns `valid=false` and an empty list.

The raw dump is transient memory only: no titles, component names, view trees or
raw output are retained in the model, logged, written to disk or exposed in status.
The parser is Kotlin-only and has JVM tests with constructed anonymous fixtures.

WMS text is an OEM implementation detail, not a stable SDK contract. The currently
recognized grammar is Android 14/Samsung-style output. Other versions may remain
unavailable until their format is verified. Two dumps are not an atomic framework
transaction; matching identity and conservative freshness reduce mismatches but do
not prove a window did not move between observations. Callers must still apply
their target authorization, geometry and release policies. `valid` means coherent
metadata, not permission to inject or consume input.
